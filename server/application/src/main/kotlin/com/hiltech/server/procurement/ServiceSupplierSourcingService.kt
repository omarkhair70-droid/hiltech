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
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class OpenServiceRfqRequest(val operationId: UUID, val expectedNeedVersion: Long)
data class ServiceRfqView(
    val id: UUID, val needId: UUID, val needVersion: Long,
    val organizationId: UUID, val specification: String, val acceptanceCriteria: String,
    val sourceCurrent: Boolean, val supplierCount: Int,
    val purchaseAuthorized: Boolean = false,
)
data class ServiceRfqReceipt(val rfq: ServiceRfqView, val replayed: Boolean)
data class CreateServiceQuoteSlotRequest(
    val operationId: UUID, val supplierOrganizationId: UUID,
)
data class ServiceQuoteSlotReceipt(
    val slotId: UUID, val rfqId: UUID, val supplierOrganizationId: UUID, val replayed: Boolean,
)
data class RecordServiceQuoteRequest(
    val operationId: UUID, val slotId: UUID, val expectedRevisionNumber: Int,
    val supplierQuoteReference: String, val evidenceId: UUID, val expectedSha256: String,
    val currency: String, val serviceAmount: String, val taxAmount: String,
    val serviceTerms: String, val validUntil: LocalDate? = null,
)
data class ServiceQuoteView(
    val id: UUID, val rfqId: UUID, val supplierOrganizationId: UUID,
    val quoteReference: String, val revision: Int, val currency: String,
    val serviceAmount: String, val taxAmount: String, val totalAmount: String,
    val serviceTerms: String, val validUntil: LocalDate?, val evidenceId: UUID,
    val sourceCurrent: Boolean,
    val supplierAwarded: Boolean = false, val paymentAuthorized: Boolean = false,
)
data class ServiceQuoteReceipt(val quote: ServiceQuoteView, val replayed: Boolean)

/**
 * Separate service sourcing; existing material purchasing schemas require StockItem,
 * Warehouse receiving and a stock three-way match, so must never be reused here.
 * Buyer authorization is an independent active ApprovalEngine binding.
 * Binary quotes require the canonical READY private Evidence target.
 */
