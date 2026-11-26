package com.hiltech.server.documents

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ProtectedDocumentRasterizerTest {
    @Test fun rendersOnlyRequestedPdfPageWithinPixelBoundAndRejectsMissingPage() {
        val original = PDDocument().use { pdf ->
            pdf.addPage(PDPage(PDRectangle.A4)); pdf.addPage(PDPage(PDRectangle(1200f, 900f)))
            ByteArrayOutputStream().use { out -> pdf.save(out); out.toByteArray() }
        }
        val page = ProtectedDocumentRasterizer.render(original, "application/pdf", 1)
        assertEquals(1, page.pageIndex); assertEquals(2, page.pageCount)
        val bitmap = ImageIO.read(ByteArrayInputStream(page.png))
        assertEquals(1800, bitmap.width); assertEquals(1350, bitmap.height)
        assertThrows<IllegalArgumentException> { ProtectedDocumentRasterizer.render(original, "application/pdf", 2) }
        assertThrows<IllegalArgumentException> { ProtectedDocumentRasterizer.render(original, "application/pdf", -1) }
    }

    @Test fun nativeImageIsSubsampledAndDecompressionDimensionLimitIsIndependentOfSmallFileSize() {
        fun png(width: Int, height: Int) = ByteArrayOutputStream().use { out -> ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray() }
        val preview = ProtectedDocumentRasterizer.render(png(3000, 2000), "image/png", 0)
        val bitmap = ImageIO.read(ByteArrayInputStream(preview.png))
        assertTrue(bitmap.width <= 1800 && bitmap.height <= 1800)
        val tall = png(1, 16001)
        assertTrue(tall.size < 100_000)
        assertThrows<IllegalArgumentException> { ProtectedDocumentRasterizer.render(tall, "image/png", 0) }
    }

    @Test fun unsupportedOrMalformedBytesCannotProduceAPreviewReceipt() {
        assertThrows<IllegalStateException> { ProtectedDocumentRasterizer.render(byteArrayOf(1), "image/webp", 0) }
        assertThrows<Exception> { ProtectedDocumentRasterizer.render(byteArrayOf(1,2,3), "application/pdf", 0) }
        assertThrows<IllegalArgumentException> { ProtectedDocumentRasterizer.render(ByteArray(16_777_217), "image/png", 0) }
    }
}
