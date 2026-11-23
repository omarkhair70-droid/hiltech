-- Phase 8 reconciliation source completeness, not a new bank workflow.
-- Original V0097 signoff had only AP/AR ledger allocations. Include already
-- independently approved employee advance/imprest funding, bank repayment,
-- and personal expense reimbursement recorded by V0090-V0092.
-- Imprest RECEIPTS_ACCEPTED have NULL bank_statement_line_id and correctly
-- do not represent an external bank movement.
CREATE OR REPLACE VIEW finance_bank_reconciliation_snapshot AS
WITH recognized_bank_events AS (
 SELECT organization_id,bank_statement_line_id,id AS event_id,
        source_kind || '_JOURNAL' AS source_kind,
        source_journal_id AS source_id,
        allocated_amount::numeric(24,6) AS amount
 FROM finance_cash_allocation_effective
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        ('FUNDING_' || draft_kind)::varchar AS source_kind,
        employee_draft_id AS source_id,amount::numeric(24,6)
 FROM finance_employee_funding_record
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        ('SETTLEMENT_' || action)::varchar AS source_kind,
        funding_record_id AS source_id,amount::numeric(24,6)
 FROM finance_employee_settlement_record
 WHERE bank_statement_line_id IS NOT NULL
 UNION ALL
 SELECT organization_id,bank_statement_line_id,id,
        'EXPENSE_REIMBURSEMENT' AS source_kind,
        expense_draft_id AS source_id,amount::numeric(24,6)
 FROM finance_expense_reimbursement_record
), matching AS (
 SELECT organization_id,bank_statement_line_id,
        sum(amount)::numeric(24,6) AS matched_amount,
        string_agg(concat_ws(':',event_id,source_kind,source_id,amount),
          '+' ORDER BY event_id) AS match_sources
 FROM recognized_bank_events
 GROUP BY organization_id,bank_statement_line_id
)
SELECT h.organization_id,h.id AS intake_id,h.account_id,h.statement_sha256,
       h.statement_lines_sha256,h.currency,h.opening_balance,h.closing_balance,
       h.expected_line_count,
       count(b.id)::bigint AS actual_line_count,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='CREDIT'),0)::numeric(24,6) AS total_credit,
       coalesce(sum(b.amount) FILTER (WHERE b.flow='DEBIT'),0)::numeric(24,6) AS total_debit,
       coalesce(sum(coalesce(m.matched_amount,0)),0)::numeric(24,6) AS total_matched,
       coalesce(bool_and(coalesce(m.matched_amount,0)=b.amount),false) AS fully_matched,
       encode(sha256(convert_to(
         coalesce(string_agg(concat_ws(':',
           b.id,b.external_transaction_id,b.booked_on,b.flow,b.currency,
           b.amount,b.original_line_sha256,coalesce(m.matched_amount,0),
           coalesce(m.match_sources,'NONE')),
           '|' ORDER BY b.id),'EMPTY'),'UTF8')),'hex') AS match_sha256
FROM finance_bank_statement_intake h
JOIN finance_bank_statement_intake_line b
 ON b.organization_id=h.organization_id AND b.intake_id=h.id
LEFT JOIN matching m ON m.organization_id=b.organization_id
 AND m.bank_statement_line_id=b.id
GROUP BY h.organization_id,h.id;

-- With the bank line locked, the signature gate cannot race a new
-- employee funding, settlement, or reimbursement record.
CREATE OR REPLACE FUNCTION finance_bank_reconciliation_prevent_late_match()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE bank_line uuid;
BEGIN
 bank_line:=NEW.bank_statement_line_id;
 IF bank_line IS NULL THEN RETURN NEW; END IF;
 PERFORM 1 FROM finance_bank_statement_intake_line
   WHERE id=bank_line AND organization_id=NEW.organization_id FOR UPDATE;
 IF EXISTS (
  SELECT 1 FROM finance_bank_reconciliation_record r
  JOIN finance_bank_statement_intake_line b
   ON b.organization_id=r.organization_id AND b.intake_id=r.intake_id
  WHERE r.organization_id=NEW.organization_id AND b.id=bank_line
 ) THEN
  RAISE EXCEPTION 'Signed statement cannot receive late Finance allocations'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_bank_reconciliation_no_late_funding
AFTER INSERT ON finance_employee_funding_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_prevent_late_match();
CREATE TRIGGER finance_bank_reconciliation_no_late_settlement
AFTER INSERT ON finance_employee_settlement_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_prevent_late_match();
CREATE TRIGGER finance_bank_reconciliation_no_late_reimbursement
AFTER INSERT ON finance_expense_reimbursement_record FOR EACH ROW
EXECUTE FUNCTION finance_bank_reconciliation_prevent_late_match();

-- Allocation source ownership and signoff remain independent approved
-- company facts. No bank transfer is initiated by this migration.
