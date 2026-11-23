package com.hiltech.server.procurement

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
import java.math.BigDecimal
import java.math.RoundingMode
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class CreateSupplierInvoiceDocumentSlotRequest(val operationId: UUID)
data class SupplierInvoiceDocumentSlotView(
    val slotId: UUID, val purchaseOrderId: UUID,
    val supplierOrganizationId: UUID, val replayed: Boolean,
)
data class CaptureSupplierInvoiceRequest(
    val operationId: UUID,
    val documentSlotId: UUID,
    val evidenceId: UUID,
    val invoiceNumber: String,
    val invoiceDate: LocalDate,
    val quantity: String,
    val currency: String,
    val unitPrice: String,
    val taxAmount: String,
)
data class InvoicablePurchaseOrderView(
    val purchaseOrderId: UUID,
    val poCode: String,
    val supplierOrganizationId: UUID,
    val stockItemId: UUID,
    val quantity: String,
    val currency: String,
    val unitPrice: String,
    val taxAmount: String,
    val version: Long,
)
data class SupplierInvoiceIntakeView(
    val id: UUID, val purchaseOrderId: UUID, val supplierOrganizationId: UUID,
    val invoiceNumber: String, val quantity: String, val currency: String,
    val unitPrice: String, val taxAmount: String, val totalAmount: String,
    val submittedAt: String, val replayed: Boolean = false,
)
data class SupplierInvoiceMatchView(
    val invoiceId: UUID, val purchaseOrderId: UUID, val sourceSha256: String,
    val resultCode: String, val poQuantity: String, val receivedQuantity: String,
    val invoicedQuantity: String, val quantityVariance: String,
    val priceVariance: String, val taxVariance: String,
    val documentVerified: Boolean = true,
    val payableCreated: Boolean = false, val paymentCreated: Boolean = false,
    val matchEventId: UUID? = null, val revision: Long? = null, val replayed: Boolean = false,
)
data class RecordSupplierInvoiceMatchRequest(val operationId: UUID, val expectedSourceSha256: String)
data class SupplierInvoiceFinanceHandoffRequest(val operationId: UUID, val matchEventId: UUID)
data class SupplierInvoiceFinanceHandoffView(
    val handoffId: UUID, val invoiceId: UUID, val matchEventId: UUID,
    val state: String = "FINANCE_REVIEW_READY", val replayed: Boolean,
    val payableCreated: Boolean = false, val paymentCreated: Boolean = false,
)

private data class InvoiceFacts(
    val id: UUID, val org: UUID, val po: UUID, val supplier: UUID,
    val quantity: BigDecimal, val currency: String,
    val unitPrice: BigDecimal, val tax: BigDecimal, val evidenceId: UUID,
    val invoiceNumber: String, val total: BigDecimal, val submittedAt: String,
)
private data class OrderedFacts(
    val id: UUID, val org: UUID, val supplier: UUID,
    val quantity: BigDecimal, val currency: String,
    val unitPrice: BigDecimal, val tax: BigDecimal,
)
private data class ReceiptFacts(val accepted: BigDecimal, val discrepancy: Boolean, val receiptRefs: String)
private data class ReceiptEvidenceState(val requiresReview: Boolean, val sourceRefs: String)

/**
 * S05 receives restricted, READY invoice Evidence; compares immutable
 * PO commercial truth with actual canonical Warehouse receipts. Finance
 * handoff is an auditable review eligibility marker, NOT a payable/payment.
 */
