package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorityBindingSnapshot
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.approval.ApprovalPolicyMode
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.audit.AuditEventRecord
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.procurement.PurchaseOrderIssuePersistence
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

data class FinanceApJournalLineCommand(
    val glAccountId: UUID,
    val side: FinanceJournalSide,
    val amount: String,
)

data class FinanceApProposeJournalRequest(
    val operationId: UUID,
    val fiscalPeriodId: UUID,
    val accountingTreatmentReference: String,
    val accountingTaxDisposition: String,
    val taxEvidenceId: UUID? = null,
    val lines: List<FinanceApJournalLineCommand>,
)

data class FinanceApRequestPostingApproval(val operationId: UUID)
data class FinanceApCommitJournalRequest(val operationId: UUID)

data class FinanceApPostingReceipt(
    val proposalId: UUID,
    val payableCandidateId: UUID,
    val sourceVersion: Long,
    val ledgerFingerprintSha256: String,
    val invoiceAmount: String,
    val currency: String,
    val state: String,
    val postingReviewRequestId: UUID? = null,
    val postedJournalId: UUID? = null,
    val replayed: Boolean = false,
    val recognizedAccountsPayable: Boolean = false,
    val paymentApproved: Boolean = false,
    val moneyTransferred: Boolean = false,
    val bankReconciled: Boolean = false,
)

/**
 * Actual double-entry recognition needs two approvals:
 *   P7/P8I source-document review and independently approved exact chart
 *   allocations. Neither approval can be forged from a client payload.
 *
 * All writes are disabled by default and use unnumbered schema, so DevOps
 * must first install approved Chart of Accounts, Fiscal Period and Policy.
 */
