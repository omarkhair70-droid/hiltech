package com.hiltech.server.documents

import java.time.Instant
import java.util.UUID

/**
 * Immutable application fact emitted only after an authoritative Evidence
 * terminal state transition commits through the command transaction.
 *
 * Mutable bean properties + no-arg constructor keep the event compatible with
 * the Spring Modulith JDBC publication registry serializer/resubmission path.
 */
class EvidenceTerminalStateChanged() {
    var eventId: String = ""
    var evidenceId: String = ""
    var workOrderId: String = ""
    var sourceVersion: Long = 0
    var storageState: String = ""
    var evidenceTypeCode: String = ""
    var evidenceRequirementKey: String = ""
    var classificationCode: String = ""
    var actorIdentityId: String = ""
    var occurredAt: String = ""
    var correlationId: String = ""

    constructor(
        eventId: UUID,
        evidenceId: UUID,
        workOrderId: UUID,
        sourceVersion: Long,
        storageState: String,
        evidenceTypeCode: String,
        evidenceRequirementKey: String,
        classificationCode: String,
        actorIdentityId: UUID,
        occurredAt: Instant,
        correlationId: String,
    ) : this() {
        require(
            storageState in
                setOf(
                    "READY",
                    "QUARANTINED",
                    "REJECTED",
                ),
        )
        require(sourceVersion >= 1)
        require(correlationId.isNotBlank())

        this.eventId = eventId.toString()
        this.evidenceId = evidenceId.toString()
        this.workOrderId = workOrderId.toString()
        this.sourceVersion = sourceVersion
        this.storageState = storageState
        this.evidenceTypeCode = evidenceTypeCode
        this.evidenceRequirementKey =
            evidenceRequirementKey
        this.classificationCode =
            classificationCode
        this.actorIdentityId =
            actorIdentityId.toString()
        this.occurredAt = occurredAt.toString()
        this.correlationId =
            correlationId.take(128)
    }
}
