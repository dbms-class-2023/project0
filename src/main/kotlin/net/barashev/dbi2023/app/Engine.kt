/*
 * Copyright 2023 Dmitry Barashev, JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 *
 */
package net.barashev.dbi2023.app

import net.barashev.dbi2023.*
import java.util.function.Function

typealias TableRecordParser = (ByteArray) -> Record3<Any, Any, Any>
typealias AttributeValueParser = Function<ByteArray, Comparable<Any>>

class QueryExecutorException: Exception {
    constructor(message: String): super(message)
    constructor(message: String, cause: Throwable): super(message, cause)
}

class QueryExecutor(
    private val storageAccessManager: StorageAccessManager,
    private val pageCache: PageCache,
    private val tableRecordParsers: Map<String, TableRecordParser>,
    private val attributeValueParsers: Map<String, AttributeValueParser>
) {
    fun execute(plan: QueryPlan): JoinSpec {
        val temporaryTables = mutableListOf<String>()
        return try {
            doExecute(plan, temporaryTables)
        } finally {
            temporaryTables.forEach {
                storageAccessManager.deleteTable(it)
            }
        }
    }
    private fun doExecute(plan: QueryPlan, temporaryTables: MutableList<String>): JoinSpec {
        val joinStack = ArrayDeque(plan.joinTree)
        var result: JoinSpec? = null
        while (joinStack.isNotEmpty()) {
            val joinNode = joinStack.removeFirst()
            val leftSpec = result?.let {
                // If we already have a prefix sequence of joins, we replace a table name in the left operand of
                // the next pair with a name of a temporary table which holds the intermediate output.
                JoinSpec(it.tableName, joinNode.leftTable.attribute).also { mergedSpec ->
                    joinNode.leftTable.filter?.let {
                        mergedSpec.filterBy(it)
                    }
                }

            } ?: joinNode.leftTable

            //println("left=$leftSpec right=${joinPair.second}")
            val leftOperand = filter(leftSpec, temporaryTables).asJoinOperand()
            val rightOperand = filter(joinNode.rightTable, temporaryTables).asJoinOperand()
            //println("left filtered=$leftOperand right filtered=$rightOperand")
            val innerJoin = createJoinOperation(JoinAlgorithm.HASH).fold(
                onSuccess = { Result.success(it) },
                onFailure = { createJoinOperation(JoinAlgorithm.NESTED_LOOPS) }
            ).fold(
                onSuccess = { Result.success(it) },
                onFailure = {createJoinOperation(JoinAlgorithm.MERGE) }
            ).onFailure {
                throw QueryExecutorException("Can't find working join algorithm", it)
            }
            val joinOutput = innerJoin.getOrThrow().join(leftOperand, rightOperand)

            val outputTableName = "${leftOperand.tableName},${rightOperand.tableName}".also {
                if (joinStack.isNotEmpty() || plan.filters.isNotEmpty()) {
                    // We will not add the last intermediate output to the list because we need it to fetch
                    // the whole result.
                    temporaryTables.add(it)
                }
            }
            val outputTableOid = storageAccessManager.createTable(outputTableName)
            TableBuilder(storageAccessManager, pageCache, outputTableOid).use { outputTableBuilder ->
                joinOutput.forEach { pair ->
                    outputTableBuilder.insert(ByteArray(pair.first.size + pair.second.size).also {
                        pair.first.copyInto(it, 0)
                        pair.second.copyInto(it, pair.first.size)
                    })
                }
            }
            result = JoinSpec(outputTableName, "")
        }
        result = result?.let {
            var filterResult = it
            plan.filters.forEachIndexed { idx, filter ->
                //println("---- join spec : $filterResult ----")
                filterResult.filter = filter
                filterResult = filter(filterResult, if (idx == plan.filters.size - 1) mutableListOf() else temporaryTables)
            }
            filterResult
        }

        val result2 = result ?: executeSelect(plan, temporaryTables)
        return result2?.also {
            it.close = { storageAccessManager.deleteTable(it.tableName) }
            temporaryTables.remove(it.tableName)
        } ?: error("Join clause seems to be empty")
    }

    private fun executeSelect(plan: QueryPlan, temporaryTables: MutableList<String>): JoinSpec? =
        if (plan.filters.size == 1) {
            plan.filters.first().let {filterSpec ->
                filter(JoinSpec(filterSpec.tableName, "").also { it.filter = filterSpec }, temporaryTables)
            }
        } else null



    private fun filter(joinSpec: JoinSpec, temporaryTables: MutableList<String>): JoinSpec {
        val filter = joinSpec.filter ?: return joinSpec
        // println("filter: input=$joinSpec")
        val valueParser = buildJoinAttrFxn(JoinSpec(joinSpec.tableName, filter.attribute))
        val outputTableName = "@${filter.attributeName},${joinSpec.tableName}".also {
            temporaryTables.add(it)
        }
        val outputTable = storageAccessManager.createTable(outputTableName)
        TableBuilder(storageAccessManager, pageCache, outputTable).use { builder ->
            val filteredRecords: Iterable<ByteArray> =
                when (filter.accessMethod) {
                    TableAccessMethod.INDEX_SCAN -> {
                        val attributeType = attributeTypes[filter.attribute]
                            ?: throw QueryExecutorException("Unknown type of attribute ${filter.attribute}")
                        val attributeValueParser = attributeValueParsers[filter.attribute]
                            ?: throw QueryExecutorException("Can't find parser for index key ${filter.attribute}")
                        storageAccessManager.createIndexScan(joinSpec.tableName, filter.attributeName, attributeType, attributeValueParser) {
                            if (filter.op.test(valueParser.apply(it), filter.attributeValue)) {
                                it
                            } else ByteArray(0)
                        }?.byEquality(filter.attributeValue, attributeValueParser)
                            ?: throw QueryExecutorException("Can't create index scan for ${filter.attribute}")
                    }
                    TableAccessMethod.FULL_SCAN -> {
                        storageAccessManager.createFullScan(joinSpec.tableName).records {
                            if (filter.op.test(valueParser.apply(it), filter.attributeValue)) {
                                it
                            } else ByteArray(0)
                        }
                    }
                }
            filteredRecords.forEach {
                if (it.isNotEmpty()) {
                    builder.insert(it)
                }
            }
        }

        return JoinSpec(outputTableName, joinSpec.attributeName)
    }

    private fun createJoinOperation(method: JoinAlgorithm): Result<InnerJoin> =
        try {
            Operations.innerJoinFactory(storageAccessManager, pageCache, method)
        } catch (ex: NotImplementedError) {
            Result.failure(ex)
        }

    private fun buildJoinAttrFxn(joinSpec: JoinSpec): AttributeValueParser {
        val (table, attribute) = joinSpec
        val joinedTables = table.split(",").filter { !it.startsWith("@") }.toList()
        return Function {
            val recordBytes = parseJoinedRecord(it, joinedTables, tableRecordParsers)
            val attributeTable = attribute.split(".")[0]
            val attrValueParser = attributeValueParsers[attribute] ?: throw IllegalStateException("No parser for attribute $attribute")
            val tableBytes = recordBytes[attributeTable] ?: throw IllegalStateException("Not found bytes for table $attributeTable")
            attrValueParser.apply(tableBytes)
        }
    }

    private fun JoinSpec.asJoinOperand(): JoinOperand<Comparable<Any>> = JoinOperand(tableName, buildJoinAttrFxn(this))
}

fun parseJoinedRecord(bytes: ByteArray, tableNames: List<String>, tableRecordParsers: Map<String, TableRecordParser>): Map<String, ByteArray> {
    var suffixStart = 0
    val result = mutableMapOf<String, ByteArray>()
    tableNames.forEach {table ->
        val suffix = bytes.copyOfRange(suffixStart, bytes.size)
        val parser = tableRecordParsers[table] ?: throw IllegalStateException("No parser for table $table")
        val record = parser(suffix)
        val recordAsBytes = record.asBytes()
        result[table] = recordAsBytes
        suffixStart += recordAsBytes.size
    }
    return result
}

