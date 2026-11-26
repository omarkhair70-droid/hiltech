package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

class WarrantyCoverageReadinessPolicyTest {
    private val project = UUID.randomUUID().toString()
    private val ticketId = UUID.randomUUID().toString()
    private val contract = UUID.randomUUID().toString()
    private val po = UUID.randomUUID().toString()

    private fun source(
        state: String = "SIGNED_RECORD_STORAGE_READY_AWARD_NOT_REVALIDATED",
        hasTerms: Boolean = true,
    ) = ProjectServiceContractSourceDto(
        projectId = project,
        sourceState = state,
        contractId = contract,
        clientPoId = po,
        sourceEffectiveDate = "2026-11-23",
        sourceEndDate = "2028-11-23",
        hasWrittenWarrantyTerms = hasTerms,
        hasWrittenMaintenanceTerms = true,
    )

    private fun ticket() = InternalSupportTicketDto(
        ticketId = ticketId,
        projectId = project,
        clientOrganizationId = UUID.randomUUID().toString(),
        title = "Equipment issue",
        description = "Reported by client",
        severityCode = "HIGH",
        priorityCode = "P1",
        lifecycleState = "OPEN",
        coverageState = "NOT_EVALUATED",
        slaState = "NOT_CONFIGURED",
        clientVisibility = "INTERNAL_ONLY",
        openedByUserId = UUID.randomUUID().toString(),
        createdAt = "2026-11-23T01:00:00Z",
        duplicateReplay = false,
    )

    @Test
    fun signedEvidenceAndDatesCannotActivateWarrantyOrSla() {
        val result = WarrantyCoverageReadinessPolicy.evaluate(source(), ticket())
        assertEquals("NOT_EVALUATED", result.coverageDecisionState)
        assertEquals("NOT_APPROVED", result.approvalState)
        assertEquals("NOT_VERIFIED", result.coveredAssetOrSiteState)
        assertTrue("P10_AWARD_GATE_NOT_REVALIDATED" in result.blockerCodes)
        assertTrue("COMPANY_WARRANTY_POLICY_NOT_APPROVED" in result.blockerCodes)
        assertTrue("WARRANTY_START_AND_END_NOT_APPROVED" in result.blockerCodes)
        assertFalse(result.warrantyApproved)
        assertFalse(result.slaAuthorized)
        assertFalse(result.billableServiceAuthorized)
        assertFalse(result.clientMayBeNotified)
        assertEquals("INTERNAL_ONLY", result.clientVisibility)
    }

    @Test
    fun undeployedSourceIsNotMistakenForOutOfWarranty() {
        val result = WarrantyCoverageReadinessPolicy.evaluate(
            source("P10_SOURCE_NOT_DEPLOYED", false), ticket(),
        )
        assertTrue("P10_SOURCE_NOT_DEPLOYED" in result.blockerCodes)
        assertTrue("WRITTEN_WARRANTY_TERMS_MISSING" in result.blockerCodes)
        assertEquals("NOT_EVALUATED", result.coverageDecisionState)
    }

    @Test
    fun absentOrUnverifiedContractHasExplicitBlockers() {
        val absent = WarrantyCoverageReadinessPolicy.evaluate(
            source("NO_SIGNED_COMMERCIAL_SOURCE", false), ticket(),
        )
        assertTrue("SIGNED_PROJECT_CONTRACT_MISSING" in absent.blockerCodes)
        val pending = WarrantyCoverageReadinessPolicy.evaluate(
            source("SIGNED_SOURCE_DOCUMENT_VERIFICATION_PENDING"), ticket(),
        )
        assertTrue("SIGNED_CONTRACT_AND_PO_EVIDENCE_NOT_VERIFIED" in pending.blockerCodes)
    }

    @Test
    fun unknownProviderStateFailsClosedAndProjectMismatchIsRejected() {
        val unknown = WarrantyCoverageReadinessPolicy.evaluate(source("FUTURE_UNKNOWN_STATE"), ticket())
        assertTrue("COMMERCIAL_SOURCE_STATE_NOT_TRUSTED" in unknown.blockerCodes)
        assertFalse(unknown.warrantyApproved)
        assertThrows(IllegalArgumentException::class.java) {
            WarrantyCoverageReadinessPolicy.evaluate(
                source(), ticket().copy(projectId = UUID.randomUUID().toString()),
            )
        }
    }
}
