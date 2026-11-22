package com.hiltech.android.field

import com.hiltech.shared.core.field.FieldEngineeringReplaySummary
import com.hiltech.shared.core.field.FieldEngineeringTestReplayCoordinator
import com.hiltech.shared.core.field.FieldEngineeringTestReplayRemote
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.work.FieldAssignedWorkApiClient
import com.hiltech.shared.core.work.FieldEngineeringTestCaptureDto
import com.hiltech.shared.core.work.FieldEngineeringTestReceiptDto
import com.hiltech.shared.core.work.TechnicianJobBundleDto
import kotlinx.coroutines.CancellationException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

fun interface FieldEngineeringTestOnlineIdentity {
    suspend fun currentVerifiedActorId(): String?
}

sealed interface AndroidEngineeringTestSyncOutcome {
    data class Completed(
        val summary: FieldEngineeringReplaySummary,
    ) : AndroidEngineeringTestSyncOutcome

    data object Retry : AndroidEngineeringTestSyncOutcome
    data object ReauthenticationRequired : AndroidEngineeringTestSyncOutcome
}

class AndroidFieldEngineeringTestSyncRuntime(
    private val db: HiltechLocalDatabase,
    private val assignedWork: FieldAssignedWorkApiClient,
    private val onlineIdentity: FieldEngineeringTestOnlineIdentity,
    private val installationId: String,
) {
    suspend fun runOnce(): AndroidEngineeringTestSyncOutcome {
        val actor =
            try {
                onlineIdentity.currentVerifiedActorId()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return if (
                    failure is HiltechApiException &&
                    failure.httpStatus in setOf(401, 403, 404)
                ) {
                    AndroidEngineeringTestSyncOutcome.ReauthenticationRequired
                } else {
                    AndroidEngineeringTestSyncOutcome.Retry
                }
            }?.takeIf { it.isNotBlank() }
                ?: return AndroidEngineeringTestSyncOutcome.ReauthenticationRequired

        val remote =
            object : FieldEngineeringTestReplayRemote {
                override suspend fun currentBundle(
                    workOrderId: String,
                    installationId: String,
                ): TechnicianJobBundleDto =
                    assignedWork.jobBundle(
                        workOrderId,
                        installationId,
                    )

                override suspend fun resolveInstrumentAssetId(
                    workOrderId: String,
                    opaqueTag: String,
                    installationId: String,
                ): String? =
                    assignedWork
                        .assignedAssetTag(
                            workOrderId,
                            opaqueTag,
                            installationId,
                        )
                        .assetId

                override suspend fun capture(
                    workOrderId: String,
                    request: FieldEngineeringTestCaptureDto,
                    installationId: String,
                ): FieldEngineeringTestReceiptDto =
                    assignedWork.captureEngineeringTest(
                        workOrderId,
                        request,
                        installationId,
                    )
            }

        val summary =
            FieldEngineeringTestReplayCoordinator(
                db = db,
                remote = remote,
                nowEpochMs = System::currentTimeMillis,
                formatEpochUtc = ::formatEpochUtc,
            ).replayOnce(
                actorId = actor,
                installationId = installationId,
            )

        return when {
            summary.reauthenticationRequired ->
                AndroidEngineeringTestSyncOutcome.ReauthenticationRequired
            summary.retryable > 0 ->
                AndroidEngineeringTestSyncOutcome.Retry
            else ->
                AndroidEngineeringTestSyncOutcome.Completed(summary)
        }
    }

    private fun formatEpochUtc(epoch: Long): String =
        SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            Locale.US,
        ).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(epoch))
}