@Service
class ServiceSupplierSourcingService(
    private val jdbc: JdbcTemplate,
    private val approvals: ApprovalPersistencePort,
    private val suppliers: SupplierSourcingPersistence,
    private val documentTarget: ServiceQuoteDocumentTargetAdapter,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.service-needs.enabled:false}")
    private val needsEnabled: Boolean = false,
    @Value("\${hiltech.procurement.service-sourcing.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.service-sourcing.buyer-authority-key:}")
    private val buyerKey: String = "",
) {
    private data class Source(
        val id: UUID, val org: UUID, val team: UUID, val frozenTeamVersion: Long,
        val currentTeamVersion: Long, val activeTeam: Boolean,
        val organizationActive: Boolean, val version: Long, val state: String,
        val specification: String, val acceptance: String,
    )
    private data class Rfq(
        val id: UUID, val org: UUID, val need: UUID, val needVersion: Long,
        val specification: String, val acceptance: String,
    )
    private data class Slot(val id: UUID, val org: UUID, val rfq: UUID, val supplier: UUID)
    private data class Quote(
        val id: UUID, val rfq: UUID, val supplier: UUID,
        val reference: String, val revision: Int, val currency: String,
        val serviceAmount: BigDecimal, val tax: BigDecimal, val total: BigDecimal,
        val terms: String, val validUntil: LocalDate?, val evidenceId: UUID,
    )

    fun eligibleNeeds(actor: UUID, org: UUID): List<ServiceRfqView> {
        requireBuyer(actor,org)
        val ids=jdbc.query(
            """
            SELECT root.id FROM procurement_service_need root
            JOIN LATERAL (SELECT result_version,to_state
              FROM procurement_service_need_event e WHERE e.need_id=root.id
              ORDER BY result_version DESC LIMIT 1) last ON true
            WHERE root.organization_id=? AND last.to_state='READY_FOR_SOURCING'
            ORDER BY root.created_at DESC,root.id DESC LIMIT 100
            """.trimIndent(),
            {rs,_->rs.getObject("id",UUID::class.java)},org,
        )
        return ids.mapNotNull { id ->
            val src=source(id)
            if(src==null || !current(src)) null else {
                val rid=jdbc.query(
                    "SELECT id FROM procurement_service_rfq WHERE service_need_id=? AND service_need_version=?",
                    {rs,_->rs.getObject("id",UUID::class.java)},id,src.version,
                ).singleOrNull()
                if(rid==null) ServiceRfqView(
                    UUID(0,0),src.id,src.version,src.org,src.specification,src.acceptance,
                    true,0,
                ) else view(actor,rfq(rid)?:return@mapNotNull null)
            }
        }
    }

    fun open(ctx: ProcurementDraftContext, needId: UUID,
             input: OpenServiceRfqRequest): ServiceRfqReceipt {
        active()
        if(ctx.operationId!=input.operationId || input.expectedNeedVersion<2) invalid()
        val root=source(needId)?:hidden()
        requireBuyer(ctx.actor,root.org)
        val id=key("service-rfq",ctx.operationId)
        val fp=fingerprint(needId,input.expectedNeedVersion)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_RFQ_CREATED",
            "SERVICE_RFQ",id,fp,ctx.correlationId,
        )) {
            lockNeed(needId)
            val actual=source(needId)?:hidden()
            requireBuyer(ctx.actor,actual.org)
            if(!current(actual) || actual.version!=input.expectedNeedVersion) stale()
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_rfq (
                  id,operation_id,organization_id,service_need_id,service_need_version,
                  specification,acceptance_criteria,created_by_user_id,created_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,actual.org,actual.id,actual.version,
                actual.specification,actual.acceptance,ctx.actor,
                at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,action="PROCUREMENT_SERVICE_RFQ_CREATED",
                targetType="SERVICE_RFQ",targetId=id,occurredAt=at,
                correlationId=ctx.correlationId,
            ))
            IdempotentCommandOutcome("SERVICE_RFQ_CREATED","{\"id\":\"$id\"}")
        }
        return ServiceRfqReceipt(view(ctx.actor,rfq(id)?:hidden()),result.replayed)
    }

    fun rfqs(actor: UUID, needId: UUID): List<ServiceRfqView> {
        val src=source(needId)?:hidden()
        requireBuyer(actor,src.org)
        val ids=jdbc.query(
            """
            SELECT id FROM procurement_service_rfq
            WHERE service_need_id=? AND organization_id=?
            ORDER BY created_at DESC,id DESC LIMIT 100
            """.trimIndent(),
            {rs,_->rs.getObject("id",UUID::class.java)},needId,src.org,
        )
        return ids.map { view(actor,rfq(it)?:hidden()) }
    }

    fun createSlot(ctx: ProcurementDraftContext, rfqId: UUID,
                   input: CreateServiceQuoteSlotRequest): ServiceQuoteSlotReceipt {
        active()
        if(ctx.operationId!=input.operationId) invalid()
        val candidate=rfq(rfqId)?:hidden()
        requireBuyer(ctx.actor,candidate.org)
        val slotId=key("service-quote-slot",ctx.operationId)
        val fp=fingerprint(rfqId,input.supplierOrganizationId)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_QUOTE_SLOT_CREATED",
            "SERVICE_QUOTE_DOCUMENT_SLOT",slotId,fp,ctx.correlationId,
        )) {
            lockRfq(rfqId)
            val current=rfq(rfqId)?:hidden()
            requireBuyer(ctx.actor,current.org)
            if(!current(current)) stale()
            if(!suppliers.isEligibleSupplier(current.org,input.supplierOrganizationId)) hidden()
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_quote_document_slot (
                  id,operation_id,organization_id,rfq_id,supplier_organization_id,
                  created_by_user_id,created_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?)
                """.trimIndent(),
                slotId,ctx.operationId,current.org,rfqId,input.supplierOrganizationId,
                ctx.actor,at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,action="PROCUREMENT_SERVICE_QUOTE_SLOT_CREATED",
                targetType="SERVICE_QUOTE_DOCUMENT_SLOT",targetId=slotId,
                occurredAt=at,correlationId=ctx.correlationId,
            ))
            IdempotentCommandOutcome("SERVICE_QUOTE_SLOT_CREATED",
                "{\"slotId\":\"$slotId\"}")
        }
        return ServiceQuoteSlotReceipt(slotId,rfqId,input.supplierOrganizationId,result.replayed)
    }

    fun recordQuote(ctx: ProcurementDraftContext, rfqId: UUID,
                    input: RecordServiceQuoteRequest): ServiceQuoteReceipt {
        active()
        if(ctx.operationId!=input.operationId || input.expectedRevisionNumber<1 ||
            !Regex("[A-Z]{3}").matches(input.currency) ||
            !Regex("[0-9a-f]{64}").matches(input.expectedSha256)
        ) invalid()
        val reference=validated(input.supplierQuoteReference,2,120)
        val terms=validated(input.serviceTerms,12,1000)
        val amount=money(input.serviceAmount)
        val tax=money(input.taxAmount)
        val total=amount.add(tax).setScale(6)
        val target=rfq(rfqId)?:hidden()
        requireBuyer(ctx.actor,target.org)
        val slot=slot(input.slotId)?:hidden()
        if(slot.org!=target.org || slot.rfq!=target.id) hidden()
        val id=key("service-supplier-quote",ctx.operationId)
        val fp=fingerprint(rfqId,slot.id,input.expectedRevisionNumber,
            reference,input.evidenceId,input.expectedSha256,input.currency,
            amount,tax,terms,input.validUntil)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_QUOTE_RECORDED",
            "SERVICE_SUPPLIER_QUOTE",id,fp,ctx.correlationId,
        )) {
            lockRfq(rfqId)
            val live=rfq(rfqId)?:hidden()
            requireBuyer(ctx.actor,live.org)
            if(!current(live)) stale()
            val stillSlot=slot(input.slotId)?:hidden()
            if(stillSlot.org!=live.org || stillSlot.rfq!=live.id ||
                !suppliers.isEligibleSupplier(live.org,stillSlot.supplier) ||
                documentTarget.loadTarget(stillSlot.id)?.supplierOrganizationId!=stillSlot.supplier ||
                !verified(live,stillSlot,input.evidenceId,input.expectedSha256)
            ) hidden()
            val next=jdbc.queryForObject(
                """
                SELECT COALESCE(MAX(revision_number),0)+1
                FROM procurement_service_supplier_quote
                WHERE rfq_id=? AND supplier_organization_id=?
                """.trimIndent(),Int::class.java,rfqId,stillSlot.supplier,
            )?:1
            if(next!=input.expectedRevisionNumber) stale()
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_supplier_quote (
                  id,operation_id,organization_id,rfq_id,slot_id,
                  supplier_organization_id,evidence_id,sha256,
                  supplier_quote_reference,revision_number,currency,service_amount,
                  tax_amount,total_amount,valid_until,service_terms,
                  recorded_by_user_id,recorded_at,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,live.org,live.id,stillSlot.id,stillSlot.supplier,
                input.evidenceId,input.expectedSha256,reference,next,input.currency,
                amount,tax,total,input.validUntil,terms,ctx.actor,
                at.atOffset(ZoneOffset.UTC),ctx.correlationId,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,action="PROCUREMENT_SERVICE_QUOTE_RECORDED",
                targetType="SERVICE_SUPPLIER_QUOTE",targetId=id,
                occurredAt=at,correlationId=ctx.correlationId,
            ))
            IdempotentCommandOutcome("SERVICE_QUOTE_RECORDED","{\"id\":\"$id\"}")
        }
        return ServiceQuoteReceipt(quoteView(ctx.actor,quote(id)?:hidden()),result.replayed)
    }

    fun comparison(actor: UUID, rfqId: UUID): List<ServiceQuoteView> {
        val root=rfq(rfqId)?:hidden()
        requireBuyer(actor,root.org)
        val ids=jdbc.query(
            """
            SELECT DISTINCT ON (supplier_organization_id) id
            FROM procurement_service_supplier_quote
            WHERE rfq_id=?
            ORDER BY supplier_organization_id,revision_number DESC
            LIMIT 100
            """.trimIndent(),
            {rs,_->rs.getObject("id",UUID::class.java)},rfqId,
        )
        return ids.map { quoteView(actor,quote(it)?:hidden()) }
    }

    private fun verified(root:Rfq,slot:Slot,evidence:UUID,sha:String):Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM evidence e
              JOIN evidence_upload_session us ON us.evidence_id=e.id
              WHERE e.id=? AND e.target_type='SERVICE_QUOTE_DOCUMENT'
                AND e.target_id=? AND e.organization_id=? AND e.sha256=?
                AND us.expected_sha256=? AND e.storage_state='READY'
                AND us.state='READY' AND e.finalized_at IS NOT NULL
                AND us.finalized_at IS NOT NULL AND e.object_key_ref IS NOT NULL
                AND e.classification_code='HIGHLY_RESTRICTED'
                AND e.client_visibility_mode='INTERNAL_ONLY'
                AND e.evidence_requirement_key='SERVICE_QUOTE_BINARY'
                AND e.evidence_type_code='SERVICE_QUOTE'
                AND ((e.content_type='application/pdf'
                       AND e.security_scan_class='SIGNED_DOCUMENT')
                  OR (e.content_type IN ('image/jpeg','image/png','image/webp')
                       AND e.security_scan_class='NATIVE_MEDIA'))
            )
            """.trimIndent(),Boolean::class.java,evidence,slot.id,root.org,sha,sha,
        )==true

    private fun view(actor:UUID,rfq:Rfq):ServiceRfqView {
        requireBuyer(actor,rfq.org)
        val count=jdbc.queryForObject(
            "SELECT COUNT(DISTINCT supplier_organization_id) FROM procurement_service_supplier_quote WHERE rfq_id=?",
            Int::class.java,rfq.id,
        )?:0
        return ServiceRfqView(rfq.id,rfq.need,rfq.needVersion,rfq.org,
            rfq.specification,rfq.acceptance,current(rfq),count)
    }

    private fun quoteView(actor:UUID,q:Quote):ServiceQuoteView {
        val source=rfq(q.rfq)?:hidden()
        requireBuyer(actor,source.org)
        return ServiceQuoteView(q.id,q.rfq,q.supplier,q.reference,q.revision,q.currency,
            q.serviceAmount.toPlainString(),q.tax.toPlainString(),q.total.toPlainString(),
            q.terms,q.validUntil,q.evidenceId,current(source))
    }

    private fun quote(id:UUID):Quote?=jdbc.query(
        """
        SELECT id,rfq_id,supplier_organization_id,supplier_quote_reference,
               revision_number,currency,service_amount,tax_amount,total_amount,
               service_terms,valid_until,evidence_id
        FROM procurement_service_supplier_quote WHERE id=?
        """.trimIndent(),
        {rs,_->Quote(rs.getObject("id",UUID::class.java),
            rs.getObject("rfq_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java),
            rs.getString("supplier_quote_reference"),rs.getInt("revision_number"),
            rs.getString("currency"),rs.getBigDecimal("service_amount"),
            rs.getBigDecimal("tax_amount"),rs.getBigDecimal("total_amount"),
            rs.getString("service_terms"),rs.getObject("valid_until",LocalDate::class.java),
            rs.getObject("evidence_id",UUID::class.java))},id,
    ).singleOrNull()

    private fun source(id:UUID):Source?=jdbc.query(
        """
        SELECT root.id,root.organization_id,root.team_id,root.team_version,
               t.version AS current_team_version,t.active AS active_team,
               (o.status='ACTIVE' AND o.organization_type='HILTECH') AS active_org,
               last.result_version, last.to_state,
               COALESCE(rev.revised_specification,root.specification) AS current_spec,
               COALESCE(rev.revised_acceptance_criteria,root.acceptance_criteria) AS current_acceptance
        FROM procurement_service_need root
        JOIN team t ON t.id=root.team_id AND t.organization_id=root.organization_id
        JOIN organization o ON o.id=root.organization_id
        LEFT JOIN LATERAL (
          SELECT result_version,to_state FROM procurement_service_need_event e
          WHERE e.need_id=root.id ORDER BY result_version DESC LIMIT 1
        ) last ON true
        LEFT JOIN LATERAL (
          SELECT revised_specification,revised_acceptance_criteria
          FROM procurement_service_need_event e
          WHERE e.need_id=root.id AND transition_type='REVISE'
          ORDER BY result_version DESC LIMIT 1
        ) rev ON true
        WHERE root.id=?
        """.trimIndent(),
        {rs,_->Source(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("team_id",UUID::class.java),
            rs.getLong("team_version"),rs.getLong("current_team_version"),
            rs.getBoolean("active_team"),rs.getBoolean("active_org"),
            rs.getLong("result_version"),rs.getString("to_state")?:"DRAFT",
            rs.getString("current_spec"),rs.getString("current_acceptance"))},id,
    ).singleOrNull()

    private fun rfq(id:UUID):Rfq?=jdbc.query(
        """
        SELECT id,organization_id,service_need_id,service_need_version,
               specification,acceptance_criteria FROM procurement_service_rfq WHERE id=?
        """.trimIndent(),
        {rs,_->Rfq(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("service_need_id",UUID::class.java),
            rs.getLong("service_need_version"),rs.getString("specification"),
            rs.getString("acceptance_criteria"))},id,
    ).singleOrNull()

    private fun slot(id:UUID):Slot?=jdbc.query(
        """
        SELECT id,organization_id,rfq_id,supplier_organization_id
        FROM procurement_service_quote_document_slot WHERE id=?
        """.trimIndent(),
        {rs,_->Slot(rs.getObject("id",UUID::class.java),
            rs.getObject("organization_id",UUID::class.java),
            rs.getObject("rfq_id",UUID::class.java),
            rs.getObject("supplier_organization_id",UUID::class.java))},id,
    ).singleOrNull()

    private fun current(rfq:Rfq):Boolean {
        val src=source(rfq.need)?:return false
        return src.org==rfq.org && src.version==rfq.needVersion && current(src)
    }
    private fun current(src:Source):Boolean =
        src.state=="READY_FOR_SOURCING" && src.activeTeam &&
        src.organizationActive && src.frozenTeamVersion==src.currentTeamVersion
    private fun lockRfq(id:UUID) {
        if(jdbc.query("SELECT id FROM procurement_service_rfq WHERE id=? FOR UPDATE",
                {rs,_->rs.getObject("id",UUID::class.java)},id).isEmpty())hidden()
    }
    private fun lockNeed(id:UUID) {
        if(jdbc.query("SELECT id FROM procurement_service_need WHERE id=? FOR UPDATE",
                {rs,_->rs.getObject("id",UUID::class.java)},id).isEmpty())hidden()
    }
    private fun requireBuyer(actor:UUID,org:UUID) {
        active()
        val key=buyerKey.trim()
        if(!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key))hidden()
        val at=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,at))hidden()
        val binding=approvals.resolveCurrentAuthority(org,key,at)?:hidden()
        if(binding.organizationId!=org || binding.authorityKey!=key ||
            !approvals.actorMatchesAuthority(actor,binding,at))hidden()
    }
    private fun money(raw:String):BigDecimal {
        if(!Regex("^(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?$").matches(raw)) invalid()
        return BigDecimal(raw).setScale(6)
    }
    private fun validated(raw:String,min:Int,max:Int):String =
        raw.trim().takeIf { it.length in min..max &&
            it.none {ch->ch.code<32 || ch.code==127} }?:invalid()
    private fun fingerprint(vararg xs:Any?):String =
        IdempotencyKeyContract.fingerprint(xs.joinToString("") {
            val s=it?.toString()?:"(null)";s.length.toString()+":"+s
        })
    private fun key(type:String,operation:UUID):UUID =
        UUID.nameUUIDFromBytes(
            ("hiltech:procurement:"+type+":"+operation).toByteArray(Charsets.UTF_8))
    private fun active() {if(!enabled || !needsEnabled)hidden()}
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_SOURCING_UNAVAILABLE","Service supplier sourcing not available.",
        HttpStatus.NOT_FOUND)
    private fun stale():Nothing=throw ProductApiException(
        "SERVICE_SOURCING_SOURCE_CHANGED","The service, quote or supplier changed.",
        HttpStatus.CONFLICT)
    private fun invalid():Nothing=throw ProductApiException(
        "SERVICE_SOURCING_INVALID","Invalid service source or quotation.",
        HttpStatus.BAD_REQUEST)
}
