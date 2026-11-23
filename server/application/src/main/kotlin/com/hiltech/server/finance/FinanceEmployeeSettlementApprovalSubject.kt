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
import java.util.UUID

const val FINANCE_EMPLOYEE_SETTLEMENT_SUBJECT="FINANCE_EMPLOYEE_SETTLEMENT"

object FinanceEmployeeSettlementReviewVersion {
    fun derive(
        org:UUID,source:UUID,employee:UUID,action:String,
        evidence:UUID,evidenceHash:String,bankLine:UUID?,
        amount:BigDecimal,currency:String,
        policyVersion:Int,reviewerId:UUID,reviewerVersion:Long,
    ):Long {
        require(policyVersion>0 && reviewerVersion>0)
        val raw=listOf(
            "HILTECH_EMPLOYEE_SETTLEMENT_REVIEW_V1",
            org,source,employee,action,evidence,evidenceHash,
            bankLine ?: "-",amount.stripTrailingZeros().toPlainString(),
            currency,policyVersion,reviewerId,reviewerVersion,
        ).joinToString("|")
        val bytes=MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(bytes,0,8).long and Long.MAX_VALUE).coerceAtLeast(1L)
    }
}
data class FinanceEmployeeSettlementVerified(
    val proposalId:UUID,val org:UUID,val fundingRecordId:UUID,
    val employeeId:UUID,val action:String,val bankLineId:UUID?,
    val evidenceId:UUID,val evidenceSha256:String,
    val amount:BigDecimal,val currency:String,
    val fundedKind:String,val fundedAmount:BigDecimal,
    val fundingBankLineId:UUID,val fundingStatementSha:String,
    val bankStatementSha:String?,val remainingBefore:BigDecimal,
    val reviewVersion:Long,val policyKey:String,val policyVersion:Int,
    val preparedBy:UUID,
)
private data class FinanceSettlementDbRow(
    val id:UUID,val fundingId:UUID,val employee:UUID,
    val action:String,val bankLine:UUID?,
    val evidenceId:UUID,val evidenceHash:String,
    val amount:BigDecimal,val currency:String,
    val version:Long,val policyKey:String,val preparedBy:UUID,
    val fundedKind:String,val fundedAmount:BigDecimal,
    val fundedBankLine:UUID,val fundedCurrency:String,
)
private data class FinanceSettlementBankLine(
    val amount:BigDecimal,val currency:String,val flow:String,
    val statementHash:String,
)
@Component
class FinanceEmployeeSettlementApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_EMPLOYEE_SETTLEMENT_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.reviewVersion==subject.subjectVersion
    fun enabled():Boolean=environment.getProperty(
        "hiltech.finance.employee-settlement.enabled",Boolean::class.java,false,
    )
    fun policyKey():String?=environment.getProperty(
        "hiltech.finance.employee-settlement.policy-key","",
    ).trim().takeIf{Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it)}
    fun evidenceReady(org:UUID,proposal:UUID,evidence:UUID,sha:String):Boolean=
        jdbc.queryForObject(
            """SELECT EXISTS(
                SELECT 1 FROM evidence e
                JOIN evidence_upload_session u ON u.evidence_id=e.id
                WHERE e.organization_id=? AND e.id=?
                  AND e.target_type='FINANCE_EMPLOYEE_SETTLEMENT'
                  AND e.target_id=? AND e.sha256=?
                  AND e.storage_state='READY' AND u.state='READY'
                  AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
                  AND e.object_key_ref IS NOT NULL
                  AND e.sha256=u.expected_sha256
                  AND e.size_bytes=u.expected_size_bytes
                  AND e.classification_code='HIGHLY_RESTRICTED'
                  AND e.client_visibility_mode='INTERNAL_ONLY'
            )""",
            Boolean::class.java,org,evidence,proposal,sha,
        )==true

    fun bank(org:UUID,id:UUID):FinanceSettlementBankLine?=
        jdbc.query(
            """SELECT l.amount,l.currency,l.flow,h.statement_sha256
               FROM finance_bank_statement_intake_line l
               JOIN finance_bank_statement_intake h
                 ON h.id=l.intake_id AND h.organization_id=l.organization_id
               WHERE l.id=? AND l.organization_id=?""",
            {rs,_ ->FinanceSettlementBankLine(
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getString("flow"),rs.getString("statement_sha256"),
            )},id,org,
        ).singleOrNull()

    fun remaining(org:UUID,funding:UUID,total:BigDecimal):BigDecimal {
        val used=jdbc.queryForObject(
            """SELECT coalesce(sum(amount),0)
               FROM finance_employee_settlement_record
               WHERE organization_id=? AND funding_record_id=?""",
            BigDecimal::class.java,org,funding,
        ) ?: BigDecimal.ZERO
        return total.subtract(used)
    }

    fun current(org:UUID,id:UUID):FinanceEmployeeSettlementVerified? {
        if(!enabled())return null
        val key=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,key,now)?.takeIf{
            it.organizationId==org && it.policyKey==key &&
                it.approvalRequired && it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(reviewer.organizationId!=org)return null
        val row=jdbc.query(
            """
            SELECT p.id,p.funding_record_id,p.employee_id,p.action,
              p.bank_statement_line_id,p.settlement_evidence_id,
              p.settlement_evidence_sha256,p.amount,p.currency,
              p.review_version,p.policy_key,p.prepared_by_user_id,
              f.draft_kind AS funded_kind,f.amount AS funded_amount,
              f.bank_statement_line_id AS funded_bank,
              f.currency AS funded_currency
            FROM finance_employee_settlement_proposal p
            JOIN finance_employee_funding_record f
              ON f.id=p.funding_record_id AND f.organization_id=p.organization_id
            WHERE p.id=? AND p.organization_id=?
            """.trimIndent(),
            {rs,_ ->FinanceSettlementDbRow(
                rs.getObject("id",UUID::class.java),
                rs.getObject("funding_record_id",UUID::class.java),
                rs.getObject("employee_id",UUID::class.java),
                rs.getString("action"),
                rs.getObject("bank_statement_line_id",UUID::class.java),
                rs.getObject("settlement_evidence_id",UUID::class.java),
                rs.getString("settlement_evidence_sha256"),
                rs.getBigDecimal("amount"),rs.getString("currency"),
                rs.getLong("review_version"),rs.getString("policy_key"),
                rs.getObject("prepared_by_user_id",UUID::class.java),
                rs.getString("funded_kind"),rs.getBigDecimal("funded_amount"),
                rs.getObject("funded_bank",UUID::class.java),
                rs.getString("funded_currency"),
            )},id,org,
        ).singleOrNull() ?: return null
        if(row.policyKey!=key || row.fundedCurrency!=row.currency ||
            row.amount<=BigDecimal.ZERO || row.amount>row.fundedAmount ||
            (row.fundedKind=="EMPLOYEE_ADVANCE" &&
                row.action!="ADVANCE_RECOVERY_CONFIRMED") ||
            (row.fundedKind=="FINANCIAL_IMPREST" &&
                row.action !in setOf("IMPREST_CASH_RETURN_CONFIRMED",
                    "IMPREST_RECEIPTS_ACCEPTED")) ||
            !approvals.isActiveOrganizationMember(org,row.preparedBy,now) ||
            approvals.actorMatchesAuthority(row.preparedBy,reviewer,now) ||
            !evidenceReady(org,id,row.evidenceId,row.evidenceHash))
            return null
        val remaining=remaining(org,row.fundingId,row.fundedAmount)
        if(remaining<row.amount)return null
        val fundingBank=bank(org,row.fundedBank) ?: return null
        if(fundingBank.flow!="DEBIT" || fundingBank.currency!=row.currency)
            return null
        val bank=if(row.bankLine==null)null else bank(org,row.bankLine)
        if(row.bankLine!=null && bank==null)return null
        if(row.action=="IMPREST_RECEIPTS_ACCEPTED") {
            if(row.bankLine!=null)return null
        } else {
            if(bank==null || bank.flow!="CREDIT" ||
                bank.currency!=row.currency || bank.amount<row.amount)
                return null
            val total=jdbc.queryForObject(
                """SELECT coalesce(sum(amount),0)
                   FROM finance_employee_settlement_record
                   WHERE organization_id=? AND bank_statement_line_id=?""",
                BigDecimal::class.java,org,row.bankLine,
            ) ?: BigDecimal.ZERO
            val cash=jdbc.queryForObject(
                """SELECT coalesce(sum(allocated_amount),0)
                   FROM finance_cash_allocation_record
                   WHERE organization_id=? AND bank_statement_line_id=?""",
                BigDecimal::class.java,org,row.bankLine,
            ) ?: BigDecimal.ZERO
            if(total.add(cash).add(row.amount)>bank.amount)return null
        }
        val expected=FinanceEmployeeSettlementReviewVersion.derive(
            org,row.fundingId,row.employee,row.action,
            row.evidenceId,row.evidenceHash,row.bankLine,row.amount,row.currency,
            policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(expected!=row.version)return null
        if(jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_employee_settlement_record
               WHERE organization_id=? AND proposal_id=?)""",
            Boolean::class.java,org,id,
        )==true)return null
        return FinanceEmployeeSettlementVerified(
            proposalId=id,org=org,fundingRecordId=row.fundingId,
            employeeId=row.employee,action=row.action,
            bankLineId=row.bankLine,evidenceId=row.evidenceId,
            evidenceSha256=row.evidenceHash,amount=row.amount,
            currency=row.currency,fundedKind=row.fundedKind,
            fundedAmount=row.fundedAmount,
            fundingBankLineId=row.fundedBank,
            fundingStatementSha=fundingBank.statementHash,
            bankStatementSha=bank?.statementHash,
            remainingBefore=remaining,reviewVersion=expected,
            policyKey=key,policyVersion=policy.versionNumber,
            preparedBy=row.preparedBy,
        )
    }
}
