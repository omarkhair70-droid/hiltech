package com.hiltech.shared.core.ux.procurement

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HuxProcurementCopyTest {
    @Test
    fun unknownStateDoesNotRenderAsApprovedOrIssued() {
        assertEquals("مسودة", huxNeedStateArabic("DRAFT"))
        assertEquals("مرسل للمراجعة", huxNeedStateArabic("SUBMITTED"))
        assertEquals("حالة تحتاج تحقق", huxNeedStateArabic("PO_ISSUED"))
        assertEquals("حالة تحتاج تحقق", huxNeedStateArabic("SOME_NEW_CODE"))
    }

    @Test
    fun hiddenStockHasNonNumericHumanExplanation() {
        assertEquals("يلزم فحص المخزون من المختص", huxStockAssessmentArabic("WAREHOUSE_REVIEW_REQUIRED"))
        assertEquals("التقييم يحتاج مراجعة", huxStockAssessmentArabic("UNKNOWN_SOURCE_CODE"))
        assertFalse(huxStockAssessmentArabic("UNKNOWN_SOURCE_CODE").contains("0"))
    }

    @Test
    fun sourceNumbersHaveDirectionalIsolatesWithoutChangingUnderlyingValue() {
        val value = "2026-11-22 14:30 UTC"
        val wrapped = huxLtrIsolate(value)
        assertTrue(wrapped.startsWith("\u2066"))
        assertTrue(wrapped.endsWith("\u2069"))
        assertEquals(value, wrapped.substring(1, wrapped.length - 1))
    }
}

class HuxQuoteCopyTest {
    @Test
    fun unknownQuoteTechnicalSourceDoesNotPretendToBeCompliant() {
        assertEquals("Technically compliant", huxTechnicalReviewLabel("COMPLIANT"))
        assertEquals("Technical review required", huxTechnicalReviewLabel("REVIEW_REQUIRED"))
        assertEquals("Technical status unavailable", huxTechnicalReviewLabel("UNRECOGNIZED"))
    }
}
