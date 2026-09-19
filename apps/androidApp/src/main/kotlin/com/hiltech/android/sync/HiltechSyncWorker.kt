package com.hiltech.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.hiltech.android.HiltechApplication

class HiltechSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val runtime = (applicationContext as? HiltechApplication)?.syncRuntime
            ?: return failure("SYNC_RUNTIME_UNAVAILABLE")

        return when (val outcome = runtime.runOnce()) {
            is AndroidSyncRunResult.Completed ->
                Result.success(
                    workDataOf(
                        ATTEMPTED_KEY to outcome.summary.attempted,
                        APPLIED_KEY to outcome.summary.applied,
                        CONFLICTS_KEY to outcome.summary.conflicts,
                        TERMINAL_KEY to outcome.summary.terminal,
                    ),
                )

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
    }
}
