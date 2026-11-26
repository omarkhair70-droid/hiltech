package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalPolicySnapshot
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.operationalaccess.OperationalAuthorityPort
import com.hiltech.server.operationalaccess.currentOperationalAuthority
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.util.UUID

data class FinanceCashboxOpeningPrepare(
    val operationId: UUID, val cashboxCode: String? = null, val currency: String,
    val custodianUserId: UUID, val companyCustodyReference: String,
    val expectedCustodianVersion: Long? = null, val expectedReviewVersion: Long? = null,
)
data class FinanceCashboxOperation(val operationId: UUID)
data class FinanceCashboxReceipt(
    val proposalId: UUID, val organizationId: UUID, val cashboxCode: String, val currency: String,
    val custodianUserId: UUID, val state: String, val approvalRequestId: UUID? = null,
    val replayed: Boolean = false, val cashboxId: UUID? = null, val openingBalance: String = "0.000000",
    val custodianDisplayName: String = "", val custodianEmployeeCode: String = "",
    val companyCustodyReference: String = "", val createdAt: String? = null, val sourceVersion: Long? = null,
    val codeSource: String = "MANUAL", val canRequestReview: Boolean = false, val canActivate: Boolean = false,
    val sourceIssueCode: String? = null, val confirmedOperationId: UUID? = null,
)
data class FinanceCashboxBalanceReceipt(
    val cashboxId: UUID, val organizationId: UUID, val code: String, val currency: String,
    val custodianUserId: UUID, val cashOnHand: String, val voucherCount: Long,
    val physicalCountReconciled: Boolean = false,
)
data class FinanceCashboxOpeningWorkbench(
    val organizationId: UUID, val canPrepare: Boolean, val canReadCompanyHistory: Boolean,
    val reviewReady: Boolean, val reviewSourceVersion: Long?, val preparationIssueCode: String?,
    val items: List<FinanceCashboxReceipt>, val hasMore: Boolean,
)
data class FinanceCashboxCustodianDirectory(
    val organizationId: UUID, val items: List<FinanceCashboxCustodianChoice>, val hasMore: Boolean,
    val reviewSourceVersion: Long?, val issueCode: String?,
)

