package com.hiltech.server.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class CreateEmployeePersistenceInput(
    val personId: UUID,
    val employeeId: UUID,
    val employmentId: UUID,
    val organizationId: UUID,
    val employeeCode: String,
    val displayName: String,
    val legalName: String?,
    val mobile: String?,
    val email: String?,
    val hireDate: LocalDate?,
    val startDate: LocalDate,
    val employmentTypeCode: String?,
    val createdAt: Instant,
)

data class IdentityPersonLinkSnapshot(
    val identityId: UUID,
    val status: String,
    val personId: UUID?,
)

interface PeoplePersistencePort {
    fun isOrganizationActive(
        organizationId: UUID,
    ): Boolean

    fun employeeCodeExists(
        organizationId: UUID,
        employeeCode: String,
    ): Boolean

    fun identityLink(
        identityId: UUID,
    ): IdentityPersonLinkSnapshot?

    fun createEmployeeCore(
        input: CreateEmployeePersistenceInput,
    )

    fun attachIdentityToPerson(
        identityId: UUID,
        personId: UUID,
    ): Boolean

    fun bumpEmployeeVersion(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun updateProfile(
        employeeId: UUID,
        personId: UUID,
        expectedEmployeeVersion: Long,
        displayName: String,
        legalName: String?,
        mobile: String?,
        email: String?,
        at: Instant,
    ): Boolean

    fun updateOwnContact(
        employeeId: UUID,
        personId: UUID,
        expectedEmployeeVersion: Long,
        expectedPersonVersion: Long,
        mobile: String?,
        email: String?,
        at: Instant,
    ): Boolean

    fun loadEmployee(
        employeeId: UUID,
        at: Instant,
    ): EmployeeAggregateSnapshot?

    fun loadOwnEmployee(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmployeeAggregateSnapshot?

    fun listDirectory(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<EmployeeDirectoryRecord>
}

@Component
class JdbcPeoplePersistence(
    private val jdbc: JdbcTemplate,
) : PeoplePersistencePort {
    override fun isOrganizationActive(
        organizationId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM organization
                WHERE id = ?
                  AND status = 'ACTIVE'
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
        ) == true

    override fun employeeCodeExists(
        organizationId: UUID,
        employeeCode: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM employee
                WHERE organization_id = ?
                  AND employee_code = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            organizationId,
            employeeCode,
        ) == true

    override fun identityLink(
        identityId: UUID,
    ): IdentityPersonLinkSnapshot? =
        jdbc.query(
            """
            SELECT id, status, person_id
            FROM user_identity
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                IdentityPersonLinkSnapshot(
                    identityId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    status =
                        rs.getString("status"),
                    personId =
                        rs.getObject(
                            "person_id",
                            UUID::class.java,
                        ),
                )
            },
            identityId,
        ).singleOrNull()

    override fun createEmployeeCore(
        input: CreateEmployeePersistenceInput,
    ) {
        val at =
            input.createdAt
                .atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO person (
                id,
                display_name,
                legal_name,
                mobile,
                email,
                created_at,
                updated_at,
                version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            """.trimIndent(),
            input.personId,
            input.displayName,
            input.legalName,
            input.mobile,
            input.email,
            at,
            at,
        )

        jdbc.update(
            """
            INSERT INTO employee (
                id,
                organization_id,
                person_id,
                employee_code,
                state,
                hire_date,
                end_date,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                'PREBOARDING',
                ?, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            input.employeeId,
            input.organizationId,
            input.personId,
            input.employeeCode,
            input.hireDate,
            at,
            at,
        )

        jdbc.update(
            """
            INSERT INTO employment (
                id,
                employee_id,
                employment_type_code,
                start_date,
                end_date,
                state,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                NULL,
                'ACTIVE',
                ?, ?, 1
            )
            """.trimIndent(),
            input.employmentId,
            input.employeeId,
            input.employmentTypeCode,
            input.startDate,
            at,
            at,
        )
    }

    override fun attachIdentityToPerson(
        identityId: UUID,
        personId: UUID,
    ): Boolean =
        jdbc.update(
            """
            UPDATE user_identity
            SET person_id = ?,
                version = version + 1
            WHERE id = ?
              AND status = 'ACTIVE'
              AND (
                  person_id IS NULL
                  OR person_id = ?
              )
            """.trimIndent(),
            personId,
            identityId,
            personId,
        ) == 1

    override fun bumpEmployeeVersion(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee
            SET updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            employeeId,
            expectedVersion,
        ) == 1

    override fun updateProfile(
        employeeId: UUID,
        personId: UUID,
        expectedEmployeeVersion: Long,
        displayName: String,
        legalName: String?,
        mobile: String?,
        email: String?,
        at: Instant,
    ): Boolean {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        val employeeUpdated =
            jdbc.update(
                """
                UPDATE employee
                SET updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                """.trimIndent(),
                timestamp,
                employeeId,
                expectedEmployeeVersion,
            ) == 1

        if (!employeeUpdated) {
            return false
        }

        val personUpdated =
            jdbc.update(
                """
                UPDATE person
                SET display_name = ?,
                    legal_name = ?,
                    mobile = ?,
                    email = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                """.trimIndent(),
                displayName,
                legalName,
                mobile,
                email,
                timestamp,
                personId,
            ) == 1

        check(personUpdated) {
            "Employee Person disappeared during profile update."
        }

        return true
    }

    override fun updateOwnContact(
        employeeId: UUID,
        personId: UUID,
        expectedEmployeeVersion: Long,
        expectedPersonVersion: Long,
        mobile: String?,
        email: String?,
        at: Instant,
    ): Boolean {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        val personUpdated =
            jdbc.update(
                """
                UPDATE person
                SET mobile = ?,
                    email = ?,
                    updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND version = ?
                """.trimIndent(),
                mobile,
                email,
                timestamp,
                personId,
                expectedPersonVersion,
            ) == 1

        if (!personUpdated) {
            return false
        }

        val employeeUpdated =
            jdbc.update(
                """
                UPDATE employee
                SET updated_at = ?,
                    version = version + 1
                WHERE id = ?
                  AND person_id = ?
                  AND version = ?
                """.trimIndent(),
                timestamp,
                employeeId,
                personId,
                expectedEmployeeVersion,
            ) == 1

        if (!employeeUpdated) {
            error(
                "Employee changed after own Person contact update inside one command transaction.",
            )
        }

        return true
    }

    override fun loadEmployee(
        employeeId: UUID,
        at: Instant,
    ): EmployeeAggregateSnapshot? =
        loadEmployeeByWhere(
            whereSql = "e.id = ?",
            args = arrayOf(employeeId),
            at = at,
        )

    override fun loadOwnEmployee(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): EmployeeAggregateSnapshot? =
        loadEmployeeByWhere(
            whereSql =
                """
                e.organization_id = ?
                AND EXISTS (
                    SELECT 1
                    FROM user_identity own_ui
                    JOIN organization_membership own_om
                      ON own_om.user_identity_id = own_ui.id
                    WHERE own_ui.id = ?
                      AND own_ui.person_id = e.person_id
                      AND own_ui.status = 'ACTIVE'
                      AND own_om.organization_id = e.organization_id
                      AND own_om.state = 'ACTIVE'
                      AND own_om.valid_from <= ?
                      AND (
                          own_om.valid_until IS NULL
                          OR own_om.valid_until >= ?
                      )
                )
                """.trimIndent(),
            args =
                arrayOf(
                    organizationId,
                    identityId,
                    at.atOffset(ZoneOffset.UTC),
                    at.atOffset(ZoneOffset.UTC),
                ),
            at = at,
        )

    override fun listDirectory(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<EmployeeDirectoryRecord> {
        val boundedLimit =
            limit.coerceIn(1, 100)
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT
                e.id AS employee_id,
                e.employee_code,
                p.display_name,
                e.state,
                EXISTS (
                    SELECT 1
                    FROM user_identity ui
                    JOIN organization_membership om
                      ON om.user_identity_id = ui.id
                    WHERE ui.person_id = p.id
                      AND ui.status = 'ACTIVE'
                      AND om.organization_id = e.organization_id
                      AND om.state = 'ACTIVE'
                      AND om.valid_from <= ?
                      AND (
                          om.valid_until IS NULL
                          OR om.valid_until >= ?
                      )
                ) AS linked_identity,
                active_employment.employment_type_code,
                e.hire_date,
                e.version
            FROM employee e
            JOIN person p
              ON p.id = e.person_id
            LEFT JOIN employment active_employment
              ON active_employment.employee_id = e.id
             AND active_employment.state = 'ACTIVE'
            WHERE e.organization_id = ?
            ORDER BY
                e.employee_code ASC,
                e.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                EmployeeDirectoryRecord(
                    employeeId =
                        rs.getObject(
                            "employee_id",
                            UUID::class.java,
                        ),
                    employeeCode =
                        rs.getString(
                            "employee_code",
                        ),
                    displayName =
                        rs.getString(
                            "display_name",
                        ),
                    employeeState =
                        EmployeeState.valueOf(
                            rs.getString("state"),
                        ),
                    linkedIdentity =
                        rs.getBoolean(
                            "linked_identity",
                        ),
                    currentEmploymentTypeCode =
                        rs.getString(
                            "employment_type_code",
                        ),
                    hireDate =
                        rs.getObject(
                            "hire_date",
                            LocalDate::class.java,
                        ),
                    version =
                        rs.getLong("version"),
                )
            },
            timestamp,
            timestamp,
            organizationId,
            boundedLimit,
        )
    }

    private fun loadEmployeeByWhere(
        whereSql: String,
        args: Array<out Any>,
        at: Instant,
    ): EmployeeAggregateSnapshot? {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)
        val sql =
            """
            SELECT
                e.id AS employee_id,
                e.organization_id,
                e.person_id,
                e.employee_code,
                e.state AS employee_state,
                e.hire_date,
                e.end_date,
                e.version AS employee_version,
                e.created_at AS employee_created_at,
                e.updated_at AS employee_updated_at,
                p.display_name,
                p.legal_name,
                p.mobile,
                p.email,
                p.version AS person_version,
                em.id AS employment_id,
                em.employment_type_code,
                em.start_date AS employment_start_date,
                em.end_date AS employment_end_date,
                em.version AS employment_version,
                (
                    SELECT ui.id
                    FROM user_identity ui
                    JOIN organization_membership om
                      ON om.user_identity_id = ui.id
                    WHERE ui.person_id = p.id
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
            LEFT JOIN employment em
              ON em.employee_id = e.id
             AND em.state = 'ACTIVE'
            WHERE $whereSql
            """.trimIndent()

        val queryArgs =
            arrayOf<Any>(
                timestamp,
                timestamp,
                *args,
            )

        return jdbc.query(
            sql,
            { rs, _ ->
                EmployeeAggregateSnapshot(
                    employeeId =
                        rs.getObject(
                            "employee_id",
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
                    employeeCode =
                        rs.getString(
                            "employee_code",
                        ),
                    employeeState =
                        EmployeeState.valueOf(
                            rs.getString(
                                "employee_state",
                            ),
                        ),
                    hireDate =
                        rs.getObject(
                            "hire_date",
                            LocalDate::class.java,
                        ),
                    endDate =
                        rs.getObject(
                            "end_date",
                            LocalDate::class.java,
                        ),
                    employeeVersion =
                        rs.getLong(
                            "employee_version",
                        ),
                    displayName =
                        rs.getString(
                            "display_name",
                        ),
                    legalName =
                        rs.getString(
                            "legal_name",
                        ),
                    mobile =
                        rs.getString("mobile"),
                    email =
                        rs.getString("email"),
                    personVersion =
                        rs.getLong(
                            "person_version",
                        ),
                    activeEmploymentId =
                        rs.getObject(
                            "employment_id",
                            UUID::class.java,
                        ),
                    employmentTypeCode =
                        rs.getString(
                            "employment_type_code",
                        ),
                    employmentStartDate =
                        rs.getObject(
                            "employment_start_date",
                            LocalDate::class.java,
                        ),
                    employmentEndDate =
                        rs.getObject(
                            "employment_end_date",
                            LocalDate::class.java,
                        ),
                    employmentVersion =
                        rs.getObject(
                            "employment_version",
                            java.lang.Long::class.java,
                        )?.toLong(),
                    linkedIdentityId =
                        rs.getObject(
                            "linked_identity_id",
                            UUID::class.java,
                        ),
                    createdAt =
                        rs.getObject(
                            "employee_created_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    updatedAt =
                        rs.getObject(
                            "employee_updated_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                )
            },
            *queryArgs,
        ).singleOrNull()
    }
}
