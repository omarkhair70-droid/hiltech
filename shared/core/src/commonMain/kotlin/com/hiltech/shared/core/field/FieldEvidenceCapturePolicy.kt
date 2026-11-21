package com.hiltech.shared.core.field

import com.hiltech.shared.core.work.FieldEvidenceRequirementDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import com.hiltech.shared.core.local.LocalEvidenceEntity
import com.hiltech.shared.core.local.LocalEvidenceStates

/** Client capture hint only; server source authority and bound EvidencePolicy are rechecked on reserve/finalize. */
data class FieldEvidenceCaptureBinding(
    val workOrderId: String,
    val workOrderVersion: Long,
    val assignmentId: String,
    val assignmentVersion: Long,
    val bindingRevision: Int,
    val evidencePolicyId: String,
    val evidencePolicyRevision: Int,
    val requirement: FieldEvidenceRequirementDto,
    val mimeType: String,
)

object FieldEvidenceCapturePolicy {
    const val MAX_BYTES: Long = 16L * 1024L * 1024L

    /**
     * Count not-yet-reflected local captures as well as remote READY evidence.
     * A local READY row with serverEvidenceId already in the JobBundle is ONE
     * logical evidence, not two. Reject/explicit discard never consume quota.
     */
    fun unreflectedLocalCount(
        bundle: TechnicianJobBundleDto,
        requirementKey: String,
        local: List<LocalEvidenceEntity>,
    ): Int {
        val alreadyShown = bundle.existingEvidence
            .filter { it.requirementKey == requirementKey && it.storageState == "READY" }
            .map { it.evidenceId }
            .toSet()
        return local.count { item ->
            item.requirementKey == requirementKey &&
                item.localState !in setOf(
                    LocalEvidenceStates.REJECTED,
                    LocalEvidenceStates.DISCARDED_EXPLICITLY,
                ) &&
                (item.serverEvidenceId == null || item.serverEvidenceId !in alreadyShown)
        }
    }



    fun bind(
        bundle: TechnicianJobBundleDto,
        actorId: String,
        installationId: String,
        requirementKey: String,
        contentType: String,
        sizeBytes: Long,
        verifiedOnlineThisSession: Boolean,
        currentLocalCaptures: Int,
    ): FieldEvidenceCaptureBinding {
        require(verifiedOnlineThisSession) {
            "Offline-only/stale identity cannot create an Evidence capture."
        }
        require(actorId.isNotBlank() && installationId.isNotBlank())
        require(bundle.workOrderVersion > 0 && bundle.assignment.version > 0)
        require(bundle.lifecycleState !in setOf("CANCELLED", "ACCEPTED", "CLOSED"))
        require(bundle.project.lifecycleState == "ACTIVE")
        require(bundle.executionCapabilities.evidenceCaptureOffline) {
            "Current assigned Work source has not authorized field capture."
        }
        val binding = requireNotNull(bundle.binding) { "A bound evidence policy is mandatory." }
        require(binding.evidencePolicy.revision > 0 && binding.bindingRevision > 0)
        val requirement = bundle.evidenceRequirements.singleOrNull { it.requirementKey == requirementKey }
            ?: throw IllegalArgumentException("Evidence requirement was not found or is ambiguous.")
        require(requirement.offlineCaptureAllowedByPolicy) {
            "This Evidence requirement does not permit local capture."
        }
        require(contentType.isNotBlank() && contentType in requirement.allowedContentTypes) {
            "The selected file type is not permitted by the current EvidencePolicy."
        }
        require(sizeBytes in 1..MAX_BYTES) {
            "Evidence must be nonempty and no larger than 16 MiB."
        }
        val knownExisting = bundle.existingEvidence.count {
            it.requirementKey == requirementKey && it.storageState == "READY"
        }
        require(currentLocalCaptures >= 0)
        require(requirement.maxCount == null ||
            knownExisting + currentLocalCaptures < requirement.maxCount) {
            "Evidence count would exceed the current bound policy."
        }
        return FieldEvidenceCaptureBinding(
            workOrderId = bundle.workOrderId,
            workOrderVersion = bundle.workOrderVersion,
            assignmentId = bundle.assignment.assignmentId,
            assignmentVersion = bundle.assignment.version,
            bindingRevision = binding.bindingRevision,
            evidencePolicyId = binding.evidencePolicy.id,
            evidencePolicyRevision = binding.evidencePolicy.revision,
            requirement = requirement,
            mimeType = contentType,
        )
    }
}
