package com.hiltech.spike.android.camera

enum class EvidenceUploadState {
    LOCAL_READY,
    UPLOADING,
    RETRYABLE,
    UPLOADED,
}

data class EvidenceUploadRecord(
    val evidenceId: String,
    val localPath: String,
    val sha256: String,
    val state: EvidenceUploadState,
    val attempts: Int,
)

fun EvidenceUploadRecord.beginUpload(): EvidenceUploadRecord =
    copy(
        state = EvidenceUploadState.UPLOADING,
        attempts = attempts + 1,
    )

fun EvidenceUploadRecord.retryableFailure(): EvidenceUploadRecord {
    require(state == EvidenceUploadState.UPLOADING)

    return copy(
        state = EvidenceUploadState.RETRYABLE,
    )
}

fun EvidenceUploadRecord.markUploaded(): EvidenceUploadRecord {
    require(state == EvidenceUploadState.UPLOADING)

    return copy(
        state = EvidenceUploadState.UPLOADED,
    )
}
