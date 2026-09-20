package com.hiltech.server.projects

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface PlanningPersistencePort {
    fun area(areaId: UUID): AreaSnapshot?
    fun areas(projectId: UUID): List<AreaSnapshot>
    fun insertArea(
        areaId: UUID,
        organizationId: UUID,
        siteId: UUID,
        parentAreaId: UUID?,
        typeCode: String,
        code: String,
        name: String,
        sequence: Int?,
        restrictedAccess: Boolean,
        actorUserId: UUID,
        at: Instant,
    )
    fun updateArea(
        areaId: UUID,
        expectedVersion: Long,
        parentAreaId: UUID?,
        typeCode: String,
        code: String,
        name: String,
        sequence: Int?,
        restrictedAccess: Boolean,
        at: Instant,
    ): Boolean

    fun milestone(milestoneId: UUID): MilestoneSnapshot?
    fun milestones(projectId: UUID): List<MilestoneSnapshot>
    fun insertMilestone(
        milestoneId: UUID,
        project: ProjectSnapshot,
        code: String,
        name: String,
        plannedDate: LocalDate?,
        sequence: Int?,
        clientVisible: Boolean,
        acceptanceRequirement: String?,
        actorUserId: UUID,
        at: Instant,
    )
    fun updateMilestone(
        milestoneId: UUID,
        expectedVersion: Long,
        code: String,
        name: String,
        plannedDate: LocalDate?,
        sequence: Int?,
        clientVisible: Boolean,
        acceptanceRequirement: String?,
        at: Instant,
    ): Boolean

    fun workPackage(workPackageId: UUID): WorkPackageSnapshot?
    fun workPackages(projectId: UUID): List<WorkPackageSnapshot>
    fun insertWorkPackage(
        workPackageId: UUID,
        project: ProjectSnapshot,
        projectSiteId: UUID?,
        siteId: UUID?,
        milestoneId: UUID?,
        code: String,
        name: String,
        description: String?,
        owner: ProjectPrincipalContext?,
        plannedStart: LocalDate?,
        plannedEnd: LocalDate?,
        sequence: Int?,
        actorUserId: UUID,
        at: Instant,
    )
    fun updateWorkPackage(
        workPackageId: UUID,
        expectedVersion: Long,
        projectSiteId: UUID?,
        siteId: UUID?,
        milestoneId: UUID?,
        code: String,
        name: String,
        description: String?,
        owner: ProjectPrincipalContext?,
        plannedStart: LocalDate?,
        plannedEnd: LocalDate?,
        sequence: Int?,
        at: Instant,
    ): Boolean

    fun dependency(dependencyId: UUID): PlanDependencySnapshot?
    fun dependencies(projectId: UUID): List<PlanDependencySnapshot>
    fun insertDependency(
        dependencyId: UUID,
        project: ProjectSnapshot,
        predecessor: PlanNodeRef,
        successor: PlanNodeRef,
        dependencyType: PlanDependencyType,
        lagMinutes: Long,
        actorUserId: UUID,
        at: Instant,
    )
    fun deleteDependency(
        dependencyId: UUID,
        expectedVersion: Long,
    ): Boolean
}

