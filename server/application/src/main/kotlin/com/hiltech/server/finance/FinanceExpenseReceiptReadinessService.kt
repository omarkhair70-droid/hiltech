package com.hiltech.server.finance

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

/** Individual Evidence is an existing canonical file, NEVER a new Finance blob. */
data class FinanceExpenseReceiptStatus(
    val expenseDraftId: UUID,
    val organizationId: UUID,
    val currency: String,
    val expenseAmount: String,
    val receiptEvidenceTargetType: String = "FINANCE_EXPENSE_RECEIPT",
    val verifiedReceiptEvidenceIds: List<UUID>,
    val verifiedReceiptCount: Int,
    val uploadedReceiptReady: Boolean,
    val observedAt: String,
    val reviewerActionAllowed: Boolean = false,
    val paymentActionAllowed: Boolean = false,
    val blockingReasons: List<String>,
)

/**
 * Read-only source verification, not a replacement for canonical Evidence.
 *
 * Users only see their own drafts through Phase8B current employment guard.
 * Reviewer views delegate to current Finance reviewer authority. The Finance
 * module never marks an upload READY, issues download URLs or adds new files.
 */
@Service
class FinanceExpenseReceiptReadinessService(
    private val jdbc: JdbcTemplate,
    private val employeeDrafts: FinanceEmployeeDraftService,
    private val reviewers: FinanceEmployeeReviewService,
    private val clock: Clock,
    private val environment: Environment,
) {
    fun mine(actor: UUID, org: UUID): List<FinanceExpenseReceiptStatus> {
        configured()
        return employeeDrafts.expenses(actor, org).map {
            checkExpenseEvidence(
                id = it.id, org = it.organizationId,
                amount = it.amount, currency = it.currency,
                creator = creatorForOwn(it.id, org, actor),
            )
        }
    }

    fun reviewerQueue(actor: UUID, org: UUID): List<FinanceExpenseReceiptStatus> {
        configured()
        // Only the company's actively appointed Finance reviewer may list
        // another employee's expense; current membership is rechecked here.
        return reviewers.list(actor, org)
            .filter { it.kind == "EXPENSE_REIMBURSEMENT" }
            .map {
                checkExpenseEvidence(
                    id = it.id, org = it.organizationId,
                    amount = it.amount, currency = it.currency,
                    creator = creatorForReview(it.id, org),
                )
            }
    }

    internal fun checkExpenseEvidence(
        id: UUID, org: UUID, amount: String, currency: String,
        creator: UUID,
    ): FinanceExpenseReceiptStatus {
        val ids = jdbc.query(
            """
            SELECT DISTINCT e.id
            FROM evidence e
            JOIN evidence_upload_session s ON s.evidence_id=e.id
            WHERE e.organization_id=? AND e.target_type='FINANCE_EXPENSE_RECEIPT'
              AND e.target_id=?
              AND e.captured_by_user_id=?
              AND e.evidence_requirement_key='FINANCE_EXPENSE_RECEIPT_BINARY'
              AND e.evidence_type_code='FINANCE_EXPENSE_RECEIPT'
              AND e.storage_state='READY' AND s.state='READY'
              AND e.finalized_at IS NOT NULL AND s.finalized_at IS NOT NULL
              AND e.object_key_ref IS NOT NULL
              AND e.sha256=s.expected_sha256
              AND e.size_bytes=s.expected_size_bytes
              AND e.classification_code='HIGHLY_RESTRICTED'
              AND e.client_visibility_mode='INTERNAL_ONLY'
              AND e.security_scan_class='ARBITRARY_FILE'
              AND e.content_type IN ('application/pdf','image/png','image/jpeg')
            ORDER BY e.id
            LIMIT 100
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            org, id, creator,
        )
        // This second switch must remain OFF until the canonical Evidence
        // owner has implemented target-specific upload/finalize/scan/access.
        // A bare DB row can never enable Finance approvals.
        val uploadSupported = environment.getProperty(
            "hiltech.finance.expense-receipts.canonical-upload-ready",
            Boolean::class.java, false,
        )
        val ready = uploadSupported && ids.isNotEmpty()
        return FinanceExpenseReceiptStatus(
            expenseDraftId = id, organizationId = org,
            currency = currency, expenseAmount = amount,
            verifiedReceiptEvidenceIds = if (uploadSupported) ids else emptyList(),
            verifiedReceiptCount = if (uploadSupported) ids.size else 0,
            uploadedReceiptReady = ready,
            observedAt = clock.instant().toString(),
            blockingReasons = buildList {
                if (!uploadSupported) add("CANONICAL_EXPENSE_RECEIPT_UPLOAD_NOT_ENABLED")
                if (!ready) add("NO_VERIFIED_READY_RECEIPT_EVIDENCE")
                add("EXPENSE_REVIEW_AND_APPROVAL_POLICY_NOT_BOUND")
                add("EXPENSE_REIMBURSEMENT_PAYMENT_NOT_AUTHORIZED")
            },
        )
    }

    private fun creatorForOwn(id: UUID, org: UUID, actor: UUID): UUID {
        // Prevent an unrelated financial draft ID from being associated with
        // an authenticated employee's evidence. The draft owner was already
        // checked by employeeDrafts.expenses().
        return jdbc.query(
            """
            SELECT created_by_user_id FROM finance_expense_claim_draft
            WHERE id=? AND organization_id=? AND created_by_user_id=?
            """.trimIndent(),
            { rs, _ -> rs.getObject("created_by_user_id", UUID::class.java) },
            id, org, actor,
        ).singleOrNull() ?: denied()
    }

    private fun creatorForReview(id: UUID, org: UUID): UUID =
        jdbc.query(
            """
            SELECT created_by_user_id FROM finance_expense_claim_draft
            WHERE id=? AND organization_id=?
            """.trimIndent(),
            { rs, _ -> rs.getObject("created_by_user_id", UUID::class.java) },
            id, org,
        ).singleOrNull() ?: denied()

    private fun configured() {
        if (!environment.getProperty(
            "hiltech.finance.expense-receipts.readiness-enabled",
            Boolean::class.java, false,
        )) denied()
    }

    private fun denied(): Nothing = throw ProductApiException(
        "FINANCE_EXPENSE_RECEIPT_UNAVAILABLE",
        "Expense receipt review unavailable or access not authorized.",
        HttpStatus.NOT_FOUND,
    )
}

@RestController
class FinanceExpenseReceiptReadinessController(
    private val readiness: FinanceExpenseReceiptReadinessService,
) {
    @GetMapping("/v1/finance/me/organizations/{orgId}/expense-receipt-readiness")
    fun mine(req: HttpServletRequest, @PathVariable orgId: UUID) =
        readiness.mine(actor(req), orgId)

    @GetMapping("/v1/finance/organizations/{orgId}/expense-receipt-review")
    fun reviewerQueue(req: HttpServletRequest, @PathVariable orgId: UUID) =
        readiness.reviewerQueue(actor(req), orgId)

    private fun actor(req: HttpServletRequest): UUID =
        HiltechRequestContext.current(req).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: throw ProductApiException(
                "UNAUTHENTICATED", "Authentication required.", HttpStatus.UNAUTHORIZED,
            )
}
