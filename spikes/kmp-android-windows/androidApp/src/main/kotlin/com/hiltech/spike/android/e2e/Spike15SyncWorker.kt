package com.hiltech.spike.android.e2e

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.hiltech.spike.shared.local.buildHiltechLocalDatabase
import com.hiltech.spike.shared.local.getAndroidDatabaseBuilder
import com.hiltech.spike.shared.network.HiltechApiClient
import com.hiltech.spike.shared.sync.OfflineCommandSyncEngine
import com.hiltech.spike.shared.sync.SyncSendResult
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

class Spike15SyncWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(
    appContext,
    workerParams,
) {
    override fun doWork(): Result = runBlocking {
        val baseUrl = inputData.getString(KEY_BASE_URL)
            ?: return@runBlocking Result.failure()
        val token = inputData.getString(KEY_TOKEN)
            ?: return@runBlocking Result.failure()
        val scenario = inputData.getString(KEY_SCENARIO)
            ?: return@runBlocking Result.failure()
        val workOrderId = inputData.getString(KEY_WORK_ORDER_ID)
            ?: return@runBlocking Result.failure()

        val database = buildHiltechLocalDatabase(
            getAndroidDatabaseBuilder(
                context = applicationContext,
                databaseName = "hiltech-spike15-$scenario.db",
            ),
        )

        try {
            val evidenceFile = File(
                applicationContext.filesDir,
                "spike15-$scenario-evidence.bin",
            )
            require(evidenceFile.exists()) {
                "Local evidence disappeared before reconnect"
            }
            val bytes = evidenceFile.readBytes()
            val evidenceId = "spike15-$scenario-evidence"
            val shaHex = MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString("") { "%02x".format(it) }

            HiltechApiClient(
                baseUrl = baseUrl,
                tokenProvider = { token },
            ).use { api ->
                val reservation = api.reserveEvidence(
                    workOrderId = workOrderId,
                    evidenceId = evidenceId,
                    correlationId = "spike15-$scenario-evidence-reserve",
                    sha256Hex = shaHex,
                    contentType = "application/octet-stream",
                )

                val uploadStatus = api.uploadEvidenceBytes(
                    reservation = reservation,
                    bytes = bytes,
                )
                check(uploadStatus in 200..299) {
                    "Evidence PUT failed: $uploadStatus"
                }

                api.finalizeEvidence(
                    workOrderId = workOrderId,
                    evidenceId = evidenceId,
                    correlationId = "spike15-$scenario-evidence-finalize",
                )

                val engine = OfflineCommandSyncEngine(
                    dao = database.pendingCommandDao(),
                    transport = api.asSyncTransport(),
                )
                val summary = engine.syncOnce(
                    nowEpochMs = System.currentTimeMillis(),
                )

                val commands = database.pendingCommandDao().listAll()

                var duplicateReplay = "NOT_RUN"
                if (scenario == "happy") {
                    check(summary.applied == 2) {
                        "Expected two applied commands, got $summary"
                    }

                    val first = commands.first()
                    duplicateReplay =
                        when (api.replay(first)) {
                            is SyncSendResult.DuplicateApplied -> "PASS"
                            else -> error("Idempotent duplicate replay was not recognized")
                        }
                } else {
                    check(summary.conflicts == 1) {
                        "Expected one explicit conflict, got $summary"
                    }
                    check(commands.any { it.state == "BLOCKED_BY_CONFLICT" }) {
                        "Dependent command was not blocked after conflict"
                    }
                }

                val finalCommands = database.pendingCommandDao().listAll()
                Spike15State.write(
                    applicationContext,
                    scenario,
                    linkedMapOf(
                        "state" to if (scenario == "happy") "SYNCED" else "CONFLICT",
                        "attempted" to summary.attempted.toString(),
                        "applied" to summary.applied.toString(),
                        "conflicts" to summary.conflicts.toString(),
                        "duplicateReplay" to duplicateReplay,
                        "evidenceExists" to evidenceFile.exists().toString(),
                        "queueStates" to finalCommands.joinToString(",") {
                            it.state
                        },
                    ),
                )
            }

            return@runBlocking Result.success()
        } catch (throwable: Throwable) {
            Spike15State.write(
                applicationContext,
                scenario,
                linkedMapOf(
                    "state" to "FAILED",
                    "error" to (throwable.message ?: throwable::class.simpleName.orEmpty()),
                ),
            )
            return@runBlocking Result.failure()
        } finally {
            database.close()
        }
    }

    companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "token"
        const val KEY_SCENARIO = "scenario"
        const val KEY_WORK_ORDER_ID = "work_order_id"
    }
}
