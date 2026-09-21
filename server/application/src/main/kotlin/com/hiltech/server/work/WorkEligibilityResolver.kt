package com.hiltech.server.work

import com.hiltech.server.people.EmployeeState
import com.hiltech.server.people.HrDocumentsPersistencePort
import com.hiltech.server.people.HrVerificationState
import com.hiltech.server.people.PeoplePersistencePort
import com.hiltech.server.people.WorkforceAssignmentPersistencePort
import com.hiltech.server.security.RoleTeamSourceAuthorityPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

interface WorkEligibilityResolverPort {
    fun eligibleTargets(
        order: WorkOrderSnapshot,
        policy: AssignmentPolicyExecutionSnapshot,
        at: Instant,
    ): List<EligibleTargetResult>
}

@Component
class WorkEligibilityResolver(
    private val people: PeoplePersistencePort,
    private val workforce: WorkforceAssignmentPersistencePort,
    private val hr: HrDocumentsPersistencePort,
    private val sourceAuthority: RoleTeamSourceAuthorityPort,
    private val jdbc: JdbcTemplate,
) : WorkEligibilityResolverPort {
    override fun eligibleTargets(
        order: WorkOrderSnapshot,
        policy: AssignmentPolicyExecutionSnapshot,
        at: Instant,
    ): List<EligibleTargetResult> {
        val userCapabilities =
            people.listDirectory(
                organizationId = order.organizationId,
                at = at,
                limit = 1000,
            ).mapNotNull { directory ->
                val employee =
                    people.loadEmployee(
                        directory.employeeId,
                        at,
                    ) ?: return@mapNotNull null
                userCapability(
                    employee.employeeId,
                    employee.displayName,
                    employee.employeeState,
                    employee.activeEmploymentId != null,
                    employee.linkedIdentityId,
                    order.organizationId,
                    policy,
                    at,
                )
            }

        val byIdentity =
            userCapabilities
                .mapNotNull { capability ->
                    capability.identityId?.let { it to capability }
                }
                .toMap()
        val byEmployee =
            userCapabilities.associateBy { it.employeeId }

        val results = mutableListOf<EligibleTargetResult>()

        userCapabilities
            .filter { it.identityId != null }
            .forEach { capability ->
                val reasons =
                    capability.reasonCodes.toMutableList()
                if (
                    AssignmentTargetType.USER !in
                    policy.allowedTargetTypes
                ) {
                    reasons += "TARGET_TYPE_NOT_ALLOWED"
                }
                results +=
                    capability.result(
                        targetType = AssignmentTargetType.USER,
                        targetId = requireNotNull(capability.identityId),
                        reasonCodes = reasons.distinct(),
                        at = at,
                    )
            }

        teamCandidates(order.organizationId).forEach { team ->
            val reasons = mutableListOf<String>()
            if (!team.active) {
                reasons += "TEAM_INACTIVE"
            }
            if (
                AssignmentTargetType.TEAM !in
                policy.allowedTargetTypes
            ) {
                reasons += "TARGET_TYPE_NOT_ALLOWED"
            }
            val members =
                currentTeamMembers(
                    team.id,
                    at,
                ).mapNotNull(byIdentity::get)
            if (members.none { it.eligible }) {
                reasons += "TEAM_HAS_NO_ELIGIBLE_MEMBER"
            }
            results +=
                EligibleTargetResult(
                    targetType = AssignmentTargetType.TEAM,
                    targetId = team.id,
                    displayLabel = team.name,
                    eligible = reasons.isEmpty(),
                    reasonCodes = reasons.distinct(),
                    sourceAsOf = at,
                    requiredRoleChecks =
                        mergeChecks(
                            members.map { it.roleChecks },
                        ),
                    requiredCertificationChecks =
                        mergeChecks(
                            members.map { it.certificationChecks },
                        ),
                )
        }

        crewCandidates(
            organizationId = order.organizationId,
            projectId = order.projectId,
        ).forEach { crew ->
            val reasons = mutableListOf<String>()
            val current =
                crew.state == "ACTIVE" &&
                    crew.effectiveFrom <= at &&
                    (crew.effectiveTo == null || crew.effectiveTo > at)
            if (!current) {
                reasons += "CREW_INACTIVE"
            }
            if (
                AssignmentTargetType.CREW !in
                policy.allowedTargetTypes
            ) {
                reasons += "TARGET_TYPE_NOT_ALLOWED"
            }
            val members =
                currentCrewMembers(
                    crew.id,
                    at,
                ).mapNotNull(byEmployee::get)
            if (members.none { it.eligible }) {
                reasons += "CREW_HAS_NO_ELIGIBLE_MEMBER"
            }
            results +=
                EligibleTargetResult(
                    targetType = AssignmentTargetType.CREW,
                    targetId = crew.id,
                    displayLabel = crew.name,
                    eligible = reasons.isEmpty(),
                    reasonCodes = reasons.distinct(),
                    sourceAsOf = at,
                    requiredRoleChecks =
                        mergeChecks(
                            members.map { it.roleChecks },
                        ),
                    requiredCertificationChecks =
                        mergeChecks(
                            members.map { it.certificationChecks },
                        ),
                )
        }

        subcontractorCandidates().forEach { subcontractor ->
            val reasons = mutableListOf<String>()
            if (!subcontractor.active) {
                reasons += "SUBCONTRACTOR_INACTIVE"
            }
            if (
                AssignmentTargetType.SUBCONTRACTOR_ORGANIZATION !in
                    policy.allowedTargetTypes ||
                !policy.allowExternalSubcontractor
            ) {
                reasons += "TARGET_TYPE_NOT_ALLOWED"
            }
            val capabilityChecksRequired =
                policy.requiredRoleCodes.isNotEmpty() ||
                    policy.requiredCertificationCodes.isNotEmpty()
            if (capabilityChecksRequired) {
                reasons += "CAPABILITY_SOURCE_UNAVAILABLE"
            }
            results +=
                EligibleTargetResult(
                    targetType =
                        AssignmentTargetType
                            .SUBCONTRACTOR_ORGANIZATION,
                    targetId = subcontractor.id,
                    displayLabel = subcontractor.name,
                    eligible = reasons.isEmpty(),
                    reasonCodes = reasons.distinct(),
                    sourceAsOf = at,
                    sourceFreshness =
                        if (capabilityChecksRequired) {
                            "UNAVAILABLE"
                        } else {
                            "CURRENT"
                        },
                    requiredRoleChecks =
                        policy.requiredRoleCodes.map {
                            EligibilityCheck(
                                code = it,
                                satisfied = false,
                                reasonCode =
                                    "CAPABILITY_SOURCE_UNAVAILABLE",
                            )
                        },
                    requiredCertificationChecks =
                        policy.requiredCertificationCodes.map {
                            EligibilityCheck(
                                code = it,
                                satisfied = false,
                                reasonCode =
                                    "CAPABILITY_SOURCE_UNAVAILABLE",
                            )
                        },
                )
        }

        return results.sortedWith(
            compareBy<EligibleTargetResult>(
                { it.targetType.name },
                { it.displayLabel },
                { it.targetId.toString() },
            ),
        )
    }

    private fun userCapability(
        employeeId: UUID,
        displayName: String,
        employeeState: EmployeeState,
        hasActiveEmployment: Boolean,
        identityId: UUID?,
        organizationId: UUID,
        policy: AssignmentPolicyExecutionSnapshot,
        at: Instant,
    ): UserCapability {
        val reasons = mutableListOf<String>()
        val allowedEmployeeState =
            employeeState == EmployeeState.ACTIVE ||
                (
                    policy.allowPreboardingEmployee &&
                        employeeState == EmployeeState.PREBOARDING
                )
        if (!allowedEmployeeState) {
            reasons += "EMPLOYEE_NOT_ACTIVE"
        }
        if (!hasActiveEmployment) {
            reasons += "EMPLOYMENT_NOT_ACTIVE"
        }

        val identity =
            identityId?.let(people::identityLink)
        if (
            identityId == null ||
            identity?.status != "ACTIVE"
        ) {
            reasons += "IDENTITY_NOT_ACTIVE"
        }
        if (
            identityId == null ||
            !sourceAuthority.isOrganizationMemberCurrent(
                identityId = identityId,
                organizationId = organizationId,
                at = at,
            )
        ) {
            reasons += "ORGANIZATION_MEMBERSHIP_NOT_CURRENT"
        }

        val currentWorkforce =
            workforce.currentForEmployee(
                employeeId,
                at,
            )
        if (currentWorkforce == null) {
            reasons += "WORKFORCE_ASSIGNMENT_NOT_CURRENT"
        }

        val roleChecks =
            policy.requiredRoleCodes
                .sorted()
                .map { roleCode ->
                    EligibilityCheck(
                        code = roleCode,
                        satisfied =
                            currentWorkforce?.roleCode == roleCode,
                        reasonCode =
                            if (
                                currentWorkforce?.roleCode ==
                                roleCode
                            ) {
                                null
                            } else {
                                "ROLE_REQUIREMENT_MISSING"
                            },
                    )
                }
        if (
            roleChecks.isNotEmpty() &&
            roleChecks.none { it.satisfied }
        ) {
            reasons += "ROLE_REQUIREMENT_MISSING"
        }

        val certifications =
            hr.listCertifications(employeeId)
        val certificationChecks =
            policy.requiredCertificationCodes
                .sorted()
                .map { requiredCode ->
                    val matching =
                        certifications.filter {
                            it.certificationTypeCode ==
                                requiredCode
                        }
                    val valid =
                        matching.any {
                            it.verificationState ==
                                HrVerificationState.VERIFIED &&
                                (
                                    it.validUntil == null ||
                                        it.validUntil >= at
                                )
                        }
                    val expired =
                        matching.any {
                            it.verificationState ==
                                HrVerificationState.VERIFIED &&
                                it.validUntil != null &&
                                it.validUntil < at
                        }
                    EligibilityCheck(
                        code = requiredCode,
                        satisfied = valid,
                        reasonCode =
                            when {
                                valid -> null
                                expired ->
                                    "CERTIFICATION_EXPIRED"
                                else ->
                                    "CERTIFICATION_MISSING"
                            },
                    )
                }
        certificationChecks
            .filterNot { it.satisfied }
            .mapNotNull { it.reasonCode }
            .forEach(reasons::add)

        return UserCapability(
            employeeId = employeeId,
            identityId = identityId,
            label = displayName,
            eligible = reasons.isEmpty(),
            reasonCodes = reasons.distinct(),
            roleChecks = roleChecks,
            certificationChecks = certificationChecks,
        )
    }

    private fun teamCandidates(
        organizationId: UUID,
    ): List<TeamCandidate> =
        jdbc.query(
            """
            SELECT id, name, active
            FROM team
            WHERE organization_id = ?
            ORDER BY name, id
            """.trimIndent(),
            { rs, _ ->
                TeamCandidate(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    active = rs.getBoolean("active"),
                )
            },
            organizationId,
        )

    private fun currentTeamMembers(
        teamId: UUID,
        at: Instant,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT user_identity_id
            FROM team_membership
            WHERE team_id = ?
              AND valid_from <= ?
              AND (valid_until IS NULL OR valid_until > ?)
            ORDER BY user_identity_id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "user_identity_id",
                    UUID::class.java,
                )
            },
            teamId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    private fun crewCandidates(
        organizationId: UUID,
        projectId: UUID,
    ): List<CrewCandidate> =
        jdbc.query(
            """
            SELECT id, name, state, effective_from, effective_to
            FROM work_crew
            WHERE organization_id = ?
              AND (project_id IS NULL OR project_id = ?)
            ORDER BY name, id
            """.trimIndent(),
            { rs, _ ->
                CrewCandidate(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    state = rs.getString("state"),
                    effectiveFrom =
                        rs.getObject(
                            "effective_from",
                            java.time.OffsetDateTime::class.java,
                        ).toInstant(),
                    effectiveTo =
                        rs.getObject(
                            "effective_to",
                            java.time.OffsetDateTime::class.java,
                        )?.toInstant(),
                )
            },
            organizationId,
            projectId,
        )

    private fun currentCrewMembers(
        crewId: UUID,
        at: Instant,
    ): List<UUID> =
        jdbc.query(
            """
            SELECT employee_id
            FROM work_crew_member
            WHERE crew_id = ?
              AND effective_from <= ?
              AND (effective_to IS NULL OR effective_to > ?)
            ORDER BY employee_id
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "employee_id",
                    UUID::class.java,
                )
            },
            crewId,
            at.atOffset(ZoneOffset.UTC),
            at.atOffset(ZoneOffset.UTC),
        )

    private fun subcontractorCandidates():
        List<SubcontractorCandidate> =
        jdbc.query(
            """
            SELECT id, display_name, status
            FROM organization
            WHERE organization_type = 'SUBCONTRACTOR'
            ORDER BY display_name, id
            """.trimIndent(),
            { rs, _ ->
                SubcontractorCandidate(
                    id = rs.getObject("id", UUID::class.java),
                    name = rs.getString("display_name"),
                    active = rs.getString("status") == "ACTIVE",
                )
            },
        )

    private fun mergeChecks(
        groups: List<List<EligibilityCheck>>,
    ): List<EligibilityCheck> {
        val codes =
            groups.flatten()
                .map { it.code }
                .distinct()
                .sorted()
        return codes.map { code ->
            val checks =
                groups.flatten().filter { it.code == code }
            val satisfied =
                checks.any { it.satisfied }
            EligibilityCheck(
                code = code,
                satisfied = satisfied,
                reasonCode =
                    if (satisfied) {
                        null
                    } else {
                        checks
                            .mapNotNull { it.reasonCode }
                            .firstOrNull()
                    },
            )
        }
    }

    private data class UserCapability(
        val employeeId: UUID,
        val identityId: UUID?,
        val label: String,
        val eligible: Boolean,
        val reasonCodes: List<String>,
        val roleChecks: List<EligibilityCheck>,
        val certificationChecks: List<EligibilityCheck>,
    ) {
        fun result(
            targetType: AssignmentTargetType,
            targetId: UUID,
            reasonCodes: List<String>,
            at: Instant,
        ) =
            EligibleTargetResult(
                targetType = targetType,
                targetId = targetId,
                displayLabel = label,
                eligible = reasonCodes.isEmpty(),
                reasonCodes = reasonCodes,
                sourceAsOf = at,
                requiredRoleChecks = roleChecks,
                requiredCertificationChecks =
                    certificationChecks,
            )
    }

    private data class TeamCandidate(
        val id: UUID,
        val name: String,
        val active: Boolean,
    )

    private data class CrewCandidate(
        val id: UUID,
        val name: String,
        val state: String,
        val effectiveFrom: Instant,
        val effectiveTo: Instant?,
    )

    private data class SubcontractorCandidate(
        val id: UUID,
        val name: String,
        val active: Boolean,
    )
}
