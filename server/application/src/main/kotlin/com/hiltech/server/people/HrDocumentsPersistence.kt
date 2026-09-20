package com.hiltech.server.people

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class HrEmployeeContext(
    val employeeId: UUID,
    val organizationId: UUID,
    val personId: UUID,
    val employeeState: EmployeeState,
    val employeeVersion: Long,
)

data class EmployeeDocumentTargetContext(
    val documentId: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val verificationState: HrVerificationState,
    val evidenceId: UUID?,
    val version: Long,
)

interface HrDocumentsPersistencePort {
    fun employee(
        employeeId: UUID,
    ): HrEmployeeContext?

    fun loadDocument(
        documentId: UUID,
    ): EmployeeDocumentSnapshot?

    fun listDocuments(
        employeeId: UUID,
    ): List<EmployeeDocumentSnapshot>

    fun loadCertification(
        certificationId: UUID,
    ): CertificationSnapshot?

    fun listCertifications(
        employeeId: UUID,
    ): List<CertificationSnapshot>

    fun eligibility(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<CertificationEligibilitySnapshot>

    fun insertDocument(
        documentId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        documentTypeCode: String,
        documentLabel: String?,
        issueDate: LocalDate?,
        expiryDate: LocalDate?,
        retentionPolicyCode: String?,
        at: Instant,
    )

    fun verifyDocument(
        documentId: UUID,
        expectedVersion: Long,
        result: HrVerificationState,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun insertCertification(
        certificationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        certificationTypeCode: String,
        certificationLabel: String?,
        issuer: String?,
        issuedAt: Instant?,
        validUntil: Instant?,
        employeeDocumentId: UUID?,
        at: Instant,
    )

    fun verifyCertification(
        certificationId: UUID,
        expectedVersion: Long,
        result: HrVerificationState,
        actorUserId: UUID,
        at: Instant,
    ): Boolean

    fun documentTarget(
        documentId: UUID,
    ): EmployeeDocumentTargetContext?

    fun attachEvidence(
        documentId: UUID,
        evidenceId: UUID,
        at: Instant,
    ): Boolean

    fun evidenceIsReadyForDocument(
        documentId: UUID,
        evidenceId: UUID,
    ): Boolean
}

@Component
class JdbcHrDocumentsPersistence(
    private val jdbc: JdbcTemplate,
) : HrDocumentsPersistencePort {
    override fun employee(
        employeeId: UUID,
    ): HrEmployeeContext? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                person_id,
                state,
                version
            FROM employee
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                HrEmployeeContext(
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

    override fun loadDocument(
        documentId: UUID,
    ): EmployeeDocumentSnapshot? =
        queryDocuments(
            "ed.id = ?",
            arrayOf(documentId),
        ).singleOrNull()

    override fun listDocuments(
        employeeId: UUID,
    ): List<EmployeeDocumentSnapshot> =
        queryDocuments(
            "ed.employee_id = ?",
            arrayOf(employeeId),
            """
            ORDER BY
                ed.document_type_code ASC,
                ed.created_at DESC,
                ed.id ASC
            """.trimIndent(),
        )

    override fun loadCertification(
        certificationId: UUID,
    ): CertificationSnapshot? =
        queryCertifications(
            "c.id = ?",
            arrayOf(certificationId),
        ).singleOrNull()

    override fun listCertifications(
        employeeId: UUID,
    ): List<CertificationSnapshot> =
        queryCertifications(
            "c.employee_id = ?",
            arrayOf(employeeId),
            """
            ORDER BY
                c.certification_type_code ASC,
                c.created_at DESC,
                c.id ASC
            """.trimIndent(),
        )

    override fun eligibility(
        organizationId: UUID,
        at: Instant,
        limit: Int,
    ): List<CertificationEligibilitySnapshot> {
        val bounded =
            limit.coerceIn(1, 500)
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        return jdbc.query(
            """
            SELECT
                e.id AS employee_id,
                e.employee_code,
                p.display_name,
                c.id AS certification_id,
                c.certification_type_code,
                c.certification_label,
                c.verification_state,
                c.valid_until,
                (
                    c.verification_state = 'VERIFIED'
                    AND (
                        c.issued_at IS NULL
                        OR c.issued_at <= ?
                    )
                    AND (
                        c.valid_until IS NULL
                        OR c.valid_until >= ?
                    )
                ) AS valid_now
            FROM certification c
            JOIN employee e
              ON e.id = c.employee_id
            JOIN person p
              ON p.id = e.person_id
            WHERE c.organization_id = ?
            ORDER BY
                e.employee_code ASC,
                c.certification_type_code ASC,
                c.id ASC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                CertificationEligibilitySnapshot(
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
                            "display_name",
                        ),
                    certificationId =
                        rs.getObject(
                            "certification_id",
                            UUID::class.java,
                        ),
                    certificationTypeCode =
                        rs.getString(
                            "certification_type_code",
                        ),
                    certificationLabel =
                        rs.getString(
                            "certification_label",
                        ),
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    validNow =
                        rs.getBoolean(
                            "valid_now",
                        ),
                    validUntil =
                        rs.getObject(
                            "valid_until",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                )
            },
            timestamp,
            timestamp,
            organizationId,
            bounded,
        )
    }

    override fun insertDocument(
        documentId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        documentTypeCode: String,
        documentLabel: String?,
        issueDate: LocalDate?,
        expiryDate: LocalDate?,
        retentionPolicyCode: String?,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO employee_document (
                id,
                organization_id,
                employee_id,
                document_type_code,
                document_label,
                issue_date,
                expiry_date,
                verification_state,
                verified_at,
                verified_by_user_id,
                evidence_id,
                retention_policy_code,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?,
                'UNVERIFIED',
                NULL, NULL, NULL,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            documentId,
            organizationId,
            employeeId,
            documentTypeCode,
            documentLabel,
            issueDate,
            expiryDate,
            retentionPolicyCode,
            timestamp,
            timestamp,
        )
    }

    override fun verifyDocument(
        documentId: UUID,
        expectedVersion: Long,
        result: HrVerificationState,
        actorUserId: UUID,
        at: Instant,
    ): Boolean {
        require(
            result in setOf(
                HrVerificationState.VERIFIED,
                HrVerificationState.REJECTED,
            ),
        )

        return jdbc.update(
            """
            UPDATE employee_document
            SET verification_state = ?,
                verified_at = ?,
                verified_by_user_id = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            result.name,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            documentId,
            expectedVersion,
        ) == 1
    }

    override fun insertCertification(
        certificationId: UUID,
        organizationId: UUID,
        employeeId: UUID,
        certificationTypeCode: String,
        certificationLabel: String?,
        issuer: String?,
        issuedAt: Instant?,
        validUntil: Instant?,
        employeeDocumentId: UUID?,
        at: Instant,
    ) {
        val timestamp =
            at.atOffset(ZoneOffset.UTC)

        jdbc.update(
            """
            INSERT INTO certification (
                id,
                organization_id,
                employee_id,
                certification_type_code,
                certification_label,
                issuer,
                issued_at,
                valid_until,
                verification_state,
                verified_at,
                verified_by_user_id,
                employee_document_id,
                created_at,
                updated_at,
                version
            )
            VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?,
                'UNVERIFIED',
                NULL, NULL,
                ?, ?, ?, 1
            )
            """.trimIndent(),
            certificationId,
            organizationId,
            employeeId,
            certificationTypeCode,
            certificationLabel,
            issuer,
            issuedAt?.atOffset(
                ZoneOffset.UTC,
            ),
            validUntil?.atOffset(
                ZoneOffset.UTC,
            ),
            employeeDocumentId,
            timestamp,
            timestamp,
        )
    }

    override fun verifyCertification(
        certificationId: UUID,
        expectedVersion: Long,
        result: HrVerificationState,
        actorUserId: UUID,
        at: Instant,
    ): Boolean {
        require(
            result in setOf(
                HrVerificationState.VERIFIED,
                HrVerificationState.REJECTED,
            ),
        )

        return jdbc.update(
            """
            UPDATE certification
            SET verification_state = ?,
                verified_at = ?,
                verified_by_user_id = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND version = ?
            """.trimIndent(),
            result.name,
            at.atOffset(ZoneOffset.UTC),
            actorUserId,
            at.atOffset(ZoneOffset.UTC),
            certificationId,
            expectedVersion,
        ) == 1
    }

    override fun documentTarget(
        documentId: UUID,
    ): EmployeeDocumentTargetContext? =
        jdbc.query(
            """
            SELECT
                id,
                organization_id,
                employee_id,
                verification_state,
                evidence_id,
                version
            FROM employee_document
            WHERE id = ?
            """.trimIndent(),
            { rs, _ ->
                EmployeeDocumentTargetContext(
                    documentId =
                        rs.getObject(
                            "id",
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
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    evidenceId =
                        rs.getObject(
                            "evidence_id",
                            UUID::class.java,
                        ),
                    version =
                        rs.getLong("version"),
                )
            },
            documentId,
        ).singleOrNull()

    override fun attachEvidence(
        documentId: UUID,
        evidenceId: UUID,
        at: Instant,
    ): Boolean =
        jdbc.update(
            """
            UPDATE employee_document
            SET evidence_id = ?,
                updated_at = ?,
                version = version + 1
            WHERE id = ?
              AND evidence_id IS NULL
              AND verification_state <> 'REJECTED'
            """.trimIndent(),
            evidenceId,
            at.atOffset(ZoneOffset.UTC),
            documentId,
        ) == 1

    override fun evidenceIsReadyForDocument(
        documentId: UUID,
        evidenceId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM evidence e
                WHERE e.id = ?
                  AND e.target_type =
                      'EMPLOYEE_DOCUMENT'
                  AND e.target_id = ?
                  AND e.work_order_id IS NULL
                  AND e.storage_state = 'READY'
            )
            """.trimIndent(),
            Boolean::class.java,
            evidenceId,
            documentId,
        ) == true

    private fun queryDocuments(
        predicate: String,
        args: Array<out Any>,
        order: String = "",
    ): List<EmployeeDocumentSnapshot> =
        jdbc.query(
            """
            SELECT
                ed.id AS document_id,
                ed.organization_id,
                ed.employee_id,
                e.employee_code,
                p.display_name,
                ed.document_type_code,
                ed.document_label,
                ed.issue_date,
                ed.expiry_date,
                ed.verification_state,
                ed.verified_at,
                ed.verified_by_user_id,
                ed.evidence_id,
                evidence.storage_state
                    AS evidence_storage_state,
                ed.retention_policy_code,
                ed.created_at,
                ed.updated_at,
                ed.version
            FROM employee_document ed
            JOIN employee e
              ON e.id = ed.employee_id
            JOIN person p
              ON p.id = e.person_id
            LEFT JOIN evidence
              ON evidence.id = ed.evidence_id
            WHERE $predicate
            $order
            """.trimIndent(),
            { rs, _ ->
                EmployeeDocumentSnapshot(
                    documentId =
                        rs.getObject(
                            "document_id",
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
                            "display_name",
                        ),
                    documentTypeCode =
                        rs.getString(
                            "document_type_code",
                        ),
                    documentLabel =
                        rs.getString(
                            "document_label",
                        ),
                    issueDate =
                        rs.getObject(
                            "issue_date",
                            LocalDate::class.java,
                        ),
                    expiryDate =
                        rs.getObject(
                            "expiry_date",
                            LocalDate::class.java,
                        ),
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    verifiedAt =
                        rs.getObject(
                            "verified_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    verifiedByUserId =
                        rs.getObject(
                            "verified_by_user_id",
                            UUID::class.java,
                        ),
                    evidenceId =
                        rs.getObject(
                            "evidence_id",
                            UUID::class.java,
                        ),
                    evidenceStorageState =
                        rs.getString(
                            "evidence_storage_state",
                        ),
                    retentionPolicyCode =
                        rs.getString(
                            "retention_policy_code",
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
            },
            *args,
        )

    private fun queryCertifications(
        predicate: String,
        args: Array<out Any>,
        order: String = "",
    ): List<CertificationSnapshot> =
        jdbc.query(
            """
            SELECT
                c.id AS certification_id,
                c.organization_id,
                c.employee_id,
                e.employee_code,
                p.display_name,
                c.certification_type_code,
                c.certification_label,
                c.issuer,
                c.issued_at,
                c.valid_until,
                c.verification_state,
                c.verified_at,
                c.verified_by_user_id,
                c.employee_document_id,
                ed.verification_state
                    AS document_verification_state,
                evidence.storage_state
                    AS document_evidence_storage_state,
                c.created_at,
                c.updated_at,
                c.version
            FROM certification c
            JOIN employee e
              ON e.id = c.employee_id
            JOIN person p
              ON p.id = e.person_id
            LEFT JOIN employee_document ed
              ON ed.id = c.employee_document_id
            LEFT JOIN evidence
              ON evidence.id = ed.evidence_id
            WHERE $predicate
            $order
            """.trimIndent(),
            { rs, _ ->
                CertificationSnapshot(
                    certificationId =
                        rs.getObject(
                            "certification_id",
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
                            "display_name",
                        ),
                    certificationTypeCode =
                        rs.getString(
                            "certification_type_code",
                        ),
                    certificationLabel =
                        rs.getString(
                            "certification_label",
                        ),
                    issuer =
                        rs.getString("issuer"),
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
                    verificationState =
                        HrVerificationState.valueOf(
                            rs.getString(
                                "verification_state",
                            ),
                        ),
                    verifiedAt =
                        rs.getObject(
                            "verified_at",
                            OffsetDateTime::class.java,
                        )?.toInstant(),
                    verifiedByUserId =
                        rs.getObject(
                            "verified_by_user_id",
                            UUID::class.java,
                        ),
                    employeeDocumentId =
                        rs.getObject(
                            "employee_document_id",
                            UUID::class.java,
                        ),
                    documentVerificationState =
                        rs.getString(
                            "document_verification_state",
                        )?.let {
                            HrVerificationState.valueOf(
                                it,
                            )
                        },
                    documentEvidenceStorageState =
                        rs.getString(
                            "document_evidence_storage_state",
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
            },
            *args,
        )
}
