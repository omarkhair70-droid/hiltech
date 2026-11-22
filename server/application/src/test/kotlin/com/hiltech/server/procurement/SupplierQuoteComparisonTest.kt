package com.hiltech.server.procurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

class SupplierQuoteComparisonTest {
    private val rfq = UUID.randomUUID()
    private val s1 = UUID.randomUUID()
    private val s2 = UUID.randomUUID()
    private val on = LocalDate.of(2026, 11, 22)

    private fun quote(
        supplier: UUID = s1,
        revision: Int = 1,
        currency: String = "EGP",
        quantity: String = "100",
        unit: String = "M",
        price: String = "12.50",
        tax: String = "1.25",
        expires: LocalDate? = on.plusDays(14),
    ) = SupplierQuoteEvidence(
        id = UUID.randomUUID(), rfqId = rfq, supplierOrganizationId = supplier,
        supplierQuoteReference = "Q-$revision", revisionNumber = revision,
        currency = currency, quantity = BigDecimal(quantity), unitCode = unit,
        unitPrice = BigDecimal(price), taxAmount = BigDecimal(tax),
        deliveryDays = 3, validUntil = expires,
        deliveryTerms = "Warehouse", paymentTerms = null, warrantyTerms = null,
        sourceEvidenceRef = "internal:quote:$revision", sourceSha256 = "a".repeat(64),
    )

    @Test fun latestRevisionReplacesComparisonProjectionWithoutDiscardingEvidence() {
        val list = listOf(
            quote(revision = 1, price = "21"),
            quote(revision = 2, price = "12.50"),
            quote(supplier = s2, revision = 1, price = "10"),
        )
        val out = SupplierQuoteComparator.compare(BigDecimal("100.000"), "M", list, on)
        assertEquals(2, out.latestBySupplier.size)
        assertEquals("1251.25", out.latestBySupplier.single { it.supplierOrganizationId == s1 }.total)
        assertEquals(2, out.latestBySupplier.single { it.supplierOrganizationId == s1 }.revisionNumber)
        assertFalse(out.supplierSelected)
        assertFalse(out.purchaseAuthorized)
        // Unreviewed technical compliance is never automatically approved.
        assertFalse(out.allQuotesDirectlyComparable)
        assertTrue(out.latestBySupplier.all { "TECHNICAL_COMPLIANCE_UNCONFIRMED" in it.issues })
    }

    @Test fun differentCurrenciesNeverQuietlyBecomeCheapestInAnInventedFxCurrency() {
        val out = SupplierQuoteComparator.compare(
            BigDecimal("100"), "M",
            listOf(quote(currency = "USD"), quote(supplier = s2, currency = "EGP")), on,
        )
        assertEquals(listOf("EGP", "USD"), out.currencies)
        assertTrue(out.latestBySupplier.all { "CURRENCY_DIFFERS_NO_FX_CONVERSION" in it.issues })
        assertFalse(out.allQuotesDirectlyComparable)
    }

    @Test fun quantityUnitExpiryAndUnverifiedValidityAreDisplayedRatherThanHidden() {
        val out = SupplierQuoteComparator.compare(BigDecimal("100"), "M", listOf(
            quote(quantity = "50", unit = "PCS", expires = on.minusDays(1)),
            quote(supplier = s2, expires = null),
        ), on)
        val wrong = out.latestBySupplier.single { it.supplierOrganizationId == s1 }
        assertTrue("QUANTITY_DIFFERS" in wrong.issues)
        assertTrue("UNIT_DIFFERS" in wrong.issues)
        assertTrue("QUOTE_EXPIRED" in wrong.issues)
        val unknown = out.latestBySupplier.single { it.supplierOrganizationId == s2 }
        assertTrue("VALIDITY_UNCONFIRMED" in unknown.issues)
    }

    @Test fun decimalTotalsAreExactAndNeverSilentlyRounded() {
        val out = SupplierQuoteComparator.compare(BigDecimal("0.125"), "KG", listOf(
            quote(quantity = "0.125", unit = "KG", price = "1.234567", tax = "0.000001"),
        ), on)
        assertEquals("0.154321875", out.latestBySupplier.single().total)
    }

    @Test fun emptyOrInvalidRequirementCannotPretendThereIsAComparison() {
        val empty = SupplierQuoteComparator.compare(BigDecimal("10"), "PCS", emptyList(), on)
        assertTrue(empty.latestBySupplier.isEmpty())
        assertFalse(empty.allQuotesDirectlyComparable)
        assertFalse(empty.purchaseAuthorized)
    }
}
