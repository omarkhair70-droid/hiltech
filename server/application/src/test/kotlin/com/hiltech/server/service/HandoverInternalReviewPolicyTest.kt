package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class HandoverInternalReviewPolicyTest {
    @Test
    fun supportsInternalCheckFindingsAndRecheckWithoutApprovingClient() {
        assertEquals("CHECKING", HandoverInternalReviewPolicy.next(null, "CHECK_STARTED"))
        assertEquals("FINDINGS_RECORDED", HandoverInternalReviewPolicy.next("CHECKING", "FINDING_RECORDED"))
        assertEquals("FINDINGS_RECORDED", HandoverInternalReviewPolicy.next("FINDINGS_RECORDED", "FINDING_RECORDED"))
        assertEquals("RECHECK_REQUIRED", HandoverInternalReviewPolicy.next("FINDINGS_RECORDED", "RECHECK_REQUESTED"))
    }

    @Test
    fun blocksApprovalIssueAndSkippingReview() {
        assertNull(HandoverInternalReviewPolicy.next(null, "INTERNAL_APPROVED"))
        assertNull(HandoverInternalReviewPolicy.next(null, "FINDING_RECORDED"))
        assertNull(HandoverInternalReviewPolicy.next("CHECKING", "CLIENT_ACCEPTED"))
        assertNull(HandoverInternalReviewPolicy.next("CHECKING", "RECHECK_REQUESTED"))
        assertNull(HandoverInternalReviewPolicy.next("RECHECK_REQUIRED", "CHECK_STARTED"))
        assertNull(HandoverInternalReviewPolicy.next("FINDINGS_RECORDED", "ISSUED"))
    }
}
