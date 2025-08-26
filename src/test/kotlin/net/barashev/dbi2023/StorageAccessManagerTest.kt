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

package net.barashev.dbi2023

import net.barashev.dbi2023.catalog.CatalogPageFactoryImpl
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StorageAccessManagerTest {
    lateinit var storage: Storage
    lateinit var directoryStorage: Storage

    @BeforeEach
    fun setUp() {
        //storage = createHardDriveEmulatorStorage()
        directoryStorage = createHardDriveEmulatorStorage(CatalogPageFactoryImpl())
        //directoryStorage = createMappedFileStorage(factory = CatalogPageFactoryImpl())
        storage = directoryStorage
    }

    @Test
    fun `test table is missing`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        assertThrows<AccessMethodException> {
            catalog.createFullScan("qwerty").records { error("Not expected to be here") }
        }
    }

    @Test
    fun `create table`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        catalog.createTable("table1")
        val fullScan = catalog.createFullScan("table1").records { error("Not expected to be here ")}
        assertEquals(listOf(), fullScan.toList())
    }

    @Test
    fun `add table page`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val tableOid = catalog.createTable("table1")
        cache.getAndPin(catalog.addPage(tableOid)).use { dataPage ->
            dataPage.putRecord(Record2(intField(42), stringField("Hello world")).asBytes())
        }

        val fullScan = catalog.createFullScan("table1").records {
            Record2(intField(), stringField()).fromBytes(it)
        }
        assertEquals(listOf(Record2(intField(42), stringField("Hello world"))), fullScan.toList())
    }

    @Test
    fun `add two tables`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val table1Oid = catalog.createTable("table1").also {
            cache.getAndPin(catalog.addPage(it)).use { dataPage ->
                dataPage.putRecord(Record2(intField(42), stringField("Hello world")).asBytes())
            }
        }
        val table2Oid = catalog.createTable("table2").also {
            cache.getAndPin(catalog.addPage(it)).use { dataPage ->
                dataPage.putRecord(Record2(stringField("Another brick in the wall"), booleanField(true)).asBytes())
            }
        }
        assertNotEquals(table1Oid, table2Oid)
        assertEquals(1, catalog.createFullScan("table1").records {
            Record2(intField(), stringField()).fromBytes(it)
        }.toList().size)
        assertEquals(1, catalog.createFullScan("table2").records {
            Record2(stringField(), booleanField()).fromBytes(it)
        }.toList().size)
    }

    @Test
    fun `iterate over the same full scan twice`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        catalog.createTable("table1").also {oid ->
            cache.getAndPin(catalog.addPage(oid)).use { dataPage ->
                (1..20).forEach {
                    dataPage.putRecord(Record2(intField(it), stringField("Hello world")).asBytes())
                }
            }
        }
        val fullScan = catalog.createFullScan("table1").records {
            Record2(intField(), stringField()).fromBytes(it)
        }
        assertEquals((1..20).toList(), fullScan.map { it.value1 }.toList())
        assertEquals((1..20).toList(), fullScan.map { it.value1 }.toList())
    }

    @Test
    fun `iterate over table pages`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val tableOid = catalog.createTable("table1")
        (1..10).forEach {
            cache.getAndPin(catalog.addPage(tableOid)).use { dataPage ->
                dataPage.putRecord(Record2(intField(it), stringField("Hello world")).asBytes())
            }
        }
        assertEquals(10, catalog.createFullScan("table1").pages().count())
        assertEquals((1..10).toList(),
            catalog.createFullScan("table1").records {
                Record2(intField(), stringField()).fromBytes(it)
            }.map { it.value1 }.toList() )
    }

    @Test
    fun `page count`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val tableOid = catalog.createTable("table1")

        assertEquals(0, catalog.pageCount("table1"))
        catalog.addPage(tableOid)
        assertEquals(1, catalog.pageCount("table1"))
        catalog.addPage(tableOid, 10)
        assertEquals(11, catalog.pageCount("table1"))
    }

    @Test
    fun `add 3000 pages`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val tableOid = catalog.createTable("table1")

        catalog.addPage(tableOid, 3000)
        assertEquals(3000, catalog.pageCount("table1"))
    }

    @Test
    fun `delete table`() {
        val cache = SimplePageCacheImpl(storage, 20)
        val catalog = SimpleStorageAccessManager(cache, directoryStorage)
        val tableOid = catalog.createTable("table1")

        assertTrue(catalog.tableExists("table1"))
        catalog.addPage(tableOid)
        assertEquals(1, catalog.pageCount("table1"))

        catalog.deleteTable("table1")
        assertFalse(catalog.tableExists("table1"))
        assertThrows<AccessMethodException> {  catalog.pageCount("table1") }
        assertThrows<AccessMethodException> {  catalog.addPage(tableOid) }

        val tableOid2 = catalog.createTable("table2")
        assertNotEquals(tableOid, tableOid2)
    }
}