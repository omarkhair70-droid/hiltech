package com.hiltech.spike.android.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.hiltech.spike.shared.local.OfflineBundleEntity
import com.hiltech.spike.shared.local.PendingCommandEntity
import com.hiltech.spike.shared.local.buildHiltechLocalDatabase
import com.hiltech.spike.shared.local.getAndroidDatabaseBuilder
import com.hiltech.spike.shared.network.HiltechApiClient
import com.hiltech.spike.shared.sync.SyncQueueState
import kotlinx.coroutines.runBlocking
import java.io.File

class Spike15Receiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val baseUrl = intent.getStringExtra(EXTRA_BASE_URL)
            ?: "http://10.0.2.2:8090"
        val token = requireNotNull(intent.getStringExtra(EXTRA_TOKEN)) {
            "SPIKE-15 token missing"
        }
        val scenario = intent.getStringExtra(EXTRA_SCENARIO)
            ?: "happy"
        val workOrderId = intent.getStringExtra(EXTRA_WORK_ORDER_ID)
            ?: "wo-42"

        when (intent.action) {
            ACTION_PREPARE ->
                runBlocking {
                    prepare(
                        context = context,
                        baseUrl = baseUrl,
                        token = token,
                        scenario = scenario,
                        workOrderId = workOrderId,
                    )
                }

            ACTION_QUEUE_OFFLINE ->
                runBlocking {
                    queueOfflineWork(
                        context = context,
                        scenario = scenario,
                        workOrderId = workOrderId,
                    )
                }

            ACTION_ENQUEUE_SYNC ->
                enqueueSync(
                    context = context,
                    baseUrl = baseUrl,
                    token = token,
                    scenario = scenario,
                    workOrderId = workOrderId,
                )

            else -> error("Unknown SPIKE-15 action: ${intent.action}")
        }
    }

    private suspend fun prepare(
        context: Context,
        baseUrl: String,
        token: String,
        scenario: String,
        workOrderId: String,
    ) {
        val database = buildHiltechLocalDatabase(
            getAndroidDatabaseBuilder(
                context = context,
                databaseName = "hiltech-spike15-$scenario.db",
            ),
        )

        try {
            database.pendingCommandDao().clear()
            database.offlineBundleDao().clear()

            HiltechApiClient(
                baseUrl = baseUrl,
                tokenProvider = { token },
            ).use { api ->
                val remote = api.getOfflineBundle(
                    workOrderId = workOrderId,
                    correlationId = "spike15-$scenario-bundle",
                )

                database.offlineBundleDao().upsert(
                    OfflineBundleEntity(
                        workOrderId = remote.workOrder.id,
                        projectId = remote.workOrder.projectId,
                        siteId = remote.workOrder.siteId,
                        assignee = remote.workOrder.assignee,
                        state = remote.workOrder.state,
                        serverVersion = remote.workOrder.version,
                        assetId = remote.assetId,
                        evidenceRequired = remote.evidenceRequired,
                        fetchedAtEpochMs = remote.fetchedAtEpochMs,
                    ),
                )

                Spike15State.write(
                    context,
                    scenario,
                    linkedMapOf(
                        "state" to "BUNDLE_READY",
                        "version" to remote.workOrder.version.toString(),
                        "workOrderId" to remote.workOrder.id,
                        "assetId" to remote.assetId,
                    ),
                )
            }
        } finally {
            database.close()
        }
    }

    private suspend fun queueOfflineWork(
        context: Context,
        scenario: String,
        workOrderId: String,
    ) {
        val database = buildHiltechLocalDatabase(
            getAndroidDatabaseBuilder(
                context = context,
                databaseName = "hiltech-spike15-$scenario.db",
            ),
        )

        try {
            val bundle = requireNotNull(
                database.offlineBundleDao().get(workOrderId),
            ) {
                "Offline bundle missing for $workOrderId"
            }

            require(bundle.assignee == "tech1") {
                "Expected tech1 assignment, got ${bundle.assignee}"
            }

            val evidence = File(
                context.filesDir,
                "spike15-$scenario-evidence.bin",
            )
            evidence.writeBytes(
                (
                    "HILTECH-SPIKE15-EVIDENCE|" +
                        scenario + "|" +
                        workOrderId + "|" +
                        bundle.assetId
                ).encodeToByteArray(),
            )

            val now = System.currentTimeMillis()

            database.pendingCommandDao().insert(
                PendingCommandEntity(
                    operationId = "spike15-$scenario-start",
                    commandType = "StartWork",
                    objectType = "work_order",
                    objectId = workOrderId,
                    baseVersion = bundle.serverVersion,
                    payloadJson = """{"assetId":"${bundle.assetId}"}""",
                    state = SyncQueueState.PENDING,
                    attemptCount = 0,
                    createdAtEpochMs = now,
                    lastAttemptAtEpochMs = null,
                    localSequence = 1,
                ),
            )

            database.pendingCommandDao().insert(
                PendingCommandEntity(
                    operationId = "spike15-$scenario-submit",
                    commandType = "SubmitWorkCompletion",
                    objectType = "work_order",
                    objectId = workOrderId,
                    baseVersion = bundle.serverVersion + 1,
                    payloadJson = """{"evidenceId":"spike15-$scenario-evidence"}""",
                    state = SyncQueueState.PENDING,
                    attemptCount = 0,
                    createdAtEpochMs = now + 1,
                    lastAttemptAtEpochMs = null,
                    localSequence = 2,
                ),
            )

            Spike15State.write(
                context,
                scenario,
                linkedMapOf(
                    "state" to "PENDING",
                    "bundleVersion" to bundle.serverVersion.toString(),
                    "evidenceExists" to evidence.exists().toString(),
                    "queueCount" to database.pendingCommandDao().count().toString(),
                ),
            )
        } finally {
            database.close()
        }
    }

    private fun enqueueSync(
        context: Context,
        baseUrl: String,
        token: String,
        scenario: String,
        workOrderId: String,
    ) {
        val request = OneTimeWorkRequestBuilder<Spike15SyncWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(
                workDataOf(
                    Spike15SyncWorker.KEY_BASE_URL to baseUrl,
                    Spike15SyncWorker.KEY_TOKEN to token,
                    Spike15SyncWorker.KEY_SCENARIO to scenario,
                    Spike15SyncWorker.KEY_WORK_ORDER_ID to workOrderId,
                ),
            )
            .addTag("hiltech-spike15")
            .addTag("hiltech-spike15-$scenario")
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "hiltech-spike15-$scenario",
                ExistingWorkPolicy.REPLACE,
                request,
            )

        Spike15State.write(
            context,
            scenario,
            linkedMapOf(
                "state" to "QUEUED",
                "evidenceExists" to File(
                    context.filesDir,
                    "spike15-$scenario-evidence.bin",
                ).exists().toString(),
            ),
        )
    }

    companion object {
        const val ACTION_PREPARE = "com.hiltech.spike.SPIKE15_PREPARE"
        const val ACTION_QUEUE_OFFLINE = "com.hiltech.spike.SPIKE15_QUEUE_OFFLINE"
        const val ACTION_ENQUEUE_SYNC = "com.hiltech.spike.SPIKE15_ENQUEUE_SYNC"

        const val EXTRA_BASE_URL = "baseUrl"
        const val EXTRA_TOKEN = "token"
        const val EXTRA_SCENARIO = "scenario"
        const val EXTRA_WORK_ORDER_ID = "workOrderId"
    }
}
