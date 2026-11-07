package com.hiltech.server.warehouse

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class WarehouseImportReadServiceTest {
    private val persistence = Mockito.mock(WarehousePersistencePort::class.java)
    private val authorization = Mockito.mock(WarehouseAuthorizationPort::class.java)
    private val service = WarehouseImportReadService(persistence, authorization)
    private val actor = UUID.randomUUID()
    private val organization = UUID.randomUUID()
    private val batchId = UUID.randomUUID()

    @Test
    fun unauthorizedImportIsHiddenBeforeRowsAreRead() {
        val batch = batch()
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch)
        Mockito.`when`(authorization.canManageImport(actor, organization))
            .thenReturn(false)

        val error = assertThrows<ProductApiException> {
            service.batchDetail(actor, batchId)
        }
        assertEquals("INVENTORY_IMPORT_NOT_FOUND", error.code)
        assertEquals(404, error.status.value())
        Mockito.verify(persistence, Mockito.never()).importRows(batchId)
    }

    @Test
    fun authorizedImportExcludesRowsOutsideTheBatchOrganization() {
        val batch = batch()
        val allowed = row(organization, batchId)
        val wrongOrganization = row(UUID.randomUUID(), batchId)
        val wrongBatch = row(organization, UUID.randomUUID())
        Mockito.`when`(persistence.importBatch(batchId)).thenReturn(batch)
        Mockito.`when`(authorization.canManageImport(actor, organization))
            .thenReturn(true)
        Mockito.`when`(persistence.importRows(batchId))
            .thenReturn(listOf(allowed, wrongOrganization, wrongBatch))

        val result = service.batchDetail(actor, batchId)
        assertEquals(batch, result.batch)
        assertEquals(listOf(allowed), result.rows)
    }

    @Test
    fun absentImportIsHiddenWithoutCheckingAuthorityOrRows() {
        val error = assertThrows<ProductApiException> {
            service.batchDetail(actor, batchId)
        }
        assertEquals("INVENTORY_IMPORT_NOT_FOUND", error.code)
        Mockito.verifyNoInteractions(authorization)
        Mockito.verify(persistence, Mockito.never()).importRows(batchId)
    }

    @Test
    fun importDirectoryIsUnavailableForNonReviewerWithoutListingAnything() {
        val error = assertThrows<ProductApiException> {
            service.batchDirectory(actor, organization)
        }
        assertEquals("INVENTORY_IMPORT_NOT_FOUND", error.code)
        Mockito.verify(persistence, Mockito.never()).importBatches(organization)
    }

    @Test
    fun importDirectoryIncludesOnlyRequestedOrganization() {
        Mockito.`when`(authorization.canManageImport(actor, organization))
            .thenReturn(true)
        val allowed = batch()
        val foreign = batch().copy(
            batchId = UUID.randomUUID(),
            organizationId = UUID.randomUUID(),
        )
        Mockito.`when`(persistence.importBatches(organization))
            .thenReturn(listOf(allowed, foreign))
        assertEquals(listOf(allowed), service.batchDirectory(actor, organization))
    }

    @Test
    fun sourcePreviewKeepsKnownLegacyColumnsAndOmitsValuesAndUnknownFields() {
        val row = row(organization, batchId).copy(
            sourceSheet = "أكسسوارات",
            rawPayloadJson = """{
                "اسم الصنف":"Example",
                "العدد":0,
                "الرقم التسلسلي":"LOCAL",
                "سعر القطعه":1000,
                "السعر الإجمالي":1000,
                "ملاحظات":"private",
                "PART NUMBER":"unexpected on this layout",
                "supplierCost":1000
            }""",
        )

        assertEquals(
            mapOf("اسم الصنف" to "Example", "العدد" to "0", "الرقم التسلسلي" to "LOCAL"),
            LegacyInventorySource.preview(InventoryImportSourceType.LEGACY_EXCEL, row),
        )
        assertEquals(emptyMap<String, String>(), LegacyInventorySource.preview(InventoryImportSourceType.CSV, row))
    }

    @Test
    fun representativeLegacyLayoutsSurfaceDifferentSourceAmbiguities() {
        val excel = InventoryImportSourceType.LEGACY_EXCEL
        assertEquals(
            setOf(InventoryImportIssueCode.LEGACY_DATE_SEMANTICS_AMBIGUOUS),
            LegacyInventorySource.issues(excel, "Main", """{"UOM":"PC","تاريخ الخروج":"2026-01-01"}"""),
        )
        assertEquals(
            setOf(InventoryImportIssueCode.UNKNOWN_UOM),
            LegacyInventorySource.issues(excel, "FIBER", """{"DESC":"Fiber cable","UOM":"PC"}"""),
        )
        assertEquals(
            setOf(InventoryImportIssueCode.DESCRIPTION_CONFLICT),
            LegacyInventorySource.issues(excel, "SAMPLE", """{"DESCRPTION":"Patch cord","DESC":"Switch"}"""),
        )
        assertEquals(
            setOf(InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS),
            LegacyInventorySource.issues(excel, "أكسسوارات", """{"الرقم التسلسلي":"LOCAL"}"""),
        )
    }

    private fun batch() = InventoryImportBatchSnapshot(
        batchId = batchId,
        organizationId = organization,
        sourceFileName = "stock.xlsx",
        sourceSha256 = "a".repeat(64),
        sourceType = InventoryImportSourceType.LEGACY_EXCEL,
        cutoverDate = null,
        state = InventoryImportBatchState.IN_REVIEW,
        createdByUserId = actor,
        createdAt = Instant.parse("2026-09-21T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-21T00:00:00Z"),
        version = 1,
    )

    private fun row(rowOrganization: UUID, rowBatch: UUID) = InventoryImportRowSnapshot(
        rowId = UUID.randomUUID(),
        batchId = rowBatch,
        organizationId = rowOrganization,
        sourceSheet = "Items",
        sourceRowNumber = 2,
        rawPayloadJson = "{\"supplierCost\":1000}",
        candidateClass = InventoryCandidateClass.UNCLASSIFIED,
        normalizedBrand = null,
        normalizedPartNumber = null,
        normalizedDescription = null,
        candidateUom = null,
        candidateQuantity = BigDecimal.ONE,
        candidateValue = BigDecimal("1000"),
        candidateCurrencyCode = "EGP",
        issueCodes = listOf(InventoryImportIssueCode.LEGACY_COLUMN_SEMANTICS_AMBIGUOUS),
        reviewState = InventoryImportReviewState.NEEDS_REVIEW,
        reviewNotes = null,
        reviewedByUserId = null,
        reviewedAt = null,
        approvedTargetType = null,
        approvedTargetId = null,
        createdAt = Instant.parse("2026-09-21T00:00:00Z"),
        updatedAt = Instant.parse("2026-09-21T00:00:00Z"),
        version = 1,
    )
}
