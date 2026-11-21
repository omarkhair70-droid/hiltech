package com.hiltech.shared.core.work

import com.hiltech.shared.core.local.FieldJobBundleCacheEntity
import com.hiltech.shared.core.local.FieldTodayCacheEntity
import com.hiltech.shared.core.local.HiltechLocalDatabase
import com.hiltech.shared.core.network.HiltechApiException
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.time.Clock

data class FieldReadSnapshot<T>(
    val value: T,
    val cached: Boolean,
    val fetchedAtEpochMs: Long? = null,
)

/** Read-only source evidence; never use a cached copy to authorize field commands. */
class FieldOfflineReadCache(
    private val database: HiltechLocalDatabase,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    suspend fun saveToday(identityId: String, items: List<FieldTodayItemDto>) {
        database.fieldOfflineCacheDao().upsertToday(
            FieldTodayCacheEntity(
                identityId,
                json.encodeToString(ListSerializer(FieldTodayItemDto.serializer()), items),
                nowEpochMs(),
            ),
        )
    }

    suspend fun cachedToday(identityId: String): FieldReadSnapshot<List<FieldTodayItemDto>>? {
        val row = database.fieldOfflineCacheDao().today(identityId) ?: return null
        val items = json.decodeFromString(
            ListSerializer(FieldTodayItemDto.serializer()), row.payloadJson,
        ).map { it.copy(currentlyActionable = false) }
        return FieldReadSnapshot(items, cached = true, fetchedAtEpochMs = row.fetchedAtEpochMs)
    }

    suspend fun saveBundle(identityId: String, bundle: TechnicianJobBundleDto) {
        val readOnly = bundle.copy(executionCapabilities = noOfflineExecution)
        database.fieldOfflineCacheDao().upsertBundle(
            FieldJobBundleCacheEntity(
                identityId = identityId,
                workOrderId = bundle.workOrderId,
                payloadJson = json.encodeToString(TechnicianJobBundleDto.serializer(), readOnly),
                fetchedAtEpochMs = nowEpochMs(),
                serverAsOf = bundle.asOf,
                workOrderVersion = bundle.workOrderVersion,
                assignmentId = bundle.assignment.assignmentId,
                assignmentVersion = bundle.assignment.version,
                bindingRevision = bundle.binding?.bindingRevision,
                instructionRevision = bundle.instruction?.revision,
            ),
        )
    }

    suspend fun cachedBundle(identityId: String, workOrderId: String): FieldReadSnapshot<TechnicianJobBundleDto>? {
        val row = database.fieldOfflineCacheDao().bundle(identityId, workOrderId) ?: return null
        val bundle = json.decodeFromString(TechnicianJobBundleDto.serializer(), row.payloadJson)
        require(bundle.workOrderId == workOrderId && row.assignmentId == bundle.assignment.assignmentId) {
            "Offline read source does not match its WorkOrder/assignment key."
        }
        return FieldReadSnapshot(
            bundle.copy(executionCapabilities = noOfflineExecution),
            cached = true,
            fetchedAtEpochMs = row.fetchedAtEpochMs,
        )
    }

    private val noOfflineExecution = FieldExecutionCapabilitiesDto(
        startOffline = false, blockOffline = false, resumeOffline = false,
        evidenceCaptureOffline = false, submitOffline = false,
        authoritativeOfflineQueue = false,
    )
}

/** Only network outages may return stale read data. Auth failures and revocations fail closed. */
class FieldAssignedWorkOfflineRepository(
    private val api: FieldAssignedWorkApiClient,
    private val cache: FieldOfflineReadCache,
) {
    suspend fun today(identityId: String, installationId: String): FieldReadSnapshot<List<FieldTodayItemDto>> =
        try {
            val latest = api.today(installationId)
            try { cache.saveToday(identityId, latest) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* online truth remains usable if disk is full */ }
            FieldReadSnapshot(latest, cached = false)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: HiltechApiException) {
              if (failure.code != "NETWORK_UNAVAILABLE") throw failure
              cache.cachedToday(identityId) ?: throw failure
          }

    suspend fun jobBundle(
        identityId: String,
        workOrderId: String,
        installationId: String,
    ): FieldReadSnapshot<TechnicianJobBundleDto> =
        try {
            val latest = api.jobBundle(workOrderId, installationId)
            try { cache.saveBundle(identityId, latest) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* online truth remains usable if disk is full */ }
            FieldReadSnapshot(latest, cached = false)
        } catch (cancelled: CancellationException) { throw cancelled }
          catch (failure: HiltechApiException) {
              if (failure.code != "NETWORK_UNAVAILABLE") throw failure
              cache.cachedBundle(identityId, workOrderId) ?: throw failure
          }
}
