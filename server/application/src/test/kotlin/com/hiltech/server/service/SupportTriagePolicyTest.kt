package com.hiltech.server.service

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class SupportTriagePolicyTest {
    @Test
    fun activeOwnerIdRequiredAndCannotCarryClassification() {
        val owner = UUID.randomUUID()
        val valid = SupportTriagePolicy.validate("OWNER_ASSIGNED", owner.toString(), null, null, "PM selected active desk owner")
        assertEquals("OWNER_ASSIGNED", valid.eventType)
        assertEquals(owner, valid.ownerEmployeeId)
        assertNull(valid.proposedPriorityCode)

        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("OWNER_ASSIGNED", "bad-uuid", null, null, "reason")
        }
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("OWNER_ASSIGNED", owner.toString(), "HIGH", null, "reason")
        }
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("OWNER_ASSIGNED", null, null, null, "reason")
        }
    }

    @Test
    fun classificationIsOnlyAnInternalRecommendationWithMandatoryReason() {
        val valid = SupportTriagePolicy.validate("CLASSIFICATION_RECORDED", null, "FAULT", "URGENT", "Fault classified by manager")
        assertEquals("FAULT", valid.proposedSeverityCode)
        assertEquals("URGENT", valid.proposedPriorityCode)
        assertNull(valid.ownerEmployeeId)

        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("CLASSIFICATION_RECORDED", null, "FAULT", null, "reason")
        }
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("CLASSIFICATION_RECORDED", null, "FAULT", "urgent-lowercase", "reason")
        }
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("CLASSIFICATION_RECORDED", null, "FAULT", "URGENT", "  ")
        }
    }

    @Test
    fun deskOwnerLedgerRequiresRealChangesAndCannotReleaseNobody() {
        val first = UUID.fromString("11111111-1111-4111-8111-111111111111")
        val second = UUID.fromString("22222222-2222-4222-8222-222222222222")
        org.junit.jupiter.api.Assertions.assertFalse(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_RELEASED", null, null))
        org.junit.jupiter.api.Assertions.assertTrue(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_ASSIGNED", null, first))
        org.junit.jupiter.api.Assertions.assertFalse(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_ASSIGNED", first, first))
        org.junit.jupiter.api.Assertions.assertTrue(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_ASSIGNED", first, second))
        org.junit.jupiter.api.Assertions.assertTrue(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_RELEASED", second, null))
        org.junit.jupiter.api.Assertions.assertFalse(
            SupportTriagePolicy.ownerChangeAllowed("OWNER_RELEASED", null, null))
        org.junit.jupiter.api.Assertions.assertTrue(
            SupportTriagePolicy.ownerChangeAllowed("CLASSIFICATION_RECORDED", first, null))
    }

    @Test
    fun releaseRequiresNoOwnerOrClassification() {
        val valid = SupportTriagePolicy.validate("OWNER_RELEASED", null, null, null, "Owner unavailable")
        assertNull(valid.ownerEmployeeId)
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("OWNER_RELEASED", UUID.randomUUID().toString(), null, null, "reason")
        }
        assertThrows(ProductApiException::class.java) {
            SupportTriagePolicy.validate("COMPLETE_WITHIN_24_HOURS", null, null, null, "forbidden inferred SLA")
        }
    }
}
