package com.hiltech.server.work

import com.hiltech.server.platform.ProductApiException
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
    private val workId = UUID.randomUUID()
    private val assetId = UUID.randomUUID()
    private val tag = "opaque_tag_01"
    private val now = Instant.parse("2026-11-21T20:00:00Z")

    private val source =
        Mockito.mock(WorkAssignmentSourceAuthorityPort::class.java)
    private val authorization =
        Mockito.mock(WorkAssignmentAuthorizationPort::class.java)
    private val work =
        Mockito.mock(WorkPersistencePort::class.java)
    private val assets =
        Mockito.mock(FieldAssignedAssetSourcePort::class.java)
    private val order =
        Mockito.mock(WorkOrderSnapshot::class.java)

    private fun service(enabled: Boolean = true) =
        FieldAssignedAssetLookupService(
            source,
            authorization,
            work,
            assets,
            Clock.fixed(now, ZoneOffset.UTC),
            enabled,
        )

    private fun authorize() {
        Mockito.`when`(
            source.isCurrentAssignedUser(actor, workId, now),
        ).thenReturn(true)
        Mockito.`when`(
            authorization.canUseAssignedWork(actor, workId, "can_view"),
        ).thenReturn(true)
        Mockito.`when`(order.workOrderId).thenReturn(workId)
        Mockito.`when`(order.organizationId).thenReturn(org)
        Mockito.`when`(work.workOrder(workId)).thenReturn(order)
    }

    private fun view() =
        FieldAssignedAssetLookupView(
            assetCode = "AS-01",
            name = "OTDR",
            typeName = "Test equipment",
            manufacturer = "Vendor",
            model = "Model",
            serialNumber = "SER-1",
            condition = "GOOD",
            locationCode = null,
            employeeCode = null,
            custodySince = null,
            availability = null,
            assetId = assetId,
        )

    @Test
    fun featureIsFailClosedWhileRolloutFlagIsOff() {
        val error =
            assertThrows<ProductApiException> {
                service(false).lookup(actor, workId, tag)
            }
        assertEquals(
            "FIELD_ASSET_LOOKUP_UNAVAILABLE",
            error.code,
        )
        Mockito.verifyNoInteractions(
            source,
            authorization,
            work,
            assets,
        )
    }

    @Test
    fun actorMustStillBeCurrentlyAssignedAndAllowedToViewWorkOrder() {
        val error =
            assertThrows<ProductApiException> {
                service().lookup(actor, workId, tag)
            }
        assertEquals(
            "FIELD_ASSET_LOOKUP_UNAVAILABLE",
            error.code,
        )
        Mockito.verifyNoInteractions(assets)
    }

    @Test
    fun canonicalAssetAdapterMustConfirmExactSelectedWorkContext() {
        authorize()
        Mockito.`when`(
            assets.lookupForWork(
                actor,
                tag,
                org,
                workId,
            ),
        ).thenReturn(null)

        val error =
            assertThrows<ProductApiException> {
                service().lookup(actor, workId, tag)
            }
        assertEquals(
            "FIELD_ASSET_LOOKUP_UNAVAILABLE",
            error.code,
        )
    }

    @Test
    fun successfulLookupReturnsOnlySafeFieldProjection() {
        authorize()
        Mockito.`when`(
            assets.lookupForWork(
                actor,
                tag,
                org,
                workId,
            ),
        ).thenReturn(view())

        val result =
            service().lookup(actor, workId, tag)

        assertEquals(assetId, result.assetId)
        assertEquals("AS-01", result.assetCode)
        assertNull(result.locationCode)
        assertNull(result.employeeCode)
        assertNull(result.custodySince)
    }
}
