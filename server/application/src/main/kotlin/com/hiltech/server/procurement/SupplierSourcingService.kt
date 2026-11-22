package com.hiltech.server.procurement

import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.warehouse.StockLedgerPersistence
import com.hiltech.server.work.WorkPersistencePort
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** Explicit sourcing authority cannot be inferred from project technical approval. */
interface SupplierSourcingAuthorizationPort {
    fun canManageSourcing(actor: UUID, organizationId: UUID): Boolean
}

/** Fail-closed until HILTECH binds a real procurement role/policy source. */
@Component
class DenyAllSupplierSourcingAuthorization : SupplierSourcingAuthorizationPort {
    override fun canManageSourcing(actor: UUID, organizationId: UUID): Boolean = false
}

data class OpenSupplierRfqRequest(val operationId: UUID, val expectedRequirementVersion: Long)

data class RecordSupplierQuoteRequest(
    val operationId: UUID,
    val supplierOrganizationId: UUID,
    val expectedRevisionNumber: Int,
    val supplierQuoteReference: String,
    val currency: String,
    val quotedQuantity: String,
    val quotedUnitCode: String,
    val unitPrice: String,
    val taxAmount: String,
    val deliveryDays: Int? = null,
    val validUntil: LocalDate? = null,
    val deliveryTerms: String? = null,
    val paymentTerms: String? = null,
    val warrantyTerms: String? = null,
    val sourceEvidenceRef: String,
    val sourceSha256: String,
)

data class SupplierRfqView(
    val id: UUID,
    val requirementId: UUID,
    val capturedRequirementVersion: Long,
    val capturedQuantity: String,
    val unitCode: String,
    val sourceCurrent: Boolean,
    val quoteCount: Int,
    val purchaseAuthorized: Boolean = false,
)

data class SupplierRfqReceipt(val rfq: SupplierRfqView, val replayed: Boolean)
data class SupplierQuoteReceipt(val quoteId: UUID, val rfqId: UUID, val revisionNumber: Int, val replayed: Boolean)

data class SourcingEligibleNeed(
    val requirementId: UUID,
    val technicalVersion: Long,
    val stockItemId: UUID,
    val requestedQuantity: String,
    val unitCode: String,
    val technicalState: String,
    val sourceCurrent: Boolean,
    val sourcingRoundExists: Boolean,
)

data class SupplierRfqComparisonView(
    val rfq: SupplierRfqView,
    val comparison: SupplierQuoteComparison,
    val allRevisionCount: Int,
    val recordedSupplierCount: Int,
)

