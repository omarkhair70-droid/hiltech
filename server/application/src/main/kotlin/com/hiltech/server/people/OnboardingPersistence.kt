package com.hiltech.server.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

interface OnboardingPersistencePort {
    fun employee(employeeId: UUID): OnboardingEmployeeContext?

    fun ownEmployee(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): OnboardingEmployeeContext?

    fun policy(configRevisionId: UUID): OnboardingPolicyContext?

    fun policyRequirements(
        configRevisionId: UUID,
    ): List<OnboardingRequirementDefinition>

    fun loadCase(caseId: UUID): OnboardingCaseRecord?

    fun openCaseForEmployee(
        employeeId: UUID,
    ): OnboardingCaseRecord?

    fun latestCaseForEmployee(
        employeeId: UUID,
    ): OnboardingCaseRecord?

    fun insertCase(
        caseId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        policyConfigRevisionId: UUID,
        actorUserId: UUID,
        at: Instant,
    )

    fun manualResolution(
        caseId: UUID,
        requirementKey: String,
    ): OnboardingManualResolution?

    fun upsertManualResolution(
        resolutionId: UUID,
        caseId: UUID,
        requirementKey: String,
        resolution: OnboardingManualResolutionType,
        reason: String?,
        actorUserId: UUID,
        at: Instant,
    )

