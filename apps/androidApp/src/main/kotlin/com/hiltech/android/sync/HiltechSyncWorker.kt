package com.hiltech.android.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf

class HiltechSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result =
        Result.failure(
            workDataOf(
                ERROR_CODE_KEY to "SYNC_ENGINE_NOT_WIRED",
            ),
        )

    companion object {
        const val ERROR_CODE_KEY = "hiltech_error_code"
    }
}
