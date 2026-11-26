package com.hiltech.server.finance

import com.hiltech.server.payroll.PayrollExpectedPayment
import com.hiltech.server.payroll.PayrollFinanceOutcome
import com.hiltech.server.payroll.PayrollFinancePaymentResultsPort
import com.hiltech.server.payroll.PayrollFinanceResult
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class FinancePayrollDisbursementRepository(private val jdbc: JdbcTemplate) {
    fun batches(org: UUID): List<FinancePayrollBatchView> = jdbc.query(
        """SELECT b.id,b.run_version_id,p.start_date,p.end_date,r.generation,b.currency,b.payable_total,
          b.line_count,b.positive_transfer_count,r.source_fingerprint,
          EXISTS(SELECT 1 FROM finance_payroll_payment_batch x WHERE x.organization_id=b.organization_id AND x.run_version_id=b.run_version_id) AS prepared
        FROM finance_payroll_review_batch b JOIN payroll_run_version r ON r.id=b.run_version_id
        JOIN payroll_period p ON p.id=r.payroll_period_id WHERE b.organization_id=?
        ORDER BY p.end_date DESC,r.generation DESC LIMIT 100""",
        { row, _ -> FinancePayrollBatchView(
            row.getObject("id", UUID::class.java), row.getObject("run_version_id", UUID::class.java),
            row.getObject("start_date", LocalDate::class.java), row.getObject("end_date", LocalDate::class.java),
            row.getInt("generation"), row.getString("currency"), row.getBigDecimal("payable_total").toPlainString(),
            row.getInt("line_count"), row.getInt("positive_transfer_count"), row.getBoolean("prepared"), row.getString("source_fingerprint"),
        ) }, org,
    )

    internal fun instruction(org: UUID, id: UUID, lock: Boolean = false): FinancePayrollInstructionSource? = jdbc.query(
        "SELECT * FROM finance_payroll_instruction WHERE organization_id=? AND id=?" + if (lock) " FOR UPDATE" else "",
        { row, _ -> FinancePayrollInstructionSource(row.getObject("id", UUID::class.java), org,
            row.getObject("run_version_id", UUID::class.java), row.getObject("employee_id", UUID::class.java),
            row.getBigDecimal("amount"), row.getString("currency"), row.getString("source_fingerprint")) }, org, id,
    ).singleOrNull()

    fun lockRunPeriod(org: UUID, run: UUID): Boolean = jdbc.query(
        """SELECT p.id FROM payroll_period p JOIN payroll_run_version r ON r.payroll_period_id=p.id
        WHERE p.organization_id=? AND r.organization_id=? AND r.id=? FOR UPDATE OF p""",
        { row, _ -> row.getObject("id", UUID::class.java) }, org, org, run,
    ).isNotEmpty()

    fun differentialCorrection(run: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM payroll_run_correction WHERE replacement_run_version_id=? AND finance_disposition='SETTLED_DIFFERENTIAL_AUTHORIZED')",
        Boolean::class.java, run,
    ) == true

    fun latestReleased(org: UUID, run: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM payroll_run_version v JOIN payroll_run_release r ON r.run_version_id=v.id
          WHERE v.id=? AND v.organization_id=? AND NOT EXISTS(
            SELECT 1 FROM payroll_run_version newer JOIN payroll_run_release released ON released.run_version_id=newer.id
            WHERE newer.organization_id=v.organization_id AND newer.payroll_period_id=v.payroll_period_id AND newer.generation>v.generation))""",
        Boolean::class.java, run, org,
    ) == true

    fun prepareBatch(id: UUID, org: UUID, batch: FinancePayrollBatchView, operation: UUID, actor: UUID, at: Instant) {
        check(jdbc.update("""INSERT INTO finance_payroll_payment_batch(id,operation_id,organization_id,review_batch_id,run_version_id,prepared_by_user_id,prepared_at)
            VALUES (?,?,?,?,?,?,?)""", id, operation, org, batch.reviewBatchId, batch.runVersionId, actor, at.atOffset(ZoneOffset.UTC)) == 1)
        val lines = jdbc.query("SELECT employee_id,net,currency FROM payroll_run_employee_line WHERE run_version_id=? AND net>0 ORDER BY employee_id",
            { row, _ -> Triple(row.getObject("employee_id", UUID::class.java), row.getBigDecimal("net"), row.getString("currency")) }, batch.runVersionId)
        lines.forEach { line ->
            val instructionId = UUID.nameUUIDFromBytes("hiltech:finance:payroll-instruction:${batch.runVersionId}:${line.first}".toByteArray(Charsets.UTF_8))
            check(jdbc.update("""INSERT INTO finance_payroll_instruction(id,organization_id,batch_id,run_version_id,employee_id,amount,currency,source_fingerprint)
                VALUES (?,?,?,?,?,?,?,?)""", instructionId, org, id, batch.runVersionId, line.first, line.second, line.third, batch.sourceFingerprint) == 1)
        }
    }

    fun instructionViews(org: UUID, run: UUID): List<FinancePayrollInstructionView> = jdbc.query(
        """SELECT i.id,i.employee_id,p.display_name,e.employee_code,i.amount,i.currency,
          last.id AS event_id,last.version,last.outcome,last.action,last.recorded_at
        FROM finance_payroll_instruction i JOIN employee e ON e.id=i.employee_id JOIN person p ON p.id=e.person_id
        LEFT JOIN LATERAL (SELECT * FROM finance_payroll_result_event r WHERE r.organization_id=i.organization_id AND r.instruction_id=i.id ORDER BY r.version DESC LIMIT 1) last ON true
        WHERE i.organization_id=? AND i.run_version_id=? ORDER BY p.display_name,e.employee_code""",
        { row, _ -> FinancePayrollInstructionView(row.getObject("id", UUID::class.java), row.getObject("employee_id", UUID::class.java),
            row.getString("display_name"), row.getString("employee_code"), row.getBigDecimal("amount").toPlainString(), row.getString("currency"),
            row.getLong("version"), row.getString("outcome") ?: "PAYMENT_NOT_RECORDED", row.getObject("event_id", UUID::class.java),
            row.getString("action"), row.getObject("recorded_at", OffsetDateTime::class.java)?.toInstant()) }, org, run,
    )

    internal fun latest(org: UUID, instruction: UUID): FinancePayrollResultSource? = jdbc.query(
        "SELECT id,version,action,outcome,evidence_id,evidence_sha256 FROM finance_payroll_result_event WHERE organization_id=? AND instruction_id=? ORDER BY version DESC LIMIT 1",
        { row, _ -> FinancePayrollResultSource(row.getObject("id", UUID::class.java), row.getLong("version"), row.getString("action"), row.getString("outcome"),
            row.getObject("evidence_id", UUID::class.java), row.getString("evidence_sha256")) }, org, instruction,
    ).singleOrNull()

    internal fun bank(org: UUID, id: UUID, lock: Boolean = false): FinancePayrollBankSource? {
        if (lock) jdbc.query("SELECT id FROM finance_bank_statement_intake_line WHERE organization_id=? AND id=? FOR UPDATE",
            { row, _ -> row.getObject("id", UUID::class.java) }, org, id)
        return jdbc.query(
            """SELECT l.id,l.amount,l.currency,l.flow,h.statement_evidence_id,h.statement_sha256,
                l.amount-coalesce((SELECT sum(u.amount) FROM finance_bank_committed_usage u WHERE u.organization_id=l.organization_id AND u.bank_statement_line_id=l.id),0) AS remaining,
                EXISTS(SELECT 1 FROM finance_bank_reconciliation_record r WHERE r.organization_id=l.organization_id AND r.intake_id=l.intake_id) AS reconciled
            FROM finance_bank_statement_intake_line l JOIN finance_bank_statement_intake h ON h.id=l.intake_id AND h.organization_id=l.organization_id
            WHERE l.organization_id=? AND l.id=?""",
            { row, _ -> FinancePayrollBankSource(row.getObject("id", UUID::class.java), row.getBigDecimal("amount"), row.getString("currency"),
                row.getString("flow"), row.getObject("statement_evidence_id", UUID::class.java), row.getString("statement_sha256"), row.getBigDecimal("remaining"), row.getBoolean("reconciled")) }, org, id,
        ).singleOrNull()
    }

    fun canCorrectMatch(org: UUID, event: UUID, lock: Boolean = false): Boolean {
        val line = jdbc.query("SELECT bank_statement_line_id FROM finance_payroll_result_event WHERE organization_id=? AND id=? AND action='SUCCESS'",
            { row, _ -> row.getObject("bank_statement_line_id", UUID::class.java) }, org, event).singleOrNull() ?: return false
        return bank(org, line, lock)?.reconciled == false
    }

    internal fun bankChoices(source: FinancePayrollInstructionSource, flow: String): List<FinancePayrollBankLineView> = jdbc.query(
        """SELECT l.id,l.external_transaction_id,l.booked_on,a.bank_name,a.masked_account_reference,l.amount,l.currency
        FROM finance_bank_statement_intake_line l JOIN finance_bank_statement_intake h ON h.id=l.intake_id AND h.organization_id=l.organization_id
        JOIN finance_bank_account_register a ON a.id=h.account_id AND a.organization_id=h.organization_id
        JOIN evidence e ON e.id=h.statement_evidence_id AND e.organization_id=h.organization_id
        JOIN evidence_upload_session upload ON upload.evidence_id=e.id
        WHERE l.organization_id=? AND l.currency=? AND l.amount=? AND l.flow=?
          AND e.storage_state='READY' AND upload.state='READY' AND e.finalized_at IS NOT NULL AND upload.finalized_at IS NOT NULL
          AND e.object_key_ref IS NOT NULL AND e.sha256=h.statement_sha256 AND e.sha256=upload.expected_sha256
          AND e.size_bytes=upload.expected_size_bytes AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY'
          AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE' AND e.target_id=h.proposal_id
          AND e.evidence_type_code='FINANCE_BANK_STATEMENT_INTAKE' AND e.security_scan_class='BANK_CANONICAL_CSV' AND e.content_type='text/csv'
          AND NOT EXISTS(SELECT 1 FROM finance_bank_committed_usage u WHERE u.organization_id=l.organization_id AND u.bank_statement_line_id=l.id)
          AND NOT EXISTS(SELECT 1 FROM finance_bank_reconciliation_record r WHERE r.organization_id=l.organization_id AND r.intake_id=l.intake_id)
        ORDER BY l.booked_on DESC,l.external_transaction_id LIMIT 100""",
        { row, _ -> FinancePayrollBankLineView(row.getObject("id", UUID::class.java), row.getString("external_transaction_id"),
            row.getObject("booked_on", LocalDate::class.java), row.getString("bank_name"), row.getString("masked_account_reference"),
            row.getBigDecimal("amount").toPlainString(), row.getString("currency")) }, source.organizationId, source.currency, source.amount, flow,
    )

    fun evidenceReady(org: UUID, id: UUID, sha: String): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM evidence e JOIN evidence_upload_session u ON u.evidence_id=e.id
         WHERE e.id=? AND e.organization_id=? AND e.sha256=? AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE'
           AND e.storage_state='READY' AND u.state='READY' AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
           AND e.object_key_ref IS NOT NULL AND e.sha256=u.expected_sha256 AND e.size_bytes=u.expected_size_bytes
           AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY'
           AND e.evidence_type_code='FINANCE_BANK_STATEMENT_INTAKE' AND e.security_scan_class='BANK_CANONICAL_CSV' AND e.content_type='text/csv')""",
        Boolean::class.java, id, org, sha,
    ) == true

    fun isBeneficiary(actor: UUID, employee: UUID): Boolean = jdbc.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM employee e JOIN user_identity u ON u.person_id=e.person_id WHERE e.id=? AND u.id=?)", Boolean::class.java, employee, actor,
    ) == true

    internal fun proposal(org: UUID, id: UUID): FinancePayrollProposalSource? = jdbc.query(
        "SELECT * FROM finance_payroll_result_proposal WHERE organization_id=? AND id=?",
        { row, _ -> FinancePayrollProposalSource(row.getObject("id", UUID::class.java), org, row.getObject("instruction_id", UUID::class.java),
            row.getLong("base_result_version"), row.getString("action"), row.getObject("bank_statement_line_id", UUID::class.java),
            row.getObject("corrects_event_id", UUID::class.java), row.getObject("evidence_id", UUID::class.java), row.getString("evidence_sha256"),
            row.getString("reason"), row.getLong("review_version"), row.getObject("prepared_by_user_id", UUID::class.java)) }, org, id,
    ).singleOrNull()

    internal fun insertProposal(p: FinancePayrollProposalSource, operation: UUID, at: Instant) {
        check(jdbc.update("""INSERT INTO finance_payroll_result_proposal(id,operation_id,organization_id,instruction_id,base_result_version,action,
          bank_statement_line_id,corrects_event_id,evidence_id,evidence_sha256,reason,review_version,policy_key,prepared_by_user_id,prepared_at)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", p.id, operation, p.organizationId, p.instructionId, p.baseVersion, p.action,
            p.bankLineId, p.correctsEventId, p.evidenceId, p.evidenceSha256, p.reason, p.reviewVersion, FINANCE_PAYROLL_RESULT_POLICY, p.preparedBy, at.atOffset(ZoneOffset.UTC)) == 1)
    }

    internal fun recordResult(id: UUID, operation: UUID, p: FinancePayrollProposalSource, source: FinancePayrollInstructionSource,
        approval: UUID, actor: UUID, at: Instant, correlation: String) {
        check(jdbc.update("""INSERT INTO finance_payroll_result_event(id,operation_id,organization_id,proposal_id,instruction_id,version,action,outcome,
          bank_statement_line_id,corrects_event_id,evidence_id,evidence_sha256,amount,currency,approval_request_id,recorded_by_user_id,recorded_at,correlation_id)
          VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", id, operation, p.organizationId, p.id, p.instructionId, p.baseVersion + 1, p.action,
            if (p.action == "VOID_MATCH") "UNKNOWN" else p.action, p.bankLineId, p.correctsEventId, p.evidenceId, p.evidenceSha256,
            source.amount, source.currency, approval, actor, at.atOffset(ZoneOffset.UTC), correlation) == 1)
    }

    /** No generic evidence ID lookup: the protected file must belong to this exact Finance proposal. */
    internal fun evidenceSource(org: UUID, proposal: UUID): FinancePayrollEvidenceSource? = jdbc.query(
        """SELECT e.id,e.sha256,e.target_id,e.captured_by_user_id,e.object_key_ref,e.original_file_name,e.content_type,e.size_bytes,
          bank.external_transaction_id,bank.booked_on,bank.flow,bank.currency,bank.amount,bank.original_line_sha256
        FROM finance_payroll_result_proposal p
        JOIN finance_payroll_instruction i ON i.id=p.instruction_id AND i.organization_id=p.organization_id
        LEFT JOIN finance_payroll_result_event corrected ON corrected.id=p.corrects_event_id AND corrected.organization_id=p.organization_id
          AND corrected.instruction_id=i.id AND corrected.action='SUCCESS'
        JOIN finance_bank_statement_intake_line bank ON bank.id=coalesce(p.bank_statement_line_id,corrected.bank_statement_line_id)
          AND bank.organization_id=p.organization_id AND bank.amount=i.amount AND bank.currency=i.currency
          AND bank.flow=CASE WHEN p.action='RETURNED' THEN 'CREDIT' ELSE 'DEBIT' END
        JOIN finance_bank_statement_intake h ON h.id=bank.intake_id AND h.organization_id=p.organization_id
          AND h.statement_evidence_id=p.evidence_id AND h.statement_sha256=p.evidence_sha256
        JOIN evidence e ON e.id=p.evidence_id AND e.organization_id=p.organization_id AND e.sha256=p.evidence_sha256
          AND e.target_id=h.proposal_id AND e.target_type='FINANCE_BANK_STATEMENT_INTAKE'
        JOIN evidence_upload_session u ON u.evidence_id=e.id AND u.state='READY' AND u.finalized_at IS NOT NULL
          AND u.expected_sha256=e.sha256 AND u.expected_size_bytes=e.size_bytes
        WHERE p.organization_id=? AND p.id=? AND e.storage_state='READY' AND e.finalized_at IS NOT NULL AND e.object_key_ref IS NOT NULL
          AND e.classification_code='HIGHLY_RESTRICTED' AND e.client_visibility_mode='INTERNAL_ONLY'
          AND e.evidence_type_code='FINANCE_BANK_STATEMENT_INTAKE' AND e.security_scan_class='BANK_CANONICAL_CSV' AND e.content_type='text/csv'""",
        { row, _ -> FinancePayrollEvidenceSource(row.getObject("id", UUID::class.java), row.getString("sha256"), row.getObject("target_id", UUID::class.java),
            row.getObject("captured_by_user_id", UUID::class.java), row.getString("object_key_ref"), row.getString("original_file_name"), row.getString("content_type"),
            row.getLong("size_bytes"), row.getString("external_transaction_id"), row.getObject("booked_on", LocalDate::class.java), row.getString("flow"),
            row.getString("currency"), row.getBigDecimal("amount"), row.getString("original_line_sha256")) }, org, proposal,
    ).singleOrNull()

    internal fun hasEvidenceView(actor: UUID, org: UUID, proposal: FinancePayrollProposalSource): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM finance_payroll_evidence_view WHERE organization_id=? AND proposal_id=?
          AND viewer_user_id=? AND review_version=? AND evidence_id=? AND evidence_sha256=?)""", Boolean::class.java,
        org, proposal.id, actor, proposal.reviewVersion, proposal.evidenceId, proposal.evidenceSha256,
    ) == true

    internal fun recordEvidenceView(id: UUID, actor: UUID, org: UUID, proposal: FinancePayrollProposalSource, at: Instant, correlation: String) {
        check(jdbc.update("""INSERT INTO finance_payroll_evidence_view(id,organization_id,proposal_id,evidence_id,evidence_sha256,review_version,
            viewer_user_id,viewed_at,correlation_id) VALUES (?,?,?,?,?,?,?,?,?)""", id, org, proposal.id, proposal.evidenceId, proposal.evidenceSha256,
            proposal.reviewVersion, actor, at.atOffset(ZoneOffset.UTC), correlation) == 1)
    }

    fun proposalViews(org: UUID, run: UUID): List<FinancePayrollProposalView> = jdbc.query(
        """SELECT p.id,p.instruction_id,employee_name.display_name AS employee_name,i.amount,i.currency,p.action,p.reason,
          coalesce(actor_name.display_name,'') AS prepared_by_name,p.prepared_at,p.evidence_id,
          bank.external_transaction_id,bank.booked_on,account.bank_name,
          CASE WHEN EXISTS(SELECT 1 FROM finance_payroll_result_event e WHERE e.proposal_id=p.id) THEN 'RECORDED'
            ELSE coalesce((SELECT a.state FROM approval_request a WHERE a.organization_id=p.organization_id AND a.subject_id=p.id
              AND a.subject_type='FINANCE_PAYROLL_RESULT' ORDER BY a.created_at DESC LIMIT 1),'PROPOSED') END AS state
        FROM finance_payroll_result_proposal p JOIN finance_payroll_instruction i ON i.id=p.instruction_id
        JOIN employee employee ON employee.id=i.employee_id JOIN person employee_name ON employee_name.id=employee.person_id
        JOIN user_identity actor ON actor.id=p.prepared_by_user_id LEFT JOIN person actor_name ON actor_name.id=actor.person_id
        LEFT JOIN finance_bank_statement_intake_line bank ON bank.id=p.bank_statement_line_id
        LEFT JOIN finance_bank_statement_intake intake ON intake.id=bank.intake_id
        LEFT JOIN finance_bank_account_register account ON account.id=intake.account_id
        WHERE p.organization_id=? AND i.run_version_id=? ORDER BY p.prepared_at DESC,p.id LIMIT 100""",
        { row, _ -> FinancePayrollProposalView(row.getObject("id", UUID::class.java), row.getObject("instruction_id", UUID::class.java),
            row.getString("employee_name"), row.getBigDecimal("amount").toPlainString(), row.getString("currency"), row.getString("action"),
            row.getString("reason"), row.getString("state"), row.getString("prepared_by_name"), row.getObject("prepared_at", OffsetDateTime::class.java).toInstant(),
            row.getString("external_transaction_id"), row.getObject("booked_on", LocalDate::class.java), row.getString("bank_name"),
            row.getObject("evidence_id", UUID::class.java), false) }, org, run,
    )

    internal fun pendingAssignedReviewer(org: UUID, proposal: UUID, actor: UUID): Boolean = jdbc.queryForObject(
        """SELECT EXISTS(SELECT 1 FROM approval_request r JOIN approval_step s ON s.approval_request_id=r.id AND s.sequence_number=1
           JOIN approval_assignment a ON a.approval_request_id=r.id AND a.approval_step_id=s.id
           WHERE r.organization_id=? AND r.subject_type='FINANCE_PAYROLL_RESULT' AND r.subject_id=? AND r.state='PENDING'
             AND r.policy_key='FINANCE_PAYROLL_RESULT_REVIEW' AND a.state='ASSIGNED' AND a.principal_type='USER'
             AND a.principal_user_id=?)""", Boolean::class.java, org, proposal, actor,
    ) == true

    fun expected(org: UUID, run: UUID): List<PayrollExpectedPayment> = jdbc.query(
        "SELECT * FROM finance_payroll_instruction WHERE organization_id=? AND run_version_id=? ORDER BY employee_id",
        { row, _ -> PayrollExpectedPayment(org, run, row.getObject("employee_id", UUID::class.java), row.getObject("id", UUID::class.java),
            row.getLong("version"), row.getBigDecimal("amount"), row.getString("currency")) }, org, run,
    )

    fun events(org: UUID, run: UUID): List<PayrollFinanceResult> = jdbc.query(
        """SELECT e.*,i.employee_id,i.version AS instruction_version FROM finance_payroll_result_event e
         JOIN finance_payroll_instruction i ON i.id=e.instruction_id AND i.organization_id=e.organization_id
         WHERE e.organization_id=? AND i.run_version_id=? ORDER BY e.instruction_id,e.version""",
        { row, _ -> PayrollFinanceResult(org, run, row.getObject("employee_id", UUID::class.java), row.getObject("instruction_id", UUID::class.java),
            row.getLong("instruction_version"), row.getObject("id", UUID::class.java), PayrollFinanceOutcome.valueOf(row.getString("outcome")),
            row.getLong("version"), "evidence:" + row.getObject("evidence_id", UUID::class.java), row.getObject("recorded_at", OffsetDateTime::class.java).toInstant()) }, org, run,
    )
}

@Component
class FinancePayrollPaymentResultsAdapter(private val repository: FinancePayrollDisbursementRepository) : PayrollFinancePaymentResultsPort {
    override fun expectedPayments(organizationId: UUID, runVersionId: UUID): List<PayrollExpectedPayment> = repository.expected(organizationId, runVersionId)
    override fun paymentEvents(organizationId: UUID, runVersionId: UUID): List<PayrollFinanceResult> = repository.events(organizationId, runVersionId)
}
