package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.audit.AuditEventWriter
import com.hiltech.server.platform.IdempotentCommandExecution
import com.hiltech.server.platform.IdempotentCommandExecutor
import com.hiltech.server.platform.IdempotentCommandOutcome
import com.hiltech.server.platform.IdempotentCommandSpec
import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Clock
import java.util.UUID

class FinancePayrollEvidenceDecisionTest {
    @Test fun approvalCannotRecordAPaymentBeforeTheCurrentReviewerOpensThisExactProofVersion() {
        val org = UUID.randomUUID()
        val reviewer = UUID.randomUUID()
        val repository = Mockito.mock(FinancePayrollDisbursementRepository::class.java)
        val authority = Mockito.mock(FinancePayrollAuthorityPort::class.java)
        val subject = Mockito.mock(FinancePayrollResultSubject::class.java)
        val engine = Mockito.mock(ApprovalEngineService::class.java)
        val instruction = FinancePayrollInstructionSource(UUID.randomUUID(), org, UUID.randomUUID(), UUID.randomUUID(), BigDecimal("100"), "EGP", "a".repeat(64))
        val proposal = FinancePayrollProposalSource(UUID.randomUUID(), org, instruction.id, 0, "SUCCESS", UUID.randomUUID(), null,
            UUID.randomUUID(), "b".repeat(64), "Synthetic original proof review", 77, UUID.randomUUID())
        val policy = FinancePayrollReviewPolicy(1, UUID.randomUUID(), 1, reviewer)
        val bankId = requireNotNull(proposal.bankLineId)
        Mockito.`when`(authority.canReview(reviewer, org)).thenReturn(true)
        Mockito.`when`(repository.proposal(org, proposal.id)).thenReturn(proposal)
        Mockito.`when`(repository.instruction(org, instruction.id, lock = true)).thenReturn(instruction)
        Mockito.`when`(repository.instruction(org, instruction.id)).thenReturn(instruction)
        Mockito.`when`(repository.bank(org, bankId, lock = true)).thenReturn(FinancePayrollBankSource(bankId,
            instruction.amount, "EGP", "DEBIT", proposal.evidenceId, proposal.evidenceSha256, instruction.amount))
        Mockito.`when`(repository.lockRunPeriod(org, instruction.runId)).thenReturn(true)
        Mockito.`when`(subject.current(org, proposal.id)).thenReturn(FinancePayrollVerifiedProposal(proposal, instruction, policy))
        val commands = object : IdempotentCommandExecutor {
            override fun execute(spec: IdempotentCommandSpec, action: () -> IdempotentCommandOutcome) = IdempotentCommandExecution(action(), false)
        }
        val service = FinancePayrollDisbursementService(repository, authority, subject, engine, commands, AuditEventWriter.NOOP, Clock.systemUTC(),
            Mockito.mock(FinancePayrollEvidenceService::class.java))
        val error = assertThrows<ProductApiException> {
            service.review(reviewer, org, proposal.id, FinancePayrollReviewResult(UUID.randomUUID(), "APPROVE"), "synthetic-proof-review")
        }
        assertEquals("FINANCE_PAYROLL_EVIDENCE_REVIEW_REQUIRED", error.code)
        Mockito.verifyNoInteractions(engine)
    }
}
