package com.hiltech.server.commercial

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import com.hiltech.server.platform.ProductApiException
import java.util.UUID
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertEquals

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
    fun quoteAmountPreservesFourPlaceMoneyWithoutFloatingPoint() {
        assertEquals(
            BigDecimal("5.1250"),
            commercialExactLineTotal(BigDecimal("0.2500"),BigDecimal("20.5000")),
        )
        assertEquals(
            BigDecimal("12345.0000"),
            commercialExactLineTotal(BigDecimal("10.0000"),BigDecimal("1234.5000")),
        )
    }

    @Test
    fun unapprovedFractionalRoundingIsNotSilent() {
        assertThrows(ProductApiException::class.java) {
            commercialExactLineTotal(BigDecimal("0.3333"),BigDecimal("0.3333"))
        }
    }

    @Test
    fun invalidIdentifiersAreRejectedBeforeUse() {
        assertThrows(Exception::class.java) { "cross-tenant-key".commercialUuid() }
    }
}
