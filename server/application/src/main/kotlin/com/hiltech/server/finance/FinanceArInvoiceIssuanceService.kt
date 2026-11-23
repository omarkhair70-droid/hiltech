package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
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
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class FinanceArPrepareIssuance(
    val operationId:UUID,val invoiceNumber:String,
    val invoiceDocumentEvidenceId:UUID,
)
data class FinanceArIssuanceOperation(val operationId:UUID)
data class FinanceArIssuanceReceipt(
    val proposalId:UUID,val candidateId:UUID,val organizationId:UUID,
    val invoiceNumber:String,val state:String,
    val approvalRequestId:UUID?=null,val issuedInvoiceId:UUID?=null,
    val replayed:Boolean=false,val recordedInternally:Boolean=false,
    val invoiceSentExternally:Boolean=false,
    val receivableRecognized:Boolean=false,
    val paymentCollected:Boolean=false,
)

/**
 * The company must separately approve invoice sequence & finalized evidence.
 * No external delivery, e-invoice gateway, AR posting or bank transactions.
 */
@Service
class FinanceArInvoiceIssuanceService(
    private val jdbc:JdbcTemplate,
    private val subject:FinanceArInvoiceIssuanceApprovalSubject,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val idempotency:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val environment:Environment,
    private val clock:Clock,
) {
    @Transactional
    fun prepare(actor:UUID,org:UUID,candidate:UUID,request:FinanceArPrepareIssuance,
                correlation:String):FinanceArIssuanceReceipt {
        authority(actor,org,"hiltech.finance.receivables.invoice-issuance.preparer-authority-key")
        val key=subject.policyKey() ?: unavailable()
        val policy=approvals.loadActivePolicy(org,key,clock.instant())?.takeIf {
            it.organizationId==org && it.approvalRequired &&
                it.mode==ApprovalPolicyMode.SINGLE &&
                !it.authorityKey.isNullOrBlank()
        } ?: unavailable()
        val reviewer=approvals.resolveCurrentAuthority(
            org,policy.authorityKey!!,clock.instant(),
        ) ?: unavailable()
        if(reviewer.organizationId!=org ||
           approvals.actorMatchesAuthority(actor,reviewer,clock.instant())) unavailable()
        val source=jdbc.query(
            """SELECT id,customer_organization_id,commercial_source_sha256,
              requested_amount,currency,invoice_issue_date,invoice_due_date,
              created_by_user_id FROM finance_ar_invoice_candidate
              WHERE id=? AND organization_id=? AND state='DRAFT'""",
            { rs,_ -> FinanceArIssuanceCandidateRow(
                rs.getObject("id",UUID::class.java),
                rs.getObject("customer_organization_id",UUID::class.java),
                rs.getString("commercial_source_sha256"),
                rs.getBigDecimal("requested_amount"),rs.getString("currency"),
                rs.getDate("invoice_issue_date").toLocalDate(),
                rs.getDate("invoice_due_date").toLocalDate(),
                rs.getObject("created_by_user_id",UUID::class.java),
            ) },candidate,org,
        ).singleOrNull() ?: stale()
        if(actor!=source.createdBy) unavailable()
        if(!subject.documentReady(org,candidate,request.invoiceDocumentEvidenceId))
            unavailable()
        val snap=FinanceArIssuanceSnapshot(
            org,candidate,source.customer,request.invoiceNumber.trim(),
            request.invoiceDocumentEvidenceId,source.hash,source.amount,
            source.currency,source.issuedOn,source.dueOn,actor,
            UUID(0L,0L),true,true,true,
        )
        runCatching { FinanceArInvoiceIssuanceRules.validate(snap) }
            .getOrNull() ?: unavailable()
        val version=FinanceArInvoiceIssuanceVersion.derive(
            snap,policy.versionNumber,reviewer.id,reviewer.version,
        )
        val id=UUID.nameUUIDFromBytes(
            "hiltech:ar-issuance-proposal:${request.operationId}"
                .toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,candidate,request.invoiceNumber.trim(),
                request.invoiceDocumentEvidenceId,source.hash,version).joinToString("|"),
        )
        val result=idempotency.execute(IdempotentCommandSpec(
            request.operationId,actor,"FINANCE_AR_ISSUANCE_PROPOSED",
            "FINANCE_AR_ISSUANCE_PROPOSAL",id,fingerprint,correlation,
        )) {
            val locked=jdbc.query(
                """SELECT id FROM finance_ar_invoice_candidate
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                { rs,_ ->rs.getObject("id",UUID::class.java) },candidate,org,
            ).singleOrNull() ?: stale()
            if(locked!=candidate || !subject.documentReady(
                org,candidate,request.invoiceDocumentEvidenceId,
            )) stale()
            check(jdbc.update(
                """
                INSERT INTO finance_ar_invoice_issuance_proposal(
                   id,operation_id,organization_id,candidate_id,invoice_number,
                   invoice_document_evidence_id,source_fingerprint_sha256,
                   approval_policy_key,approval_subject_version,
                   created_by_user_id,created_at
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,request.operationId,org,candidate,request.invoiceNumber.trim(),
                request.invoiceDocumentEvidenceId,source.hash,key,version,
                actor,clock.instant().atOffset(ZoneOffset.UTC),
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AR_ISSUANCE_PROPOSED",
                targetType="FINANCE_AR_ISSUANCE_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="COMPANY_INVOICE_DOCUMENT_REVIEW_NO_SEND",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AR_ISSUANCE_PROPOSED","""{"id":"$id"}""",
            )
        }
        return FinanceArIssuanceReceipt(
            id,candidate,org,request.invoiceNumber.trim(),
            "PROPOSED",replayed=result.replayed,
        )
    }

    fun requestReview(actor:UUID,org:UUID,proposal:UUID,operation:UUID,
                      correlation:String):FinanceArIssuanceReceipt {
        authority(actor,org,"hiltech.finance.receivables.invoice-issuance.preparer-authority-key")
        val snap=subject.current(org,proposal) ?: stale()
        if(snap.preparedBy!=actor) unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_AR_INVOICE_ISSUANCE_SUBJECT,
            proposal,snap.subjectVersion)
        val old=engine.subjectStatus(ref,snap.policyKey)
        if(old!=null) {
            if(old.state!=ApprovalRequestState.PENDING) unavailable()
            return receipt(snap,"PENDING",approval=old.approvalRequestId,
                replayed=true)
        }
        val requested=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snap.policyKey,reasonCode="FINANCE_AR_INVOICE_ISSUANCE_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if(!requested.approvalRequired || requested.approvalRequestId==null ||
           requested.state!=ApprovalRequestState.PENDING ||
           requested.policyKey!=snap.policyKey ||
           requested.policyVersion!=snap.policyVersion) unavailable()
        return receipt(snap,"PENDING",approval=requested.approvalRequestId,
            replayed=requested.replayed)
    }

    @Transactional
    fun record(actor:UUID,org:UUID,proposal:UUID,operation:UUID,
               correlation:String):FinanceArIssuanceReceipt {
        authority(actor,org,"hiltech.finance.receivables.invoice-issuance.operator-authority-key")
        historical(org,proposal)?.let { old ->
            val original=jdbc.query(
                """SELECT operation_id,issued_by_user_id FROM finance_ar_issued_invoice
                   WHERE organization_id=? AND id=?""",
                { rs,_ ->rs.getObject("operation_id",UUID::class.java) to
                    rs.getObject("issued_by_user_id",UUID::class.java) },
                org,old.issuedInvoiceId,
            ).singleOrNull() ?: unavailable()
            if(original.first!=operation || original.second!=actor) unavailable()
            return old.copy(replayed=true)
        }
        val snap=subject.current(org,proposal) ?: stale()
        if(snap.preparedBy==actor) unavailable()
        val ref=ApprovalSubjectRef(org,FINANCE_AR_INVOICE_ISSUANCE_SUBJECT,
            proposal,snap.subjectVersion)
        val approved=engine.subjectStatus(ref,snap.policyKey)?.takeIf {
            it.state==ApprovalRequestState.APPROVED &&
                it.subjectVersion==snap.subjectVersion
        } ?: unavailable()
        val invoiceId=UUID.nameUUIDFromBytes(
            "hiltech:ar-issued-invoice:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,proposal,snap.subjectVersion,
                approved.approvalRequestId,snap.invoiceNumber).joinToString("|"),
        )
        val outcome=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_AR_ISSUANCE_RECORDED",
            "FINANCE_AR_ISSUED_INVOICE",invoiceId,fingerprint,correlation,
        )) {
            val locked=jdbc.query(
                """SELECT id FROM finance_ar_invoice_candidate
                   WHERE id=? AND organization_id=? FOR UPDATE""",
                { rs,_ ->rs.getObject("id",UUID::class.java) },
                snap.candidateId,org,
            ).singleOrNull() ?: stale()
            if(locked!=snap.candidateId || subject.current(org,proposal)!=snap)
                stale()
            val original=FinanceArIssuanceSnapshot(
                org,snap.candidateId,snap.customerOrganizationId,
                snap.invoiceNumber,snap.documentEvidenceId,snap.commercialSourceHash,
                snap.amount,snap.currency,snap.issueDate,snap.dueDate,
                snap.preparedBy,actor,true,true,true,
            )
            runCatching { FinanceArInvoiceIssuanceRules.validate(original) }
                .getOrNull() ?: unavailable()
            check(jdbc.update(
                """
                INSERT INTO finance_ar_issued_invoice(
                   id,operation_id,organization_id,candidate_id,proposal_id,
                   invoice_number,invoice_document_evidence_id,
                   invoice_approval_id,approval_policy_key,approval_subject_version,
                   source_fingerprint_sha256,customer_organization_id,
                   currency,gross_amount,issue_date,due_date,
                   issued_by_user_id,issued_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                invoiceId,operation,org,snap.candidateId,proposal,
                snap.invoiceNumber,snap.documentEvidenceId,
                approved.approvalRequestId,snap.policyKey,snap.subjectVersion,
                snap.commercialSourceHash,snap.customerOrganizationId,
                snap.currency,snap.amount,snap.issueDate,snap.dueDate,
                actor,clock.instant().atOffset(ZoneOffset.UTC),correlation,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AR_ISSUANCE_RECORDED",
                targetType="FINANCE_AR_ISSUED_INVOICE",targetId=invoiceId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="APPROVED_INTERNAL_ISSUANCE_NO_SEND_OR_AR_POSTING",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AR_ISSUANCE_RECORDED","""{"id":"$invoiceId"}""",
            )
        }
        return receipt(snap,"RECORDED",approval=approved.approvalRequestId,
            invoice=invoiceId,replayed=outcome.replayed)
    }

    fun status(actor:UUID,org:UUID,proposal:UUID):FinanceArIssuanceReceipt {
        authority(actor,org,"hiltech.finance.receivables.invoice-issuance.preparer-authority-key")
        historical(org,proposal)?.let { return it }
        val snap=subject.current(org,proposal) ?: stale()
        val review=engine.subjectStatus(
            ApprovalSubjectRef(org,FINANCE_AR_INVOICE_ISSUANCE_SUBJECT,
                proposal,snap.subjectVersion),snap.policyKey)
        return receipt(snap,review?.state?.name ?: "PROPOSED",
            approval=review?.approvalRequestId)
    }
    private fun historical(org:UUID,proposal:UUID):FinanceArIssuanceReceipt?=
        jdbc.query(
            """SELECT id,candidate_id,invoice_number,invoice_approval_id
               FROM finance_ar_issued_invoice
               WHERE organization_id=? AND proposal_id=?""",
            {rs,_ ->FinanceArIssuanceReceipt(
                proposal,rs.getObject("candidate_id",UUID::class.java),
                org,rs.getString("invoice_number"),"RECORDED",
                approvalRequestId=rs.getObject("invoice_approval_id",UUID::class.java),
                issuedInvoiceId=rs.getObject("id",UUID::class.java),
                recordedInternally=true,
            )},org,proposal,
        ).singleOrNull()
    private fun receipt(s:FinanceArIssuanceReviewSnapshot,state:String,
                        approval:UUID?=null,invoice:UUID?=null,
                        replayed:Boolean=false)=FinanceArIssuanceReceipt(
        s.proposalId,s.candidateId,s.organizationId,s.invoiceNumber,state,
        approvalRequestId=approval,issuedInvoiceId=invoice,replayed=replayed,
        recordedInternally=invoice!=null,
    )
    private fun authority(actor:UUID,org:UUID,property:String) {
        if(!subject.enabled()) unavailable()
        val key=environment.getProperty(property,"").trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now)) unavailable()
        val binding=approvals.resolveCurrentAuthority(org,key,now) ?: unavailable()
        if(binding.organizationId!=org ||
           !approvals.actorMatchesAuthority(actor,binding,now)) unavailable()
    }
    private fun unavailable():Nothing=throw ProductApiException(
        "FINANCE_AR_ISSUANCE_UNAUTHORIZED",
        "Approved company tax invoice issuance authority or evidence unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "FINANCE_AR_ISSUANCE_STALE",
        "Invoice candidate, document, or approval source changed.",
        HttpStatus.CONFLICT,
    )
}
private data class FinanceArIssuanceCandidateRow(
    val id:UUID,val customer:UUID,val hash:String,
    val amount:java.math.BigDecimal,val currency:String,
    val issuedOn:java.time.LocalDate,val dueOn:java.time.LocalDate,
    val createdBy:UUID,
)

@RestController
class FinanceArInvoiceIssuanceController(
    private val finance:FinanceArInvoiceIssuanceService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/invoice-candidates/{candidateId}/issuance-proposals")
    fun prepare(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable candidateId:UUID,@RequestBody body:FinanceArPrepareIssuance
    ):FinanceArIssuanceReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return finance.prepare(actor(http),orgId,candidateId,body,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/invoice-issuance-proposals/{id}/request-review")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceArIssuanceOperation
    ):FinanceArIssuanceReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return finance.requestReview(actor(http),orgId,id,body.operationId,c.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/invoice-issuance-proposals/{id}/record")
    fun record(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceArIssuanceOperation
    ):FinanceArIssuanceReceipt {
        val c=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,body.operationId)
        return finance.record(actor(http),orgId,id,body.operationId,c.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/invoice-issuance-proposals/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceArIssuanceReceipt =
        finance.status(actor(http),orgId,id)
    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",HttpStatus.UNAUTHORIZED)
}
