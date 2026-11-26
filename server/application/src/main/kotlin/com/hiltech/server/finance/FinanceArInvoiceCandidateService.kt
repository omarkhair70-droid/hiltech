package com.hiltech.server.finance

import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class FinanceArCreateCandidateRequest(
    val operationId:UUID,
    val billingMilestoneEvidenceId:UUID,
    val requestedAmount:String,
    val legalTaxReviewReference:String,
    val contractualBillableReference:String,
    val invoiceIssueDate:LocalDate,
    val invoiceDueDate:LocalDate,
)
data class FinanceArCandidateReceipt(
    val candidateId:UUID,
    val organizationId:UUID,
    val opportunityId:UUID,
    val customerOrganizationId:UUID,
    val commercialSourceSha256:String,
    val requestedAmount:String,
    val currency:String,
    val state:String="DRAFT_UNISSUED",
    val replayed:Boolean=false,
    val invoiceIssued:Boolean=false,
    val receivablePosted:Boolean=false,
    val cashCollected:Boolean=false,
    val bankReconciled:Boolean=false,
)

/**
 * Source-bound AR **draft only**. No tax invoice number is reserved, no
 * commercial customer is contacted and no revenue is recognized.
 */
@Service
class FinanceArInvoiceCandidateService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val award:FinanceClientAwardSourceService,
    private val approvals:ApprovalPersistencePort,
    private val environment:Environment,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    @Transactional
    fun create(
        actor:UUID,org:UUID,opportunity:UUID,request:FinanceArCreateCandidateRequest,
        correlation:String,
    ):FinanceArCandidateReceipt {
        requireFinance(actor,org)
        // This also rejects missing P10 canonical source and requires its
        // separate company-assigned Sales->Finance source read authority.
        val released=award.detail(actor,org,opportunity)
        val amount=runCatching { BigDecimal(request.requestedAmount) }
            .getOrNull() ?: unavailable()
        val validated=runCatching { FinanceArInvoiceCandidateRules.validate(
            FinanceArInvoiceCandidateInput(
                organizationId=org,customerOrganizationId=released.clientOrganizationId,
                opportunityId=opportunity,
                commercialSourceSha256=released.sourceFingerprintSha256,
                contractId=released.contractId,
                clientPurchaseOrderId=released.clientPoId,
                commercialCurrency=released.currency,
                awardedAmount=BigDecimal(released.sellAmount),
                requestedInvoiceAmount=amount,
                billingEvidenceId=request.billingMilestoneEvidenceId,
                legalTaxReviewReference=request.legalTaxReviewReference,
                contractualBillableReference=request.contractualBillableReference,
                invoiceIssueDate=request.invoiceIssueDate,
                invoiceDueDate=request.invoiceDueDate,
            )
        ) }.getOrNull() ?: unavailable()
        // A naked Evidence ID, even with a valid FK, is never proof that a
        // milestone is legally billable. A canonical Evidence owner must
        // register and verify this exact target type before flag activation.
        if(!environment.getProperty(
              "hiltech.finance.receivables.canonical-billing-evidence-ready",
              Boolean::class.java,false,
            )) unavailable()
        val verified=jdbc.queryForObject(
            """
            SELECT EXISTS(
              SELECT 1 FROM evidence e
              JOIN evidence_upload_session s ON s.evidence_id=e.id
              WHERE e.id=? AND e.organization_id=?
                AND e.target_type='FINANCE_CLIENT_BILLING_MILESTONE'
                AND e.target_id=?
                AND e.storage_state='READY' AND s.state='READY'
                AND e.finalized_at IS NOT NULL AND s.finalized_at IS NOT NULL
                AND e.object_key_ref IS NOT NULL
                AND e.sha256=s.expected_sha256
                AND e.size_bytes=s.expected_size_bytes
            )
            """.trimIndent(),Boolean::class.java,
            request.billingMilestoneEvidenceId,org,opportunity,
        )==true
        if(!verified) unavailable()
        val draftId=UUID.nameUUIDFromBytes(
            "hiltech:finance-ar-candidate:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,opportunity,released.sourceFingerprintSha256,
                request.billingMilestoneEvidenceId,validated.amount,
                request.invoiceIssueDate,request.invoiceDueDate,
                request.legalTaxReviewReference.trim(),
                request.contractualBillableReference.trim()).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_AR_CANDIDATE_CREATED",
            "FINANCE_AR_INVOICE_CANDIDATE",draftId,fingerprint,correlation,
        )) {
            // Lock the parent opportunity to serialize attempts per award.
            val locked=jdbc.query(
                """SELECT id FROM commercial_opportunity WHERE id=?
                   AND organization_id=? FOR UPDATE""",
                { rs,_ ->rs.getObject("id",UUID::class.java) },
                opportunity,org,
            ).singleOrNull() ?: stale()
            if(locked!=opportunity ||
                award.detail(actor,org,opportunity)!=released) stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_ar_invoice_candidate(
                    id,operation_id,organization_id,opportunity_id,project_id,
                    customer_organization_id,signed_contract_id,
                    verified_client_po_id,commercial_source_sha256,
                    billing_milestone_evidence_id,
                    contractual_billable_reference,legal_tax_review_reference,
                    invoice_issue_date,invoice_due_date,currency,requested_amount,
                    state,created_by_user_id,created_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?)
                """.trimIndent(),
                draftId,request.operationId,org,opportunity,released.projectId,
                released.clientOrganizationId,released.contractId,
                released.clientPoId,released.sourceFingerprintSha256,
                request.billingMilestoneEvidenceId,
                request.contractualBillableReference.trim(),
                request.legalTaxReviewReference.trim(),
                request.invoiceIssueDate,request.invoiceDueDate,
                released.currency,validated.amount,actor,at,correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AR_CANDIDATE_CREATED",
                targetType="FINANCE_AR_INVOICE_CANDIDATE",targetId=draftId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="VERIFIED_MILESTONE_DRAFT_NO_INVOICE_ISSUANCE",
            ))
            IdempotentCommandOutcome("FINANCE_AR_CANDIDATE_CREATED",
                """{"id":"$draftId"}""",
            )
        }
        return FinanceArCandidateReceipt(
            draftId,org,opportunity,released.clientOrganizationId,
            released.sourceFingerprintSha256,validated.amount.toPlainString(),
            released.currency,replayed=outcome.replayed,
        )
    }
    fun status(actor:UUID,org:UUID,id:UUID):FinanceArCandidateReceipt {
        requireFinance(actor,org)
        return jdbc.query(
            """
            SELECT id,organization_id,opportunity_id,
               customer_organization_id,commercial_source_sha256,
               requested_amount,currency
            FROM finance_ar_invoice_candidate
            WHERE id=? AND organization_id=? AND state='DRAFT'
            """.trimIndent(),
            { rs,_ ->FinanceArCandidateReceipt(
                rs.getObject("id",UUID::class.java),
                rs.getObject("organization_id",UUID::class.java),
                rs.getObject("opportunity_id",UUID::class.java),
                rs.getObject("customer_organization_id",UUID::class.java),
                rs.getString("commercial_source_sha256"),
                rs.getBigDecimal("requested_amount").toPlainString(),
                rs.getString("currency"),
            ) },id,org,
        ).singleOrNull() ?: unavailable()
    }
    private fun requireFinance(actor: UUID, org: UUID) {
        if (!environment.getProperty("hiltech.finance.receivables.invoice-candidate.enabled", Boolean::class.java, false)) unavailable()
        val property = "hiltech.finance.receivables.invoice-candidate.preparer-authority-key"
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, property,
            environment.getProperty(property), clock.instant()) ?: unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AR_INVOICE_CANDIDATE_UNAVAILABLE",
        "Verified billing evidence or Finance authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_AR_SOURCE_STALE",
        "Commercial award changed while preparing Finance draft.",
        HttpStatus.CONFLICT,
    )
}
@RestController
class FinanceArInvoiceCandidateController(
    private val service:FinanceArInvoiceCandidateService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/client-award-sources/{opportunityId}/invoice-candidates")
    fun create(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable opportunityId:UUID,@RequestBody body:FinanceArCreateCandidateRequest
    ):FinanceArCandidateReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return service.create(actor(http),orgId,opportunityId,body,c.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/invoice-candidates/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceArCandidateReceipt =
        service.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance login required.",HttpStatus.UNAUTHORIZED,
            )
}
