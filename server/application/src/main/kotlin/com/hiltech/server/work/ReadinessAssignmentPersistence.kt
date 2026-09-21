package com.hiltech.server.work

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

interface ReadinessAssignmentPersistencePort {
    fun assignmentPolicy(configId: UUID): AssignmentPolicyExecutionSnapshot?
    fun readinessPolicy(configId: UUID): ReadinessPolicyExecutionSnapshot?
    fun readinessRequirements(
        workOrderId: UUID,
        readinessPolicyId: UUID,
    ): List<ReadinessRequirementExecutionSnapshot>

    fun blockers(workOrderId: UUID): List<WorkReadinessBlockerSnapshot>
    fun dependenciesSatisfied(workOrderId: UUID): Boolean
    fun assignments(workOrderId: UUID): List<WorkAssignmentRecord>
    fun activeAssignment(workOrderId: UUID): WorkAssignmentRecord?
    fun assignment(assignmentId: UUID): WorkAssignmentRecord?

    fun updateRequirementEvaluation(
        requirementId: UUID,
        expectedVersion: Long,
        state: String,
        reasonCode: String?,
        sourceAsOf: Instant,
        evaluatedAt: Instant,
        satisfiedByRef: String?,
    ): Boolean

    fun resolveReadinessBlockers(
        workOrderId: UUID,
        at: Instant,
    )

    fun resolveReadinessBlocker(
        workOrderId: UUID,
        requirementId: UUID,
        at: Instant,
    )

    fun insertReadinessBlocker(
        blockerId: UUID,
        workOrderId: UUID,
        requirementId: UUID,
        reasonCode: String,
        actorUserId: UUID,
        at: Instant,
    )

    fun updateReadiness(
        workOrderId: UUID,
        expectedVersion: Long,
        state: WorkReadiness,
        at: Instant,
    ): Boolean

    fun insertWaiver(
        waiverId: UUID,
        order: WorkOrderSnapshot,
        requirement: ReadinessRequirementExecutionSnapshot,
        operationId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    )

