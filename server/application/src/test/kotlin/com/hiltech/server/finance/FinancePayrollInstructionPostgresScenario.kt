package com.hiltech.server.finance

import com.hiltech.server.approval.ApprovalEngineService
import com.hiltech.server.approval.ApprovalPersistencePort
import com.hiltech.server.audit.JdbcAuditEventWriter
import com.hiltech.server.payroll.Payroll9cExpectedBatchGuard
import com.hiltech.server.payroll.PayrollRunPublicationPort
import com.hiltech.server.platform.command.JdbcIdempotentCommandExecutor
import com.hiltech.server.telemetry.HiltechTelemetryRuntime
import io.opentelemetry.api.OpenTelemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.SQLException
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID

/** Extends the existing genuine released-payroll SQL fixture; never disables source guards. */
object FinancePayrollInstructionPostgresScenario {
    fun verify(jdbc: JdbcTemplate, tx: TransactionTemplate, org: UUID, actor: UUID, run: UUID,
        reviewBatch: UUID, clock: Clock, publication: PayrollRunPublicationPort,
        settledCorrection:((FinancePayrollResultPostgresScenario.SettledCorrectionFixture)->UUID)?=null) {
        val at = clock.instant().atOffset(ZoneOffset.UTC)
        jdbc.update("""INSERT INTO finance_payroll_authority_binding(id,organization_id,identity_id,authority_key,
            effective_from,created_by_user_id,created_at) VALUES (?,?,?,'PREPARE',?,?,?)""",
            UUID.randomUUID(), org, actor, at.minusMinutes(1), actor, at)
        val repo = FinancePayrollDisbursementRepository(jdbc)
        val actualAuthority = CurrentFinancePayrollAuthority(jdbc, clock)
        assertTrue(actualAuthority.canPrepare(actor, org))
        assertFalse(actualAuthority.canReview(actor, org))
        assertFalse(actualAuthority.canPrepare(actor, UUID.randomUUID()))
        val frozen = jdbc.query("SELECT employee_id,net,currency FROM payroll_run_employee_line WHERE run_version_id=? AND net>0 ORDER BY employee_id",
            { row, _ -> Triple(row.getObject("employee_id", UUID::class.java), row.getBigDecimal("net"), row.getString("currency")) }, run)
        assertTrue(frozen.isNotEmpty())
        val batchSource = repo.batches(org).single { it.reviewBatchId == reviewBatch }

        fun refusedBatch(mode: String) {
            val failure = assertThrows<Exception> {
                tx.execute {
                    val batch = UUID.randomUUID()
                    jdbc.update("""INSERT INTO finance_payroll_payment_batch(id,operation_id,organization_id,review_batch_id,run_version_id,prepared_by_user_id,prepared_at)
                        VALUES (?,?,?,?,?,?,?)""", batch, UUID.randomUUID(), org, reviewBatch, run, actor, at)
                    if (mode != "INCOMPLETE") frozen.forEach { line ->
                        jdbc.update("""INSERT INTO finance_payroll_instruction(id,organization_id,batch_id,run_version_id,employee_id,amount,currency,source_fingerprint)
                            VALUES (?,?,?,?,?,?,?,?)""", UUID.randomUUID(), org, batch, run, line.first,
                            if (mode == "WRONG_AMOUNT") line.second + BigDecimal.ONE else line.second,
                            if (mode == "WRONG_CURRENCY") "USD" else line.third, batchSource.sourceFingerprint)
                    }
                }
            }
            val states = generateSequence(failure as Throwable?) { it.cause }.filterIsInstance<SQLException>().map { it.sqlState }.toList()
            assertTrue("23514" in states, "A frozen source refusal must be explicit: $states")
        }
        refusedBatch("INCOMPLETE")
        refusedBatch("WRONG_AMOUNT")
        refusedBatch("WRONG_CURRENCY")
        assertTrue(repo.expected(org, run).isEmpty())

        val subject = FinancePayrollResultSubject(repo, actualAuthority, Mockito.mock(ApprovalPersistencePort::class.java),
            StaticListableBeanFactory(mapOf("publication" to publication)).getBeanProvider(PayrollRunPublicationPort::class.java), clock)
        val commands = JdbcIdempotentCommandExecutor(jdbc, DataSourceTransactionManager(requireNotNull(jdbc.dataSource)), clock,
            HiltechTelemetryRuntime(OpenTelemetry.noop()) {})
        val service = FinancePayrollDisbursementService(repo, actualAuthority, subject, Mockito.mock(ApprovalEngineService::class.java),
            commands, JdbcAuditEventWriter(jdbc), clock, Mockito.mock(FinancePayrollEvidenceService::class.java))
        val operation = UUID.randomUUID()
        val created = service.prepareBatch(actor, org, reviewBatch, operation, "synthetic-payroll-instructions")
        assertEquals("PREPARED", created.state)
        assertFalse(created.bankExecutionInitiated)
        assertFalse(created.ledgerPosted)
        assertTrue(service.prepareBatch(actor, org, reviewBatch, operation, "synthetic-payroll-instructions").replayed)
        val adapter = FinancePayrollPaymentResultsAdapter(repo)
        val expected = adapter.expectedPayments(org, run)
        assertEquals(frozen.size, expected.size)
        Payroll9cExpectedBatchGuard.validateAgainstDatabase(jdbc, org, run, expected)
        assertTrue(adapter.paymentEvents(org, run).isEmpty())
        assertTrue(adapter.expectedPayments(UUID.randomUUID(), run).isEmpty())
        assertEquals("PAYMENT_NOT_RECORDED", service.run(actor, org, run).instructions.single().outcome)
        assertThrows<Exception> { jdbc.update("UPDATE finance_payroll_instruction SET amount=amount+1 WHERE organization_id=? AND run_version_id=?", org, run) }
        assertEquals(frozen.first().second.stripTrailingZeros(), repo.expected(org, run).first().amount.stripTrailingZeros())
        FinancePayrollResultPostgresScenario.verify(jdbc, tx, org, actor, run, clock, publication,settledCorrection)
        jdbc.update("UPDATE finance_payroll_authority_binding SET active=false,version=version+1 WHERE organization_id=? AND identity_id=?", org, actor)
        assertFalse(actualAuthority.canPrepare(actor, org))
        assertThrows<Exception> { service.run(actor, org, run) }
    }
}
