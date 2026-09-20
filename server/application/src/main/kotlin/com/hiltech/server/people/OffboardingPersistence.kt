package com.hiltech.server.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class OffboardingEmployeeContext(
    val employeeId: UUID,
    val organizationId: UUID,
    val personId: UUID,
    val employeeCode: String,
    val displayName: String,
    val state: EmployeeState,
    val hireDate: LocalDate?,
    val employeeVersion: Long,
    val activeEmploymentId: UUID?,
    val employmentStartDate: LocalDate?,
    val employmentVersion: Long?,
)

interface OffboardingPersistencePort {
    fun employee(
        employeeId: UUID,
        forUpdate: Boolean = false,
    ): OffboardingEmployeeContext?

    fun case(
        caseId: UUID,
        forUpdate: Boolean = false,
    ): OffboardingCaseRecord?

    fun byOperationId(
        operationId: UUID,
    ): OffboardingCaseRecord?

    fun latestForEmployee(
        employeeId: UUID,
    ): OffboardingCaseRecord?

    fun openForEmployee(
        employeeId: UUID,
    ): OffboardingCaseRecord?

    fun insertCase(
        caseId: UUID,
        operationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        lastWorkingDate: LocalDate,
        reasonCategoryCode: String,
        note: String?,
        actorUserId: UUID,
        at: Instant,
    )

    fun insertInitialClearances(
        caseId: UUID,
        accessInitiallyClear: Boolean,
        actorUserId: UUID,
        at: Instant,
    )

    fun clearances(
        caseId: UUID,
    ): List<OffboardingClearanceRecord>

