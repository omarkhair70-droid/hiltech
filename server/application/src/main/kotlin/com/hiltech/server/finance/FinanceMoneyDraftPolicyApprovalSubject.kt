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

enum class FinanceEmployeeMoneyDraftKind(
    val path: String,
    val subjectType: String,
    val kindCode: String,
    val stateCode: String,
    val enabledKey: String,
    val policyKeySetting: String,
    val reasonCode: String,
) {
    ADVANCE(
        "advances", "FINANCE_EMPLOYEE_ADVANCE_POLICY_REVIEW",
        "EMPLOYEE_ADVANCE", "DRAFT_POLICY_PENDING",
        "hiltech.finance.advances.policy-review.enabled",
        "hiltech.finance.advances.policy-review.policy-key",
        "FINANCE_EMPLOYEE_ADVANCE_REQUEST_REVIEW",
    ),
    IMPREST(
        "imprests", "FINANCE_FINANCIAL_IMPREST_POLICY_REVIEW",
        "FINANCIAL_IMPREST", "DRAFT_CUSTODY_POLICY_PENDING",
        "hiltech.finance.imprests.policy-review.enabled",
        "hiltech.finance.imprests.policy-review.policy-key",
        "FINANCE_IMPREST_CUSTODY_REQUEST_REVIEW",
    );

    companion object {
        fun fromSubject(type: String): FinanceEmployeeMoneyDraftKind? =
            entries.singleOrNull { it.subjectType == type }
    }
}

data class FinanceMoneyDraftPolicySnapshot(
    val id: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val createdByUserId: UUID,
    val kind: FinanceEmployeeMoneyDraftKind,
    val amount: BigDecimal,
    val currency: String,
    val purpose: String,
    val projectId: UUID?,
    val policyKey: String,
    val policyVersion: Int,
    val sourceVersion: Long,
)

private data class MoneyDraftDbRow(
    val id: UUID,
    val organizationId: UUID,
    val employeeId: UUID,
    val createdByUserId: UUID,
    val amount: BigDecimal,
    val currency: String,
    val purpose: String,
    val projectId: UUID?,
    val version: Long,
    val state: String,
)

/**
 * Reuses immutable Phase8B source requests + canonical ApprovalPolicyVersion.
 * Actual money loan/treasury custody issuance still needs independent funding
 * proof, accounting recognition, repayment/settlement and signatory controls.
 */
@Component
class FinanceMoneyDraftPolicyApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        FinanceEmployeeMoneyDraftKind.fromSubject(subjectType) != null

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean {
        val kind = FinanceEmployeeMoneyDraftKind.fromSubject(subject.subjectType)
            ?: return false
        return current(kind, subject.organizationId, subject.subjectId)
            ?.sourceVersion == subject.subjectVersion
    }

    fun current(
        kind: FinanceEmployeeMoneyDraftKind,
        org: UUID,
        draftId: UUID,
    ): FinanceMoneyDraftPolicySnapshot? {
        if (!environment.getProperty(
                kind.enabledKey, Boolean::class.java, false,
            ) || !environment.getProperty(
                "hiltech.finance.employee-requests.enabled",
                Boolean::class.java, false,
            )) return null

        val key = environment.getProperty(kind.policyKeySetting, "").trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) return null
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

        // The two physical Phase8B tables are never coalesced: imprest
        // custodian is NOT an employee advance debtor.
        val table = when (kind) {
            FinanceEmployeeMoneyDraftKind.ADVANCE -> "finance_employee_advance_draft"
            FinanceEmployeeMoneyDraftKind.IMPREST -> "finance_financial_imprest_draft"
        }
        val ownerField = when (kind) {
            FinanceEmployeeMoneyDraftKind.ADVANCE -> "employee_id"
            FinanceEmployeeMoneyDraftKind.IMPREST -> "custodian_employee_id"
        }
        val rows = jdbc.query(
            """
            SELECT d.id,d.organization_id,
                d.$ownerField AS employee_id,d.created_by_user_id,
                d.requested_amount AS amount,d.currency,d.purpose,d.project_id,
                d.version,d.state
            FROM $table d
            JOIN employee e ON e.id=d.$ownerField
                AND e.organization_id=d.organization_id
            JOIN user_identity u ON u.id=d.created_by_user_id
                AND u.person_id=e.person_id
            JOIN organization o ON o.id=d.organization_id
            WHERE d.id=? AND d.organization_id=?
                AND d.state=?
                AND e.state='ACTIVE' AND u.status='ACTIVE'
                AND o.status='ACTIVE'
                AND EXISTS (
                    SELECT 1 FROM employment em
                    WHERE em.employee_id=e.id AND em.state='ACTIVE'
                      AND em.start_date<=? AND em.end_date IS NULL
                )
            """.trimIndent(),
            { rs, _ ->
                MoneyDraftDbRow(
                    id = rs.getObject("id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    employeeId = rs.getObject("employee_id", UUID::class.java),
                    createdByUserId = rs.getObject("created_by_user_id", UUID::class.java),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency"),
                    purpose = rs.getString("purpose"),
                    projectId = rs.getObject("project_id", UUID::class.java),
                    version = rs.getLong("version"),
                    state = rs.getString("state"),
                )
            },
            draftId, org, kind.stateCode,
            now.atZone(ZoneOffset.UTC).toLocalDate(),
        )
        val row = rows.singleOrNull() ?: return null
        if (!approvals.isActiveOrganizationMember(org, row.createdByUserId, now) ||
            approvals.actorMatchesAuthority(row.createdByUserId, authority, now)) return null

        val sourceParts = listOf(
            "finance-policy-v1", kind.subjectType, org, row.id,
            row.employeeId, row.createdByUserId, row.version,
            row.amount.stripTrailingZeros().toPlainString(),
            row.currency, row.purpose, row.projectId ?: "-",
            policy.policyKey, policy.versionNumber, authority.id, authority.version,
        ).joinToString("|")
        val hashed = MessageDigest.getInstance("SHA-256")
            .digest(sourceParts.toByteArray(Charsets.UTF_8))
        val version = (ByteBuffer.wrap(hashed, 0, 8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
        return FinanceMoneyDraftPolicySnapshot(
            id = row.id, organizationId = row.organizationId,
            employeeId = row.employeeId, createdByUserId = row.createdByUserId,
            kind = kind, amount = row.amount,
            currency = row.currency, purpose = row.purpose,
            projectId = row.projectId,
            policyKey = policy.policyKey, policyVersion = policy.versionNumber,
            sourceVersion = version,
        )
    }
}