    fun applyWaiver(
        requirementId: UUID,
        expectedVersion: Long,
        waiverId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun insertAssignment(
        assignmentId: UUID,
        order: WorkOrderSnapshot,
        target: EligibleTargetResult,
        operationId: UUID,
        actorUserId: UUID,
        at: Instant,
        supersedesAssignmentId: UUID?,
        reason: String?,
    )

    fun endAssignment(
        assignmentId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun transitionToAssigned(
        workOrderId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun bumpAssignedWorkOrder(
        workOrderId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean
}

@Component
class JdbcReadinessAssignmentPersistence(
    private val jdbc: JdbcTemplate,
) : ReadinessAssignmentPersistencePort {
    override fun assignmentPolicy(configId: UUID): AssignmentPolicyExecutionSnapshot? =
        jdbc.query(
            """
            SELECT
                cr.id,
                cr.revision_number,
                ap.execution_mode,
                ap.allowed_target_types,
                ap.required_role_codes,
                ap.required_skill_or_certification_codes,
                ap.allow_preboarding_employee,
                ap.allow_cross_team_assignment,
                ap.allow_external_subcontractor,
                ap.reassignment_requires_reason
            FROM config_revision cr
            JOIN assignment_policy ap
              ON ap.config_revision_id = cr.id
            WHERE cr.id = ?
            """.trimIndent(),
            { rs, _ ->
                AssignmentPolicyExecutionSnapshot(
                    configId = rs.getObject("id", UUID::class.java),
                    revision = rs.getInt("revision_number"),
                    mode = AssignmentMode.valueOf(rs.getString("execution_mode")),
                    allowedTargetTypes =
                        rs.stringArray("allowed_target_types")
                            .map(AssignmentTargetType::valueOf)
                            .toSet(),
                    requiredRoleCodes =
                        rs.stringArray("required_role_codes").toSet(),
                    requiredCertificationCodes =
                        rs.stringArray("required_skill_or_certification_codes").toSet(),
                    allowPreboardingEmployee =
                        rs.getBoolean("allow_preboarding_employee"),
                    allowCrossTeamAssignment =
                        rs.getBoolean("allow_cross_team_assignment"),
                    allowExternalSubcontractor =
                        rs.getBoolean("allow_external_subcontractor"),
                    reassignmentRequiresReason =
                        rs.getBoolean("reassignment_requires_reason"),
                )
            },
            configId,
        ).singleOrNull()

    override fun readinessPolicy(configId: UUID): ReadinessPolicyExecutionSnapshot? =
        jdbc.query(
            """
            SELECT cr.id, cr.revision_number, rp.all_required_must_be_satisfied
            FROM config_revision cr
            JOIN readiness_policy rp
              ON rp.config_revision_id = cr.id
            WHERE cr.id = ?
            """.trimIndent(),
            { rs, _ ->
                ReadinessPolicyExecutionSnapshot(
                    configId = rs.getObject("id", UUID::class.java),
                    revision = rs.getInt("revision_number"),
                    allRequiredMustBeSatisfied =
                        rs.getBoolean("all_required_must_be_satisfied"),
                )
            },
            configId,
        ).singleOrNull()

    override fun readinessRequirements(
        workOrderId: UUID,
        readinessPolicyId: UUID,
    ): List<ReadinessRequirementExecutionSnapshot> =
        jdbc.query(
            """
            SELECT
                wri.id,
                wri.requirement_family,
                wri.requirement_key,
                wri.requirement_type_code,
                rpr.label,
                wri.required,
                wri.satisfaction_state,
                wri.evaluation_reason_code,
                wri.source_as_of,
                wri.evaluated_at,
                COALESCE(rpr.waiver_allowed, false) AS waiver_allowed,
                COALESCE(rpr.waiver_reason_required, true) AS waiver_reason_required,
                wri.waived,
                wri.waiver_ref,
                wri.version
            FROM work_requirement_instance wri
            LEFT JOIN readiness_policy_requirement rpr
              ON rpr.config_revision_id = ?
             AND rpr.requirement_key = wri.requirement_key
            WHERE wri.work_order_id = ?
              AND wri.requirement_family IN (
                  'READINESS',
                  'ASSET',
                  'MATERIAL',
                  'DOCUMENT'
              )
            ORDER BY
                CASE wri.requirement_family
                    WHEN 'READINESS' THEN 0
                    WHEN 'DOCUMENT' THEN 1
                    WHEN 'MATERIAL' THEN 2
                    WHEN 'ASSET' THEN 3
                    ELSE 4
                END,
                COALESCE(rpr.sort_order, 0),
                wri.requirement_key,
                wri.id
            """.trimIndent(),
            { rs, _ -> rs.readinessRequirement() },
            readinessPolicyId,
            workOrderId,
        )

    override fun dependenciesSatisfied(workOrderId: UUID): Boolean =
        jdbc.queryForObject(
            """
            SELECT NOT EXISTS (
                SELECT 1
                FROM work_order_dependency d
                JOIN work_order predecessor
                  ON predecessor.id = d.predecessor_work_order_id
                WHERE d.successor_work_order_id = ?
                  AND predecessor.lifecycle_state NOT IN ('ACCEPTED','CLOSED')
            )
            """.trimIndent(),
            Boolean::class.java,
            workOrderId,
        ) == true

    override fun blockers(workOrderId: UUID): List<WorkReadinessBlockerSnapshot> =
        jdbc.query(
            """
            SELECT id, source_requirement_id, blocker_type_code,
                   explanation_code, description, state, version
            FROM work_blocker
            WHERE work_order_id = ?
              AND state = 'OPEN'
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                WorkReadinessBlockerSnapshot(
                    blockerId = rs.getObject("id", UUID::class.java),
                    requirementId =
                        rs.getObject("source_requirement_id", UUID::class.java),
                    blockerTypeCode = rs.getString("blocker_type_code"),
                    explanationCode = rs.getString("explanation_code"),
                    description = rs.getString("description"),
                    state = rs.getString("state"),
                    version = rs.getLong("version"),
                )
            },
            workOrderId,
        )

    override fun assignments(workOrderId: UUID): List<WorkAssignmentRecord> =
        jdbc.query(
            assignmentSelect +
                """
                WHERE wa.work_order_id = ?
                ORDER BY wa.assigned_at DESC, wa.id DESC
                """.trimIndent(),
            assignmentMapper,
            workOrderId,
        )

    override fun activeAssignment(workOrderId: UUID): WorkAssignmentRecord? =
        jdbc.query(
            assignmentSelect +
                """
                WHERE wa.work_order_id = ?
                  AND wa.state = 'ACTIVE'
                  AND wa.valid_from <= now()
                  AND (wa.valid_until IS NULL OR wa.valid_until > now())
                ORDER BY wa.assigned_at DESC
                LIMIT 1
                """.trimIndent(),
            assignmentMapper,
            workOrderId,
        ).singleOrNull()

    override fun assignment(assignmentId: UUID): WorkAssignmentRecord? =
        jdbc.query(
            assignmentSelect + " WHERE wa.id = ?",
            assignmentMapper,
            assignmentId,
        ).singleOrNull()

    override fun updateRequirementEvaluation(
        requirementId: UUID,
        expectedVersion: Long,
        state: String,
        reasonCode: String?,
        sourceAsOf: Instant,
        evaluatedAt: Instant,
        satisfiedByRef: String?,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_requirement_instance
            SET satisfaction_state = ?,
                evaluation_reason_code = ?,
                source_as_of = ?,
                evaluated_at = ?,
                satisfied_by_ref = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND waived = false
            """.trimIndent(),
            state,
            reasonCode,
            sourceAsOf.atOffset(ZoneOffset.UTC),
            evaluatedAt.atOffset(ZoneOffset.UTC),
            satisfiedByRef,
            evaluatedAt.atOffset(ZoneOffset.UTC),
            requirementId,
            expectedVersion,
        ) == 1

    override fun resolveReadinessBlockers(
        workOrderId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE work_blocker
            SET state = 'RESOLVED',
                resolved_at = ?,
                resolution = 'READINESS_REEVALUATED',
                version = version + 1
            WHERE work_order_id = ?
              AND state = 'OPEN'
              AND source_requirement_id IS NOT NULL
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
        )
    }

    override fun resolveReadinessBlocker(
        workOrderId: UUID,
        requirementId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE work_blocker
            SET state = 'RESOLVED',
                resolved_at = ?,
                resolution = 'READINESS_REQUIREMENT_RESOLVED',
                version = version + 1
            WHERE work_order_id = ?
              AND source_requirement_id = ?
              AND state = 'OPEN'
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            requirementId,
        )
    }

    override fun insertReadinessBlocker(
        blockerId: UUID,
        workOrderId: UUID,
        requirementId: UUID,
        reasonCode: String,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO work_blocker(
                id, work_order_id, blocker_type_code, severity_code,
                description, created_by, owner_target_type, owner_target_id,
                state, resolved_at, resolution, client_visible, version,
                source_requirement_id, explanation_code
            )
            VALUES (?, ?, 'READINESS_REQUIREMENT', 'BLOCKING', ?, ?,
                    NULL, NULL, 'OPEN', NULL, NULL, false, 1, ?, ?)
            """.trimIndent(),
            blockerId,
            workOrderId,
            reasonCode,
            actorUserId,
            requirementId,
            reasonCode,
        )
    }

    override fun updateReadiness(
        workOrderId: UUID,
        expectedVersion: Long,
        state: WorkReadiness,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_order
            SET readiness_state = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND lifecycle_state IN ('PLANNED','ASSIGNED')
            """.trimIndent(),
            state.name,
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            expectedVersion,
        ) == 1

    override fun insertWaiver(
        waiverId: UUID,
        order: WorkOrderSnapshot,
        requirement: ReadinessRequirementExecutionSnapshot,
        operationId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO work_readiness_waiver(
                id, organization_id, work_order_id, requirement_id,
                requirement_version, reason, waived_by_user_id,
                source_operation_id, created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            waiverId,
            order.organizationId,
            order.workOrderId,
            requirement.requirementId,
            requirement.version,
            reason,
            actorUserId,
            operationId,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    override fun applyWaiver(
        requirementId: UUID,
        expectedVersion: Long,
        waiverId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_requirement_instance
            SET satisfaction_state = 'WAIVED',
                waived = true,
                waiver_ref = ?,
                waived_by_user_id = ?,
                waiver_reason = ?,
                evaluation_reason_code = 'WAIVED_BY_AUTHORITY',
                source_as_of = ?,
                evaluated_at = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND waived = false
            """.trimIndent(),
            waiverId,
            actorUserId,
            reason,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            requirementId,
            expectedVersion,
        ) == 1

    override fun insertAssignment(
        assignmentId: UUID,
        order: WorkOrderSnapshot,
        target: EligibleTargetResult,
        operationId: UUID,
        actorUserId: UUID,
        at: Instant,
        supersedesAssignmentId: UUID?,
        reason: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO work_assignment(
                id, work_order_id, target_type, target_id, lead,
                assigned_at, assigned_by, valid_from, valid_until,
                state, source_operation_id, version,
                organization_id, project_id, supersedes_assignment_id, reason
            )
            VALUES (?, ?, ?, ?, true, ?, ?, ?, NULL,
                    'ACTIVE', ?, 1, ?, ?, ?, ?)
            """.trimIndent(),
            assignmentId,
            order.workOrderId,
            target.targetType.name,
            target.targetId,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            operationId,
            order.organizationId,
            order.projectId,
            supersedesAssignmentId,
            reason,
        )
    }

    override fun endAssignment(
        assignmentId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_assignment
            SET state = 'REPLACED',
                valid_until = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND state = 'ACTIVE'
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            assignmentId,
            expectedVersion,
        ) == 1

    override fun transitionToAssigned(
        workOrderId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_order
            SET lifecycle_state = 'ASSIGNED',
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND lifecycle_state = 'PLANNED'
              AND readiness_state = 'READY'
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            expectedVersion,
        ) == 1

    override fun bumpAssignedWorkOrder(
        workOrderId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_order
            SET updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND lifecycle_state = 'ASSIGNED'
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            expectedVersion,
        ) == 1

    private val assignmentMapper = { rs: ResultSet, _: Int ->
        WorkAssignmentRecord(
            assignmentId = rs.getObject("id", UUID::class.java),
            workOrderId = rs.getObject("work_order_id", UUID::class.java),
            targetType = AssignmentTargetType.valueOf(rs.getString("target_type")),
            targetId = rs.getObject("target_id", UUID::class.java),
            targetLabel = rs.getString("target_label"),
            lead = rs.getBoolean("lead"),
            state = rs.getString("state"),
            assignedAt = rs.getObject("assigned_at", java.time.OffsetDateTime::class.java).toInstant(),
            assignedBy = rs.getObject("assigned_by", UUID::class.java),
            validFrom = rs.getObject("valid_from", java.time.OffsetDateTime::class.java).toInstant(),
            validUntil = rs.getObject("valid_until", java.time.OffsetDateTime::class.java)?.toInstant(),
            supersedesAssignmentId =
                rs.getObject("supersedes_assignment_id", UUID::class.java),
            reason = rs.getString("reason"),
            version = rs.getLong("version"),
        )
    }

    private val assignmentSelect =
        """
        SELECT wa.*,
               CASE wa.target_type
                   WHEN 'USER' THEN COALESCE(p.display_name, wa.target_id::text)
                   WHEN 'TEAM' THEN COALESCE(t.name, wa.target_id::text)
                   WHEN 'CREW' THEN COALESCE(c.name, wa.target_id::text)
                   WHEN 'SUBCONTRACTOR_ORGANIZATION' THEN COALESCE(o.display_name, wa.target_id::text)
                   ELSE wa.target_id::text
               END AS target_label
        FROM work_assignment wa
        LEFT JOIN user_identity ui
          ON wa.target_type = 'USER' AND ui.id = wa.target_id
        LEFT JOIN person p
          ON p.id = ui.person_id
        LEFT JOIN team t
          ON wa.target_type = 'TEAM' AND t.id = wa.target_id
        LEFT JOIN work_crew c
          ON wa.target_type = 'CREW' AND c.id = wa.target_id
        LEFT JOIN organization o
          ON wa.target_type = 'SUBCONTRACTOR_ORGANIZATION' AND o.id = wa.target_id
        """.trimIndent() + "\n"

    private fun ResultSet.readinessRequirement() =
        ReadinessRequirementExecutionSnapshot(
            requirementId = getObject("id", UUID::class.java),
            family = getString("requirement_family"),
            key = getString("requirement_key"),
            typeCode = getString("requirement_type_code"),
            label = getString("label"),
            required = getBoolean("required"),
            satisfactionState = getString("satisfaction_state"),
            evaluationReasonCode = getString("evaluation_reason_code"),
            sourceAsOf =
                getObject("source_as_of", java.time.OffsetDateTime::class.java)?.toInstant(),
            evaluatedAt =
                getObject("evaluated_at", java.time.OffsetDateTime::class.java)?.toInstant(),
            waiverAllowed = getBoolean("waiver_allowed"),
            waiverReasonRequired = getBoolean("waiver_reason_required"),
            waived = getBoolean("waived"),
            waiverRef = getObject("waiver_ref", UUID::class.java),
            version = getLong("version"),
        )

    private fun ResultSet.stringArray(column: String): List<String> {
        val raw = getArray(column)?.array ?: return emptyList()
        return (raw as Array<*>).mapNotNull { it?.toString() }
    }
}
