package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalSubjectVersionSource
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

const val FINANCE_AR_INVOICE_ISSUANCE_SUBJECT="FINANCE_AR_INVOICE_ISSUANCE"

data class FinanceArIssuanceReviewSnapshot(
    val proposalId:UUID,val candidateId:UUID,val organizationId:UUID,
    val opportunityId:UUID,val customerOrganizationId:UUID,
    val invoiceNumber:String,val documentEvidenceId:UUID,
    val commercialSourceHash:String,val amount:BigDecimal,val currency:String,
    val issueDate:java.time.LocalDate,val dueDate:java.time.LocalDate,
    val preparedBy:UUID,val subjectVersion:Long,
    val policyKey:String,val policyVersion:Int,
)

/** Revalidates exact immutable invoice number and PDF prior to approval decision. */
@Component
class FinanceArInvoiceIssuanceApprovalSubject(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val award:FinanceClientAwardSourceService,
    private val environment:Environment,
    private val clock:Clock,
):ApprovalSubjectVersionSource {
    override fun supports(subjectType:String):Boolean =
        subjectType==FINANCE_AR_INVOICE_ISSUANCE_SUBJECT
    override fun isCurrent(subject:ApprovalSubjectRef):Boolean =
        supports(subject.subjectType) &&
            current(subject.organizationId,subject.subjectId)
                ?.subjectVersion==subject.subjectVersion

    fun enabled():Boolean=environment.getProperty(
        "hiltech.finance.receivables.invoice-issuance.enabled",
        Boolean::class.java,false,
    )
    fun policyKey():String?=environment.getProperty(
        "hiltech.finance.receivables.invoice-issuance.policy-key","",
    ).trim().takeIf { Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(it) }

    fun documentReady(org:UUID,candidate:UUID,evidenceId:UUID):Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM evidence e
              JOIN evidence_upload_session u ON u.evidence_id=e.id
              WHERE e.id=? AND e.organization_id=?
                AND e.target_type='FINANCE_AR_ISSUED_INVOICE'
                AND e.target_id=? AND e.storage_state='READY'
                AND u.state='READY' AND e.finalized_at IS NOT NULL
                AND u.finalized_at IS NOT NULL AND e.object_key_ref IS NOT NULL
                AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
                AND e.content_type='application/pdf'
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
            )
            """.trimIndent(),Boolean::class.java,evidenceId,org,candidate,
        )==true

    fun current(org:UUID,id:UUID):FinanceArIssuanceReviewSnapshot? {
        if(!enabled() || !environment.getProperty(
            "hiltech.finance.receivables.canonical-invoice-number-ready",
            Boolean::class.java,false,
        )) return null
        val policyKey=policyKey() ?: return null
        val now=clock.instant()
        val policy=approvals.loadActivePolicy(org,policyKey,now)?.takeIf {
            it.organizationId==org && it.approvalRequired &&
                it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: return null
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,now,
        ) ?: return null
        if(reviewer.organizationId!=org) return null
        val rows=jdbc.query(
            """
            SELECT p.id AS proposal_id,p.invoice_number,
              p.invoice_document_evidence_id,p.approval_policy_key,
              p.approval_subject_version,p.created_by_user_id,
              c.id AS candidate_id,c.opportunity_id,
              c.customer_organization_id,c.commercial_source_sha256,
              c.requested_amount,c.currency,c.invoice_issue_date,
              c.invoice_due_date,c.state
            FROM finance_ar_invoice_issuance_proposal p
            JOIN finance_ar_invoice_candidate c
              ON c.id=p.candidate_id AND c.organization_id=p.organization_id
            WHERE p.id=? AND p.organization_id=?
              AND p.source_fingerprint_sha256=c.commercial_source_sha256
            """.trimIndent(),
            { rs,_ ->FinanceArIssuanceReviewSnapshot(
                proposalId=rs.getObject("proposal_id",UUID::class.java),
                candidateId=rs.getObject("candidate_id",UUID::class.java),
                organizationId=org,
                opportunityId=rs.getObject("opportunity_id",UUID::class.java),
                customerOrganizationId=rs.getObject("customer_organization_id",UUID::class.java),
                invoiceNumber=rs.getString("invoice_number"),
                documentEvidenceId=rs.getObject("invoice_document_evidence_id",UUID::class.java),
                commercialSourceHash=rs.getString("commercial_source_sha256"),
                amount=rs.getBigDecimal("requested_amount"),currency=rs.getString("currency"),
                issueDate=rs.getDate("invoice_issue_date").toLocalDate(),
                dueDate=rs.getDate("invoice_due_date").toLocalDate(),
                preparedBy=rs.getObject("created_by_user_id",UUID::class.java),
                subjectVersion=rs.getLong("approval_subject_version"),
                policyKey=rs.getString("approval_policy_key"),
                policyVersion=policy.versionNumber,
            )},id,org,
        ).singleOrNull() ?: return null
        if(rows.policyKey!=policyKey ||
            !approvals.isActiveOrganizationMember(org,rows.preparedBy,now) ||
            approvals.actorMatchesAuthority(rows.preparedBy,reviewer,now) ||
            !documentReady(org,rows.candidateId,rows.documentEvidenceId)) return null
        val source=runCatching {
            award.detail(rows.preparedBy,org,rows.opportunityId)
        }.getOrNull() ?: return null
        if(source.sourceFingerprintSha256!=rows.commercialSourceHash ||
            source.clientOrganizationId!=rows.customerOrganizationId ||
            source.currency!=rows.currency) return null
        val values=FinanceArIssuanceSnapshot(
            org,rows.candidateId,rows.customerOrganizationId,
            rows.invoiceNumber,rows.documentEvidenceId,rows.commercialSourceHash,
            rows.amount,rows.currency,rows.issueDate,rows.dueDate,
            rows.preparedBy,UUID(0L,0L),true,true,true,
        )
        if(runCatching { FinanceArInvoiceIssuanceRules.validate(values) }.isFailure)
            return null
        val expected=FinanceArInvoiceIssuanceVersion.derive(
            values,policy.versionNumber,reviewer.id,reviewer.version,
        )
        if(expected!=rows.subjectVersion) return null
        val already=jdbc.queryForObject(
            """SELECT EXISTS(SELECT 1 FROM finance_ar_issued_invoice
               WHERE organization_id=? AND candidate_id=?)""",
            Boolean::class.java,org,rows.candidateId,
        )==true
        return if(already) null else rows
    }
}
