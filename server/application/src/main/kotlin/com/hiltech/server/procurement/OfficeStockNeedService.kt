package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.ReservationPersistence
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.warehouse.StockItemView
import com.hiltech.server.warehouse.WarehouseAuthorizationPort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

data class CreateOfficeStockNeedRequest(
    val operationId: UUID, val teamVersion: Long,
    val deliveryLocationId: UUID, val deliveryLocationVersion: Long,
    val stockItemId: UUID, val stockItemVersion: Long,
    val quantity: String, val unitCode: String, val justification: String,
)
data class ReviseOfficeStockNeedRequest(
    val operationId: UUID, val expectedVersion: Long, val teamVersion: Long,
    val stockItemVersion: Long, val quantity: String,
    val unitCode: String, val justification: String,
)
data class OfficeStockItemOption(
    val id: UUID, val code: String, val name: String,
    val unitCode: String, val version: Long,
)
data class OfficeStockNeedView(
    val id: UUID, val organizationId: UUID, val teamId: UUID, val teamName: String,
    val teamVersion: Long, val locationId: UUID, val locationName: String,
    val stockItemId: UUID, val stockItemVersion: Long,
    val requestedQuantity: String, val unitCode: String, val justification: String,
    val state: String, val version: Long, val requestedBy: UUID,
    val assessmentCode: String, val sourceCurrent: Boolean,
    val canSubmit: Boolean, val canRevise: Boolean,
    val canWithdraw: Boolean, val canTechnicalReview: Boolean,
    val lastReviewReason: String?,
)
data class OfficeStockNeedReceipt(val need: OfficeStockNeedView, val replayed: Boolean)

/** Internal stocked office/department goods. Non-stock services are never
 * reclassified as a warehouse item merely to pass three-way matching. */
