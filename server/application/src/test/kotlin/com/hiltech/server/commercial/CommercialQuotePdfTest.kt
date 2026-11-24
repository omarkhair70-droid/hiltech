package com.hiltech.server.commercial

import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import org.junit.jupiter.api.Assertions.assertTrue

class CommercialQuotePdfTest {
    @Test
    fun acceptsValidLogoAndRejectsUnsafeLogoAndPrefix() {
        val logo = java.awt.image.BufferedImage(96,32,java.awt.image.BufferedImage.TYPE_INT_RGB)
        val bytes = java.io.ByteArrayOutputStream().use { out -> javax.imageio.ImageIO.write(logo,"png",out); out.toByteArray() }
        val profile = CommercialDocumentProfile(legalName="HILTECH",logoBase64=java.util.Base64.getEncoder().encodeToString(bytes))
        CommercialDocumentSettings.validate(profile)
        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) { CommercialDocumentSettings.validate(profile.copy(logoBase64="not-a-png")) }
        org.junit.jupiter.api.Assertions.assertThrows(Exception::class.java) { CommercialDocumentSettings.validate(profile.copy(quotePrefix="bad prefix")) }
    }

    @Test
    fun rendersReleasedProjectionWithArabicAndPagination() {
        val quote = ClientCommercialQuote(
            quoteId = "id", quoteCode = "Q-TEST-001", versionNumber = 1,
            clientOrganizationId = "client", currency = "EGP", totalPrice = BigDecimal("10000.0000"),
            paymentTerms = "الدفع عند التسليم", deliveryTerms = "Delivery to Cairo", exclusions = "لا تشمل أعمال إضافية",
            validityDate = "2026-12-31",
            lines = (1..40).map { ClientCommercialQuoteLine(it.toString(), "توريد معدات وفحص Network", BigDecimal.ONE, "EA", BigDecimal("250")) },
            clientName = "عميل اختبار", issuerName = "HILTECH", releasedAt = "2026-11-24",
            subtotal = BigDecimal("10000.0000"), discountAmount = BigDecimal("500"), taxAmount = BigDecimal("500"),
            issuerProfile = CommercialDocumentProfile(legalName="HILTECH", address="القاهرة، مصر",registration="TEST-REG",taxNumber="TEST-TAX"),
        )
        val bytes = CommercialQuotePdf.render(quote)
        assertTrue(bytes.copyOfRange(0, 5).decodeToString() == "%PDF-")
        Loader.loadPDF(bytes).use {
            assertTrue(it.numberOfPages > 1)
            val rendered = org.apache.pdfbox.rendering.PDFRenderer(it).renderImageWithDPI(0,96f)
            assertTrue(rendered.width > 500 && rendered.height > 700)
            val output = java.nio.file.Path.of("build/reports/commercial-pdf-evidence")
            java.nio.file.Files.createDirectories(output)
            java.nio.file.Files.write(output.resolve("sample-quote.pdf"),bytes)
            javax.imageio.ImageIO.write(rendered,"png",output.resolve("page-1.png").toFile())
        }
    }
}
