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
import java.time.LocalDate
import java.util.UUID

const val FINANCE_BANK_STATEMENT_INTAKE_SUBJECT="FINANCE_BANK_STATEMENT_INTAKE"

object FinanceBankStatementReviewVersion {
    fun derive(
        org:UUID,account:UUID,statementEvidence:UUID,statementHash:String,
        lineHash:String,opened:LocalDate,closed:LocalDate,
        opening:BigDecimal,closing:BigDecimal,count:Int,
        policyVersion:Int,reviewerId:UUID,reviewerVersion:Long,
    ):Long {
        require(policyVersion>0 && reviewerVersion>0)
        val value=listOf(
            "HILTECH_BANK_STATEMENT_INTAKE_V1",org,account,
            statementEvidence,statementHash,lineHash,opened,closed,
            opening.stripTrailingZeros().toPlainString(),
            closing.stripTrailingZeros().toPlainString(),count,
            policyVersion,reviewerId,reviewerVersion,
        ).joinToString("|")
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
data class FinanceBankStatementVerifiedProposal(
    val proposalId:UUID,val organizationId:UUID,
    val accountId:UUID,val currency:String,val statementEvidenceId:UUID,
    val statementHash:String,val lineHash:String,
    val periodStart:LocalDate,val periodEnd:LocalDate,
    val opening:BigDecimal,val closing:BigDecimal,
    val expectedCount:Int,val reviewVersion:Long,val policyKey:String,
    val policyVersion:Int,val preparedBy:UUID,
    val lines:List<FinanceBankIntakeSourceLine>,
)
private data class FinanceBankStatementSourceRow(
    val id:UUID,val account:UUID,val evidence:UUID,
    val sha:String,val lineHash:String,val start:LocalDate,val end:LocalDate,
    val opening:BigDecimal,val closing:BigDecimal,
    val count:Int,val version:Long,val policyKey:String,
    val preparedBy:UUID,val currency:String,val accountState:String,
)
@Component
class FinanceBankStatementIntakeApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_BANK_STATEMENT_INTAKE_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.reviewVersion==subject.subjectVersion

    fun enabled():Boolean=environment.getProperty(
        "hiltech.finance.bank-statement-intake.enabled",
        Boolean::class.java,false,
    )
    fun policyKey():String?=environment.getProperty(
        "hiltech.finance.bank-statement-intake.policy-key","",
    ).trim().takeIf {Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}

    fun evidenceReady(org:UUID,proposal:UUID,id:UUID,sha:String):Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1 FROM evidence e
                JOIN evidence_upload_session u ON u.evidence_id=e.id
                WHERE e.id=? AND e.organization_id=?
                  AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE'
                  AND e.target_id=? AND e.sha256=?
                  AND e.storage_state='READY' AND u.state='READY'
                  AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
                  AND e.object_key_ref IS NOT NULL
                  AND e.sha256=u.expected_sha256
                  AND e.size_bytes=u.expected_size_bytes
                  AND e.classification_code='HIGHLY_RESTRICTED'
                  AND e.client_visibility_mode='INTERNAL_ONLY'
            )
            """.trimIndent(),Boolean::class.java,id,org,proposal,sha,
        )==true

    fun current(org:UUID,proposal:UUID):FinanceBankStatementVerifiedProposal? {
        if(!enabled())return null
        val key=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf {
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(reviewer.organizationId!=org)return null
        val source=jdbc.query(
            """
            SELECT p.id,p.account_id,p.statement_evidence_id,
                p.statement_sha256,p.statement_lines_sha256,
                p.date_start,p.date_end,p.opening_balance,p.closing_balance,
                p.expected_line_count,p.source_version,p.policy_key,
                p.prepared_by_user_id,a.currency,a.state
            FROM finance_bank_statement_intake_proposal p
            JOIN finance_bank_account_register a
              ON a.id=p.account_id AND a.organization_id=p.organization_id
            WHERE p.id=? AND p.organization_id=?
            """.trimIndent(),
            {rs,_ ->FinanceBankStatementSourceRow(
                rs.getObject("id",UUID::class.java),
                rs.getObject("account_id",UUID::class.java),
                rs.getObject("statement_evidence_id",UUID::class.java),
                rs.getString("statement_sha256"),
                rs.getString("statement_lines_sha256"),
                rs.getDate("date_start").toLocalDate(),
                rs.getDate("date_end").toLocalDate(),
                rs.getBigDecimal("opening_balance"),
                rs.getBigDecimal("closing_balance"),
                rs.getInt("expected_line_count"),rs.getLong("source_version"),
                rs.getString("policy_key"),
                rs.getObject("prepared_by_user_id",UUID::class.java),
                rs.getString("currency"),rs.getString("state"),
            )},proposal,org,
        ).singleOrNull() ?: return null
        if(source.policyKey!=key || source.accountState!="APPROVED" ||
            !approvals.isActiveOrganizationMember(org,source.preparedBy,now) ||
            approvals.actorMatchesAuthority(source.preparedBy,reviewer,now) ||
            !evidenceReady(org,source.id,source.evidence,source.sha))
            return null
        val rows=jdbc.query(
            """
            SELECT external_transaction_id,booked_on,flow,currency,
                amount,original_line_sha256
            FROM finance_bank_statement_intake_proposal_line
            WHERE proposal_id=? AND organization_id=?
            ORDER BY line_number
            """.trimIndent(),
            {rs,_ ->FinanceBankIntakeSourceLine(
                rs.getString("external_transaction_id"),
                rs.getDate("booked_on").toLocalDate(),
                FinanceBankFlow.valueOf(rs.getString("flow")),
                rs.getString("currency"),rs.getBigDecimal("amount"),
                rs.getString("original_line_sha256"),
            )},proposal,org,
        )
        val checked=runCatching{FinanceBankIntakeRules.validate(
            FinanceBankIntakeSource(
                organizationId=org,bankAccountId=source.account,
                evidenceId=source.evidence,evidenceSha256=source.sha,
                currency=source.currency,
                statementStart=source.start,statementEnd=source.end,
                openingBalance=source.opening,closingBalance=source.closing,
                lines=rows,companyBankAccountApproved=true,
                finalizedEvidenceReviewed=true,
            ),
        )}.getOrNull() ?: return null
        if(checked.lineCount!=source.count ||
            checked.sourceLinesSha256!=source.lineHash)return null
        val expectedVersion=FinanceBankStatementReviewVersion.derive(
            org,source.account,source.evidence,source.sha,source.lineHash,
            source.start,source.end,source.opening,source.closing,source.count,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(expectedVersion!=source.version)return null
        val already=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_bank_statement_intake
               WHERE organization_id=? AND proposal_id=?)""",
            Boolean::class.java,org,proposal,
        )==true
        if(already)return null
        return FinanceBankStatementVerifiedProposal(
            source.id,org,source.account,source.currency,
            source.evidence,source.sha,source.lineHash,
            source.start,source.end,source.opening,source.closing,
            source.count,source.version,key,policy.versionNumber,
            source.preparedBy,rows,
        )
    }
}
