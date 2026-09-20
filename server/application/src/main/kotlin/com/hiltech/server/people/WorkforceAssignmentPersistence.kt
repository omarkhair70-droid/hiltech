package com.hiltech.server.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class WorkforceAssignmentEmployeeContext(
    val employeeId: UUID,
    val organizationId: UUID,
    val personId: UUID,
    val employeeState: EmployeeState,
    val employeeVersion: Long,
)

data class WorkforceAssignmentTeamContext(
    val teamId: UUID,
    val organizationId: UUID,
    val code: String,
    val name: String,
    val active: Boolean,
)

interface WorkforceAssignmentPersistencePort {
    fun employee(
        employeeId: UUID,
    ): WorkforceAssignmentEmployeeContext?

    fun lockEmployee(
        employeeId: UUID,
    ): WorkforceAssignmentEmployeeContext?

    fun lockOrganization(
        organizationId: UUID,
    ): Boolean

    fun team(
        teamId: UUID,
    ): WorkforceAssignmentTeamContext?

    fun hasActiveAssignment(
        employeeId: UUID,
    ): Boolean

    fun wouldCreateReportingCycle(
        employeeId: UUID,
        reportsToEmployeeId: UUID,
    ): Boolean

    fun insertAssignment(
        assignmentId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        teamId: UUID?,
        roleCode: String,
        roleLabel: String?,
        reportsToEmployeeId: UUID?,
        effectiveFrom: Instant,
        createdAt: Instant,
    )

    fun loadAssignment(
        assignmentId: UUID,
    ): WorkforceAssignmentSnapshot?

    fun currentForEmployee(
        employeeId: UUID,
        at: Instant,
    ): WorkforceAssignmentSnapshot?

    fun currentAssignmentIdForEmployee(
        employeeId: UUID,
        at: Instant,
    ): UUID?

    fun ownAssignment(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): WorkforceAssignmentSnapshot?

