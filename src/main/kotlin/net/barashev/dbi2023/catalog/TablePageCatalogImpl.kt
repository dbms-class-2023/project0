package net.barashev.dbi2023.catalog

import net.barashev.dbi2023.DEFAULT_DISK_PAGE_SIZE
import net.barashev.dbi2023.DiskPage
import net.barashev.dbi2023.DiskPageFactory
import net.barashev.dbi2023.DiskPageImpl
import net.barashev.dbi2023.FullScanImpl
import net.barashev.dbi2023.MAX_ROOT_PAGE_COUNT
import net.barashev.dbi2023.NAME_SYSTABLE_OID
import net.barashev.dbi2023.Oid
import net.barashev.dbi2023.OidNameRecord
import net.barashev.dbi2023.OidPageidRecord
import net.barashev.dbi2023.PageCache
import net.barashev.dbi2023.PageId
import net.barashev.dbi2023.Record2
import net.barashev.dbi2023.Record3
import net.barashev.dbi2023.TablePageDirectory
import net.barashev.dbi2023.booleanField
import net.barashev.dbi2023.intField
import net.barashev.dbi2023.stringField
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.use

/**
 * This catalog implementation maps up to MAX_TABLE_COUNT tables to their pages.
 *
 * A table catalog is a list of OidPageidRecord records, where Oid is the table OID and PageId is an identifier of a
 * disk page that contains the table's data.
 *
 * Every table catalog is stored as a linked list of disk pages. The head page ID is equal to the table OID.
 * A pointer to the next page in the list is stored in the header record.
 *
 * Zero page is a special page that stores a mapping of table names to table OIDs, as well as an identifier of the
 * first free data page.
 */
class TablePageCatalogImpl(private val directoryCache: PageCache): TablePageDirectory {
    private var nextCatalogPageId = -1
        set(value) {
            val updateHeader = field != -1
            field = value
            if (updateHeader) {
                saveHeader()
            }
        }
    private var nextDataPageId = -1
        set(value) {
            val updateHeader = field != -1
            field = value
            if (updateHeader) {
                saveHeader()
            }
        }

    private fun saveHeader() {
        directoryCache.getAndPin(NAME_SYSTABLE_OID).use { page ->
            ZeroPageHeader.read(page).let {
                ZeroPageHeader(it.directorySize, nextCatalogPageId, nextDataPageId).write(page)
            }
        }
    }

    override fun addTable(record: OidNameRecord) {
        val bytes = record.asBytes()
        directoryCache.getAndPin(NAME_SYSTABLE_OID).use {
            it.putRecord(bytes).isOk
        }
        saveHeader()
    }

    private val modifyLock = ReentrantReadWriteLock()

    init {
        // We read the page #0 where we store table name => table oid mapping to initialize the next page identifiers.
        directoryCache.getAndPin(NAME_SYSTABLE_OID).use {
            val zeroPageHeader = ZeroPageHeader.read(it)
            nextDataPageId = zeroPageHeader.freeDataPageId
            nextCatalogPageId = zeroPageHeader.freeCatalogPageId
            saveHeader()
        }
    }

    override fun tableNameOidList(): List<OidNameRecord> =
        FullScanImpl(directoryCache, NAME_SYSTABLE_OID, {pages(NAME_SYSTABLE_OID).iterator()}).records {
            OidNameRecord(intField(), stringField(), booleanField()).fromBytes(it)
        }.toList()


    override fun pages(tableOid: Oid): Iterable<OidPageidRecord> =
        if (tableOid == NAME_SYSTABLE_OID) {
            listOf(OidPageidRecord(intField(NAME_SYSTABLE_OID), intField(NAME_SYSTABLE_OID)))
        } else {
            TablePageRecords(directoryCache, tableOid)
        }

    override fun add(tableOid: Oid, pageCount: Int): PageId {
        try {
            modifyLock.writeLock().lock()
            val firstCatalogPageId = getFirstCatalogPage(tableOid)
            var catalogPageId = directoryCache.get(firstCatalogPageId).let(CatalogPageHeader::read).lastPageId
            if (catalogPageId == 0) {
                // This means that it is a new page, and last page id has not been initialized yet.
                catalogPageId = firstCatalogPageId
            }
            var result = -1
            repeat(pageCount) {
                val addPageResult = addPage(tableOid, catalogPageId)
                if (addPageResult.nextDataPageId > nextDataPageId) {
                    nextDataPageId = addPageResult.nextDataPageId
                }
                if (addPageResult.catalogPageId != catalogPageId) {
                    catalogPageId = addPageResult.catalogPageId
                }
                result = addPageResult.firstDataPageId
            }
            return result
        } finally {
            modifyLock.writeLock().unlock()
        }
    }

