package com.hiltech.server.warehouse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.time.Instant
import java.util.UUID

class WarehouseFieldAssignedAssetSourceTest {
    private val actor = UUID.randomUUID()
    private val org = UUID.randomUUID()
    private val work = UUID.randomUUID()
    private val asset = UUID.randomUUID()
    private val tag = "opaque_tag_01"
    private val movement = UUID.randomUUID()

    private val service =
        Mockito.mock(AssetPassportService::class.java)
    private val persistence =
        Mockito.mock(AssetPassportPersistence::class.java)

    private fun source() =
        WarehouseFieldAssignedAssetSource(
            service,
            persistence,
        )

    private fun arrange(
        custodyWork: UUID = work,
        activeTagAsset: UUID? = asset,
        currentMovement: UUID = movement,
    ) {
        Mockito.`when`(
            service.lookup(actor, tag),
        ).thenReturn(
            AssetTagLookupView(
                assetCode = "AS-01",
                name = "OTDR",
                typeName = "Test equipment",
                manufacturer = "Vendor",
                model = "Model",
                serialNumber = "SER-1",
                condition = AssetCondition.GOOD,
                locationCode = "BIN-A",
                employeeCode = "EMP-9",
                custodySince = Instant.EPOCH.toString(),
                availability = null,
                assetId = asset,
            ),
        )
        Mockito.`when`(
            persistence.asset(asset),
        ).thenReturn(
            AssetIdentityView(
                id = asset,
                organizationId = org,
                warehouseId = UUID.randomUUID(),
                assetCode = "AS-01",
                name = "OTDR",
                manufacturer = "Vendor",
                model = "Model",
                serialNumber = "SER-1",
                typeId = UUID.randomUUID(),
                typeName = "Test equipment",
                typeRevision = 1,
                condition = AssetCondition.GOOD,
                lifecycle = "ACTIVE",
                duplicateSerial = false,
                sourceImportRowId = null,
                version = 4,
            ),
        )
        val first =
            AssetCustodyView(
                storageLocationId = null,
                locationCode = null,
                employeeId = UUID.randomUUID(),
                employeeCode = "EMP-9",
                projectId = null,
                siteId = null,
                workOrderId = custodyWork,
                sourceMovementId = movement,
                since = Instant.EPOCH.toString(),
            )
        val current =
            first.copy(
                sourceMovementId =
                    currentMovement,
            )
        Mockito.`when`(
            persistence.custody(asset),
        ).thenReturn(first, current)
        Mockito.`when`(
            persistence.activeTagAsset(tag),
        ).thenReturn(activeTagAsset)
    }

    @Test
    fun exactWorkCustodyReturnsSafeProjection() {
        arrange()

        val result =
            source().lookupForWork(
                actor,
                tag,
                org,
                work,
            )

        requireNotNull(result)
        assertEquals(asset, result.assetId)
        assertEquals("GOOD", result.condition)
        assertNull(result.locationCode)
        assertNull(result.employeeCode)
        assertNull(result.custodySince)
    }

    @Test
    fun wrongWorkCustodyIsHidden() {
        arrange(
            custodyWork = UUID.randomUUID(),
        )

        assertNull(
            source().lookupForWork(
                actor,
                tag,
                org,
                work,
            ),
        )
    }

    @Test
    fun tagOrCustodyRaceIsHidden() {
        arrange(
            activeTagAsset =
                UUID.randomUUID(),
        )
        assertNull(
            source().lookupForWork(
                actor,
                tag,
                org,
                work,
            ),
        )

        Mockito.reset(
            service,
            persistence,
        )
        arrange(
            currentMovement =
                UUID.randomUUID(),
        )
        assertNull(
            source().lookupForWork(
                actor,
                tag,
                org,
                work,
            ),
        )
    }
}
