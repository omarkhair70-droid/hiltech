package com.hiltech.desktop.finance

import com.hiltech.shared.core.finance.FinanceReceiptBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FinanceWindowsReceiptPickerContractTest {
    @Test fun selectedBytesDetermineActualMimeNotFilename() {
        assertEquals("application/pdf", FinanceReceiptBytes.detectedContentType("%PDF-1.7".encodeToByteArray()))
        assertEquals("image/jpeg", FinanceReceiptBytes.detectedContentType(byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0x00,
        )))
        assertEquals("image/png", FinanceReceiptBytes.detectedContentType(byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
        )))
        assertNull(FinanceReceiptBytes.detectedContentType("%PDF".encodeToByteArray()))
        assertNull(FinanceReceiptBytes.detectedContentType("not-a-document".encodeToByteArray()))
        assertNull(FinanceReceiptBytes.detectedContentType(byteArrayOf()))
    }
}
