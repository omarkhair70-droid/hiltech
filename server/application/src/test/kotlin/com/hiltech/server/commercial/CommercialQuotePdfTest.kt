package com.hiltech.server.commercial

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertTrue

class CommercialQuotePdfTest {
    @Test
    fun rendersReleasedProjectionWithArabicAndPagination() {
        val quote = ClientCommercialQuote(
            quoteId = "id", quoteCode = "Q-TEST-001", versionNumber = 1,
            clientOrganizationId = "client", currency = "EGP", totalPrice = BigDecimal("10000.0000"),
            paymentTerms = "الدفع عند التسليم", deliveryTerms = "Delivery to Cairo", exclusions = "لا تشمل أعمال إضافية",
            validityDate = "2026-12-31",
            lines = (1..40).map { ClientCommercialQuoteLine(it.toString(), "توريد معدات وفحص Network", BigDecimal.ONE, "EA", BigDecimal("250")) },
            clientName = "عميل اختبار", issuerName = "HILTECH", releasedAt = "2026-11-24",
        )
        val bytes = CommercialQuotePdf.render(quote)
        assertTrue(bytes.copyOfRange(0, 5).decodeToString() == "%PDF-")
        Loader.loadPDF(bytes).use { assertTrue(it.numberOfPages > 1) }
    }
}
