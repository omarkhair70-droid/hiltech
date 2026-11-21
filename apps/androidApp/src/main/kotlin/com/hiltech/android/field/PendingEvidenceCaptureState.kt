package com.hiltech.android.field

import android.os.Bundle
import java.io.File

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
    val cameraFile: File? = null,
)

internal object PendingEvidenceCaptureState {
    private const val ACTOR_KEY = "hiltech.pendingEvidence.actor"
    private const val WORK_ORDER_KEY = "hiltech.pendingEvidence.workOrder"
    private const val REQUIREMENT_KEY = "hiltech.pendingEvidence.requirement"
    private const val CAMERA_NAME_KEY = "hiltech.pendingEvidence.cameraFileName"

    // A generated camera filename, never an arbitrary absolute or external path.
    private val cameraFileName = Regex(
        "[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\.jpg",
    )

    fun save(outState: Bundle, request: PendingEvidenceCapture?) {
        if (request == null) return
        outState.putString(ACTOR_KEY, request.actorId)
        outState.putString(WORK_ORDER_KEY, request.workOrderId)
        outState.putString(REQUIREMENT_KEY, request.requirementKey)
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
        val filename = savedState.getString(CAMERA_NAME_KEY)
        if (filename != null && !cameraFileName.matches(filename)) return null
        return PendingEvidenceCapture(
            actorId = actor,
            workOrderId = workOrder,
            requirementKey = requirement,
            cameraFile = filename?.let { File(cameraDirectory, it) },
        )
    }
}