@Service
class OfficeStockNeedService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val drafts: PurchaseRequirementDraftPersistence,
    private val office: OfficeStockSource,
    private val verifier: ProcurementDemandSourceVerifier,
    private val stock: StockLedgerPersistence,
    private val warehouseAuth: WarehouseAuthorizationPort,
    private val reservations: ReservationPersistence,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.requirements.enabled:false}")
    private val requirementsEnabled: Boolean = false,
    @Value("\${hiltech.procurement.office-stock.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun teams(actor: UUID, organizationId: UUID): List<OfficeTeamOption> {
        active()
        return office.teams(actor, organizationId)
    }
    fun destinations(actor: UUID, teamId: UUID): List<OfficeDestinationOption> {
        active()
        return office.destinations(actor, teamId)
    }
    fun stockCatalog(actor: UUID, teamId: UUID, search: String): List<OfficeStockItemOption> {
        active()
        val team = office.teamsForKnownActor(actor, teamId) ?: hidden()
        return stock.items(team.organizationId, search.take(120), null)
            .filter { it.active }
            .take(80).map {
                OfficeStockItemOption(it.id, it.itemCode, it.name, it.baseUnitCode, it.version)
            }
    }
    fun create(ctx: ProcurementDraftContext, teamId: UUID,
               req: CreateOfficeStockNeedRequest): OfficeStockNeedReceipt {
        active()
        if (req.operationId != ctx.operationId) invalid()
        val source = office.load(teamId, req.deliveryLocationId) ?: hidden()
        if (!office.canRequest(ctx.actor, source)) hidden()
        val item = checkedItem(source, req.stockItemId)
        val qty = quantity(req.quantity, item)
        val reason = justification(req.justification)
        if (source.teamVersion != req.teamVersion ||
            source.delivery.version != req.deliveryLocationVersion ||
            item.version != req.stockItemVersion ||
            item.baseUnitCode != req.unitCode
        ) stale()
        val id = uuid("purchase-requirement", ctx.operationId)
        val fingerprint = hash(teamId, req.teamVersion, req.deliveryLocationId,
            req.deliveryLocationVersion, req.stockItemId, req.stockItemVersion,
            qty, req.unitCode, reason)
        val result = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_OFFICE_STOCK_NEED_CREATED",
            "PURCHASE_REQUIREMENT", id, fingerprint, ctx.correlationId,
        )) {
            // Serialize same-team concurrent requests before checking duplicates.
            val lock = jdbc.query(
                "SELECT id FROM team WHERE id=? FOR UPDATE",
                { rs, _ -> rs.getObject("id", UUID::class.java) }, teamId,
            ).singleOrNull() ?: hidden()
            val current = office.load(lock, req.deliveryLocationId) ?: hidden()
            if (!office.canRequest(ctx.actor, current)) hidden()
            if (current.teamVersion != req.teamVersion ||
                current.delivery.version != req.deliveryLocationVersion
            ) stale()
            val currentItem = checkedItem(current, item.id)
            if (currentItem.version != req.stockItemVersion ||
                currentItem.baseUnitCode != req.unitCode) stale()
            val duplicate = jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM procurement_purchase_requirement p
                  LEFT JOIN LATERAL (
                    SELECT t.to_state FROM procurement_requirement_transition t
                    WHERE t.requirement_id=p.id ORDER BY t.result_version DESC LIMIT 1
                  ) last ON true
                  WHERE p.source_type='OFFICE_TEAM_STOCK'
                    AND p.office_team_id=? AND p.organization_id=?
                    AND p.stock_item_id=?
                    AND COALESCE(last.to_state,'DRAFT') NOT IN ('WITHDRAWN','REJECTED')
                )
                """.trimIndent(), Boolean::class.java,
                current.teamId, current.organizationId, item.id,
            ) == true
            if (duplicate) throw ProductApiException(
                "OFFICE_STOCK_NEED_ALREADY_OPEN",
                "A live need already exists for the same team and stock item.",
                HttpStatus.CONFLICT,
            )
            val at = clock.instant()
            val assessment = stockAssessment(ctx.actor, current, currentItem, qty)
            drafts.insert(PurchaseRequirementDraftRecord(
                id = id, operationId = ctx.operationId,
                organizationId = current.organizationId,
                workOrderId = null, sourceWorkVersion = null,
                stockItemId = currentItem.id, stockItemVersion = currentItem.version,
                requestedQuantity = qty, unitCode = currentItem.baseUnitCode,
                justification = reason,
                assessmentCode = assessment.code, visibleFreeQuantity = assessment.free,
                assessedAt = at, requestedBy = ctx.actor, createdAt = at,
                correlationId = ctx.correlationId,
                sourceType = "OFFICE_TEAM_STOCK",
                officeTeamId = current.teamId, officeTeamVersion = current.teamVersion,
                officeDeliveryLocationId = current.delivery.storageLocationId,
                officeDeliveryLocationVersion = current.delivery.version,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_OFFICE_STOCK_NEED_CREATED",
                targetType = "PURCHASE_REQUIREMENT", targetId = id,
                occurredAt = at, correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("OFFICE_STOCK_NEED_CREATED", """{"id":"$id"}""")
        }
        return OfficeStockNeedReceipt(detail(ctx.actor, id), result.replayed)
    }

    fun list(actor: UUID, teamId: UUID): List<OfficeStockNeedView> {
        active()
        val team = office.teamsForKnownActor(actor, teamId) ?: hidden()
        return drafts.forOfficeTeam(team.organizationId, teamId)
            .map { detail(actor, it.id) }
    }
    fun detail(actor: UUID, id: UUID): OfficeStockNeedView {
        active()
        val root = root(id)
        val src = office.load(root.officeTeamId ?: hidden(),
            root.officeDeliveryLocationId ?: hidden()) ?: hidden()
        if (root.organizationId != src.organizationId ||
            (!office.canRequest(actor, src) && !office.canReview(actor, src))
        ) hidden()
        val last = drafts.latestTransition(id)
        val state = last?.toState ?: "DRAFT"
        val current = verifier.isCurrent(root, last)
        val requester = office.canRequest(actor, src) && root.requestedBy == actor
        val reviewer = office.canReview(actor, src) && root.requestedBy != actor
        return OfficeStockNeedView(
            id, root.organizationId, src.teamId, src.teamName,
            last?.revised?.workVersion ?: root.officeTeamVersion ?: 0,
            src.delivery.storageLocationId, src.delivery.name,
            root.stockItemId, last?.revised?.itemVersion ?: root.stockItemVersion,
            (last?.revised?.quantity ?: root.requestedQuantity).toPlainString(),
            last?.revised?.unitCode ?: root.unitCode,
            last?.revised?.justification ?: root.justification,
            state, last?.resultVersion ?: root.version, root.requestedBy,
            last?.revised?.assessmentCode ?: root.assessmentCode,
            current,
            requester && current && state=="DRAFT",
            requester && state in setOf("DRAFT","NEEDS_INFO"),
            requester && state in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING"),
            reviewer && state in setOf("SUBMITTED","READY_FOR_SOURCING"),
            if (state=="NEEDS_INFO") last?.reason else null,
        )
    }
    fun submit(ctx: ProcurementDraftContext, id: UUID, req: SubmitPurchaseRequirementRequest):
        OfficeStockNeedReceipt = transition(ctx, id, req.operationId, req.expectedVersion,
            "SUBMIT", "SUBMITTED", null, null)
    fun withdraw(ctx: ProcurementDraftContext, id: UUID, req: WithdrawPurchaseRequirementRequest):
        OfficeStockNeedReceipt = transition(ctx, id, req.operationId, req.expectedVersion,
            "WITHDRAW", "WITHDRAWN", req.reason, null)
    fun review(ctx: ProcurementDraftContext, id: UUID, req: ReviewPurchaseRequirementRequest):
        OfficeStockNeedReceipt {
        val action = when(req.decision) {
            "READY_FOR_SOURCING" -> "TECHNICAL_READY"
            "NEEDS_INFO" -> "RETURN_FOR_INFO"
            "REJECTED" -> "REJECT"
            else -> invalid()
        }
        return transition(ctx,id,req.operationId,req.expectedVersion,
            action,req.decision,req.reason,null)
    }
    fun revise(ctx: ProcurementDraftContext, id: UUID, req: ReviseOfficeStockNeedRequest):
        OfficeStockNeedReceipt {
        active()
        val root = root(id)
        val item = stock.item(root.stockItemId) ?: hidden()
        return transition(ctx,id,req.operationId,req.expectedVersion,
            "REVISE","DRAFT",null, Revision(
                req.teamVersion, req.stockItemVersion,
                quantity(req.quantity,item),req.unitCode,justification(req.justification),
            ))
    }
    private data class Revision(
        val teamVersion: Long,val stockItemVersion: Long,
        val qty: BigDecimal,val unit: String,val why: String,
    )
    private fun transition(
        ctx: ProcurementDraftContext,id: UUID,op: UUID,expected: Long,
        type:String,target:String,rawReason:String?,changed:Revision?,
    ): OfficeStockNeedReceipt {
        active()
        if (ctx.operationId!=op || expected<1) invalid()
        val original = root(id)
        val reviewer = type in setOf("TECHNICAL_READY","RETURN_FOR_INFO","REJECT")
        val source = office.load(original.officeTeamId ?: hidden(),
            original.officeDeliveryLocationId ?: hidden()) ?: hidden()
        val authorized = if(reviewer) office.canReview(ctx.actor,source) &&
            original.requestedBy!=ctx.actor
            else office.canRequest(ctx.actor,source) && original.requestedBy==ctx.actor
        if(!authorized || source.organizationId!=original.organizationId) hidden()
        val reason=rawReason?.trim()
        if(reason!=null && (reason.isBlank() || reason.length>500)) invalid()
        if(type in setOf("RETURN_FOR_INFO","REJECT") && reason==null) invalid()
        val fp=hash(id,expected,type,target,reason,
            changed?.teamVersion,changed?.stockItemVersion,changed?.qty,changed?.unit,changed?.why)
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_OFFICE_STOCK_NEED_$type",
            "PURCHASE_REQUIREMENT",id,fp,ctx.correlationId,
        )) {
            if(!drafts.lock(id)) hidden()
            val root=root(id)
            val current=office.load(root.officeTeamId?:hidden(),
                root.officeDeliveryLocationId?:hidden())?:hidden()
            if(root.organizationId!=current.organizationId ||
                (if(reviewer) !office.canReview(ctx.actor,current) ||
                    root.requestedBy==ctx.actor
                else !office.canRequest(ctx.actor,current) ||
                    root.requestedBy!=ctx.actor)
            ) hidden()
            val last=drafts.latestTransition(id)
            val version=last?.resultVersion?:root.version
            val state=last?.toState?:"DRAFT"
            if(version!=expected) stale()
            val allowed=when(type) {
                "SUBMIT"->state=="DRAFT"
                "REVISE"->state in setOf("DRAFT","NEEDS_INFO")
                "WITHDRAW"->state in setOf("DRAFT","SUBMITTED","NEEDS_INFO","READY_FOR_SOURCING")
                "TECHNICAL_READY"->state=="SUBMITTED"
                "RETURN_FOR_INFO","REJECT"->state in setOf("SUBMITTED","READY_FOR_SOURCING")
                else->false
            }
            if(!allowed) stale()
            val item=checkedItem(current,root.stockItemId)
            val at=clock.instant()
            val next=if(type=="REVISE") {
                val input=changed?:invalid()
                if(current.teamVersion!=input.teamVersion ||
                    current.delivery.version!=root.officeDeliveryLocationVersion ||
                    item.version!=input.stockItemVersion ||
                    item.baseUnitCode!=input.unit) stale()
                val balance=stockAssessment(ctx.actor,current,item,input.qty)
                PurchaseRequirementRevision(
                    input.qty,input.why,current.teamVersion,item.version,
                    item.baseUnitCode,balance.code,balance.free,at,
                )
            } else last?.revised
            if(type in setOf("SUBMIT","TECHNICAL_READY") &&
                !verifier.isCurrent(root,last)) stale()
            drafts.appendTransition(PurchaseRequirementTransitionRecord(
                id=uuid("requirement-transition",ctx.operationId),
                operationId=ctx.operationId,requirementId=id,
                organizationId=root.organizationId,
                sourceVersion=version,resultVersion=version+1,
                type=type,fromState=state,toState=target,reason=reason,
                actor=ctx.actor,at=at,correlationId=ctx.correlationId,revised=next,
            ))
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,action="PROCUREMENT_OFFICE_STOCK_NEED_$type",
                targetType="PURCHASE_REQUIREMENT",targetId=id,
                occurredAt=at,correlationId=ctx.correlationId,
            ))
            IdempotentCommandOutcome("OFFICE_STOCK_NEED_$type","""{"id":"$id"}""")
        }
        return OfficeStockNeedReceipt(detail(ctx.actor,id),result.replayed)
    }
    private fun stockAssessment(actor:UUID,src:OfficeStockSourceSnapshot,
                                item:StockItemView,qty:BigDecimal):StockPreflight {
        val at=clock.instant()
        if(!warehouseAuth.canViewStorage(actor,src.delivery))
            return StockPreflight("WAREHOUSE_REVIEW_REQUIRED",null,at)
        val balance=reservations.balances(item.id,at)
            .singleOrNull { it.location==src.delivery.storageLocationId }
            ?:return StockPreflight("WAREHOUSE_REVIEW_REQUIRED",null,at)
        val free=balance.available.max(BigDecimal.ZERO)
        return StockPreflight(
            if(free>=qty) "VISIBLE_STOCK_AVAILABLE" else "VISIBLE_STOCK_SHORTAGE",
            free,at,
        )
    }
    private fun checkedItem(src:OfficeStockSourceSnapshot,id:UUID):StockItemView {
        val item=stock.item(id)?.takeIf { it.active && it.organizationId==src.organizationId }
            ?:hidden()
        if(!reservations.stockUnitActive(src.organizationId,item.baseUnitCode)) hidden()
        return item
    }
    private fun quantity(raw:String,item:StockItemView):BigDecimal {
        if(!Regex("[0-9]{1,12}(\\.[0-9]{1,6})?").matches(raw))invalid()
        val n=BigDecimal(raw).stripTrailingZeros()
        if(n.signum()<=0 || (item.serialized && n.scale()>0))invalid()
        return n
    }
    private fun justification(raw:String):String=raw.trim().takeIf {
        it.length in 12..500 && it.none { c->c.code<32 }
    }?:invalid()
    private fun root(id:UUID)=drafts.byId(id)?.takeIf {
        it.sourceType=="OFFICE_TEAM_STOCK" && it.officeTeamId!=null &&
            it.officeDeliveryLocationId!=null
    }?:hidden()
    private fun hash(vararg xs:Any?)=IdempotencyKeyContract.fingerprint(
        xs.joinToString("") { x-> val v=x?.toString()?:"(null)"; "${v.length}:$v" }
    )
    private fun uuid(kind:String,op:UUID)=UUID.nameUUIDFromBytes(
        "hiltech:procurement:$kind:$op".toByteArray(Charsets.UTF_8),
    )
    private fun active(){if(!requirementsEnabled||!enabled)hidden()}
    private fun hidden():Nothing=throw ProductApiException(
        "OFFICE_STOCK_NEED_UNAVAILABLE","No current authorized HILTECH office team purchase source.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing=throw ProductApiException(
        "OFFICE_STOCK_NEED_SOURCE_CHANGED","Office team, item or delivery location changed.",
        HttpStatus.CONFLICT,
    )
    private fun invalid():Nothing=throw ProductApiException(
        "OFFICE_STOCK_NEED_INVALID","Invalid office stock need, requested unit or review.",
        HttpStatus.BAD_REQUEST,
    )
}