    fun organizationStructure(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<WorkforceAssignmentSnapshot>
}

@Component
class JdbcWorkforceAssignmentPersistence(
    private val jdbc: JdbcTemplate,
) : WorkforceAssignmentPersistencePort {
    override fun employee(
        employeeId: UUID,
    ): WorkforceAssignmentEmployeeContext? =
        loadEmployeeContext(
            employeeId = employeeId,
            forUpdate = false,
        )

    override fun lockEmployee(
        employeeId: UUID,
    ): WorkforceAssignmentEmployeeContext? =
        loadEmployeeContext(
            employeeId = employeeId,
            forUpdate = true,
        )

    private fun loadEmployeeContext(
        employeeId: UUID,
        forUpdate: Boolean,
    ): WorkforceAssignmentEmployeeContext? =
        jdbc.query(
            """
            SELECT
                e.id,
                e.organization_id,
                e.person_id,
                e.state,
                e.version
            FROM employee e
            WHERE e.id = ?
            ${if (forUpdate) "FOR UPDATE OF e" else ""}
            """.trimIndent(),
            { rs, _ ->
                WorkforceAssignmentEmployeeContext(
                    employeeId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    personId =
                        rs.getObject(
                            "person_id",
                            UUID::class.java,
                        ),
                    employeeState =
                        EmployeeState.valueOf(
                            rs.getString("state"),
                        ),
                    employeeVersion =
                        rs.getLong("version"),
                )
            },
            employeeId,
        ).singleOrNull()

    override fun lockOrganization(
        organizationId: UUID,
    ): Boolean =
        jdbc.query(
            """
            SELECT id
            FROM organization
            WHERE id = ?
              AND status = 'ACTIVE'
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            organizationId,
        ).singleOrNull() != null

    override fun team(
        teamId: UUID,
    ): WorkforceAssignmentTeamContext? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                code,
                name,
                active
            FROM team
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                WorkforceAssignmentTeamContext(
                    teamId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    organizationId =
                        rs.getObject(
                            "organization_id",
                            UUID::class.java,
                        ),
                    code =
                        rs.getString("code"),
                    name =
                        rs.getString("name"),
                    active =
                        rs.getBoolean("active"),
                )
            },
            teamId,
        ).singleOrNull()

    override fun hasActiveAssignment(
        employeeId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM workforce_assignment
                WHERE employee_id = ?
                  AND state = 'ACTIVE'
            )
            """.trimIndent(),
            Boolean::class.java,
            employeeId,
        ) == true

    override fun wouldCreateReportingCycle(
        employeeId: UUID,
        reportsToEmployeeId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            WITH RECURSIVE reporting_chain AS (
                SELECT
                    wa.employee_id,
                    wa.reports_to_employee_id,
                    ARRAY[wa.employee_id]::uuid[]
                        AS path
                FROM workforce_assignment wa
                WHERE wa.employee_id = ?
                  AND wa.state = 'ACTIVE'

                UNION ALL

                SELECT
                    parent.employee_id,
                    parent.reports_to_employee_id,
                    chain.path ||
                        parent.employee_id
                FROM workforce_assignment parent
                JOIN reporting_chain chain
                  ON parent.employee_id =
                     chain.reports_to_employee_id
                WHERE parent.state = 'ACTIVE'
                  AND NOT (
                      parent.employee_id =
                      ANY(chain.path)
                  )
            )
            SELECT EXISTS (
                SELECT 1
                FROM reporting_chain
                WHERE employee_id = ?
                   OR reports_to_employee_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            reportsToEmployeeId,
            employeeId,
            employeeId,
        ) == true

    override fun insertAssignment(
        assignmentId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        teamId: UUID?,
        roleCode: String,
        roleLabel: String?,
        reportsToEmployeeId: UUID?,
        effectiveFrom: Instant,
        createdAt: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO workforce_assignment (
                id,
                organization_id,
                employee_id,
                team_id,
                role_code,
                role_label,
                reports_to_employee_id,
                state,
                effective_from,
                effective_to,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                ?, ?, ?,
                'ACTIVE',
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            assignmentId,
            organizationId,
            employeeId,
            teamId,
            roleCode,
            roleLabel,
            reportsToEmployeeId,
            effectiveFrom.atOffset(
                ZoneOffset.UTC,
            ),
            createdAt.atOffset(
                ZoneOffset.UTC,
            ),
            createdAt.atOffset(
                ZoneOffset.UTC,
            ),
        )
    }

    override fun loadAssignment(
        assignmentId: UUID,
    ): WorkforceAssignmentSnapshot? =
        loadBy(
            whereSql = "wa.id = ?",
            args = arrayOf(assignmentId),
        )

    override fun currentForEmployee(
        employeeId: UUID,
        at: Instant,
    ): WorkforceAssignmentSnapshot? =
        loadBy(
            whereSql =
                """
                wa.employee_id = ?
                AND wa.state = 'ACTIVE'
                AND wa.effective_from <= ?
                """.trimIndent(),
            args =
                arrayOf(
                    employeeId,
                    at.atOffset(ZoneOffset.UTC),
                ),
        )

    override fun currentAssignmentIdForEmployee(
        employeeId: UUID,
        at: Instant,
    ): UUID? =
        jdbc.query(
            """
            SELECT id
            FROM workforce_assignment
            WHERE employee_id = ?
              AND state = 'ACTIVE'
              AND effective_from <= ?
            """.trimIndent(),
            { rs, _ ->
                rs.getObject(
                    "id",
                    UUID::class.java,
                )
            },
            employeeId,
            at.atOffset(ZoneOffset.UTC),
        ).singleOrNull()

    override fun ownAssignment(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): WorkforceAssignmentSnapshot? =
        loadBy(
            whereSql =
                """
                wa.organization_id = ?
                AND wa.state = 'ACTIVE'
                AND wa.effective_from <= ?
                AND EXISTS (
                    SELECT 1
                    FROM employee own_employee
                    JOIN user_identity own_identity
                      ON own_identity.person_id =
                         own_employee.person_id
                    JOIN organization_membership own_membership
                      ON own_membership.user_identity_id =
                         own_identity.id
                    WHERE own_employee.id =
                          wa.employee_id
                      AND own_identity.id = ?
                      AND own_identity.status =
                          'ACTIVE'
                      AND own_membership.organization_id =
                          wa.organization_id
                      AND own_membership.state =
                          'ACTIVE'
                      AND own_membership.valid_from <= ?
                      AND (
                          own_membership.valid_until
                              IS NULL
                          OR own_membership.valid_until >= ?
                      )
                )
                """.trimIndent(),
            args =
                arrayOf(
                    organizationId,
                    at.atOffset(
                        ZoneOffset.UTC,
                    ),
                    identityId,
                    at.atOffset(
                        ZoneOffset.UTC,
                    ),
                    at.atOffset(
                        ZoneOffset.UTC,
                    ),
                ),
        )

    override fun organizationStructure(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<WorkforceAssignmentSnapshot> {
        val bounded =
            limit.coerceIn(1, 200)

        return queryMany(
            whereSql =
                """
                wa.organization_id = ?
                AND wa.state = 'ACTIVE'
                AND wa.effective_from <= ?
                """.trimIndent(),
            args =
                arrayOf(
                    organizationId,
                    at.atOffset(
                        ZoneOffset.UTC,
                    ),
                ),
            orderAndLimit =
                """
                ORDER BY
                    e.employee_code ASC,
                    wa.id ASC
                LIMIT $bounded
                """.trimIndent(),
        )
    }

    private fun loadBy(
        whereSql: String,
        args: Array<out Any>,
    ): WorkforceAssignmentSnapshot? =
        queryMany(
            whereSql = whereSql,
            args = args,
            orderAndLimit = "LIMIT 2",
        ).singleOrNull()

    private fun queryMany(
        whereSql: String,
        args: Array<out Any>,
        orderAndLimit: String,
    ): List<WorkforceAssignmentSnapshot> =
        jdbc.query(
            """
            SELECT
                wa.id AS assignment_id,
                wa.organization_id,
                wa.employee_id,
                e.employee_code,
                p.display_name
                    AS employee_display_name,
                wa.team_id,
                t.code AS team_code,
                t.name AS team_name,
                wa.role_code,
                wa.role_label,
                wa.reports_to_employee_id,
                manager.employee_code
                    AS manager_employee_code,
                manager_person.display_name
                    AS manager_display_name,
                wa.state,
                wa.effective_from,
                wa.effective_to,
                wa.version
            FROM workforce_assignment wa
            JOIN employee e
              ON e.id = wa.employee_id
            JOIN person p
              ON p.id = e.person_id
            LEFT JOIN team t
              ON t.id = wa.team_id
            LEFT JOIN employee manager
              ON manager.id =
                 wa.reports_to_employee_id
            LEFT JOIN person manager_person
              ON manager_person.id =
                 manager.person_id
            WHERE $whereSql
            $orderAndLimit
            """.trimIndent(),
            mapper,
            *args,
        )

    private val mapper =
        { rs: java.sql.ResultSet, _: Int ->
            WorkforceAssignmentSnapshot(
                assignmentId =
                    rs.getObject(
                        "assignment_id",
                        UUID::class.java,
                    ),
                organizationId =
                    rs.getObject(
                        "organization_id",
                        UUID::class.java,
                    ),
                employeeId =
                    rs.getObject(
                        "employee_id",
                        UUID::class.java,
                    ),
                employeeCode =
                    rs.getString(
                        "employee_code",
                    ),
                employeeDisplayName =
                    rs.getString(
                        "employee_display_name",
                    ),
                teamId =
                    rs.getObject(
                        "team_id",
                        UUID::class.java,
                    ),
                teamCode =
                    rs.getString("team_code"),
                teamName =
                    rs.getString("team_name"),
                roleCode =
                    rs.getString("role_code"),
                roleLabel =
                    rs.getString("role_label"),
                reportsToEmployeeId =
                    rs.getObject(
                        "reports_to_employee_id",
                        UUID::class.java,
                    ),
                reportsToEmployeeCode =
                    rs.getString(
                        "manager_employee_code",
                    ),
                reportsToDisplayName =
                    rs.getString(
                        "manager_display_name",
                    ),
                state =
                    WorkforceAssignmentState
                        .valueOf(
                            rs.getString("state"),
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
        }
}
