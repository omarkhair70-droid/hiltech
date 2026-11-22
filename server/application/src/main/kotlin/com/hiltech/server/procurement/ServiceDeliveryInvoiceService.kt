package com.hiltech.server.procurement

import com.hiltech.server.approval.ApprovalPersistencePort
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
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class CreateServicePurchaseDocumentSlotRequest(
    val operationId:UUID,val documentKind:String,
)
data class ServicePurchaseDocumentSlotReceipt(
    val slotId:UUID,val orderId:UUID,val documentKind:String,val replayed:Boolean,
)
data class RecordServiceDeliveryReviewRequest(
    val operationId:UUID,val documentSlotId:UUID,val evidenceId:UUID,
    val evidenceSha256:String,val outcome:String,val reviewNotes:String,
)
data class ServiceDeliveryReviewView(
    val id:UUID,val serviceOrderId:UUID,val outcome:String,val evidenceId:UUID,
    val evidenceSha256:String,val reviewNotes:String,val replayed:Boolean=false,
)
data class CaptureServiceInvoiceRequest(
    val operationId:UUID,val documentSlotId:UUID,val evidenceId:UUID,
    val evidenceSha256:String,val invoiceNumber:String,val invoiceDate:LocalDate,
    val currency:String,val serviceAmount:String,val taxAmount:String,
)
data class ServiceInvoiceView(
    val invoiceId:UUID,val serviceOrderId:UUID,val invoiceNumber:String,
    val supplierOrganizationId:UUID,val invoiceDate:LocalDate,val currency:String,
    val serviceAmount:String,val taxAmount:String,val totalAmount:String,
    val evidenceId:UUID,val replayed:Boolean=false,
)
data class ServiceInvoiceMatchPreview(
    val invoiceId:UUID,val serviceOrderId:UUID,val resultCode:String,
    val sourceSha256:String,val expectedCurrency:String,val invoicedCurrency:String,
    val expectedTotal:String,val invoicedTotal:String,
    val acceptedEvidenceId:UUID?,val matchEventId:UUID?=null,
    val replayed:Boolean=false,val paymentAuthorized:Boolean=false,
)
data class RecordServiceInvoiceMatchRequest(
    val operationId:UUID,val expectedSourceSha256:String,
)
data class HandOffServiceInvoiceRequest(
    val operationId:UUID,val matchEventId:UUID,
)
data class ServiceInvoiceHandoffReceipt(
    val handoffId:UUID,val invoiceId:UUID,val matchEventId:UUID,
    val reviewOnly:Boolean=true,val payableCreated:Boolean=false,
    val paymentAuthorized:Boolean=false,val replayed:Boolean=false,
)

/**
 * The actual company sequence after issuing a service order:
 * 1. Independent reviewer uploads protected deliverable and records outcome.
 * 2. Finance uploads the actual supplier invoice and enters its amounts.
 * 3. The system compares approved service order + last accepted delivery
 *    against invoice and prepares a review-only Finance handoff.
 * Does not invent stock, send supplier messages, create a payable or pay money.
 */
