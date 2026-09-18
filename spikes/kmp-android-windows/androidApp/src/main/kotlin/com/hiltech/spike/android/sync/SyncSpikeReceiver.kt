package com.hiltech.spike.android.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

class SyncSpikeReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val probeId = intent.getStringExtra(EXTRA_PROBE_ID)
            ?: "default"

        val retryOnce = intent.getBooleanExtra(
            EXTRA_RETRY_ONCE,
            false,
        )

        val batteryNotLow = intent.getBooleanExtra(
            EXTRA_BATTERY_NOT_LOW,
            false,
        )

        SyncProbeState.write(
            context = context,
            probeId = probeId,
            state = "QUEUED",
            attempts = 0,
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(batteryNotLow)
            .build()

        val request = OneTimeWorkRequestBuilder<SyncProbeWorker>()
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                10,
                TimeUnit.SECONDS,
            )
            .setInputData(
                workDataOf(
                    SyncProbeWorker.KEY_PROBE_ID to probeId,
                    SyncProbeWorker.KEY_RETRY_ONCE to retryOnce,
                ),
            )
            .addTag("hiltech-sync-spike")
            .addTag("hiltech-sync-$probeId")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "hiltech-sync-$probeId",
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }

    companion object {
        const val EXTRA_PROBE_ID = "probeId"
        const val EXTRA_RETRY_ONCE = "retryOnce"
        const val EXTRA_BATTERY_NOT_LOW = "batteryNotLow"
    }
}
