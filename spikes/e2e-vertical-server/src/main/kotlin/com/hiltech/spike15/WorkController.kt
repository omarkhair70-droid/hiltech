package com.hiltech.spike15

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

@RestController
class WorkController(
    private val fga: FgaClient,
    private val store: WorkStore,
    private val service: WorkService,
    private val evidenceStorage: EvidenceStorage,
) {
    @GetMapping("/health")
    fun health(): Map<String, String> =
        mapOf("status" to "UP")

    @PostMapping("/v1/work-orders")
    fun createWorkOrder(
        auth: JwtAuthenticationToken,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = actor(auth)
        requireAllowed(actor, "pm", "project:project-a")

        val outcome = service.create(
            operationId = body.string("operationId"),
            correlationId = correlationId,
            traceparent = traceparent(traceparent, correlationId),
            actor = actor,
            workOrderId = body.string("objectId"),
        )

        return outcome(outcome)
    }

    @PostMapping("/v1/work-orders/{workOrderId}/assign")
    fun assignWork(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = actor(auth)
        requireAllowed(actor, "pm", "project:project-a")

        val payload = body.string("payloadJson")
        val assignee = Regex("\\"assignee\\"\\s*:\\s*\\"([^\\"]+)\\"")
            .find(payload)
            ?.groupValues
            ?.get(1)
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "assignee missing from payloadJson",
            )

        val outcome = service.assign(
            operationId = body.string("operationId"),
            correlationId = correlationId,
            traceparent = traceparent(traceparent, correlationId),
            actor = actor,
            workOrderId = workOrderId,
            expectedVersion = body.long("baseVersion"),
            assignee = assignee,
        )

        return outcome(outcome)
    }

    @PostMapping("/v1/work-orders/{workOrderId}/touch")
    fun touchWork(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = actor(auth)
        requireAllowed(actor, "pm", "project:project-a")

        return outcome(
            service.touch(
                operationId = body.string("operationId"),
                correlationId = correlationId,
                traceparent = traceparent(traceparent, correlationId),
                actor = actor,
                workOrderId = workOrderId,
                expectedVersion = body.long("baseVersion"),
            ),
        )
    }

    @GetMapping("/v1/work-orders/{workOrderId}/offline-bundle")
    fun offlineBundle(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
    ): Map<String, Any?> {
        val actor = actor(auth)
        requireAllowed(actor, "viewer", "work_order:$workOrderId")

        val work = store.work(workOrderId)
        return linkedMapOf(
            "workOrder" to workMap(work),
            "assetId" to "fluke-03",
            "evidenceRequired" to true,
            "fetchedAtEpochMs" to System.currentTimeMillis(),
        )
    }

    @GetMapping("/v1/work-orders/{workOrderId}")
    fun workOrder(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
    ): Map<String, Any?> {
        val actor = actor(auth)
        requireAllowed(actor, "viewer", "work_order:$workOrderId")
        return workMap(store.work(workOrderId))
    }

    @PostMapping("/v1/sync/commands")
    fun replayCommand(
        auth: JwtAuthenticationToken,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = actor(auth)
        val workOrderId = body.string("objectId")
        requireAllowed(actor, "assignee", "work_order:$workOrderId")

        val operationId = body.string("operationId")
        val baseVersion = body.long("baseVersion")
        val trace = traceparent(traceparent, correlationId)

        val result =
            when (body.string("commandType")) {
                "StartWork" ->
                    service.start(
                        operationId,
                        correlationId,
                        trace,
                        actor,
                        workOrderId,
                        baseVersion,
                    )

                "SubmitWorkCompletion" ->
                    service.submit(
                        operationId,
                        correlationId,
                        trace,
                        actor,
                        workOrderId,
                        baseVersion,
                    )

                else ->
                    throw ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Unsupported offline command",
                    )
            }

        return outcome(result)
    }

    @PostMapping("/v1/work-orders/{workOrderId}/evidence/reserve")
    fun reserveEvidence(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
        @RequestBody body: Map<String, Any?>,
    ): Map<String, Any?> {
        val actor = actor(auth)
        requireAllowed(actor, "assignee", "work_order:$workOrderId")

        val evidenceId = body.string("evidenceId")
        val sha256Hex = body.string("sha256Hex")
        val contentType = body.string("contentType")

        val reservation = evidenceStorage.reserve(
            evidenceId = evidenceId,
            contentType = contentType,
        )

        service.reserveEvidence(
            evidenceId = evidenceId,
            workOrderId = workOrderId,
            objectKey = reservation.objectKey,
            expectedSha256 = sha256Hex,
            contentType = contentType,
        )

        return linkedMapOf(
            "evidenceId" to evidenceId,
            "uploadUrl" to reservation.uploadUrl,
            "sha256Hex" to sha256Hex,
            "contentType" to contentType,
        )
    }

    @PostMapping("/v1/work-orders/{workOrderId}/evidence/{evidenceId}/finalize")
    fun finalizeEvidence(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
        @PathVariable evidenceId: String,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): Map<String, Any?> {
        val actor = actor(auth)
        requireAllowed(actor, "assignee", "work_order:$workOrderId")

        val row = store.evidence(evidenceId)
        if (row.workOrderId != workOrderId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "Evidence/work mismatch")
        }

        val size = evidenceStorage.verifySha256(
            objectKey = row.objectKey,
            expectedHex = row.expectedSha256,
        )

        service.finalizeEvidence(
            evidenceId = evidenceId,
            sizeBytes = size,
            correlationId = correlationId,
            traceparent = traceparent(traceparent, correlationId),
            actor = actor,
        )

        val work = store.work(workOrderId)
        return linkedMapOf(
            "resultCode" to "APPLIED",
            "serverVersion" to work.version,
            "state" to work.state,
        )
    }

    @PostMapping("/v1/work-orders/{workOrderId}/accept")
    fun acceptWork(
        auth: JwtAuthenticationToken,
        @PathVariable workOrderId: String,
        @RequestBody body: Map<String, Any?>,
        @RequestHeader("X-Correlation-Id") correlationId: String,
        @RequestHeader("traceparent", required = false) traceparent: String?,
    ): ResponseEntity<Map<String, Any?>> {
        val actor = actor(auth)
        requireAllowed(actor, "reviewer", "work_order:$workOrderId")

        return outcome(
            service.accept(
                operationId = body.string("operationId"),
                correlationId = correlationId,
                traceparent = traceparent(traceparent, correlationId),
                actor = actor,
                workOrderId = workOrderId,
                expectedVersion = body.long("baseVersion"),
            ),
        )
    }

    @GetMapping("/v1/audit")
    fun audit(
        auth: JwtAuthenticationToken,
    ): Map<String, Any?> {
        val actor = actor(auth)
        requireAllowed(actor, "pm", "project:project-a")

        return linkedMapOf(
            "events" to store.auditRows(),
            "projectionCount" to store.projectionCount(),
        )
    }

    @PostMapping("/v1/test/reset")
    fun reset(
        auth: JwtAuthenticationToken,
    ): Map<String, String> {
        val actor = actor(auth)
        requireAllowed(actor, "pm", "project:project-a")
        store.resetSchema()
        return mapOf("status" to "RESET")
    }

    private fun requireAllowed(
        actor: String,
        relation: String,
        objectRef: String,
    ) {
        if (!fga.allowed(actor, relation, objectRef)) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "OpenFGA denied $actor $relation $objectRef",
            )
        }
    }

    private fun actor(auth: JwtAuthenticationToken): String {
        val raw =
            auth.token.getClaimAsString("preferred_username")
                ?: auth.token.getClaimAsString("azp")
                ?: throw ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "actor claim missing",
                )

        return raw.removePrefix("service-account-")
    }

    private fun outcome(
        result: CommandOutcome,
    ): ResponseEntity<Map<String, Any?>> {
        val body = linkedMapOf<String, Any?>(
            "resultCode" to result.resultCode,
            "serverVersion" to result.serverVersion,
            "state" to result.state,
        )

        val status =
            when (result.resultCode) {
                "VERSION_CONFLICT" -> HttpStatus.CONFLICT
                "EVIDENCE_NOT_READY" -> HttpStatus.UNPROCESSABLE_ENTITY
                else -> HttpStatus.OK
            }

        return ResponseEntity.status(status).body(body)
    }

    private fun workMap(work: WorkSnapshot): Map<String, Any?> =
        linkedMapOf(
            "id" to work.id,
            "projectId" to work.projectId,
            "siteId" to work.siteId,
            "assignee" to work.assignee,
            "state" to work.state,
            "version" to work.version,
            "evidenceReady" to work.evidenceReady,
            "acceptedBy" to work.acceptedBy,
        )

    private fun traceparent(
        provided: String?,
        correlationId: String,
    ): String =
        provided ?: "00-" +
            correlationId.hashCode().toUInt().toString(16).padStart(8, '0').repeat(4).take(32) +
            "-" +
            correlationId.reversed().hashCode().toUInt().toString(16).padStart(8, '0').repeat(2).take(16) +
            "-01"

    private fun Map<String, Any?>.string(key: String): String =
        this[key]?.toString()
            ?: throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Missing $key",
            )

    private fun Map<String, Any?>.long(key: String): Long =
        when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLong()
            else ->
                throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Missing/invalid $key",
                )
        }
}
