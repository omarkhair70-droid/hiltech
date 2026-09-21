package com.hiltech.server.work

import com.hiltech.server.projects.ProjectSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface ReviewProgressHealthPersistencePort {
    fun reviewPolicy(reviewPolicyId: UUID): ReviewPolicyExecutionSnapshot?
    fun submittedReviewVersion(workOrderId: UUID): Long?
    fun decisions(workOrderId: UUID, submittedVersion: Long): List<ReviewDecisionSnapshot>
    fun insertDecision(
        decisionId: UUID,
        operationId: UUID,
        workOrder: WorkOrderSnapshot,
        submittedVersion: Long,
        policy: ReviewPolicyExecutionSnapshot,
        step: ReviewStepExecutionSnapshot?,
        reviewerUserId: UUID,
        decision: WorkReviewDecisionType,
        reason: String?,
        reviewerSourceType: String?,
        reviewerSourceRef: String?,
        at: Instant,
        correlationId: String,
    )
    fun acceptWorkOrder(
        workOrderId: UUID,
        expectedVersion: Long,
        submittedVersion: Long,
        decisionId: UUID,
        at: Instant,
    ): Boolean
    fun requestRework(
        workOrderId: UUID,
        expectedVersion: Long,
        submittedVersion: Long,
        at: Instant,
    ): Boolean
    fun beforeAcceptEvidenceGate(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): EvidenceAcceptanceGate
    fun submittedWorkOrders(limit: Int): List<SubmittedReviewWorkRow>
    fun recomputeProgress(project: ProjectSnapshot, at: Instant): ProjectProgressSnapshot
    fun progress(projectId: UUID): ProjectProgressSnapshot?
    fun activeHealthPolicy(
        organizationId: UUID,
        at: Instant,
    ): ProjectHealthPolicyExecutionSnapshot?
    fun deriveHealthSignals(
        project: ProjectSnapshot,
        policy: ProjectHealthPolicyExecutionSnapshot?,
        at: Instant,
    ): List<ProjectHealthSignalSnapshot>
    fun replaceHealthSignals(
        projectId: UUID,
        baselineVersion: Int,
        signals: List<ProjectHealthSignalSnapshot>,
        at: Instant,
    )
    fun storeHealth(
        project: ProjectSnapshot,
        policy: ProjectHealthPolicyExecutionSnapshot?,
        state: ProjectHealthState,
        signals: List<ProjectHealthSignalSnapshot>,
        at: Instant,
    ): ProjectHealthSnapshot
    fun health(projectId: UUID): ProjectHealthSnapshot?
    fun insertHold(
        holdId: UUID,
        project: ProjectSnapshot,
        operationId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    )
    fun openHold(projectId: UUID): ProjectHoldRecordSnapshot?
    fun resolveHold(
        holdId: UUID,
        expectedVersion: Long,
        operationId: UUID,
        resolution: String,
        actorUserId: UUID,
        at: Instant,
    ): Boolean
    fun nextMilestone(project: ProjectSnapshot, today: LocalDate): CommandCenterMilestoneSnapshot?
    fun projectSiteIds(projectId: UUID): List<UUID>
    fun workPackageIds(projectId: UUID, baselineVersion: Int): List<UUID>
}

data class EvidenceAcceptanceGate(
    val satisfied: Boolean,
    val reasonCodes: List<String>,
)

data class SubmittedReviewWorkRow(
    val workOrderId: UUID,
    val workOrderCode: String,
    val projectId: UUID,
    val projectCode: String,
    val projectName: String,
    val siteId: UUID,
    val title: String,
    val submittedAt: Instant?,
    val submittedReviewVersion: Long?,
    val currentVersion: Long,
    val reviewPolicyId: UUID,
)

data class ProjectHoldRecordSnapshot(
    val holdId: UUID,
    val projectId: UUID,
    val state: String,
    val reason: String,
    val putOnHoldAt: Instant,
    val version: Long,
)

