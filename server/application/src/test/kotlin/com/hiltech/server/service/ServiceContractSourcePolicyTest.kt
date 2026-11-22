package com.hiltech.server.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.UUID

class ServiceContractSourcePolicyTest {
    private val project = UUID.randomUUID()
    private val signed = CommercialServiceSourceFact(
        contractId = UUID.randomUUID(),
        clientPoId = UUID.randomUUID(),
        effectiveDate = "2026-11-23",
        endDate = "2028-11-23",
        signedEvidenceReady = true,
        verifiedPoEvidenceReady = true,
        hasWarrantyText = true,
        hasMaintenanceText = true,
    )

    @Test
    fun absentP10DependencyIsUnavailableNotWarrantyActive() {
        val dto = ServiceContractSourcePolicy.evaluate(project, false, null)
        assertEquals("P10_SOURCE_NOT_DEPLOYED", dto.sourceState)
        assertEquals("NOT_EVALUATED", dto.warrantyEntitlementState)
        assertEquals("NOT_CONFIGURED", dto.slaPolicyState)
        assertEquals("NOT_APPROVED", dto.maintenanceScheduleState)
        assertEquals("INTERNAL_ONLY", dto.clientVisibility)
    }

    @Test
    fun lackOfSignedSourceMustNotImplyClientEntitlement() {
        val dto = ServiceContractSourcePolicy.evaluate(project, true, null)
        assertEquals("NO_SIGNED_COMMERCIAL_SOURCE", dto.sourceState)
        assertEquals(null, dto.contractId)
        assertFalse(dto.hasWrittenWarrantyTerms)
    }

    @Test
    fun signedButUnverifiedEvidenceIsNotApprovedSource() {
        val dto = ServiceContractSourcePolicy.evaluate(
            project, true, signed.copy(verifiedPoEvidenceReady = false),
        )
        assertEquals("SIGNED_SOURCE_DOCUMENT_VERIFICATION_PENDING", dto.sourceState)
        assertEquals("NOT_EVALUATED", dto.warrantyEntitlementState)
        assertEquals("NOT_EVALUATED", dto.renewalEligibilityState)
    }

    @Test
    fun evenReadySignedRecordsDoNotActivateWarrantyScheduleOrRenewal() {
        val dto = ServiceContractSourcePolicy.evaluate(project, true, signed)
        assertEquals("SIGNED_SOURCE_DOCUMENTS_READY", dto.sourceState)
        assertEquals(signed.contractId.toString(), dto.contractId)
        assertEquals(signed.effectiveDate, dto.sourceEffectiveDate)
        assertEquals(signed.endDate, dto.sourceEndDate)
        assertEquals(true, dto.hasWrittenWarrantyTerms)
        assertEquals(true, dto.hasWrittenMaintenanceTerms)
        assertEquals("NOT_EVALUATED", dto.warrantyEntitlementState)
        assertEquals("NOT_CONFIGURED", dto.slaPolicyState)
        assertEquals("NOT_APPROVED", dto.maintenanceScheduleState)
        assertEquals("NOT_EVALUATED", dto.renewalEligibilityState)
    }
}
