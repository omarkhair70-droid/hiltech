package com.hiltech.server.inbox

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

data class InboxItemResponse(
    val inboxItemId: String,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long,
    val actionKey: String,
    val attentionClass: String,
    val state: String,
    val read: Boolean,
    val actionable: Boolean,
    val safeTitleCode: String,
    val safeSummary: String?,
    val createdAt: String,
    val updatedAt: String,
    val resolvedAt: String?,
)

data class InboxPageResponse(
    val items: List<InboxItemResponse>,
    val nextCursor: String?,
    val asOf: String,
    val correlationId: String,
)

data class InboxReadStateRequest(
    val operationId: String,
)

data class InboxReadStateResponse(
    val inboxItemId: String,
    val read: Boolean,
    val replayed: Boolean,
    val correlationId: String,
)

data class InboxCursor(
    val surface: String,
    val actorUserId: UUID,
    val asOf: Instant,
    val stateFilter: String?,
    val readFilter: String?,
    val boundaryAt: Instant,
    val itemId: UUID,
)

@Component
class InboxCursorCodec(
    @Value(
        "\${hiltech.inbox.cursor-signing-key:}",
    )
    private val signingKey: String,
) {
    fun encode(
        cursor: InboxCursor,
    ): String {
        requireConfigured()
        val payload =
            listOf(
                "v1",
                cursor.surface,
                cursor.actorUserId
                    .toString(),
                cursor.asOf.toString(),
                cursor.stateFilter ?: "-",
                cursor.readFilter ?: "-",
                cursor.boundaryAt.toString(),
                cursor.itemId.toString(),
            ).joinToString("|")
        val bytes =
            payload.toByteArray(
                StandardCharsets.UTF_8,
            )

        return encoder
            .encodeToString(bytes) +
            "." +
            encoder.encodeToString(
                sign(bytes),
            )
    }

    fun decode(
        raw: String,
    ): InboxCursor {
        requireConfigured()
        val parts =
            raw.split('.')
        if (parts.size != 2) {
            invalid()
        }

        val payload =
            runCatching {
                decoder.decode(
                    parts[0],
                )
            }.getOrElse {
                invalid()
            }
        val signature =
            runCatching {
                decoder.decode(
                    parts[1],
                )
            }.getOrElse {
                invalid()
            }

        if (
            !MessageDigest.isEqual(
                sign(payload),
                signature,
            )
        ) {
            invalid()
        }

        val values =
            payload.toString(
                StandardCharsets.UTF_8,
            ).split('|')
        if (
            values.size != 8 ||
            values[0] != "v1"
        ) {
            invalid()
        }

        return runCatching {
            InboxCursor(
                surface = values[1],
                actorUserId =
                    UUID.fromString(
                        values[2],
                    ),
                asOf =
                    Instant.parse(
                        values[3],
                    ),
                stateFilter =
                    values[4]
                        .takeUnless {
                            it == "-"
                        },
                readFilter =
                    values[5]
                        .takeUnless {
                            it == "-"
                        },
                boundaryAt =
                    Instant.parse(
                        values[6],
                    ),
                itemId =
                    UUID.fromString(
                        values[7],
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
                    "INBOX_CURSOR_NOT_CONFIGURED",
                message =
                    "Inbox pagination is not configured.",
                status =
                    HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
    }

    private fun invalid(): Nothing =
        throw ProductApiException(
            code = "CURSOR_INVALID",
            message =
                "The Inbox cursor is invalid.",
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
class InboxService(
    private val store:
        InboxReadPort,
    private val sourceAccess:
        InboxSourceAccessPort,
    private val idempotency:
        IdempotentCommandExecutor,
    private val cursorCodec:
        InboxCursorCodec,
    private val clock: Clock,
) {
    fun workQueue(
        actorUserId: UUID,
        rawCursor: String?,
        requestedLimit: Int,
        correlationId: String,
    ): InboxPageResponse {
        requireLimit(
            requestedLimit,
        )
        val cursor =
            decodeCursor(
                raw = rawCursor,
                surface = "WORK_QUEUE",
                actorUserId =
                    actorUserId,
                stateFilter = null,
                readFilter = null,
            )
        val now =
            clock.instant()
        val asOf =
            cursor?.asOf ?: now
        val candidates =
            store.findWorkQueueCandidates(
                actorUserId =
                    actorUserId,
                currentAt = now,
                asOf = asOf,
                afterCreatedAt =
                    cursor?.boundaryAt,
                afterItemId =
                    cursor?.itemId,
                limit =
                    requestedLimit + 1,
            )
        val window =
            candidates.take(
                requestedLimit,
            )
        val visible =
            window.filter {
                sourceAccess.canAct(
                    actorUserId =
                        actorUserId,
                    item = it.item,
                    at = now,
                )
            }

        val nextCursor =
            if (
                candidates.size >
                requestedLimit &&
                window.isNotEmpty()
            ) {
                val last =
                    window.last()
                cursorCodec.encode(
                    InboxCursor(
                        surface =
                            "WORK_QUEUE",
                        actorUserId =
                            actorUserId,
                        asOf = asOf,
                        stateFilter =
                            null,
                        readFilter =
                            null,
                        boundaryAt =
                            last.item.createdAt,
                        itemId =
                            last.item.id,
                    ),
                )
            } else {
                null
            }

        return InboxPageResponse(
            items =
                visible.map {
                    toResponse(
                        actorUserId =
                            actorUserId,
                        candidate = it,
                        now = now,
                    )
                },
            nextCursor =
                nextCursor,
            asOf =
                asOf.toString(),
            correlationId =
                correlationId,
        )
    }

    fun inbox(
        actorUserId: UUID,
        rawCursor: String?,
        requestedLimit: Int,
        stateFilterRaw: String?,
        readFilterRaw: String?,
        correlationId: String,
    ): InboxPageResponse {
        requireLimit(
            requestedLimit,
        )
        val stateFilter =
            stateFilterRaw
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    runCatching {
                        InboxItemState.valueOf(
                            it.uppercase(),
                        )
                    }.getOrElse {
                        invalidFilter()
                    }
                }
        val readFilter =
            readFilterRaw
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let {
                    runCatching {
                        InboxReadFilter.valueOf(
                            it.uppercase(),
                        )
                    }.getOrElse {
                        invalidFilter()
                    }
                }
        val cursor =
            decodeCursor(
                raw = rawCursor,
                surface = "INBOX",
                actorUserId =
                    actorUserId,
                stateFilter =
                    stateFilter?.name,
                readFilter =
                    readFilter?.name,
            )
        val now =
            clock.instant()
        val asOf =
            cursor?.asOf ?: now
        val candidates =
            store.findInboxCandidates(
                actorUserId =
                    actorUserId,
                currentAt = now,
                asOf = asOf,
                stateFilter =
                    stateFilter,
                readFilter =
                    readFilter,
                afterUpdatedAt =
                    cursor?.boundaryAt,
                afterItemId =
                    cursor?.itemId,
                limit =
                    requestedLimit + 1,
            )
        val window =
            candidates.take(
                requestedLimit,
            )
        val visible =
            window.filter {
                sourceAccess.canRead(
                    actorUserId =
                        actorUserId,
                    item = it.item,
                    at = now,
                )
            }
        val nextCursor =
            if (
                candidates.size >
                requestedLimit &&
                window.isNotEmpty()
            ) {
                val last =
                    window.last()
                cursorCodec.encode(
                    InboxCursor(
                        surface = "INBOX",
                        actorUserId =
                            actorUserId,
                        asOf = asOf,
                        stateFilter =
                            stateFilter?.name,
                        readFilter =
                            readFilter?.name,
                        boundaryAt =
                            last.item.updatedAt,
                        itemId =
                            last.item.id,
                    ),
                )
            } else {
                null
            }

        return InboxPageResponse(
            items =
                visible.map {
                    toResponse(
                        actorUserId =
                            actorUserId,
                        candidate = it,
                        now = now,
                    )
                },
            nextCursor =
                nextCursor,
            asOf =
                asOf.toString(),
            correlationId =
                correlationId,
        )
    }

    fun one(
        actorUserId: UUID,
        inboxItemId: UUID,
    ): InboxItemResponse {
        val now =
            clock.instant()
        val candidate =
            store.loadCandidate(
                actorUserId =
                    actorUserId,
                currentAt = now,
                inboxItemId =
                    inboxItemId,
            ) ?: throw notVisible()

        if (
            !sourceAccess.canRead(
                actorUserId =
                    actorUserId,
                item =
                    candidate.item,
                at = now,
            )
        ) {
            throw notVisible()
        }

        return toResponse(
            actorUserId =
                actorUserId,
            candidate =
                candidate,
            now = now,
        )
    }

    fun setReadState(
        actorUserId: UUID,
        inboxItemId: UUID,
        operationId: UUID,
        read: Boolean,
        correlationId: String,
    ): InboxReadStateResponse {
        val fingerprint =
            IdempotencyKeyContract.fingerprint(
                listOf(
                    inboxItemId,
                    read,
                ).joinToString("|"),
            )

        val execution =
            idempotency.execute(
                IdempotentCommandSpec(
                    operationId =
                        operationId,
                    actorUserId =
                        actorUserId,
                    commandType =
                        if (read) {
                            "MarkInboxRead"
                        } else {
                            "MarkInboxUnread"
                        },
                    targetType =
                        "InboxItem",
                    targetId =
                        inboxItemId,
                    requestFingerprint =
                        fingerprint,
                    correlationId =
                        correlationId,
                ),
            ) {
                val now =
                    clock.instant()
                val candidate =
                    store.loadCandidate(
                        actorUserId =
                            actorUserId,
                        currentAt = now,
                        inboxItemId =
                            inboxItemId,
                    ) ?: throw notVisible()

                if (
                    !sourceAccess.canRead(
                        actorUserId =
                            actorUserId,
                        item =
                            candidate.item,
                        at = now,
                    )
                ) {
                    throw notVisible()
                }

                if (read) {
                    store.markRead(
                        inboxItemId =
                            inboxItemId,
                        actorUserId =
                            actorUserId,
                        at = now,
                    )
                } else {
                    store.markUnread(
                        inboxItemId =
                            inboxItemId,
                        actorUserId =
                            actorUserId,
                        at = now,
                    )
                }

                IdempotentCommandOutcome(
                    resultCode =
                        if (read) {
                            "INBOX_READ"
                        } else {
                            "INBOX_UNREAD"
                        },
                    resultPayloadJson =
                        buildJsonObject {
                            put(
                                "inboxItemId",
                                inboxItemId
                                    .toString(),
                            )
                            put(
                                "read",
                                read,
                            )
                        }.toString(),
                )
            }

        return InboxReadStateResponse(
            inboxItemId =
                inboxItemId.toString(),
            read = read,
            replayed =
                execution.replayed,
            correlationId =
                correlationId,
        )
    }

    private fun decodeCursor(
        raw: String?,
        surface: String,
        actorUserId: UUID,
        stateFilter: String?,
        readFilter: String?,
    ): InboxCursor? {
        val cursor =
            raw
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(
                    cursorCodec::decode,
                )
                ?: return null

        if (
            cursor.surface != surface ||
            cursor.actorUserId !=
                actorUserId ||
            cursor.stateFilter !=
                stateFilter ||
            cursor.readFilter !=
                readFilter
        ) {
            throw ProductApiException(
                code = "CURSOR_INVALID",
                message =
                    "The Inbox cursor does not match this query.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        return cursor
    }

    private fun toResponse(
        actorUserId: UUID,
        candidate: InboxCandidateRecord,
        now: Instant,
    ): InboxItemResponse =
        InboxItemResponse(
            inboxItemId =
                candidate.item.id
                    .toString(),
            sourceType =
                candidate.item.sourceType,
            sourceId =
                candidate.item.sourceId
                    .toString(),
            sourceVersion =
                candidate.item.sourceVersion,
            actionKey =
                candidate.item.actionKey,
            attentionClass =
                "ACTION_REQUIRED",
            state =
                candidate.item.state.name,
            read =
                candidate.readAt != null,
            actionable =
                sourceAccess.canAct(
                    actorUserId =
                        actorUserId,
                    item =
                        candidate.item,
                    at = now,
                ),
            safeTitleCode =
                candidate.item
                    .safeTitleCode,
            safeSummary =
                candidate.item.safeSummary,
            createdAt =
                candidate.item.createdAt
                    .toString(),
            updatedAt =
                candidate.item.updatedAt
                    .toString(),
            resolvedAt =
                candidate.item.resolvedAt
                    ?.toString(),
        )

    private fun requireLimit(
        limit: Int,
    ) {
        if (limit !in 1..100) {
            throw ProductApiException(
                code =
                    "INVALID_PAGE_LIMIT",
                message =
                    "Inbox page limit must be between 1 and 100.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
    }

    private fun invalidFilter(): Nothing =
        throw ProductApiException(
            code =
                "INVALID_INBOX_FILTER",
            message =
                "Inbox filter is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )

    private fun notVisible():
        ProductApiException =
        ProductApiException(
            code =
                "OBJECT_NOT_VISIBLE",
            message =
                "The requested Inbox item is not available.",
            status =
                HttpStatus.NOT_FOUND,
        )
}

@RestController
@RequestMapping("/v1/work-queue")
class WorkQueueController(
    private val service:
        InboxService,
) {
    @GetMapping
    fun list(
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
    ): InboxPageResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )
        return service.workQueue(
            actorUserId =
                context.requireIdentityId(),
            rawCursor = cursor,
            requestedLimit = limit,
            correlationId =
                context.correlationId,
        )
    }
}

@RestController
@RequestMapping("/v1/inbox")
class InboxController(
    private val service:
        InboxService,
) {
    @GetMapping
    fun list(
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
        @RequestParam(
            name = "state",
            required = false,
        )
        state: String?,
        @RequestParam(
            name = "read",
            required = false,
        )
        read: String?,
    ): InboxPageResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )
        return service.inbox(
            actorUserId =
                context.requireIdentityId(),
            rawCursor = cursor,
            requestedLimit = limit,
            stateFilterRaw =
                state,
            readFilterRaw =
                read,
            correlationId =
                context.correlationId,
        )
    }

    @GetMapping("/{inboxItemId}")
    fun one(
        requestContext:
            HttpServletRequest,
        @PathVariable
        inboxItemId: String,
    ): InboxItemResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )
        return service.one(
            actorUserId =
                context.requireIdentityId(),
            inboxItemId =
                inboxItemId.toUuid(
                    "INVALID_INBOX_ITEM_ID",
                ),
        )
    }

    @PostMapping("/{inboxItemId}/read")
    fun read(
        requestContext:
            HttpServletRequest,
        @PathVariable
        inboxItemId: String,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request:
            InboxReadStateRequest,
    ): InboxReadStateResponse =
        setReadState(
            requestContext =
                requestContext,
            inboxItemId =
                inboxItemId,
            idempotencyKey =
                idempotencyKey,
            request = request,
            read = true,
        )

    @PostMapping("/{inboxItemId}/unread")
    fun unread(
        requestContext:
            HttpServletRequest,
        @PathVariable
        inboxItemId: String,
        @RequestHeader(
            name = "Idempotency-Key",
            required = false,
        )
        idempotencyKey: String?,
        @RequestBody
        request:
            InboxReadStateRequest,
    ): InboxReadStateResponse =
        setReadState(
            requestContext =
                requestContext,
            inboxItemId =
                inboxItemId,
            idempotencyKey =
                idempotencyKey,
            request = request,
            read = false,
        )

    private fun setReadState(
        requestContext:
            HttpServletRequest,
        inboxItemId: String,
        idempotencyKey: String?,
        request: InboxReadStateRequest,
        read: Boolean,
    ): InboxReadStateResponse {
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

        return service.setReadState(
            actorUserId = actor,
            inboxItemId =
                inboxItemId.toUuid(
                    "INVALID_INBOX_ITEM_ID",
                ),
            operationId =
                operationId,
            read = read,
            correlationId =
                context.correlationId,
        )
    }
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
                "Inbox request is invalid.",
            status =
                HttpStatus.BAD_REQUEST,
        )
    }
