package com.hiltech.spike.android.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class SyncProbeWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(
    appContext,
    workerParams,
) {
    override fun doWork(): Result {
        val probeId = inputData.getString(KEY_PROBE_ID)
            ?: return Result.failure()

        val retryOnce = inputData.getBoolean(
            KEY_RETRY_ONCE,
            false,
        )

        val humanAttempt = runAttemptCount + 1

        if (retryOnce && runAttemptCount == 0) {
            SyncProbeState.write(
                context = applicationContext,
                probeId = probeId,
                state = "RETRYABLE",
                attempts = humanAttempt,
            )
            return Result.retry()
        }

        SyncProbeState.write(
            context = applicationContext,
            probeId = probeId,
            state = "SYNCED",
            attempts = humanAttempt,
        )

        return Result.success()
    }

    companion object {
        const val KEY_PROBE_ID = "probe_id"
        const val KEY_RETRY_ONCE = "retry_once"
    }
}