    fun bumpCaseVersion(
        caseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun identityReady(
        employeeId: UUID,
        organizationId: UUID,
        at: Instant,
    ): Boolean

    fun hasCurrentWorkforceAssignment(
        employeeId: UUID,
        at: Instant,
    ): Boolean

    fun documentFacts(
        employeeId: UUID,
        documentTypeCode: String,
    ): List<OnboardingDocumentFact>

    fun certificationFacts(
        employeeId: UUID,
        certificationTypeCode: String,
    ): List<OnboardingCertificationFact>

    fun activateEmployee(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean

    fun activateCase(
        caseId: UUID,
        expectedVersion: Long,
        actorUserId: UUID,
        at: Instant,
    ): Boolean
}

@Component
class JdbcOnboardingPersistence(
    private val jdbc: JdbcTemplate,
) : OnboardingPersistencePort {
    override fun employee(
        employeeId: UUID,
    ): OnboardingEmployeeContext? =
        queryEmployee(
            "WHERE e.id = ?",
            arrayOf(employeeId),
        )

    override fun ownEmployee(
        identityId: UUID,
        organizationId: UUID,
        at: Instant,
    ): OnboardingEmployeeContext? {
        val timestamp = at.atOffset(ZoneOffset.UTC)

        return queryEmployee(
            """
            JOIN user_identity ui
              ON ui.person_id = e.person_id
            JOIN organization_membership om
              ON om.user_identity_id = ui.id
             AND om.organization_id = e.organization_id
            WHERE ui.id = ?
              AND e.organization_id = ?
              AND ui.status = 'ACTIVE'
              AND om.state = 'ACTIVE'
              AND om.valid_from <= ?
              AND (
                  om.valid_until IS NULL
                  OR om.valid_until >= ?
              )
            """.trimIndent(),
            arrayOf(
                identityId,
                organizationId,
                timestamp,
                timestamp,
            ),
        )
    }

    override fun policy(
        configRevisionId: UUID,
    ): OnboardingPolicyContext? =
        jdbc.query(
            """
            SELECT
                cr.id,
                cr.scope_type,
                cr.scope_organization_id,
                cr.lifecycle_state,
                cr.code,
                cr.name,
                cr.revision_number
            FROM config_revision cr
            JOIN onboarding_policy op
              ON op.config_revision_id = cr.id
            WHERE cr.id = ?
              AND cr.family = 'onboarding-policies'
            """.trimIndent(),
            { rs, _ ->
                OnboardingPolicyContext(
                    configRevisionId =
                        rs.getObject("id", UUID::class.java),
                    scopeType = rs.getString("scope_type"),
                    scopeOrganizationId =
                        rs.getObject(
                            "scope_organization_id",
                            UUID::class.java,
                        ),
                    lifecycleState =
                        rs.getString("lifecycle_state"),
                    code = rs.getString("code"),
                    name = rs.getString("name"),
                    revisionNumber =
                        rs.getInt("revision_number"),
                )
            },
            configRevisionId,
        ).singleOrNull()

    override fun policyRequirements(
        configRevisionId: UUID,
    ): List<OnboardingRequirementDefinition> =
        jdbc.query(
            """
            SELECT
                id,
                config_revision_id,
                requirement_key,
                requirement_type,
                label,
                responsibility,
                blocking,
                waiver_allowed,
                self_service_visible,
                employee_may_submit,
                evidence_required,
                profile_field_code,
                document_type_code,
                certification_type_code,
                manual_confirmation_code,
                sort_order
            FROM onboarding_policy_requirement
            WHERE config_revision_id = ?
            ORDER BY sort_order ASC, requirement_key ASC
            """.trimIndent(),
            { rs, _ ->
                OnboardingRequirementDefinition(
                    id =
                        rs.getObject("id", UUID::class.java),
                    configRevisionId =
                        rs.getObject(
                            "config_revision_id",
                            UUID::class.java,
                        ),
                    requirementKey =
                        rs.getString("requirement_key"),
                    requirementType =
                        OnboardingRequirementType.valueOf(
                            rs.getString("requirement_type"),
                        ),
                    label = rs.getString("label"),
                    responsibility =
                        OnboardingResponsibility.valueOf(
                            rs.getString("responsibility"),
                        ),
                    blocking = rs.getBoolean("blocking"),
                    waiverAllowed =
                        rs.getBoolean("waiver_allowed"),
                    selfServiceVisible =
                        rs.getBoolean("self_service_visible"),
                    employeeMaySubmit =
                        rs.getBoolean("employee_may_submit"),
                    evidenceRequired =
                        rs.getBoolean("evidence_required"),
                    profileFieldCode =
                        rs.getString("profile_field_code"),
                    documentTypeCode =
                        rs.getString("document_type_code"),
                    certificationTypeCode =
                        rs.getString(
                            "certification_type_code",
                        ),
                    manualConfirmationCode =
                        rs.getString(
                            "manual_confirmation_code",
                        ),
                    sortOrder = rs.getInt("sort_order"),
                )
            },
            configRevisionId,
        )

    override fun loadCase(
        caseId: UUID,
    ): OnboardingCaseRecord? =
        queryCases(
            "oc.id = ?",
            arrayOf(caseId),
        ).singleOrNull()

    override fun openCaseForEmployee(
        employeeId: UUID,
    ): OnboardingCaseRecord? =
        queryCases(
            "oc.employee_id = ? AND oc.state = 'OPEN'",
            arrayOf(employeeId),
        ).singleOrNull()

    override fun latestCaseForEmployee(
        employeeId: UUID,
    ): OnboardingCaseRecord? =
        queryCases(
            "oc.employee_id = ?",
            arrayOf(employeeId),
            "ORDER BY oc.created_at DESC, oc.id DESC LIMIT 1",
        ).singleOrNull()

    override fun insertCase(
        caseId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        policyConfigRevisionId: UUID,
        actorUserId: UUID,
        at: Instant,
    ) {
        val timestamp = at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO onboarding_case (
                id,
                organization_id,
                employee_id,
                policy_config_revision_id,
                state,
                started_at,
                started_by_user_id,
                activated_at,
                activated_by_user_id,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?,
                'OPEN',
                ?, ?,
                NULL, NULL,
                ?, ?, 1
            )
            """.trimIndent(),
            caseId,
            organizationId,
            employeeId,
            policyConfigRevisionId,
            timestamp,
            actorUserId,
            timestamp,
            timestamp,
        )
    }

    override fun manualResolution(
        caseId: UUID,
        requirementKey: String,
    ): OnboardingManualResolution? =
        jdbc.query(
            """
            SELECT
                id,
                onboarding_case_id,
                requirement_key,
                resolution,
                reason,
                resolved_at,
                resolved_by_user_id,
                version
            FROM onboarding_manual_requirement_resolution
            WHERE onboarding_case_id = ?
              AND requirement_key = ?
            """.trimIndent(),
            { rs, _ ->
                OnboardingManualResolution(
                    resolutionId =
                        rs.getObject("id", UUID::class.java),
                    onboardingCaseId =
                        rs.getObject(
                            "onboarding_case_id",
                            UUID::class.java,
                        ),
                    requirementKey =
                        rs.getString("requirement_key"),
                    resolution =
                        OnboardingManualResolutionType.valueOf(
                            rs.getString("resolution"),
                        ),
                    reason = rs.getString("reason"),
                    resolvedAt =
                        rs.getObject(
                            "resolved_at",
                            OffsetDateTime::class.java,
                        ).toInstant(),
                    resolvedByUserId =
                        rs.getObject(
                            "resolved_by_user_id",
                            UUID::class.java,
                        ),
                    version = rs.getLong("version"),
                )
            },
            caseId,
            requirementKey,
        ).singleOrNull()

    override fun upsertManualResolution(
        resolutionId: UUID,
        caseId: UUID,
        requirementKey: String,
        resolution: OnboardingManualResolutionType,
        reason: String?,
        actorUserId: UUID,
        at: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO onboarding_manual_requirement_resolution (
                id,
                onboarding_case_id,
                requirement_key,
                resolution,
                reason,
                resolved_at,
                resolved_by_user_id,
                version
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT (
                onboarding_case_id,
                requirement_key
            )
            DO UPDATE SET
                resolution = EXCLUDED.resolution,
                reason = EXCLUDED.reason,
                resolved_at = EXCLUDED.resolved_at,
                resolved_by_user_id = EXCLUDED.resolved_by_user_id,
                version =
                    onboarding_manual_requirement_resolution.version + 1
            """.trimIndent(),
            resolutionId,
            caseId,
            requirementKey,
            resolution.name,
            reason,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
        )
    }

    override fun bumpCaseVersion(
        caseId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE onboarding_case
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

    override fun identityReady(
        employeeId: UUID,
        organizationId: UUID,
        at: Instant,
    ): Boolean {
        val timestamp = at.atOffset(ZoneOffset.UTC)

        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM employee e
                JOIN user_identity ui
                  ON ui.person_id = e.person_id
                JOIN organization_membership om
                  ON om.user_identity_id = ui.id
                 AND om.organization_id = e.organization_id
                WHERE e.id = ?
                  AND e.organization_id = ?
                  AND ui.status = 'ACTIVE'
                  AND om.state = 'ACTIVE'
                  AND om.valid_from <= ?
                  AND (
                      om.valid_until IS NULL
                      OR om.valid_until >= ?
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            employeeId,
            organizationId,
            timestamp,
            timestamp,
        ) == true
    }

    override fun hasCurrentWorkforceAssignment(
        employeeId: UUID,
        at: Instant,
    ): Boolean {
        val timestamp = at.atOffset(ZoneOffset.UTC)

        return jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM workforce_assignment wa
                WHERE wa.employee_id = ?
                  AND wa.state = 'ACTIVE'
                  AND wa.effective_from <= ?
                  AND (
                      wa.effective_to IS NULL
                      OR wa.effective_to >= ?
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            employeeId,
            timestamp,
            timestamp,
        ) == true
    }

    override fun documentFacts(
        employeeId: UUID,
        documentTypeCode: String,
    ): List<OnboardingDocumentFact> =
        jdbc.query(
            """
            SELECT
                ed.verification_state,
                ev.storage_state
            FROM employee_document ed
            LEFT JOIN evidence ev
              ON ev.id = ed.evidence_id
            WHERE ed.employee_id = ?
              AND ed.document_type_code = ?
            ORDER BY ed.created_at DESC, ed.id DESC
            """.trimIndent(),
            { rs, _ ->
                OnboardingDocumentFact(
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    evidenceStorageState =
                        rs.getString("storage_state"),
                )
            },
            employeeId,
            documentTypeCode,
        )

    override fun certificationFacts(
        employeeId: UUID,
        certificationTypeCode: String,
    ): List<OnboardingCertificationFact> =
        jdbc.query(
            """
            SELECT
                verification_state,
                issued_at,
                valid_until
            FROM certification
            WHERE employee_id = ?
              AND certification_type_code = ?
            ORDER BY created_at DESC, id DESC
            """.trimIndent(),
            { rs, _ ->
                OnboardingCertificationFact(
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    issuedAt =
                        rs.getObject(
                            "issued_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    validUntil =
                        rs.getObject(
                            "valid_until",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                )
            },
            employeeId,
            certificationTypeCode,
        )

    override fun activateEmployee(
        employeeId: UUID,
        expectedVersion: Long,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee
            SET state = 'ACTIVE',
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND state = 'PREBOARDING'
              AND version = ?
            """.trimIndent(),
            at.atOffset(ZoneOffset.UTC),
            employeeId,
            expectedVersion,
        ) == 1

    override fun activateCase(
        caseId: UUID,
        expectedVersion: Long,
        actorUserId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE onboarding_case
            SET state = 'ACTIVATED',
                activated_at = ?,
                activated_by_user_id = ?,
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

    private fun queryEmployee(
        suffix: String,
        args: Array<Any>,
    ): OnboardingEmployeeContext? =
        jdbc.query(
            """
            SELECT DISTINCT
                e.id AS employee_id,
                e.organization_id,
                e.person_id,
                e.employee_code,
                e.state AS employee_state,
                e.version AS employee_version,
                p.display_name,
                p.mobile,
                p.email,
                p.version AS person_version
            FROM employee e
            JOIN person p
              ON p.id = e.person_id
            $suffix
            """.trimIndent(),
            { rs, _ ->
                OnboardingEmployeeContext(
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
                        rs.getString("employee_code"),
                    employeeState =
                        EmployeeState.valueOf(
                            rs.getString(
                                "employee_state",
                            ),
                        ),
                    employeeVersion =
                        rs.getLong("employee_version"),
                    displayName =
                        rs.getString("display_name"),
                    mobile = rs.getString("mobile"),
                    email = rs.getString("email"),
                    personVersion =
                        rs.getLong("person_version"),
                )
            },
            *args,
        ).singleOrNull()

    private fun queryCases(
        predicate: String,
        args: Array<Any>,
        suffix: String = "",
    ): List<OnboardingCaseRecord> =
        jdbc.query(
            """
            SELECT
                oc.id,
                oc.organization_id,
                oc.employee_id,
                oc.policy_config_revision_id,
                oc.state,
                oc.started_at,
                oc.started_by_user_id,
                oc.activated_at,
                oc.activated_by_user_id,
                oc.version
            FROM onboarding_case oc
            WHERE $predicate
            $suffix
            """.trimIndent(),
            { rs, _ ->
                OnboardingCaseRecord(
                    caseId =
                        rs.getObject("id", UUID::class.java),
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
                    policyConfigRevisionId =
                        rs.getObject(
                            "policy_config_revision_id",
                            UUID::class.java,
                        ),
                    state =
                        OnboardingCaseState.valueOf(
                            rs.getString("state"),
                        ),
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
                    activatedAt =
                        rs.getObject(
                            "activated_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    activatedByUserId =
                        rs.getObject(
                            "activated_by_user_id",
                            UUID::class.java,
                        ),
                    version = rs.getLong("version"),
                )
            },
            *args,
        )
}
