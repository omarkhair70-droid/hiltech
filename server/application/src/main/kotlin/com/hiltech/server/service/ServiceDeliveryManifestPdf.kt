package com.hiltech.server.service

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
import java.time.ZoneOffset

/** Customer package index, with shaped Arabic/Latin text. It contains no private review notes or claim of a customer signature. */
internal object ServiceDeliveryManifestPdf {
    fun render(release: ServiceDeliveryEvent): ByteArray {
        val font = requireNotNull(javaClass.getResourceAsStream("/commercial-fonts/plex_regular.ttf")).use { Font.createFont(Font.TRUETYPE_FONT, it) }
        val width = 1190; val height = 1684; val margin = 82
        val accent = Color(21, 91, 86); val ink = Color(23, 38, 43)
        return PDDocument().use { pdf ->
            pdf.documentInformation.title = "HILTECH Handover / " + release.proposal.projectCode
            var bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
            var graphics = bitmap.createGraphics()
            var y = 0f; var page = 0
            fun start() {
                graphics.dispose()
                bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB); graphics = bitmap.createGraphics()
                graphics.color = Color.WHITE; graphics.fillRect(0, 0, width, height)
                graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
                graphics.color = accent; graphics.fillRect(margin, 54, 72, 8)
                graphics.font = font.deriveFont(38f); graphics.drawString("HILTECH", margin, 122)
                y = 185f
            }
            fun finish() {
                page++
                graphics.color = accent; graphics.drawLine(margin, height - 96, width - margin, height - 96)
                graphics.font = font.deriveFont(20f); graphics.drawString(release.proposal.projectCode + "  /  " + page, margin, height - 55)
                graphics.dispose()
                val sheet = PDPage(PDRectangle.A4); pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use { it.drawImage(LosslessFactory.createFromImage(pdf, bitmap), 0f, 0f, PDRectangle.A4.width, PDRectangle.A4.height) }
            }
            fun text(value: String, size: Float = 26f, heading: Boolean = false) {
                value.split('\n').forEach { line ->
                    if (line.isBlank()) { y += 12; return@forEach }
                    val styled = AttributedString(line); styled.addAttribute(TextAttribute.FONT, font.deriveFont(size))
                    val iterator = styled.iterator; val breaks = LineBreakMeasurer(iterator, graphics.fontRenderContext)
                    while (breaks.position < iterator.endIndex) {
                        val layout = breaks.nextLayout((width - 2 * margin).toFloat())
                        val lineHeight = layout.ascent + layout.descent + layout.leading + 12
                        if (y + lineHeight > height - 135) { finish(); start() }
                        graphics.color = if (heading) accent else ink
                        val x = if (layout.isLeftToRight) margin.toFloat() else width - margin - layout.advance
                        layout.draw(graphics, x, y + layout.ascent); y += lineHeight
                    }
                }
                y += 14
            }
            start()
            text("PROJECT HANDOVER  /  تسليم المشروع", 40f, true)
            text(release.proposal.projectName, 32f)
            text("Client / العميل: " + release.proposal.clientName)
            text("Project / المشروع: " + release.proposal.projectCode)
            text("Issued / تاريخ الإصدار: " + release.recordedAt.atOffset(ZoneOffset.UTC).toLocalDate())
            text("Package record / سجل الإصدار: " + release.number)
            text("DOCUMENT INDEX  /  فهرس المستندات", 30f, true)
            release.proposal.packageDocuments.forEachIndexed { index, doc ->
                text((index + 1).toString() + ". " + doc.documentTitle, 27f, true)
                text(listOf(doc.documentCode, doc.revisionLabel, doc.workOrderCode, doc.workOrderTitle).filter { it.isNotBlank() }.joinToString("  ·  "), 23f)
                text("SHA-256: " + doc.sha256, 17f)
            }
            text("The listed original files accompany this index. Client responses are recorded separately.\nالمستندات الأصلية المدرجة مرفقة بهذا الفهرس. تُسجل استجابة العميل في سجل مستقل.", 23f)
            finish()
            ByteArrayOutputStream().use { output -> pdf.save(output); output.toByteArray() }
        }
    }
}