@Service
class FinanceApJournalPostingService(
    private val jdbc: JdbcTemplate,
    private val purchases: PurchaseOrderIssuePersistence,
    private val accountantReview: FinanceSupplierAccountingApprovalSubject,
    private val postingSubject: FinanceApJournalPostingApprovalSubject,
    private val approvals: ApprovalPersistencePort,
    private val engine: ApprovalEngineService,
    private val idempotency: IdempotentCommandExecutor,
    private val audit: AuditEventWriter,
    private val clock: Clock,
    private val environment: Environment,
) {
    @Transactional
    fun propose(
        actor: UUID, org: UUID, candidate: UUID,
        req: FinanceApProposeJournalRequest, correlation: String,
    ): FinanceApPostingReceipt {
        requireAuthority(actor, org, "hiltech.finance.ap-posting.preparer-authority-key")
        if (req.lines.size !in 2..32) unavailable()
        val source = accountantReview.current(org, candidate) ?: stale()
        val originalReview = approvals.loadSubjectApproval(
            ApprovalSubjectRef(
                org, FINANCE_SUPPLIER_ACCOUNTING_REVIEW_SUBJECT,
                candidate, source.sourceVersion,
            ), source.policyKey,
        )?.takeIf {
            it.state == ApprovalRequestState.APPROVED &&
                it.subjectVersion == source.sourceVersion
        } ?: unavailable()

        val policyKey = postingSubject.policyKey() ?: unavailable()
        val now = clock.instant()
        val policy = approvals.loadActivePolicy(org, policyKey, now)
            ?.takeIf {
                it.organizationId == org && it.policyKey == policyKey &&
                    it.approvalRequired && it.mode == ApprovalPolicyMode.SINGLE &&
                    !it.authorityKey.isNullOrBlank()
            } ?: unavailable()
        val reviewer = approvals.resolveCurrentAuthority(
            org, policy.authorityKey!!, now,
        ) ?: unavailable()
        if (reviewer.organizationId != org ||
            reviewer.authorityKey != policy.authorityKey ||
            approvals.actorMatchesAuthority(actor, reviewer, now)) unavailable()

        val period = jdbc.query(
            """
            SELECT state,date_start,date_end FROM finance_gl_period
            WHERE id=? AND organization_id=?
            """.trimIndent(),
            { rs, _ ->
                Triple(
                    rs.getString("state"),
                    rs.getDate("date_start").toLocalDate(),
                    rs.getDate("date_end").toLocalDate(),
                )
            }, req.fiscalPeriodId, org,
        ).singleOrNull() ?: unavailable()
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        if (period.first != "OPEN" ||
            today.isBefore(period.second) || today.isAfter(period.third)) unavailable()

        val lines = req.lines.map { cmd ->
            val chart = jdbc.query(
                """
                SELECT account_kind FROM finance_gl_account
                WHERE id=? AND organization_id=? AND state='APPROVED'
                  AND (currency IS NULL OR currency=?)
                """.trimIndent(),
                { rs, _ -> FinanceApAccountKind.valueOf(rs.getString("account_kind")) },
                cmd.glAccountId, org, source.currency,
            ).singleOrNull() ?: unavailable()
            FinanceApLedgerLine(
                cmd.glAccountId, chart, cmd.side,
                runCatching { BigDecimal(cmd.amount) }.getOrNull() ?: unavailable(),
            )
        }
        val plan = FinanceApLedgerPlan(
            organizationId = org,
            candidateId = candidate,
            sourceSha256 = source.sourceSha256,
            sourceVersion = source.sourceVersion,
            fiscalPeriodId = req.fiscalPeriodId,
            grossAmount = source.amount,
            currency = source.currency,
            accountingTreatmentReference = req.accountingTreatmentReference,
            accountingTaxDisposition = req.accountingTaxDisposition,
            taxEvidenceId = req.taxEvidenceId,
            lines = lines,
        )
        val checked = runCatching { FinanceApLedgerRules.validate(plan) }
            .getOrNull() ?: unavailable()
        // Do not recognize VAT recovery until actual tax treatment Evidence
        // target and company-specific reclaim rules are integrated by Docs.
        if (plan.taxEvidenceId != null) unavailable()
        val subjectVersion = FinanceApPostingReviewVersion.derive(
            checked, policy.versionNumber, reviewer.id, reviewer.version,
            originalReview.requestId,
        )
        val id = UUID.nameUUIDFromBytes(
            "hiltech:finance-ap-proposal:${req.operationId}".toByteArray(Charsets.UTF_8),
        )
        val fingerprint = IdempotencyKeyContract.fingerprint(
            listOf(org,candidate,checked.fingerprintSha256,subjectVersion,policyKey)
                .joinToString("|"),
        )
        val outcome = idempotency.execute(
            IdempotentCommandSpec(
                req.operationId,actor,"FINANCE_AP_JOURNAL_PROPOSAL_SEALED",
                "FINANCE_AP_JOURNAL_PROPOSAL",id,fingerprint,correlation,
            ),
        ) {
            // Same canonical P7 purchase-order lock as invoice intake and
            // receiving/matching; holding it through this transaction prevents
            // racing warehouse changes between currentness and accounting.
            lockInvoicePurchaseOrder(org, candidate)
            val again = accountantReview.current(org, candidate) ?: stale()
            if (again != source) stale()
            val at = clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_ap_posting_proposal(
                  id,operation_id,organization_id,payable_candidate_id,
                  supplier_invoice_id,supplier_organization_id,
                  original_accounting_approval_id,invoice_source_sha256,
                  invoice_review_source_version,ledger_fingerprint_sha256,
                  ledger_subject_version,fiscal_period_id,currency,gross_amount,
                  accounting_treatment_reference,accounting_tax_disposition,
                  tax_evidence_id,posting_policy_key,state,created_by_user_id,
                  created_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?)
                """.trimIndent(),
                id,req.operationId,org,candidate,source.supplierInvoiceId,
                source.supplierOrganizationId,originalReview.requestId,
                source.sourceSha256,source.sourceVersion,
                checked.fingerprintSha256,subjectVersion,req.fiscalPeriodId,
                source.currency,source.amount,plan.accountingTreatmentReference.trim(),
                plan.accountingTaxDisposition,plan.taxEvidenceId,policyKey,
                actor,at,correlation,
            ) == 1)
            lines.forEachIndexed { index, line ->
                check(jdbc.update(
                    """
                    INSERT INTO finance_ap_posting_proposal_line(
                        id,organization_id,proposal_id,line_number,
                        gl_account_id,side,amount
                    ) VALUES(?,?,?,?,?,?,?)
                    """.trimIndent(),
                    UUID.nameUUIDFromBytes(
                        "hiltech:finance-ap-journal-line:$id:${index + 1}"
                            .toByteArray(Charsets.UTF_8),
                    ),org,id,index + 1,line.accountId,line.side.name,line.amount,
                ) == 1)
            }
            check(jdbc.update(
                """
                UPDATE finance_ap_posting_proposal
                SET state='SEALED',sealed_at=?
                WHERE id=? AND organization_id=? AND state='DRAFT'
                """.trimIndent(),at,id,org,
            ) == 1)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AP_JOURNAL_PROPOSAL_SEALED",
                targetType="FINANCE_AP_JOURNAL_PROPOSAL",targetId=id,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="BALANCED_CHART_ALLOCATION_REQUIRES_INDEPENDENT_POSTING_APPROVAL",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AP_JOURNAL_PROPOSAL_SEALED", """{"id":"$id"}""",
            )
        }
        return receipt(id, source.candidateId, subjectVersion,
            checked.fingerprintSha256, source.amount, source.currency,
            "SEALED_AWAITING_POSTING_REVIEW", replayed=outcome.replayed)
    }

    fun requestApproval(
        actor: UUID, org: UUID, proposalId: UUID, operation: UUID,
        correlation: String,
    ): FinanceApPostingReceipt {
        requireAuthority(actor, org, "hiltech.finance.ap-posting.preparer-authority-key")
        val snapshot = postingSubject.current(org,proposalId) ?: stale()
        if (snapshot.requesterUserId != actor) unavailable()
        val ref = ApprovalSubjectRef(
            org,FINANCE_AP_JOURNAL_POSTING_SUBJECT,proposalId,
            snapshot.exactLedgerSourceVersion,
        )
        val current = engine.subjectStatus(ref,snapshot.postingPolicyKey)
        if (current != null) {
            if (current.state != ApprovalRequestState.PENDING) unavailable()
            return receipt(snapshot,"PENDING",
                reviewId=current.approvalRequestId,replayed=true)
        }
        val result=engine.requestException(
            actorUserId=actor,operationId=operation,subject=ref,
            policyKey=snapshot.postingPolicyKey,
            reasonCode="FINANCE_AP_JOURNAL_POSTING_REVIEW",
            safeReasonSummary=null,correlationId=correlation,
            allowAuthorityBypass=false,
        )
        if (!result.approvalRequired || result.approvalRequestId==null ||
            result.state!=ApprovalRequestState.PENDING ||
            result.policyKey!=snapshot.postingPolicyKey ||
            result.policyVersion!=snapshot.postingPolicyVersion) unavailable()
        return receipt(snapshot,"PENDING",
            reviewId=result.approvalRequestId,replayed=result.replayed)
    }

    @Transactional
    fun post(
        actor: UUID, org: UUID, proposalId: UUID, operation: UUID,
        correlation: String,
    ): FinanceApPostingReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-posting.operator-authority-key")
        // A timed-out successful POST may be replayed after receiving/period
        // changes. Return its immutable posted receipt, never submit twice.
        readPosted(org,proposalId)?.let { historic ->
            val poster=jdbc.queryForObject(
                "SELECT posted_by_user_id FROM finance_ap_posted_journal WHERE id=?",
                UUID::class.java,historic.postedJournalId,
            )
            val originalOperation=jdbc.queryForObject(
                "SELECT operation_id FROM finance_ap_posted_journal WHERE id=?",
                UUID::class.java,historic.postedJournalId,
            )
            if (poster!=actor || originalOperation!=operation) unavailable()
            return historic.copy(replayed=true)
        }
        val snapshot=postingSubject.current(org,proposalId) ?: stale()
        if (snapshot.requesterUserId==actor) unavailable()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_JOURNAL_POSTING_SUBJECT,
            proposalId,snapshot.exactLedgerSourceVersion,
        )
        val approval=engine.subjectStatus(ref,snapshot.postingPolicyKey)
            ?.takeIf {
                it.state==ApprovalRequestState.APPROVED &&
                    it.subjectVersion==snapshot.exactLedgerSourceVersion
            } ?: unavailable()
        val journalId=UUID.nameUUIDFromBytes(
            "hiltech:finance-ap-posted-journal:$operation".toByteArray(Charsets.UTF_8),
        )
        val fingerprint=IdempotencyKeyContract.fingerprint(
            listOf(org,proposalId,approval.approvalRequestId,
                snapshot.ledgerFingerprintSha256,snapshot.exactLedgerSourceVersion)
                .joinToString("|"),
        )
        val result=idempotency.execute(IdempotentCommandSpec(
            operation,actor,"FINANCE_AP_JOURNAL_POSTED",
            "FINANCE_AP_POSTED_JOURNAL",journalId,fingerprint,correlation,
        )) {
            // Acquire the canonical P7 PO lock BEFORE the proposal row lock.
            // Holding this across source revalidation and journal INSERT
            // makes warehousing/receiving vs posting serializable.
            lockInvoicePurchaseOrder(org,snapshot.candidateId)
            val locked=jdbc.query(
                """
                SELECT id FROM finance_ap_posting_proposal
                WHERE id=? AND organization_id=? AND state='SEALED' FOR UPDATE
                """.trimIndent(),
                { rs, _ -> rs.getObject("id",UUID::class.java) },
                proposalId,org,
            ).singleOrNull() ?: stale()
            if (locked!=proposalId ||
                postingSubject.current(org,proposalId)!=snapshot) stale()
            val at=clock.instant().atOffset(ZoneOffset.UTC)
            check(jdbc.update(
                """
                INSERT INTO finance_ap_posted_journal(
                  id,operation_id,organization_id,proposal_id,
                  payable_candidate_id,journal_posting_approval_id,
                  fiscal_period_id,invoice_source_sha256,
                  ledger_fingerprint_sha256,currency,gross_amount,
                  posted_by_user_id,posted_at,correlation_id
                ) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """.trimIndent(),
                journalId,operation,org,proposalId,snapshot.candidateId,
                approval.approvalRequestId,snapshot.periodId,
                // Source hash from actual immutable proposal, NOT from
                // client input or an inferred bank settlement.
                jdbc.queryForObject(
                    "SELECT invoice_source_sha256 FROM finance_ap_posting_proposal WHERE id=?",
                    String::class.java,proposalId,
                ) ?: stale(),
                snapshot.ledgerFingerprintSha256,snapshot.currency,
                snapshot.grossAmount,actor,at,correlation,
            )==1)
            val inserted=jdbc.update(
                """
                INSERT INTO finance_ap_posted_journal_line(
                  id,organization_id,journal_id,proposal_line_id,
                  line_number,gl_account_id,side,amount
                )
                SELECT gen_random_uuid(),l.organization_id,?,l.id,
                       l.line_number,l.gl_account_id,l.side,l.amount
                FROM finance_ap_posting_proposal_line l
                WHERE l.proposal_id=? AND l.organization_id=?
                ORDER BY l.line_number
                """.trimIndent(),journalId,proposalId,org,
            )
            check(inserted==snapshot.lines.size)
            audit.append(AuditEventRecord(
                actorUserId=actor,action="FINANCE_AP_JOURNAL_POSTED",
                targetType="FINANCE_AP_POSTED_JOURNAL",targetId=journalId,
                occurredAt=clock.instant(),correlationId=correlation,
                reason="APPROVED_BALANCED_AP_RECOGNITION_NOT_PAYMENT",
            ))
            IdempotentCommandOutcome(
                "FINANCE_AP_JOURNAL_POSTED","""{"id":"$journalId"}""",
            )
        }
        return receipt(snapshot,"POSTED_ACCOUNTS_PAYABLE",
            reviewId=approval.approvalRequestId,journalId=journalId,
            replayed=result.replayed)
    }

    fun status(actor: UUID,org: UUID,proposalId: UUID): FinanceApPostingReceipt {
        requireAuthority(actor,org,"hiltech.finance.ap-posting.preparer-authority-key")
        // Historical posted AP remains recognized even when the underlying
        // supplier source later changes. That is not payment or a new booking.
        readPosted(org,proposalId)?.let { return it }
        val snapshot=postingSubject.current(org,proposalId) ?: stale()
        val ref=ApprovalSubjectRef(
            org,FINANCE_AP_JOURNAL_POSTING_SUBJECT,
            proposalId,snapshot.exactLedgerSourceVersion,
        )
        val review=engine.subjectStatus(ref,snapshot.postingPolicyKey)
        val posted=jdbc.query(
            """
            SELECT id FROM finance_ap_posted_journal
            WHERE organization_id=? AND proposal_id=?
            """.trimIndent(),
            { rs,_ ->rs.getObject("id",UUID::class.java) },org,proposalId,
        ).singleOrNull()
        return receipt(
            snapshot,
            if(posted!=null) "POSTED_ACCOUNTS_PAYABLE"
            else review?.state?.name ?: "SEALED_AWAITING_POSTING_REVIEW",
            reviewId=review?.approvalRequestId,journalId=posted,
        )
    }

    private fun lockInvoicePurchaseOrder(org: UUID, candidate: UUID) {
        val po=jdbc.query(
            """
            SELECT purchase_order_id FROM finance_payable_candidate
            WHERE organization_id=? AND id=?
            """.trimIndent(),
            { rs,_ -> rs.getObject("purchase_order_id",UUID::class.java) },
            org,candidate,
        ).singleOrNull() ?: stale()
        if(!purchases.lockOrder(po,org)) stale()
    }

    private fun readPosted(
        org: UUID,proposalId: UUID,
    ): FinanceApPostingReceipt? = jdbc.query(
        """
        SELECT j.id AS journal_id,j.payable_candidate_id,
           j.journal_posting_approval_id,
           j.ledger_fingerprint_sha256,j.currency,j.gross_amount,
           p.ledger_subject_version
        FROM finance_ap_posted_journal j
        JOIN finance_ap_posting_proposal p
          ON p.id=j.proposal_id AND p.organization_id=j.organization_id
        WHERE j.organization_id=? AND j.proposal_id=?
        """.trimIndent(),
        { rs, _ ->
            receipt(
                proposalId=proposalId,
                candidateId=rs.getObject("payable_candidate_id",UUID::class.java),
                sourceVersion=rs.getLong("ledger_subject_version"),
                sha=rs.getString("ledger_fingerprint_sha256"),
                amount=rs.getBigDecimal("gross_amount"),
                currency=rs.getString("currency"),
                state="POSTED_ACCOUNTS_PAYABLE",
                reviewId=rs.getObject("journal_posting_approval_id",UUID::class.java),
                journalId=rs.getObject("journal_id",UUID::class.java),
            )
        },org,proposalId,
    ).singleOrNull()

    private fun requireAuthority(
        actor: UUID,org: UUID,setting: String,
    ): ApprovalAuthorityBindingSnapshot {
        if (!postingSubject.enabled()) unavailable()
        val key=environment.getProperty(setting,"").trim()
        if (!Regex("^[A-Z][A-Z0-9_]{2,79}$").matches(key)) unavailable()
        val now=clock.instant()
        if(!approvals.isActiveOrganizationMember(org,actor,now)) unavailable()
        val binding=approvals.resolveCurrentAuthority(org,key,now)
            ?: unavailable()
        if(binding.organizationId!=org || binding.authorityKey!=key ||
           !approvals.actorMatchesAuthority(actor,binding,now)) unavailable()
        return binding
    }

    private fun receipt(
        snapshot: FinanceApPostingVerifiedSnapshot,state: String,
        reviewId: UUID?=null,journalId: UUID?=null,replayed:Boolean=false,
    ) = receipt(snapshot.proposalId,snapshot.candidateId,
        snapshot.exactLedgerSourceVersion,snapshot.ledgerFingerprintSha256,
        snapshot.grossAmount,snapshot.currency,state,
        reviewId,journalId,replayed,
    )
    private fun receipt(
        proposalId: UUID,candidateId:UUID,sourceVersion:Long,
        sha:String,amount:BigDecimal,currency:String,state:String,
        reviewId:UUID?=null,journalId:UUID?=null,replayed:Boolean=false,
    )=FinanceApPostingReceipt(
        proposalId=proposalId,payableCandidateId=candidateId,
        sourceVersion=sourceVersion,ledgerFingerprintSha256=sha,
        invoiceAmount=amount.toPlainString(),currency=currency,state=state,
        postingReviewRequestId=reviewId,postedJournalId=journalId,
        replayed=replayed,recognizedAccountsPayable=journalId!=null,
    )

    private fun unavailable():Nothing = throw ProductApiException(
        "FINANCE_AP_POSTING_NOT_AUTHORIZED",
        "Company accounting posting policy/authority unavailable.",
        HttpStatus.NOT_FOUND,
    )
    private fun stale():Nothing = throw ProductApiException(
        "FINANCE_AP_POSTING_SOURCE_STALE",
        "Supplier invoice, fiscal period or exact ledger review changed.",
        HttpStatus.CONFLICT,
    )
}

@RestController
class FinanceApJournalPostingController(
    private val finance:FinanceApJournalPostingService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/payable-candidates/{candidateId}/journal-proposals")
    fun propose(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable candidateId:UUID,
        @RequestBody body:FinanceApProposeJournalRequest
    ):FinanceApPostingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.propose(actor(http),orgId,candidateId,body,ctx.correlationId)
    }

    @PostMapping("/v1/finance/organizations/{orgId}/ap-journal-proposals/{id}/request-posting-approval")
    fun request(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceApRequestPostingApproval
    ):FinanceApPostingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.requestApproval(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }

    @PostMapping("/v1/finance/organizations/{orgId}/ap-journal-proposals/{id}/post")
    fun post(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID,@RequestBody body:FinanceApCommitJournalRequest
    ):FinanceApPostingReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        return finance.post(actor(http),orgId,id,body.operationId,ctx.correlationId)
    }

    @GetMapping("/v1/finance/organizations/{orgId}/ap-journal-proposals/{id}")
    fun status(http:HttpServletRequest,@PathVariable orgId:UUID,
        @PathVariable id:UUID):FinanceApPostingReceipt =
        finance.status(actor(http),orgId,id)

    private fun actor(http:HttpServletRequest):UUID =
        HiltechRequestContext.current(http).identityId
            ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?:throw ProductApiException("UNAUTHENTICATED",
                "Authenticated Finance identity required.",HttpStatus.UNAUTHORIZED)
}
