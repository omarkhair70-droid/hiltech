package com.hiltech.server.warehouse

import com.hiltech.server.platform.HiltechRequestContext
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.time.Instant
import java.util.UUID

class WarehouseCommandCenterApiTest {
    @Test
    fun wireKeepsSourceLinksAndNeverExposesRestrictedFinanceValue() {
        val service = Mockito.mock(WarehouseCommandCenterService::class.java)
        val actor = UUID.randomUUID()
        val session = UUID.randomUUID()
        val warehouse = UUID.randomUUID()
        val organization = UUID.randomUUID()
        val source = UUID.randomUUID()
        val asset = UUID.randomUUID()
        val item = WarehouseCommandCenterItem(
            sourceType = "ASSET_MOVEMENT",
            sourceId = source,
            label = "SYNTH-ASSET",
            state = "OVERDUE",
            reasonCodes = listOf("RETURN_OVERDUE"),
            occurredAt = null,
            dueAt = Instant.parse("2026-11-20T10:00:00Z"),
            projectId = null,
            siteId = null,
            workOrderId = null,
            drillType = "ASSET",
            drillId = asset,
        )
        val empty = WarehouseCommandCenterQueue(emptyList(), false)
        val snapshot = WarehouseCommandCenterSnapshot(
            warehouseId = warehouse,
            organizationId = organization,
            warehouseCode = "WH-1",
            warehouseName = "Warehouse",
            asOf = Instant.parse("2026-11-20T12:00:00Z"),
            inventory = WarehouseInventoryOverview(1, 1, 0, 0, false, "LOW_STOCK_THRESHOLD_NOT_CONFIGURED"),
            dueReturns = WarehouseCommandCenterQueue(listOf(item), false),
            activeReservations = empty,
            assetExceptions = empty,
            receivingQueue = empty,
            stocktakeQueue = empty,
            importExceptions = empty,
            recentMovements = empty,
        )
        Mockito.`when`(service.snapshot(actor, warehouse)).thenReturn(snapshot)

        val json = MockMvcBuilders.standaloneSetup(WarehouseCommandCenterController(service)).build()
            .perform(get("/v1/warehouses/$warehouse/command-center").with {
                HiltechRequestContext.bindAuthenticatedIdentity(it, actor, session)
                it
            })
            .andExpect(status().isOk())
            .andReturn().response.contentAsString

        assertTrue(json.contains("\"sourceType\":\"ASSET_MOVEMENT\""))
        assertTrue(json.contains(source.toString()))
        assertTrue(json.contains("drillType"))
        assertTrue(json.contains("ASSET"))
        assertTrue(json.contains(asset.toString()))
        assertTrue(json.contains("RETURN_OVERDUE"))
        for (forbidden in listOf(
            "acquisitionCost", "acquisition_cost", "candidateValue", "candidate_value",
            "purchasePrice", "unitCost", "currencyCode", "payroll", "invoiceTotal",
        )) {
            assertFalse(json.contains(forbidden, ignoreCase = true), "Restricted value leaked on Command Center wire: $forbidden")
        }
    }
}
