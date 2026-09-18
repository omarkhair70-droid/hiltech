package com.hiltech.spike.shared.network

import com.hiltech.spike.shared.local.PendingCommandEntity
import com.hiltech.spike.shared.sync.SyncSendResult
import com.hiltech.spike.shared.sync.SyncTransport
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.Serializable

@Serializable
data class WorkCommandRequest(
    val operationId: String,
    val correlationId: String,
    val commandType: String,
    val objectType: String,
    val objectId: String,
    val baseVersion: Long? = null,
    val payloadJson: String = "{}",
)

@Serializable
data class CommandResponse(
    val resultCode: String,
    val serverVersion: Long? = null,
    val state: String? = null,
    val detail: String? = null,
)

@Serializable
data class WorkOrderView(
    val id: String,
    val projectId: String,
    val siteId: String,
    val assignee: String?,
    val state: String,
    val version: Long,
    val evidenceReady: Boolean = false,
    val acceptedBy: String? = null,
)

@Serializable
data class OfflineBundle(
    val workOrder: WorkOrderView,
    val assetId: String,
    val evidenceRequired: Boolean,
    val fetchedAtEpochMs: Long,
)

@Serializable
data class EvidenceReservation(
    val evidenceId: String,
    val uploadUrl: String,
    val sha256Hex: String,
    val contentType: String,
)

@Serializable
data class AuditEventView(
    val correlationId: String,
    val traceparent: String,
    val operationId: String,
    val actor: String,
    val commandType: String,
    val objectId: String,
    val resultingState: String? = null,
    val objectVersion: Long? = null,
)

@Serializable
data class AuditResponse(
    val events: List<AuditEventView>,
    val projectionCount: Int,
)

