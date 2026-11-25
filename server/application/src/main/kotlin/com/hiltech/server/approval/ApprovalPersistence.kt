package com.hiltech.server.approval

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import org.springframework.http.HttpStatus
import com.hiltech.server.platform.ProductApiException
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class ApprovalPolicySnapshot(
    val organizationId: UUID,
    val policyKey: String,
    val versionNumber: Int,
    val approvalRequired: Boolean,
    val mode: ApprovalPolicyMode,
    val authorityKey: String?,
    val reasonRequired: Boolean,
)

data class ApprovalAuthorityBindingSnapshot(
    val id: UUID,
    val organizationId: UUID,
    val authorityKey: String,
    val principalType: ApprovalPrincipalType,
    val principalId: UUID,
    val effectiveFrom: Instant,
    val effectiveTo: Instant?,
    val version: Long,
)

data class ApprovalRequestCreation(
    val requestId: UUID,
    val stepId: UUID,
    val assignmentId: UUID,
    val subject: ApprovalSubjectRef,
    val policy: ApprovalPolicySnapshot,
    val requesterUserId: UUID,
    val authority: ApprovalAuthorityBindingSnapshot,
    val reasonCode: String,
    val safeReasonSummary: String?,
    val correlationId: String,
    val createdAt: Instant,
)

data class ApprovalSubjectApprovalRecord(
    val requestId: UUID,
    val state: ApprovalRequestState,
    val subjectVersion: Long,
    val policyKey: String,
)

data class ApprovalReadRecord(
    val requestId: UUID,
    val organizationId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val requesterUserId: UUID,
    val state: ApprovalRequestState,
    val reasonCode: String,
    val safeReasonSummary: String?,
    val createdAt: Instant,
    val authorityKey: String,
    val assignmentPrincipalType: ApprovalPrincipalType,
    val assignmentPrincipalId: UUID,
)

data class ApprovalAttentionSnapshot(
    val requestId: UUID,
    val organizationId: UUID,
    val requestVersion: Long,
    val requestState: ApprovalRequestState,
    val reasonCode: String,
    val safeReasonSummary: String?,
    val createdAt: Instant,
    val completedAt: Instant?,
    val authorityKey: String,
    val assignmentPrincipalType: ApprovalPrincipalType,
    val assignmentPrincipalId: UUID,
    val assignmentState: String,
)

data class ApprovalDecisionContext(
    val requestId: UUID,
    val organizationId: UUID,
    val subjectType: String,
    val subjectId: UUID,
    val subjectVersion: Long,
    val policyKey: String,
    val policyVersion: Int,
    val requesterUserId: UUID,
    val requestState: ApprovalRequestState,
    val requestVersion: Long,
    val stepId: UUID,
    val authorityKey: String,
    val assignmentId: UUID,
    val assignmentPrincipalType: ApprovalPrincipalType,
    val assignmentPrincipalId: UUID,
    val assignmentState: String,
)

