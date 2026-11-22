package com.hiltech.shared.core.finance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinanceReceiptBytesTest {
    @Test fun canonicalSignaturesAreDetectedOnAllPlatforms() {
        assertEquals(
            "application/pdf",
            FinanceReceiptBytes.detectedContentType("%PDF-1.7".encodeToByteArray()),
        )
        assertEquals(
            "image/jpeg",
            FinanceReceiptBytes.detectedContentType(byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(),
            )),
        )
        assertEquals(
            "image/png",
            FinanceReceiptBytes.detectedContentType(byteArrayOf(
                0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
            )),
        )
        assertNull(FinanceReceiptBytes.detectedContentType("report.pdf".encodeToByteArray()))
    }

    @Test fun mismatchedMimeAndEmptyBytesCannotBeReserved() {
        val pdf = "%PDF-1.7".encodeToByteArray()
        assertTrue(FinanceReceiptBytes.validSelectedReceipt(pdf, "application/pdf"))
        assertFalse(FinanceReceiptBytes.validSelectedReceipt(pdf, "image/png"))
        assertFalse(FinanceReceiptBytes.validSelectedReceipt(byteArrayOf(), "application/pdf"))
        assertFalse(FinanceReceiptBytes.validSelectedReceipt(
            "file.pdf".encodeToByteArray(), "application/pdf",
        ))
    }
}
