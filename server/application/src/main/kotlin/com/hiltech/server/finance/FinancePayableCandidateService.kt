package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.PurchaseOrderIssuePersistence
import com.hiltech.server.procurement.SupplierInvoiceMatchingService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

data class RecordSupplierPayableCandidateRequest(
    val operationId: UUID,
    val expectedSourceSha256: String,
)
data class SupplierHandoffFinanceView(
    val handoffId: UUID, val invoiceId: UUID, val purchaseOrderId: UUID,
    val supplierOrganizationId: UUID, val invoiceNumber: String,
    val currency: String, val invoiceAmount: String,
    val sourceSha256: String, val eligible: Boolean, val alreadyRecorded: Boolean,
)
data class SupplierPayableCandidateView(
    val id: UUID, val handoffId: UUID, val invoiceId: UUID,
    val supplierOrganizationId: UUID, val invoiceNumber: String,
    val amount: String, val currency: String, val sourceSha256: String,
    val state: String, val sourceCurrent: Boolean, val replayed: Boolean = false,
    val organizationId: UUID? = null,
)
private data class SupplierSource(
    val handoffId: UUID, val org: UUID, val invoiceId: UUID, val poId: UUID,
    val supplierId: UUID, val evidenceId: UUID, val invoiceNumber: String,
    val currency: String, val amount: BigDecimal,
    val matchId: UUID, val matchRevision: Long, val sourceSha256: String,
)

/**
 * Finance 8A intake, not accounting posting.
 *
 * Never accepts caller-entered amount, supplier, currency, invoice or beneficiary.
 * The only source is a genuine P7 supplier invoice + latest MATCHED revision +
 * Finance handoff; the physical three-way match is checked under the PO lock.
 * Accounting approval, legal booking, payment and bank integration remain absent.
 */