interface ApprovalPersistencePort {
    fun isActiveOrganizationMember(
        organizationId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun loadActivePolicy(
        organizationId: UUID,
        policyKey: String,
        at: Instant,
    ): ApprovalPolicySnapshot?

    fun resolveCurrentAuthority(
        organizationId: UUID,
        authorityKey: String,
        at: Instant,
    ): ApprovalAuthorityBindingSnapshot?

    fun resolveCurrentAuthorityForSubject(
        subject: ApprovalSubjectRef,
        authorityKey: String,
        at: Instant,
    ): ApprovalAuthorityBindingSnapshot? =
        resolveCurrentAuthority(subject.organizationId, authorityKey, at)

    fun loadSubjectApproval(subject: ApprovalSubjectRef, policyKey: String): ApprovalSubjectApprovalRecord? = null

    fun actorMatchesAuthority(
        actorUserId: UUID,
        authority: ApprovalAuthorityBindingSnapshot,
        at: Instant,
    ): Boolean

    fun insertExceptionRequest(
        creation: ApprovalRequestCreation,
    )

    fun loadPendingAssignedRequest(
        approvalRequestId: UUID,
    ): ApprovalReadRecord?

    fun loadAttentionSnapshot(
        approvalRequestId: UUID,
    ): ApprovalAttentionSnapshot?

    fun findPendingAssignedRequests(
        actorUserId: UUID,
        authorityAt: Instant,
        asOf: Instant,
        afterCreatedAt: Instant?,
        afterRequestId: UUID?,
        limit: Int,
    ): List<ApprovalReadRecord>

    fun loadDecisionContextForUpdate(
        approvalRequestId: UUID,
    ): ApprovalDecisionContext?

    fun actorMatchesAssignment(
        actorUserId: UUID,
        context: ApprovalDecisionContext,
        at: Instant,
    ): Boolean

    fun applyDecision(
        context: ApprovalDecisionContext,
        decisionId: UUID,
        actorUserId: UUID,
        decision: ApprovalDecisionType,
        decidedAt: Instant,
        comment: String?,
        operationId: UUID,
        correlationId: String,
    )

    fun supersede(
        context: ApprovalDecisionContext,
        at: Instant,
    )
}

@Component
class JdbcApprovalPersistence @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val subjectAuthoritySources: List<ApprovalSubjectAuthoritySource>,
) : ApprovalPersistencePort {
    constructor(jdbc: JdbcTemplate) : this(jdbc, emptyList())
    override fun isActiveOrganizationMember(
        organizationId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM organization_membership m
                JOIN user_identity u
                  ON u.id = m.user_identity_id
                WHERE m.organization_id = ?
                  AND m.user_identity_id = ?
                  AND m.state = 'ACTIVE'
                  AND u.status = 'ACTIVE'
                  AND m.valid_from <= ?
                  AND (
                      m.valid_until IS NULL
                      OR m.valid_until > ?
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) ?: false

    override fun loadActivePolicy(
        organizationId: UUID,
        policyKey: String,
        at: Instant,
    ): ApprovalPolicySnapshot? =
        jdbc.query(
            """
            SELECT
                organization_id,
                policy_key,
                version_number,
                approval_required,
                step_mode,
                authority_key,
                reason_required
            FROM approval_policy_version
            WHERE organization_id = ?
              AND policy_key = ?
              AND lifecycle_state = 'ACTIVE'
              AND effective_from <= ?
              AND (
                  effective_to IS NULL
                  OR effective_to > ?
              )
            """.trimIndent(),
            { rs, _ ->
                ApprovalPolicySnapshot(
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    policyKey =
                        rs.getString("policy_key"),
                    versionNumber =
                        rs.getInt("version_number"),
                    approvalRequired =
                        rs.getBoolean(
                            "approval_required",
                        ),
                    mode =
                        ApprovalPolicyMode.valueOf(
                            rs.getString(
                                "step_mode",
                            ),
                        ),
                    authorityKey =
                        rs.getString(
                            "authority_key",
                        ),
                    reasonRequired =
                        rs.getBoolean(
                            "reason_required",
                        ),
                )
            },
            organizationId,
            policyKey,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()

    override fun resolveCurrentAuthority(
        organizationId: UUID,
        authorityKey: String,
        at: Instant,
    ): ApprovalAuthorityBindingSnapshot? =
        jdbc.query(
            """
            SELECT
                b.id,
                b.organization_id,
                b.authority_key,
                b.principal_type,
                b.principal_user_id,
                b.principal_team_id,
                b.effective_from,
                b.effective_to,
                b.version
            FROM approval_authority_binding b
            WHERE b.organization_id = ?
              AND b.authority_key = ?
              AND b.active = true
              AND b.effective_from <= ?
              AND (
                  b.effective_to IS NULL
                  OR b.effective_to > ?
              )
            """.trimIndent(),
            { rs, _ ->
                val type =
                    ApprovalPrincipalType.valueOf(
                        rs.getString(
                            "principal_type",
                        ),
                    )
                ApprovalAuthorityBindingSnapshot(
                    id =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    authorityKey =
                        rs.getString(
                            "authority_key",
                        ),
                    principalType = type,
                    principalId =
                        rs.getObject(
                            if (
                                type ==
                                ApprovalPrincipalType.USER
                            ) {
                                "principal_user_id"
                            } else {
                                "principal_team_id"
                            },
                            UUID::class.java,
                        ),
                    effectiveFrom =
                        rs.getObject(
                            "effective_from",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    effectiveTo =
                        rs.getObject(
                            "effective_to",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            organizationId,
            authorityKey,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()
            ?.takeIf { authority ->
                authorityPrincipalBelongsToOrganization(
                    authority = authority,
                    at = at,
                )
            }

    override fun resolveCurrentAuthorityForSubject(
        subject: ApprovalSubjectRef,
        authorityKey: String,
        at: Instant,
    ): ApprovalAuthorityBindingSnapshot? {
        val matching = subjectAuthoritySources.filter { it.supports(subject.subjectType) }
        if (matching.size > 1) return null
        if (matching.size == 1) return matching.single().resolve(subject, authorityKey, at)
        return resolveCurrentAuthority(subject.organizationId, authorityKey, at)
    }

    override fun loadSubjectApproval(
        subject: ApprovalSubjectRef,
        policyKey: String,
    ): ApprovalSubjectApprovalRecord? =
        jdbc.query(
            """SELECT id, state, subject_version, policy_key
               FROM approval_request
               WHERE organization_id = ? AND subject_type = ? AND subject_id = ?
                 AND subject_version = ? AND policy_key = ?""".trimIndent(),
            { rs, _ ->
                ApprovalSubjectApprovalRecord(
                    rs.getObject("id", UUID::class.java),
                    ApprovalRequestState.valueOf(rs.getString("state")),
                    rs.getLong("subject_version"),
                    rs.getString("policy_key"),
                )
            },
            subject.organizationId, subject.subjectType, subject.subjectId, subject.subjectVersion, policyKey,
        ).singleOrNull()

    override fun actorMatchesAuthority(
        actorUserId: UUID,
        authority: ApprovalAuthorityBindingSnapshot,
        at: Instant,
    ): Boolean =
        when (authority.principalType) {
            ApprovalPrincipalType.USER ->
                actorUserId ==
                    authority.principalId &&
                    isActiveOrganizationMember(
                        organizationId =
                            authority.organizationId,
                        actorUserId =
                            actorUserId,
                        at = at,
                    )

            ApprovalPrincipalType.TEAM ->
                isCurrentTeamMember(
                    organizationId =
                        authority.organizationId,
                    teamId =
                        authority.principalId,
                    actorUserId =
                        actorUserId,
                    at = at,
                )
        }

    override fun insertExceptionRequest(
        creation: ApprovalRequestCreation,
    ) {
        val at =
            creation.createdAt
                .atOffset(ZoneOffset.UTC)

        // A setup change locks the same policy row before closing pending requests.
        // Do not allow a request whose policy was read before that change to arrive
        // afterwards under the retired revision.
        val currentPolicy = jdbc.query(
            """SELECT id FROM approval_policy_version
               WHERE organization_id=? AND policy_key=? AND version_number=?
                 AND lifecycle_state='ACTIVE' AND approval_required=true AND step_mode='SINGLE'
                 AND authority_key=? AND reason_required=?
                 AND effective_from<=? AND (effective_to IS NULL OR effective_to>?)
               FOR SHARE""".trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) }, creation.subject.organizationId,
            creation.policy.policyKey, creation.policy.versionNumber, creation.policy.authorityKey,
            creation.policy.reasonRequired, at, at,
        ).singleOrNull()
        if (currentPolicy == null) throw ProductApiException(
            "APPROVAL_POLICY_CHANGED", "The review policy changed; refresh before requesting review.", HttpStatus.CONFLICT,
        )

        jdbc.update(
            """
            INSERT INTO approval_request (
                id,
                organization_id,
                subject_type,
                subject_id,
                subject_version,
                policy_key,
                policy_version,
                requester_user_id,
                state,
                reason_code,
                safe_reason_summary,
                created_at,
                completed_at,
                correlation_id,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                'PENDING',
                ?, ?,
                ?, NULL, ?, 1
            )
            """.trimIndent(),
            creation.requestId,
            creation.subject.organizationId,
            creation.subject.subjectType,
            creation.subject.subjectId,
            creation.subject.subjectVersion,
            creation.policy.policyKey,
            creation.policy.versionNumber,
            creation.requesterUserId,
            creation.reasonCode,
            creation.safeReasonSummary,
            at,
            creation.correlationId.take(128),
        )

        jdbc.update(
            """
            INSERT INTO approval_step (
                id,
                approval_request_id,
                sequence_number,
                mode,
                authority_key,
                state,
                required_count
            )
            VALUES (
                ?, ?, 1,
                'SINGLE', ?,
                'PENDING', 1
            )
            """.trimIndent(),
            creation.stepId,
            creation.requestId,
            creation.authority.authorityKey,
        )

        jdbc.update(
            """
            INSERT INTO approval_assignment (
                id,
                approval_request_id,
                approval_step_id,
                principal_type,
                principal_user_id,
                principal_team_id,
                state,
                assigned_at,
                acted_at
            )
            VALUES (
                ?, ?, ?, ?,
                ?, ?,
                'ASSIGNED',
                ?, NULL
            )
            """.trimIndent(),
            creation.assignmentId,
            creation.requestId,
            creation.stepId,
            creation.authority.principalType.name,
            creation.authority.principalId
                .takeIf {
                    creation.authority.principalType ==
                        ApprovalPrincipalType.USER
                },
            creation.authority.principalId
                .takeIf {
                    creation.authority.principalType ==
                        ApprovalPrincipalType.TEAM
                },
            at,
        )
    }

    override fun loadAttentionSnapshot(
        approvalRequestId: UUID,
    ): ApprovalAttentionSnapshot? =
        jdbc.query(
            """
            SELECT
                r.id AS request_id,
                r.organization_id,
                r.version AS request_version,
                r.state AS request_state,
                r.reason_code,
                r.safe_reason_summary,
                r.created_at,
                r.completed_at,
                s.authority_key,
                a.principal_type,
                a.principal_user_id,
                a.principal_team_id,
                a.state AS assignment_state
            FROM approval_request r
            JOIN approval_step s
              ON s.approval_request_id = r.id
             AND s.sequence_number = 1
            JOIN approval_assignment a
              ON a.approval_request_id = r.id
             AND a.approval_step_id = s.id
            WHERE r.id = ?
            """.trimIndent(),
            { rs, _ ->
                val principalType =
                    ApprovalPrincipalType.valueOf(
                        rs.getString(
                            "principal_type",
                        ),
                    )
                ApprovalAttentionSnapshot(
                    requestId =
                        rs.getObject(
                            "request_id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    requestVersion =
                        rs.getLong(
                            "request_version",
                        ),
                    requestState =
                        ApprovalRequestState.valueOf(
                            rs.getString(
                                "request_state",
                            ),
                        ),
                    reasonCode =
                        rs.getString(
                            "reason_code",
                        ),
                    safeReasonSummary =
                        rs.getString(
                            "safe_reason_summary",
                        ),
                    createdAt =
                        rs.getObject(
                            "created_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    completedAt =
                        rs.getObject(
                            "completed_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    authorityKey =
                        rs.getString(
                            "authority_key",
                        ),
                    assignmentPrincipalType =
                        principalType,
                    assignmentPrincipalId =
                        rs.getObject(
                            if (
                                principalType ==
                                ApprovalPrincipalType.USER
                            ) {
                                "principal_user_id"
                            } else {
                                "principal_team_id"
                            },
                            UUID::class.java,
                        ),
                    assignmentState =
                        rs.getString(
                            "assignment_state",
                        ),
                )
            },
            approvalRequestId,
        ).singleOrNull()

    override fun loadPendingAssignedRequest(
        approvalRequestId: UUID,
    ): ApprovalReadRecord? =
        jdbc.query(
            """
            SELECT
                r.id AS request_id,
                r.organization_id,
                r.subject_type,
                r.subject_id,
                r.subject_version,
                r.policy_key,
                r.policy_version,
                r.requester_user_id,
                r.state AS request_state,
                r.reason_code,
                r.safe_reason_summary,
                r.created_at,
                s.authority_key,
                a.principal_type,
                a.principal_user_id,
                a.principal_team_id
            FROM approval_request r
            JOIN approval_step s
              ON s.approval_request_id = r.id
             AND s.sequence_number = 1
             AND s.state = 'PENDING'
            JOIN approval_assignment a
              ON a.approval_request_id = r.id
             AND a.approval_step_id = s.id
             AND a.state = 'ASSIGNED'
            WHERE r.id = ?
              AND r.state = 'PENDING'
            """.trimIndent(),
            approvalReadMapper,
            approvalRequestId,
        ).singleOrNull()

    override fun findPendingAssignedRequests(
        actorUserId: UUID,
        authorityAt: Instant,
        asOf: Instant,
        afterCreatedAt: Instant?,
        afterRequestId: UUID?,
        limit: Int,
    ): List<ApprovalReadRecord> {
        require(limit in 1..101)
        require(
            (afterCreatedAt == null) ==
                (afterRequestId == null),
        )

        val baseSql =
            """
            SELECT
                r.id AS request_id,
                r.organization_id,
                r.subject_type,
                r.subject_id,
                r.subject_version,
                r.policy_key,
                r.policy_version,
                r.requester_user_id,
                r.state AS request_state,
                r.reason_code,
                r.safe_reason_summary,
                r.created_at,
                s.authority_key,
                a.principal_type,
                a.principal_user_id,
                a.principal_team_id
            FROM approval_request r
            JOIN approval_step s
              ON s.approval_request_id = r.id
             AND s.sequence_number = 1
             AND s.state = 'PENDING'
            JOIN approval_assignment a
              ON a.approval_request_id = r.id
             AND a.approval_step_id = s.id
             AND a.state = 'ASSIGNED'
            JOIN approval_authority_binding b
              ON b.organization_id = r.organization_id
             AND b.authority_key = s.authority_key
             AND b.active = true
             AND b.effective_from <= ?
             AND (
                 b.effective_to IS NULL
                 OR b.effective_to > ?
             )
             AND b.principal_type = a.principal_type
             AND (
                 (
                     b.principal_type = 'USER'
                     AND b.principal_user_id = a.principal_user_id
                 )
                 OR
                 (
                     b.principal_type = 'TEAM'
                     AND b.principal_team_id = a.principal_team_id
                 )
             )
            WHERE r.state = 'PENDING'
              AND r.created_at <= ?
              AND EXISTS (
                  SELECT 1
                  FROM organization_membership om
                  JOIN user_identity ui
                    ON ui.id = om.user_identity_id
                  WHERE om.organization_id = r.organization_id
                    AND om.user_identity_id = ?
                    AND om.state = 'ACTIVE'
                    AND ui.status = 'ACTIVE'
                    AND om.valid_from <= ?
                    AND (
                        om.valid_until IS NULL
                        OR om.valid_until > ?
                    )
              )
              AND (
                  (
                      a.principal_type = 'USER'
                      AND a.principal_user_id = ?
                  )
                  OR
                  (
                      a.principal_type = 'TEAM'
                      AND EXISTS (
                          SELECT 1
                          FROM team t
                          JOIN team_membership tm
                            ON tm.team_id = t.id
                          WHERE t.id = a.principal_team_id
                            AND t.organization_id = r.organization_id
                            AND t.active = true
                            AND tm.user_identity_id = ?
                            AND tm.valid_from <= ?
                            AND (
                                tm.valid_until IS NULL
                                OR tm.valid_until > ?
                            )
                      )
                  )
              )
            """.trimIndent()

        val authorityOffset =
            authorityAt.atOffset(
                ZoneOffset.UTC,
            )
        val asOfOffset =
            asOf.atOffset(
                ZoneOffset.UTC,
            )

        val args =
            mutableListOf<Any>(
                authorityOffset,
                authorityOffset,
                asOfOffset,
                actorUserId,
                authorityOffset,
                authorityOffset,
                actorUserId,
                actorUserId,
                authorityOffset,
                authorityOffset,
            )

        val pagePredicate =
            if (
                afterCreatedAt != null &&
                afterRequestId != null
            ) {
                args +=
                    afterCreatedAt.atOffset(
                        ZoneOffset.UTC,
                    )
                args +=
                    afterCreatedAt.atOffset(
                        ZoneOffset.UTC,
                    )
                args += afterRequestId
                """
                  AND (
                      r.created_at < ?
                      OR (
                          r.created_at = ?
                          AND r.id < ?
                      )
                  )
                """.trimIndent()
            } else {
                ""
            }

        args += limit

        return jdbc.query(
            baseSql +
                "\n" +
                pagePredicate +
                """
                
                ORDER BY
                    r.created_at DESC,
                    r.id DESC
                LIMIT ?
                """.trimIndent(),
            approvalReadMapper,
            *args.toTypedArray(),
        )
    }

    override fun loadDecisionContextForUpdate(
        approvalRequestId: UUID,
    ): ApprovalDecisionContext? =
        jdbc.query(
            """
            SELECT
                r.id AS request_id,
                r.organization_id,
                r.subject_type,
                r.subject_id,
                r.subject_version,
                r.policy_key,
                r.policy_version,
                r.requester_user_id,
                r.state AS request_state,
                r.version AS request_version,
                s.id AS step_id,
                s.authority_key,
                a.id AS assignment_id,
                a.principal_type,
                a.principal_user_id,
                a.principal_team_id,
                a.state AS assignment_state
            FROM approval_request r
            JOIN approval_step s
              ON s.approval_request_id = r.id
             AND s.sequence_number = 1
            JOIN approval_assignment a
              ON a.approval_request_id = r.id
             AND a.approval_step_id = s.id
            WHERE r.id = ?
            FOR UPDATE OF r, s, a
            """.trimIndent(),
            { rs, _ ->
                val principalType =
                    ApprovalPrincipalType.valueOf(
                        rs.getString(
                            "principal_type",
                        ),
                    )
                ApprovalDecisionContext(
                    requestId =
                        rs.getObject(
                            "request_id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    subjectType =
                        rs.getString(
                            "subject_type",
                        ),
                    subjectId =
                        rs.getObject(
                            "subject_id",
                            UUID::class.java,
                        ),
                    subjectVersion =
                        rs.getLong(
                            "subject_version",
                        ),
                    policyKey =
                        rs.getString(
                            "policy_key",
                        ),
                    policyVersion =
                        rs.getInt(
                            "policy_version",
                        ),
                    requesterUserId =
                        rs.getObject(
                            "requester_user_id",
                            UUID::class.java,
                        ),
                    requestState =
                        ApprovalRequestState
                            .valueOf(
                                rs.getString(
                                    "request_state",
                                ),
                            ),
                    requestVersion =
                        rs.getLong(
                            "request_version",
                        ),
                    stepId =
                        rs.getObject(
                            "step_id",
                            UUID::class.java,
                        ),
                    authorityKey =
                        rs.getString(
                            "authority_key",
                        ),
                    assignmentId =
                        rs.getObject(
                            "assignment_id",
                            UUID::class.java,
                        ),
                    assignmentPrincipalType =
                        principalType,
                    assignmentPrincipalId =
                        rs.getObject(
                            if (
                                principalType ==
                                ApprovalPrincipalType.USER
                            ) {
                                "principal_user_id"
                            } else {
                                "principal_team_id"
                            },
                            UUID::class.java,
                        ),
                    assignmentState =
                        rs.getString(
                            "assignment_state",
                        ),
                )
            },
            approvalRequestId,
        ).singleOrNull()

    override fun actorMatchesAssignment(
        actorUserId: UUID,
        context: ApprovalDecisionContext,
        at: Instant,
    ): Boolean =
        when (
            context.assignmentPrincipalType
        ) {
            ApprovalPrincipalType.USER ->
                actorUserId ==
                    context.assignmentPrincipalId &&
                    isActiveOrganizationMember(
                        organizationId =
                            context.organizationId,
                        actorUserId =
                            actorUserId,
                        at = at,
                    )

            ApprovalPrincipalType.TEAM ->
                isCurrentTeamMember(
                    organizationId =
                        context.organizationId,
                    teamId =
                        context.assignmentPrincipalId,
                    actorUserId =
                        actorUserId,
                    at = at,
                )
        }

    override fun applyDecision(
        context: ApprovalDecisionContext,
        decisionId: UUID,
        actorUserId: UUID,
        decision: ApprovalDecisionType,
        decidedAt: Instant,
        comment: String?,
        operationId: UUID,
        correlationId: String,
    ) {
        val at =
            decidedAt.atOffset(ZoneOffset.UTC)
        val terminalState =
            when (decision) {
                ApprovalDecisionType.APPROVE ->
                    ApprovalRequestState.APPROVED

                ApprovalDecisionType.REJECT ->
                    ApprovalRequestState.REJECTED

                ApprovalDecisionType.REQUEST_CHANGE ->
                    ApprovalRequestState.CHANGE_REQUESTED
            }

        val inserted =
            jdbc.update(
                """
                INSERT INTO approval_decision (
                    id,
                    approval_request_id,
                    approval_assignment_id,
                    actor_user_id,
                    decision,
                    decided_at,
                    comment_text,
                    subject_version,
                    operation_id,
                    correlation_id
                )
                VALUES (
                    ?, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?
                )
                """.trimIndent(),
                decisionId,
                context.requestId,
                context.assignmentId,
                actorUserId,
                decision.name,
                at,
                comment,
                context.subjectVersion,
                operationId,
                correlationId.take(128),
            )
        check(inserted == 1)

        check(
            jdbc.update(
                """
                UPDATE approval_assignment
                SET state = 'ACTED',
                    acted_at = ?
                WHERE id = ?
                  AND state = 'ASSIGNED'
                """.trimIndent(),
                at,
                context.assignmentId,
            ) == 1,
        )

        check(
            jdbc.update(
                """
                UPDATE approval_step
                SET state = 'COMPLETED'
                WHERE id = ?
                  AND state = 'PENDING'
                """.trimIndent(),
                context.stepId,
            ) == 1,
        )

        check(
            jdbc.update(
                """
                UPDATE approval_request
                SET state = ?,
                    completed_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND state = 'PENDING'
                  AND version = ?
                """.trimIndent(),
                terminalState.name,
                at,
                context.requestId,
                context.requestVersion,
            ) == 1,
        )
    }

    override fun supersede(
        context: ApprovalDecisionContext,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        check(
            jdbc.update(
                """
                UPDATE approval_assignment
                SET state = 'REVOKED'
                WHERE id = ?
                  AND state = 'ASSIGNED'
                """.trimIndent(),
                context.assignmentId,
            ) == 1,
        )
        check(
            jdbc.update(
                """
                UPDATE approval_step
                SET state = 'SUPERSEDED'
                WHERE id = ?
                  AND state = 'PENDING'
                """.trimIndent(),
                context.stepId,
            ) == 1,
        )
        check(
            jdbc.update(
                """
                UPDATE approval_request
                SET state = 'SUPERSEDED',
                    completed_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND state = 'PENDING'
                  AND version = ?
                """.trimIndent(),
                timestamp,
                context.requestId,
                context.requestVersion,
            ) == 1,
        )
    }

    private val approvalReadMapper =
        { rs: java.sql.ResultSet, _: Int ->
            val principalType =
                ApprovalPrincipalType.valueOf(
                    rs.getString(
                        "principal_type",
                    ),
                )
            ApprovalReadRecord(
                requestId =
                    rs.getObject(
                        "request_id",
                        UUID::class.java,
                    ),
                organizationId =
                    rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                subjectType =
                    rs.getString(
                        "subject_type",
                    ),
                subjectId =
                    rs.getObject(
                        "subject_id",
                        UUID::class.java,
                    ),
                subjectVersion =
                    rs.getLong(
                        "subject_version",
                    ),
                policyKey =
                    rs.getString(
                        "policy_key",
                    ),
                policyVersion =
                    rs.getInt(
                        "policy_version",
                    ),
                requesterUserId =
                    rs.getObject(
                        "requester_user_id",
                        UUID::class.java,
                    ),
                state =
                    ApprovalRequestState.valueOf(
                        rs.getString(
                            "request_state",
                        ),
                    ),
                reasonCode =
                    rs.getString(
                        "reason_code",
                    ),
                safeReasonSummary =
                    rs.getString(
                        "safe_reason_summary",
                    ),
                createdAt =
                    rs.getObject(
                        "created_at",
                        OffsetDateTime::class.java,
                    ).toInstant(),
                authorityKey =
                    rs.getString(
                        "authority_key",
                    ),
                assignmentPrincipalType =
                    principalType,
                assignmentPrincipalId =
                    rs.getObject(
                        if (
                            principalType ==
                            ApprovalPrincipalType.USER
                        ) {
                            "principal_user_id"
                        } else {
                            "principal_team_id"
                        },
                        UUID::class.java,
                    ),
            )
        }

    private fun authorityPrincipalBelongsToOrganization(
        authority: ApprovalAuthorityBindingSnapshot,
        at: Instant,
    ): Boolean =
        when (authority.principalType) {
            ApprovalPrincipalType.USER ->
                isActiveOrganizationMember(
                    organizationId =
                        authority.organizationId,
                    actorUserId =
                        authority.principalId,
                    at = at,
                )

            ApprovalPrincipalType.TEAM ->
                jdbc.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM team t
                        WHERE t.id = ?
                          AND t.organization_id = ?
                          AND t.active = true
                    )
                    """.trimIndent(),
                    Boolean::class.java,
                    authority.principalId,
                    authority.organizationId,
                ) ?: false
        }

    private fun isCurrentTeamMember(
        organizationId: UUID,
        teamId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM team t
                JOIN team_membership tm
                  ON tm.team_id = t.id
                JOIN user_identity u
                  ON u.id = tm.user_identity_id
                WHERE t.id = ?
                  AND t.organization_id = ?
                  AND t.active = true
                  AND tm.user_identity_id = ?
                  AND tm.valid_from <= ?
                  AND (
                      tm.valid_until IS NULL
                      OR tm.valid_until > ?
                  )
                  AND u.status = 'ACTIVE'
            )
            """.trimIndent(),
            Boolean::class.java,
            teamId,
            organizationId,
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        ) ?: false
}
