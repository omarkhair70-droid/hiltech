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
        val profile = quote.issuerProfile
        val logo = profile?.logoBase64?.takeIf { it.isNotBlank() }?.let { javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(Base64.getDecoder().decode(it))) }
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
                if (logo != null) {
                    val scale = minOf(280.0 / logo.width, 90.0 / logo.height)
                    graphics.drawImage(logo, margin, 60, (logo.width*scale).toInt().coerceAtLeast(1), (logo.height*scale).toInt().coerceAtLeast(1), null)
                } else graphics.drawString("HILTECH", margin, 106)
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
                graphics.drawString("HILTECH  /  " + pageNumber, margin, height - 58)
                graphics.font = regular.deriveFont(17f)
                if (profile == null) {
                    graphics.drawString("D1 Tiba Building - Zahraa El Maadi - Cairo, Egypt", margin + 190, height - 60)
                    graphics.drawString("01000087808   |   info@hiltech-eg.com   |   hiltech-eg.com", margin + 190, height - 34)
                }
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
            paragraph("Issuer / جهة الإصدار: " + (profile?.legalName ?: quote.issuerName ?: "HILTECH"))
            profile?.let { p ->
                listOf(p.address, listOf(p.phone,p.email,p.website).filter { it.isNotBlank() }.joinToString(" | "),
                    p.registration.takeIf { it.isNotBlank() }?.let { "Registration / السجل التجاري: " + it }.orEmpty(),
                    p.taxNumber.takeIf { it.isNotBlank() }?.let { "Tax ID / الرقم الضريبي: " + it }.orEmpty()).filter { it.isNotBlank() }.forEach { paragraph(it,22f) }
            }
            paragraph("Issued / تاريخ الإصدار: " + (quote.releasedAt ?: "—"))
            paragraph("Valid until / صالح حتى: " + quote.validityDate)
            paragraph("ITEMS / البنود", 30f, true)
            val edges = intArrayOf(margin, margin+90, margin+490, margin+620, margin+810, width-margin)
            fun tableHeader() {
                if (y+62 > height-115) { finish(); begin() }
                graphics.color = accent
                graphics.fillRect(margin,y.toInt(),width-margin*2,54)
                graphics.color = Color.WHITE
                graphics.font = regular.deriveFont(19f)
                listOf("No. / رقم","Item / البند","Qty / الكمية","Unit price / السعر","Total / الإجمالي").forEachIndexed { i,label ->
                    graphics.drawString(label,edges[i]+9,y+34)
                }
                y += 54
            }
            fun layoutCell(value: String, cellWidth: Int): List<java.awt.font.TextLayout> {
                val a = AttributedString(value.ifBlank { "—" }.replace("\n"," ").replace("\r"," "))
                a.addAttribute(TextAttribute.FONT,regular.deriveFont(21f))
                val it = a.iterator
                val measurer = LineBreakMeasurer(it,graphics.fontRenderContext)
                val result = mutableListOf<java.awt.font.TextLayout>()
                while(measurer.position<it.endIndex) result.add(measurer.nextLayout((cellWidth-18).toFloat()))
                return result
            }
            tableHeader()
            quote.lines.forEachIndexed { index,line ->
                val values = listOf(line.lineCode,line.description,line.quantity.stripTrailingZeros().toPlainString()+" "+line.unit,
                    line.unitPrice.stripTrailingZeros().toPlainString(),commercialExactLineTotal(line.quantity,line.unitPrice).stripTrailingZeros().toPlainString())
                val cells = values.mapIndexed { i,value -> layoutCell(value,edges[i+1]-edges[i]) }
                val maxLines = cells.maxOf { it.size }
                var offset = 0
                while (offset < maxLines) {
                    if (y+52>height-115) { finish(); begin(); tableHeader() }
                    val linesFit = ((height-115-y-18)/30).toInt().coerceAtLeast(1)
                    val count = minOf(maxLines-offset,linesFit)
                    val rowHeight = (count*30+18).toFloat()
                    graphics.color = if(index%2==0) Color(242,246,245) else Color.WHITE
                    graphics.fillRect(margin,y.toInt(),width-margin*2,rowHeight.toInt())
                    graphics.color = ink
                    cells.forEachIndexed { i,cell ->
                        cell.drop(offset).take(count).forEachIndexed { j,layout ->
                            val x = if(i>=2 || !layout.isLeftToRight) edges[i+1]-9-layout.advance else edges[i]+9f
                            layout.draw(graphics,x,y+9+j*30+layout.ascent)
                        }
                    }
                    graphics.color = Color(216,225,223)
                    graphics.drawLine(margin,(y+rowHeight).toInt(),width-margin,(y+rowHeight).toInt())
                    y += rowHeight
                    offset += count
                    if(offset<maxLines) { finish(); begin(); tableHeader() }
                }
            }
            y += 24
            paragraph("Currency / العملة: "+quote.currency,22f)
            quote.subtotal?.let { paragraph("Subtotal / قبل الخصم والضريبة: " + it.toPlainString() + " " + quote.currency) }
            paragraph("Discount / الخصم: " + quote.discountAmount.toPlainString() + " " + quote.currency)
            paragraph("Tax / الضريبة: " + quote.taxAmount.toPlainString() + " " + quote.currency)
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