    private fun getFirstCatalogPage(tableOid: Oid): PageId = tableOid

    private fun addPage(tableOid: Oid, catalogPageId: PageId): AddPageResult {
        directoryCache.getAndPin(catalogPageId).use { catalogPage ->
            // First we try to add a new table=>data page id record into the current catalog page.
            catalogPage.putRecord(OidPageidRecord(intField(tableOid), intField(nextDataPageId)).asBytes()).let { result ->
                if (result.isOutOfSpace) {
                    // If there is no room at the current catalog page, we add a new catalog page and insert a link to it into the
                    // header of the current one.
                    val newCatalogPageId = directoryCache.getAndPin(nextCatalogPageId++).use { newCatalogPage ->
                        CatalogPageHeader(0, -1, -1).write(newCatalogPage)
                        updateLastCatalogPageId(tableOid, newCatalogPage.id)
                        updateNextCatalogPageId(catalogPageId, newCatalogPage.id)
                        newCatalogPage.id
                    }
                    // After that, we try inserting a new record into the new catalog page.
                    return addPage(tableOid, newCatalogPageId)
                } else {
                    return AddPageResult(nextDataPageId, nextDataPageId + 1, catalogPageId)
                }
            }
        }
    }

    private fun updateLastCatalogPageId(tableOid: Oid, id: PageId) {
        directoryCache.getAndPin(getFirstCatalogPage(tableOid)).use { catalogPage ->
            CatalogPageHeader.read(catalogPage).let { CatalogPageHeader(it.directorySize, id, it.nextPageId) }.write(catalogPage)
        }
    }

    private fun updateNextCatalogPageId(currentCatalogPageId: PageId, newCatalogPageId: PageId) {
        directoryCache.getAndPin(currentCatalogPageId).use { catalogPage ->
            CatalogPageHeader.read(catalogPage).let { CatalogPageHeader(it.directorySize, it.lastPageId, newCatalogPageId) }.write(catalogPage)
        }
    }


    override fun delete(tableOid: Oid) {
        directoryCache.getAndPin(tableOid).use {
            it.clear()
        }
    }
}

data class AddPageResult(val firstDataPageId: PageId, val nextDataPageId: PageId, val catalogPageId: PageId)

class ZeroPageHeader(internal val directorySize: Int, internal val freeCatalogPageId: Int, internal val freeDataPageId: PageId) {
    fun write(page: DiskPage) {
        page.putHeader(Record3(intField(directorySize), intField(freeCatalogPageId), intField(freeDataPageId)).asBytes())
    }

    companion object {
        fun read(page: DiskPage): ZeroPageHeader {
            val record = Record3(intField(), intField(), intField()).fromBytes(page.rawBytes)
            if (record.value3 == -1) {
                return ZeroPageHeader(0, MAX_TABLE_COUNT, MAX_ROOT_PAGE_COUNT + 1)
            }
            return ZeroPageHeader(record.value1, record.value2, record.value3)
        }
    }
}
/**
 * A header record of a catalog page.
 * Directory size is the number of records in this page.
 * Next page id is the identifier of the next page in the linked list.
 * Last page id is the identifier of the last page in the linked list (used in the head page only).
 */
class CatalogPageHeader(internal val directorySize: Int,
                        internal val lastPageId: PageId,
                        internal val nextPageId: PageId) {

    fun write(page: DiskPage) {
        page.putHeader(Record3(intField(directorySize), intField(lastPageId), intField(nextPageId)).asBytes())
    }

    companion object {
        fun read(page: DiskPage): CatalogPageHeader {
            val record = Record3(intField(), intField(), intField()).fromBytes(page.rawBytes)
            if (record.value3 == 0) {
                return CatalogPageHeader(0, 0, -1).also { it.write(page) }
            }
            return CatalogPageHeader(record.value1, record.value2, record.value3)
        }

        fun recordSize() = Record3(intField(), intField(), intField()).size
    }
}

/**
 * Factory for creating catalog pages. Adds a header record to the page.
 */
class CatalogPageFactoryImpl: DiskPageFactory {
    override fun createEmptyPage(pageId: PageId): DiskPage = DiskPageImpl(pageId, DEFAULT_DISK_PAGE_SIZE,
        CatalogPageHeader.recordSize()).also {
            CatalogPageHeader(0, 0, -1).write(it)
    }

    override fun createCopyPage(proto: DiskPage, pageId: PageId?): DiskPage =
        DiskPageImpl(pageId ?: proto.id, DEFAULT_DISK_PAGE_SIZE, proto.headerSize, proto.rawBytes)
}
private val MAX_TABLE_COUNT = 128