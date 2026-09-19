package com.hiltech.server.activity

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

data class ActivityItemResponse(
    val activityId: String,
    val activityType: String,
    val contextType: String,
    val contextId: String,
    val sourceType: String,
    val sourceId: String,
    val occurredAt: String,
    val safeSummary: Map<String, String>,
    val classificationCode: String,
    val correlationId: String?,
)

data class WorkOrderActivityPageResponse(
    val items: List<ActivityItemResponse>,
    val nextCursor: String?,
    val asOf: String,
    val correlationId: String,
)

data class ActivityCursor(
    val workOrderId: UUID,
    val asOf: Instant,
    val afterOccurredAt: Instant,
    val afterActivityId: UUID,
)

@Component
class ActivityCursorCodec(
    @Value(
        "\${hiltech.activity.cursor-signing-key:}",
    )
    private val signingKey: String,
) {
    fun encode(
        cursor: ActivityCursor,
    ): String {
        requireConfigured()
        val payload =
            listOf(
                "v1",
                cursor.workOrderId.toString(),
                cursor.asOf.toEpochMilli()
                    .toString(),
                cursor.afterOccurredAt
                    .toEpochMilli()
                    .toString(),
                cursor.afterActivityId
                    .toString(),
            ).joinToString("|")

        val payloadBytes =
            payload.toByteArray(
                StandardCharsets.UTF_8,
            )
        val signature =
            sign(payloadBytes)

        return encoder.encodeToString(
            payloadBytes,
        ) + "." +
            encoder.encodeToString(
                signature,
            )
    }

    fun decode(
        raw: String,
    ): ActivityCursor {
        requireConfigured()

        val parts =
            raw.split('.')
        if (parts.size != 2) {
            invalid()
        }

        val payloadBytes =
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
        val expectedSignature =
            sign(payloadBytes)

        if (
            !MessageDigest.isEqual(
                expectedSignature,
                suppliedSignature,
            )
        ) {
            invalid()
        }

        val values =
            payloadBytes
                .toString(
                    StandardCharsets.UTF_8,
                )
                .split('|')
        if (
            values.size != 5 ||
            values[0] != "v1"
        ) {
            invalid()
        }

        return runCatching {
            ActivityCursor(
                workOrderId =
                    UUID.fromString(
                        values[1],
                    ),
                asOf =
                    Instant.ofEpochMilli(
                        values[2].toLong(),
                    ),
                afterOccurredAt =
                    Instant.ofEpochMilli(
                        values[3].toLong(),
                    ),
                afterActivityId =
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
            Mac.getInstance("HmacSHA256")
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
                    "ACTIVITY_CURSOR_NOT_CONFIGURED",
                message =
                    "Activity pagination is not configured.",
                status =
                    HttpStatus.SERVICE_UNAVAILABLE,
            )
        }
    }

    private fun invalid(): Nothing =
        throw ProductApiException(
            code = "CURSOR_INVALID",
            message =
                "The Activity cursor is invalid.",
            status = HttpStatus.BAD_REQUEST,
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
class WorkOrderActivityService(
    private val projection:
        ActivityProjectionPort,
    private val authorization:
        WorkOrderActivityAuthorizationPort,
    private val cursorCodec:
        ActivityCursorCodec,
    private val clock: Clock,
) {
    fun read(
        actorIdentityId: UUID,
        workOrderId: UUID,
        rawCursor: String?,
        requestedLimit: Int,
        correlationId: String,
    ): WorkOrderActivityPageResponse {
        if (requestedLimit !in 1..100) {
            throw ProductApiException(
                code =
                    "INVALID_PAGE_LIMIT",
                message =
                    "Activity page limit must be between 1 and 100.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        if (
            !authorization
                .canViewWorkOrder(
                    identityId =
                        actorIdentityId,
                    workOrderId =
                        workOrderId,
                )
        ) {
            throw ProductApiException(
                code =
                    "OBJECT_NOT_VISIBLE",
                message =
                    "The requested WorkOrder is not available.",
                status = HttpStatus.NOT_FOUND,
            )
        }

        val cursor =
            rawCursor
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let(cursorCodec::decode)

        if (
            cursor != null &&
            cursor.workOrderId !=
            workOrderId
        ) {
            throw ProductApiException(
                code = "CURSOR_INVALID",
                message =
                    "The Activity cursor does not belong to this WorkOrder.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }

        val asOf =
            cursor?.asOf
                ?: clock.instant()
        val records =
            projection.findWorkOrderActivity(
                workOrderId =
                    workOrderId,
                asOf = asOf,
                afterOccurredAt =
                    cursor?.afterOccurredAt,
                afterActivityId =
                    cursor?.afterActivityId,
                limit =
                    requestedLimit + 1,
            )
        val visible =
            records.take(
                requestedLimit,
            )
        val nextCursor =
            if (
                records.size >
                requestedLimit &&
                visible.isNotEmpty()
            ) {
                val last =
                    visible.last()
                cursorCodec.encode(
                    ActivityCursor(
                        workOrderId =
                            workOrderId,
                        asOf = asOf,
                        afterOccurredAt =
                            last.occurredAt,
                        afterActivityId =
                            last.id,
                    ),
                )
            } else {
                null
            }

        return WorkOrderActivityPageResponse(
            items =
                visible.map {
                    it.toResponse()
                },
            nextCursor = nextCursor,
            asOf = asOf.toString(),
            correlationId =
                correlationId,
        )
    }

    private fun ActivityRecord
        .toResponse():
        ActivityItemResponse =
        ActivityItemResponse(
            activityId = id.toString(),
            activityType =
                activityType,
            contextType =
                contextType,
            contextId =
                contextId.toString(),
            sourceType =
                sourceType,
            sourceId =
                sourceId.toString(),
            occurredAt =
                occurredAt.toString(),
            safeSummary =
                safeSummary,
            classificationCode =
                classificationCode,
            correlationId =
                correlationId,
        )
}

@RestController
@RequestMapping("/v1/work-orders")
class WorkOrderActivityController(
    private val service:
        WorkOrderActivityService,
) {
    @GetMapping("/{workOrderId}/activity")
    fun activity(
        requestContext: HttpServletRequest,
        @PathVariable
        workOrderId: String,
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
    ): WorkOrderActivityPageResponse {
        val context =
            HiltechRequestContext.current(
                requestContext,
            )

        return service.read(
            actorIdentityId =
                context.requireIdentityId(),
            workOrderId =
                workOrderId.toUuid(),
            rawCursor = cursor,
            requestedLimit = limit,
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

    private fun String.toUuid(): UUID =
        runCatching {
            UUID.fromString(this)
        }.getOrElse {
            throw ProductApiException(
                code =
                    "INVALID_WORK_ORDER_ID",
                message =
                    "WorkOrder identifier is invalid.",
                status =
                    HttpStatus.BAD_REQUEST,
            )
        }
}