@Component
class JdbcReviewProgressHealthPersistence(
    private val jdbc: JdbcTemplate,
) : ReviewProgressHealthPersistencePort {
    override fun reviewPolicy(
        reviewPolicyId: UUID,
    ): ReviewPolicyExecutionSnapshot? {
        val header =
            jdbc.query(
                """
                SELECT
                    cr.id,
                    cr.revision_number,
                    cr.code,
                    cr.name,
                    rp.mode,
                    rp.bind_exact_submitted_version,
                    rp.client_acceptance_separate,
                    rp.allow_delegation
                FROM config_revision cr
                JOIN review_policy rp
                  ON rp.config_revision_id = cr.id
                WHERE cr.id = ?
                  AND cr.family = 'review-policies'
                """.trimIndent(),
                { rs, _ ->
                    ReviewPolicyExecutionSnapshot(
                        configId =
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                        revision =
                            rs.getInt(
                                "revision_number",
                            ),
                        code =
                            rs.getString("code"),
                        name =
                            rs.getString("name"),
                        mode =
                            ReviewMode.valueOf(
                                rs.getString("mode"),
                            ),
                        bindExactSubmittedVersion =
                            rs.getBoolean(
                                "bind_exact_submitted_version",
                            ),
                        clientAcceptanceSeparate =
                            rs.getBoolean(
                                "client_acceptance_separate",
                            ),
                        allowDelegation =
                            rs.getBoolean(
                                "allow_delegation",
                            ),
                        steps = emptyList(),
                    )
                },
                reviewPolicyId,
            ).singleOrNull()
                ?: return null

        val steps =
            jdbc.query(
                """
                SELECT
                    id,
                    step_key,
                    sequence,
                    selector_type,
                    selector_value,
                    quorum_count,
                    reauth_required,
                    reason_required_on_rework,
                    reason_required_on_reject,
                    evidence_visibility_mode,
                    escalation_policy_ref
                FROM review_policy_step
                WHERE config_revision_id = ?
                ORDER BY sequence, step_key, id
                """.trimIndent(),
                { rs, _ ->
                    ReviewStepExecutionSnapshot(
                        stepId =
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                        stepKey =
                            rs.getString("step_key"),
                        sequence =
                            rs.getInt("sequence"),
                        selectorType =
                            ReviewSelectorType.valueOf(
                                rs.getString(
                                    "selector_type",
                                ),
                            ),
                        selectorValue =
                            rs.getString(
                                "selector_value",
                            ),
                        quorumCount =
                            (rs.getObject(
                                "quorum_count",
                            ) as? Number)?.toInt(),
                        reauthRequired =
                            rs.getBoolean(
                                "reauth_required",
                            ),
                        reasonRequiredOnRework =
                            rs.getBoolean(
                                "reason_required_on_rework",
                            ),
                        reasonRequiredOnReject =
                            rs.getBoolean(
                                "reason_required_on_reject",
                            ),
                        evidenceVisibilityMode =
                            rs.getString(
                                "evidence_visibility_mode",
                            ),
                        escalationPolicyRef =
                            rs.getObject(
                                "escalation_policy_ref",
                                UUID::class.java,
                            ),
                    )
                },
                reviewPolicyId,
            )
        return header.copy(steps = steps)
    }

    override fun submittedReviewVersion(
        workOrderId: UUID,
    ): Long? =
        jdbc.queryForObject(
            """
            SELECT submitted_review_version
            FROM work_order
            WHERE id = ?
            """.trimIndent(),
            Long::class.java,
            workOrderId,
        )

    override fun decisions(
        workOrderId: UUID,
        submittedVersion: Long,
    ): List<ReviewDecisionSnapshot> =
        jdbc.query(
            """
            SELECT
                id,
                work_order_id,
                submitted_work_version,
                review_policy_id,
                review_policy_revision,
                review_step_id,
                reviewer_user_id,
                decision,
                reason,
                decided_at,
                delegation_id,
                reviewer_source_type,
                reviewer_source_ref,
                decision_sequence
            FROM work_review_decision
            WHERE work_order_id = ?
              AND submitted_work_version = ?
            ORDER BY decided_at, id
            """.trimIndent(),
            { rs, _ ->
                ReviewDecisionSnapshot(
                    decisionId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    workOrderId =
                        rs.getObject(
                            "work_order_id",
                            UUID::class.java,
                        ),
                    submittedWorkVersion =
                        rs.getLong(
                            "submitted_work_version",
                        ),
                    reviewPolicyId =
                        rs.getObject(
                            "review_policy_id",
                            UUID::class.java,
                        ),
                    reviewPolicyRevision =
                        rs.getInt(
                            "review_policy_revision",
                        ),
                    reviewStepKey =
                        rs.getString(
                            "review_step_id",
                        ),
                    reviewerUserId =
                        rs.getObject(
                            "reviewer_user_id",
                            UUID::class.java,
                        ),
                    decision =
                        WorkReviewDecisionType.valueOf(
                            rs.getString("decision"),
                        ),
                    reason =
                        rs.getString("reason"),
                    decidedAt =
                        rs.getObject(
                            "decided_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    delegationId =
                        rs.getObject(
                            "delegation_id",
                            UUID::class.java,
                        ),
                    reviewerSourceType =
                        rs.getString(
                            "reviewer_source_type",
                        ),
                    reviewerSourceRef =
                        rs.getString(
                            "reviewer_source_ref",
                        ),
                    sequence =
                        (rs.getObject(
                            "decision_sequence",
                        ) as? Number)?.toInt(),
                )
            },
            workOrderId,
            submittedVersion,
        )

    override fun insertDecision(
        decisionId: UUID,
        operationId: UUID,
        workOrder: WorkOrderSnapshot,
        submittedVersion: Long,
        policy: ReviewPolicyExecutionSnapshot,
        step: ReviewStepExecutionSnapshot?,
        reviewerUserId: UUID,
        decision: WorkReviewDecisionType,
        reason: String?,
        reviewerSourceType: String?,
        reviewerSourceRef: String?,
        at: Instant,
        correlationId: String,
    ) {
        jdbc.update(
            """
            INSERT INTO work_review_decision (
                id,
                work_order_id,
                submitted_work_version,
                review_policy_id,
                review_policy_revision,
                review_step_id,
                reviewer_user_id,
                decision,
                reason,
                decided_at,
                delegation_id,
                correlation_id,
                operation_id,
                reviewer_source_type,
                reviewer_source_ref,
                decision_sequence
            )
            VALUES (
                ?, ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                NULL, ?, ?, ?, ?, ?
            )
            """.trimIndent(),
            decisionId,
            workOrder.workOrderId,
            submittedVersion,
            policy.configId,
            policy.revision,
            step?.stepKey,
            reviewerUserId,
            decision.name,
            reason,
            at.atOffset(ZoneOffset.UTC),
            correlationId,
            operationId,
            reviewerSourceType,
            reviewerSourceRef,
            step?.sequence,
        )
    }

    override fun acceptWorkOrder(
        workOrderId: UUID,
        expectedVersion: Long,
        submittedVersion: Long,
        decisionId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_order
            SET lifecycle_state = 'ACCEPTED',
                accepted_at = ?,
                accepted_review_decision_id = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND lifecycle_state = 'SUBMITTED_FOR_REVIEW'
              AND version = ?
              AND submitted_review_version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            decisionId,
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            expectedVersion,
            submittedVersion,
        ) == 1

    override fun requestRework(
        workOrderId: UUID,
        expectedVersion: Long,
        submittedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_order
            SET lifecycle_state = 'REWORK_REQUIRED',
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND lifecycle_state = 'SUBMITTED_FOR_REVIEW'
              AND version = ?
              AND submitted_review_version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            workOrderId,
            expectedVersion,
            submittedVersion,
        ) == 1

    override fun beforeAcceptEvidenceGate(
        workOrderId: UUID,
        evidencePolicyId: UUID,
        evidencePolicyRevision: Int,
    ): EvidenceAcceptanceGate {
        val missing =
            jdbc.query(
                """
                SELECT epr.requirement_key
                FROM evidence_policy_requirement epr
                WHERE epr.config_revision_id = ?
                  AND epr.stage = 'BEFORE_ACCEPT'
                  AND (
                      SELECT COUNT(*)
                      FROM evidence e
                      WHERE e.work_order_id = ?
                        AND e.target_type = 'WORK_ORDER'
                        AND e.target_id = ?
                        AND e.evidence_policy_id = ?
                        AND e.evidence_policy_revision = ?
                        AND e.evidence_requirement_key =
                            epr.requirement_key
                        AND e.storage_state = 'READY'
                        AND NOT EXISTS (
                            SELECT 1
                            FROM evidence newer
                            WHERE newer.supersedes_evidence_id = e.id
                              AND newer.storage_state = 'READY'
                        )
                  ) < epr.min_count
                ORDER BY epr.requirement_key
                """.trimIndent(),
                { rs, _ ->
                    rs.getString(
                        "requirement_key",
                    )
                },
                evidencePolicyId,
                workOrderId,
                workOrderId,
                evidencePolicyId,
                evidencePolicyRevision,
            )
        return EvidenceAcceptanceGate(
            satisfied = missing.isEmpty(),
            reasonCodes =
                missing.map {
                    "BEFORE_ACCEPT_EVIDENCE_MISSING:" + it
                },
        )
    }

    override fun submittedWorkOrders(
        limit: Int,
    ): List<SubmittedReviewWorkRow> =
        jdbc.query(
            """
            SELECT
                wo.id,
                wo.work_order_code,
                wo.project_id,
                p.project_code,
                p.name AS project_name,
                wo.site_id,
                wo.title,
                wo.submitted_at,
                wo.submitted_review_version,
                wo.version,
                wpb.review_policy_id
            FROM work_order wo
            JOIN project p
              ON p.id = wo.project_id
            JOIN work_policy_binding wpb
              ON wpb.id = wo.current_policy_binding_id
             AND wpb.work_order_id = wo.id
            WHERE wo.lifecycle_state =
                'SUBMITTED_FOR_REVIEW'
            ORDER BY wo.submitted_at NULLS LAST,
                     wo.updated_at,
                     wo.id
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                SubmittedReviewWorkRow(
                    workOrderId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    workOrderCode =
                        rs.getString(
                            "work_order_code",
                        ),
                    projectId =
                        rs.getObject(
                            "project_id",
                            UUID::class.java,
                        ),
                    projectCode =
                        rs.getString(
                            "project_code",
                        ),
                    projectName =
                        rs.getString(
                            "project_name",
                        ),
                    siteId =
                        rs.getObject(
                            "site_id",
                            UUID::class.java,
                        ),
                    title =
                        rs.getString("title"),
                    submittedAt =
                        rs.getObject(
                            "submitted_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    submittedReviewVersion =
                        (rs.getObject(
                            "submitted_review_version",
                        ) as? Number)?.toLong(),
                    currentVersion =
                        rs.getLong("version"),
                    reviewPolicyId =
                        rs.getObject(
                            "review_policy_id",
                            UUID::class.java,
                        ),
                )
            },
            limit.coerceIn(1, 500),
        )

    override fun recomputeProgress(
        project: ProjectSnapshot,
        at: Instant,
    ): ProjectProgressSnapshot {
        val row =
            jdbc.queryForMap(
                """
                SELECT
                    COALESCE(
                        SUM(
                            CASE
                                WHEN lifecycle_state = 'ACCEPTED'
                                    THEN progress_weight
                                ELSE 0
                            END
                        ),
                        0
                    ) AS accepted_weight,
                    COALESCE(
                        SUM(progress_weight),
                        0
                    ) AS total_weight,
                    COUNT(*) AS included_count,
                    COUNT(*) FILTER (
                        WHERE lifecycle_state = 'ACCEPTED'
                    ) AS accepted_count
                FROM work_order
                WHERE project_id = ?
                  AND baseline_version = ?
                  AND counts_toward_project_progress = true
                  AND lifecycle_state <> 'CANCELLED'
                """.trimIndent(),
                project.projectId,
                project.baselineVersion,
            )
        val accepted =
            row["accepted_weight"].decimal()
        val total =
            row["total_weight"].decimal()
        val included =
            (row["included_count"] as Number).toInt()
        val acceptedCount =
            (row["accepted_count"] as Number).toInt()
        val percent =
            if (total.compareTo(BigDecimal.ZERO) == 0) {
                null
            } else {
                accepted
                    .multiply(BigDecimal("100"))
                    .divide(
                        total,
                        4,
                        RoundingMode.HALF_UP,
                    )
            }
        jdbc.update(
            """
            INSERT INTO project_progress_projection (
                project_id,
                baseline_version,
                accepted_weight,
                total_weight,
                progress_percent,
                as_of,
                project_version,
                included_work_count,
                accepted_work_count
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id)
            DO UPDATE SET
                baseline_version =
                    EXCLUDED.baseline_version,
                accepted_weight =
                    EXCLUDED.accepted_weight,
                total_weight =
                    EXCLUDED.total_weight,
                progress_percent =
                    EXCLUDED.progress_percent,
                as_of = EXCLUDED.as_of,
                project_version =
                    EXCLUDED.project_version,
                included_work_count =
                    EXCLUDED.included_work_count,
                accepted_work_count =
                    EXCLUDED.accepted_work_count
            """.trimIndent(),
            project.projectId,
            project.baselineVersion,
            accepted,
            total,
            percent,
            at.atOffset(ZoneOffset.UTC),
            project.version,
            included,
            acceptedCount,
        )
        return ProjectProgressSnapshot(
            projectId = project.projectId,
            projectVersion = project.version,
            baselineVersion =
                project.baselineVersion,
            acceptedWeight = accepted,
            totalWeight = total,
            progressPercent = percent,
            includedWorkCount = included,
            acceptedWorkCount = acceptedCount,
            asOf = at,
        )
    }

    override fun progress(
        projectId: UUID,
    ): ProjectProgressSnapshot? =
        jdbc.query(
            """
            SELECT
                project_id,
                project_version,
                baseline_version,
                accepted_weight,
                total_weight,
                progress_percent,
                included_work_count,
                accepted_work_count,
                as_of
            FROM project_progress_projection
            WHERE project_id = ?
            """.trimIndent(),
            { rs, _ ->
                ProjectProgressSnapshot(
                    projectId =
                        rs.getObject(
                            "project_id",
                            UUID::class.java,
                        ),
                    projectVersion =
                        rs.getLong(
                            "project_version",
                        ),
                    baselineVersion =
                        rs.getInt(
                            "baseline_version",
                        ),
                    acceptedWeight =
                        rs.getBigDecimal(
                            "accepted_weight",
                        ),
                    totalWeight =
                        rs.getBigDecimal(
                            "total_weight",
                        ),
                    progressPercent =
                        rs.getBigDecimal(
                            "progress_percent",
                        ),
                    includedWorkCount =
                        rs.getInt(
                            "included_work_count",
                        ),
                    acceptedWorkCount =
                        rs.getInt(
                            "accepted_work_count",
                        ),
                    asOf =
                        rs.getObject(
                            "as_of",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                )
            },
            projectId,
        ).singleOrNull()

    override fun activeHealthPolicy(
        organizationId: UUID,
        at: Instant,
    ): ProjectHealthPolicyExecutionSnapshot? =
        jdbc.query(
            """
            SELECT
                cr.id,
                cr.revision_number,
                php.aggregation_precedence,
                php.signal_rules::text AS signal_rules_json
            FROM config_revision cr
            JOIN project_health_policy php
              ON php.config_revision_id = cr.id
            WHERE cr.family = 'project-health-policies'
              AND cr.lifecycle_state = 'ACTIVE'
              AND (
                  (
                      cr.scope_type = 'ORGANIZATION'
                      AND cr.scope_organization_id = ?
                  )
                  OR cr.scope_type = 'SYSTEM'
              )
              AND (
                  cr.effective_from IS NULL
                  OR cr.effective_from <= ?
              )
              AND (
                  cr.effective_to IS NULL
                  OR cr.effective_to > ?
              )
            ORDER BY
                CASE
                    WHEN cr.scope_type = 'ORGANIZATION'
                        THEN 0
                    ELSE 1
                END,
                cr.revision_number DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                ProjectHealthPolicyExecutionSnapshot(
                    configId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    revision =
                        rs.getInt(
                            "revision_number",
                        ),
                    aggregationPrecedence =
                        rs.getArray(
                            "aggregation_precedence",
                        )
                            ?.array
                            ?.let { it as Array<*> }
                            ?.map { it.toString() }
                            ?: emptyList(),
                    signalRulesJson =
                        rs.getString(
                            "signal_rules_json",
                        ),
                )
            },
            organizationId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()

    override fun deriveHealthSignals(
        project: ProjectSnapshot,
        policy: ProjectHealthPolicyExecutionSnapshot?,
        at: Instant,
    ): List<ProjectHealthSignalSnapshot> {
        val severityByCode =
            HealthSignalRules.parse(
                policy?.signalRulesJson,
            )

        val signals =
            mutableListOf<ProjectHealthSignalSnapshot>()

        jdbc.queryForList(
            """
            SELECT
                id,
                lifecycle_state,
                readiness_state,
                planned_end,
                version
            FROM work_order
            WHERE project_id = ?
              AND baseline_version = ?
              AND lifecycle_state <> 'CANCELLED'
            """.trimIndent(),
            project.projectId,
            project.baselineVersion,
        ).forEach { row ->
            val id = row["id"] as UUID
            val lifecycle =
                row["lifecycle_state"].toString()
            val readiness =
                row["readiness_state"].toString()
            val plannedEnd =
                (row["planned_end"] as? OffsetDateTime)
                    ?.toInstant()
            val version =
                (row["version"] as Number).toLong()

            if (
                plannedEnd != null &&
                plannedEnd < at &&
                lifecycle !in
                    setOf(
                        "ACCEPTED",
                        "CLOSED",
                    )
            ) {
                signals +=
                    signal(
                        project,
                        ProjectHealthSignalCode
                            .OVERDUE_WORK,
                        severityByCode,
                        "WORK_ORDER",
                        id,
                        "WorkOrder is past planned end.",
                        version,
                        at,
                    )
            }
            if (lifecycle == "BLOCKED") {
                signals +=
                    signal(
                        project,
                        ProjectHealthSignalCode
                            .BLOCKED_WORK,
                        severityByCode,
                        "WORK_ORDER",
                        id,
                        "WorkOrder is blocked.",
                        version,
                        at,
                    )
            }
            if (lifecycle == "REWORK_REQUIRED") {
                signals +=
                    signal(
                        project,
                        ProjectHealthSignalCode
                            .REWORK_BACKLOG,
                        severityByCode,
                        "WORK_ORDER",
                        id,
                        "WorkOrder requires rework.",
                        version,
                        at,
                    )
            }
            if (readiness == "BLOCKED") {
                signals +=
                    signal(
                        project,
                        ProjectHealthSignalCode
                            .READINESS_FAILURE,
                        severityByCode,
                        "WORK_ORDER",
                        id,
                        "WorkOrder readiness is blocked.",
                        version,
                        at,
                    )
            }
        }

        jdbc.queryForList(
            """
            SELECT id, planned_date, version
            FROM milestone
            WHERE project_id = ?
              AND baseline_version = ?
              AND state = 'PLANNED'
              AND planned_date IS NOT NULL
              AND planned_date < ?
            """.trimIndent(),
            project.projectId,
            project.baselineVersion,
            at.atZone(ZoneOffset.UTC).toLocalDate(),
        ).forEach { row ->
            signals +=
                signal(
                    project,
                    ProjectHealthSignalCode
                        .MILESTONE_DELAY,
                    severityByCode,
                    "MILESTONE",
                    row["id"] as UUID,
                    "Milestone planned date has passed.",
                    (row["version"] as Number).toLong(),
                    at,
                )
        }

        jdbc.queryForList(
            """
            SELECT wb.id, wb.version
            FROM work_blocker wb
            JOIN work_order wo
              ON wo.id = wb.work_order_id
            WHERE wo.project_id = ?
              AND wo.baseline_version = ?
              AND wb.state = 'OPEN'
              AND wb.client_visible = true
            """.trimIndent(),
            project.projectId,
            project.baselineVersion,
        ).forEach { row ->
            signals +=
                signal(
                    project,
                    ProjectHealthSignalCode
                        .CLIENT_ACTION_REQUIRED,
                    severityByCode,
                    "WORK_BLOCKER",
                    row["id"] as UUID,
                    "Open client-visible blocker requires action.",
                    (row["version"] as Number).toLong(),
                    at,
                )
        }

        return signals
    }

    override fun replaceHealthSignals(
        projectId: UUID,
        baselineVersion: Int,
        signals: List<ProjectHealthSignalSnapshot>,
        at: Instant,
    ) {
        jdbc.update(
            """
            UPDATE project_health_signal
            SET current = false,
                last_observed_at = ?
            WHERE project_id = ?
              AND current = true
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            projectId,
        )

        signals.forEach { signal ->
            val existing =
                jdbc.query(
                    """
                    SELECT id, first_observed_at
                    FROM project_health_signal
                    WHERE project_id = ?
                      AND signal_code = ?
                      AND source_type = ?
                      AND source_id = ?
                    ORDER BY last_observed_at DESC
                    LIMIT 1
                    """.trimIndent(),
                    { rs, _ ->
                        Pair(
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                            rs.getObject(
                                "first_observed_at",
                                OffsetDateTime::class.java,
                            ).toInstant(),
                        )
                    },
                    projectId,
                    signal.signalCode.name,
                    signal.sourceType,
                    signal.sourceId,
                ).singleOrNull()

            if (existing == null) {
                jdbc.update(
                    """
                    INSERT INTO project_health_signal (
                        id,
                        project_id,
                        baseline_version,
                        signal_code,
                        severity,
                        source_type,
                        source_id,
                        first_observed_at,
                        last_observed_at,
                        summary,
                        current,
                        source_version
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, true, ?
                    )
                    """.trimIndent(),
                    signal.signalId,
                    projectId,
                    baselineVersion,
                    signal.signalCode.name,
                    signal.severity.name,
                    signal.sourceType,
                    signal.sourceId,
                    at.atOffset(ZoneOffset.UTC),
                    at.atOffset(ZoneOffset.UTC),
                    signal.summary,
                    signal.sourceVersion,
                )
            } else {
                jdbc.update(
                    """
                    UPDATE project_health_signal
                    SET baseline_version = ?,
                        severity = ?,
                        last_observed_at = ?,
                        summary = ?,
                        current = true,
                        source_version = ?
                    WHERE id = ?
                    """.trimIndent(),
                    baselineVersion,
                    signal.severity.name,
                    at.atOffset(ZoneOffset.UTC),
                    signal.summary,
                    signal.sourceVersion,
                    existing.first,
                )
            }
        }
    }

    override fun storeHealth(
        project: ProjectSnapshot,
        policy: ProjectHealthPolicyExecutionSnapshot?,
        state: ProjectHealthState,
        signals: List<ProjectHealthSignalSnapshot>,
        at: Instant,
    ): ProjectHealthSnapshot {
        val summary =
            signals.joinToString(
                prefix = "[",
                separator = ",",
                postfix = "]",
            ) {
                "{\"code\":\"" + it.signalCode.name +
                    "\",\"severity\":\"" + it.severity.name +
                    "\",\"sourceType\":\"" + it.sourceType +
                    "\",\"sourceId\":\"" + it.sourceId + "\"}"
            }
        jdbc.update(
            """
            INSERT INTO project_health_projection (
                project_id,
                health_state,
                signal_summary_json,
                baseline_version,
                as_of,
                project_version,
                health_policy_id,
                health_policy_revision
            )
            VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?)
            ON CONFLICT (project_id)
            DO UPDATE SET
                health_state = EXCLUDED.health_state,
                signal_summary_json =
                    EXCLUDED.signal_summary_json,
                baseline_version =
                    EXCLUDED.baseline_version,
                as_of = EXCLUDED.as_of,
                project_version =
                    EXCLUDED.project_version,
                health_policy_id =
                    EXCLUDED.health_policy_id,
                health_policy_revision =
                    EXCLUDED.health_policy_revision
            """.trimIndent(),
            project.projectId,
            state.name,
            summary,
            project.baselineVersion,
            at.atOffset(ZoneOffset.UTC),
            project.version,
            policy?.configId,
            policy?.revision,
        )
        return ProjectHealthSnapshot(
            projectId = project.projectId,
            projectVersion = project.version,
            baselineVersion =
                project.baselineVersion,
            state = state,
            healthPolicyId =
                policy?.configId,
            healthPolicyRevision =
                policy?.revision,
            signals = signals,
            reasonCodes =
                signals.map {
                    it.signalCode.name
                }.distinct(),
            asOf = at,
        )
    }

    override fun health(
        projectId: UUID,
    ): ProjectHealthSnapshot? {
        val header =
            jdbc.query(
                """
                SELECT
                    project_id,
                    project_version,
                    baseline_version,
                    health_state,
                    health_policy_id,
                    health_policy_revision,
                    as_of
                FROM project_health_projection
                WHERE project_id = ?
                """.trimIndent(),
                { rs, _ ->
                    ProjectHealthSnapshot(
                        projectId =
                            rs.getObject(
                                "project_id",
                                UUID::class.java,
                            ),
                        projectVersion =
                            rs.getLong(
                                "project_version",
                            ),
                        baselineVersion =
                            rs.getInt(
                                "baseline_version",
                            ),
                        state =
                            ProjectHealthState.valueOf(
                                rs.getString(
                                    "health_state",
                                ),
                            ),
                        healthPolicyId =
                            rs.getObject(
                                "health_policy_id",
                                UUID::class.java,
                            ),
                        healthPolicyRevision =
                            (rs.getObject(
                                "health_policy_revision",
                            ) as? Number)?.toInt(),
                        signals = emptyList(),
                        reasonCodes =
                            emptyList(),
                        asOf =
                            rs.getObject(
                                "as_of",
                                OffsetDateTime::class.java,
                            ).toInstant(),
                    )
                },
                projectId,
            ).singleOrNull()
                ?: return null

        val signals =
            jdbc.query(
                """
                SELECT
                    id,
                    project_id,
                    baseline_version,
                    signal_code,
                    severity,
                    source_type,
                    source_id,
                    first_observed_at,
                    last_observed_at,
                    summary,
                    current,
                    source_version
                FROM project_health_signal
                WHERE project_id = ?
                  AND current = true
                ORDER BY severity DESC,
                         signal_code,
                         source_type,
                         source_id
                """.trimIndent(),
                { rs, _ ->
                    ProjectHealthSignalSnapshot(
                        signalId =
                            rs.getObject(
                                "id",
                                UUID::class.java,
                            ),
                        projectId =
                            rs.getObject(
                                "project_id",
                                UUID::class.java,
                            ),
                        baselineVersion =
                            rs.getInt(
                                "baseline_version",
                            ),
                        signalCode =
                            ProjectHealthSignalCode.valueOf(
                                rs.getString(
                                    "signal_code",
                                ),
                            ),
                        severity =
                            ProjectHealthSeverity.valueOf(
                                rs.getString(
                                    "severity",
                                ),
                            ),
                        sourceType =
                            rs.getString(
                                "source_type",
                            ),
                        sourceId =
                            rs.getObject(
                                "source_id",
                                UUID::class.java,
                            ),
                        firstObservedAt =
                            rs.getObject(
                                "first_observed_at",
                                OffsetDateTime::class.java,
                            ).toInstant(),
                        lastObservedAt =
                            rs.getObject(
                                "last_observed_at",
                                OffsetDateTime::class.java,
                            ).toInstant(),
                        summary =
                            rs.getString("summary"),
                        current =
                            rs.getBoolean("current"),
                        sourceVersion =
                            (rs.getObject(
                                "source_version",
                            ) as? Number)?.toLong(),
                    )
                },
                projectId,
            )
        return header.copy(
            signals = signals,
            reasonCodes =
                signals.map {
                    it.signalCode.name
                }.distinct(),
        )
    }

    override fun insertHold(
        holdId: UUID,
        project: ProjectSnapshot,
        operationId: UUID,
        reason: String,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO project_hold_record (
                id,
                project_id,
                organization_id,
                put_on_hold_operation_id,
                put_on_hold_project_version,
                reason,
                state,
                put_on_hold_at,
                put_on_hold_by,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, 'OPEN', ?, ?, 1
            )
            """.trimIndent(),
            holdId,
            project.projectId,
            project.organizationId,
            operationId,
            project.version,
            reason,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
        )
    }

    override fun openHold(
        projectId: UUID,
    ): ProjectHoldRecordSnapshot? =
        jdbc.query(
            """
            SELECT
                id,
                project_id,
                state,
                reason,
                put_on_hold_at,
                version
            FROM project_hold_record
            WHERE project_id = ?
              AND state = 'OPEN'
            """.trimIndent(),
            { rs, _ ->
                ProjectHoldRecordSnapshot(
                    holdId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    projectId =
                        rs.getObject(
                            "project_id",
                            UUID::class.java,
                        ),
                    state =
                        rs.getString("state"),
                    reason =
                        rs.getString("reason"),
                    putOnHoldAt =
                        rs.getObject(
                            "put_on_hold_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            projectId,
        ).singleOrNull()

    override fun resolveHold(
        holdId: UUID,
        expectedVersion: Long,
        operationId: UUID,
        resolution: String,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE project_hold_record
            SET state = 'RESOLVED',
                resolution = ?,
                resume_operation_id = ?,
                resolved_at = ?,
                resolved_by = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'OPEN'
              AND version = ?
            """.trimIndent(),
            resolution,
            operationId,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            holdId,
            expectedVersion,
        ) == 1

    override fun nextMilestone(
        project: ProjectSnapshot,
        today: LocalDate,
    ): CommandCenterMilestoneSnapshot? =
        jdbc.query(
            """
            SELECT
                id,
                code,
                name,
                planned_date,
                state
            FROM milestone
            WHERE project_id = ?
              AND baseline_version = ?
              AND state = 'PLANNED'
            ORDER BY
                CASE
                    WHEN planned_date IS NULL
                        THEN 1
                    ELSE 0
                END,
                planned_date,
                sequence NULLS LAST,
                id
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                CommandCenterMilestoneSnapshot(
                    milestoneId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    code =
                        rs.getString("code"),
                    name =
                        rs.getString("name"),
                    plannedDate =
                        rs.getObject(
                            "planned_date",
                            LocalDate::class.java,
                        ),
                    state =
                        rs.getString("state"),
                )
            },
            project.projectId,
            project.baselineVersion,
        ).singleOrNull()

    override fun projectSiteIds(
        projectId: UUID,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT id
            FROM project_site
            WHERE project_id = ?
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            projectId,
        )

    override fun workPackageIds(
        projectId: UUID,
        baselineVersion: Int,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT id
            FROM work_package
            WHERE project_id = ?
              AND baseline_version = ?
              AND state <> 'CANCELLED'
            ORDER BY id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            projectId,
            baselineVersion,
        )

    private fun signal(
        project: ProjectSnapshot,
        code: ProjectHealthSignalCode,
        severityByCode:
            Map<ProjectHealthSignalCode, ProjectHealthSeverity>,
        sourceType: String,
        sourceId: UUID,
        summary: String,
        sourceVersion: Long?,
        at: Instant,
    ) =
        ProjectHealthSignalSnapshot(
            signalId =
                UUID.nameUUIDFromBytes(
                    (
                        "hiltech-health:" +
                            project.projectId + ":" +
                            code.name + ":" +
                            sourceType + ":" +
                            sourceId
                    ).toByteArray(),
                ),
            projectId = project.projectId,
            baselineVersion =
                project.baselineVersion,
            signalCode = code,
            severity =
                severityByCode[code]
                    ?: ProjectHealthSeverity.ATTENTION,
            sourceType = sourceType,
            sourceId = sourceId,
            firstObservedAt = at,
            lastObservedAt = at,
            summary = summary,
            current = true,
            sourceVersion = sourceVersion,
        )

    private fun Any?.decimal(): BigDecimal =
        when (this) {
            null -> BigDecimal.ZERO
            is BigDecimal -> this
            is Number ->
                BigDecimal(toString())
            else ->
                BigDecimal(toString())
        }
}

private object HealthSignalRules {
    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun parse(
        raw: String?,
    ): Map<ProjectHealthSignalCode, ProjectHealthSeverity> {
        if (raw.isNullOrBlank()) {
            return emptyMap()
        }
        val root =
            runCatching {
                json.parseToJsonElement(raw)
                    .jsonObject
            }.getOrElse {
                return emptyMap()
            }

        return ProjectHealthSignalCode.entries
            .mapNotNull { code ->
                val rule =
                    root[code.name]
                        ?: return@mapNotNull null
                val severityRaw =
                    runCatching {
                        rule.jsonObject["severity"]
                            ?.jsonPrimitive
                            ?.content
                    }.getOrNull()
                        ?: runCatching {
                            rule.jsonPrimitive.content
                        }.getOrNull()
                        ?: return@mapNotNull null
                val severity =
                    runCatching {
                        ProjectHealthSeverity.valueOf(
                            severityRaw.uppercase(),
                        )
                    }.getOrNull()
                        ?: return@mapNotNull null
                code to severity
            }.toMap()
    }
}

