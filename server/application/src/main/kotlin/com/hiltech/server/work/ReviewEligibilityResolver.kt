package com.hiltech.server.work

import com.hiltech.server.projects.ProjectAuthorizationPort
import com.hiltech.server.projects.ProjectResponsibilityPrincipalType
import com.hiltech.server.projects.ProjectSnapshot
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

interface ReviewEligibilityResolverPort {
    fun resolve(
        actorUserId: UUID,
        project: ProjectSnapshot,
        policy: ReviewPolicyExecutionSnapshot,
        decisions: List<ReviewDecisionSnapshot>,
        at: Instant,
    ): ReviewerEligibilitySnapshot
}

@Component
class ReviewEligibilityResolver(
    private val authorization: ProjectAuthorizationPort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val jdbc: JdbcTemplate,
) : ReviewEligibilityResolverPort {
    override fun resolve(
        actorUserId: UUID,
        project: ProjectSnapshot,
        policy: ReviewPolicyExecutionSnapshot,
        decisions: List<ReviewDecisionSnapshot>,
        at: Instant,
    ): ReviewerEligibilitySnapshot {
        if (
            !sourceAuthority.isOrganizationMemberCurrent(
                actorUserId,
                project.organizationId,
                at,
            )
        ) {
            return denied(
                "ORGANIZATION_MEMBERSHIP_NOT_CURRENT",
            )
        }

        val steps =
            candidateSteps(
                policy,
                decisions,
            )
        if (steps.isEmpty()) {
            return denied(
                "REVIEW_POLICY_ALREADY_SATISFIED",
            )
        }

        val alreadyAccepted =
            decisions
                .filter {
                    it.reviewerUserId == actorUserId &&
                        it.decision ==
                            WorkReviewDecisionType.ACCEPT
                }
                .mapNotNull {
                    it.reviewStepKey
                }
                .toSet()

        for (step in steps) {
            if (step.stepKey in alreadyAccepted) {
                continue
            }
            val result =
                evaluateStep(
                    actorUserId,
                    project,
                    step,
                    at,
                )
            if (result.eligible) {
                return result
            }
        }

        val reasons =
            steps.flatMap {
                evaluateStep(
                    actorUserId,
                    project,
                    it,
                    at,
                ).reasonCodes
            }.distinct()

        return ReviewerEligibilitySnapshot(
            eligible = false,
            reviewStepKey = null,
            sourceType = null,
            sourceRef = null,
            reasonCodes =
                reasons.ifEmpty {
                    listOf(
                        "REVIEWER_NOT_ELIGIBLE",
                    )
                },
        )
    }

    private fun candidateSteps(
        policy: ReviewPolicyExecutionSnapshot,
        decisions: List<ReviewDecisionSnapshot>,
    ): List<ReviewStepExecutionSnapshot> {
        if (policy.steps.isEmpty()) {
            return emptyList()
        }

        val acceptedByStep =
            decisions
                .filter {
                    it.decision ==
                        WorkReviewDecisionType.ACCEPT
                }
                .groupBy {
                    it.reviewStepKey
                }

        fun satisfied(
            step: ReviewStepExecutionSnapshot,
        ): Boolean {
            val count =
                acceptedByStep[step.stepKey]
                    ?.map {
                        it.reviewerUserId
                    }
                    ?.distinct()
                    ?.size
                    ?: 0
            val required =
                when (policy.mode) {
                    ReviewMode.QUORUM ->
                        step.quorumCount ?: 1
                    else -> 1
                }
            return count >= required
        }

        return when (policy.mode) {
            ReviewMode.SEQUENTIAL -> {
                val first =
                    policy.steps
                        .sortedBy { it.sequence }
                        .firstOrNull {
                            !satisfied(it)
                        }
                listOfNotNull(first)
            }

            ReviewMode.ANY_ONE ->
                if (
                    decisions.any {
                        it.decision ==
                            WorkReviewDecisionType.ACCEPT
                    }
                ) {
                    emptyList()
                } else {
                    policy.steps
                }

            ReviewMode.ALL,
            ReviewMode.QUORUM,
            ->
                policy.steps.filterNot {
                    satisfied(it)
                }
        }
    }

    private fun evaluateStep(
        actorUserId: UUID,
        project: ProjectSnapshot,
        step: ReviewStepExecutionSnapshot,
        at: Instant,
    ): ReviewerEligibilitySnapshot {
        val baseAllowed =
            authorization.canReviewWork(
                actorUserId,
                project,
            )

        return when (step.selectorType) {
            ReviewSelectorType.RELATIONSHIP -> {
                val selector =
                    step.selectorValue
                        ?.trim()
                        ?.uppercase()
                when (selector) {
                    "PROJECT_MANAGER" -> {
                        val current =
                            currentProjectManager(
                                actorUserId,
                                project,
                                at,
                            )
                        if (current && baseAllowed) {
                            allowed(
                                step,
                                "PROJECT_RELATIONSHIP",
                                "PROJECT_MANAGER",
                            )
                        } else {
                            denied(
                                step,
                                "PROJECT_MANAGER_NOT_CURRENT",
                            )
                        }
                    }

                    "CAN_REVIEW_WORK",
                    "PROJECT_REVIEWER",
                    -> {
                        if (baseAllowed) {
                            allowed(
                                step,
                                "PROJECT_RELATIONSHIP",
                                "CAN_REVIEW_WORK",
                            )
                        } else {
                            denied(
                                step,
                                "PROJECT_REVIEW_RELATIONSHIP_NOT_CURRENT",
                            )
                        }
                    }

                    else ->
                        denied(
                            step,
                            "REVIEW_RELATIONSHIP_SOURCE_UNAVAILABLE",
                        )
                }
            }

            ReviewSelectorType.ROLE -> {
                val role =
                    currentRole(
                        actorUserId,
                        project.organizationId,
                        at,
                    )
                if (
                    baseAllowed &&
                    role != null &&
                    role.equals(
                        step.selectorValue,
                        ignoreCase = true,
                    )
                ) {
                    allowed(
                        step,
                        "WORKFORCE_ROLE",
                        role,
                    )
                } else {
                    denied(
                        step,
                        if (role == null) {
                            "WORKFORCE_ASSIGNMENT_NOT_CURRENT"
                        } else {
                            "REVIEW_ROLE_REQUIREMENT_MISSING"
                        },
                    )
                }
            }

            ReviewSelectorType.TEAM -> {
                val teamId =
                    step.selectorValue
                        ?.let {
                            runCatching {
                                UUID.fromString(it)
                            }.getOrNull()
                        }
                if (
                    teamId != null &&
                    baseAllowed &&
                    sourceAuthority
                        .isTeamMemberCurrent(
                            actorUserId,
                            teamId,
                            at,
                        )
                ) {
                    allowed(
                        step,
                        "TEAM",
                        teamId.toString(),
                    )
                } else {
                    denied(
                        step,
                        "REVIEW_TEAM_MEMBERSHIP_NOT_CURRENT",
                    )
                }
            }

            ReviewSelectorType.SPECIFIC_USER -> {
                val expected =
                    step.selectorValue
                        ?.let {
                            runCatching {
                                UUID.fromString(it)
                            }.getOrNull()
                        }
                if (
                    expected == actorUserId &&
                    baseAllowed
                ) {
                    allowed(
                        step,
                        "SPECIFIC_USER",
                        actorUserId.toString(),
                    )
                } else {
                    denied(
                        step,
                        "SPECIFIC_REVIEWER_NOT_CURRENT",
                    )
                }
            }

            ReviewSelectorType.SUBJECT_MANAGER_CHAIN ->
                denied(
                    step,
                    "SUBJECT_MANAGER_CHAIN_SOURCE_UNAVAILABLE",
                )

            ReviewSelectorType.CLIENT_RELATIONSHIP ->
                denied(
                    step,
                    "CLIENT_REVIEW_RELATIONSHIP_SOURCE_UNAVAILABLE",
                )
        }
    }

    private fun currentProjectManager(
        actorUserId: UUID,
        project: ProjectSnapshot,
        at: Instant,
    ): Boolean {
        val responsibility =
            project.responsibility
                ?: return false
        return when (
            responsibility.principalType
        ) {
            ProjectResponsibilityPrincipalType.EMPLOYEE ->
                responsibility
                    .linkedUserIdentityId ==
                    actorUserId

            ProjectResponsibilityPrincipalType.TEAM ->
                sourceAuthority
                    .isTeamMemberCurrent(
                        actorUserId,
                        responsibility.principalId,
                        at,
                    )
        }
    }

    private fun currentRole(
        actorUserId: UUID,
        organizationId: UUID,
        at: Instant,
    ): String? =
        jdbc.query(
            """
            SELECT wa.role_code
            FROM user_identity ui
            JOIN employee e
              ON e.person_id = ui.person_id
             AND e.organization_id = ?
            JOIN employment emp
              ON emp.employee_id = e.id
             AND emp.state = 'ACTIVE'
            JOIN workforce_assignment wa
              ON wa.employee_id = e.id
             AND wa.state = 'ACTIVE'
             AND wa.effective_from <= ?
             AND (
                 wa.effective_to IS NULL
                 OR wa.effective_to > ?
             )
            WHERE ui.id = ?
              AND ui.status = 'ACTIVE'
              AND e.state IN (
                  'ACTIVE',
                  'PREBOARDING'
              )
            ORDER BY wa.effective_from DESC,
                     wa.id DESC
            LIMIT 1
            """.trimIndent(),
            { rs, _ ->
                rs.getString(
                    "role_code",
                )
            },
            organizationId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
        ).singleOrNull()

    private fun allowed(
        step: ReviewStepExecutionSnapshot,
        sourceType: String,
        sourceRef: String,
    ) =
        ReviewerEligibilitySnapshot(
            eligible = true,
            reviewStepKey =
                step.stepKey,
            sourceType = sourceType,
            sourceRef = sourceRef,
            reasonCodes = emptyList(),
        )

    private fun denied(
        step: ReviewStepExecutionSnapshot,
        code: String,
    ) =
        ReviewerEligibilitySnapshot(
            eligible = false,
            reviewStepKey =
                step.stepKey,
            sourceType = null,
            sourceRef = null,
            reasonCodes = listOf(code),
        )

    private fun denied(
        code: String,
    ) =
        ReviewerEligibilitySnapshot(
            eligible = false,
            reviewStepKey = null,
            sourceType = null,
            sourceRef = null,
            reasonCodes = listOf(code),
        )
}
