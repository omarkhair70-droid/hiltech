package com.hiltech.shared.core.finance

/**
 * Portable PDF/PNG/JPEG signature check before any signed upload.
 * Canonical server storage verification repeats this check after PUT.
 * Signature verification is not antivirus clearance.
 */
object FinanceReceiptBytes {
    fun detectedContentType(bytes: ByteArray): String? {
        if (bytes.size >= 5 &&
            bytes.copyOfRange(0, 5).decodeToString() == "%PDF-") return "application/pdf"
        if (bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() &&
            bytes[1] == 0xd8.toByte() &&
            bytes[2] == 0xff.toByte()) return "image/jpeg"
        val png = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )
        if (bytes.size >= png.size &&
            png.indices.all { bytes[it] == png[it] }) return "image/png"
        return null
    }

    fun validSelectedReceipt(bytes: ByteArray, contentType: String): Boolean =
        bytes.isNotEmpty() &&
            bytes.size <= MAX_RECEIPT_SIZE &&
            detectedContentType(bytes) == contentType

    const val MAX_RECEIPT_SIZE: Int = 16 * 1024 * 1024
}
