package com.hiltech.spike15

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

data class WorkSubmittedEvent(
    val workOrderId: String,
    val version: Long,
    val correlationId: String,
)

data class WorkAcceptedEvent(
    val workOrderId: String,
    val version: Long,
    val correlationId: String,
)

@Service
class WorkService(
    private val store: WorkStore,
    private val publisher: ApplicationEventPublisher,
) {
    @Transactional
    fun create(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
    ): CommandOutcome =
        store.createWorkOrder(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
        )

    @Transactional
    fun assign(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
        assignee: String,
    ): CommandOutcome =
        store.assignWork(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
            expectedVersion,
            assignee,
        )

    @Transactional
    fun start(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome =
        store.startWork(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
            expectedVersion,
        )

    @Transactional
    fun submit(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome {
        val result = store.submitCompletion(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
            expectedVersion,
        )

        if (result.resultCode == "APPLIED") {
            publisher.publishEvent(
                WorkSubmittedEvent(
                    workOrderId = workOrderId,
                    version = requireNotNull(result.serverVersion),
                    correlationId = correlationId,
                ),
            )
        }

        return result
    }

    @Transactional
    fun touch(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome =
        store.touchWork(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
            expectedVersion,
        )

    @Transactional
    fun accept(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome {
        val result = store.acceptWork(
            operationId,
            correlationId,
            traceparent,
            actor,
            workOrderId,
            expectedVersion,
        )

        if (result.resultCode == "APPLIED") {
            publisher.publishEvent(
                WorkAcceptedEvent(
                    workOrderId = workOrderId,
                    version = requireNotNull(result.serverVersion),
                    correlationId = correlationId,
                ),
            )
        }

        return result
    }

    @Transactional
    fun reserveEvidence(
        evidenceId: String,
        workOrderId: String,
        objectKey: String,
        expectedSha256: String,
        contentType: String,
    ) {
        store.reserveEvidence(
            evidenceId,
            workOrderId,
            objectKey,
            expectedSha256,
            contentType,
        )
    }

    @Transactional
    fun finalizeEvidence(
        evidenceId: String,
        sizeBytes: Long,
        correlationId: String,
        traceparent: String,
        actor: String,
    ) {
        store.finalizeEvidence(
            evidenceId,
            sizeBytes,
            correlationId,
            traceparent,
            actor,
        )
    }
}