class HiltechApiClient(
    baseUrl: String,
    private val tokenProvider: suspend () -> String,
    private val client: HttpClient = createPlatformHiltechHttpClient(),
) : AutoCloseable {
    private val root = baseUrl.trimEnd('/')

    suspend fun createWorkOrder(
        operationId: String,
        correlationId: String,
        payloadJson: String,
    ): CommandResponse =
        postCommand(
            path = "/v1/work-orders",
            request = WorkCommandRequest(
                operationId = operationId,
                correlationId = correlationId,
                commandType = "CreateWorkOrder",
                objectType = "work_order",
                objectId = "wo-42",
                payloadJson = payloadJson,
            ),
        )

    suspend fun assignWork(
        operationId: String,
        correlationId: String,
        workOrderId: String,
        baseVersion: Long,
        assignee: String,
    ): CommandResponse =
        postCommand(
            path = "/v1/work-orders/$workOrderId/assign",
            request = WorkCommandRequest(
                operationId = operationId,
                correlationId = correlationId,
                commandType = "AssignWork",
                objectType = "work_order",
                objectId = workOrderId,
                baseVersion = baseVersion,
                payloadJson = """{"assignee":"$assignee"}""",
            ),
        )

    suspend fun touchWork(
        operationId: String,
        correlationId: String,
        workOrderId: String,
        baseVersion: Long,
    ): CommandResponse =
        postCommand(
            path = "/v1/work-orders/$workOrderId/touch",
            request = WorkCommandRequest(
                operationId = operationId,
                correlationId = correlationId,
                commandType = "PMUpdateWork",
                objectType = "work_order",
                objectId = workOrderId,
                baseVersion = baseVersion,
            ),
        )

    suspend fun getOfflineBundle(
        workOrderId: String,
        correlationId: String,
    ): OfflineBundle =
        client.get("$root/v1/work-orders/$workOrderId/offline-bundle") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
        }.body()

    suspend fun getWorkOrder(
        workOrderId: String,
        correlationId: String,
    ): WorkOrderView =
        client.get("$root/v1/work-orders/$workOrderId") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
        }.body()

    suspend fun acceptWork(
        operationId: String,
        correlationId: String,
        workOrderId: String,
        baseVersion: Long,
    ): CommandResponse =
        postCommand(
            path = "/v1/work-orders/$workOrderId/accept",
            request = WorkCommandRequest(
                operationId = operationId,
                correlationId = correlationId,
                commandType = "AcceptWork",
                objectType = "work_order",
                objectId = workOrderId,
                baseVersion = baseVersion,
            ),
        )

    suspend fun reserveEvidence(
        workOrderId: String,
        evidenceId: String,
        correlationId: String,
        sha256Hex: String,
        contentType: String,
    ): EvidenceReservation =
        client.post("$root/v1/work-orders/$workOrderId/evidence/reserve") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
            header("Idempotency-Key", evidenceId)
            contentType(ContentType.Application.Json)
            setBody(
                mapOf(
                    "evidenceId" to evidenceId,
                    "sha256Hex" to sha256Hex,
                    "contentType" to contentType,
                ),
            )
        }.body()

    suspend fun uploadEvidenceBytes(
        reservation: EvidenceReservation,
        bytes: ByteArray,
    ): Int {
        val response = client.put(reservation.uploadUrl) {
            contentType(ContentType.parse(reservation.contentType))
            setBody(bytes)
        }
        return response.status.value
    }

    suspend fun finalizeEvidence(
        workOrderId: String,
        evidenceId: String,
        correlationId: String,
    ): CommandResponse =
        client.post("$root/v1/work-orders/$workOrderId/evidence/$evidenceId/finalize") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
            header("Idempotency-Key", "finalize-$evidenceId")
        }.body()

    suspend fun audit(correlationId: String): AuditResponse =
        client.get("$root/v1/audit") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
        }.body()

    suspend fun reset(correlationId: String) {
        client.post("$root/v1/test/reset") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
        }
    }

    suspend fun replay(command: PendingCommandEntity): SyncSendResult {
        val correlationId = "sync-" + command.operationId
        val response = client.post("$root/v1/sync/commands") {
            bearerAuth(tokenProvider())
            correlationHeaders(correlationId)
            header("Idempotency-Key", command.operationId)
            contentType(ContentType.Application.Json)
            setBody(
                WorkCommandRequest(
                    operationId = command.operationId,
                    correlationId = correlationId,
                    commandType = command.commandType,
                    objectType = command.objectType,
                    objectId = command.objectId,
                    baseVersion = command.baseVersion,
                    payloadJson = command.payloadJson,
                ),
            )
        }

        val payload = response.body<CommandResponse>()
        return when {
            response.status.value == 409 ->
                SyncSendResult.Conflict(
                    serverVersion = payload.serverVersion,
                    resultCode = payload.resultCode,
                )

            response.status.value in 500..599 ->
                SyncSendResult.Retryable(payload.resultCode)

            response.status.value == 401 || response.status.value == 403 ->
                SyncSendResult.TerminalFailure(payload.resultCode)

            payload.resultCode == "DUPLICATE_APPLIED" ->
                SyncSendResult.DuplicateApplied(
                    serverVersion = payload.serverVersion,
                    resultCode = payload.resultCode,
                )

            response.status.value in 200..299 ->
                SyncSendResult.Applied(
                    serverVersion = payload.serverVersion,
                    resultCode = payload.resultCode,
                )

            else ->
                SyncSendResult.TerminalFailure(payload.resultCode)
        }
    }

    fun asSyncTransport(): SyncTransport = SyncTransport { replay(it) }

    override fun close() {
        client.close()
    }

    private suspend fun postCommand(
        path: String,
        request: WorkCommandRequest,
    ): CommandResponse =
        client.post(root + path) {
            bearerAuth(tokenProvider())
            correlationHeaders(request.correlationId)
            header("Idempotency-Key", request.operationId)
            contentType(ContentType.Application.Json)
            setBody(request)
        }.body()

    private fun io.ktor.client.request.HttpRequestBuilder.correlationHeaders(
        correlationId: String,
    ) {
        header("X-Correlation-Id", correlationId)
        header("traceparent", traceparentFor(correlationId))
    }

    private fun traceparentFor(correlationId: String): String {
        val left = correlationId.hashCode().toUInt().toString(16).padStart(8, '0')
        val right = correlationId.reversed().hashCode().toUInt().toString(16).padStart(8, '0')
        val traceId = (left + right + left + right).take(32)
        val spanId = (right + left).take(16)
        return "00-$traceId-$spanId-01"
    }
}

expect fun createPlatformHiltechHttpClient(): HttpClient