@Service
class ServiceDeliveryInvoiceService @org.springframework.beans.factory.annotation.Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val approvals:ApprovalPersistencePort,
    private val documentTarget:ServicePurchaseDocumentTargetAdapter,
    private val commands:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-orders.enabled:false}")
    private val orderEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-acceptance.enabled:false}")
    private val acceptanceEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-invoices.enabled:false}")
    private val invoiceEnabled:Boolean=false,
    @Value("\${hiltech.procurement.service-acceptance.authority-key:}")
    private val reviewerKey:String="",
    @Value("\${hiltech.procurement.service-invoices.finance-authority-key:}")
    private val financeKey:String="",
) {
    private data class Order(
        val id:UUID,val org:UUID,val supplier:UUID,val quote:UUID,
        val approval:UUID,val currency:String,val total:BigDecimal,
    )
    private data class Slot(
        val id:UUID,val order:UUID,val org:UUID,val kind:String,
    )
    private data class Delivery(
        val id:UUID,val outcome:String,val evidenceId:UUID,val sha:String,
    )
    private data class Invoice(
        val id:UUID,val order:UUID,val org:UUID,val supplier:UUID,val number:String,
        val date:LocalDate,val currency:String,val amount:BigDecimal,
        val tax:BigDecimal,val total:BigDecimal,val evidenceId:UUID,val sha:String,
    )
    private data class Match(
        val id:UUID,val revision:Long,val result:String,val sha:String,
    )

    fun documentSlot(ctx:ProcurementDraftContext,orderId:UUID,
                     input:CreateServicePurchaseDocumentSlotRequest):
        ServicePurchaseDocumentSlotReceipt {
        if(ctx.operationId!=input.operationId)invalid()
        if(input.documentKind !in setOf("DELIVERY","INVOICE"))invalid()
        configured(input.documentKind)
        val order=order(orderId)?:hidden()
        requireAuthority(ctx.actor,order.org,input.documentKind)
        val id=key("service-purchase-document-slot",ctx.operationId)
        val fp=fingerprint(orderId,input.documentKind)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_DOCUMENT_SLOT_CREATED",
            "SERVICE_PURCHASE_DOCUMENT_SLOT",id,fp,ctx.correlationId,
        )) {
            val actual=lockedOrder(orderId)?:hidden()
            requireAuthority(ctx.actor,actual.org,input.documentKind)
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_document_slot (
                  id,operation_id,organization_id,service_order_id,document_kind,
                  created_by_user_id,created_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,actual.org,actual.id,input.documentKind,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_DOCUMENT_SLOT_CREATED",id)
            IdempotentCommandOutcome("SERVICE_DOCUMENT_SLOT_CREATED","{\"slotId\":\""+id+"\"}")
        }
        return ServicePurchaseDocumentSlotReceipt(id,orderId,input.documentKind,result.replayed)
    }

    fun reviewDelivery(ctx:ProcurementDraftContext,orderId:UUID,
                       req:RecordServiceDeliveryReviewRequest):ServiceDeliveryReviewView {
        if(ctx.operationId!=req.operationId ||
           req.outcome !in setOf("ACCEPTED","NEEDS_REWORK","REJECTED") ||
           !validSha(req.evidenceSha256))invalid()
        val notes=validated(req.reviewNotes,12,1000)
        configured("DELIVERY")
        val order=order(orderId)?:hidden()
        requireAuthority(ctx.actor,order.org,"DELIVERY")
        val id=key("service-delivery-review",ctx.operationId)
        val fp=fingerprint(orderId,req.documentSlotId,req.evidenceId,
            req.evidenceSha256,req.outcome,notes)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_DELIVERY_REVIEWED",
            "PROCUREMENT_SERVICE_ACCEPTANCE",id,fp,ctx.correlationId,
        )) {
            val actual=lockedOrder(orderId)?:hidden()
            requireAuthority(ctx.actor,actual.org,"DELIVERY")
            requireDocument(ctx.actor,actual,req.documentSlotId,"DELIVERY")
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_acceptance (
                  id,operation_id,organization_id,service_order_id,
                  document_slot_id,evidence_id,evidence_sha256,outcome,
                  review_notes,reviewed_by_user_id,reviewed_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,actual.org,orderId,req.documentSlotId,
                req.evidenceId,req.evidenceSha256,req.outcome,notes,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_DELIVERY_REVIEWED",id)
            IdempotentCommandOutcome("SERVICE_DELIVERY_REVIEWED","{\"id\":\""+id+"\"}")
        }
        return ServiceDeliveryReviewView(id,orderId,req.outcome,req.evidenceId,
            req.evidenceSha256,notes,result.replayed)
    }

    fun captureInvoice(ctx:ProcurementDraftContext,orderId:UUID,
                       req:CaptureServiceInvoiceRequest):ServiceInvoiceView {
        if(ctx.operationId!=req.operationId || !validSha(req.evidenceSha256) ||
            !Regex("[A-Z]{3}").matches(req.currency))invalid()
        val number=validated(req.invoiceNumber,2,96)
        val amount=money(req.serviceAmount)
        val tax=money(req.taxAmount)
        configured("INVOICE")
        val order=order(orderId)?:hidden()
        requireAuthority(ctx.actor,order.org,"INVOICE")
        val id=key("service-invoice",ctx.operationId)
        val fp=fingerprint(orderId,req.documentSlotId,req.evidenceId,
            req.evidenceSha256,number,req.invoiceDate,req.currency,amount,tax)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_INVOICE_CAPTURED",
            "PROCUREMENT_SERVICE_INVOICE",id,fp,ctx.correlationId,
        )) {
            val actual=lockedOrder(orderId)?:hidden()
            requireAuthority(ctx.actor,actual.org,"INVOICE")
            requireDocument(ctx.actor,actual,req.documentSlotId,"INVOICE")
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_invoice (
                  id,operation_id,organization_id,service_order_id,
                  supplier_organization_id,document_slot_id,evidence_id,evidence_sha256,
                  invoice_number,invoice_date,currency,service_amount,tax_amount,
                  total_amount,captured_by_user_id,captured_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,actual.org,orderId,actual.supplier,
                req.documentSlotId,req.evidenceId,req.evidenceSha256,
                number,req.invoiceDate,req.currency,amount,tax,
                amount.add(tax),ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_INVOICE_CAPTURED",id)
            IdempotentCommandOutcome("SERVICE_INVOICE_CAPTURED","{\"id\":\""+id+"\"}")
        }
        return invoiceView(invoice(id)?:hidden()).copy(replayed=result.replayed)
    }

    fun invoices(actor:UUID,organizationId:UUID):List<ServiceInvoiceView> {
        requireAuthority(actor,organizationId,"INVOICE")
        return jdbc.query(
            """
            SELECT id FROM procurement_service_invoice WHERE organization_id=?
            ORDER BY captured_at DESC,id DESC LIMIT 100
            """.trimIndent(),{rs,_->rs.getObject("id",UUID::class.java)},organizationId,
        ).map {invoiceView(invoice(it)?:hidden())}
    }

    fun invoiceDetail(actor:UUID,id:UUID):ServiceInvoiceView {
        configured("INVOICE")
        val inv=invoice(id)?:hidden()
        requireAuthority(actor,inv.org,"INVOICE")
        return invoiceView(inv)
    }

    fun preview(actor:UUID,id:UUID):ServiceInvoiceMatchPreview {
        configured("INVOICE")
        val inv=invoice(id)?:hidden()
        requireAuthority(actor,inv.org,"INVOICE")
        return compute(inv)
    }

    fun recordMatch(ctx:ProcurementDraftContext,id:UUID,
                    req:RecordServiceInvoiceMatchRequest):ServiceInvoiceMatchPreview {
        if(ctx.operationId!=req.operationId || !validSha(req.expectedSourceSha256))invalid()
        configured("INVOICE")
        val original=invoice(id)?:hidden()
        requireAuthority(ctx.actor,original.org,"INVOICE")
        val eventId=key("service-invoice-match",ctx.operationId)
        val fp=fingerprint(id,req.expectedSourceSha256)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_INVOICE_MATCH_RECORDED",
            "PROCUREMENT_SERVICE_INVOICE_MATCH",eventId,fp,ctx.correlationId,
        )) {
            lockedOrder(original.order)?:hidden()
            lockInvoice(id)
            val current=invoice(id)?:hidden()
            requireAuthority(ctx.actor,current.org,"INVOICE")
            val preview=compute(current)
            if(preview.sourceSha256!=req.expectedSourceSha256)stale()
            val revision=jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision),0)+1 FROM procurement_service_invoice_match WHERE service_invoice_id=?",
                Long::class.java,id,
            )?:1L
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_invoice_match (
                  id,operation_id,organization_id,service_invoice_id,service_order_id,
                  revision,result_code,accepted_evidence_id,expected_currency,
                  invoiced_currency,expected_total,invoiced_total,source_sha256,
                  checked_by_user_id,checked_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                eventId,ctx.operationId,current.org,current.id,current.order,revision,
                preview.resultCode,preview.acceptedEvidenceId,preview.expectedCurrency,
                preview.invoicedCurrency,BigDecimal(preview.expectedTotal),
                BigDecimal(preview.invoicedTotal),preview.sourceSha256,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_INVOICE_MATCH_RECORDED",eventId)
            IdempotentCommandOutcome("SERVICE_INVOICE_MATCH_RECORDED",
                "{\"matchEventId\":\""+eventId+"\"}")
        }
        return compute(invoice(id)?:hidden()).copy(matchEventId=eventId,replayed=result.replayed)
    }

    fun handoff(ctx:ProcurementDraftContext,id:UUID,
                req:HandOffServiceInvoiceRequest):ServiceInvoiceHandoffReceipt {
        if(ctx.operationId!=req.operationId)invalid()
        configured("INVOICE")
        val inv=invoice(id)?:hidden()
        requireAuthority(ctx.actor,inv.org,"INVOICE")
        val handoffId=key("service-invoice-finance-handoff",ctx.operationId)
        val fp=fingerprint(id,req.matchEventId)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_INVOICE_FINANCE_HANDOFF",
            "PROCUREMENT_SERVICE_FINANCE_HANDOFF",handoffId,fp,ctx.correlationId,
        )) {
            lockedOrder(inv.order)?:hidden()
            lockInvoice(id)
            val current=invoice(id)?:hidden()
            requireAuthority(ctx.actor,current.org,"INVOICE")
            val live=compute(current)
            if(live.resultCode!="MATCHED")stale()
            val recorded=jdbc.query(
                """
                SELECT id,revision,result_code,source_sha256
                FROM procurement_service_invoice_match
                WHERE id=? AND service_invoice_id=? AND organization_id=?
                FOR UPDATE
                """.trimIndent(),
                {rs,_->Match(rs.getObject("id",UUID::class.java),
                    rs.getLong("revision"),rs.getString("result_code"),
                    rs.getString("source_sha256"))},
                req.matchEventId,id,current.org,
            ).singleOrNull()?:stale()
            val latest=jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision),0) FROM procurement_service_invoice_match WHERE service_invoice_id=?",
                Long::class.java,id,
            )?:0L
            if(recorded.revision!=latest || recorded.result!="MATCHED" ||
               recorded.sha!=live.sourceSha256)stale()
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_finance_handoff (
                  id,operation_id,organization_id,service_invoice_id,match_event_id,
                  reviewed_by_user_id,reviewed_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?)
                """.trimIndent(),
                handoffId,ctx.operationId,current.org,id,req.matchEventId,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            log(ctx,"PROCUREMENT_SERVICE_INVOICE_FINANCE_HANDOFF",handoffId)
            IdempotentCommandOutcome("SERVICE_FINANCE_HANDOFF",
                "{\"handoffId\":\""+handoffId+"\"}")
        }
        return ServiceInvoiceHandoffReceipt(handoffId,id,req.matchEventId,
            replayed=result.replayed)
    }

    private fun compute(inv:Invoice):ServiceInvoiceMatchPreview {
        val order=order(inv.order)?:hidden()
        if(inv.org!=order.org || inv.supplier!=order.supplier)hidden()
        val last=jdbc.query(
            """
            SELECT id,outcome,evidence_id,evidence_sha256
            FROM procurement_service_acceptance
            WHERE organization_id=? AND service_order_id=?
            ORDER BY reviewed_at DESC,id DESC LIMIT 1
            """.trimIndent(),
            {rs,_->Delivery(
                rs.getObject("id",UUID::class.java),rs.getString("outcome"),
                rs.getObject("evidence_id",UUID::class.java),
                rs.getString("evidence_sha256"))},
            inv.org,inv.order,
        ).singleOrNull()
        val result=when {
            last==null -> "NO_ACCEPTANCE"
            last.outcome!="ACCEPTED" -> "DELIVERY_REVIEW_REQUIRED"
            order.currency!=inv.currency -> "CURRENCY_MISMATCH"
            order.total.compareTo(inv.total)!=0 -> "AMOUNT_MISMATCH"
            else -> "MATCHED"
        }
        val sha=sha256(fingerprint(
            order.id,order.quote,order.approval,order.currency,
            order.total.toPlainString(),inv.id,inv.evidenceId,
            inv.sha,inv.currency,inv.total.toPlainString(),
            last?.id,last?.evidenceId,last?.sha,last?.outcome,
        ))
        return ServiceInvoiceMatchPreview(inv.id,inv.order,result,sha,
            order.currency,inv.currency,order.total.toPlainString(),
            inv.total.toPlainString(),
            last?.takeIf{it.outcome=="ACCEPTED"}?.evidenceId)
    }

    private fun order(id:UUID):Order?=jdbc.query(
        """
        SELECT id,organization_id,supplier_organization_id,quote_id,
               approval_request_id,currency,total_amount
        FROM procurement_service_order WHERE id=?
        """.trimIndent(),
        {rs,_->Order(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java),
            rs.getObject("quote_id",UUID::class.java),
            rs.getObject("approval_request_id",UUID::class.java),
            rs.getString("currency"),rs.getBigDecimal("total_amount"))},id,
    ).singleOrNull()
    private fun lockedOrder(id:UUID):Order? {
        if(jdbc.query("SELECT id FROM procurement_service_order WHERE id=? FOR UPDATE",
            {rs,_->rs.getObject("id",UUID::class.java)},id).isEmpty())return null
        return order(id)
    }
    private fun invoice(id:UUID):Invoice?=jdbc.query(
        """
        SELECT id,service_order_id,organization_id,supplier_organization_id,
               invoice_number,invoice_date,currency,service_amount,tax_amount,
               total_amount,evidence_id,evidence_sha256
        FROM procurement_service_invoice WHERE id=?
        """.trimIndent(),
        {rs,_->Invoice(rs.getObject("id",UUID::class.java),
            rs.getObject("service_order_id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java),
            rs.getString("invoice_number"),rs.getObject("invoice_date",LocalDate::class.java),
            rs.getString("currency"),rs.getBigDecimal("service_amount"),
            rs.getBigDecimal("tax_amount"),rs.getBigDecimal("total_amount"),
            rs.getObject("evidence_id",UUID::class.java),
            rs.getString("evidence_sha256"))},id,
    ).singleOrNull()
    private fun invoiceView(v:Invoice)=ServiceInvoiceView(v.id,v.order,v.number,
        v.supplier,v.date,v.currency,v.amount.toPlainString(),v.tax.toPlainString(),
        v.total.toPlainString(),v.evidenceId)

    private fun lockInvoice(id:UUID) {
        if(jdbc.query("SELECT id FROM procurement_service_invoice WHERE id=? FOR UPDATE",
            {rs,_->rs.getObject("id",UUID::class.java)},id).isEmpty())hidden()
    }
    private fun requireDocument(actor:UUID,o:Order,slotId:UUID,kind:String) {
        val t=documentTarget.loadTarget(slotId)?:hidden()
        if(t.serviceOrderId!=o.id || t.organizationId!=o.org ||
           t.documentKind!=kind || !documentTarget.canAccess(actor,t))hidden()
    }
    private fun configured(kind:String) {
        val (flag,key)=when(kind) {
            "DELIVERY" -> acceptanceEnabled to reviewerKey.trim()
            "INVOICE" -> invoiceEnabled to financeKey.trim()
            else -> invalid()
        }
        if(!orderEnabled || !flag || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) hidden()
    }
    private fun requireAuthority(actor:UUID,org:UUID,kind:String) {
        val (flag,key)=when(kind) {
            "DELIVERY" -> acceptanceEnabled to reviewerKey.trim()
            "INVOICE" -> invoiceEnabled to financeKey.trim()
            else -> invalid()
        }
        if(!orderEnabled || !flag ||
           !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))hidden()
        val at=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,at))hidden()
        val binding=approvals.resolveCurrentAuthority(org,key,at)?:hidden()
        if(binding.organizationId!=org || binding.authorityKey!=key ||
           !approvals.actorMatchesAuthority(actor,binding,at))hidden()
    }
    private fun money(raw:String):BigDecimal {
        if(!Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$").matches(raw))invalid()
        return BigDecimal(raw).setScale(6)
    }
    private fun validated(raw:String,min:Int,max:Int):String =
        raw.trim().takeIf {it.length in min..max &&
            it.none {ch->ch.code<32 || ch.code==127}}?:invalid()
    private fun validSha(raw:String)=Regex("^[0-9a-f]{64}$").matches(raw)
    private fun key(kind:String,op:UUID)=UUID.nameUUIDFromBytes(
        ("hiltech:procurement:"+kind+":"+op).toByteArray(Charsets.UTF_8))
    private fun fingerprint(vararg values:Any?):String =
        IdempotencyKeyContract.fingerprint(values.joinToString("") {
            val s=it?.toString()?:"(null)";s.length.toString()+":"+s
        })
    private fun sha256(text:String)=MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") {"%02x".format(it)}
    private fun log(ctx:ProcurementDraftContext,action:String,id:UUID) {
        audit.append(AuditEventRecord(actorUserId=ctx.actor,action=action,
            targetType="PROCUREMENT_SERVICE",targetId=id,
            occurredAt=clock.instant(),correlationId=ctx.correlationId))
    }
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_PURCHASE_UNAVAILABLE","Current authorized service purchase unavailable.",
        HttpStatus.NOT_FOUND)
    private fun stale():Nothing=throw ProductApiException(
        "SERVICE_PURCHASE_SOURCE_CHANGED","Service acceptance or invoice evidence changed.",
        HttpStatus.CONFLICT)
    private fun invalid():Nothing=throw ProductApiException(
        "SERVICE_PURCHASE_INVALID","Invalid service review or invoice.",HttpStatus.BAD_REQUEST)
}
