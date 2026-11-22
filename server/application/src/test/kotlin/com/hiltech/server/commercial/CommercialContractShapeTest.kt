package com.hiltech.server.commercial

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class CommercialContractShapeTest {
    @Test
    fun publicQuoteDoesNotContainPrivateCostOrMargin() {
        val publicFields = ClientCommercialQuote::class.java.declaredFields
            .map { it.name.lowercase() }.toSet()
        val lineFields = ClientCommercialQuoteLine::class.java.declaredFields
            .map { it.name.lowercase() }.toSet()
        for (name in publicFields + lineFields) {
            assertFalse("cost" in name || "margin" in name || "supplier" in name ||
                "vendor" in name || "internal" in name)
        }
        assertTrue("totalprice" in publicFields)
    }

    @Test
    fun missingVerifiedEvidenceAlwaysBlocksAward() {
        assertFalse(FailClosedCommercialAwardEvidenceGate().verifiedAward(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
    }

    @Test
    fun invalidIdentifiersAreRejectedBeforeUse() {
        assertThrows(Exception::class.java) { "cross-tenant-key".commercialUuid() }
    }
}