@Service
class FinancePayableCandidateService(
    private val jdbc: JdbcTemplate,
    private val approval: ApprovalPersistencePort,
    private val purchases: PurchaseOrderIssuePersistence,
    private val invoices: SupplierInvoiceMatchingService,
    private val commands: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val environment: Environment,
) {
    fun handoffs(actor: UUID, org: UUID): List<SupplierHandoffFinanceView> {
        requireFinance(actor, org)
        val ids = jdbc.query(
            """
            SELECT h.id FROM procurement_supplier_invoice_finance_handoff h
            WHERE h.organization_id=?
            ORDER BY h.reviewed_at DESC LIMIT 100
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, org,
        )
        return ids.mapNotNull { id ->
            val source = loadSource(id) ?: return@mapNotNull null
            if (source.org != org) return@mapNotNull null
            val current = isCurrent(actor, source)
            SupplierHandoffFinanceView(
                id, source.invoiceId, source.poId, source.supplierId,
                source.invoiceNumber, source.currency, source.amount.toPlainString(),
                source.sourceSha256, current,
                jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM finance_payable_candidate WHERE finance_handoff_id=?)",
                    Boolean::class.java, id,
                ) == true,
            )
        }
    }

    fun candidates(actor: UUID, org: UUID): List<SupplierPayableCandidateView> {
        requireFinance(actor, org)
        return jdbc.query(
            """
            SELECT id FROM finance_payable_candidate
            WHERE organization_id=? ORDER BY recorded_at DESC LIMIT 100
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) }, org,
        ).map { detail(actor, it) }
    }

    fun detail(actor: UUID, id: UUID): SupplierPayableCandidateView {
        configured()
        val row = jdbc.query(
            """
            SELECT id, organization_id, finance_handoff_id, supplier_invoice_id,
              supplier_organization_id, invoice_number, gross_amount, currency,
              source_sha256, state FROM finance_payable_candidate WHERE id=?
            """.trimIndent(),
            { rs, _ -> SupplierPayableCandidateRow(
                rs.getObject("id", UUID::class.java),
                rs.getObject("organization_id", UUID::class.java),
                rs.getObject("finance_handoff_id", UUID::class.java),
                rs.getObject("supplier_invoice_id", UUID::class.java),
                rs.getObject("supplier_organization_id", UUID::class.java),
                rs.getString("invoice_number"), rs.getBigDecimal("gross_amount"),
                rs.getString("currency"), rs.getString("source_sha256"),
                rs.getString("state"),
            ) }, id,
        ).singleOrNull() ?: unavailable()
        requireFinance(actor, row.org)
        val source = loadSource(row.handoffId)
        return SupplierPayableCandidateView(
            row.id, row.handoffId, row.invoiceId, row.supplierId,
            row.invoiceNumber, row.amount.toPlainString(), row.currency,
            row.sha, row.state,
            source != null && source.org == row.org &&
                source.invoiceId == row.invoiceId && isCurrent(actor, source),
            organizationId = row.org,
        )
    }

    fun record(
        actor: UUID, correlation: String, request: RecordSupplierPayableCandidateRequest,
        idempotencyKey: String?,
        handoffId: UUID,
    ): SupplierPayableCandidateView {
        configured()
        IdempotencyKeyContract.requireMatches(idempotencyKey, request.operationId)
        if (!Regex("^[a-f0-9]{64}$").matches(request.expectedSourceSha256)) invalid()
        val source = loadSource(handoffId) ?: unavailable()
        requireFinance(actor, source.org)
        if (source.sourceSha256 != request.expectedSourceSha256 ||
            !isCurrent(actor, source)) stale()
        val id = UUID.nameUUIDFromBytes(
            "hiltech:finance-payable-candidate:${request.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = hash(handoffId, request.expectedSourceSha256)
        val result = commands.execute(IdempotentCommandSpec(
            request.operationId, actor, "FINANCE_PAYABLE_CANDIDATE_RECORDED",
            "FINANCE_PAYABLE_CANDIDATE", id, fingerprint, correlation,
        )) {
            // P7 receiving/matching source is serialized on the canonical PO.
            if (!purchases.lockOrder(source.poId, source.org)) stale()
            requireFinance(actor, source.org)
            val fresh = loadSource(handoffId) ?: stale()
            if (fresh != source || fresh.sourceSha256 != request.expectedSourceSha256 ||
                !isCurrent(actor, fresh)) stale()
            if (fresh.amount.signum() <= 0) stale()
            val at = clock.instant()
            check(jdbc.update(
                """
                INSERT INTO finance_payable_candidate (
                    id, operation_id, organization_id, supplier_organization_id,
                    purchase_order_id, supplier_invoice_id, finance_handoff_id,
                    matched_event_id, invoice_evidence_id, invoice_number,
                    source_sha256, match_revision, currency, gross_amount,
                    recorded_by_user_id, recorded_at, correlation_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, request.operationId, fresh.org, fresh.supplierId,
                fresh.poId, fresh.invoiceId, fresh.handoffId, fresh.matchId,
                fresh.evidenceId, fresh.invoiceNumber, fresh.sourceSha256,
                fresh.matchRevision, fresh.currency, fresh.amount,
                actor, at.atOffset(ZoneOffset.UTC), correlation,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId = actor, action = "FINANCE_PAYABLE_CANDIDATE_RECORDED",
                targetType = "FINANCE_PAYABLE_CANDIDATE", targetId = id,
                occurredAt = at, correlationId = correlation,
                reason = "ACCOUNTING_REVIEW_REQUIRED_NO_POSTING_OR_PAYMENT",
            ))
            IdempotentCommandOutcome(
                "FINANCE_PAYABLE_CANDIDATE_RECORDED", """{"id":"$id"}""",
            )
        }
        return detail(actor, id).copy(replayed = result.replayed)
    }

    private data class SupplierPayableCandidateRow(
        val id: UUID, val org: UUID, val handoffId: UUID,
        val invoiceId: UUID, val supplierId: UUID, val invoiceNumber: String,
        val amount: BigDecimal, val currency: String, val sha: String, val state: String,
    )

    private fun loadSource(id: UUID): SupplierSource? = jdbc.query(
        """
        SELECT h.id AS handoff_id, h.organization_id, h.invoice_id,
          h.matched_event_id, i.purchase_order_id, i.supplier_organization_id,
          i.evidence_id, i.invoice_number, i.currency, i.total_amount,
          m.revision, m.source_sha256
        FROM procurement_supplier_invoice_finance_handoff h
        JOIN procurement_supplier_invoice_intake i
          ON i.id=h.invoice_id AND i.organization_id=h.organization_id
        JOIN procurement_supplier_invoice_match_event m
          ON m.id=h.matched_event_id AND m.invoice_id=i.id
          AND m.organization_id=i.organization_id AND m.result_code='MATCHED'
        WHERE h.id=?
        """.trimIndent(),
        { rs, _ -> SupplierSource(
            rs.getObject("handoff_id", UUID::class.java),
            rs.getObject("organization_id", UUID::class.java),
            rs.getObject("invoice_id", UUID::class.java),
            rs.getObject("purchase_order_id", UUID::class.java),
            rs.getObject("supplier_organization_id", UUID::class.java),
            rs.getObject("evidence_id", UUID::class.java),
            rs.getString("invoice_number"), rs.getString("currency"),
            rs.getBigDecimal("total_amount"),
            rs.getObject("matched_event_id", UUID::class.java),
            rs.getLong("revision"), rs.getString("source_sha256"),
        ) }, id,
    ).singleOrNull()

    private fun isCurrent(actor: UUID, s: SupplierSource): Boolean {
        val match = invoices.preview(actor, s.invoiceId)
        return match.resultCode == "MATCHED" &&
            match.invoiceId == s.invoiceId &&
            match.purchaseOrderId == s.poId &&
            match.sourceSha256 == s.sourceSha256 &&
            s.amount.signum() > 0 &&
            jdbc.queryForObject(
                """
                SELECT EXISTS(SELECT 1 FROM procurement_supplier_invoice_match_event
                  WHERE id=? AND invoice_id=? AND revision=(
                    SELECT MAX(revision) FROM procurement_supplier_invoice_match_event
                    WHERE invoice_id=?))
                """.trimIndent(), Boolean::class.java, s.matchId, s.invoiceId, s.invoiceId,
            ) == true
    }

    private fun configured() {
        if (!environment.getProperty("hiltech.finance.payable-candidates.enabled", Boolean::class.java, false) ||
            !Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(
                environment.getProperty("hiltech.finance.payables.review-authority-key", "").trim(),
            )) unavailable()
    }

    private fun requireFinance(actor: UUID, org: UUID) {
        configured()
        val key = environment.getProperty("hiltech.finance.payables.review-authority-key", "").trim()
        val now = clock.instant()
        if (!approval.isActiveOrganizationMember(org, actor, now)) unavailable()
        val authority = approval.resolveCurrentAuthority(org, key, now) ?: unavailable()
        if (authority.organizationId != org || authority.authorityKey != key ||
            !approval.actorMatchesAuthority(actor, authority, now)) unavailable()
    }

    private fun hash(vararg values: Any) = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("\u001f").toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun unavailable(): Nothing = throw ProductApiException(
        "FINANCE_PAYABLE_UNAVAILABLE", "Finance record unavailable or not authorized.", HttpStatus.NOT_FOUND,
    )
    private fun invalid(): Nothing = throw ProductApiException(
        "FINANCE_PAYABLE_INVALID", "Invalid Finance intake command.", HttpStatus.BAD_REQUEST,
    )
    private fun stale(): Nothing = throw ProductApiException(
        "FINANCE_SOURCE_CHANGED", "Source invoice/match requires fresh review.", HttpStatus.CONFLICT,
    )
}

@RestController
class FinancePayableCandidateController(private val finance: FinancePayableCandidateService) {
    @GetMapping("/v1/finance/organizations/{orgId}/supplier-handoffs")
    fun handoffs(req: HttpServletRequest, @PathVariable orgId: UUID) =
        finance.handoffs(actor(req), orgId)

    @GetMapping("/v1/finance/organizations/{orgId}/payable-candidates")
    fun candidates(req: HttpServletRequest, @PathVariable orgId: UUID) =
        finance.candidates(actor(req), orgId)

    @GetMapping("/v1/finance/payable-candidates/{id}")
    fun detail(req: HttpServletRequest, @PathVariable id: UUID) =
        finance.detail(actor(req), id)

    @PostMapping("/v1/finance/supplier-handoffs/{id}/payable-candidates")
    fun record(req: HttpServletRequest, @PathVariable id: UUID,
               @RequestBody command: RecordSupplierPayableCandidateRequest): SupplierPayableCandidateView {
        val ctx = HiltechRequestContext.current(req)
        return finance.record(
            actor(req), ctx.correlationId, command, ctx.idempotencyKey, id,
        )
    }
    private fun actor(req: HttpServletRequest): UUID =
        HiltechRequestContext.current(req).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}
