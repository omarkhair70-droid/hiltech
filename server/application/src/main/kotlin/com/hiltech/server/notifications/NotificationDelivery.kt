package com.hiltech.server.notifications

import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class NotificationDeliveryRequest(
    val notificationIntentId: UUID,
    val channel: NotificationChannel,
    val recipientTargetRef: String,
    val templateCode: String,
    val safePreview: String?,
    val deepLinkType: String,
    val deepLinkId: UUID,
    val idempotencyKey: String,
    val correlationId: String?,
)

data class NotificationDeliveryResult(
    val providerKey: String,
    val outcome: NotificationDeliveryOutcome,
    val providerMessageId: String? = null,
    val failureCode: String? = null,
    val deliveredAt: Instant? = null,
)

fun interface NotificationDeliveryPort {
    fun deliver(
        request: NotificationDeliveryRequest,
    ): NotificationDeliveryResult
}

enum class NotificationDispatchStatus {
    DISPATCHED,
    FAILED_RETRYABLE,
    FAILED_FINAL,
    SUPPRESSED_STALE,
    NO_PROVIDER,
    NOT_PENDING,
}

data class NotificationDispatchResult(
    val status: NotificationDispatchStatus,
    val intent: NotificationIntentRecord,
    val attempt:
        NotificationDeliveryAttemptRecord? = null,
)

@Component
class NotificationDispatchService(
    private val persistence:
        NotificationPersistencePort,
    private val sourceAccess:
        NotificationSourceAccessPort,
    private val deliveryProvider:
        ObjectProvider<NotificationDeliveryPort>,
    private val clock: Clock,
) {
    fun dispatch(
        notificationIntentId: UUID,
        channel: NotificationChannel,
        recipientTargetRef: String,
        correlationId: String?,
    ): NotificationDispatchResult {
        val intent =
            requireNotNull(
                persistence.loadIntent(
                    notificationIntentId,
                ),
            ) {
                "Notification intent not found."
            }

        if (
            intent.policyState !=
            NotificationPolicyState.PENDING
        ) {
            return NotificationDispatchResult(
                status =
                    NotificationDispatchStatus
                        .NOT_PENDING,
                intent = intent,
            )
        }

        val now =
            clock.instant()

        if (
            !sourceAccess.canDispatch(
                intent = intent,
                at = now,
            )
        ) {
            persistence.suppress(
                notificationIntentId =
                    intent.id,
                reasonCode =
                    "SOURCE_NOT_ACTIONABLE",
                at = now,
            )
            return NotificationDispatchResult(
                status =
                    NotificationDispatchStatus
                        .SUPPRESSED_STALE,
                intent =
                    requireNotNull(
                        persistence.loadIntent(
                            intent.id,
                        ),
                    ),
            )
        }

        val provider =
            deliveryProvider.ifAvailable
                ?: return NotificationDispatchResult(
                    status =
                        NotificationDispatchStatus
                            .NO_PROVIDER,
                    intent = intent,
                )

        val result =
            provider.deliver(
                NotificationDeliveryRequest(
                    notificationIntentId =
                        intent.id,
                    channel = channel,
                    recipientTargetRef =
                        recipientTargetRef,
                    templateCode =
                        intent.templateCode,
                    safePreview =
                        intent.safePreview,
                    deepLinkType =
                        intent.deepLinkType,
                    deepLinkId =
                        intent.deepLinkId,
                    idempotencyKey =
                        intent.producerKey,
                    correlationId =
                        correlationId,
                ),
            )

        val attempt =
            persistence.recordAttempt(
                notificationIntentId =
                    intent.id,
                channel = channel,
                result = result,
                attemptedAt = now,
                correlationId =
                    correlationId,
            )

        val status =
            when (result.outcome) {
                NotificationDeliveryOutcome.ACCEPTED ->
                    NotificationDispatchStatus
                        .DISPATCHED

                NotificationDeliveryOutcome.FAILED_RETRYABLE ->
                    NotificationDispatchStatus
                        .FAILED_RETRYABLE

                NotificationDeliveryOutcome.FAILED_FINAL ->
                    NotificationDispatchStatus
                        .FAILED_FINAL
            }

        return NotificationDispatchResult(
            status = status,
            intent =
                requireNotNull(
                    persistence.loadIntent(
                        intent.id,
                    ),
                ),
            attempt = attempt,
        )
    }
}
