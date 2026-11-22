package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class HandoverReadinessPolicyTest {
    private val work = UUID.fromString("00000000-0000-0000-0000-000000000011")
    private val doc = UUID.fromString("00000000-0000-0000-0000-000000000012")
    private val revision = UUID.fromString("00000000-0000-0000-0000-000000000013")
    private val evidence = UUID.fromString("00000000-0000-0000-0000-000000000014")

    private fun approved(requirement: String = "AS_BUILT") = HandoverDocumentFact(
        work, doc, requirement, revision, evidence, "a".repeat(64),
    )

    @Test
    fun emptyProjectIsNotComplete() {
        val reasons = HandoverReadinessPolicy.missing(emptyList(), emptyList(), emptyList())
        assertTrue("PROJECT_WORK_ORDERS_MISSING" in reasons)
        assertTrue(HandoverReadinessPolicy.MISSING_PACKAGE_POLICY in reasons)
        assertTrue(HandoverReadinessPolicy.MISSING_SHARING_POLICY in reasons)
        assertTrue(HandoverReadinessPolicy.MISSING_ACCEPTANCE_AUTHORITY in reasons)
    }

    @Test
    fun requiredDocumentNeedsActualApprovedRevision() {
        val works = listOf(HandoverWorkFact(work, "ACCEPTED"))
        val requirements = listOf(HandoverRequirementFact(work, "AS_BUILT", "SATISFIED"))
        val missing = HandoverReadinessPolicy.missing(works, requirements, emptyList())
        assertTrue(missing.any { it.startsWith("APPROVED_DOCUMENT_MISSING:") })
        val present = HandoverReadinessPolicy.missing(works, requirements, listOf(approved()))
        assertFalse(present.any { it.startsWith("APPROVED_DOCUMENT_MISSING:") })
        assertTrue(HandoverReadinessPolicy.MISSING_PACKAGE_POLICY in present)
    }

    @Test
    fun workMustBeIndependentlyAccepted() {
        val missing = HandoverReadinessPolicy.missing(
            listOf(HandoverWorkFact(work, "SUBMITTED_FOR_REVIEW")),
            emptyList(), listOf(approved()),
        )
        assertTrue(missing.any { it.startsWith("WORK_NOT_ACCEPTED:") })
    }

    @Test
    fun recordedWaiverIsNotFakedAsDocument() {
        val missing = HandoverReadinessPolicy.missing(
            listOf(HandoverWorkFact(work, "CLOSED")),
            listOf(HandoverRequirementFact(work, "SPECIAL_TEST", "WAIVED")),
            emptyList(),
        )
        assertFalse(missing.any { it.startsWith("APPROVED_DOCUMENT_MISSING:") })
        assertTrue(HandoverReadinessPolicy.MISSING_PACKAGE_POLICY in missing)
    }

    @Test
    fun digestChangesWhenWorkAcceptanceOrRequirementStateChanges() {
        val documents = listOf(approved())
        val accepted = listOf(HandoverWorkFact(work, "ACCEPTED"))
        val inReview = listOf(HandoverWorkFact(work, "SUBMITTED_FOR_REVIEW"))
        val required = listOf(HandoverRequirementFact(work, "AS_BUILT", "SATISFIED"))
        val waived = listOf(HandoverRequirementFact(work, "AS_BUILT", "WAIVED"))
        val original = HandoverReadinessPolicy.digest(documents, accepted, required)
        assertFalse(original == HandoverReadinessPolicy.digest(documents, inReview, required))
        assertFalse(original == HandoverReadinessPolicy.digest(documents, accepted, waived))
        assertEquals(
            original,
            HandoverReadinessPolicy.digest(documents.reversed(), accepted.reversed(), required.reversed()),
        )
    }

    @Test
    fun digestDetectsProjectWorkAndRequirementVersionChanges() {
        val documents = listOf(approved())
        val work = listOf(HandoverWorkFact(this.work, "ACCEPTED", 17))
        val requirements = listOf(HandoverRequirementFact(this.work, "AS_BUILT", "SATISFIED", 4))
        val base = HandoverReadinessPolicy.digest(documents, work, requirements, 8)
        assertFalse(base == HandoverReadinessPolicy.digest(documents,
            work.map { it.copy(version = it.version + 1) }, requirements, 8))
        assertFalse(base == HandoverReadinessPolicy.digest(documents,
            work, requirements.map { it.copy(version = it.version + 1) }, 8))
        assertFalse(base == HandoverReadinessPolicy.digest(documents,
            work, requirements, 9))
    }

    @Test
    fun digestIsDeterministicAndSensitiveToEvidence() {
        val another = approved().copy(
            documentId = UUID.fromString("00000000-0000-0000-0000-000000000015"),
            revisionId = UUID.fromString("00000000-0000-0000-0000-000000000016"),
        )
        val a = HandoverReadinessPolicy.digest(listOf(approved(), another))
        val b = HandoverReadinessPolicy.digest(listOf(another, approved()))
        val changed = HandoverReadinessPolicy.digest(
            listOf(another, approved().copy(sourceSha256 = "b".repeat(64))),
        )
        assertEquals(a, b)
        assertFalse(a == changed)
        assertEquals(64, a.length)
    }
}