    fun transitionEmployeeToOffboarding(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun bumpCaseVersion(
        caseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun resolveClearance(
        caseId: UUID,
        type: OffboardingClearanceType,
        resolution: OffboardingClearanceState,
        source: OffboardingClearanceSource,
        reason: String?,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun endEmployment(
        employmentId: UUID,
        expectedVersion: Long,
        endDate: LocalDate,
        at: Instant,
    ): Boolean

    fun transitionEmployeeToFormer(
        employeeId: UUID,
        expectedVersion: Long,
        endDate: LocalDate,
        at: Instant,
    ): Boolean

    fun completeCase(
        caseId: UUID,
        expectedVersion: Long,
        actorUserId: UUID,
        at: Instant,
    ): Boolean
}

@Component
class JdbcOffboardingPersistence(
    private val jdbc: JdbcTemplate,
) : OffboardingPersistencePort {
    override fun employee(
        employeeId: UUID,
        forUpdate: Boolean,
    ): OffboardingEmployeeContext? =
        jdbc.query(
            """
            SELECT
                e.id AS employee_id,
                e.organization_id,
                e.person_id,
                e.employee_code,
                p.display_name,
                e.state AS employee_state,
                e.hire_date,
                e.version AS employee_version,
                em.id AS employment_id,
                em.start_date AS employment_start_date,
                em.version AS employment_version
            FROM employee e
            JOIN person p
              ON p.id = e.person_id
            LEFT JOIN employment em
              ON em.employee_id = e.id
             AND em.state = 'ACTIVE'
            WHERE e.id = ?
            ${if (forUpdate) "FOR UPDATE OF e" else ""}
            """.trimIndent(),
            { rs, _ ->
                OffboardingEmployeeContext(
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
                    displayName =
                        rs.getString(
                            "display_name",
                        ),
                    state =
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
                    employeeVersion =
                        rs.getLong(
                            "employee_version",
                        ),
                    activeEmploymentId =
                        rs.getObject(
                            "employment_id",
                            UUID::class.java,
                        ),
                    employmentStartDate =
                        rs.getObject(
                            "employment_start_date",
                            LocalDate::class.java,
                        ),
                    employmentVersion =
                        rs.getObject(
                            "employment_version",
                            java.lang.Long::class.java,
                        )?.toLong(),
                )
            },
            employeeId,
        ).singleOrNull()

    override fun case(
        caseId: UUID,
        forUpdate: Boolean,
    ): OffboardingCaseRecord? =
        queryCase(
            predicate = "oc.id = ?",
            args = arrayOf(caseId),
            suffix =
                if (forUpdate) {
                    "FOR UPDATE OF oc"
                } else {
                    ""
                },
        )

    override fun byOperationId(
        operationId: UUID,
    ): OffboardingCaseRecord? =
        queryCase(
            predicate =
                "oc.operation_id = ?",
            args =
                arrayOf(operationId),
        )

    override fun latestForEmployee(
        employeeId: UUID,
    ): OffboardingCaseRecord? =
        queryCase(
            predicate =
                "oc.employee_id = ?",
            args =
                arrayOf(employeeId),
            suffix =
                """
                ORDER BY
                    oc.created_at DESC,
                    oc.id DESC
                LIMIT 1
                """.trimIndent(),
        )

    override fun openForEmployee(
        employeeId: UUID,
    ): OffboardingCaseRecord? =
        queryCase(
            predicate =
                """
                oc.employee_id = ?
                AND oc.state = 'OPEN'
                """.trimIndent(),
            args =
                arrayOf(employeeId),
        )

    override fun insertCase(
        caseId: UUID,
        operationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        lastWorkingDate: LocalDate,
        reasonCategoryCode: String,
        note: String?,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO offboarding_case (
                id,
                operation_id,
                organization_id,
                employee_id,
                state,
                last_working_date,
                reason_category_code,
                note,
                started_at,
                started_by_user_id,
                completed_at,
                completed_by_user_id,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                'OPEN',
                ?, ?, ?,
                ?, ?,
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            caseId,
            operationId,
            organizationId,
            employeeId,
            lastWorkingDate,
            reasonCategoryCode,
            note,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
        )
    }

    override fun insertInitialClearances(
        caseId: UUID,
        accessInitiallyClear: Boolean,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        OffboardingClearanceType.entries
            .forEach {
                type ->
                val isAccess =
                    type ==
                        OffboardingClearanceType
                            .ACCESS
                val state =
                    if (
                        isAccess &&
                        accessInitiallyClear
                    ) {
                        OffboardingClearanceState
                            .CLEAR
                    } else {
                        OffboardingClearanceState
                            .PENDING
                    }
                val source =
                    if (isAccess) {
                        OffboardingClearanceSource
                            .IDENTITY
                    } else {
                        OffboardingClearanceSource
                            .PEOPLE
                    }

                jdbc.update(
                    """
                    INSERT INTO offboarding_clearance (
                        id,
                        offboarding_case_id,
                        clearance_type,
                        state,
                        reason,
                        resolved_at,
                        resolved_by_user_id,
                        source,
                        version
                    )
                    VALUES (
                        ?, ?, ?, ?,
                        NULL,
                        ?, ?,
                        ?, 1
                    )
                    """.trimIndent(),
                    deterministicClearanceId(
                        caseId,
                        type,
                    ),
                    caseId,
                    type.name,
                    state.name,
                    if (
                        state ==
                        OffboardingClearanceState
                            .PENDING
                    ) {
                        null
                    } else {
                        timestamp
                    },
                    if (
                        state ==
                        OffboardingClearanceState
                            .PENDING
                    ) {
                        null
                    } else {
                        actorUserId
                    },
                    source.name,
                )
            }
    }

    override fun clearances(
        caseId: UUID,
    ): List<OffboardingClearanceRecord> =
        jdbc.query(
            """
            SELECT
                id,
                offboarding_case_id,
                clearance_type,
                state,
                reason,
                resolved_at,
                resolved_by_user_id,
                source,
                version
            FROM offboarding_clearance
            WHERE offboarding_case_id = ?
            ORDER BY clearance_type ASC
            """.trimIndent(),
            { rs, _ ->
                OffboardingClearanceRecord(
                    clearanceId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    caseId =
                        rs.getObject(
                            "offboarding_case_id",
                            UUID::class.java,
                        ),
                    type =
                        OffboardingClearanceType
                            .valueOf(
                                rs.getString(
                                    "clearance_type",
                                ),
                            ),
                    state =
                        OffboardingClearanceState
                            .valueOf(
                                rs.getString(
                                    "state",
                                ),
                            ),
                    reason =
                        rs.getString("reason"),
                    resolvedAt =
                        rs.getObject(
                            "resolved_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    resolvedByUserId =
                        rs.getObject(
                            "resolved_by_user_id",
                            UUID::class.java,
                        ),
                    source =
                        OffboardingClearanceSource
                            .valueOf(
                                rs.getString(
                                    "source",
                                ),
                            ),
                    version =
                        rs.getLong("version"),
                )
            },
            caseId,
        )

    override fun transitionEmployeeToOffboarding(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee
            SET state = 'OFFBOARDING',
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'ACTIVE'
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            employeeId,
            expectedVersion,
        ) == 1

    override fun bumpCaseVersion(
        caseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE offboarding_case
            SET updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'OPEN'
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            caseId,
            expectedVersion,
        ) == 1

    override fun resolveClearance(
        caseId: UUID,
        type: OffboardingClearanceType,
        resolution: OffboardingClearanceState,
        source: OffboardingClearanceSource,
        reason: String?,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE offboarding_clearance
            SET state = ?,
                reason = ?,
                resolved_at = ?,
                resolved_by_user_id = ?,
                source = ?,
                version = version + 1
            WHERE offboarding_case_id = ?
              AND clearance_type = ?
            """.trimIndent(),
            resolution.name,
            reason,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            source.name,
            caseId,
            type.name,
        ) == 1

    override fun endEmployment(
        employmentId: UUID,
        expectedVersion: Long,
        endDate: LocalDate,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employment
            SET state = 'ENDED',
                end_date = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'ACTIVE'
              AND version = ?
            """.trimIndent(),
            endDate,
            at.atOffset(ZoneOffset.UTC),
            employmentId,
            expectedVersion,
        ) == 1

    override fun transitionEmployeeToFormer(
        employeeId: UUID,
        expectedVersion: Long,
        endDate: LocalDate,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee
            SET state = 'FORMER',
                end_date = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'OFFBOARDING'
              AND version = ?
            """.trimIndent(),
            endDate,
            at.atOffset(ZoneOffset.UTC),
            employeeId,
            expectedVersion,
        ) == 1

    override fun completeCase(
        caseId: UUID,
        expectedVersion: Long,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE offboarding_case
            SET state = 'COMPLETED',
                completed_at = ?,
                completed_by_user_id = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'OPEN'
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            caseId,
            expectedVersion,
        ) == 1

    private fun queryCase(
        predicate: String,
        args: Array<Any>,
        suffix: String = "",
    ): OffboardingCaseRecord? =
        jdbc.query(
            """
            SELECT
                oc.id,
                oc.operation_id,
                oc.organization_id,
                oc.employee_id,
                oc.state,
                oc.last_working_date,
                oc.reason_category_code,
                oc.note,
                oc.started_at,
                oc.started_by_user_id,
                oc.completed_at,
                oc.completed_by_user_id,
                oc.version
            FROM offboarding_case oc
            WHERE $predicate
            $suffix
            """.trimIndent(),
            { rs, _ ->
                OffboardingCaseRecord(
                    caseId =
                        rs.getObject(
                            "id",
                            UUID::class.java,
                        ),
                    operationId =
                        rs.getObject(
                            "operation_id",
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
                    state =
                        OffboardingCaseState
                            .valueOf(
                                rs.getString("state"),
                            ),
                    lastWorkingDate =
                        rs.getObject(
                            "last_working_date",
                            LocalDate::class.java,
                        ),
                    reasonCategoryCode =
                        rs.getString(
                            "reason_category_code",
                        ),
                    note =
                        rs.getString("note"),
                    startedAt =
                        rs.getObject(
                            "started_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    startedByUserId =
                        rs.getObject(
                            "started_by_user_id",
                            UUID::class.java,
                        ),
                    completedAt =
                        rs.getObject(
                            "completed_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    completedByUserId =
                        rs.getObject(
                            "completed_by_user_id",
                            UUID::class.java,
                        ),
                    version =
                        rs.getLong("version"),
                )
            },
            *args,
        ).singleOrNull()

    private fun deterministicClearanceId(
        caseId: UUID,
        type: OffboardingClearanceType,
    ): UUID =
        UUID.nameUUIDFromBytes(
            (
                "offboarding-clearance:" +
                    caseId +
                    ":" +
                    type.name
            ).toByteArray(
                Charsets.UTF_8,
            ),
        )
}
