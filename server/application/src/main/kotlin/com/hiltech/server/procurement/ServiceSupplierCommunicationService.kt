package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class AttestServiceSupplierCommunicationRequest(
    val operationId:UUID,val eventType:String,val channel:String,
    val externalReference:String,val occurredAt:Instant,
)
data class ServiceSupplierCommunicationView(
    val id:UUID,val serviceOrderId:UUID,val eventVersion:Long,
    val eventType:String,val channel:String,val externalReference:String,
    val occurredAt:Instant,val recordedBy:UUID,
    val replayed:Boolean=false,val sentByThisApi:Boolean=false,
)

/**
 * One human-friendly externally verified reference: buyer/issuer records how
 * they contacted the supplier and (optionally) its acknowledgement.
 * No message is sent by this API, so UI never claims the vendor was contacted
 * automatically. The SAME current service issuer binding is revalidated.
 */
@Service
class ServiceSupplierCommunicationService @Autowired constructor(
    private val jdbc:JdbcTemplate,
    private val orders:ServiceOrderService,
    private val commands:IdempotentCommandExecutor,
    private val audit:AuditEventWriter,
    private val clock:Clock,
    @Value("\${hiltech.procurement.service-supplier-communications.enabled:false}")
    private val enabled:Boolean=false,
) {
    fun history(actor:UUID,orderId:UUID):List<ServiceSupplierCommunicationView> {
        available()
        val order=orders.issued(actor,orderId)
        orders.requireIssuerForSupplierCommunication(actor,order.organizationId)
        return query(orderId)
    }

    fun attest(ctx:ProcurementDraftContext,orderId:UUID,
               input:AttestServiceSupplierCommunicationRequest):
        ServiceSupplierCommunicationView {
        available()
        if(ctx.operationId!=input.operationId ||
            input.eventType !in setOf(
                "EXTERNAL_DISPATCH_ATTESTED","SUPPLIER_ACK_ATTESTED") ||
            input.channel !in setOf(
                "EMAIL","SUPPLIER_PORTAL","HAND_DELIVERY","OTHER_VERIFIED_CHANNEL") ||
            input.externalReference.trim().length !in 8..255 ||
            input.externalReference.any {it.code<32 || it.code==127} ||
            input.occurredAt.isAfter(clock.instant()) ||
            input.occurredAt.isBefore(clock.instant().minusSeconds(366L*86400L))
        ) invalid()
        val order=orders.issued(ctx.actor,orderId)
        orders.requireIssuerForSupplierCommunication(ctx.actor,order.organizationId)
        val id=UUID.nameUUIDFromBytes(
            ("hiltech:procurement:service-supplier-communication:"+
                ctx.operationId).toByteArray(Charsets.UTF_8))
        val fp=IdempotencyKeyContract.fingerprint(
            listOf(orderId,input.eventType,input.channel,
                input.externalReference.trim(),input.occurredAt)
                .joinToString("\u001f"),
        )
        val result=commands.execute(IdempotentCommandSpec(
            ctx.operationId,ctx.actor,"PROCUREMENT_SERVICE_SUPPLIER_COMMUNICATION_ATTESTED",
            "PROCUREMENT_SERVICE_ORDER_SUPPLIER_COMMUNICATION",
            id,fp,ctx.correlationId,
        )) {
            val current=orders.issued(ctx.actor,orderId)
            if(current.organizationId!=order.organizationId)hidden()
            orders.requireIssuerForSupplierCommunication(ctx.actor,current.organizationId)
            // The order-level row lock serializes two actors attesting in parallel.
            if(jdbc.query(
                "SELECT id FROM procurement_service_order WHERE id=? AND organization_id=? FOR UPDATE",
                {rs,_->rs.getObject("id",UUID::class.java)},
                orderId,current.organizationId,
            ).isEmpty())hidden()
            val history=query(orderId)
            val expectedVersion=when(input.eventType) {
                "EXTERNAL_DISPATCH_ATTESTED" -> {
                    if(history.isNotEmpty())stale()
                    1L
                }
                "SUPPLIER_ACK_ATTESTED" -> {
                    if(history.size!=1 ||
                       history.single().eventType!="EXTERNAL_DISPATCH_ATTESTED")stale()
                    2L
                }
                else->invalid()
            }
            val at=clock.instant()
            check(jdbc.update(
                """
                INSERT INTO procurement_service_order_supplier_communication (
                  id,operation_id,organization_id,service_order_id,event_version,
                  event_type,channel,external_reference,occurred_at,recorded_at,
                  recorded_by_user_id,correlation_id
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                id,ctx.operationId,current.organizationId,orderId,expectedVersion,
                input.eventType,input.channel,input.externalReference.trim(),
                input.occurredAt.atOffset(ZoneOffset.UTC),at.atOffset(ZoneOffset.UTC),
                ctx.actor,ctx.correlationId,
            )==1)
            audit.append(AuditEventRecord(
                actorUserId=ctx.actor,
                action="PROCUREMENT_SERVICE_SUPPLIER_COMMUNICATION_ATTESTED",
                targetType="PROCUREMENT_SERVICE_ORDER",targetId=orderId,
                occurredAt=at,correlationId=ctx.correlationId,
                reason=input.eventType,
            ))
            IdempotentCommandOutcome("SERVICE_SUPPLIER_COMMUNICATION_ATTESTED",
                "{\"id\":\""+id+"\"}")
        }
        orders.requireIssuerForSupplierCommunication(ctx.actor,order.organizationId)
        val event=query(orderId).singleOrNull{it.id==id}?:hidden()
        return event.copy(replayed=result.replayed)
    }

    private fun query(orderId:UUID):List<ServiceSupplierCommunicationView> =
        jdbc.query(
            """
            SELECT id,service_order_id,event_version,event_type,channel,
                   external_reference,occurred_at,recorded_by_user_id
            FROM procurement_service_order_supplier_communication
            WHERE service_order_id=? ORDER BY event_version
            """.trimIndent(),{rs,_->ServiceSupplierCommunicationView(
                rs.getObject("id",UUID::class.java),
                rs.getObject("service_order_id",UUID::class.java),
                rs.getLong("event_version"),rs.getString("event_type"),
                rs.getString("channel"),rs.getString("external_reference"),
                rs.getObject("occurred_at",OffsetDateTime::class.java).toInstant(),
                rs.getObject("recorded_by_user_id",UUID::class.java),
            )},orderId,
        )

    private fun available() {if(!enabled)hidden()}
    private fun hidden():Nothing=throw ProductApiException(
        "SERVICE_SUPPLIER_COMMUNICATION_UNAVAILABLE",
        "Current supplier communication authority unavailable.",HttpStatus.NOT_FOUND)
    private fun stale():Nothing=throw ProductApiException(
        "SERVICE_SUPPLIER_COMMUNICATION_STALE",
        "Supplier communication history has changed.",HttpStatus.CONFLICT)
    private fun invalid():Nothing=throw ProductApiException(
        "SERVICE_SUPPLIER_COMMUNICATION_INVALID",
        "A real supplier communication reference and correct date are required.",
        HttpStatus.BAD_REQUEST)
}

@RestController
class ServiceSupplierCommunicationController(
    private val service:ServiceSupplierCommunicationService,
) {
    @GetMapping("/v1/service-procurement/orders/{id}/supplier-communications")
    fun history(req:HttpServletRequest,@PathVariable id:UUID):
        List<ServiceSupplierCommunicationView> = service.history(req.actor(),id)

    @PostMapping("/v1/service-procurement/orders/{id}/supplier-communications")
    fun attest(req:HttpServletRequest,@PathVariable id:UUID,
               @RequestBody body:AttestServiceSupplierCommunicationRequest):
        ServiceSupplierCommunicationView =
        service.attest(req.ctx(body.operationId),id,body)

    private fun HttpServletRequest.actor():UUID =
        HiltechRequestContext.current(this).identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException("UNAUTHENTICATED",
                "Authentication required.",HttpStatus.UNAUTHORIZED)
    private fun HttpServletRequest.ctx(id:UUID):ProcurementDraftContext {
        val c=HiltechRequestContext.current(this)
        IdempotencyKeyContract.requireMatches(c.idempotencyKey,id)
        return ProcurementDraftContext(id,actor(),c.correlationId)
    }
}
