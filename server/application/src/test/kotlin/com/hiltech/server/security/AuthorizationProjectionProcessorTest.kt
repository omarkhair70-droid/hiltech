package com.hiltech.server.security

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class AuthorizationProjectionProcessorTest {
    private val now = Instant.parse("2026-09-19T00:00:00Z")
    private val tuple = OpenFgaTuple(
        subjectType = "user",
        subjectId = "tech-42",
        relation = "assigned_user",
        objectType = "work_order",
        objectId = "wo-42",
    )

    @Test
    fun staleGrantOutboxNeverReplaysOverNewerProjection() {
        val store = FakeStore(
            work = work(
                outboxVersion = 4,
                projectionVersion = 5,
                desiredState = AuthorizationDesiredState.ABSENT,
            ),
        )
        var gatewayCalls = 0
        val processor = processor(
            store = store,
            result = {
                gatewayCalls += 1
                OpenFgaMutationResult.Applied
            },
        )

        assertEquals(
            AuthorizationProjectionProcessResult.STALE_OUTBOX_COMPLETED,
            processor.processOne(now),
        )
        assertEquals(0, gatewayCalls)
        assertEquals("STALE_OUTBOX_REVISION", store.completedCode)
    }

    @Test
    fun pinnedModelMismatchFailsWithoutNetworkCall() {
        val store = FakeStore(
            work = work(
                authorizationModelId = "old-model",
            ),
        )
        var gatewayCalls = 0
        val processor = processor(
            store = store,
            result = {
                gatewayCalls += 1
                OpenFgaMutationResult.Applied
            },
        )

        assertEquals(
            AuthorizationProjectionProcessResult.FAILED_PERMANENT,
            processor.processOne(now),
        )
        assertEquals(0, gatewayCalls)
        assertEquals("AUTHORIZATION_MODEL_MISMATCH", store.failedCode)
    }

    @Test
    fun appliedAndRetryableOutcomesPersistThroughStoreBoundary() {
        val appliedStore = FakeStore(work = work())
        val appliedProcessor = processor(
            store = appliedStore,
            result = { OpenFgaMutationResult.Applied },
        )

        assertEquals(
            AuthorizationProjectionProcessResult.APPLIED,
            appliedProcessor.processOne(now),
        )
        assertEquals(1, appliedStore.appliedCount)

        val retryStore = FakeStore(work = work())
        val retryProcessor = processor(
            store = retryStore,
            result = {
                OpenFgaMutationResult.Retryable(
                    code = "OPENFGA_TRANSPORT_FAILURE",
                )
            },
        )

        assertEquals(
            AuthorizationProjectionProcessResult.RETRY_SCHEDULED,
            retryProcessor.processOne(now),
        )
        assertEquals(
            "OPENFGA_TRANSPORT_FAILURE",
            retryStore.retryCode,
        )
    }

    @Test
    fun retryPolicyMatchesFrozenScheduleWithBoundedStableJitter() {
        val policy = AuthorizationProjectionRetryPolicy()
        val bases = listOf(
            1_000L,
            2_000L,
            5_000L,
            10_000L,
            30_000L,
            60_000L,
            120_000L,
            300_000L,
            300_000L,
        )

        bases.forEachIndexed { index, base ->
            val delay = policy.delayMs(
                retryCount = index + 1,
                relationKey = tuple.relationKey,
            )

            assertTrue(
                delay in (base * 0.8).toLong()..(base * 1.2).toLong(),
                "delay=$delay base=$base",
            )
            assertEquals(
                delay,
                policy.delayMs(
                    retryCount = index + 1,
                    relationKey = tuple.relationKey,
                ),
            )
        }
    }

    private fun processor(
        store: FakeStore,
        result: () -> OpenFgaMutationResult,
    ): AuthorizationProjectionProcessor =
        AuthorizationProjectionProcessor(
            store = store,
            openFga = object : OpenFgaProjectionPort {
                override fun apply(
                    tuple: OpenFgaTuple,
                    desiredState: AuthorizationDesiredState,
                ): OpenFgaMutationResult = result()
            },
            properties = HiltechOpenFgaProperties(
                enabled = true,
                apiUrl = "http://openfga:8080",
                storeId = "store-locked",
                authorizationModelId = "model-locked",
            ),
        )

    private fun work(
        outboxVersion: Long = 5,
        projectionVersion: Long = 5,
        desiredState: AuthorizationDesiredState = AuthorizationDesiredState.PRESENT,
        authorizationModelId: String = "model-locked",
    ): AuthorizationProjectionWork =
        AuthorizationProjectionWork(
            outboxId = UUID.randomUUID(),
            outboxSourceVersion = outboxVersion,
            projection = AuthorizationRelationProjection(
                relationKey = tuple.relationKey,
                tuple = tuple,
                desiredState = desiredState,
                sourceType = "WorkAssignment",
                sourceId = "assignment-42",
                sourceVersion = projectionVersion,
                projectionState = AuthorizationProjectionState.PENDING,
                authorizationModelId = authorizationModelId,
                lastAttemptAt = null,
                retryCount = 0,
            ),
        )

    private class FakeStore(
        private val work: AuthorizationProjectionWork?,
    ) : AuthorizationProjectionStore {
        var completedCode: String? = null
        var appliedCount: Int = 0
        var retryCode: String? = null
        var failedCode: String? = null
        private var claimed = false

        override fun claimNext(now: Instant): AuthorizationProjectionWork? {
            if (claimed) {
                return null
            }
            claimed = true
            return work
        }

        override fun completeOutbox(
            work: AuthorizationProjectionWork,
            now: Instant,
            resultCode: String,
        ) {
            completedCode = resultCode
        }

        override fun markApplied(
            work: AuthorizationProjectionWork,
            now: Instant,
        ) {
            appliedCount += 1
        }

        override fun markRetryable(
            work: AuthorizationProjectionWork,
            now: Instant,
            errorCode: String,
        ) {
            retryCode = errorCode
        }

        override fun markFailed(
            work: AuthorizationProjectionWork,
            now: Instant,
            errorCode: String,
        ) {
            failedCode = errorCode
        }
    }
}
