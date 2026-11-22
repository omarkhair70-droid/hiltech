package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestResult
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

data class CreateServiceOrderCandidateRequest(
    val operationId:UUID,val rfqId:UUID,val quoteId:UUID,
)
data class ServiceOrderCandidateView(
    val id:UUID,val organizationId:UUID,val rfqId:UUID,val quoteId:UUID,
    val supplierOrganizationId:UUID,val currency:String,val totalAmount:String,
    val sourceCurrent:Boolean,val approvalRequestId:UUID?,
    val approvalState:String?,val serviceOrderId:UUID?,
)
data class ServiceOrderCandidateReceipt(
    val candidate:ServiceOrderCandidateView,val replayed:Boolean,
)
data class RequestServiceOrderApprovalRequest(val operationId:UUID)
data class IssueServiceOrderRequest(val operationId:UUID,val expectedCandidateVersion:Long)
data class IssuedServiceOrderView(
    val orderId:UUID,val candidateId:UUID,val organizationId:UUID,
    val supplierOrganizationId:UUID,val approvalRequestId:UUID,
    val quoteId:UUID,val totalAmount:String,val currency:String,
    val supplierDispatchState:String="NOT_SENT",val serviceAccepted:Boolean=false,
    val invoiceMatched:Boolean=false,val paymentAuthorized:Boolean=false,
    val replayed:Boolean=false,
)

/**
 * Three ordinary actions: buyer selects current verified quotation, requests
 * canonical ApprovalEngine decision, separately authorized issuer records an
 * INTERNAL service order only when the decision is APPROVED. No supplier
 * emails, external commitments or financial mutation are done here.
 */