@Component
class JdbcPlanningPersistence(
    private val jdbc: JdbcTemplate,
) : PlanningPersistencePort {
    override fun area(areaId: UUID): AreaSnapshot? =
        jdbc.query(
            areaSelect + "\nWHERE a.id = ?",
            areaMapper,
            areaId,
        ).singleOrNull()

    override fun areas(projectId: UUID): List<AreaSnapshot> =
        jdbc.query(
            areaSelect +
                """
                JOIN project_site ps
                  ON ps.organization_id = a.organization_id
                 AND ps.site_id = a.site_id
                WHERE ps.project_id = ?
                ORDER BY a.site_id, a.sequence NULLS LAST, a.code, a.id
                """.trimIndent(),
            areaMapper,
            projectId,
        )

    override fun insertArea(
        areaId: UUID,
        organizationId: UUID,
        siteId: UUID,
        parentAreaId: UUID?,
        typeCode: String,
        code: String,
        name: String,
        sequence: Int?,
        restrictedAccess: Boolean,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO area (
                id, organization_id, site_id, parent_area_id,
                type_code, code, name, sequence, restricted_access,
                created_at, created_by, updated_at, version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            areaId,
            organizationId,
            siteId,
            parentAreaId,
            typeCode,
            code,
            name,
            sequence,
            restrictedAccess,
            timestamp,
            actorUserId,
            timestamp,
        )
    }

    override fun updateArea(
        areaId: UUID,
        expectedVersion: Long,
        parentAreaId: UUID?,
        typeCode: String,
        code: String,
        name: String,
        sequence: Int?,
        restrictedAccess: Boolean,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE area
            SET parent_area_id = ?,
                type_code = ?,
                code = ?,
                name = ?,
                sequence = ?,
                restricted_access = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            parentAreaId,
            typeCode,
            code,
            name,
            sequence,
            restrictedAccess,
            at.atOffset(ZoneOffset.UTC),
            areaId,
            expectedVersion,
        ) == 1

    override fun milestone(milestoneId: UUID): MilestoneSnapshot? =
        jdbc.query(
            milestoneSelect + "\nWHERE m.id = ?",
            milestoneMapper,
            milestoneId,
        ).singleOrNull()

    override fun milestones(projectId: UUID): List<MilestoneSnapshot> =
        jdbc.query(
            milestoneSelect +
                """
                WHERE m.project_id = ?
                ORDER BY m.sequence NULLS LAST, m.planned_date NULLS LAST, m.code, m.id
                """.trimIndent(),
            milestoneMapper,
            projectId,
        )

    override fun insertMilestone(
        milestoneId: UUID,
        project: ProjectSnapshot,
        code: String,
        name: String,
        plannedDate: LocalDate?,
        sequence: Int?,
        clientVisible: Boolean,
        acceptanceRequirement: String?,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO milestone (
                id, organization_id, project_id, code, name, state,
                planned_date, actual_date, sequence, client_visible,
                acceptance_requirement, baseline_version,
                created_at, created_by, updated_at, version
            )
            VALUES (?, ?, ?, ?, ?, 'PLANNED', ?, NULL, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            milestoneId,
            project.organizationId,
            project.projectId,
            code,
            name,
            plannedDate,
            sequence,
            clientVisible,
            acceptanceRequirement,
            project.baselineVersion,
            timestamp,
            actorUserId,
            timestamp,
        )
    }

    override fun updateMilestone(
        milestoneId: UUID,
        expectedVersion: Long,
        code: String,
        name: String,
        plannedDate: LocalDate?,
        sequence: Int?,
        clientVisible: Boolean,
        acceptanceRequirement: String?,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE milestone
            SET code = ?,
                name = ?,
                planned_date = ?,
                sequence = ?,
                client_visible = ?,
                acceptance_requirement = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND state = 'PLANNED'
            """.trimIndent(),
            code,
            name,
            plannedDate,
            sequence,
            clientVisible,
            acceptanceRequirement,
            at.atOffset(ZoneOffset.UTC),
            milestoneId,
            expectedVersion,
        ) == 1

    override fun workPackage(workPackageId: UUID): WorkPackageSnapshot? =
        jdbc.query(
            workPackageSelect + "\nWHERE wp.id = ?",
            workPackageMapper,
            workPackageId,
        ).singleOrNull()

    override fun workPackages(projectId: UUID): List<WorkPackageSnapshot> =
        jdbc.query(
            workPackageSelect +
                """
                WHERE wp.project_id = ?
                ORDER BY wp.sequence NULLS LAST, wp.planned_start NULLS LAST, wp.code, wp.id
                """.trimIndent(),
            workPackageMapper,
            projectId,
        )

    override fun insertWorkPackage(
        workPackageId: UUID,
        project: ProjectSnapshot,
        projectSiteId: UUID?,
        siteId: UUID?,
        milestoneId: UUID?,
        code: String,
        name: String,
        description: String?,
        owner: ProjectPrincipalContext?,
        plannedStart: LocalDate?,
        plannedEnd: LocalDate?,
        sequence: Int?,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp = at.atOffset(ZoneOffset.UTC)
        jdbc.update(
            """
            INSERT INTO work_package (
                id, organization_id, project_id, project_site_id, site_id,
                milestone_id, code, name, description,
                owner_principal_type, owner_employee_id, owner_team_id,
                state, planned_start, planned_end, sequence, baseline_version,
                created_at, created_by, updated_at, version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?, ?,
                ?, ?, ?,
                'PLANNED', ?, ?, ?, ?,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            workPackageId,
            project.organizationId,
            project.projectId,
            projectSiteId,
            siteId,
            milestoneId,
            code,
            name,
            description,
            owner?.principalType?.name,
            owner?.principalId?.takeIf {
                owner.principalType == ProjectResponsibilityPrincipalType.EMPLOYEE
            },
            owner?.principalId?.takeIf {
                owner.principalType == ProjectResponsibilityPrincipalType.TEAM
            },
            plannedStart,
            plannedEnd,
            sequence,
            project.baselineVersion,
            timestamp,
            actorUserId,
            timestamp,
        )
    }

    override fun updateWorkPackage(
        workPackageId: UUID,
        expectedVersion: Long,
        projectSiteId: UUID?,
        siteId: UUID?,
        milestoneId: UUID?,
        code: String,
        name: String,
        description: String?,
        owner: ProjectPrincipalContext?,
        plannedStart: LocalDate?,
        plannedEnd: LocalDate?,
        sequence: Int?,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE work_package
            SET project_site_id = ?,
                site_id = ?,
                milestone_id = ?,
                code = ?,
                name = ?,
                description = ?,
                owner_principal_type = ?,
                owner_employee_id = ?,
                owner_team_id = ?,
                planned_start = ?,
                planned_end = ?,
                sequence = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
              AND state = 'PLANNED'
            """.trimIndent(),
            projectSiteId,
            siteId,
            milestoneId,
            code,
            name,
            description,
            owner?.principalType?.name,
            owner?.principalId?.takeIf {
                owner.principalType == ProjectResponsibilityPrincipalType.EMPLOYEE
            },
            owner?.principalId?.takeIf {
                owner.principalType == ProjectResponsibilityPrincipalType.TEAM
            },
            plannedStart,
            plannedEnd,
            sequence,
            at.atOffset(ZoneOffset.UTC),
            workPackageId,
            expectedVersion,
        ) == 1

    override fun dependency(dependencyId: UUID): PlanDependencySnapshot? =
        jdbc.query(
            dependencySelect + "\nWHERE d.id = ?",
            dependencyMapper,
            dependencyId,
        ).singleOrNull()

    override fun dependencies(projectId: UUID): List<PlanDependencySnapshot> =
        jdbc.query(
            dependencySelect +
                """
                WHERE d.project_id = ?
                ORDER BY d.created_at, d.id
                """.trimIndent(),
            dependencyMapper,
            projectId,
        )

    override fun insertDependency(
        dependencyId: UUID,
        project: ProjectSnapshot,
        predecessor: PlanNodeRef,
        successor: PlanNodeRef,
        dependencyType: PlanDependencyType,
        lagMinutes: Long,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO project_plan_dependency (
                id, organization_id, project_id,
                predecessor_milestone_id, predecessor_work_package_id,
                successor_milestone_id, successor_work_package_id,
                dependency_type, lag_minutes, created_at, created_by, version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            dependencyId,
            project.organizationId,
            project.projectId,
            predecessor.id.takeIf { predecessor.type == PlanNodeType.MILESTONE },
            predecessor.id.takeIf { predecessor.type == PlanNodeType.WORK_PACKAGE },
            successor.id.takeIf { successor.type == PlanNodeType.MILESTONE },
            successor.id.takeIf { successor.type == PlanNodeType.WORK_PACKAGE },
            dependencyType.name,
            lagMinutes,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
        )
    }

    override fun deleteDependency(
        dependencyId: UUID,
        expectedVersion: Long,
    ): Boolean =
        jdbc.update(
            """
            DELETE FROM project_plan_dependency
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            dependencyId,
            expectedVersion,
        ) == 1

    private val areaSelect =
        """
        SELECT
            a.id, a.organization_id, a.site_id, a.parent_area_id,
            a.type_code, a.code, a.name, a.sequence, a.restricted_access,
            a.created_at, a.updated_at, a.version
        FROM area a
        """.trimIndent()

    private val areaMapper =
        { rs: java.sql.ResultSet, _: Int ->
            AreaSnapshot(
                areaId = rs.getObject("id", UUID::class.java),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                siteId = rs.getObject("site_id", UUID::class.java),
                parentAreaId = rs.getObject("parent_area_id", UUID::class.java),
                typeCode = rs.getString("type_code"),
                code = rs.getString("code"),
                name = rs.getString("name"),
                sequence = rs.getObject("sequence", Int::class.javaObjectType),
                restrictedAccess = rs.getBoolean("restricted_access"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                version = rs.getLong("version"),
            )
        }

    private val milestoneSelect =
        """
        SELECT
            m.id, m.organization_id, m.project_id, m.code, m.name, m.state,
            m.planned_date, m.actual_date, m.sequence, m.client_visible,
            m.acceptance_requirement, m.baseline_version,
            m.created_at, m.updated_at, m.version
        FROM milestone m
        """.trimIndent()

    private val milestoneMapper =
        { rs: java.sql.ResultSet, _: Int ->
            MilestoneSnapshot(
                milestoneId = rs.getObject("id", UUID::class.java),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                projectId = rs.getObject("project_id", UUID::class.java),
                code = rs.getString("code"),
                name = rs.getString("name"),
                state = MilestoneState.valueOf(rs.getString("state")),
                plannedDate = rs.getObject("planned_date", LocalDate::class.java),
                actualDate = rs.getObject("actual_date", LocalDate::class.java),
                sequence = rs.getObject("sequence", Int::class.javaObjectType),
                clientVisible = rs.getBoolean("client_visible"),
                acceptanceRequirement = rs.getString("acceptance_requirement"),
                baselineVersion = rs.getInt("baseline_version"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                version = rs.getLong("version"),
            )
        }

    private val workPackageSelect =
        """
        SELECT
            wp.id, wp.organization_id, wp.project_id, wp.project_site_id,
            wp.site_id, wp.milestone_id, wp.code, wp.name, wp.description,
            wp.owner_principal_type, wp.owner_employee_id, wp.owner_team_id,
            person.display_name AS employee_label,
            team.name AS team_label,
            wp.state, wp.planned_start, wp.planned_end, wp.sequence,
            wp.baseline_version, wp.created_at, wp.updated_at, wp.version
        FROM work_package wp
        LEFT JOIN employee employee
          ON employee.id = wp.owner_employee_id
        LEFT JOIN person person
          ON person.id = employee.person_id
        LEFT JOIN team team
          ON team.id = wp.owner_team_id
        """.trimIndent()

    private val workPackageMapper =
        { rs: java.sql.ResultSet, _: Int ->
            val ownerType = rs.getString("owner_principal_type")
                ?.let(ProjectResponsibilityPrincipalType::valueOf)
            val owner = ownerType?.let { type ->
                WorkPackageOwnerSnapshot(
                    principalType = type,
                    principalId = when (type) {
                        ProjectResponsibilityPrincipalType.EMPLOYEE ->
                            rs.getObject("owner_employee_id", UUID::class.java)
                        ProjectResponsibilityPrincipalType.TEAM ->
                            rs.getObject("owner_team_id", UUID::class.java)
                    },
                    principalLabel = when (type) {
                        ProjectResponsibilityPrincipalType.EMPLOYEE ->
                            rs.getString("employee_label")
                        ProjectResponsibilityPrincipalType.TEAM ->
                            rs.getString("team_label")
                    },
                )
            }
            WorkPackageSnapshot(
                workPackageId = rs.getObject("id", UUID::class.java),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                projectId = rs.getObject("project_id", UUID::class.java),
                projectSiteId = rs.getObject("project_site_id", UUID::class.java),
                siteId = rs.getObject("site_id", UUID::class.java),
                milestoneId = rs.getObject("milestone_id", UUID::class.java),
                code = rs.getString("code"),
                name = rs.getString("name"),
                description = rs.getString("description"),
                owner = owner,
                state = WorkPackageState.valueOf(rs.getString("state")),
                plannedStart = rs.getObject("planned_start", LocalDate::class.java),
                plannedEnd = rs.getObject("planned_end", LocalDate::class.java),
                sequence = rs.getObject("sequence", Int::class.javaObjectType),
                baselineVersion = rs.getInt("baseline_version"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                updatedAt = rs.getObject("updated_at", OffsetDateTime::class.java).toInstant(),
                version = rs.getLong("version"),
            )
        }

    private val dependencySelect =
        """
        SELECT
            d.id, d.organization_id, d.project_id,
            d.predecessor_milestone_id, d.predecessor_work_package_id,
            d.successor_milestone_id, d.successor_work_package_id,
            d.dependency_type, d.lag_minutes, d.created_at, d.version
        FROM project_plan_dependency d
        """.trimIndent()

    private val dependencyMapper =
        { rs: java.sql.ResultSet, _: Int ->
            val predecessorMilestone =
                rs.getObject("predecessor_milestone_id", UUID::class.java)
            val successorMilestone =
                rs.getObject("successor_milestone_id", UUID::class.java)
            PlanDependencySnapshot(
                dependencyId = rs.getObject("id", UUID::class.java),
                organizationId = rs.getObject("organization_id", UUID::class.java),
                projectId = rs.getObject("project_id", UUID::class.java),
                predecessor = PlanNodeRef(
                    type = if (predecessorMilestone != null) {
                        PlanNodeType.MILESTONE
                    } else {
                        PlanNodeType.WORK_PACKAGE
                    },
                    id = predecessorMilestone
                        ?: rs.getObject("predecessor_work_package_id", UUID::class.java),
                ),
                successor = PlanNodeRef(
                    type = if (successorMilestone != null) {
                        PlanNodeType.MILESTONE
                    } else {
                        PlanNodeType.WORK_PACKAGE
                    },
                    id = successorMilestone
                        ?: rs.getObject("successor_work_package_id", UUID::class.java),
                ),
                dependencyType = PlanDependencyType.valueOf(rs.getString("dependency_type")),
                lagMinutes = rs.getLong("lag_minutes"),
                createdAt = rs.getObject("created_at", OffsetDateTime::class.java).toInstant(),
                version = rs.getLong("version"),
            )
        }
}
