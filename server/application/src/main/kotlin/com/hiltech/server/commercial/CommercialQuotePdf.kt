package com.hiltech.server.commercial

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.font.LineBreakMeasurer
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.text.AttributedString
import java.util.Base64

data class CommercialQuotePdfDto(val filename: String, val base64: String)

/**
 * Customer-only export from an authorized, released quote version.
 * Java2D lays out mixed Arabic/Latin text before rendering, including bidi and shaping.
 * Pages are rasterized at ~144dpi; document is printable, not searchable/tagged.
 */
internal object CommercialQuotePdf {
    fun render(quote: ClientCommercialQuote): ByteArray {
        val regular = requireNotNull(javaClass.getResourceAsStream("/commercial-fonts/plex_regular.ttf"))
            .use { Font.createFont(Font.TRUETYPE_FONT, it) }
        val width = 1190
        val height = 1684
        val margin = 85
        val ink = Color(23, 38, 43)
        val accent = Color(21, 91, 86)
        return PDDocument().use { document ->
            document.documentInformation.title = "HILTECH " + quote.quoteCode + " / v" + quote.versionNumber
            var image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            var graphics = image.createGraphics()
            var y = 0f
            var pageNumber = 0
            fun begin() {
                graphics.dispose()
                image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                graphics = image.createGraphics()
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                graphics.color = accent
                graphics.fillRect(margin, 50, 64, 8)
                graphics.font = regular.deriveFont(36f)
                graphics.drawString("HILTECH", margin, 106)
                graphics.font = regular.deriveFont(21f)
                graphics.color = ink
                graphics.drawString(quote.quoteCode + "  /  v" + quote.versionNumber, margin, 148)
                y = 198f
            }
            fun finish() {
                pageNumber++
                graphics.color = accent
                graphics.drawLine(margin, height - 90, width - margin, height - 90)
                graphics.font = regular.deriveFont(20f)
                graphics.drawString("HILTECH  /  " + pageNumber, margin, height - 55)
                graphics.dispose()
                val page = PDPage(PDRectangle.A4)
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.drawImage(LosslessFactory.createFromImage(document, image), 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height)
                }
            }
            fun paragraph(value: String, size: Float = 26f, heading: Boolean = false) {
                for (part in value.replace("\r", "").split("\n")) {
                    if (part.isBlank()) { y += 12; continue }
                    val attributed = AttributedString(part)
                    attributed.addAttribute(TextAttribute.FONT, regular.deriveFont(size))
                    val iterator = attributed.iterator
                    val measure = LineBreakMeasurer(iterator, graphics.fontRenderContext)
                    while (measure.position < iterator.endIndex) {
                        val layout = measure.nextLayout((width - margin * 2).toFloat())
                        val lineHeight = layout.ascent + layout.descent + layout.leading + 9
                        if (y + lineHeight > height - 115) { finish(); begin() }
                        graphics.color = if (heading) accent else ink
                        val x = if (layout.isLeftToRight) margin.toFloat() else width - margin - layout.advance
                        layout.draw(graphics, x, y + layout.ascent)
                        y += lineHeight
                    }
                }
                y += 10
            }
            begin()
            paragraph("QUOTATION  /  عرض سعر", 42f, true)
            paragraph("Client / العميل: " + (quote.clientName ?: quote.clientOrganizationId))
            paragraph("Issuer / جهة الإصدار: " + (quote.issuerName ?: "HILTECH"))
            paragraph("Issued / تاريخ الإصدار: " + (quote.releasedAt ?: "—"))
            paragraph("Valid until / صالح حتى: " + quote.validityDate)
            paragraph("ITEMS / البنود", 30f, true)
            quote.lines.forEach { line ->
                paragraph(line.lineCode + "  ·  " + line.description, 29f)
                paragraph("Quantity / الكمية: " + line.quantity.stripTrailingZeros().toPlainString() +
                    "  " + line.unit + "   |   Unit price / سعر الوحدة: " + line.unitPrice.toPlainString() + " " + quote.currency, 23f)
            }
            paragraph("TOTAL / الإجمالي: " + quote.totalPrice.toPlainString() + " " + quote.currency, 34f, true)
            paragraph("Payment terms / شروط الدفع", 30f, true)
            paragraph(quote.paymentTerms)
            paragraph("Delivery terms / شروط التسليم", 30f, true)
            paragraph(quote.deliveryTerms)
            if (quote.exclusions.isNotBlank()) {
                paragraph("Exclusions / الاستبعادات", 30f, true)
                paragraph(quote.exclusions)
            }
            finish()
            ByteArrayOutputStream().use { out -> document.save(out); out.toByteArray() }
        }
    }

    fun export(quote: ClientCommercialQuote): CommercialQuotePdfDto =
        CommercialQuotePdfDto(quote.quoteCode + "-v" + quote.versionNumber + ".pdf", Base64.getEncoder().encodeToString(render(quote)))
}
