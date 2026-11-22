package com.hiltech.server.work

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.AssetCondition
import com.hiltech.server.warehouse.AssetCustodyView
import com.hiltech.server.warehouse.AssetIdentityView
import com.hiltech.server.warehouse.AssetPassportPersistence
import com.hiltech.server.warehouse.AssetPassportService
import com.hiltech.server.warehouse.AssetTagLookupView
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class FieldAssignedAssetLookupServiceTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val project = UUID.randomUUID()
    private val site = UUID.randomUUID()
    private val workId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()
    private val tag = "opaque_tag_01"
    private val now = Instant.parse("2026-11-21T20:00:00Z")

    private val source = Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization = Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val work = Mockito.mock(WorkPersistencePort::class.java)
    private val assetService = Mockito.mock(AssetPassportService::class.java)
    private val assets = Mockito.mock(AssetPassportPersistence::class.java)
    private val order = Mockito.mock(WorkOrderSnapshot::class.java)

    private fun service(enabled: Boolean = true) = FieldAssignedAssetLookupService(
        source, authorization, work, assetService, assets,
        Clock.fixed(now, ZoneOffset.UTC), enabled,
    )

    private fun allowed(custodyWorkId: UUID = workId) {
        Mockito.`when`(source.isCurrentAssignedUser(actor, workId, now)).thenReturn(true)
        Mockito.`when`(authorization.canUseAssignedWork(actor, workId, "can_view")).thenReturn(true)
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(order.projectId).thenReturn(project)
        Mockito.`when`(order.siteId).thenReturn(site)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
        Mockito.`when`(assetService.lookup(actor, tag)).thenReturn(
            AssetTagLookupView(
                "AS-01", "OTDR", "Test equipment", "Vendor", "Model", "SER-1",
                AssetCondition.GOOD, "BIN-A", "EMP-9", now.toString(), null, assetId,
            ),
        )
        Mockito.`when`(assets.asset(assetId)).thenReturn(
            AssetIdentityView(
                assetId, org, UUID.randomUUID(), "AS-01", "OTDR", "Vendor", "Model", "SER-1",
                UUID.randomUUID(), "Test equipment", 1, AssetCondition.GOOD, "ACTIVE",
                false, null, 4,
            ),
        )
        val movement = UUID.randomUUID()
        val custody = AssetCustodyView(
            null, null, UUID.randomUUID(), "EMP-9", project, site, custodyWorkId,
            movement, now.toString(),
        )
        Mockito.`when`(assets.custody(assetId)).thenReturn(custody)
        Mockito.`when`(assets.activeTagAsset(tag)).thenReturn(assetId)
    }

    @Test fun `feature is fail closed while rollout flag is off`() {
        val error = assertThrows<ProductApiException> { service(false).lookup(actor, workId, tag) }
        assertEquals("FIELD_ASSET_LOOKUP_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(source, authorization, work, assetService, assets)
    }

    @Test fun `actor must still be currently assigned and allowed to view WorkOrder`() {
        val error = assertThrows<ProductApiException> { service().lookup(actor, workId, tag) }
        assertEquals("FIELD_ASSET_LOOKUP_UNAVAILABLE", error.code)
        Mockito.verifyNoInteractions(assetService)
    }

    @Test fun `canonical Asset access is narrowed to exact selected WorkOrder custody`() {
        allowed(custodyWorkId = UUID.randomUUID())
        val error = assertThrows<ProductApiException> { service().lookup(actor, workId, tag) }
        assertEquals("FIELD_ASSET_LOOKUP_UNAVAILABLE", error.code)
    }

    @Test fun `successful field lookup strips custody directory details`() {
        allowed()
        val result = service().lookup(actor, workId, tag)
        assertEquals(assetId, result.assetId)
        assertEquals("AS-01", result.assetCode)
        assertNull(result.locationCode)
        assertNull(result.employeeCode)
        assertNull(result.custodySince)
    }
}