@Service
class ServiceOrderService(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val engine:ApprovalEngineService,
    private val sourceGuard:ServiceOrderCandidateApprovalSubject,
    private val commands:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-orders.enabled:false}")
    private val enabled:Boolean=false,
    @Value("\${hiltech.procurement.service-sourcing.enabled:false}")
    private val sourcingEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-orders.buyer-authority-key:}")
    private val buyerKey:String="",
    @Value("\${hiltech.procurement.service-orders.issuer-authority-key:}")
    private val issuerKey:String="",
    @Value("\${hiltech.procurement.service-orders.approval-policy-key:}")
    private val policyKey:String="",
) {
    private data class Quote(
        val id:UUID,val rfq:UUID,val org:UUID,val supplier:UUID,
        val currency:String,val amount:BigDecimal,val scope:String,val criteria:String,
    )
    private data class Candidate(
        val id:UUID,val org:UUID,val rfq:UUID,val quote:UUID,
        val supplier:UUID,val currency:String,val amount:BigDecimal,
    )
    private data class Approval(
        val id:UUID,val state:String,val policyVersion:Int,
    )

    fun create(ctx:ProcurementDraftContext,req:CreateServiceOrderCandidateRequest):
        ServiceOrderCandidateReceipt {
        activeOrHidden()
        if(ctx.operationId!=req.operationId)invalid()
        val chosen=quote(req.rfqId,req.quoteId)?:hidden()
        requireBuyer(ctx.actor,chosen.org)
        val id=key("service-order-candidate",ctx.operationId)
        val fp=fingerprint(req.rfqId,req.quoteId)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_ORDER_CANDIDATE_CREATED",
            "SERVICE_ORDER_CANDIDATE",id,fp,ctx.correlationId,
        )) {
            lockRfq(req.rfqId)
            val current=quote(req.rfqId,req.quoteId)?:hidden()
            requireBuyer(ctx.actor,current.org)
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_order_candidate (
                  id,operation_id,organization_id,rfq_id,quote_id,
                  supplier_organization_id,currency,total_amount,
                  service_specification,service_acceptance_criteria,
                  created_by_user_id,created_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,current.org,current.rfq,current.id,
                current.supplier,current.currency,current.amount,current.scope,current.criteria,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_ORDER_CANDIDATE_CREATED",id)
            IdempotentCommandOutcome("SERVICE_ORDER_CANDIDATE_CREATED","{\"id\":\""+id+"\"}")
        }
        return ServiceOrderCandidateReceipt(view(ctx.actor,id),result.replayed)
    }

    fun requestApproval(ctx:ProcurementDraftContext,candidateId:UUID,
                        input:RequestServiceOrderApprovalRequest):ApprovalRequestResult {
        activeOrHidden()
        if(ctx.operationId!=input.operationId)invalid()
        val candidate=candidate(candidateId)?:hidden()
        requireBuyer(ctx.actor,candidate.org)
        val key=policyKey.trim()
        if(!validKey(key) || !sourceGuard.isCurrent(subject(candidate)))stale()
        val policy=approvals.loadActivePolicy(candidate.org,key,clock.instant())?:hidden()
        if(!policy.approvalRequired || policy.mode!=ApprovalPolicyMode.SINGLE)hidden()
        // Explicit false: being the configured approver cannot silently waive
        // approval. Actual approvers decide in the existing ApprovalEngine UI.
        return engine.requestException(
            actorUserId=ctx.actor,operationId=ctx.operationId,
            subject=subject(candidate),policyKey=key,
            reasonCode="SERVICE_ORDER_PURCHASE",safeReasonSummary=
                "Independent review of selected external service supplier quotation.",
            correlationId=ctx.correlationId,allowAuthorityBypass=false,
        )
    }

    fun issue(ctx:ProcurementDraftContext,candidateId:UUID,
              input:IssueServiceOrderRequest):IssuedServiceOrderView {
        activeOrHidden()
        if(ctx.operationId!=input.operationId || input.expectedCandidateVersion!=1L) invalid()
        val initial=candidate(candidateId)?:hidden()
        requireIssuer(ctx.actor,initial.org)
        val id=key("service-order",ctx.operationId)
        val fp=fingerprint(candidateId,input.expectedCandidateVersion)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_ORDER_ISSUED",
            "PROCUREMENT_SERVICE_ORDER",id,fp,ctx.correlationId,
        )) {
            val current=lockedCandidate(candidateId)?:hidden()
            val binding=requireIssuer(ctx.actor,current.org)
            if(!sourceGuard.isCurrent(subject(current)))stale()
            val policy=approvals.loadActivePolicy(current.org,policyKey.trim(),clock.instant())
                ?:hidden()
            if(!policy.approvalRequired || policy.mode!=ApprovalPolicyMode.SINGLE)hidden()
            val approval=jdbc.query(
                """
                SELECT id,state,policy_version FROM approval_request
                WHERE organization_id=? AND subject_type='SERVICE_ORDER_CANDIDATE'
                  AND subject_id=? AND subject_version=1
                  AND policy_key=? FOR UPDATE
                """.trimIndent(),
                {rs,_->Approval(rs.getObject("id",UUID::class.java),
                    rs.getString("state"),rs.getInt("policy_version"))},
                current.org,current.id,policyKey.trim(),
            ).singleOrNull()?:hidden()
            if(approval.state!="APPROVED" || approval.policyVersion!=policy.versionNumber)
                stale()
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_order (
                  id,operation_id,organization_id,candidate_id,quote_id,
                  supplier_organization_id,approval_request_id,approval_policy_key,
                  approval_policy_version,issuer_authority_binding_id,currency,
                  total_amount,issued_by_user_id,issued_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,current.org,current.id,current.quote,current.supplier,
                approval.id,policyKey.trim(),policy.versionNumber,binding,
                current.currency,current.amount,ctx.actor,at.atOffset(ZoneOffset.UTC),
                ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_ORDER_ISSUED",id)
            IdempotentCommandOutcome("SERVICE_ORDER_ISSUED","{\"id\":\""+id+"\"}")
        }
        return issued(ctx.actor,id).copy(replayed=result.replayed)
    }

    fun queue(actor:UUID,organizationId:UUID):List<ServiceOrderCandidateView> {
        requireIssuer(actor,organizationId)
        val ids=jdbc.query(
            """
            SELECT c.id FROM procurement_service_order_candidate c
            LEFT JOIN procurement_service_order o ON o.candidate_id=c.id
            WHERE c.organization_id=? AND o.id IS NULL
            ORDER BY c.created_at DESC,c.id DESC LIMIT 100
            """.trimIndent(),{rs,_->rs.getObject("id",UUID::class.java)},organizationId)
        return ids.map {view(actor,it)}
    }

    fun view(actor:UUID,id:UUID):ServiceOrderCandidateView {
        activeOrHidden()
        val candidate=candidate(id)?:hidden()
        if(!authorized(actor,candidate.org,buyerKey) &&
            !authorized(actor,candidate.org,issuerKey))hidden()
        val request=approvals.loadSubjectApproval(subject(candidate),policyKey.trim())
        val orderId=jdbc.query(
            "SELECT id FROM procurement_service_order WHERE candidate_id=?",
            {rs,_->rs.getObject("id",UUID::class.java)},id,
        ).singleOrNull()
        return ServiceOrderCandidateView(candidate.id,candidate.org,
            candidate.rfq,candidate.quote,candidate.supplier,candidate.currency,
            candidate.amount.toPlainString(),
            sourceGuard.isCurrent(subject(candidate)),request?.requestId,
            request?.state?.name,orderId)
    }

    fun issued(actor:UUID,id:UUID):IssuedServiceOrderView {
        activeOrHidden()
        val row=jdbc.query(
            """
            SELECT id,organization_id,candidate_id,quote_id,supplier_organization_id,
                   approval_request_id,total_amount,currency
            FROM procurement_service_order WHERE id=?
            """.trimIndent(),
            {rs,_->IssuedServiceOrderView(
                rs.getObject("id",UUID::class.java),
                rs.getObject("candidate_id",UUID::class.java),
                rs.getObject("organization_id",UUID::class.java),
                rs.getObject("supplier_organization_id",UUID::class.java),
                rs.getObject("approval_request_id",UUID::class.java),
                rs.getObject("quote_id",UUID::class.java),
                rs.getBigDecimal("total_amount").toPlainString(),
                rs.getString("currency"))},id,
        ).singleOrNull()?:hidden()
        if(!authorized(actor,row.organizationId,issuerKey) &&
            !authorized(actor,row.organizationId,buyerKey))hidden()
        return row
    }

    private fun quote(rfqId:UUID,quoteId:UUID):Quote?=jdbc.query(
        """
        SELECT q.id,q.rfq_id,q.organization_id,q.supplier_organization_id,
               q.currency,q.total_amount,rfq.specification,rfq.acceptance_criteria
        FROM procurement_service_supplier_quote q
        JOIN procurement_service_rfq rfq ON rfq.id=q.rfq_id
          AND rfq.organization_id=q.organization_id
        WHERE q.id=? AND rfq.id=?
        """.trimIndent(),
        {rs,_->Quote(rs.getObject("id",UUID::class.java),
            rs.getObject("rfq_id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java),
            rs.getString("currency"),rs.getBigDecimal("total_amount"),
            rs.getString("specification"),rs.getString("acceptance_criteria"))},
        quoteId,rfqId,
    ).singleOrNull()

    private fun candidate(id:UUID):Candidate?=jdbc.query(
        """
        SELECT id,organization_id,rfq_id,quote_id,supplier_organization_id,
               currency,total_amount FROM procurement_service_order_candidate WHERE id=?
        """.trimIndent(),{rs,_->Candidate(
            rs.getObject("id",UUID::class.java),rs.getObject("organization_id",UUID::class.java),
            rs.getObject("rfq_id",UUID::class.java),rs.getObject("quote_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java),
            rs.getString("currency"),rs.getBigDecimal("total_amount"))},id,
    ).singleOrNull()
    private fun lockedCandidate(id:UUID):Candidate? {
        val row=jdbc.query("SELECT id FROM procurement_service_order_candidate WHERE id=? FOR UPDATE",
            {rs,_->rs.getObject("id",UUID::class.java)},id).singleOrNull()?:return null
        return candidate(row)
    }
    private fun lockRfq(id:UUID) {
        if(jdbc.query("SELECT id FROM procurement_service_rfq WHERE id=? FOR UPDATE",
            {rs,_->rs.getObject("id",UUID::class.java)},id).isEmpty()) hidden()
    }
    private fun subject(candidate:Candidate)=ApprovalSubjectRef(
        candidate.org,"SERVICE_ORDER_CANDIDATE",candidate.id,1L,
    )
    private fun requireBuyer(actor:UUID,org:UUID) {
        if(!authorized(actor,org,buyerKey))hidden()
    }
    private fun requireIssuer(actor:UUID,org:UUID):UUID {
        if(!enabled || !sourcingEnabled || !validKey(policyKey.trim()))hidden()
        val key=issuerKey.trim()
        if(!validKey(key))hidden()
        val at=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,at))hidden()
        val binding=approvals.resolveCurrentAuthority(org,key,at)?:hidden()
        if(binding.organizationId!=org || binding.authorityKey!=key ||
           !approvals.actorMatchesAuthority(actor,binding,at))hidden()
        return binding.id
    }
    private fun authorized(actor:UUID,org:UUID,rawKey:String):Boolean {
        val key=rawKey.trim()
        if(!enabled || !sourcingEnabled || !validKey(key))return false
        val at=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,at))return false
        val authority=approvals.resolveCurrentAuthority(org,key,at)?:return false
        return authority.organizationId==org && authority.authorityKey==key &&
            approvals.actorMatchesAuthority(actor,authority,at)
    }
    private fun activeOrHidden() {if(!enabled || !sourcingEnabled)hidden()}
    private fun validKey(s:String)=Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(s)
    private fun fingerprint(vararg xs:Any?):String =
        IdempotencyKeyContract.fingerprint(xs.joinToString("") {
            val v=it?.toString()?:"(null)";v.length.toString()+":"+v
        })
    private fun key(kind:String,op:UUID):UUID =
        UUID.nameUUIDFromBytes(
            ("hiltech:procurement:"+kind+":"+op).toByteArray(Charsets.UTF_8))
    private fun log(ctx:ProcurementDraftContext,type:String,id:UUID) {
        audit.append(AuditEventRecord(actorUserId=ctx.actor,action=type,
            targetType="PROCUREMENT_SERVICE_ORDER",targetId=id,
            occurredAt=clock.instant(),correlationId=ctx.correlationId))
    }
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_ORDER_UNAVAILABLE","Current service purchasing authorization unavailable.",
        HttpStatus.NOT_FOUND)
    private fun stale():Nothing=throw ProductApiException(
        "SERVICE_ORDER_SOURCE_CHANGED","Service quote, supplier or approval is no longer current.",
        HttpStatus.CONFLICT)
    private fun invalid():Nothing=throw ProductApiException(
        "SERVICE_ORDER_INVALID","Invalid service order command.",HttpStatus.BAD_REQUEST)
}