@Service
class SupplierInvoiceMatchingService @Autowired constructor(
    private val jdbc: JdbcTemplate,
    private val purchases: PurchaseOrderIssuePersistence,
    private val approval: ApprovalPersistencePort,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    @Value("\${hiltech.procurement.supplier-invoices.enabled:false}")
    private val enabled: Boolean = false,
    @Value("\${hiltech.procurement.invoice.finance-authority-key:}")
    private val financeAuthorityKey: String = "",
) {
    fun createSlot(
        ctx: ProcurementDraftContext, poId: UUID,
        request: CreateSupplierInvoiceDocumentSlotRequest,
    ): SupplierInvoiceDocumentSlotView {
        configured()
        if (request.operationId != ctx.operationId) invalid()
        val po = order(poId) ?: unavailable()
        requireFinance(ctx.actor, po.org)
        val id = deterministic("supplier-invoice-slot", ctx.operationId)
        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "SUPPLIER_INVOICE_SLOT_CREATED",
            "PROCUREMENT_SUPPLIER_INVOICE_DOCUMENT_SLOT", id,
            hash(poId), ctx.correlationId,
        )) {
            lockAndValidatePo(poId, po.org)
            requireFinance(ctx.actor, po.org)
            check(jdbc.update(
                """
                INSERT INTO procurement_supplier_invoice_document_slot (
                  id, operation_id, organization_id, purchase_order_id,
                  supplier_organization_id, created_by_user_id, created_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), id, ctx.operationId, po.org, poId, po.supplier,
                ctx.actor, clock.instant().atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            log(ctx, "SUPPLIER_INVOICE_SLOT_CREATED", id)
            IdempotentCommandOutcome("SUPPLIER_INVOICE_SLOT_CREATED", """{"slotId":"$id"}""")
        }
        return SupplierInvoiceDocumentSlotView(id, poId, po.supplier, outcome.replayed)
    }

    fun capture(
        ctx: ProcurementDraftContext, poId: UUID,
        req: CaptureSupplierInvoiceRequest,
    ): SupplierInvoiceIntakeView {
        configured()
        if (req.operationId != ctx.operationId ||
            req.invoiceNumber.trim().length !in 2..96 ||
            req.invoiceNumber.any { it.code < 32 } ||
            !Regex("^[A-Z]{3}$").matches(req.currency)
        ) invalid()
        val qty = decimal(req.quantity, true)
        val price = decimal(req.unitPrice)
        val tax = decimal(req.taxAmount)
        if (qty.scale() > 6 || price.scale() > 6 || tax.scale() > 6) invalid()
        val po = order(poId) ?: unavailable()
        requireFinance(ctx.actor, po.org)
        val id = deterministic("supplier-invoice-intake", ctx.operationId)
        val total = qty.multiply(price).add(tax).setScale(6, RoundingMode.HALF_UP)
        val fingerprint = hash(poId, req.documentSlotId, req.evidenceId,
            req.invoiceNumber.trim(), req.invoiceDate, qty.toPlainString(), req.currency,
            price.toPlainString(), tax.toPlainString())
        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "SUPPLIER_INVOICE_INTAKE_CAPTURED",
            "PROCUREMENT_SUPPLIER_INVOICE", id, fingerprint, ctx.correlationId,
        )) {
            lockAndValidatePo(poId, po.org)
            requireFinance(ctx.actor, po.org)
            val validEvidence = jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM procurement_supplier_invoice_document_slot s
                  JOIN evidence e ON e.target_id=s.id
                    AND e.target_type='SUPPLIER_INVOICE_DOCUMENT'
                    AND e.organization_id=s.organization_id
                  WHERE s.id=? AND s.purchase_order_id=? AND s.organization_id=?
                    AND s.supplier_organization_id=? AND e.id=?
                    AND e.storage_state='READY'
                )
                """.trimIndent(), Boolean::class.java,
                req.documentSlotId, poId, po.org, po.supplier, req.evidenceId,
            ) == true
            if (!validEvidence) unavailable()
            // Record what the supplier actually invoiced, including mistakes.
            // Never silently discard an over-billed invoice: the immutable
            // Finance match must classify QUANTITY_MISMATCH and block handoff.
            // The DB unique invoice-number key prevents duplicate capture.
            check(jdbc.update(
                """
                INSERT INTO procurement_supplier_invoice_intake (
                  id, operation_id, organization_id, purchase_order_id,
                  supplier_organization_id, document_slot_id, evidence_id,
                  invoice_number, invoice_date, currency, quantity, unit_price,
                  tax_amount, total_amount, submitted_by_user_id, submitted_at,
                  correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), id, ctx.operationId, po.org, poId, po.supplier,
                req.documentSlotId, req.evidenceId, req.invoiceNumber.trim(),
                req.invoiceDate, req.currency, qty, price, tax, total, ctx.actor,
                clock.instant().atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            log(ctx, "SUPPLIER_INVOICE_INTAKE_CAPTURED", id)
            IdempotentCommandOutcome("SUPPLIER_INVOICE_INTAKE_CAPTURED", """{"invoiceId":"$id"}""")
        }
        return invoice(id)?.let(::view)?.copy(replayed = outcome.replayed) ?: unavailable()
    }

    fun list(actor: UUID, org: UUID): List<SupplierInvoiceIntakeView> {
        requireFinance(actor, org)
        return jdbc.query(
            """
            SELECT * FROM procurement_supplier_invoice_intake
            WHERE organization_id=? ORDER BY submitted_at DESC LIMIT 100
            """.trimIndent(), { rs, _ -> invoiceRow(rs).let(::view) }, org,
        )
    }

    fun eligibleOrders(actor: UUID, org: UUID): List<InvoicablePurchaseOrderView> {
        requireFinance(actor, org)
        val ids = jdbc.query(
            """
            SELECT id FROM procurement_purchase_order
            WHERE organization_id=? ORDER BY issued_at DESC LIMIT 100
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, org,
        )
        return ids.mapNotNull { id ->
            val state = purchases.effectiveState(id) ?: return@mapNotNull null
            if (state.order.organizationId != org || state.effectiveState != "ISSUED" ||
                !purchases.hasSupplierCommunication(id)
            ) return@mapNotNull null
            jdbc.query(
                """
                SELECT id, po_code, supplier_organization_id, stock_item_id,
                       quantity, currency, unit_price, tax_amount
                FROM procurement_purchase_order
                WHERE id=? AND organization_id=?
                """.trimIndent(),
                { rs, _ -> InvoicablePurchaseOrderView(
                    rs.getObject("id", UUID::class.java),
                    rs.getString("po_code"),
                    rs.getObject("supplier_organization_id", UUID::class.java),
                    rs.getObject("stock_item_id", UUID::class.java),
                    rs.getBigDecimal("quantity").toPlainString(),
                    rs.getString("currency"),
                    rs.getBigDecimal("unit_price").toPlainString(),
                    rs.getBigDecimal("tax_amount").toPlainString(),
                    state.version,
                ) }, id, org,
            ).singleOrNull()
        }
    }

    fun preview(actor: UUID, invoiceId: UUID): SupplierInvoiceMatchView {
        val inv = invoice(invoiceId) ?: unavailable()
        requireFinance(actor, inv.org)
        return compute(inv)
    }

    fun recordMatch(
        ctx: ProcurementDraftContext, invoiceId: UUID,
        request: RecordSupplierInvoiceMatchRequest,
    ): SupplierInvoiceMatchView {
        val inv = invoice(invoiceId) ?: unavailable()
        requireFinance(ctx.actor, inv.org)
        if (ctx.operationId != request.operationId || !Regex("^[a-f0-9]{64}$").matches(
                request.expectedSourceSha256
            )
        ) invalid()
        val id = deterministic("supplier-invoice-match", ctx.operationId)
        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "SUPPLIER_INVOICE_MATCH_RECORDED",
            "PROCUREMENT_SUPPLIER_INVOICE_MATCH_EVENT", id,
            hash(invoiceId, request.expectedSourceSha256), ctx.correlationId,
        )) {
            lockAndValidatePo(inv.po, inv.org)
            requireFinance(ctx.actor, inv.org)
            val current = invoice(invoiceId) ?: unavailable()
            val match = compute(current)
            if (match.sourceSha256 != request.expectedSourceSha256) stale()
            val revision = jdbc.queryForObject(
                "SELECT COALESCE(MAX(revision),0)+1 FROM procurement_supplier_invoice_match_event WHERE invoice_id=?",
                Long::class.java, invoiceId,
            ) ?: 1L
            check(jdbc.update(
                """
                INSERT INTO procurement_supplier_invoice_match_event (
                  id, operation_id, organization_id, invoice_id, purchase_order_id,
                  revision, result_code, po_quantity, received_quantity,
                  invoiced_quantity, quantity_variance, price_variance, tax_variance,
                  source_sha256, checked_by_user_id, checked_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, ctx.operationId, inv.org, invoiceId, inv.po,
                revision, match.resultCode, BigDecimal(match.poQuantity),
                BigDecimal(match.receivedQuantity), BigDecimal(match.invoicedQuantity),
                BigDecimal(match.quantityVariance), BigDecimal(match.priceVariance),
                BigDecimal(match.taxVariance), match.sourceSha256,
                ctx.actor, clock.instant().atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            log(ctx, "SUPPLIER_INVOICE_MATCH_RECORDED", id)
            IdempotentCommandOutcome("SUPPLIER_INVOICE_MATCH_RECORDED", """{"matchEventId":"$id"}""")
        }
        val saved = jdbc.query(
            "SELECT revision FROM procurement_supplier_invoice_match_event WHERE id=? AND invoice_id=?",
            { rs, _ -> rs.getLong("revision") }, id, invoiceId,
        ).singleOrNull() ?: unavailable()
        return compute(inv).copy(matchEventId = id, revision = saved, replayed = outcome.replayed)
    }

    fun handoff(
        ctx: ProcurementDraftContext, invoiceId: UUID,
        req: SupplierInvoiceFinanceHandoffRequest,
    ): SupplierInvoiceFinanceHandoffView {
        val inv = invoice(invoiceId) ?: unavailable()
        requireFinance(ctx.actor, inv.org)
        if (ctx.operationId != req.operationId) invalid()
        val id = deterministic("supplier-invoice-finance-handoff", ctx.operationId)
        val outcome = commands.execute(IdempotentCommandSpec(
            ctx.operationId, ctx.actor, "SUPPLIER_INVOICE_FINANCE_HANDOFF",
            "PROCUREMENT_SUPPLIER_INVOICE_FINANCE_HANDOFF", id,
            hash(invoiceId, req.matchEventId), ctx.correlationId,
        )) {
            lockAndValidatePo(inv.po, inv.org)
            requireFinance(ctx.actor, inv.org)
            val match = compute(invoice(invoiceId) ?: unavailable())
            if (match.resultCode != "MATCHED") stale()
            val approved = jdbc.query(
                """
                SELECT id FROM procurement_supplier_invoice_match_event
                WHERE id=? AND invoice_id=? AND organization_id=?
                  AND result_code='MATCHED' AND source_sha256=?
                  AND revision=(
                    SELECT MAX(revision) FROM procurement_supplier_invoice_match_event
                    WHERE invoice_id=?
                  )
                """.trimIndent(), { rs, _ -> rs.getObject("id", UUID::class.java) },
                req.matchEventId, invoiceId, inv.org, match.sourceSha256, invoiceId,
            ).singleOrNull() ?: stale()
            check(jdbc.update(
                """
                INSERT INTO procurement_supplier_invoice_finance_handoff (
                  id, operation_id, organization_id, invoice_id, matched_event_id,
                  reviewed_by_user_id, reviewed_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(), id, ctx.operationId, inv.org, invoiceId, approved,
                ctx.actor, clock.instant().atOffset(ZoneOffset.UTC), ctx.correlationId,
            ) == 1)
            log(ctx, "SUPPLIER_INVOICE_FINANCE_HANDOFF", id)
            IdempotentCommandOutcome("SUPPLIER_INVOICE_FINANCE_HANDOFF", """{"handoffId":"$id"}""")
        }
        return SupplierInvoiceFinanceHandoffView(id, invoiceId, req.matchEventId, replayed = outcome.replayed)
    }

    private fun compute(inv: InvoiceFacts): SupplierInvoiceMatchView {
        val po = order(inv.po) ?: unavailable()
        val received = jdbc.query(
            """
            SELECT
              COALESCE(SUM(CASE WHEN l.condition_received IN ('GOOD','FAIR')
                THEN l.received_quantity ELSE 0 END),0) AS accepted,
              EXISTS(
                SELECT 1 FROM receiving_discrepancy d
                JOIN procurement_po_receiving_link b2 ON b2.receiving_record_id=d.receiving_record_id
                WHERE b2.purchase_order_id=?
              ) OR COALESCE(BOOL_OR(l.condition_received IN ('DAMAGED','UNFIT')),false)
                AS has_discrepancy,
              COALESCE(STRING_AGG(l.id::text || ':' || l.version::text, ',' ORDER BY l.id), '')
                AS receipt_refs
            FROM procurement_po_receiving_link b
            JOIN receiving_line l ON l.receiving_record_id=b.receiving_record_id
            WHERE b.purchase_order_id=? AND l.received_at IS NOT NULL
            """.trimIndent(),
            { rs, _ -> ReceiptFacts(
                rs.getBigDecimal("accepted"), rs.getBoolean("has_discrepancy"),
                rs.getString("receipt_refs"),
            ) }, inv.po, inv.po,
        ).singleOrNull() ?: ReceiptFacts(BigDecimal.ZERO, false, "")
        // A posted stock line is not yet approved three-way-match evidence:
        // the parent Receiving must be CLOSED with a verified delivery note.
        // Any unclosed or undocumented sibling delivery blocks Finance handoff.
        val deliveryEvidence = jdbc.query(
            """
            SELECT
              COALESCE(BOOL_OR(
                r.state NOT IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY')
                OR e.id IS NULL
              ),false) AS requires_review,
              COALESCE(STRING_AGG(
                r.id::text || ':' || r.version::text || ':' || r.state ||
                ':' || COALESCE(e.id::text,'NO_READY_NOTE'),
                ',' ORDER BY r.id
              ),'') AS source_refs
            FROM procurement_po_receiving_link b
            JOIN receiving_record r ON r.id=b.receiving_record_id
              AND r.organization_id=b.organization_id
            LEFT JOIN LATERAL (
              SELECT proof.id FROM evidence proof
              WHERE proof.organization_id=r.organization_id
                AND proof.target_id=r.id
                AND proof.target_type='PO_RECEIVING_DELIVERY_NOTE'
                AND proof.evidence_requirement_key='PO_DELIVERY_NOTE_BINARY'
                AND proof.evidence_type_code='PO_DELIVERY_NOTE'
                AND proof.storage_state='READY'
                AND proof.classification_code='HIGHLY_RESTRICTED'
                AND proof.client_visibility_mode='INTERNAL_ONLY'
              LIMIT 1
            ) e ON true
            WHERE b.purchase_order_id=? AND b.organization_id=?
            """.trimIndent(), { rs, _ -> ReceiptEvidenceState(
                rs.getBoolean("requires_review"), rs.getString("source_refs"),
            ) }, inv.po, inv.org,
        ).singleOrNull() ?: ReceiptEvidenceState(true, "")
        val invoicedTotal = jdbc.queryForObject(
            "SELECT COALESCE(SUM(quantity),0) FROM procurement_supplier_invoice_intake WHERE purchase_order_id=?",
            BigDecimal::class.java, inv.po,
        ) ?: BigDecimal.ZERO
        val taxExpected = po.tax.multiply(inv.quantity)
            .divide(po.quantity, 6, RoundingMode.HALF_UP)
        val qDelta = received.accepted.subtract(invoicedTotal)
        val pDelta = inv.unitPrice.subtract(po.unitPrice)
        val tDelta = inv.tax.subtract(taxExpected)
        val result = SupplierInvoiceMatchRules.classify(
            SupplierInvoiceMatchFacts(
                orderedQuantity = po.quantity,
                acceptedQuantity = received.accepted,
                invoicedQuantity = invoicedTotal,
                poCurrency = po.currency,
                invoiceCurrency = inv.currency,
                priceVariance = pDelta,
                taxVariance = tDelta,
                unresolvedPhysicalDiscrepancy = received.discrepancy || deliveryEvidence.requiresReview,
            ),
        )
        val discrepancyRefs = jdbc.queryForObject(
            """
            SELECT COALESCE(STRING_AGG(source_ref, ',' ORDER BY source_ref), '')
            FROM (
              SELECT 'DISCREPANCY:' || d.id::text || ':' || d.version::text AS source_ref
              FROM receiving_discrepancy d
              JOIN procurement_po_receiving_link b
                ON b.receiving_record_id = d.receiving_record_id
              WHERE b.purchase_order_id = ? AND b.organization_id = ?
              UNION ALL
              SELECT 'REJECTED:' || r.id::text AS source_ref
              FROM procurement_po_receiving_rejection_event r
              JOIN procurement_po_receiving_link b
                ON b.receiving_record_id = r.receiving_record_id
              WHERE b.purchase_order_id = ? AND b.organization_id = ?
            ) physical_exceptions
            """.trimIndent(),
            String::class.java, inv.po, inv.org, inv.po, inv.org,
        ) ?: ""
        val source = hash(inv.id, po.id, po.quantity, po.currency, po.unitPrice,
            po.tax, inv.quantity, inv.currency, inv.unitPrice, inv.tax, inv.evidenceId,
            received.accepted, received.discrepancy, received.receiptRefs,
            discrepancyRefs, deliveryEvidence.sourceRefs, invoicedTotal, result)
        return SupplierInvoiceMatchView(inv.id, inv.po, source, result,
            po.quantity.toPlainString(), received.accepted.toPlainString(),
            invoicedTotal.toPlainString(), qDelta.toPlainString(),
            pDelta.toPlainString(), tDelta.toPlainString())
    }

    private fun invoice(id: UUID): InvoiceFacts? =
        jdbc.query("SELECT * FROM procurement_supplier_invoice_intake WHERE id=?",
            { rs, _ -> invoiceRow(rs) }, id).singleOrNull()

    private fun invoiceRow(rs: java.sql.ResultSet) = InvoiceFacts(
        rs.getObject("id", UUID::class.java),
        rs.getObject("organization_id", UUID::class.java),
        rs.getObject("purchase_order_id", UUID::class.java),
        rs.getObject("supplier_organization_id", UUID::class.java),
        rs.getBigDecimal("quantity"), rs.getString("currency"),
        rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_amount"),
        rs.getObject("evidence_id", UUID::class.java),
        rs.getString("invoice_number"), rs.getBigDecimal("total_amount"),
        rs.getObject("submitted_at", OffsetDateTime::class.java).toInstant().toString(),
    )

    private fun view(f: InvoiceFacts) = SupplierInvoiceIntakeView(
        f.id, f.po, f.supplier, f.invoiceNumber, f.quantity.toPlainString(), f.currency,
        f.unitPrice.toPlainString(), f.tax.toPlainString(), f.total.toPlainString(), f.submittedAt,
    )

    private fun order(id: UUID): OrderedFacts? = jdbc.query(
        """
        SELECT id, organization_id, supplier_organization_id,
               quantity, currency, unit_price, tax_amount
        FROM procurement_purchase_order WHERE id=?
        """.trimIndent(),
        { rs, _ -> OrderedFacts(
            rs.getObject("id", UUID::class.java),
            rs.getObject("organization_id", UUID::class.java),
            rs.getObject("supplier_organization_id", UUID::class.java),
            rs.getBigDecimal("quantity"), rs.getString("currency"),
            rs.getBigDecimal("unit_price"), rs.getBigDecimal("tax_amount"),
        ) }, id,
    ).singleOrNull()

    private fun lockAndValidatePo(id: UUID, org: UUID) {
        if (!purchases.lockOrder(id, org)) unavailable()
        val effective = purchases.effectiveState(id) ?: unavailable()
        if (effective.order.organizationId != org ||
            effective.effectiveState != "ISSUED" ||
            !purchases.hasSupplierCommunication(id)
        ) stale()
    }

    private fun requireFinance(actor: UUID, org: UUID) {
        configured()
        val now = clock.instant()
        val key = financeAuthorityKey.trim()
        if (!approval.isActiveOrganizationMember(org, actor, now)) unavailable()
        val current = approval.resolveCurrentAuthority(org, key, now) ?: unavailable()
        if (current.organizationId != org || current.authorityKey != key ||
            !approval.actorMatchesAuthority(actor, current, now)
        ) unavailable()
    }

    private fun configured() {
        if (!enabled || !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(financeAuthorityKey.trim()))
            unavailable()
    }
    private fun decimal(text: String, strictlyPositive: Boolean = false): BigDecimal {
        val value = runCatching { BigDecimal(text) }.getOrElse { invalid() }
        if ((strictlyPositive && value.signum() <= 0) ||
            (!strictlyPositive && value.signum() < 0) ||
            value.precision() > 18 || value.scale() > 6
        ) invalid()
        return value
    }
    private fun log(ctx: ProcurementDraftContext, action: String, id: UUID) {
        audit.append(AuditEventRecord(
            actorUserId = ctx.actor, action = action,
            targetType = "PROCUREMENT_SUPPLIER_INVOICE", targetId = id,
            occurredAt = clock.instant(), correlationId = ctx.correlationId,
            reason = "SOURCE_BOUND_FINANCE_REVIEW_ONLY_NO_PAYABLE",
        ))
    }
    private fun deterministic(kind: String, op: UUID) =
        UUID.nameUUIDFromBytes("hiltech:$kind:$op".toByteArray(Charsets.UTF_8))
    private fun hash(vararg fields: Any): String =
        MessageDigest.getInstance("SHA-256")
            .digest(fields.joinToString("\u001f").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    private fun unavailable(): Nothing = throw ProductApiException(
        "PROCUREMENT_SUPPLIER_INVOICE_UNAVAILABLE",
        "Invoice is unavailable or financial access is not authorized.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "PROCUREMENT_SUPPLIER_INVOICE_INVALID",
        "Invoice input requires valid numbers, Evidence and supplier fields.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "PROCUREMENT_SUPPLIER_INVOICE_SOURCE_CHANGED",
        "Invoice/receipt source changed or requires exception review.", HttpStatus.CONFLICT,
    )
}

@RestController
class SupplierInvoiceMatchingController(private val service: SupplierInvoiceMatchingService) {
    @PostMapping("/v1/procurement/purchase-orders/{id}/invoice-document-slots")
    fun slot(servlet: HttpServletRequest, @PathVariable id: UUID,
             @RequestBody body: CreateSupplierInvoiceDocumentSlotRequest) =
        service.createSlot(context(servlet, body.operationId), id, body)

    @PostMapping("/v1/procurement/purchase-orders/{id}/supplier-invoices")
    fun capture(servlet: HttpServletRequest, @PathVariable id: UUID,
                @RequestBody body: CaptureSupplierInvoiceRequest) =
        service.capture(context(servlet, body.operationId), id, body)

    @GetMapping("/v1/procurement/organizations/{orgId}/invoicable-pos")
    fun eligibleOrders(servlet: HttpServletRequest, @PathVariable orgId: UUID) =
        service.eligibleOrders(actor(servlet), orgId)

    @GetMapping("/v1/procurement/organizations/{orgId}/supplier-invoices")
    fun list(servlet: HttpServletRequest, @PathVariable orgId: UUID) =
        service.list(actor(servlet), orgId)

    @GetMapping("/v1/procurement/supplier-invoices/{id}/match-preview")
    fun preview(servlet: HttpServletRequest, @PathVariable id: UUID) =
        service.preview(actor(servlet), id)

    @PostMapping("/v1/procurement/supplier-invoices/{id}/matches")
    fun match(servlet: HttpServletRequest, @PathVariable id: UUID,
              @RequestBody body: RecordSupplierInvoiceMatchRequest) =
        service.recordMatch(context(servlet, body.operationId), id, body)

    @PostMapping("/v1/procurement/supplier-invoices/{id}/finance-handoff")
    fun handoff(servlet: HttpServletRequest, @PathVariable id: UUID,
                @RequestBody body: SupplierInvoiceFinanceHandoffRequest) =
        service.handoff(context(servlet, body.operationId), id, body)

    private fun context(servlet: HttpServletRequest, operationId: UUID): ProcurementDraftContext {
        val ctx = HiltechRequestContext.current(servlet)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey, operationId)
        return ProcurementDraftContext(operationId, actor(servlet), ctx.correlationId)
    }
    private fun actor(servlet: HttpServletRequest): UUID =
        HiltechRequestContext.current(servlet).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException("UNAUTHENTICATED", "Authentication required", HttpStatus.UNAUTHORIZED)
}
