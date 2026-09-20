package com.hiltech.server.projects

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class OrganizationProjectContext(
    val organizationId: UUID,
    val displayName: String,
    val organizationType: String,
    val active: Boolean,
)

data class ProjectCodePolicySnapshot(
    val configRevisionId: UUID,
    val scopeType: String,
    val policyCode: String,
    val prefix: String?,
    val includeYear: Boolean,
    val separator: String,
    val sequenceScope: String,
    val sequencePadding: Int,
    val manualOverrideAllowed: Boolean,
    val resetRule: String,
)

data class ProjectPrincipalContext(
    val principalType: ProjectResponsibilityPrincipalType,
    val principalId: UUID,
    val label: String,
    val linkedUserIdentityId: UUID?,
)

data class ResponsibilityChangeResult(
    val previous: ProjectResponsibilitySnapshot?,
    val current: ProjectResponsibilitySnapshot,
)

interface ProjectsPersistencePort {
    fun organization(organizationId: UUID): OrganizationProjectContext?
    fun activeProjectCodePolicies(
        organizationId: UUID,
        at: Instant,
    ): List<ProjectCodePolicySnapshot>

    fun allocateCodeSequence(
        organizationId: UUID,
        policy: ProjectCodePolicySnapshot,
        at: Instant,
    ): Long

    fun projectCodeExists(
        organizationId: UUID,
        projectCode: String,
    ): Boolean

    fun insertProject(
        projectId: UUID,
        organizationId: UUID,
        projectCode: String,
        name: String,
        clientOrganizationId: UUID,
        sourceType: ProjectSourceType,
        sourceExternalReference: String?,
        startDatePlanned: LocalDate?,
        endDatePlanned: LocalDate?,
        actorUserId: UUID,
        createdAt: Instant,
    )

    fun project(
        projectId: UUID,
        at: Instant,
    ): ProjectSnapshot?

