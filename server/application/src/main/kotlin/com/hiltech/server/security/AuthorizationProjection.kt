package com.hiltech.server.security

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.UUID
import kotlin.math.absoluteValue

enum class AuthorizationProjectionState {
    PENDING,
    APPLYING,
    APPLIED,
    FAILED,
}

data class AuthorizationRelationProjection(
    val relationKey: String,
    val tuple: OpenFgaTuple,
    val desiredState: AuthorizationDesiredState,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long,
    val projectionState: AuthorizationProjectionState,
    val authorizationModelId: String,
    val lastAttemptAt: Instant?,
    val retryCount: Int,
)

data class AuthorizationProjectionWork(
    val outboxId: UUID,
    val outboxSourceVersion: Long,
    val projection: AuthorizationRelationProjection,
)

data class AuthorizationProjectionIntent(
    val eventId: UUID,
    val tuple: OpenFgaTuple,
    val desiredState: AuthorizationDesiredState,
    val sourceType: String,
    val sourceId: String,
    val sourceVersion: Long,
    val eventType: String = "RELATION_DESIRED_STATE_CHANGED",
    val occurredAt: Instant,
) {
    init {
        require(sourceType.isNotBlank())
        require(sourceId.isNotBlank())
        require(sourceVersion >= 1)
        require(eventType.isNotBlank())
        require(tuple.relationKey.length <= 512)
    }
}

fun interface AuthorizationProjectionIntentWriter {
    fun write(intent: AuthorizationProjectionIntent)
}

interface AuthorizationProjectionStore {
    fun claimNext(now: Instant): AuthorizationProjectionWork?

    fun completeOutbox(
        work: AuthorizationProjectionWork,
        now: Instant,
        resultCode: String,
    )

    fun markApplied(
        work: AuthorizationProjectionWork,
        now: Instant,
    )

    fun markRetryable(
        work: AuthorizationProjectionWork,
        now: Instant,
        errorCode: String,
    )

    fun markFailed(
        work: AuthorizationProjectionWork,
        now: Instant,
        errorCode: String,
    )
}

class AuthorizationProjectionRetryPolicy {
    private val baseDelaysMs = longArrayOf(
        1_000L,
        2_000L,
        5_000L,
        10_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L,
    )

    fun delayMs(
        retryCount: Int,
        relationKey: String,
    ): Long {
        if (retryCount <= 0) {
            return 0
        }

        val base = baseDelaysMs[(retryCount - 1).coerceAtMost(baseDelaysMs.lastIndex)]
        val stable = (31L * relationKey.hashCode().toLong() + retryCount.toLong()).absoluteValue
        val jitterBasisPoints = (stable % 4_001L) - 2_000L
        val jitterFraction = jitterBasisPoints / 10_000.0

        return (base * (1.0 + jitterFraction))
            .toLong()
            .coerceAtLeast(1)
    }

    fun isDue(
        projection: AuthorizationRelationProjection,
        now: Instant,
    ): Boolean {
        val lastAttempt = projection.lastAttemptAt ?: return true
        val delay = delayMs(
            retryCount = projection.retryCount,
            relationKey = projection.relationKey,
        )
        return !now.isBefore(lastAttempt.plusMillis(delay))
    }
}

enum class AuthorizationProjectionProcessResult {
    NO_WORK,
    STALE_OUTBOX_COMPLETED,
    ALREADY_APPLIED_COMPLETED,
    APPLIED,
    RETRY_SCHEDULED,
    FAILED_PERMANENT,
}

class AuthorizationProjectionProcessor(
    private val store: AuthorizationProjectionStore,
    private val openFga: OpenFgaProjectionPort,
    private val properties: HiltechOpenFgaProperties,
) {
    fun processOne(
        now: Instant = Instant.now(),
    ): AuthorizationProjectionProcessResult {
        val work = store.claimNext(now)
            ?: return AuthorizationProjectionProcessResult.NO_WORK

        val projection = work.projection

        if (work.outboxSourceVersion < projection.sourceVersion) {
            store.completeOutbox(
                work = work,
                now = now,
                resultCode = "STALE_OUTBOX_REVISION",
            )
            return AuthorizationProjectionProcessResult.STALE_OUTBOX_COMPLETED
        }

        if (work.outboxSourceVersion > projection.sourceVersion) {
            store.markFailed(
                work = work,
                now = now,
                errorCode = "OUTBOX_AHEAD_OF_PROJECTION",
            )
            return AuthorizationProjectionProcessResult.FAILED_PERMANENT
        }

        if (projection.projectionState == AuthorizationProjectionState.APPLIED) {
            store.completeOutbox(
                work = work,
                now = now,
                resultCode = "ALREADY_APPLIED",
            )
            return AuthorizationProjectionProcessResult.ALREADY_APPLIED_COMPLETED
        }

        if (
            projection.authorizationModelId != properties.authorizationModelId
        ) {
            store.markFailed(
                work = work,
                now = now,
                errorCode = "AUTHORIZATION_MODEL_MISMATCH",
            )
            return AuthorizationProjectionProcessResult.FAILED_PERMANENT
        }

        return when (
            val result = openFga.apply(
                tuple = projection.tuple,
                desiredState = projection.desiredState,
            )
        ) {
            OpenFgaMutationResult.Applied -> {
                store.markApplied(
                    work = work,
                    now = now,
                )
                AuthorizationProjectionProcessResult.APPLIED
            }

            is OpenFgaMutationResult.Retryable -> {
                store.markRetryable(
                    work = work,
                    now = now,
                    errorCode = result.code,
                )
                AuthorizationProjectionProcessResult.RETRY_SCHEDULED
            }

            is OpenFgaMutationResult.PermanentFailure -> {
                store.markFailed(
                    work = work,
                    now = now,
                    errorCode = result.code,
                )
                AuthorizationProjectionProcessResult.FAILED_PERMANENT
            }
        }
    }
}

@Configuration(proxyBeanMethods = false)
class AuthorizationProjectionCoreConfiguration {
    @Bean
    fun authorizationProjectionRetryPolicy(): AuthorizationProjectionRetryPolicy =
        AuthorizationProjectionRetryPolicy()

    @Bean
    @ConditionalOnProperty(
        prefix = "hiltech.authorization.openfga",
        name = ["enabled"],
        havingValue = "true",
    )
    fun authorizationProjectionProcessor(
        store: AuthorizationProjectionStore,
        openFga: OpenFgaGateway,
        properties: HiltechOpenFgaProperties,
    ): AuthorizationProjectionProcessor =
        AuthorizationProjectionProcessor(
            store = store,
            openFga = openFga,
            properties = properties,
        )
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(
    prefix = "hiltech.authorization.openfga",
    name = ["enabled"],
    havingValue = "true",
)
class AuthorizationProjectionSchedulingConfiguration(
    private val processor: AuthorizationProjectionProcessor,
) {
    @Scheduled(
        fixedDelayString = "\${hiltech.authorization.openfga.projector-poll-delay-ms:1000}",
    )
    fun projectPendingAuthorizationRelations() {
        repeat(MAX_BATCH_PER_POLL) {
            if (
                processor.processOne() ==
                AuthorizationProjectionProcessResult.NO_WORK
            ) {
                return
            }
        }
    }

    companion object {
        private const val MAX_BATCH_PER_POLL = 25
    }
}
