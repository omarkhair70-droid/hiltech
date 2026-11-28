package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalAuthorizationPort
import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalDecisionType
import com.hiltech.server.approval.ApprovalRequestState
import com.hiltech.server.approval.ApprovalSubjectRef
import com.hiltech.server.approval.ApprovalPrincipalType
import com.hiltech.server.approval.FailClosedApprovalSubjectVersionGuard
import com.hiltech.server.approval.JdbcApprovalPersistence
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.documents.EvidenceStorageAccessPort
import com.hiltech.server.documents.storage.EvidenceObjectStoragePort
import com.hiltech.server.payroll.PayrollFinanceOutcome
import com.hiltech.server.payroll.PayrollRunPublicationPort
import com.hiltech.server.platform.ProductApiException
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.security.AuthorizationProjectionIntentWriter
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.security.MessageDigest
import java.sql.SQLException
import java.time.Clock
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import java.util.Base64

/**
 * Real Payroll/Finance SQL and ApprovalEngine integration over synthetic canonical bank metadata.
 * The FGA decision boundary is represented by one exact independent assignment. No live bank,
 * S3 upload, provider transfer, or device acceptance is claimed by this database fixture.
 * Original canonical CSV bytes are delivered through the protected storage port and verified.
 */
object FinancePayrollResultPostgresScenario {
    data class SettledCorrectionFixture(val reviewer:UUID,val originalJournal:UUID,val wageAccount:UUID,
        val payableAccount:UUID,val accountingPeriod:UUID,val allocations:String,val disbursements:FinancePayrollDisbursementService)
    fun verify(jdbc: JdbcTemplate, tx: TransactionTemplate, org: UUID, preparer: UUID, run: UUID,
        clock: Clock, publication: PayrollRunPublicationPort,settledCorrection:((SettledCorrectionFixture)->UUID)?=null) {
        // Keep the parent release/correction fixture unchanged after verifying this separate journey.
        tx.execute { transaction ->
            try {
                val at = clock.instant().atOffset(ZoneOffset.UTC)
                val reviewer = member(jdbc, org, "Synthetic payment reviewer", at)
                val importer = member(jdbc, org, "Synthetic bank importer", at)
                jdbc.update("""INSERT INTO finance_payroll_authority_binding(id,organization_id,identity_id,authority_key,
                    effective_from,created_by_user_id,created_at) VALUES (?,?,?,'REVIEW',?,?,?)""",
                    UUID.randomUUID(), org, reviewer, at.minusDays(1), preparer, at)
                policy(jdbc, org, FINANCE_PAYROLL_RESULT_POLICY, FINANCE_PAYROLL_RESULT_REVIEWER, preparer, reviewer, at, true)
                val repo = FinancePayrollDisbursementRepository(jdbc)
                val instruction = repo.instruction(org, repo.expected(org, run).single().financeInstructionId)!!
                val debit = bank(jdbc, org, preparer, reviewer, importer, instruction.amount, instruction.currency, FinanceBankFlow.DEBIT, at)
                val credit = bank(jdbc, org, preparer, reviewer, importer, instruction.amount, instruction.currency, FinanceBankFlow.CREDIT, at)
                // Deferred bank source guards are executed; they are never disabled or replaced.
                jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
                val authority = CurrentFinancePayrollAuthority(jdbc, clock)
                val persistence = JdbcApprovalPersistence(jdbc)
                val subject = FinancePayrollResultSubject(repo, authority, persistence,
                    StaticListableBeanFactory(mapOf("publication" to publication)).getBeanProvider(PayrollRunPublicationPort::class.java), clock)
                val manager = DataSourceTransactionManager(requireNotNull(jdbc.dataSource))
                // Expected command failures need their own savepoint inside this rollback-only fixture.
                // Keep the production executor's REQUIRED rollback behavior unchanged.
                val negativeCommand = TransactionTemplate(manager).apply {
                    propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
                }
                val commands = JdbcIdempotentCommandExecutor(jdbc, manager, clock, HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
                val audit = JdbcAuditEventWriter(jdbc)
                val storageFixtures=mutableListOf(debit,credit)
                var beforeStorageRead: (() -> Unit)? = null
                var corruptStorage = false
                var storageReads = 0
                val storage = object : EvidenceStorageAccessPort {
                    override fun current(): EvidenceObjectStoragePort? = null
                    override fun readRestrictedObject(objectKey: String, maxBytes: Long): ByteArray {
                        storageReads++
                        beforeStorageRead?.invoke()
                        val original = storageFixtures.single { it.objectKey == objectKey }.bytes
                        check(original.size.toLong() <= maxBytes)
                        return if (corruptStorage) original.copyOf().also { it[it.lastIndex - 1] = '9'.code.toByte() } else original
                    }
                }
                val evidence = FinancePayrollEvidenceService(repo, authority, subject, storage, audit, clock)
                val engine = ApprovalEngineService(persistence, commands, AuthorizationProjectionIntentWriter {}, object : ApprovalAuthorizationPort {
                    override fun canDecide(actorUserId: UUID, approvalRequestId: UUID, assignmentPrincipalType: ApprovalPrincipalType, assignmentPrincipalId: UUID) =
                        actorUserId == reviewer && assignmentPrincipalType == ApprovalPrincipalType.USER && assignmentPrincipalId == reviewer
                }, FailClosedApprovalSubjectVersionGuard(listOf(subject)), audit, ApplicationEventPublisher {}, clock, decisionConstraints = listOf(subject))
                val service = FinancePayrollDisbursementService(repo, authority, subject, engine, commands, audit, clock, evidence)
                val adapter = FinancePayrollPaymentResultsAdapter(repo)
                assertTrue(service.capabilities(preparer, org).reviewConfigured)
                assertEquals(listOf(debit.line), service.bankChoices(preparer, org, instruction.id, "SUCCESS").map { it.bankStatementLineId })
                assertEquals(listOf(credit.line), service.bankChoices(preparer, org, instruction.id, "RETURNED").map { it.bankStatementLineId })

                // The same released payroll source can acquire an accounting
                // accrual, not a payment, before any bank result exists.
                val payrollPeriod = requireNotNull(jdbc.queryForObject(
                    "SELECT payroll_period_id FROM payroll_run_version WHERE organization_id=? AND id=?", UUID::class.java,org,run))
                val release = requireNotNull(jdbc.queryForObject(
                    "SELECT id FROM payroll_run_release WHERE organization_id=? AND run_version_id=?",UUID::class.java,org,run))
                val preparedAt = requireNotNull(jdbc.queryForObject(
                    "SELECT prepared_at FROM finance_payroll_payment_batch WHERE organization_id=? AND run_version_id=?",OffsetDateTime::class.java,org,run))
                val glPeriod=UUID.randomUUID()
                jdbc.update("""INSERT INTO finance_gl_period(id,organization_id,date_start,date_end,state,approved_company_period_reference,created_at)
                    VALUES(?,?,?,?,'OPEN','SYNTHETIC-PAYROLL-PERIOD',?)""",glPeriod,org,at.toLocalDate().minusDays(1),at.toLocalDate().plusDays(1),at)
                fun payrollChart(kind:String,code:String,bankLine:UUID?=null):UUID {
                    val id=UUID.randomUUID()
                    val bankId=bankLine?.let{jdbc.queryForObject("SELECT account_id FROM finance_bank_statement_intake_line WHERE organization_id=? AND id=?",UUID::class.java,org,it)}
                    jdbc.update("""INSERT INTO finance_gl_account(id,organization_id,account_code,account_name,account_kind,
                        currency,state,approved_company_chart_reference,created_at,treasury_bank_account_id)
                        VALUES(?,?,?,?,?,?,'APPROVED','SYNTHETIC-PAYROLL-CHART',?,?)""",id,org,code,code,kind,instruction.currency,at,bankId)
                    return id
                }
                val wageGl=payrollChart("PAYROLL_EXPENSE","SYNTH-WAGES")
                val liabilityGl=payrollChart("PAYROLL_PAYABLE","SYNTH-WAGES-DUE")
                val debitBankGl=payrollChart("BANK_ASSET","SYNTH-PAYROLL-DEBIT",debit.line)
                val creditBankGl=payrollChart("BANK_ASSET","SYNTH-PAYROLL-CREDIT",credit.line)
                val gross=requireNotNull(jdbc.queryForObject("SELECT gross FROM payroll_run_version WHERE organization_id=? AND id=?",BigDecimal::class.java,org,run))
                val deductionGl=payrollChart("PAYROLL_DEDUCTION_PAYABLE","SYNTH-PAYROLL-DEDUCTION")
                val reimbursementGl=payrollChart("EXPENSE","SYNTH-PAYROLL-REIMBURSEMENT")
                val allocations="""[{"kind":"DEDUCTION","domain":"SYNTHETIC_FIXTURE","code":"SYNTHETIC_DEDUCTION","accountId":"$deductionGl","reference":"SYNTHETIC-TREATMENT-01"},{"kind":"REIMBURSEMENT","domain":"SYNTHETIC_FIXTURE","code":"SYNTHETIC_REIMBURSEMENT","accountId":"$reimbursementGl","reference":"SYNTHETIC-FIRST-RECOGNITION"}]"""
                fun postPayroll(kind:String,source:UUID?,original:UUID?,dr:UUID,cr:UUID,
                    amount:BigDecimal=if(kind=="PAYROLL_ACCRUAL")gross else instruction.amount,
                    componentAllocations:String=if(kind=="PAYROLL_ACCRUAL")allocations else "[]"):UUID {
                    val id=UUID.randomUUID()
                    val sourceAt=source?.let{jdbc.queryForObject("SELECT recorded_at FROM finance_payroll_result_event WHERE organization_id=? AND id=?",OffsetDateTime::class.java,org,it)}?:preparedAt
                    jdbc.update("""INSERT INTO finance_payroll_gl_journal(id,operation_id,organization_id,payroll_period_id,run_version_id,
                        release_id,entry_type,source_event_id,original_journal_id,period_id,debit_account_id,credit_account_id,amount,
                        currency,source_recorded_at,posted_by_user_id,posted_at,correlation_id,component_allocations)
                        VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'synthetic-payroll-gl',CAST(? AS jsonb))""",
                        id,UUID.randomUUID(),org,payrollPeriod,run,release,kind,source,original,glPeriod,dr,cr,amount,instruction.currency,sourceAt,preparer,at,componentAllocations)
                    return id
                }
                fun rejectedPayroll(block:()->Unit) {
                    val sp=transaction.createSavepoint()
                    try { assertThrows<Exception> { block() } }
                    finally { transaction.rollbackToSavepoint(sp);transaction.releaseSavepoint(sp) }
                }
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl,gross+BigDecimal.ONE)}
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl,componentAllocations="[]")}
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl,componentAllocations=allocations.replace(deductionGl.toString(),debitBankGl.toString()))}
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl,componentAllocations=allocations.replace("SYNTHETIC_REIMBURSEMENT","UNKNOWN_COMPONENT"))}
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,debitBankGl,liabilityGl)}
                val accrual=postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl)
                assertEquals(4,jdbc.queryForObject("SELECT count(*) FROM finance_payroll_gl_journal_line WHERE organization_id=? AND journal_id=?",Int::class.java,org,accrual))
                assertEquals(0,gross.compareTo(BigDecimal("110")))
                rejectedPayroll{postPayroll("PAYROLL_ACCRUAL",null,null,wageGl,liabilityGl)}
                assertTrue(adapter.paymentEvents(org,run).isEmpty())
                assertEquals(1L,jdbc.queryForObject("SELECT journal_count FROM finance_gl_period_close_source WHERE organization_id=? AND period_id=?",Long::class.java,org,glPeriod))

                fun instructionReview()=jdbc.queryForMap("""SELECT posted_payments,posted_returns,event_count,unposted_count,void_count,pending_count,no_result_count
                    FROM finance_payroll_instruction_review WHERE organization_id=? AND instruction_id=?""",org,instruction.id)
                assertEquals(1L,instructionReview()["no_result_count"])
                assertEquals(0,BigDecimal.ZERO.compareTo(instructionReview()["posted_payments"] as BigDecimal))

                val successRequest = FinancePayrollPrepareResult(UUID.randomUUID(), 0, "SUCCESS", debit.line,
                    beneficiaryConfirmed = true, reason = "Synthetic exact beneficiary and amount checked")
                val successProposal = service.propose(preparer, org, instruction.id, successRequest, "synthetic-payroll-result")
                assertEquals(1L,instructionReview()["pending_count"])
                assertTrue(service.propose(preparer, org, instruction.id, successRequest, "synthetic-payroll-result").replayed)
                assertTrue(adapter.paymentEvents(org, run).isEmpty())
                assertTrue(service.run(reviewer, org, run).proposals.single().canReview)
                assertFalse(service.run(preparer, org, run).proposals.single().canReview)
                assertFalse(service.run(reviewer, org, run).proposals.single().evidenceViewed)
                val reviewSubject = ApprovalSubjectRef(org, FINANCE_PAYROLL_RESULT_SUBJECT, successProposal.targetId,
                    repo.proposal(org, successProposal.targetId)!!.reviewVersion)
                val approvalRequest = engine.subjectStatus(reviewSubject, FINANCE_PAYROLL_RESULT_POLICY)!!
                val deniedOperation = UUID.randomUUID()
                assertEquals("APPROVAL_SOURCE_REVIEW_NOT_ALLOWED", assertThrows<ProductApiException> {
                    negativeCommand.executeWithoutResult {
                        engine.decide(reviewer, deniedOperation, approvalRequest.approvalRequestId, ApprovalDecisionType.APPROVE, null, "synthetic-generic-proof-gate")
                    }
                }.code)
                assertFalse(transaction.isRollbackOnly, "An isolated expected rejection must not poison the rest of the source journey")
                assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotent_operation WHERE operation_id=?", Int::class.java, deniedOperation))
                assertEquals(ApprovalRequestState.PENDING, engine.subjectStatus(reviewSubject, FINANCE_PAYROLL_RESULT_POLICY)!!.state)
                assertTrue(adapter.paymentEvents(org, run).isEmpty())
                val ownProof = evidence.read(preparer, org, successProposal.targetId, "synthetic-payroll-proof")
                assertEquals(debit.bytes.toString(Charsets.UTF_8), ownProof.contentUtf8)
                assertEquals(debit.sha, ownProof.sha256)
                assertEquals(debit.evidence, ownProof.evidenceId)
                assertFalse(service.run(reviewer, org, run).proposals.single().evidenceViewed)
                val readsBeforeDenial = storageReads
                assertThrows<ProductApiException> { evidence.read(importer, org, successProposal.targetId, "synthetic-payroll-proof") }
                assertThrows<ProductApiException> { evidence.read(reviewer, UUID.randomUUID(), successProposal.targetId, "synthetic-payroll-proof") }
                assertThrows<ProductApiException> { evidence.read(reviewer, org, UUID.randomUUID(), "synthetic-payroll-proof") }
                assertEquals(readsBeforeDenial, storageReads)
                // A Finance REVIEW assignment alone does not open another reviewer's bank files.
                jdbc.update("""INSERT INTO finance_payroll_authority_binding(id,organization_id,identity_id,authority_key,
                    effective_from,created_by_user_id,created_at) VALUES (?,?,?,'REVIEW',?,?,?)""",
                    UUID.randomUUID(), org, importer, at.minusDays(1), preparer, at)
                assertThrows<ProductApiException> { evidence.read(importer, org, successProposal.targetId, "synthetic-payroll-proof") }
                assertEquals(readsBeforeDenial, storageReads)
                val uploaderBranch = transaction.createSavepoint()
                try {
                    jdbc.update("UPDATE finance_payroll_authority_binding SET active=false,version=version+1 WHERE organization_id=? AND identity_id=? AND authority_key='PREPARE'", org, preparer)
                    assertThrows<ProductApiException> { evidence.read(preparer, org, successProposal.targetId, "synthetic-payroll-proof") }
                    assertEquals(readsBeforeDenial, storageReads)
                } finally {
                    transaction.rollbackToSavepoint(uploaderBranch)
                    transaction.releaseSavepoint(uploaderBranch)
                }
                val revokeBranch = transaction.createSavepoint()
                try {
                    beforeStorageRead = { jdbc.update("UPDATE finance_payroll_authority_binding SET active=false,version=version+1 WHERE organization_id=? AND identity_id=? AND authority_key='REVIEW'", org, reviewer) }
                    assertThrows<ProductApiException> { evidence.read(reviewer, org, successProposal.targetId, "synthetic-payroll-proof") }
                    assertFalse(repo.hasEvidenceView(reviewer, org, repo.proposal(org, successProposal.targetId)!!))
                    val revokedReads = storageReads
                    assertThrows<ProductApiException> { evidence.read(reviewer, org, successProposal.targetId, "synthetic-payroll-proof") }
                    assertEquals(revokedReads, storageReads)
                } finally {
                    beforeStorageRead = null
                    transaction.rollbackToSavepoint(revokeBranch)
                    transaction.releaseSavepoint(revokeBranch)
                }
                corruptStorage = true
                assertEquals("FINANCE_PAYROLL_EVIDENCE_SOURCE_MISMATCH", assertThrows<ProductApiException> {
                    evidence.read(reviewer, org, successProposal.targetId, "synthetic-payroll-proof")
                }.code)
                assertFalse(repo.hasEvidenceView(reviewer, org, repo.proposal(org, successProposal.targetId)!!))
                corruptStorage = false
                val reviewerProof = evidence.read(reviewer, org, successProposal.targetId, "synthetic-payroll-proof")
                assertEquals(ownProof.contentUtf8, reviewerProof.contentUtf8)
                assertNotEquals(ownProof.viewReceiptId, reviewerProof.viewReceiptId)
                assertTrue(service.run(reviewer, org, run).proposals.single().evidenceViewed)
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM finance_payroll_evidence_view WHERE organization_id=? AND proposal_id=?", Int::class.java, org, successProposal.targetId))
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_event WHERE action='FINANCE_PAYROLL_EVIDENCE_VIEWED' AND target_id=?", Int::class.java, debit.evidence))
                assertEquals(ApprovalRequestState.APPROVED, engine.decide(reviewer, UUID.randomUUID(), approvalRequest.approvalRequestId,
                    ApprovalDecisionType.APPROVE, null, "synthetic-generic-proof-gate").state)
                assertTrue(adapter.paymentEvents(org, run).isEmpty(), "Generic approval is not a recorded payroll payment result")
                val review = FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE")
                assertEquals("RECORDED", service.review(reviewer, org, successProposal.targetId, review, "synthetic-payroll-result").state)
                assertTrue(service.review(reviewer, org, successProposal.targetId, review, "synthetic-payroll-result").replayed)
                assertEquals(PayrollFinanceOutcome.SUCCESS, adapter.paymentEvents(org, run).single().outcome)
                assertEquals(BigDecimal.ZERO, repo.bank(org, debit.line)!!.remaining.stripTrailingZeros())
                assertTrue(service.bankChoices(preparer, org, instruction.id, "SUCCESS").isEmpty())
                val matchedHash = matchHash(jdbc, org, debit.intake)
                assertTrue(jdbc.queryForObject("SELECT fully_matched FROM finance_bank_reconciliation_snapshot WHERE organization_id=? AND intake_id=?",
                    Boolean::class.java, org, debit.intake) == true)
                val firstEvent = repo.latest(org, instruction.id)!!
                assertEquals(1L,instructionReview()["unposted_count"])
                assertEquals(0,BigDecimal.ZERO.compareTo(instructionReview()["posted_payments"] as BigDecimal))
                rejectedPayroll{postPayroll("PAYROLL_PAYMENT",firstEvent.id,null,liabilityGl,creditBankGl)}
                val firstGl=postPayroll("PAYROLL_PAYMENT",firstEvent.id,null,liabilityGl,debitBankGl)
                assertEquals(0L,instructionReview()["unposted_count"])
                assertEquals(0,instruction.amount.compareTo(instructionReview()["posted_payments"] as BigDecimal))
                assertEquals(0L,instructionReview()["pending_count"])
                rejectedPayroll{postPayroll("PAYROLL_PAYMENT",firstEvent.id,null,liabilityGl,debitBankGl)}

                if(settledCorrection!=null){
                    val correctionBranch=transaction.createSavepoint()
                    val readsBefore=storageReads
                    try {
                        val correctedRun=settledCorrection(SettledCorrectionFixture(reviewer,accrual,wageGl,liabilityGl,glPeriod,allocations,service))
                        val extraInstruction=repo.instruction(org,repo.expected(org,correctedRun).single().financeInstructionId)!!
                        assertEquals(0,BigDecimal("5").compareTo(extraInstruction.amount))
                        val extraBank=bank(jdbc,org,preparer,reviewer,importer,extraInstruction.amount,extraInstruction.currency,FinanceBankFlow.DEBIT,at)
                        storageFixtures.add(extraBank)
                        jdbc.execute("SET CONSTRAINTS ALL IMMEDIATE")
                        val extraBankGl=payrollChart("BANK_ASSET","SYNTH-DELTA-BANK",extraBank.line)
                        val proposal=service.propose(preparer,org,extraInstruction.id,FinancePayrollPrepareResult(UUID.randomUUID(),0,
                            "SUCCESS",extraBank.line,beneficiaryConfirmed=true,reason="Synthetic exact additional payment evidence"),"synthetic-settled-delta")
                        evidence.read(reviewer,org,proposal.targetId,"synthetic-settled-delta")
                        val extraReceipt=service.review(reviewer,org,proposal.targetId,FinancePayrollReviewResult(UUID.randomUUID(),"APPROVE"),"synthetic-settled-delta")
                        assertEquals("RECORDED",extraReceipt.state)
                        assertFalse(extraReceipt.bankExecutionInitiated)
                        assertFalse(extraReceipt.ledgerPosted)
                        val event=repo.latest(org,extraInstruction.id)!!
                        val extraRelease=jdbc.queryForObject("SELECT id FROM payroll_run_release WHERE organization_id=? AND run_version_id=?",UUID::class.java,org,correctedRun)
                        val recordedAt=jdbc.queryForObject("SELECT recorded_at FROM finance_payroll_result_event WHERE organization_id=? AND id=?",OffsetDateTime::class.java,org,event.id)
                        jdbc.update("""INSERT INTO finance_payroll_gl_journal(id,operation_id,organization_id,payroll_period_id,run_version_id,
                            release_id,entry_type,source_event_id,period_id,debit_account_id,credit_account_id,amount,currency,
                            source_recorded_at,posted_by_user_id,posted_at,correlation_id,component_allocations)
                            VALUES(?,?,?,?,?,?,'PAYROLL_PAYMENT',?,?,?,?,?,?,?,?,?,'synthetic-delta-payment','[]'::jsonb)""",
                            UUID.randomUUID(),UUID.randomUUID(),org,payrollPeriod,correctedRun,extraRelease,event.id,glPeriod,
                            liabilityGl,extraBankGl,extraInstruction.amount,extraInstruction.currency,recordedAt,preparer,at)
                        val remaining=jdbc.queryForObject("""SELECT coalesce(sum(CASE WHEN l.side='CREDIT' THEN l.amount ELSE -l.amount END),0)
                            FROM finance_payroll_gl_journal_line l WHERE l.organization_id=? AND l.gl_account_id=?""",BigDecimal::class.java,org,liabilityGl)
                        assertEquals(0,remaining!!.signum())
                        assertEquals(PayrollFinanceOutcome.SUCCESS,adapter.paymentEvents(org,correctedRun).single().outcome)
                        assertEquals(PayrollFinanceOutcome.SUCCESS,adapter.paymentEvents(org,run).single().outcome)
                        assertEquals(BigDecimal.ZERO,repo.bank(org,extraBank.line)!!.remaining.stripTrailingZeros())
                    } finally {
                        transaction.rollbackToSavepoint(correctionBranch);transaction.releaseSavepoint(correctionBranch)
                        while(storageFixtures.size>2)storageFixtures.removeAt(storageFixtures.lastIndex)
                        storageReads=readsBefore
                    }
                }

                val voidRequest = FinancePayrollPrepareResult(UUID.randomUUID(), firstEvent.version, "VOID_MATCH",
                    correctsEventId = firstEvent.id, reason = "Synthetic match correction preserves original bank proof")
                val voidProposal = service.propose(preparer, org, instruction.id, voidRequest, "synthetic-payroll-result")
                assertNotNull(subject.current(org, voidProposal.targetId))
                val signedBranch = transaction.createSavepoint()
                try {
                    signStatement(jdbc, org, debit.intake, preparer, reviewer, importer, at)
                    assertTrue(repo.bank(org, debit.line)!!.reconciled)
                    assertFalse(repo.canCorrectMatch(org, firstEvent.id))
                    assertNull(subject.current(org, voidProposal.targetId))
                    assertTrue(service.run(reviewer, org, run).proposals.single { it.proposalId == voidProposal.targetId }.canCloseStale)
                    assertFalse(service.run(preparer, org, run).proposals.single { it.proposalId == voidProposal.targetId }.canCloseStale)
                    val closeRequest = FinancePayrollReviewResult(UUID.randomUUID(), "CLOSE_STALE")
                    val closed = service.review(reviewer, org, voidProposal.targetId, closeRequest, "synthetic-stale-payroll-review")
                    assertEquals("SUPERSEDED", closed.state)
                    assertFalse(closed.bankExecutionInitiated)
                    assertFalse(closed.ledgerPosted)
                    assertTrue(service.review(reviewer, org, voidProposal.targetId, closeRequest, "synthetic-stale-payroll-review").replayed)
                    assertEquals(1, adapter.paymentEvents(org, run).size, "Closing an outdated review must not record a payment or correction")
                    assertEquals("SUPERSEDED", service.run(reviewer, org, run).proposals.single { it.proposalId == voidProposal.targetId }.state)
                } finally {
                    transaction.rollbackToSavepoint(signedBranch)
                    transaction.releaseSavepoint(signedBranch)
                }
                // A draft match can be corrected only while the statement remains unsigned.
                evidence.read(reviewer, org, voidProposal.targetId, "synthetic-payroll-proof")
                service.review(reviewer, org, voidProposal.targetId, FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-payroll-result")
                assertEquals(PayrollFinanceOutcome.UNKNOWN, adapter.paymentEvents(org, run).last().outcome)
                val voidEvent=repo.latest(org,instruction.id)!!
                rejectedPayroll{postPayroll("PAYROLL_VOID",voidEvent.id,firstGl,creditBankGl,liabilityGl)}
                postPayroll("PAYROLL_VOID",voidEvent.id,firstGl,debitBankGl,liabilityGl)
                assertEquals(1L,instructionReview()["void_count"])
                assertEquals(0,BigDecimal.ZERO.compareTo(instructionReview()["posted_payments"] as BigDecimal))
                assertEquals(0,BigDecimal.ZERO.compareTo(instructionReview()["posted_returns"] as BigDecimal))
                assertEquals(instruction.amount, repo.bank(org, debit.line)!!.remaining)
                assertNotEquals(matchedHash, matchHash(jdbc, org, debit.intake))
                assertFalse(jdbc.queryForObject("SELECT fully_matched FROM finance_bank_reconciliation_snapshot WHERE organization_id=? AND intake_id=?",
                    Boolean::class.java, org, debit.intake) == true)
                val matchedAgain = service.propose(preparer, org, instruction.id,
                    successRequest.copy(operationId = UUID.randomUUID(), baseResultVersion = 2), "synthetic-payroll-result")
                evidence.read(reviewer, org, matchedAgain.targetId, "synthetic-payroll-proof")
                service.review(reviewer, org, matchedAgain.targetId, FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-payroll-result")
                val secondGl=postPayroll("PAYROLL_PAYMENT",repo.latest(org,instruction.id)!!.id,null,liabilityGl,debitBankGl)
                val returnProposal = service.propose(preparer, org, instruction.id,
                    FinancePayrollPrepareResult(UUID.randomUUID(), 3, "RETURNED", credit.line, beneficiaryConfirmed = true,
                        reason = "Synthetic bank return matched to the original beneficiary"), "synthetic-payroll-result")
                jdbc.update("UPDATE finance_payroll_authority_binding SET active=false,version=version+1 WHERE organization_id=? AND identity_id=? AND authority_key='REVIEW'", org, reviewer)
                assertThrows<ProductApiException> {
                    service.review(reviewer, org, returnProposal.targetId, FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-payroll-result")
                }
                assertEquals(3, adapter.paymentEvents(org, run).size)
                jdbc.update("UPDATE finance_payroll_authority_binding SET active=true,version=version+1 WHERE organization_id=? AND identity_id=? AND authority_key='REVIEW'", org, reviewer)
                evidence.read(reviewer, org, returnProposal.targetId, "synthetic-payroll-proof")
                service.review(reviewer, org, returnProposal.targetId, FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-payroll-result")
                assertEquals(listOf(PayrollFinanceOutcome.SUCCESS, PayrollFinanceOutcome.UNKNOWN, PayrollFinanceOutcome.SUCCESS, PayrollFinanceOutcome.RETURNED),
                    adapter.paymentEvents(org, run).map { it.outcome })
                assertEquals("RETURNED", service.run(preparer, org, run).instructions.single().outcome)
                val returnedEvent=repo.latest(org,instruction.id)!!
                postPayroll("PAYROLL_RETURN",returnedEvent.id,secondGl,creditBankGl,liabilityGl)
                assertEquals(0,instruction.amount.compareTo(instructionReview()["posted_returns"] as BigDecimal))
                assertEquals(0,instruction.amount.compareTo(instructionReview()["posted_payments"] as BigDecimal))
                assertEquals(1L,instructionReview()["void_count"])
                assertEquals(5L,jdbc.queryForObject("SELECT journal_count FROM finance_gl_period_close_source WHERE organization_id=? AND period_id=?",Long::class.java,org,glPeriod))
                val balance=requireNotNull(jdbc.queryForObject("""SELECT sum(CASE WHEN l.side='CREDIT' THEN l.amount ELSE -l.amount END)
                    FROM finance_payroll_gl_journal_line l JOIN finance_payroll_gl_journal j ON j.id=l.journal_id AND j.organization_id=l.organization_id
                    WHERE j.organization_id=? AND j.period_id=? AND l.gl_account_id=?""",BigDecimal::class.java,org,glPeriod,liabilityGl))
                assertEquals(0,balance.compareTo(instruction.amount))
                val accountingDesk=FinanceAccountingDeskReadStore(jdbc)
                assertTrue(accountingDesk.ledger(org,glPeriod,0).journals.isEmpty())
                val payrollLedger=accountingDesk.ledger(org,glPeriod,0,includePayroll=true)
                assertEquals(5,payrollLedger.journals.size)
                assertTrue(payrollLedger.currencyTotals.single().balanced)
                assertEquals(0,payrollLedger.movements.single{it.accountId==wageGl}.debit.toBigDecimal().compareTo(gross))
                assertEquals(0,payrollLedger.movements.single{it.accountId==deductionGl}.credit.toBigDecimal().compareTo(BigDecimal("20")))
                assertEquals(0,payrollLedger.movements.single{it.accountId==reimbursementGl}.debit.toBigDecimal().compareTo(BigDecimal("10")))
                assertEquals(BigDecimal.ZERO, repo.bank(org, credit.line)!!.remaining.stripTrailingZeros())
                assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM finance_bank_committed_usage WHERE organization_id=?", Int::class.java, org))

                // SQL integrity refuses duplicate use even if a caller presents fabricated approval rows.
                val duplicateBranch = transaction.createSavepoint()
                try {
                    val duplicate = FinancePayrollProposalSource(UUID.randomUUID(), org, instruction.id, 4, "SUCCESS", debit.line, null,
                        debit.evidence, debit.sha, "Synthetic duplicate allocation must be refused", 999, preparer)
                    repo.insertProposal(duplicate, UUID.randomUUID(), clock.instant())
                    val approval = approved(jdbc, org, FINANCE_PAYROLL_RESULT_SUBJECT, duplicate.id, duplicate.reviewVersion,
                        FINANCE_PAYROLL_RESULT_POLICY, FINANCE_PAYROLL_RESULT_REVIEWER, preparer, reviewer, at)
                    evidence.read(reviewer, org, duplicate.id, "synthetic-payroll-proof")
                    val rejected = assertThrows<Exception> {
                        repo.recordResult(UUID.randomUUID(), UUID.randomUUID(), duplicate, instruction, approval, reviewer, clock.instant(), "synthetic-payroll-result")
                    }
                    val states = generateSequence(rejected as Throwable?) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
                    assertTrue("23514" in states, "Duplicate allocation must fail with an explicit source constraint: $states")
                } finally {
                    transaction.rollbackToSavepoint(duplicateBranch)
                    transaction.releaseSavepoint(duplicateBranch)
                }
                assertEquals(4, adapter.paymentEvents(org, run).size)
                assertTrue(adapter.paymentEvents(UUID.randomUUID(), run).isEmpty())
                assertThrows<ProductApiException> { service.review(preparer, org, returnProposal.targetId,
                    FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-payroll-result") }
                assertThrows<ProductApiException> {
                    negativeCommand.executeWithoutResult {
                        service.propose(preparer, org, instruction.id,
                            successRequest.copy(operationId = UUID.randomUUID(), baseResultVersion = 4), "synthetic-payroll-result")
                    }
                }
                assertFalse(transaction.isRollbackOnly)
            } finally {
                transaction.setRollbackOnly()
            }
        }
        assertTrue(FinancePayrollPaymentResultsAdapter(FinancePayrollDisbursementRepository(jdbc)).paymentEvents(org, run).isEmpty())
    }

    private data class Bank(val line: UUID, val intake: UUID, val evidence: UUID, val sha: String, val bytes: ByteArray) {
        val objectKey: String get() = "synthetic/payroll-bank/$evidence"
    }

    private fun member(jdbc: JdbcTemplate, org: UUID, name: String, at: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        val person = UUID.randomUUID()
        jdbc.update("INSERT INTO person(id,display_name,created_at,updated_at) VALUES (?,?,?,?)", person, name, at, at)
        jdbc.update("INSERT INTO user_identity(id,auth_provider,auth_subject,person_id,status,created_at) VALUES (?,'synthetic-payroll-bank',?,?,'ACTIVE',?)", id, id.toString(), person, at)
        jdbc.update("INSERT INTO organization_membership(id,organization_id,user_identity_id,membership_type,state,valid_from) VALUES (?,?,?,'EMPLOYEE','ACTIVE',?)", UUID.randomUUID(), org, id, at.minusDays(1))
        return id
    }

    private fun policy(jdbc: JdbcTemplate, org: UUID, key: String, authority: String, preparer: UUID, reviewer: UUID, at: OffsetDateTime, reason: Boolean = false) {
        if (jdbc.queryForObject("SELECT count(*) FROM approval_policy_version WHERE organization_id=? AND policy_key=?", Int::class.java, org, key) == 0) {
            jdbc.update("""INSERT INTO approval_authority_binding(id,organization_id,authority_key,principal_type,principal_user_id,effective_from,active,created_by_user_id,created_at)
                VALUES (?,?,?,'USER',?,?,true,?,?)""", UUID.randomUUID(), org, authority, reviewer, at.minusDays(1), preparer, at)
            jdbc.update("""INSERT INTO approval_policy_version(id,organization_id,policy_key,version_number,lifecycle_state,approval_required,step_mode,authority_key,reason_required,effective_from,created_by_user_id,created_at)
                VALUES (?,?,?,1,'ACTIVE',true,'SINGLE',?,?,?,?,?)""", UUID.randomUUID(), org, key, authority, reason, at.minusDays(1), preparer, at)
        }
    }

    private fun approved(jdbc: JdbcTemplate, org: UUID, type: String, target: UUID, version: Long, policy: String, authority: String,
        preparer: UUID, reviewer: UUID, at: OffsetDateTime): UUID {
        val id = UUID.randomUUID()
        val step = UUID.randomUUID()
        val assignment = UUID.randomUUID()
        jdbc.update("""INSERT INTO approval_request(id,organization_id,subject_type,subject_id,subject_version,policy_key,policy_version,requester_user_id,state,reason_code,created_at,completed_at)
            VALUES (?,?,?,?,?,?,1,?,'APPROVED','SYNTHETIC_SOURCE_REVIEW',?,?)""", id, org, type, target, version, policy, preparer, at, at)
        jdbc.update("INSERT INTO approval_step(id,approval_request_id,sequence_number,mode,authority_key,state) VALUES (?,?,1,'SINGLE',?,'COMPLETED')", step, id, authority)
        jdbc.update("""INSERT INTO approval_assignment(id,approval_request_id,approval_step_id,principal_type,principal_user_id,state,assigned_at,acted_at)
            VALUES (?,?,?,'USER',?,'ACTED',?,?)""", assignment, id, step, reviewer, at, at)
        jdbc.update("""INSERT INTO approval_decision(id,approval_request_id,approval_assignment_id,actor_user_id,decision,decided_at,subject_version,operation_id)
            VALUES (?,?,?,?,'APPROVE',?,?,?)""", UUID.randomUUID(), id, assignment, reviewer, at, version, UUID.randomUUID())
        return id
    }

    private fun evidence(jdbc: JdbcTemplate, org: UUID, type: String, target: UUID, preparer: UUID, at: OffsetDateTime,
        canonicalCsv: ByteArray? = null): Pair<UUID, String> {
        val id = UUID.randomUUID()
        val bytes = canonicalCsv ?: "Synthetic restricted bank document $id".toByteArray(Charsets.UTF_8)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        jdbc.update("""INSERT INTO evidence(id,organization_id,target_type,target_id,evidence_requirement_key,evidence_type_code,content_type,original_file_name,
            size_bytes,sha256,captured_at,captured_by_user_id,storage_state,object_key_ref,finalized_at,classification_code,client_visibility_mode,created_at,security_scan_class)
            VALUES (?,?,?,?,'SYNTHETIC_BANK_PROOF',?,?,?,?,?,?,?,'READY',?,?,'HIGHLY_RESTRICTED','INTERNAL_ONLY',?,?)""",
            id, org, type, target, if (canonicalCsv != null) "FINANCE_BANK_STATEMENT_INTAKE" else "FINANCE_BANK_STATEMENT",
            if (canonicalCsv != null) "text/csv" else "application/pdf", if (canonicalCsv != null) "synthetic-statement.csv" else "synthetic-statement.pdf",
            bytes.size, sha, at, preparer, "synthetic/payroll-bank/$id", at, at, if (canonicalCsv != null) "BANK_CANONICAL_CSV" else "SIGNED_DOCUMENT")
        jdbc.update("""INSERT INTO evidence_upload_session(id,evidence_id,expected_sha256,expected_size_bytes,expires_at,state,created_at,finalized_at,operation_id)
            VALUES (?,?,?,?,?,'READY',?,?,?)""", UUID.randomUUID(), id, sha, bytes.size, at.plusHours(2), at, at, UUID.randomUUID())
        return id to sha
    }

    private fun bank(jdbc: JdbcTemplate, org: UUID, preparer: UUID, reviewer: UUID, importer: UUID, amount: BigDecimal,
        currency: String, flow: FinanceBankFlow, at: OffsetDateTime): Bank {
        val account = UUID.randomUUID()
        val proposal = UUID.randomUUID()
        val intake = UUID.randomUUID()
        val lineId = UUID.randomUUID()
        val originalRow = "SYNTHETIC-$lineId,${at.toLocalDate()},${flow.name},$currency,${amount.toPlainString()}"
        val csv = ("external_transaction_id,booked_on,flow,currency,amount\n" + originalRow + "\n").toByteArray(Charsets.UTF_8)
        val proof = evidence(jdbc, org, "FINANCE_BANK_STATEMENT_INTAKE", proposal, preparer, at, csv)
        val parsedLine = FinanceBankCsvParser.decodeAndValidate(Base64.getEncoder().encodeToString(csv), proof.second).lines.single()
        val sourceLine = FinanceBankIntakeSourceLine(parsedLine.externalTransactionId, parsedLine.bookedOn, parsedLine.flow, requireNotNull(parsedLine.currency),
            BigDecimal(parsedLine.amount), parsedLine.originalLineSha256)
        val opening = BigDecimal("100000.000000")
        val closing = if (flow == FinanceBankFlow.DEBIT) opening - amount else opening + amount
        val source = FinanceBankIntakeSource(org, account, proof.first, proof.second, currency, at.toLocalDate(), at.toLocalDate(), opening, closing,
            listOf(sourceLine), companyBankAccountApproved = true, finalizedEvidenceReviewed = true)
        val hash = FinanceBankIntakeRules.validate(source).sourceLinesSha256
        val policyKey = "FINANCE_BANK_STATEMENT_INTAKE"
        val authorityKey = "FINANCE_BANK_STATEMENT_INTAKE_REVIEWER"
        policy(jdbc, org, policyKey, authorityKey, preparer, reviewer, at)
        jdbc.update("""INSERT INTO finance_bank_account_register(id,organization_id,currency,bank_name,masked_account_reference,company_approval_reference,state,created_at)
            VALUES (?,?,?,'Synthetic Bank',?,'SYNTHETIC COMPANY BANK APPROVAL','APPROVED',?)""", account, org, currency, "SYN****${account.toString().take(8)}", at)
        jdbc.update("""INSERT INTO finance_bank_statement_intake_proposal(id,operation_id,organization_id,account_id,statement_evidence_id,statement_sha256,statement_lines_sha256,
            date_start,date_end,opening_balance,closing_balance,expected_line_count,source_version,policy_key,prepared_by_user_id,created_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,1,101,?,?,?)""", proposal, UUID.randomUUID(), org, account, proof.first, proof.second, hash,
            at.toLocalDate(), at.toLocalDate(), opening, closing, policyKey, preparer, at)
        jdbc.update("""INSERT INTO finance_bank_statement_intake_proposal_line(id,organization_id,proposal_id,line_number,external_transaction_id,booked_on,flow,currency,amount,original_line_sha256)
            VALUES (?,?,?,1,?,?,?,?,?,?)""", UUID.randomUUID(), org, proposal, sourceLine.externalTransactionId, sourceLine.bookedOn, flow.name, currency, amount, sourceLine.originalLineSha256)
        val approval = approved(jdbc, org, "FINANCE_BANK_STATEMENT_INTAKE", proposal, 101, policyKey, authorityKey, preparer, reviewer, at)
        jdbc.update("""INSERT INTO finance_bank_statement_intake(id,operation_id,organization_id,proposal_id,account_id,approval_request_id,statement_sha256,statement_lines_sha256,
            statement_evidence_id,currency,date_start,date_end,opening_balance,closing_balance,expected_line_count,imported_by_user_id,imported_at,correlation_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,?,?,'synthetic-payroll-bank')""", intake, UUID.randomUUID(), org, proposal, account, approval, proof.second, hash,
            proof.first, currency, sourceLine.bookedOn, sourceLine.bookedOn, opening, closing, importer, at)
        jdbc.update("""INSERT INTO finance_bank_statement_intake_line(id,organization_id,intake_id,account_id,external_transaction_id,booked_on,flow,currency,amount,original_line_sha256)
            VALUES (?,?,?,?,?,?,?,?,?,?)""", lineId, org, intake, account, sourceLine.externalTransactionId, sourceLine.bookedOn, flow.name, currency, amount, sourceLine.originalLineSha256)
        return Bank(lineId, intake, proof.first, proof.second, csv)
    }

    private fun matchHash(jdbc: JdbcTemplate, org: UUID, intake: UUID): String = jdbc.queryForObject(
        "SELECT match_sha256 FROM finance_bank_reconciliation_snapshot WHERE organization_id=? AND intake_id=?", String::class.java, org, intake)!!

    private fun signStatement(jdbc: JdbcTemplate, org: UUID, intake: UUID, preparer: UUID, reviewer: UUID, recorder: UUID, at: OffsetDateTime) {
        val proposal = UUID.randomUUID()
        val policyKey = "FINANCE_BANK_RECONCILIATION"
        val authorityKey = "FINANCE_BANK_RECONCILIATION_REVIEWER"
        policy(jdbc, org, policyKey, authorityKey, preparer, reviewer, at)
        jdbc.update("""INSERT INTO finance_bank_reconciliation_proposal(id,operation_id,organization_id,intake_id,account_id,statement_sha256,match_sha256,expected_line_count,
            total_credit,total_debit,total_matched,reconciliation_reference,subject_version,policy_key,prepared_by_user_id,created_at)
            SELECT ?,?,organization_id,intake_id,account_id,statement_sha256,match_sha256,actual_line_count,total_credit,total_debit,total_matched,
            'SYNTHETIC SIGNED RECONCILIATION',77,?,?,? FROM finance_bank_reconciliation_snapshot WHERE organization_id=? AND intake_id=?""",
            proposal, UUID.randomUUID(), policyKey, preparer, at, org, intake)
        val proof = evidence(jdbc, org, "FINANCE_BANK_RECONCILIATION", proposal, preparer, at)
        val approval = approved(jdbc, org, "FINANCE_BANK_RECONCILIATION", proposal, 77, policyKey, authorityKey, preparer, reviewer, at)
        jdbc.update("""INSERT INTO finance_bank_reconciliation_record(id,operation_id,organization_id,proposal_id,intake_id,approval_request_id,signed_evidence_id,recorded_by_user_id,recorded_at,correlation_id)
            VALUES (?,?,?,?,?,?,?,?,?,'synthetic-payroll-bank')""", UUID.randomUUID(), UUID.randomUUID(), org, proposal, intake, approval, proof.first, recorder, at)
    }
}
