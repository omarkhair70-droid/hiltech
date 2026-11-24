package com.hiltech.server.finance

import com.hiltech.server.documents.FinanceBankEvidenceAccount
import com.hiltech.server.documents.FinanceBankStatementEvidenceTargetPort
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.UUID

/**
 * Shares the exact company Bank Intake preparer authority and current active
 * independent reviewer policy with FinanceBankStatementIntakeService.
 * No fallback to ordinary organization membership as a financial grant.
 */
@Component
class JdbcFinanceBankStatementEvidenceTarget(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val subject: FinanceBankStatementIntakeApprovalSubject,
    private val environment: Environment,
    private val clock: Clock,
) : FinanceBankStatementEvidenceTargetPort {
    override fun loadApprovedAccount(accountId: UUID): FinanceBankEvidenceAccount? =
        jdbc.query(
            """
            SELECT id,organization_id,currency,state
            FROM finance_bank_account_register
            WHERE id=? AND state='APPROVED'
            """.trimIndent(),
            { rs, _ ->
                FinanceBankEvidenceAccount(
                    id = rs.getObject("id", UUID::class.java),
                    organizationId = rs.getObject("organization_id", UUID::class.java),
                    currency = rs.getString("currency"),
                    state = rs.getString("state"),
                )
            },
            accountId,
        ).singleOrNull()

    override fun canReserve(actor: UUID, account: FinanceBankEvidenceAccount): Boolean =
        account.state == "APPROVED" &&
            loadApprovedAccount(account.id) == account &&
            canUseFinanceBankIntake(actor, account.organizationId)

    override fun canAccess(actor: UUID, organizationId: UUID, uploader: UUID): Boolean =
        actor == uploader && canUseFinanceBankIntake(actor, organizationId)

    override fun validateCanonicalCsv(bytes: ByteArray, expectedSha256: String): Boolean =
        runCatching {
            FinanceBankCsvParser.decodeAndValidate(java.util.Base64.getEncoder().encodeToString(bytes), expectedSha256)
        }.isSuccess

    private fun canUseFinanceBankIntake(actor: UUID, org: UUID): Boolean {
        if (!subject.enabled()) return false
        val preparerKey = environment.getProperty(
            "hiltech.finance.bank-statement-intake.preparer-authority-key", "",
        ).trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(preparerKey)) return false
        val policyKey = subject.policyKey() ?: return false
        val now = clock.instant()
        if (!approvals.isActiveOrganizationMember(org, actor, now)) return false
        val preparer = approvals.resolveCurrentAuthority(org, preparerKey, now)
            ?: return false
        if (preparer.organizationId != org ||
            !approvals.actorMatchesAuthority(actor, preparer, now)
        ) return false

        val policy = approvals.loadActivePolicy(org, policyKey, now)
            ?.takeIf { it.organizationId == org &&
                it.policyKey == policyKey &&
                it.approvalRequired &&
                it.mode == ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
            } ?: return false
        val reviewer = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: return false
        return reviewer.organizationId == org &&
            !approvals.actorMatchesAuthority(actor, reviewer, now)
    }
}