/** Zero opening, named current custody and an independently reviewed immutable proposal. */
@Service
class FinanceCashboxOpeningService @Autowired constructor(
    private val persistence: FinanceCashboxOpeningPersistencePort,
    private val subject: FinanceCashboxOpeningApprovalSubject,
    private val approvals: ApprovalPersistencePort,
    private val engine: ApprovalEngineService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val environment: Environment,
    private val clock: Clock,
    private val operationalAuthority: OperationalAuthorityPort? = null,
) {
    fun workbench(actor: UUID, org: UUID, query: String = ""): FinanceCashboxOpeningWorkbench {
        validateQuery(query)
        val prepare = hasAuthority(actor, org, PREPARER)
        val reader = hasAuthority(actor, org, READER)
        if (!prepare && !reader) denied()
        val review = currentReview(org)
        val issue = if (!prepare) null else preparationIssue(actor, review)
        val rows = persistence.history(org, if (reader) null else actor, query.trim(), 101)
        return FinanceCashboxOpeningWorkbench(org, prepare, reader, review != null && issue == null,
            review?.version, issue, rows.take(100).map { receipt(it, actor, prepare) }, rows.size > 100)
    }

    fun custodians(actor: UUID, org: UUID, query: String = ""): FinanceCashboxCustodianDirectory {
        requireAuthority(actor, org, PREPARER)
        validateQuery(query)
        val review = currentReview(org)
        val issue = preparationIssue(actor, review)
        if (review == null || issue != null) return FinanceCashboxCustodianDirectory(org, emptyList(), false, review?.version, issue)
        val now = clock.instant()
        val rows = persistence.custodians(org, query.trim(), now, 101)
        val eligible = rows.filter { !approvals.actorMatchesAuthority(it.identityId, review.binding, now) }
        return FinanceCashboxCustodianDirectory(org, eligible.take(100), rows.size > 100, review.version, null)
    }

    @Transactional
    fun prepare(actor: UUID, org: UUID, request: FinanceCashboxOpeningPrepare, correlation: String): FinanceCashboxReceipt {
        requireAuthority(actor, org, PREPARER)
        val code = request.cashboxCode?.trim()
        val currency = request.currency.trim()
        val reference = request.companyCustodyReference.trim()
        val automatic = code == null
        if ((code != null && code.length !in 3..64) || !currency.matches(Regex("^[A-Z]{3}$")) || reference.length !in 8..180 ||
            reference.any { it.code < 32 || it.code == 127 } ||
            (automatic && (request.expectedCustodianVersion == null || request.expectedCustodianVersion <= 0 ||
                request.expectedReviewVersion == null || request.expectedReviewVersion <= 0))) {
            problem("FINANCE_CASHBOX_INVALID", "Choose a current custodian, currency and company custody reference.", HttpStatus.BAD_REQUEST)
        }
        val existing = persistence.operation(request.operationId)
        if (existing != null && (existing.organizationId != org || existing.preparedBy != actor)) denied()
        val id = UUID.nameUUIDFromBytes("hiltech:cashbox-opening:${request.operationId}".toByteArray(Charsets.UTF_8))
        val before = if (existing == null) requireReview(actor, org, request.custodianUserId) else null
        // The historic manual format is unchanged. Its stored source version,
        // rather than a newly rotated reviewer, reproduces the original intent.
        val fingerprint = IdempotencyKeyContract.fingerprint(if (automatic) {
            listOf("FINANCE_AUTOMATIC_CASHBOX_V1", org, currency, request.custodianUserId, reference,
                request.expectedCustodianVersion, request.expectedReviewVersion).joinToString("|")
        } else {
            val version = existing?.subjectVersion ?: FinanceCashboxOpeningVersion.derive(org, requireNotNull(code), currency,
                request.custodianUserId, reference, requireNotNull(before).policy.versionNumber, before.binding.id, before.binding.version)
            listOf(org, code, currency, request.custodianUserId, reference, version).joinToString("|")
        })
        val outcome = idempotency.execute(IdempotentCommandSpec(request.operationId, actor, "FINANCE_CASHBOX_OPENING_PROPOSED",
            "FINANCE_CASHBOX_OPENING_PROPOSAL", id, fingerprint, correlation)) {
            persistence.lockOrganization(org)
            requireAuthority(actor, org, PREPARER)
            if (existing != null) problem("FINANCE_CASHBOX_OPERATION_CONFLICT", "The recorded request cannot be dispatched again.")
            val review = requireReview(actor, org, request.custodianUserId)
            if (before != review || (automatic && request.expectedReviewVersion != review.version))
                problem("FINANCE_CASHBOX_REVIEW_CHANGED", "The reviewer configuration changed. Refresh and review the request.")
            val custodian = persistence.custodian(org, request.custodianUserId, clock.instant(), lock = true)
                ?: problem("FINANCE_CASHBOX_CUSTODIAN_CHANGED", "Choose a current employee with an active company account.")
            if (automatic && request.expectedCustodianVersion != custodian.sourceVersion)
                problem("FINANCE_CASHBOX_CUSTODIAN_CHANGED", "The selected employee account changed. Refresh the choice.")
            val number = if (automatic) persistence.nextNumber(org) else null
            val recordedCode = code ?: "CBX-${requireNotNull(number).toString().padStart(6, '0')}"
            val version = FinanceCashboxOpeningVersion.derive(org, recordedCode, currency, custodian.identityId, reference,
                review.policy.versionNumber, review.binding.id, review.binding.version)
            persistence.append(FinanceCashboxOpeningStored(id, request.operationId, org, recordedCode, currency, custodian.identityId,
                reference, version, review.policy.policyKey, actor, clock.instant(), if (automatic) "AUTOMATIC" else "MANUAL", number,
                custodian.sourceVersion, review.version, custodian.displayName, custodian.employeeCode))
            audit.append(AuditEventRecord(actorUserId = actor, action = "FINANCE_CASHBOX_OPENING_PROPOSED",
                targetType = "FINANCE_CASHBOX_OPENING_PROPOSAL", targetId = id, occurredAt = clock.instant(), correlationId = correlation,
                reason = "ZERO_INITIAL_CASH_INDEPENDENT_APPROVAL_REQUIRED"))
            IdempotentCommandOutcome("FINANCE_CASHBOX_OPENING_PROPOSED", """{"id":"$id"}""")
        }
        val saved = persistence.proposal(org, id) ?: stale()
        if (saved.operationId != request.operationId || saved.preparedBy != actor ||
            saved.currency != currency || saved.custodian != request.custodianUserId || saved.companyReference != reference ||
            (automatic && (saved.codeSource != "AUTOMATIC" || saved.custodianSourceVersion != request.expectedCustodianVersion ||
                saved.reviewSourceVersion != request.expectedReviewVersion)) || (!automatic && (saved.codeSource != "MANUAL" || saved.code != code))) {
            problem("FINANCE_CASHBOX_OPERATION_CONFLICT", "The saved request differs from this command.")
        }
        return receipt(saved, actor, true).copy(replayed = outcome.replayed, confirmedOperationId = request.operationId)
    }

    @Transactional
    fun requestReview(actor: UUID, org: UUID, id: UUID, operation: UUID, correlation: String): FinanceCashboxReceipt {
        requireAuthority(actor, org, PREPARER)
        persistence.lockOrganization(org)
        requireAuthority(actor, org, PREPARER)
        val saved = persistence.proposal(org, id) ?: stale()
        if (actor != saved.preparedBy) denied()
        val current = subject.current(org, id) ?: stale()
        val ref = ApprovalSubjectRef(org, FINANCE_CASHBOX_OPENING_SUBJECT, id, current.version)
        val old = engine.subjectStatus(ref, current.policyKey)
        if (old != null) {
            if (old.state != ApprovalRequestState.PENDING) stale()
            return receipt(saved, actor, true).copy(state = "PENDING", approvalRequestId = old.approvalRequestId,
                replayed = true, canRequestReview = false, confirmedOperationId = operation)
        }
        val accepted = engine.requestException(actorUserId = actor, operationId = operation, subject = ref,
            policyKey = current.policyKey, reasonCode = "FINANCE_ZERO_CASHBOX_OPENING", safeReasonSummary = null,
            correlationId = correlation, allowAuthorityBypass = false)
        if (!accepted.approvalRequired || accepted.approvalRequestId == null || accepted.state != ApprovalRequestState.PENDING ||
            accepted.policyVersion != current.policyVersion) stale()
        return receipt(saved, actor, true).copy(state = "PENDING", approvalRequestId = accepted.approvalRequestId,
            replayed = accepted.replayed, canRequestReview = false, confirmedOperationId = operation)
    }

    @Transactional
    fun activate(actor: UUID, org: UUID, id: UUID, operation: UUID, correlation: String): FinanceCashboxReceipt {
        requireAuthority(actor, org, PREPARER)
        val saved = persistence.proposal(org, id) ?: stale()
        if (saved.preparedBy != actor) denied()
        persistence.activation(org, id)?.let { activated ->
            if (activated.operationId != operation || activated.actor != actor)
                problem("FINANCE_CASHBOX_ALREADY_ACTIVE", "This cashbox was activated by another recorded request.")
            return receipt(saved, actor, true).copy(replayed = true, confirmedOperationId = operation)
        }
        val current = subject.current(org, id) ?: stale()
        val approved = engine.subjectStatus(ApprovalSubjectRef(org, FINANCE_CASHBOX_OPENING_SUBJECT, id, current.version), current.policyKey)
            ?.takeIf { it.state == ApprovalRequestState.APPROVED && it.subjectVersion == current.version } ?: stale()
        val fingerprint = IdempotencyKeyContract.fingerprint(listOf(org, id, current.version, approved.approvalRequestId).joinToString("|"))
        val outcome = idempotency.execute(IdempotentCommandSpec(operation, actor, "FINANCE_CASHBOX_OPENED",
            "FINANCE_CASHBOX_REGISTER", id, fingerprint, correlation)) {
            persistence.lockOrganization(org)
            requireAuthority(actor, org, PREPARER)
            if (subject.current(org, id) != current) stale()
            if (saved.codeSource == "AUTOMATIC" && persistence.custodian(org, saved.custodian, clock.instant(), lock = true) == null)
                problem("FINANCE_CASHBOX_CUSTODIAN_CHANGED", "The selected custodian is no longer current.")
            persistence.activate(saved, operation, actor, approved.approvalRequestId, clock.instant())
            audit.append(AuditEventRecord(actorUserId = actor, action = "FINANCE_CASHBOX_OPENED", targetType = "FINANCE_CASHBOX_REGISTER",
                targetId = id, occurredAt = clock.instant(), correlationId = correlation, reason = "COMPANY_AUTHORIZED_ZERO_BALANCE_PHYSICAL_CUSTODY"))
            IdempotentCommandOutcome("FINANCE_CASHBOX_OPENED", """{"id":"$id"}""")
        }
        return receipt(saved, actor, true).copy(replayed = outcome.replayed, confirmedOperationId = operation)
    }

    /** Existing callers retain their own queue; stale proposals now remain visible. */
    fun openingQueue(actor: UUID, org: UUID): List<FinanceCashboxReceipt> {
        requireAuthority(actor, org, PREPARER)
        return persistence.history(org, actor, "", 100).filter { persistence.activation(org, it.id) == null }.map { receipt(it, actor, true) }
    }
    fun status(actor: UUID, org: UUID, id: UUID): FinanceCashboxReceipt {
        val reader = hasAuthority(actor, org, READER)
        val prepare = hasAuthority(actor, org, PREPARER)
        if (!reader && !prepare) denied()
        val stored = persistence.proposal(org, id) ?: stale()
        if (!reader && stored.preparedBy != actor) denied()
        return receipt(stored, actor, prepare)
    }
    fun balance(actor: UUID, org: UUID, id: UUID): FinanceCashboxBalanceReceipt {
        requireAuthority(actor, org, READER)
        return persistence.balance(org, id) ?: stale()
    }

    private fun receipt(saved: FinanceCashboxOpeningStored, actor: UUID, canPrepare: Boolean): FinanceCashboxReceipt {
        val active = persistence.activation(saved.organizationId, saved.id)
        val current = if (active == null) subject.current(saved.organizationId, saved.id) else null
        val reviewed = if (active == null) persistence.recordedReview(saved) else null
        val issue = if (active != null || current != null) null else "FINANCE_CASHBOX_SOURCE_CHANGED"
        val state = when {
            active != null -> "ACTIVE"
            reviewed?.state in setOf("REJECTED", "CHANGE_REQUESTED", "SUPERSEDED", "CANCELLED") -> requireNotNull(reviewed).state
            issue != null -> "REVIEW_REQUIRED"
            else -> reviewed?.state ?: "PROPOSED"
        }
        val owns = canPrepare && saved.preparedBy == actor && current != null
        return FinanceCashboxReceipt(saved.id, saved.organizationId, saved.code, saved.currency, saved.custodian, state,
            active?.approvalRequestId ?: reviewed?.requestId, cashboxId = if (active != null) saved.id else null,
            custodianDisplayName = saved.custodianDisplayName, custodianEmployeeCode = saved.custodianEmployeeCode,
            companyCustodyReference = saved.companyReference, createdAt = saved.createdAt.toString(), sourceVersion = saved.subjectVersion,
            codeSource = saved.codeSource, canRequestReview = owns && state == "PROPOSED", canActivate = owns && state == "APPROVED", sourceIssueCode = issue)
    }
    private data class Review(val policy: ApprovalPolicySnapshot, val binding: ApprovalAuthorityBindingSnapshot) {
        val version: Long get() = cashboxSourceVersion("CASHBOX_REVIEW_V1", policy.organizationId, policy.policyKey,
            policy.versionNumber, binding.id, binding.version)
    }
    private fun currentReview(org: UUID): Review? {
        val key = subject.policyKey() ?: return null
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, key, now)?.takeIf { it.organizationId == org && it.policyKey == key &&
            it.approvalRequired && it.mode == ApprovalPolicyMode.SINGLE && !it.authorityKey.isNullOrBlank() } ?: return null
        val binding = approvals.resolveCurrentAuthority(org, requireNotNull(policy.authorityKey), now)?.takeIf { it.organizationId == org }
            ?: return null
        if (binding.principalType == ApprovalPrincipalType.USER && !approvals.isActiveOrganizationMember(org, binding.principalId, now)) return null
        return Review(policy, binding)
    }
    private fun preparationIssue(actor: UUID, review: Review?): String? = when {
        review == null -> "FINANCE_CASHBOX_REVIEW_SETUP_REQUIRED"
        approvals.actorMatchesAuthority(actor, review.binding, clock.instant()) -> "FINANCE_CASHBOX_INDEPENDENCE_REQUIRED"
        else -> null
    }
    private fun requireReview(actor: UUID, org: UUID, custodian: UUID): Review {
        val review = currentReview(org) ?: problem("FINANCE_CASHBOX_REVIEW_SETUP_REQUIRED", "Set up a current independent cashbox reviewer in company administration.", HttpStatus.PRECONDITION_REQUIRED)
        if (preparationIssue(actor, review) != null || approvals.actorMatchesAuthority(custodian, review.binding, clock.instant()))
            problem("FINANCE_CASHBOX_INDEPENDENCE_REQUIRED", "The reviewer must be independent from both the preparer and the custodian.", HttpStatus.FORBIDDEN)
        if (!approvals.isActiveOrganizationMember(org, custodian, clock.instant()))
            problem("FINANCE_CASHBOX_CUSTODIAN_CHANGED", "The selected custodian no longer has current company access.")
        return review
    }
    private fun hasAuthority(actor: UUID, org: UUID, property: String): Boolean = subject.enabled() &&
        currentOperationalAuthority(operationalAuthority, approvals, actor, org, property, environment.getProperty(property), clock.instant()) != null
    private fun requireAuthority(actor: UUID, org: UUID, property: String) { if (!hasAuthority(actor, org, property)) denied() }
    private fun validateQuery(query: String) { if (query.length > 120) problem("FINANCE_CASHBOX_SEARCH_INVALID", "Use a shorter search.", HttpStatus.BAD_REQUEST) }
    private fun denied(): Nothing = problem("FINANCE_CASHBOX_NOT_AUTHORIZED", "Current company cash responsibility is required.", HttpStatus.FORBIDDEN)
    private fun stale(): Nothing = problem("FINANCE_CASHBOX_STALE", "Cashbox custody or its exact independent review changed. Refresh the current record.")
    private fun problem(code: String, message: String, status: HttpStatus = HttpStatus.CONFLICT): Nothing = throw ProductApiException(code, message, status)
    private companion object {
        const val PREPARER = "hiltech.finance.cashbox.preparer-authority-key"
        const val READER = "hiltech.finance.cashbox.reader-authority-key"
    }
}