    fun listProjects(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<ProjectSnapshot>

    fun updateProjectDetails(
        projectId: UUID,
        expectedVersion: Long,
        name: String,
        startDatePlanned: LocalDate?,
        endDatePlanned: LocalDate?,
        at: Instant,
    ): Boolean

    fun transitionProject(
        projectId: UUID,
        expectedVersion: Long,
        expectedState: ProjectLifecycleState,
        targetState: ProjectLifecycleState,
        at: Instant,
    ): Boolean

    fun bumpProjectVersion(
        projectId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun principal(
        organizationId: UUID,
        input: ProjectPrincipalInput,
        at: Instant,
    ): ProjectPrincipalContext?

    fun currentResponsibility(
        projectId: UUID,
        at: Instant,
    ): ProjectResponsibilitySnapshot?

    fun insertInitialResponsibility(
        responsibilityId: UUID,
        projectId: UUID,
        organizationId: UUID,
        principal: ProjectPrincipalContext,
        operationId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): ProjectResponsibilitySnapshot

    fun replaceResponsibility(
        responsibilityId: UUID,
        projectId: UUID,
        organizationId: UUID,
        principal: ProjectPrincipalContext,
        operationId: UUID,
        actorUserId: UUID,
        reason: String?,
        at: Instant,
    ): ResponsibilityChangeResult

    fun siteCodeExists(
        organizationId: UUID,
        clientOrganizationId: UUID,
        siteCode: String,
    ): Boolean

    fun insertSite(
        siteId: UUID,
        organizationId: UUID,
        clientOrganizationId: UUID,
        siteCode: String,
        name: String,
        addressText: String?,
        latitude: Double?,
        longitude: Double?,
        timezone: String?,
        actorUserId: UUID,
        at: Instant,
    )

    fun site(siteId: UUID): SiteSnapshot?

    fun updateSite(
        siteId: UUID,
        expectedVersion: Long,
        name: String,
        addressText: String?,
        latitude: Double?,
        longitude: Double?,
        timezone: String?,
        status: SiteStatus,
        at: Instant,
    ): Boolean

    fun attachSite(
        projectSiteId: UUID,
        organizationId: UUID,
        projectId: UUID,
        siteId: UUID,
        projectSiteCode: String?,
        accessInstructions: String?,
        projectSpecificNotes: String?,
    )

    fun projectSite(
        projectSiteId: UUID,
    ): ProjectSiteSnapshot?

    fun listProjectSites(
        projectId: UUID,
    ): List<ProjectSiteSnapshot>

    fun projectSiteExists(
        projectId: UUID,
        siteId: UUID,
    ): Boolean
}

@Component
class JdbcProjectsPersistence(
    private val jdbc: JdbcTemplate,
) : ProjectsPersistencePort {
    override fun organization(
        organizationId: UUID,
    ): OrganizationProjectContext? =
        jdbc.query(
            """
            SELECT id, display_name, organization_type, status
            FROM organization
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                OrganizationProjectContext(
                    organizationId =
                        rs.getObject("id", UUID::class.java),
                    displayName =
                        rs.getString("display_name"),
                    organizationType =
                        rs.getString("organization_type"),
                    active =
                        rs.getString("status") == "ACTIVE",
                )
            },
            organizationId,
        ).singleOrNull()

    override fun activeProjectCodePolicies(
        organizationId: UUID,
        at: Instant,
    ): List<ProjectCodePolicySnapshot> {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT
                cr.id,
                cr.scope_type,
                cr.code,
                cp.prefix,
                cp.include_year,
                cp.separator,
                cp.sequence_scope,
                cp.sequence_padding,
                cp.manual_override_allowed,
                cp.reset_rule
            FROM config_revision cr
            JOIN code_policy cp
              ON cp.config_revision_id = cr.id
            WHERE cr.family = 'code-policies'
              AND cr.lifecycle_state = 'ACTIVE'
              AND cp.target_object_type = 'PROJECT'
              AND (
                  (
                      cr.scope_type = 'ORGANIZATION'
                      AND cr.scope_organization_id = ?
                  )
                  OR (
                      cr.scope_type = 'SYSTEM'
                      AND cr.scope_organization_id IS NULL
                  )
              )
              AND (cr.effective_from IS NULL OR cr.effective_from <= ?)
              AND (cr.effective_to IS NULL OR cr.effective_to > ?)
            ORDER BY
                CASE WHEN cr.scope_type = 'ORGANIZATION' THEN 0 ELSE 1 END,
                cr.code,
                cr.id
            """.trimIndent(),
            { rs, _ ->
                ProjectCodePolicySnapshot(
                    configRevisionId =
                        rs.getObject("id", UUID::class.java),
                    scopeType =
                        rs.getString("scope_type"),
                    policyCode =
                        rs.getString("code"),
                    prefix =
                        rs.getString("prefix"),
                    includeYear =
                        rs.getBoolean("include_year"),
                    separator =
                        rs.getString("separator"),
                    sequenceScope =
                        rs.getString("sequence_scope"),
                    sequencePadding =
                        rs.getInt("sequence_padding"),
                    manualOverrideAllowed =
                        rs.getBoolean("manual_override_allowed"),
                    resetRule =
                        rs.getString("reset_rule"),
                )
            },
            organizationId,
            timestamp,
            timestamp,
        )
    }

    override fun allocateCodeSequence(
        organizationId: UUID,
        policy: ProjectCodePolicySnapshot,
        at: Instant,
    ): Long {
        val period =
            if (policy.resetRule == "YEARLY") {
                at.atZone(ZoneOffset.UTC).year.toString()
            } else {
                "ALL"
            }
        val scopeKey =
            when (policy.sequenceScope) {
                "ORGANIZATION" ->
                    organizationId.toString()
                "OBJECT_TYPE" ->
                    "PROJECT"
                else ->
                    error(
                        "Unsupported Project CodePolicy sequence scope ${policy.sequenceScope}.",
                    )
            }

        jdbc.update(
            """
            INSERT INTO code_sequence (
                scope_key,
                target_object_type,
                policy_code,
                sequence_period,
                next_value,
                version,
                updated_at
            )
            VALUES (?, 'PROJECT', ?, ?, 1, 1, ?)
            ON CONFLICT (
                scope_key,
                target_object_type,
                policy_code,
                sequence_period
            )
            DO NOTHING
            """.trimIndent(),
            scopeKey,
            policy.policyCode,
            period,
            at.atOffset(ZoneOffset.UTC),
        )

        return requireNotNull(
            jdbc.queryForObject(
                """
                UPDATE code_sequence
                SET next_value = next_value + 1,
                    version = version + 1,
                    updated_at = ?
                WHERE scope_key = ?
                  AND target_object_type = 'PROJECT'
                  AND policy_code = ?
                  AND sequence_period = ?
                RETURNING next_value - 1
                """.trimIndent(),
                Long::class.java,
                at.atOffset(ZoneOffset.UTC),
                scopeKey,
                policy.policyCode,
                period,
            ),
        )
    }

    override fun projectCodeExists(
        organizationId: UUID,
        projectCode: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM project
                WHERE organization_id = ?
                  AND project_code = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
            projectCode,
        ) == true

    override fun insertProject(
        projectId: UUID,
        organizationId: UUID,
        projectCode: String,
        name: String,
        clientOrganizationId: UUID,
        sourceType: ProjectSourceType,
        sourceExternalReference: String?,
        startDatePlanned: LocalDate?,
        endDatePlanned: LocalDate?,
        actorUserId: UUID,
        createdAt: Instant,
    ) {
        val timestamp =
            createdAt.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO project (
                id,
                organization_id,
                project_code,
                name,
                client_organization_id,
                source_opportunity_id,
                contract_id,
                client_po_id,
                lifecycle_state,
                start_date_planned,
                end_date_planned,
                baseline_version,
                created_at,
                created_by,
                updated_at,
                version,
                source_type,
                source_external_reference
            )
            VALUES (
                ?, ?, ?, ?, ?,
                NULL, NULL, NULL,
                'DRAFT',
                ?, ?,
                1,
                ?, ?, ?,
                1,
                ?, ?
            )
            """.trimIndent(),
            projectId,
            organizationId,
            projectCode,
            name,
            clientOrganizationId,
            startDatePlanned,
            endDatePlanned,
            timestamp,
            actorUserId,
            timestamp,
            sourceType.name,
            sourceExternalReference,
        )
    }

    override fun project(
        projectId: UUID,
        at: Instant,
    ): ProjectSnapshot? =
        loadProjectById(
            projectId = projectId,
            at = at,
        )

    override fun listProjects(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<ProjectSnapshot> {
        val ids =
            jdbc.query(
                """
                SELECT id
                FROM project
                WHERE organization_id = ?
                ORDER BY updated_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("id", UUID::class.java)
                },
                organizationId,
                limit.coerceIn(1, 200),
            )
        return ids.mapNotNull {
            loadProjectById(it, at)
        }
    }

    override fun updateProjectDetails(
        projectId: UUID,
        expectedVersion: Long,
        name: String,
        startDatePlanned: LocalDate?,
        endDatePlanned: LocalDate?,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE project
            SET name = ?,
                start_date_planned = ?,
                end_date_planned = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND lifecycle_state IN (
                  'DRAFT','KICKOFF','PLANNING'
              )
            """.trimIndent(),
            name,
            startDatePlanned,
            endDatePlanned,
            at.atOffset(ZoneOffset.UTC),
            projectId,
            expectedVersion,
        ) == 1

    override fun transitionProject(
        projectId: UUID,
        expectedVersion: Long,
        expectedState: ProjectLifecycleState,
        targetState: ProjectLifecycleState,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE project
            SET lifecycle_state = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND lifecycle_state = ?
            """.trimIndent(),
            targetState.name,
            at.atOffset(ZoneOffset.UTC),
            projectId,
            expectedVersion,
            expectedState.name,
        ) == 1

    override fun bumpProjectVersion(
        projectId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE project
            SET updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            projectId,
            expectedVersion,
        ) == 1

    override fun principal(
        organizationId: UUID,
        input: ProjectPrincipalInput,
        at: Instant,
    ): ProjectPrincipalContext? =
        when (input.principalType) {
            ProjectResponsibilityPrincipalType.EMPLOYEE ->
                employeePrincipal(
                    organizationId,
                    input.principalId,
                    at,
                )

            ProjectResponsibilityPrincipalType.TEAM ->
                teamPrincipal(
                    organizationId,
                    input.principalId,
                )
        }

    override fun currentResponsibility(
        projectId: UUID,
        at: Instant,
    ): ProjectResponsibilitySnapshot? {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT
                pr.id,
                pr.project_id,
                pr.organization_id,
                pr.responsibility_key,
                pr.principal_type,
                pr.principal_employee_id,
                pr.principal_team_id,
                pr.effective_from,
                pr.version,
                COALESCE(p.display_name, t.name)
                    AS principal_label,
                linked_identity.id AS linked_identity_id
            FROM project_responsibility pr
            LEFT JOIN employee e
              ON e.id = pr.principal_employee_id
            LEFT JOIN person p
              ON p.id = e.person_id
            LEFT JOIN team t
              ON t.id = pr.principal_team_id
            LEFT JOIN LATERAL (
                SELECT ui.id
                FROM user_identity ui
                JOIN organization_membership om
                  ON om.user_identity_id = ui.id
                WHERE ui.person_id = e.person_id
                  AND ui.status = 'ACTIVE'
                  AND om.organization_id = pr.organization_id
                  AND om.state = 'ACTIVE'
                  AND om.valid_from <= ?
                  AND (
                      om.valid_until IS NULL
                      OR om.valid_until >= ?
                  )
                ORDER BY ui.id
                LIMIT 1
            ) linked_identity ON true
            WHERE pr.project_id = ?
              AND pr.responsibility_key = 'PROJECT_MANAGER'
              AND pr.state = 'ACTIVE'
            """.trimIndent(),
            { rs, _ ->
                val type =
                    ProjectResponsibilityPrincipalType.valueOf(
                        rs.getString("principal_type"),
                    )
                val linked =
                    rs.getObject(
                        "linked_identity_id",
                        UUID::class.java,
                    )
                ProjectResponsibilitySnapshot(
                    responsibilityId =
                        rs.getObject("id", UUID::class.java),
                    projectId =
                        rs.getObject(
                            "project_id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    responsibilityKey =
                        rs.getString("responsibility_key"),
                    principalType = type,
                    principalId =
                        when (type) {
                            ProjectResponsibilityPrincipalType.EMPLOYEE ->
                                rs.getObject(
                                    "principal_employee_id",
                                    UUID::class.java,
                                )
                            ProjectResponsibilityPrincipalType.TEAM ->
                                rs.getObject(
                                    "principal_team_id",
                                    UUID::class.java,
                                )
                        },
                    principalLabel =
                        rs.getString("principal_label"),
                    linkedUserIdentityId = linked,
                    resolutionState =
                        when (type) {
                            ProjectResponsibilityPrincipalType.TEAM ->
                                ProjectResponsibilityResolutionState.TEAM
                            ProjectResponsibilityPrincipalType.EMPLOYEE ->
                                if (linked != null) {
                                    ProjectResponsibilityResolutionState.RESOLVED_USER
                                } else {
                                    ProjectResponsibilityResolutionState.BUSINESS_ONLY_NO_LOGIN
                                }
                        },
                    effectiveFrom =
                        rs.getObject(
                            "effective_from",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            timestamp,
            timestamp,
            projectId,
        ).singleOrNull()
    }

    override fun insertInitialResponsibility(
        responsibilityId: UUID,
        projectId: UUID,
        organizationId: UUID,
        principal: ProjectPrincipalContext,
        operationId: UUID,
        actorUserId: UUID,
        at: Instant,
    ): ProjectResponsibilitySnapshot {
        insertResponsibility(
            responsibilityId,
            projectId,
            organizationId,
            principal,
            operationId,
            actorUserId,
            reason = null,
            at = at,
        )
        return requireNotNull(
            currentResponsibility(
                projectId,
                at,
            ),
        )
    }

    override fun replaceResponsibility(
        responsibilityId: UUID,
        projectId: UUID,
        organizationId: UUID,
        principal: ProjectPrincipalContext,
        operationId: UUID,
        actorUserId: UUID,
        reason: String?,
        at: Instant,
    ): ResponsibilityChangeResult {
        val previous =
            currentResponsibility(
                projectId,
                at,
            )

        if (previous != null) {
            jdbc.update(
                """
                UPDATE project_responsibility
                SET state = 'ENDED',
                    effective_to = ?,
                    version = version + 1
                WHERE id = ?
                  AND state = 'ACTIVE'
                """.trimIndent(),
                at.atOffset(ZoneOffset.UTC),
                previous.responsibilityId,
            )
        }

        insertResponsibility(
            responsibilityId,
            projectId,
            organizationId,
            principal,
            operationId,
            actorUserId,
            reason,
            at,
        )

        return ResponsibilityChangeResult(
            previous =
                previous?.copy(
                    version = previous.version + 1,
                ),
            current =
                requireNotNull(
                    currentResponsibility(
                        projectId,
                        at,
                    ),
                ),
        )
    }

    override fun siteCodeExists(
        organizationId: UUID,
        clientOrganizationId: UUID,
        siteCode: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM site
                WHERE organization_id = ?
                  AND client_organization_id = ?
                  AND site_code = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
            clientOrganizationId,
            siteCode,
        ) == true

    override fun insertSite(
        siteId: UUID,
        organizationId: UUID,
        clientOrganizationId: UUID,
        siteCode: String,
        name: String,
        addressText: String?,
        latitude: Double?,
        longitude: Double?,
        timezone: String?,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO site (
                id,
                organization_id,
                client_organization_id,
                site_code,
                name,
                address_text,
                latitude,
                longitude,
                timezone,
                status,
                created_at,
                created_by,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                'ACTIVE',
                ?, ?, ?,
                1
            )
            """.trimIndent(),
            siteId,
            organizationId,
            clientOrganizationId,
            siteCode,
            name,
            addressText,
            latitude,
            longitude,
            timezone,
            timestamp,
            actorUserId,
            timestamp,
        )
    }

    override fun site(
        siteId: UUID,
    ): SiteSnapshot? =
        jdbc.query(
            siteSelect + "\nWHERE s.id = ?",
            siteMapper,
            siteId,
        ).singleOrNull()

    override fun updateSite(
        siteId: UUID,
        expectedVersion: Long,
        name: String,
        addressText: String?,
        latitude: Double?,
        longitude: Double?,
        timezone: String?,
        status: SiteStatus,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE site
            SET name = ?,
                address_text = ?,
                latitude = ?,
                longitude = ?,
                timezone = ?,
                status = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            name,
            addressText,
            latitude,
            longitude,
            timezone,
            status.name,
            at.atOffset(ZoneOffset.UTC),
            siteId,
            expectedVersion,
        ) == 1

    override fun attachSite(
        projectSiteId: UUID,
        organizationId: UUID,
        projectId: UUID,
        siteId: UUID,
        projectSiteCode: String?,
        accessInstructions: String?,
        projectSpecificNotes: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO project_site (
                id,
                organization_id,
                project_id,
                site_id,
                project_site_code,
                lifecycle_state,
                access_instructions,
                project_specific_notes,
                active_from,
                active_until,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                ?,
                'PLANNED',
                ?, ?,
                NULL, NULL,
                1
            )
            """.trimIndent(),
            projectSiteId,
            organizationId,
            projectId,
            siteId,
            projectSiteCode,
            accessInstructions,
            projectSpecificNotes,
        )
    }

    override fun projectSite(
        projectSiteId: UUID,
    ): ProjectSiteSnapshot? =
        loadProjectSites(
            whereSql = "ps.id = ?",
            args = arrayOf(projectSiteId),
        ).singleOrNull()

    override fun listProjectSites(
        projectId: UUID,
    ): List<ProjectSiteSnapshot> =
        loadProjectSites(
            whereSql = "ps.project_id = ?",
            args = arrayOf(projectId),
        )

    override fun projectSiteExists(
        projectId: UUID,
        siteId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM project_site
                WHERE project_id = ?
                  AND site_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            projectId,
            siteId,
        ) == true

    private fun employeePrincipal(
        organizationId: UUID,
        employeeId: UUID,
        at: Instant,
    ): ProjectPrincipalContext? {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        return jdbc.query(
            """
            SELECT
                e.id,
                p.display_name,
                (
                    SELECT ui.id
                    FROM user_identity ui
                    JOIN organization_membership om
                      ON om.user_identity_id = ui.id
                    WHERE ui.person_id = e.person_id
                      AND ui.status = 'ACTIVE'
                      AND om.organization_id = e.organization_id
                      AND om.state = 'ACTIVE'
                      AND om.valid_from <= ?
                      AND (
                          om.valid_until IS NULL
                          OR om.valid_until >= ?
                      )
                    ORDER BY ui.id
                    LIMIT 1
                ) AS linked_identity_id
            FROM employee e
            JOIN person p
              ON p.id = e.person_id
            WHERE e.id = ?
              AND e.organization_id = ?
              AND e.state IN ('PREBOARDING','ACTIVE')
            """.trimIndent(),
            { rs, _ ->
                ProjectPrincipalContext(
                    principalType =
                        ProjectResponsibilityPrincipalType.EMPLOYEE,
                    principalId =
                        rs.getObject("id", UUID::class.java),
                    label =
                        rs.getString("display_name"),
                    linkedUserIdentityId =
                        rs.getObject(
                            "linked_identity_id",
                            UUID::class.java,
                        ),
                )
            },
            timestamp,
            timestamp,
            employeeId,
            organizationId,
        ).singleOrNull()
    }

    private fun teamPrincipal(
        organizationId: UUID,
        teamId: UUID,
    ): ProjectPrincipalContext? =
        jdbc.query(
            """
            SELECT id, name
            FROM team
            WHERE id = ?
              AND organization_id = ?
              AND active = true
            """.trimIndent(),
            { rs, _ ->
                ProjectPrincipalContext(
                    principalType =
                        ProjectResponsibilityPrincipalType.TEAM,
                    principalId =
                        rs.getObject("id", UUID::class.java),
                    label =
                        rs.getString("name"),
                    linkedUserIdentityId = null,
                )
            },
            teamId,
            organizationId,
        ).singleOrNull()

    private fun insertResponsibility(
        responsibilityId: UUID,
        projectId: UUID,
        organizationId: UUID,
        principal: ProjectPrincipalContext,
        operationId: UUID,
        actorUserId: UUID,
        reason: String?,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO project_responsibility (
                id,
                project_id,
                organization_id,
                responsibility_key,
                principal_type,
                principal_employee_id,
                principal_team_id,
                state,
                effective_from,
                effective_to,
                assigned_by_user_id,
                source_operation_id,
                reason,
                created_at,
                version
            )
            VALUES (
                ?, ?, ?,
                'PROJECT_MANAGER',
                ?,
                ?, ?,
                'ACTIVE',
                ?, NULL,
                ?, ?,
                ?, ?,
                1
            )
            """.trimIndent(),
            responsibilityId,
            projectId,
            organizationId,
            principal.principalType.name,
            principal.principalId.takeIf {
                principal.principalType ==
                    ProjectResponsibilityPrincipalType.EMPLOYEE
            },
            principal.principalId.takeIf {
                principal.principalType ==
                    ProjectResponsibilityPrincipalType.TEAM
            },
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            operationId,
            reason,
            at.atOffset(ZoneOffset.UTC),
        )
    }

    private fun loadProjectById(
        projectId: UUID,
        at: Instant,
    ): ProjectSnapshot? =
        jdbc.query(
            """
            SELECT
                p.id,
                p.organization_id,
                p.project_code,
                p.name,
                p.client_organization_id,
                client.display_name AS client_display_name,
                p.source_type,
                p.source_external_reference,
                p.lifecycle_state,
                p.start_date_planned,
                p.end_date_planned,
                p.baseline_version,
                p.created_at,
                p.updated_at,
                p.version,
                (
                    SELECT COUNT(*)
                    FROM project_site ps
                    WHERE ps.project_id = p.id
                ) AS site_count
            FROM project p
            JOIN organization client
              ON client.id = p.client_organization_id
            WHERE p.id = ?
            """.trimIndent(),
            { rs, _ ->
                ProjectSnapshot(
                    projectId =
                        rs.getObject("id", UUID::class.java),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    projectCode =
                        rs.getString("project_code"),
                    name =
                        rs.getString("name"),
                    clientOrganizationId =
                        rs.getObject(
                            "client_organization_id",
                            UUID::class.java,
                        ),
                    clientDisplayName =
                        rs.getString("client_display_name"),
                    sourceType =
                        ProjectSourceType.valueOf(
                            rs.getString("source_type"),
                        ),
                    sourceExternalReference =
                        rs.getString(
                            "source_external_reference",
                        ),
                    lifecycleState =
                        ProjectLifecycleState.valueOf(
                            rs.getString("lifecycle_state"),
                        ),
                    responsibility = null,
                    startDatePlanned =
                        rs.getObject(
                            "start_date_planned",
                            LocalDate::class.java,
                        ),
                    endDatePlanned =
                        rs.getObject(
                            "end_date_planned",
                            LocalDate::class.java,
                        ),
                    baselineVersion =
                        rs.getInt("baseline_version"),
                    siteCount =
                        rs.getInt("site_count"),
                    createdAt =
                        rs.getObject(
                            "created_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    updatedAt =
                        rs.getObject(
                            "updated_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            projectId,
        ).singleOrNull()
            ?.let {
                it.copy(
                    responsibility =
                        currentResponsibility(
                            it.projectId,
                            at,
                        ),
                )
            }

    private fun loadProjectSites(
        whereSql: String,
        args: Array<out Any>,
    ): List<ProjectSiteSnapshot> =
        jdbc.query(
            """
            SELECT
                ps.id,
                ps.organization_id,
                ps.project_id,
                ps.site_id,
                ps.project_site_code,
                ps.lifecycle_state,
                ps.access_instructions,
                ps.project_specific_notes,
                ps.active_from,
                ps.active_until,
                ps.version
            FROM project_site ps
            WHERE $whereSql
            ORDER BY ps.project_site_code NULLS LAST, ps.id
            """.trimIndent(),
            { rs, _ ->
                ProjectSiteSnapshot(
                    projectSiteId =
                        rs.getObject("id", UUID::class.java),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    projectId =
                        rs.getObject(
                            "project_id",
                            UUID::class.java,
                        ),
                    site =
                        requireNotNull(
                            site(
                                rs.getObject(
                                    "site_id",
                                    UUID::class.java,
                                ),
                            ),
                        ),
                    projectSiteCode =
                        rs.getString("project_site_code"),
                    lifecycleState =
                        ProjectSiteLifecycleState.valueOf(
                            rs.getString("lifecycle_state"),
                        ),
                    accessInstructions =
                        rs.getString("access_instructions"),
                    projectSpecificNotes =
                        rs.getString("project_specific_notes"),
                    activeFrom =
                        rs.getObject(
                            "active_from",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    activeUntil =
                        rs.getObject(
                            "active_until",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    version =
                        rs.getLong("version"),
                )
            },
            *args,
        )

    private val siteSelect =
        """
        SELECT
            s.id,
            s.organization_id,
            s.client_organization_id,
            client.display_name AS client_display_name,
            s.site_code,
            s.name,
            s.address_text,
            s.latitude,
            s.longitude,
            s.timezone,
            s.status,
            s.created_at,
            s.updated_at,
            s.version
        FROM site s
        JOIN organization client
          ON client.id = s.client_organization_id
        """.trimIndent()

    private val siteMapper =
        { rs: java.sql.ResultSet, _: Int ->
            SiteSnapshot(
                siteId =
                    rs.getObject("id", UUID::class.java),
                organizationId =
                    rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                clientOrganizationId =
                    rs.getObject(
                        "client_organization_id",
                        UUID::class.java,
                    ),
                clientDisplayName =
                    rs.getString("client_display_name"),
                siteCode =
                    rs.getString("site_code"),
                name =
                    rs.getString("name"),
                addressText =
                    rs.getString("address_text"),
                latitude =
                    rs.getBigDecimal("latitude")
                        ?.toDouble(),
                longitude =
                    rs.getBigDecimal("longitude")
                        ?.toDouble(),
                timezone =
                    rs.getString("timezone"),
                status =
                    SiteStatus.valueOf(
                        rs.getString("status"),
                    ),
                createdAt =
                    rs.getObject(
                        "created_at",
                        OffsetDateTime::class.java,
                    ).toInstant(),
                updatedAt =
                    rs.getObject(
                        "updated_at",
                        OffsetDateTime::class.java,
                    ).toInstant(),
                version =
                    rs.getLong("version"),
            )
        }
}
