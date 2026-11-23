package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

const val FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT = "FINANCE_EXPENSE_DOCUMENTATION_REVIEW"

data class FinanceExpenseDocumentationSnapshot(
    val organizationId: UUID,
    val expenseDraftId: UUID,
    val createdByUserId: UUID,
    val employeeId: UUID,
    val amount: String,
    val currency: String,
    val verifiedEvidenceIds: List<UUID>,
    val sourceVersion: Long,
)

private data class ExpenseDocumentationSourceRow(
    val expenseId: UUID,
    val organizationId: UUID,
    val creatorId: UUID,
    val employeeId: UUID,
    val expenseVersion: Long,
    val amount: BigDecimal,
    val currency: String,
    val evidenceId: UUID,
    val evidenceVersion: Long,
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * A Finance expense's documentation approval subject is versioned from the
 * ACTUAL expense and all current canonical private READY receipt metadata.
 * Any new receipt, changed attachment hash/version, authority/policy binding
 * or revoked creator authorization makes a pending approval stale.
 *
 * This is a review of supporting documents ONLY, never recognition of a
 * booked expense, a reimbursement entitlement or a payment mandate.
 */
@Component
class FinanceExpenseDocumentationApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == FINANCE_EXPENSE_DOCUMENTATION_REVIEW_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId, subject.subjectId)
                ?.sourceVersion == subject.subjectVersion

    fun current(org: UUID, expenseId: UUID): FinanceExpenseDocumentationSnapshot? {
        if (!enabled()) return null
        val key = policyKey() ?: return null
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, key, now)
            ?.takeIf {
                it.organizationId == org &&
                    it.policyKey == key &&
                    it.approvalRequired &&
                    it.mode == ApprovalPolicyMode.SINGLE &&
                    !it.authorityKey.isNullOrBlank()
            } ?: return null
        val authority = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: return null
        if (authority.organizationId != org ||
            authority.authorityKey != policy.authorityKey) return null

        val rows = jdbc.query(
            """
            SELECT d.id AS expense_id,d.organization_id,d.created_by_user_id,
                d.employee_id,d.version AS expense_version,d.amount,d.currency,
                e.id AS evidence_id,e.version AS evidence_version,e.sha256,
                e.size_bytes
            FROM finance_expense_claim_draft d
            JOIN evidence e ON e.target_id=d.id
                AND e.organization_id=d.organization_id
                AND e.target_type='FINANCE_EXPENSE_RECEIPT'
                AND e.captured_by_user_id=d.created_by_user_id
            JOIN evidence_upload_session s ON s.evidence_id=e.id
            WHERE d.id=? AND d.organization_id=?
                AND d.state='DRAFT_RECEIPT_PENDING'
                AND EXISTS (
                    SELECT 1 FROM employee emp
                    JOIN employment employment ON employment.employee_id=emp.id
                        AND employment.state='ACTIVE'
                    JOIN user_identity owner ON owner.id=d.created_by_user_id
                        AND owner.person_id=emp.person_id
                    JOIN organization company ON company.id=d.organization_id
                    WHERE emp.id=d.employee_id
                        AND emp.organization_id=d.organization_id
                        AND emp.state='ACTIVE'
                        AND company.status='ACTIVE'
                        AND owner.status='ACTIVE'
                        AND employment.start_date<=?
                        AND employment.end_date IS NULL
                )
                AND e.evidence_requirement_key='FINANCE_EXPENSE_RECEIPT_BINARY'
                AND e.evidence_type_code='FINANCE_EXPENSE_RECEIPT'
                AND e.storage_state='READY' AND s.state='READY'
                AND e.finalized_at IS NOT NULL AND s.finalized_at IS NOT NULL
                AND e.object_key_ref IS NOT NULL
                AND e.sha256=s.expected_sha256
                AND e.size_bytes=s.expected_size_bytes
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
                AND (
                    (e.content_type='application/pdf'
                        AND e.security_scan_class='SIGNED_DOCUMENT')
                    OR
                    (e.content_type IN ('image/jpeg','image/png')
                        AND e.security_scan_class='NATIVE_MEDIA')
                )
            ORDER BY e.id
            LIMIT 101
            """.trimIndent(),
            { rs, _ ->
                ExpenseDocumentationSourceRow(
                    expenseId = rs.getObject("expense_id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    creatorId = rs.getObject("created_by_user_id", UUID::class.java),
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    expenseVersion = rs.getLong("expense_version"),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency"),
                    evidenceId = rs.getObject("evidence_id", UUID::class.java),
                    evidenceVersion = rs.getLong("evidence_version"),
                    sha256 = rs.getString("sha256"),
                    sizeBytes = rs.getLong("size_bytes"),
                )
            },
            expenseId, org, now.atZone(ZoneOffset.UTC).toLocalDate(),
        )
        // A capped source would not yield an authoritative version.
        if (rows.isEmpty() || rows.size > 100) return null
        val first = rows.first()
        if (rows.any {
                it.expenseId != first.expenseId ||
                    it.organizationId != first.organizationId ||
                    it.creatorId != first.creatorId ||
                    it.expenseVersion != first.expenseVersion ||
                    it.amount.compareTo(first.amount) != 0 ||
                    it.currency != first.currency
            }) return null
        if (!approvals.isActiveOrganizationMember(org, first.creatorId, now) ||
            approvals.actorMatchesAuthority(first.creatorId, authority, now)) return null

        val proof = buildString {
            append(org).append('|').append(first.expenseId).append('|')
            append(first.creatorId).append('|').append(first.employeeId).append('|')
            append(first.expenseVersion).append('|')
            append(first.amount.stripTrailingZeros().toPlainString())
                .append('|').append(first.currency).append('|')
            append(key).append('|').append(policy.versionNumber).append('|')
            append(authority.id).append('|').append(authority.version)
            for (row in rows) {
                append('|').append(row.evidenceId).append(':')
                    .append(row.evidenceVersion).append(':')
                    .append(row.sha256).append(':').append(row.sizeBytes)
            }
        }
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(proof.toByteArray(Charsets.UTF_8))
        val version = (ByteBuffer.wrap(hash, 0, 8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
        return FinanceExpenseDocumentationSnapshot(
            organizationId = first.organizationId,
            expenseDraftId = first.expenseId,
            createdByUserId = first.creatorId,
            employeeId = first.employeeId,
            amount = first.amount.toPlainString(),
            currency = first.currency,
            verifiedEvidenceIds = rows.map { it.evidenceId },
            sourceVersion = version,
        )
    }

    private fun policyKey(): String? =
        environment.getProperty("hiltech.finance.expenses.documentation-review.policy-key", "")
            .trim().takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }

    private fun enabled(): Boolean =
        environment.getProperty(
            "hiltech.finance.expenses.documentation-review.enabled",
            Boolean::class.java, false,
        ) &&
        environment.getProperty(
            "hiltech.finance.expense-receipts.canonical-upload-ready",
            Boolean::class.java, false,
        ) &&
        environment.getProperty(
            "hiltech.finance.expense-receipts.readiness-enabled",
            Boolean::class.java, false,
        )
}
