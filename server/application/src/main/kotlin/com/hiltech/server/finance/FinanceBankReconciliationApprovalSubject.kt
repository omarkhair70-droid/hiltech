package com.hiltech.server.finance

import com.hiltech.server.approval.CompanyReviewPolicyKeys

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
import java.util.UUID

const val FINANCE_BANK_RECONCILIATION_SUBJECT = "FINANCE_BANK_RECONCILIATION"

object FinanceSignedBankReconciliationVersion {
    fun derive(
        org: UUID, intake: UUID, account: UUID, statementHash: String,
        matchHash: String, currency: String, lines: Long,
        credits: BigDecimal, debits: BigDecimal, allocated: BigDecimal,
        reference: String, policyVersion: Int,
        reviewerBinding: UUID, reviewerVersion: Long,
    ): Long {
        require(policyVersion > 0 && reviewerVersion > 0 && lines > 0)
        fun n(value: BigDecimal) = value.stripTrailingZeros().toPlainString()
        val input = listOf(
            "FINANCE_SIGNED_BANK_RECONCILIATION_V1", org, intake, account,
            statementHash, matchHash, currency, lines,
            n(credits), n(debits), n(allocated), reference,
            policyVersion, reviewerBinding, reviewerVersion,
        ).joinToString("|")
        val data = MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(data, 0, 8).long and Long.MAX_VALUE).coerceAtLeast(1)
    }
}

data class FinanceSignedBankSnapshot(
    val org: UUID, val intake: UUID, val account: UUID,
    val statementHash: String, val matchHash: String,
    val currency: String, val lines: Long,
    val credits: BigDecimal, val debits: BigDecimal, val matched: BigDecimal,
    val fullyMatched: Boolean,
)

data class FinanceSignedBankProposal(
    val id: UUID, val snapshot: FinanceSignedBankSnapshot,
    val reference: String, val version: Long,
    val policyKey: String, val policyVersion: Int,
    val preparedBy: UUID,
)

@Component
class FinanceBankReconciliationApprovalSubject(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val environment: Environment,
    private val clock: Clock,
) : ApprovalSubjectVersionSource {
    override fun supports(subjectType: String): Boolean =
        subjectType == FINANCE_BANK_RECONCILIATION_SUBJECT

    override fun isCurrent(subject: ApprovalSubjectRef): Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId, subject.subjectId)?.version == subject.subjectVersion

    fun enabled(): Boolean = environment.getProperty(
        "hiltech.finance.bank-reconciliation.signoff-enabled",
        Boolean::class.java, false,
    )
    fun policyKey(): String? = CompanyReviewPolicyKeys.resolve(environment, "hiltech.finance.bank-reconciliation.signoff-policy-key").takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }

    fun snapshot(org: UUID, intake: UUID): FinanceSignedBankSnapshot? =
        jdbc.query(
            """
            SELECT s.organization_id,s.intake_id,s.account_id,
                   s.statement_sha256,s.match_sha256,s.currency,
                   s.actual_line_count,s.expected_line_count,s.total_credit,
                   s.total_debit,s.total_matched,s.fully_matched,
                   s.opening_balance,s.closing_balance,
                   a.state AS account_state
            FROM finance_bank_reconciliation_snapshot s
            JOIN finance_bank_account_register a
              ON a.organization_id=s.organization_id AND a.id=s.account_id
            WHERE s.organization_id=? AND s.intake_id=?
            """.trimIndent(),
            { rs, _ ->
                val credits = rs.getBigDecimal("total_credit")
                val debits = rs.getBigDecimal("total_debit")
                val opening = rs.getBigDecimal("opening_balance")
                val closing = rs.getBigDecimal("closing_balance")
                val count = rs.getLong("actual_line_count")
                val verified = rs.getBoolean("fully_matched") &&
                    count == rs.getLong("expected_line_count") &&
                    opening.add(credits).subtract(debits).compareTo(closing) == 0 &&
                    rs.getString("account_state") == "APPROVED"
                FinanceSignedBankSnapshot(
                    org, intake, rs.getObject("account_id", UUID::class.java),
                    rs.getString("statement_sha256"),
                    rs.getString("match_sha256"), rs.getString("currency"),
                    count, credits, debits, rs.getBigDecimal("total_matched"),
                    verified,
                )
            }, org, intake,
        ).singleOrNull()

    fun current(org: UUID, id: UUID): FinanceSignedBankProposal? {
        if (!enabled()) return null
        val key = policyKey() ?: return null
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, key, now)?.takeIf {
            it.organizationId == org && it.policyKey == key &&
                it.approvalRequired && it.mode == ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: return null
        if (reviewer.organizationId != org) return null
        val prepared = jdbc.query(
            """
            SELECT p.intake_id,p.account_id,p.statement_sha256,p.match_sha256,
                   p.currency,p.expected_line_count,p.total_credit,
                   p.total_debit,p.total_matched,p.reconciliation_reference,
                   p.subject_version,p.policy_key,p.prepared_by_user_id
            FROM finance_bank_reconciliation_proposal p
            WHERE p.id=? AND p.organization_id=?
              AND NOT EXISTS (
               SELECT 1 FROM finance_bank_reconciliation_record r
               WHERE r.organization_id=p.organization_id AND r.proposal_id=p.id
              )
            """.trimIndent(),
            { rs, _ ->
                val s = FinanceSignedBankSnapshot(
                    org, rs.getObject("intake_id", UUID::class.java),
                    rs.getObject("account_id", UUID::class.java),
                    rs.getString("statement_sha256"),
                    rs.getString("match_sha256"), rs.getString("currency"),
                    rs.getLong("expected_line_count"),
                    rs.getBigDecimal("total_credit"),
                    rs.getBigDecimal("total_debit"),
                    rs.getBigDecimal("total_matched"), true,
                )
                FinanceSignedBankProposal(
                    id, s, rs.getString("reconciliation_reference"),
                    rs.getLong("subject_version"), rs.getString("policy_key"),
                    policy.versionNumber,
                    rs.getObject("prepared_by_user_id", UUID::class.java),
                )
            }, id, org,
        ).singleOrNull() ?: return null
        if (prepared.policyKey != key ||
            !approvals.isActiveOrganizationMember(org, prepared.preparedBy, now) ||
            approvals.actorMatchesAuthority(prepared.preparedBy, reviewer, now)
        ) return null
        val current = snapshot(org, prepared.snapshot.intake) ?: return null
        if (!current.fullyMatched || current != prepared.snapshot) return null
        val correct = FinanceSignedBankReconciliationVersion.derive(
            org, current.intake, current.account, current.statementHash,
            current.matchHash, current.currency, current.lines,
            current.credits, current.debits, current.matched,
            prepared.reference, policy.versionNumber, reviewer.id, reviewer.version,
        )
        if (correct != prepared.version) return null
        return prepared
    }
}