@RestController
class FinanceCashboxOpeningController(private val cashboxes: FinanceCashboxOpeningService) {
    @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/opening-workbench")
    fun workbench(http: HttpServletRequest, @PathVariable orgId: UUID, @RequestParam(defaultValue = "") query: String) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cashboxes.workbench(actor(http), orgId, query))
    @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/custodian-choices")
    fun custodians(http: HttpServletRequest, @PathVariable orgId: UUID, @RequestParam(defaultValue = "") query: String) =
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(cashboxes.custodians(actor(http), orgId, query))
    @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/prepare-opening")
    fun prepare(http: HttpServletRequest, @PathVariable orgId: UUID, @RequestBody body: FinanceCashboxOpeningPrepare): FinanceCashboxReceipt {
        val context = HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, body.operationId)
        return cashboxes.prepare(actor(http), orgId, body, context.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/request-opening-review")
    fun request(http: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID, @RequestBody body: FinanceCashboxOperation): FinanceCashboxReceipt {
        val context = HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, body.operationId)
        return cashboxes.requestReview(actor(http), orgId, id, body.operationId, context.correlationId)
    }
    @PostMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/activate")
    fun activate(http: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID, @RequestBody body: FinanceCashboxOperation): FinanceCashboxReceipt {
        val context = HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(context.idempotencyKey, body.operationId)
        return cashboxes.activate(actor(http), orgId, id, body.operationId, context.correlationId)
    }
    @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/opening-queue")
    fun openingQueue(http: HttpServletRequest, @PathVariable orgId: UUID): List<FinanceCashboxReceipt> = cashboxes.openingQueue(actor(http), orgId)
    @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}")
    fun status(http: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID): FinanceCashboxReceipt = cashboxes.status(actor(http), orgId, id)
    @GetMapping("/v1/finance/organizations/{orgId}/cashboxes/{id}/balance")
    fun balance(http: HttpServletRequest, @PathVariable orgId: UUID, @PathVariable id: UUID): FinanceCashboxBalanceReceipt = cashboxes.balance(actor(http), orgId, id)
    private fun actor(http: HttpServletRequest): UUID = HiltechRequestContext.current(http).identityId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        ?: throw ProductApiException("UNAUTHENTICATED", "Company Finance identity required.", HttpStatus.UNAUTHORIZED)
}
