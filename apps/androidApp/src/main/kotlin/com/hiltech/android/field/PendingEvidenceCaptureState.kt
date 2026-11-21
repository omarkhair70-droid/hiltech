package com.hiltech.android.field

import android.os.Bundle
import java.io.File
import com.hiltech.shared.core.work.TechnicianJobBundleDto

/**
 * Restores only the pending system picker/camera request after Activity recreation.
 * It does not persist permissions, grant access to a different user, or authorize
 * Evidence. MainActivity still revalidates the current signed-in actor and JobBundle
 * before any local Room capture.
 */
internal data class PendingEvidenceCapture(
    val actorId: String,
    val workOrderId: String,
    val requirementKey: String,
    val assignmentId: String,
    val assignmentVersion: Long,
    val workOrderVersion: Long,
    val bindingRevision: Int,
    val evidencePolicyId: String,
    val evidencePolicyRevision: Int,
    val cameraFile: File? = null,
) {
    /** File picking/camera must never silently rebind evidence to changed work. */
    fun matches(bundle: TechnicianJobBundleDto): Boolean {
        val policy = bundle.binding ?: return false
        return bundle.workOrderId == workOrderId &&
            bundle.assignment.assignmentId == assignmentId &&
            bundle.assignment.version == assignmentVersion &&
            bundle.workOrderVersion == workOrderVersion &&
            policy.bindingRevision == bindingRevision &&
            policy.evidencePolicy.id == evidencePolicyId &&
            policy.evidencePolicy.revision == evidencePolicyRevision &&
            bundle.evidenceRequirements.any { it.requirementKey == requirementKey }
    }
}

internal object PendingEvidenceCaptureState {
    private const val ACTOR_KEY = "hiltech.pendingEvidence.actor"
    private const val WORK_ORDER_KEY = "hiltech.pendingEvidence.workOrder"
    private const val REQUIREMENT_KEY = "hiltech.pendingEvidence.requirement"
    private const val CAMERA_NAME_KEY = "hiltech.pendingEvidence.cameraFileName"
    private const val ASSIGNMENT_ID_KEY = "hiltech.pendingEvidence.assignmentId"
    private const val ASSIGNMENT_VERSION_KEY = "hiltech.pendingEvidence.assignmentVersion"
    private const val WORK_VERSION_KEY = "hiltech.pendingEvidence.workVersion"
    private const val BINDING_VERSION_KEY = "hiltech.pendingEvidence.bindingRevision"
    private const val POLICY_ID_KEY = "hiltech.pendingEvidence.policyId"
    private const val POLICY_VERSION_KEY = "hiltech.pendingEvidence.policyRevision"

    // A generated camera filename, never an arbitrary absolute or external path.
    private val cameraFileName = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.jpg",
    )

    fun save(outState: Bundle, request: PendingEvidenceCapture?) {
        if (request == null) return
        outState.putString(ACTOR_KEY, request.actorId)
        outState.putString(WORK_ORDER_KEY, request.workOrderId)
        outState.putString(REQUIREMENT_KEY, request.requirementKey)
        outState.putString(ASSIGNMENT_ID_KEY, request.assignmentId)
        outState.putLong(ASSIGNMENT_VERSION_KEY, request.assignmentVersion)
        outState.putLong(WORK_VERSION_KEY, request.workOrderVersion)
        outState.putInt(BINDING_VERSION_KEY, request.bindingRevision)
        outState.putString(POLICY_ID_KEY, request.evidencePolicyId)
        outState.putInt(POLICY_VERSION_KEY, request.evidencePolicyRevision)
        request.cameraFile?.name?.let { filename ->
            if (cameraFileName.matches(filename)) {
                outState.putString(CAMERA_NAME_KEY, filename)
            }
        }
    }

    fun restore(savedState: Bundle?, cameraDirectory: File): PendingEvidenceCapture? {
        if (savedState == null) return null
        val actor = savedState.getString(ACTOR_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val workOrder = savedState.getString(WORK_ORDER_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val requirement = savedState.getString(REQUIREMENT_KEY)?.takeIf { it.isNotBlank() } ?: return null
        val assignment = savedState.getString(ASSIGNMENT_ID_KEY)?.takeIf { it.isNotBlank() }
            ?: return null
        val policyId = savedState.getString(POLICY_ID_KEY)?.takeIf { it.isNotBlank() }
            ?: return null
        if (!savedState.containsKey(ASSIGNMENT_VERSION_KEY) ||
            !savedState.containsKey(WORK_VERSION_KEY) ||
            !savedState.containsKey(BINDING_VERSION_KEY) ||
            !savedState.containsKey(POLICY_VERSION_KEY)) return null
        val assignmentVersion = savedState.getLong(ASSIGNMENT_VERSION_KEY)
        val workVersion = savedState.getLong(WORK_VERSION_KEY)
        val bindingRevision = savedState.getInt(BINDING_VERSION_KEY)
        val policyRevision = savedState.getInt(POLICY_VERSION_KEY)
        if (assignmentVersion < 1 || workVersion < 1 || bindingRevision < 1 || policyRevision < 1) {
            return null
        }
        val filename = savedState.getString(CAMERA_NAME_KEY)
        if (filename != null && !cameraFileName.matches(filename)) return null
        return PendingEvidenceCapture(
            actorId = actor,
            workOrderId = workOrder,
            requirementKey = requirement,
            assignmentId = assignment,
            assignmentVersion = assignmentVersion,
            workOrderVersion = workVersion,
            bindingRevision = bindingRevision,
            evidencePolicyId = policyId,
            evidencePolicyRevision = policyRevision,
            cameraFile = filename?.let { File(cameraDirectory, it) },
        )
    }
}
