package com.hiltech.spike.android.camera

import org.junit.Test
import kotlin.test.assertEquals

class EvidenceUploadStateTest {
    @Test
    fun local_evidence_survives_retry_then_uploads_without_losing_identity() {
        val local = EvidenceUploadRecord(
            evidenceId = "evidence-001",
            localPath = "/private/evidence/evidence-001.jpg",
            sha256 = "abc123",
            state = EvidenceUploadState.LOCAL_READY,
            attempts = 0,
        )

        val attempt1 = local.beginUpload()
        val retryable = attempt1.retryableFailure()
        val attempt2 = retryable.beginUpload()
        val uploaded = attempt2.markUploaded()

        assertEquals(EvidenceUploadState.UPLOADED, uploaded.state)
        assertEquals(2, uploaded.attempts)
        assertEquals(local.evidenceId, uploaded.evidenceId)
        assertEquals(local.localPath, uploaded.localPath)
        assertEquals(local.sha256, uploaded.sha256)
    }
}
