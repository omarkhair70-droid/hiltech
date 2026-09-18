package com.hiltech.spike15

import jakarta.annotation.PostConstruct
import org.jooq.DSLContext
import org.springframework.stereotype.Repository

data class WorkSnapshot(
    val id: String,
    val projectId: String,
    val siteId: String,
    val assignee: String?,
    val state: String,
    val version: Long,
    val evidenceReady: Boolean,
    val acceptedBy: String?,
)

data class CommandOutcome(
    val resultCode: String,
    val serverVersion: Long?,
    val state: String?,
)

data class EvidenceRow(
    val evidenceId: String,
    val workOrderId: String,
    val objectKey: String,
    val expectedSha256: String,
    val contentType: String,
    val state: String,
)

@Repository
class WorkStore(
    private val dsl: DSLContext,
) {
    @PostConstruct
    fun resetSchema() {
        dsl.execute("DROP TABLE IF EXISTS module_projection")
        dsl.execute("DROP TABLE IF EXISTS audit_event")
        dsl.execute("DROP TABLE IF EXISTS evidence")
        dsl.execute("DROP TABLE IF EXISTS applied_operation")
        dsl.execute("DROP TABLE IF EXISTS work_order")

        dsl.execute(
            """
            CREATE TABLE work_order (
                id TEXT PRIMARY KEY,
                project_id TEXT NOT NULL,
                site_id TEXT NOT NULL,
                assignee TEXT,
                state TEXT NOT NULL,
                version BIGINT NOT NULL CHECK (version >= 1),
                evidence_ready BOOLEAN NOT NULL DEFAULT FALSE,
                accepted_by TEXT
            )
            """.trimIndent(),
        )

        dsl.execute(
            """
            CREATE TABLE applied_operation (
                operation_id TEXT PRIMARY KEY,
                work_order_id TEXT NOT NULL,
                command_type TEXT NOT NULL,
                result_code TEXT NOT NULL,
                server_version BIGINT,
                state TEXT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )

        dsl.execute(
            """
            CREATE TABLE evidence (
                evidence_id TEXT PRIMARY KEY,
                work_order_id TEXT NOT NULL REFERENCES work_order(id),
                object_key TEXT NOT NULL,
                expected_sha256 TEXT NOT NULL,
                content_type TEXT NOT NULL,
                state TEXT NOT NULL,
                size_bytes BIGINT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )

        dsl.execute(
            """
            CREATE TABLE audit_event (
                id BIGSERIAL PRIMARY KEY,
                correlation_id TEXT NOT NULL,
                traceparent TEXT NOT NULL,
                operation_id TEXT NOT NULL,
                actor TEXT NOT NULL,
                command_type TEXT NOT NULL,
                object_id TEXT NOT NULL,
                resulting_state TEXT,
                object_version BIGINT,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )

        dsl.execute(
            """
            CREATE TABLE module_projection (
                event_key TEXT PRIMARY KEY,
                event_type TEXT NOT NULL,
                work_order_id TEXT NOT NULL,
                object_version BIGINT NOT NULL,
                correlation_id TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now()
            )
            """.trimIndent(),
        )
    }

    fun createWorkOrder(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
    ): CommandOutcome {
        existing(operationId)?.let {
            return it.copy(resultCode = "DUPLICATE_APPLIED")
        }

        if (workOrNull(workOrderId) != null) {
            val current = work(workOrderId)
            return CommandOutcome("VERSION_CONFLICT", current.version, current.state)
        }

        dsl.execute(
            """
            INSERT INTO work_order (
                id, project_id, site_id, state, version
            ) VALUES (?, 'project-a', 'site-a', 'PLANNED', 1)
            """.trimIndent(),
            workOrderId,
        )

        return recordApplied(
            operationId = operationId,
            correlationId = correlationId,
            traceparent = traceparent,
            actor = actor,
            commandType = "CreateWorkOrder",
            workOrderId = workOrderId,
            version = 1,
            state = "PLANNED",
        )
    }

    fun assignWork(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
        assignee: String,
    ): CommandOutcome =
        transition(
            operationId = operationId,
            correlationId = correlationId,
            traceparent = traceparent,
            actor = actor,
            commandType = "AssignWork",
            workOrderId = workOrderId,
            expectedVersion = expectedVersion,
            expectedState = "PLANNED",
            newState = "ASSIGNED",
            extraSql = ", assignee = ?",
            extraArgs = arrayOf(assignee),
        )

    fun startWork(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome =
        transition(
            operationId,
            correlationId,
            traceparent,
            actor,
            "StartWork",
            workOrderId,
            expectedVersion,
            "ASSIGNED",
            "IN_PROGRESS",
        )

    fun submitCompletion(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome {
        existing(operationId)?.let {
            return it.copy(resultCode = "DUPLICATE_APPLIED")
        }

        val changed = dsl.execute(
            """
            UPDATE work_order
            SET state = 'SUBMITTED_FOR_REVIEW',
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND state = 'IN_PROGRESS'
              AND evidence_ready = TRUE
            """.trimIndent(),
            workOrderId,
            expectedVersion,
        )

        if (changed == 0) {
            val current = work(workOrderId)
            val code =
                if (!current.evidenceReady) "EVIDENCE_NOT_READY"
                else "VERSION_CONFLICT"
            return CommandOutcome(code, current.version, current.state)
        }

        val current = work(workOrderId)
        return recordApplied(
            operationId,
            correlationId,
            traceparent,
            actor,
            "SubmitWorkCompletion",
            workOrderId,
            current.version,
            current.state,
        )
    }

    fun touchWork(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome {
        existing(operationId)?.let {
            return it.copy(resultCode = "DUPLICATE_APPLIED")
        }

        val changed = dsl.execute(
            "UPDATE work_order SET version = version + 1 WHERE id = ? AND version = ?",
            workOrderId,
            expectedVersion,
        )

        if (changed == 0) {
            val current = work(workOrderId)
            return CommandOutcome("VERSION_CONFLICT", current.version, current.state)
        }

        val current = work(workOrderId)
        return recordApplied(
            operationId,
            correlationId,
            traceparent,
            actor,
            "PMUpdateWork",
            workOrderId,
            current.version,
            current.state,
        )
    }

    fun acceptWork(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        workOrderId: String,
        expectedVersion: Long,
    ): CommandOutcome {
        existing(operationId)?.let {
            return it.copy(resultCode = "DUPLICATE_APPLIED")
        }

        val changed = dsl.execute(
            """
            UPDATE work_order
            SET state = 'ACCEPTED',
                version = version + 1,
                accepted_by = ?
            WHERE id = ?
              AND version = ?
              AND state = 'SUBMITTED_FOR_REVIEW'
            """.trimIndent(),
            actor,
            workOrderId,
            expectedVersion,
        )

        if (changed == 0) {
            val current = work(workOrderId)
            return CommandOutcome("VERSION_CONFLICT", current.version, current.state)
        }

        val current = work(workOrderId)
        return recordApplied(
            operationId,
            correlationId,
            traceparent,
            actor,
            "AcceptWork",
            workOrderId,
            current.version,
            current.state,
        )
    }

    fun reserveEvidence(
        evidenceId: String,
        workOrderId: String,
        objectKey: String,
        expectedSha256: String,
        contentType: String,
    ) {
        dsl.execute(
            """
            INSERT INTO evidence (
                evidence_id, work_order_id, object_key,
                expected_sha256, content_type, state
            ) VALUES (?, ?, ?, ?, ?, 'RESERVED')
            ON CONFLICT (evidence_id) DO NOTHING
            """.trimIndent(),
            evidenceId,
            workOrderId,
            objectKey,
            expectedSha256,
            contentType,
        )
    }

    fun evidence(evidenceId: String): EvidenceRow {
        val record = dsl.fetchOne(
            """
            SELECT evidence_id, work_order_id, object_key,
                   expected_sha256, content_type, state
            FROM evidence
            WHERE evidence_id = ?
            """.trimIndent(),
            evidenceId,
        ) ?: error("Evidence not found: $evidenceId")

        return EvidenceRow(
            evidenceId = record.get("evidence_id", String::class.java),
            workOrderId = record.get("work_order_id", String::class.java),
            objectKey = record.get("object_key", String::class.java),
            expectedSha256 = record.get("expected_sha256", String::class.java),
            contentType = record.get("content_type", String::class.java),
            state = record.get("state", String::class.java),
        )
    }

    fun finalizeEvidence(
        evidenceId: String,
        sizeBytes: Long,
        correlationId: String,
        traceparent: String,
        actor: String,
    ) {
        val row = evidence(evidenceId)

        dsl.execute(
            "UPDATE evidence SET state = 'READY', size_bytes = ? WHERE evidence_id = ?",
            sizeBytes,
            evidenceId,
        )
        dsl.execute(
            "UPDATE work_order SET evidence_ready = TRUE WHERE id = ?",
            row.workOrderId,
        )
        recordAudit(
            correlationId = correlationId,
            traceparent = traceparent,
            operationId = "finalize-$evidenceId",
            actor = actor,
            commandType = "FinalizeEvidence",
            workOrderId = row.workOrderId,
            state = work(row.workOrderId).state,
            version = work(row.workOrderId).version,
        )
    }

    fun work(workOrderId: String): WorkSnapshot =
        workOrNull(workOrderId) ?: error("Work order not found: $workOrderId")

    fun auditRows(): List<Map<String, Any?>> =
        dsl.fetch(
            """
            SELECT correlation_id, traceparent, operation_id, actor,
                   command_type, object_id, resulting_state, object_version
            FROM audit_event
            ORDER BY id
            """.trimIndent(),
        ).map { record ->
            linkedMapOf(
                "correlationId" to record.get("correlation_id"),
                "traceparent" to record.get("traceparent"),
                "operationId" to record.get("operation_id"),
                "actor" to record.get("actor"),
                "commandType" to record.get("command_type"),
                "objectId" to record.get("object_id"),
                "resultingState" to record.get("resulting_state"),
                "objectVersion" to record.get("object_version"),
            )
        }

    fun recordProjection(
        eventKey: String,
        eventType: String,
        workOrderId: String,
        version: Long,
        correlationId: String,
    ) {
        dsl.execute(
            """
            INSERT INTO module_projection (
                event_key, event_type, work_order_id,
                object_version, correlation_id
            ) VALUES (?, ?, ?, ?, ?)
            ON CONFLICT (event_key) DO NOTHING
            """.trimIndent(),
            eventKey,
            eventType,
            workOrderId,
            version,
            correlationId,
        )
    }

    fun projectionCount(): Int =
        dsl.fetchOne("SELECT COUNT(*) AS c FROM module_projection")!!
            .get("c", java.lang.Long::class.java)
            .toInt()

    private fun transition(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        commandType: String,
        workOrderId: String,
        expectedVersion: Long,
        expectedState: String,
        newState: String,
        extraSql: String = "",
        extraArgs: Array<Any> = emptyArray(),
    ): CommandOutcome {
        existing(operationId)?.let {
            return it.copy(resultCode = "DUPLICATE_APPLIED")
        }

        val sql =
            """
            UPDATE work_order
            SET state = ?,
                version = version + 1
                $extraSql
            WHERE id = ?
              AND version = ?
              AND state = ?
            """.trimIndent()

        val args = mutableListOf<Any>(newState)
        args.addAll(extraArgs)
        args += workOrderId
        args += expectedVersion
        args += expectedState

        val changed = dsl.execute(sql, *args.toTypedArray())

        if (changed == 0) {
            val current = work(workOrderId)
            return CommandOutcome("VERSION_CONFLICT", current.version, current.state)
        }

        val current = work(workOrderId)
        return recordApplied(
            operationId,
            correlationId,
            traceparent,
            actor,
            commandType,
            workOrderId,
            current.version,
            current.state,
        )
    }

    private fun recordApplied(
        operationId: String,
        correlationId: String,
        traceparent: String,
        actor: String,
        commandType: String,
        workOrderId: String,
        version: Long,
        state: String,
    ): CommandOutcome {
        dsl.execute(
            """
            INSERT INTO applied_operation (
                operation_id, work_order_id, command_type,
                result_code, server_version, state
            ) VALUES (?, ?, ?, 'APPLIED', ?, ?)
            """.trimIndent(),
            operationId,
            workOrderId,
            commandType,
            version,
            state,
        )

        recordAudit(
            correlationId,
            traceparent,
            operationId,
            actor,
            commandType,
            workOrderId,
            state,
            version,
        )

        return CommandOutcome("APPLIED", version, state)
    }

    private fun recordAudit(
        correlationId: String,
        traceparent: String,
        operationId: String,
        actor: String,
        commandType: String,
        workOrderId: String,
        state: String,
        version: Long,
    ) {
        dsl.execute(
            """
            INSERT INTO audit_event (
                correlation_id, traceparent, operation_id, actor,
                command_type, object_id, resulting_state, object_version
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            correlationId,
            traceparent,
            operationId,
            actor,
            commandType,
            workOrderId,
            state,
            version,
        )
    }

    private fun existing(operationId: String): CommandOutcome? {
        val record = dsl.fetchOne(
            """
            SELECT result_code, server_version, state
            FROM applied_operation
            WHERE operation_id = ?
            """.trimIndent(),
            operationId,
        ) ?: return null

        return CommandOutcome(
            resultCode = record.get("result_code", String::class.java),
            serverVersion = record.get("server_version", java.lang.Long::class.java)?.toLong(),
            state = record.get("state", String::class.java),
        )
    }

    private fun workOrNull(workOrderId: String): WorkSnapshot? {
        val record = dsl.fetchOne(
            """
            SELECT id, project_id, site_id, assignee, state,
                   version, evidence_ready, accepted_by
            FROM work_order
            WHERE id = ?
            """.trimIndent(),
            workOrderId,
        ) ?: return null

        return WorkSnapshot(
            id = record.get("id", String::class.java),
            projectId = record.get("project_id", String::class.java),
            siteId = record.get("site_id", String::class.java),
            assignee = record.get("assignee", String::class.java),
            state = record.get("state", String::class.java),
            version = record.get("version", java.lang.Long::class.java).toLong(),
            evidenceReady = record.get("evidence_ready", java.lang.Boolean::class.java).booleanValue(),
            acceptedBy = record.get("accepted_by", String::class.java),
        )
    }
}
