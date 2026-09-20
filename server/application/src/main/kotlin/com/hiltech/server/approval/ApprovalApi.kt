package com.hiltech.server.approval

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class ApprovalRequestResponse(
    val approvalRequestId: String,
    val subjectType: String,
    val subjectId: String,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val state: String,
    val reasonCode: String,
    val safeReasonSummary: String?,
    val createdAt: String,
    val authorityKey: String,
)

data class AssignedApprovalPageResponse(
    val items: List<ApprovalRequestResponse>,
    val nextCursor: String?,
    val asOf: String,
    val correlationId: String,
)

data class ApprovalDecisionRequest(
    val operationId: String,
    val decision: ApprovalDecisionType,
    val comment: String? = null,
)

data class ApprovalDecisionResponse(
    val approvalRequestId: String,
    val state: String,
    val replayed: Boolean,
    val correlationId: String,
)

data class ApprovalCursor(
    val actorUserId: UUID,
    val asOf: Instant,
    val afterCreatedAt: Instant,
    val afterRequestId: UUID,
)

@Component
class ApprovalCursorCodec(
    @Value(
        "\${hiltech.approval.cursor-signing-key:}",
    )
    private val signingKey: String,
) {
    fun encode(
        cursor: ApprovalCursor,
    ): String {
        requireConfigured()
        val payload =
            listOf(
                "v1",
                cursor.actorUserId.toString(),
                cursor.asOf.toString(),
                cursor.afterCreatedAt.toString(),
                cursor.afterRequestId.toString(),
            ).joinToString("|")
        val bytes =
            payload.toByteArray(
                StandardCharsets.UTF_8,
            )

        return encoder.encodeToString(bytes) +
            "." +
            encoder.encodeToString(
                sign(bytes),
            )
    }

    fun decode(
        raw: String,
    ): ApprovalCursor {
        requireConfigured()
        val parts = raw.split('.')
        if (parts.size != 2) {
            invalid()
        }

        val payload =
            runCatching {
                decoder.decode(parts[0])
            }.getOrElse {
                invalid()
            }
        val suppliedSignature =
            runCatching {
                decoder.decode(parts[1])
            }.getOrElse {
                invalid()
            }

        // Base64url has unused trailing bits for some unpadded lengths.
        // Java's decoder accepts multiple textual encodings that decode to
        // identical bytes. Cursors are signed opaque tokens, so accept only
        // our canonical no-padding encoding before checking the MAC.
        if (
            encoder.encodeToString(payload) !=
                parts[0] ||
            encoder.encodeToString(
                suppliedSignature,
            ) != parts[1]
        ) {
            invalid()
        }

        if (
            !MessageDigest.isEqual(
                sign(payload),
                suppliedSignature,
            )
        ) {
            invalid()
        }

        val values =
            payload.toString(
                StandardCharsets.UTF_8,
            ).split('|')

        if (
            values.size != 5 ||
            values[0] != "v1"
        ) {
            invalid()
        }

        return runCatching {
            ApprovalCursor(
                actorUserId =
                    UUID.fromString(
                        values[1],
                    ),
                asOf =
                    Instant.parse(
                        values[2],
                    ),
                afterCreatedAt =
                    Instant.parse(
                        values[3],
                    ),
                afterRequestId =
                    UUID.fromString(
                        values[4],
                    ),
            )
        }.getOrElse {
            invalid()
        }
    }

    private fun sign(
        value: ByteArray,
    ): ByteArray {
        val mac =
            Mac.getInstance(
                "HmacSHA256",
            )
        mac.init(
            SecretKeySpec(
                signingKey.toByteArray(
                    StandardCharsets.UTF_8,
                ),
                "HmacSHA256",
            ),
        )
        return mac.doFinal(value)
    }

    private fun requireConfigured() {
        if (
            signingKey.toByteArray(
                StandardCharsets.UTF_8,
            ).size < 32
        ) {
            throw ProductApiException(
                code =
                    "APPROVAL_CURSOR_NOT_CONFIGURED",
                message =
                    "Approval pagination is not configured.",
                status =
                    HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
    }

    private fun invalid(): Nothing =
        throw ProductApiException(
            code = "CURSOR_INVALID",
            message =
                "The Approval cursor is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )

    companion object {
        private val encoder =
            Base64.getUrlEncoder()
                .withoutPadding()
        private val decoder =
            Base64.getUrlDecoder()
    }
}

@Component
class ApprovalReadService(
    private val persistence:
        ApprovalPersistencePort,
    private val authorization:
        ApprovalAuthorizationPort,
    private val cursorCodec:
        ApprovalCursorCodec,
    private val clock: Clock,
) {
    fun one(
        actorUserId: UUID,
        approvalRequestId: UUID,
    ): ApprovalRequestResponse {
        val record =
            persistence
                .loadPendingAssignedRequest(
                    approvalRequestId,
                )
                ?: throw notVisible()

        ensureCurrentlyAssigned(
            actorUserId = actorUserId,
            record = record,
            now = clock.instant(),
        )

        return record.toResponse()
    }

    fun assigned(
        actorUserId: UUID,
        rawCursor: String?,
        requestedLimit: Int,
        correlationId: String,
    ): AssignedApprovalPageResponse {
        if (requestedLimit !in 1..100) {
            throw ProductApiException(
                code = "INVALID_PAGE_LIMIT",
                message =
                    "Approval page limit must be between 1 and 100.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val cursor =
            rawCursor
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    cursorCodec::decode,
                )

        if (
            cursor != null &&
            cursor.actorUserId !=
            actorUserId
        ) {
            throw ProductApiException(
                code = "CURSOR_INVALID",
                message =
                    "The Approval cursor belongs to another identity.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val now = clock.instant()
        val asOf =
            cursor?.asOf ?: now
        val records =
            persistence
                .findPendingAssignedRequests(
                    actorUserId =
                        actorUserId,
                    authorityAt = now,
                    asOf = asOf,
                    afterCreatedAt =
                        cursor?.afterCreatedAt,
                    afterRequestId =
                        cursor?.afterRequestId,
                    limit =
                        requestedLimit + 1,
                )

        val pageWindow =
            records.take(
                requestedLimit,
            )
        val visible =
            pageWindow.filter { record ->
                isCurrentlyAssigned(
                    actorUserId =
                        actorUserId,
                    record = record,
                    now = now,
                )
            }

        val nextCursor =
            if (
                records.size >
                requestedLimit &&
                pageWindow.isNotEmpty()
            ) {
                val last =
                    pageWindow.last()
                cursorCodec.encode(
                    ApprovalCursor(
                        actorUserId =
                            actorUserId,
                        asOf = asOf,
                        afterCreatedAt =
                            last.createdAt,
                        afterRequestId =
                            last.requestId,
                    ),
                )
            } else {
                null
            }

        return AssignedApprovalPageResponse(
            items =
                visible.map {
                    it.toResponse()
                },
            nextCursor =
                nextCursor,
            asOf = asOf.toString(),
            correlationId =
                correlationId,
        )
    }

    private fun ensureCurrentlyAssigned(
        actorUserId: UUID,
        record: ApprovalReadRecord,
        now: Instant,
    ) {
        if (
            !isCurrentlyAssigned(
                actorUserId =
                    actorUserId,
                record = record,
                now = now,
            )
        ) {
            throw notVisible()
        }
    }

    private fun isCurrentlyAssigned(
        actorUserId: UUID,
        record: ApprovalReadRecord,
        now: Instant,
    ): Boolean {
        val authority =
            persistence
                .resolveCurrentAuthority(
                    organizationId =
                        record.organizationId,
                    authorityKey =
                        record.authorityKey,
                    at = now,
                ) ?: return false

        if (
            authority.principalType !=
                record.assignmentPrincipalType ||
            authority.principalId !=
                record.assignmentPrincipalId
        ) {
            return false
        }

        if (
            !persistence
                .actorMatchesAuthority(
                    actorUserId =
                        actorUserId,
                    authority =
                        authority,
                    at = now,
                )
        ) {
            return false
        }

        return authorization.canDecide(
            actorUserId =
                actorUserId,
            approvalRequestId =
                record.requestId,
            assignmentPrincipalType =
                record
                    .assignmentPrincipalType,
            assignmentPrincipalId =
                record
                    .assignmentPrincipalId,
        )
    }

    private fun ApprovalReadRecord
        .toResponse():
        ApprovalRequestResponse =
        ApprovalRequestResponse(
            approvalRequestId =
                requestId.toString(),
            subjectType =
                subjectType,
            subjectId =
                subjectId.toString(),
            subjectVersion =
                subjectVersion,
            policyKey =
                policyKey,
            policyVersion =
                policyVersion,
            state =
                state.name,
            reasonCode =
                reasonCode,
            safeReasonSummary =
                safeReasonSummary,
            createdAt =
                createdAt.toString(),
            authorityKey =
                authorityKey,
        )

    private fun notVisible():
        ProductApiException =
        ProductApiException(
            code =
                "OBJECT_NOT_VISIBLE",
            message =
                "The requested approval is not available.",
            status =
                HttpStatus.NOT_FOUND,
        )
}

@RestController
@RequestMapping("/v1/approvals")
class ApprovalController(
    private val readService:
        ApprovalReadService,
    private val engine:
        ApprovalEngineService,
) {
    @GetMapping("/assigned")
    fun assigned(
        requestContext:
            HttpServletRequest,
        @RequestParam(
            name = "cursor",
            required = false,
        )
        cursor: String?,
        @RequestParam(
            name = "limit",
            defaultValue = "50",
        )
        limit: Int,
    ): AssignedApprovalPageResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )
        val actor =
            context.requireIdentityId()

        return readService.assigned(
            actorUserId = actor,
            rawCursor = cursor,
            requestedLimit = limit,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{approvalRequestId}")
    fun one(
        requestContext:
            HttpServletRequest,
        @PathVariable
        approvalRequestId: String,
    ): ApprovalRequestResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return readService.one(
            actorUserId =
                context.requireIdentityId(),
            approvalRequestId =
                approvalRequestId.toUuid(
                    "INVALID_APPROVAL_ID",
                ),
        )
    }

    @PostMapping(
        "/{approvalRequestId}/decisions",
    )
    fun decide(
        requestContext:
            HttpServletRequest,
        @PathVariable
        approvalRequestId: String,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request:
            ApprovalDecisionRequest,
    ): ApprovalDecisionResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )
        val actor =
            context.requireIdentityId()
        val operationId =
            request.operationId.toUuid(
                "INVALID_OPERATION_ID",
            )

        IdempotencyKeyContract
            .requireMatches(
                rawHeader =
                    idempotencyKey,
                operationId =
                    operationId,
            )

        val result =
            engine.decide(
                actorUserId =
                    actor,
                operationId =
                    operationId,
                approvalRequestId =
                    approvalRequestId.toUuid(
                        "INVALID_APPROVAL_ID",
                    ),
                decision =
                    request.decision,
                comment =
                    request.comment,
                correlationId =
                    context.correlationId,
            )

        return ApprovalDecisionResponse(
            approvalRequestId =
                result.approvalRequestId
                    .toString(),
            state =
                result.state.name,
            replayed =
                result.replayed,
            correlationId =
                context.correlationId,
        )
    }

    private fun com.hiltech.server.platform.HiltechRequestContextSnapshot
        .requireIdentityId(): UUID =
        identityId
            ?.let {
                runCatching {
                    UUID.fromString(it)
                }.getOrNull()
            }
            ?: throw ProductApiException(
                code = "UNAUTHENTICATED",
                message =
                    "Authentication is required.",
                status =
                    HttpStatus.UNAUTHORIZED,
            )

    private fun String.toUuid(
        code: String,
    ): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            throw ProductApiException(
                code = code,
                message =
                    "Approval request is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
}