@Service
class SupplierSourcingService @Autowired constructor(
    private val sourcing: SupplierSourcingPersistence,
    private val requirements: PurchaseRequirementDraftPersistence,
    private val work: WorkPersistencePort,
    private val stock: StockLedgerPersistence,
    private val authority: SupplierSourcingAuthorizationPort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.sourcing.enabled:false}")
    private val enabled: Boolean = false,
) {
    fun openRfq(
        ctx: ProcurementDraftContext,
        requirementId: UUID,
        request: OpenSupplierRfqRequest,
    ): SupplierRfqReceipt {
        requireEnabled()
        if (request.operationId != ctx.operationId ||
            request.expectedRequirementVersion < 2
        ) invalid()
        val source = currentSource(requirementId)
        requireAuthority(ctx.actor, source.root.organizationId)
        val id = deterministicId("supplier-rfq", ctx.operationId)
        val canonical = fingerprint(requirementId, request.expectedRequirementVersion)
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_SUPPLIER_RFQ_DRAFT_CREATED",
            "SUPPLIER_RFQ", id, canonical, ctx.correlationId,
        )) {
            if (!requirements.lock(requirementId)) unavailable()
            val current = currentSource(requirementId)
            requireAuthority(ctx.actor, current.root.organizationId)
            if (current.version != request.expectedRequirementVersion ||
                current.state != "READY_FOR_SOURCING" || !current.sourceCurrent
            ) stale()
            if (sourcing.byRequirementAndVersion(requirementId, current.version) != null) {
                throw ProductApiException("SOURCING_ROUND_EXISTS", "This technical revision already has a sourcing round.", HttpStatus.CONFLICT)
            }
            val at = clock.instant()
            sourcing.insertRfq(SupplierRfqRecord(
                id, ctx.operationId, current.root.organizationId, requirementId, current.version,
                current.root.stockItemId, current.quantity, current.unitCode, ctx.actor,
                at, ctx.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_SUPPLIER_RFQ_DRAFT_CREATED",
                targetType = "SUPPLIER_RFQ", targetId = id, occurredAt = at,
                correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("PROCUREMENT_SUPPLIER_RFQ_DRAFT_CREATED", """{"id":"$id"}""")
        }
        return SupplierRfqReceipt(rfqView(ctx.actor, sourcing.rfq(id) ?: unavailable()), execution.replayed)
    }

    fun recordQuote(
        ctx: ProcurementDraftContext,
        rfqId: UUID,
        request: RecordSupplierQuoteRequest,
    ): SupplierQuoteReceipt {
        requireEnabled()
        if (request.operationId != ctx.operationId || request.expectedRevisionNumber < 1 ||
            !Regex("[A-Z]{3}").matches(request.currency) ||
            !Regex("[0-9a-f]{64}").matches(request.sourceSha256) ||
            request.supplierQuoteReference.isBlank() || request.supplierQuoteReference.length > 120 ||
            request.sourceEvidenceRef.isBlank() || request.sourceEvidenceRef.length > 240 ||
            request.quotedUnitCode.isBlank() || request.quotedUnitCode.length > 32 ||
            request.deliveryDays?.let { it < 0 } == true
        ) invalid()
        val quantity = moneyDecimal(request.quotedQuantity, positive = true)
        val price = moneyDecimal(request.unitPrice, positive = false)
        val tax = moneyDecimal(request.taxAmount, positive = false)
        val terms = listOfNotNull(request.deliveryTerms, request.paymentTerms, request.warrantyTerms)
        if (terms.any { it.length > 500 }) invalid()
        val root = sourcing.rfq(rfqId) ?: unavailable()
        requireAuthority(ctx.actor, root.organizationId)
        val id = deterministicId("supplier-quote", ctx.operationId)
        val fp = fingerprint(
            rfqId, request.supplierOrganizationId, request.expectedRevisionNumber,
            request.supplierQuoteReference, request.currency, quantity.toPlainString(),
            request.quotedUnitCode, price.toPlainString(), tax.toPlainString(),
            request.deliveryDays, request.validUntil,
            request.deliveryTerms, request.paymentTerms, request.warrantyTerms,
            request.sourceEvidenceRef, request.sourceSha256,
        )
        val execution = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "PROCUREMENT_SUPPLIER_QUOTE_RECORDED",
            "SUPPLIER_QUOTE_EVIDENCE", id, fp, ctx.correlationId,
        )) {
            if (!sourcing.lockRfq(rfqId)) unavailable()
            val current = sourcing.rfq(rfqId) ?: unavailable()
            requireAuthority(ctx.actor, current.organizationId)
            val source = currentSource(current.requirementId)
            if (source.root.organizationId != current.organizationId ||
                source.version != current.requirementVersion ||
                source.state != "READY_FOR_SOURCING" || !source.sourceCurrent
            ) stale()
            if (sourcing.supplierTypeAndStatus(request.supplierOrganizationId) !=
                ("SUPPLIER" to "ACTIVE")
            ) invalid()
            val revision = sourcing.latestRevision(rfqId, request.supplierOrganizationId)
            if (request.expectedRevisionNumber != revision + 1) stale()
            val now = clock.instant()
            val evidence = SupplierQuoteEvidence(
                id, rfqId, request.supplierOrganizationId,
                request.supplierQuoteReference.trim(), request.expectedRevisionNumber,
                request.currency, quantity, request.quotedUnitCode.trim(), price, tax,
                request.deliveryDays, request.validUntil, request.deliveryTerms?.trim(),
                request.paymentTerms?.trim(), request.warrantyTerms?.trim(),
                request.sourceEvidenceRef.trim(), request.sourceSha256,
            )
            sourcing.insertQuote(SupplierQuoteRecord(
                evidence, ctx.operationId, current.organizationId, ctx.actor, now,
                ctx.correlationId,
            ))
            audit.append(AuditEventRecord(
                actorUserId = ctx.actor, action = "PROCUREMENT_SUPPLIER_QUOTE_RECORDED",
                targetType = "SUPPLIER_QUOTE_EVIDENCE", targetId = id, occurredAt = now,
                correlationId = ctx.correlationId,
            ))
            IdempotentCommandOutcome("PROCUREMENT_SUPPLIER_QUOTE_RECORDED", """{"id":"$id"}""")
        }
        return SupplierQuoteReceipt(id, rfqId, request.expectedRevisionNumber, execution.replayed)
    }

    /**
     * Organization-scoped buyer inbox. Source state comes from canonical
     * S01 event history and source versions are re-read before presentation.
     */
    fun eligibleNeeds(actor: UUID, organizationId: UUID): List<SourcingEligibleNeed> {
        requireEnabled()
        requireAuthority(actor, organizationId)
        return sourcing.eligibleRequirementIds(organizationId).mapNotNull { id ->
            val source = currentSource(id)
            if (source.root.organizationId != organizationId) unavailable()
            if (source.state != "READY_FOR_SOURCING") return@mapNotNull null
            SourcingEligibleNeed(
                requirementId = id,
                technicalVersion = source.version,
                stockItemId = source.root.stockItemId,
                requestedQuantity = source.quantity.toPlainString(),
                unitCode = source.unitCode,
                technicalState = source.state,
                sourceCurrent = source.sourceCurrent,
                sourcingRoundExists = sourcing.byRequirementAndVersion(id, source.version) != null,
            )
        }
    }

    /** All rounds are private to the source's organization and buyer authority. */
    fun roundsForRequirement(actor: UUID, requirementId: UUID): List<SupplierRfqView> {
        requireEnabled()
        val root = requirements.byId(requirementId) ?: unavailable()
        requireAuthority(actor, root.organizationId)
        return sourcing.roundsForRequirement(root.organizationId, requirementId).map { round ->
            if (round.organizationId != root.organizationId ||
                round.requirementId != requirementId
            ) unavailable()
            rfqView(actor, round, sourcing.countQuotes(round.id))
        }
    }

    /** Price comparison never awards a supplier; only sourcing authority can read terms. */
    fun comparison(actor: UUID, rfqId: UUID): SupplierRfqComparisonView {
        requireEnabled()
        val root = sourcing.rfq(rfqId) ?: unavailable()
        requireAuthority(actor, root.organizationId)
        val all = sourcing.quotes(rfqId)
        val compared = SupplierQuoteComparator.compare(
            root.sourceQuantity, root.sourceUnitCode, all,
            LocalDate.now(clock.withZone(ZoneOffset.UTC)),
        )
        return SupplierRfqComparisonView(
            rfqView(actor, root, all.size), compared, all.size,
            all.map { it.supplierOrganizationId }.distinct().size,
        )
    }

    private data class Source(
        val root: PurchaseRequirementDraftRecord,
        val version: Long,
        val state: String,
        val quantity: BigDecimal,
        val unitCode: String,
        val sourceCurrent: Boolean,
    )

    private fun currentSource(id: UUID): Source {
        val root = requirements.byId(id) ?: unavailable()
        val last = requirements.latestTransition(id)
        val expectedWorkVersion = last?.revised?.workVersion ?: root.sourceWorkVersion
        val expectedItemVersion = last?.revised?.itemVersion ?: root.stockItemVersion
        val quantity = last?.revised?.quantity ?: root.requestedQuantity
        val unit = last?.revised?.unitCode ?: root.unitCode
        val order = work.workOrder(root.workOrderId)
        val item = stock.item(root.stockItemId)
        val current = order != null && order.organizationId == root.organizationId &&
            order.version == expectedWorkVersion && item != null &&
            item.organizationId == root.organizationId && item.active &&
            item.version == expectedItemVersion && item.baseUnitCode == unit
        return Source(
            root, last?.resultVersion ?: root.version,
            last?.toState ?: "DRAFT", quantity, unit, current,
        )
    }

    private fun rfqView(actor: UUID, record: SupplierRfqRecord, count: Int = 0): SupplierRfqView {
        requireAuthority(actor, record.organizationId)
        val source = currentSource(record.requirementId)
        return SupplierRfqView(
            record.id, record.requirementId, record.requirementVersion,
            record.sourceQuantity.toPlainString(), record.sourceUnitCode,
            source.root.organizationId == record.organizationId &&
                source.version == record.requirementVersion &&
                source.state == "READY_FOR_SOURCING" && source.sourceCurrent,
            count,
        )
    }

    private fun moneyDecimal(text: String, positive: Boolean): BigDecimal {
        if (!Regex("(0|[1-9][0-9]{0,11})(\\.[0-9]{1,6})?").matches(text)) invalid()
        val value = runCatching { BigDecimal(text) }.getOrElse { invalid() }
        if (if (positive) value.signum() <= 0 else value.signum() < 0) invalid()
        return value
    }

    private fun fingerprint(vararg values: Any?): String {
        val canonical = values.joinToString("") { item ->
            val value = item?.toString() ?: "(null)"
            "${value.length}:$value"
        }
        return IdempotencyKeyContract.fingerprint(canonical)
    }

    private fun deterministicId(type: String, operationId: UUID) = UUID.nameUUIDFromBytes(
        "hiltech:procurement:$type:$operationId".toByteArray(Charsets.UTF_8),
    )

    private fun requireEnabled() { if (!enabled) unavailable() }
    private fun requireAuthority(actor: UUID, org: UUID) {
        if (!authority.canManageSourcing(actor, org)) unavailable()
    }

    private fun unavailable(): Nothing = throw ProductApiException(
        "SOURCING_UNAVAILABLE", "Sourcing is unavailable for this identity or source.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "SOURCING_SOURCE_CHANGED", "The technical source or quote revision changed. Refresh.",
        HttpStatus.CONFLICT,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "INVALID_SUPPLIER_QUOTE", "Invalid sourcing details or evidence.",
        HttpStatus.BAD_REQUEST,
    )
}
