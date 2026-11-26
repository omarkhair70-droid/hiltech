package com.hiltech.server.documents

import org.apache.pdfbox.Loader
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

data class ProtectedDocumentRasterPage(val pageIndex: Int, val pageCount: Int, val png: ByteArray)

/** Pure bounded decoder. Source domains must verify original bytes and current authority before and after calling it. */
object ProtectedDocumentRasterizer {
    fun render(bytes: ByteArray, contentType: String, pageIndex: Int): ProtectedDocumentRasterPage {
        require(bytes.size in 1..16_777_216 && pageIndex in 0..499)
        val (count, image) = when (contentType) {
            "application/pdf" -> Loader.loadPDF(bytes).use { pdf ->
                require(!pdf.isEncrypted && pdf.numberOfPages in 1..500 && pageIndex < pdf.numberOfPages)
                val box = pdf.getPage(pageIndex).cropBox
                require(box.width.isFinite() && box.height.isFinite() && box.width > 0 && box.height > 0 && box.width <= 14400 && box.height <= 14400)
                val scale = minOf(2f, 1800f / maxOf(box.width, box.height))
                pdf.numberOfPages to PDFRenderer(pdf).renderImage(pageIndex, scale, ImageType.RGB)
            }
            "image/png", "image/jpeg" -> {
                require(pageIndex == 0)
                val image = ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    requireNotNull(input)
                    val reader = ImageIO.getImageReaders(input).asSequence().firstOrNull() ?: error("Unsupported image")
                    try {
                        reader.input = input
                        val width = reader.getWidth(0); val height = reader.getHeight(0)
                        require(width in 1..16000 && height in 1..16000 && width.toLong() * height <= 64_000_000)
                        val sample = maxOf(1, (maxOf(width, height) + 1799) / 1800)
                        val parameters = reader.defaultReadParam
                        parameters.setSourceSubsampling(sample, sample, 0, 0)
                        requireNotNull(reader.read(0, parameters))
                    } finally { reader.dispose() }
                }
                1 to image
            }
            else -> error("Unsupported document format")
        }
        require(image.width in 1..1800 && image.height in 1..1800)
        val png = ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "png", output)); output.toByteArray() }
        require(png.size <= 16_777_216)
        return ProtectedDocumentRasterPage(pageIndex, count, png)
    }
}
