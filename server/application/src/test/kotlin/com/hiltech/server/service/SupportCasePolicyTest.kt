package com.hiltech.server.service

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import com.hiltech.server.work.WorkOrderLifecycle
import org.junit.jupiter.api.Test
import java.util.UUID

class SupportCasePolicyTest {
    private val work = UUID.randomUUID().toString()
    private val evidence = UUID.randomUUID().toString()

    @Test
    fun canProgressFromOpenThroughVerifiedInternalClosure() {
        assertEquals("INVESTIGATING", SupportCasePolicy.next("OPEN", "INVESTIGATION_STARTED"))
        assertEquals("INVESTIGATING", SupportCasePolicy.next("INVESTIGATING", "WORK_ORDER_LINKED"))
        assertEquals("RESOLVED_INTERNAL", SupportCasePolicy.next("INVESTIGATING", "RESOLUTION_RECORDED"))
        assertEquals("CLOSED_INTERNAL", SupportCasePolicy.next("RESOLVED_INTERNAL", "INTERNAL_CLOSE_RECORDED"))
        assertEquals("INVESTIGATING", SupportCasePolicy.next("CLOSED_INTERNAL", "REOPENED"))
    }

    @Test
    fun cannotCloseWithoutInternalResolutionOrReopenOpenCase() {
        assertNull(SupportCasePolicy.next("OPEN", "INTERNAL_CLOSE_RECORDED"))
        assertNull(SupportCasePolicy.next("OPEN", "RESOLUTION_RECORDED"))
        assertNull(SupportCasePolicy.next("OPEN", "REOPENED"))
        assertNull(SupportCasePolicy.next("CLOSED_INTERNAL", "RESOLUTION_RECORDED"))
        assertNull(SupportCasePolicy.next("OPEN", "CLIENT_ACCEPTED"))
        assertNull(SupportCasePolicy.next("RESOLVED_INTERNAL", "WARRANTY_ACTIVATED"))
    }

    @Test
    fun cancelledSourceWorkCannotBeAttachedAsNewServiceInvestigation() {
        assertFalse(SupportCasePolicy.canLinkWork(WorkOrderLifecycle.CANCELLED))
        assertTrue(SupportCasePolicy.canLinkWork(WorkOrderLifecycle.DRAFT))
        assertTrue(SupportCasePolicy.canLinkWork(WorkOrderLifecycle.IN_PROGRESS))
    }

    @Test
    fun resolutionNeedsRealWorkAndEvidenceIds() {
        val valid = SupportCasePolicy.validate("RESOLUTION_RECORDED", "Verified accepted work", work, evidence)
        assertEquals(UUID.fromString(work), valid.workOrderId)
        assertEquals(UUID.fromString(evidence), valid.evidenceId)
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("RESOLUTION_RECORDED", "No evidence", work, null)
        }
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("RESOLUTION_RECORDED", "Not UUID", work, "not-uuid")
        }
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("WORK_ORDER_LINKED", "Must be a link", work, evidence)
        }
    }

    @Test
    fun forgedClientActionsAndUnsupportedFinancialFieldsRejected() {
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("CLIENT_CLOSED", "Self-declared customer acceptance", null, null)
        }
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("INTERNAL_CLOSE_RECORDED", "no field evidence on close", work, null)
        }
        assertThrows(ProductApiException::class.java) {
            SupportCasePolicy.validate("REOPENED", "  ", null, null)
        }
    }
}
