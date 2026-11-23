package com.hiltech.server.procurement

import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReceivingSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant
import java.util.UUID

class ProcurementPoReceivingGuardTest {
    private val jdbc = Mockito.mock(JdbcTemplate::class.java)
    private val orders = Mockito.mock(PurchaseOrderIssuePersistence::class.java)
    private val org = UUID.randomUUID()
    private val record = UUID.randomUUID()
    private val location = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    @Test fun untrustedTextCannotBecomeAnIssuedPurchaseOrder() {
        val guard = ProcurementPoReceivingGuard(jdbc, orders, true)
        val failure = assertThrows<ProductApiException> {
            guard.linkNewRecord(record, org, location, ReceivingSourceType.PO,
                "PO-SYNTH-ONLY", actor, Instant.EPOCH, "source-check")
        }
        assertEquals("PROCUREMENT_PO_RECEIVING_SOURCE_INVALID", failure.code)
        Mockito.verifyNoInteractions(jdbc, orders)
    }

    @Test fun legacyWarehouseReceiptIsUnchangedWhileNewFeatureIsDisabled() {
        val guard = ProcurementPoReceivingGuard(jdbc, orders, false)
        guard.linkNewRecord(record, org, location, ReceivingSourceType.PO,
            "LEGACY-PO-NO-CANONICAL-ISSUE", actor, Instant.EPOCH, "legacy")
        Mockito.verifyNoInteractions(jdbc, orders)
    }

    @Test fun nonPoSourceDoesNotEnterProcurementAdapter() {
        val guard = ProcurementPoReceivingGuard(jdbc, orders, true)
        guard.linkNewRecord(record, org, location, ReceivingSourceType.OTHER,
            "MANUAL-DELIVERY", actor, Instant.EPOCH, "manual")
        Mockito.verifyNoInteractions(jdbc, orders)
    }
}
