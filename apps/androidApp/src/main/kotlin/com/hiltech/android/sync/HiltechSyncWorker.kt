package com.hiltech.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hiltech.android.HiltechApplication
import com.hiltech.android.field.AndroidEvidenceSyncOutcome

class HiltechSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as? HiltechApplication)?.syncRuntime
            ?: return failure("SYNC_RUNTIME_UNAVAILABLE")

        return when (val outcome = runtime.runOnce()) {
            is AndroidSyncRunResult.Completed -> {
                // Only after online identity bootstrap and scoped command replay.
                val evidence = (applicationContext as HiltechApplication)
                    .fieldEvidenceSyncRuntime.runOnce()
                when (evidence) {
                    AndroidEvidenceSyncOutcome.Retry -> Result.retry()
                    AndroidEvidenceSyncOutcome.ReauthenticationRequired -> failure("REAUTH_REQUIRED")
                    is AndroidEvidenceSyncOutcome.Completed -> Result.success(
                        workDataOf(
                            ATTEMPTED_KEY to outcome.summary.attempted,
                            APPLIED_KEY to outcome.summary.applied,
                            CONFLICTS_KEY to outcome.summary.conflicts,
                            TERMINAL_KEY to outcome.summary.terminal,
                            EVIDENCE_READY_KEY to evidence.summary.ready,
                            EVIDENCE_REVIEW_KEY to evidence.summary.reviewRequired,
                            EVIDENCE_QUARANTINED_KEY to evidence.summary.quarantined,
                        ),
                    )
                }
            }

            AndroidSyncRunResult.Retry ->
                Result.retry()

            AndroidSyncRunResult.ReauthenticationRequired ->
                failure("REAUTH_REQUIRED")

            AndroidSyncRunResult.RuntimeNotConfigured ->
                failure("SYNC_RUNTIME_NOT_CONFIGURED")
        }
    }

    private fun failure(code: String): Result =
        Result.failure(
            workDataOf(
                ERROR_CODE_KEY to code,
            ),
        )

    companion object {
        const val ERROR_CODE_KEY = "hiltech_error_code"
        const val ATTEMPTED_KEY = "hiltech_attempted"
        const val APPLIED_KEY = "hiltech_applied"
        const val CONFLICTS_KEY = "hiltech_conflicts"
        const val TERMINAL_KEY = "hiltech_terminal"
        const val EVIDENCE_READY_KEY = "hiltech_evidence_ready"
        const val EVIDENCE_REVIEW_KEY = "hiltech_evidence_review"
        const val EVIDENCE_QUARANTINED_KEY = "hiltech_evidence_quarantined"
    }
}
