-- Source-bound replacement of an unpaid payroll accrual. The original journal
-- is immutable. The replacement includes a reversal of its exact base lines
-- plus the new frozen components, in one transaction and one journal.
-- No-transfer clearance is necessary; any older bank result/proposal fails
-- closed. Verified-return and settled-differential accounting remain separate.
DROP INDEX uq_finance_payroll_gl_accrual_period;
CREATE UNIQUE INDEX uq_finance_payroll_gl_accrual_run
 ON finance_payroll_gl_journal(organization_id,run_version_id) WHERE entry_type='PAYROLL_ACCRUAL';

DO $$
DECLARE constraint_name text;
BEGIN
 SELECT conname INTO STRICT constraint_name FROM pg_constraint
 WHERE conrelid='finance_payroll_gl_journal'::regclass AND contype='c'
   AND pg_get_constraintdef(oid) LIKE '%original_journal_id IS NOT NULL%';
 EXECUTE format('ALTER TABLE finance_payroll_gl_journal DROP CONSTRAINT %I',constraint_name);
END; $$;
ALTER TABLE finance_payroll_gl_journal ADD CONSTRAINT finance_payroll_gl_original_source_valid CHECK(
 entry_type='PAYROLL_ACCRUAL' OR
 ((entry_type IN('PAYROLL_RETURN','PAYROLL_VOID'))=(original_journal_id IS NOT NULL)));

CREATE VIEW finance_payroll_gl_correction_source AS
 SELECT c.organization_id,c.id AS correction_id,c.replacement_run_version_id,c.original_run_version_id,
   c.finance_clearance_ref,c.finance_clearance_version,original.id AS original_journal_id,original.posted_at AS original_posted_at
 FROM payroll_run_correction c
 JOIN payroll_run_version newer ON newer.organization_id=c.organization_id AND newer.id=c.replacement_run_version_id
 JOIN payroll_run_version older ON older.organization_id=c.organization_id AND older.id=c.original_run_version_id
 JOIN payroll_run_release published ON published.organization_id=c.organization_id AND published.run_version_id=newer.id
 JOIN payroll_run_release previous ON previous.organization_id=c.organization_id AND previous.id=c.original_release_id
    AND previous.run_version_id=older.id
 LEFT JOIN finance_payroll_gl_journal original ON original.organization_id=c.organization_id
    AND original.run_version_id=older.id AND original.entry_type='PAYROLL_ACCRUAL'
 WHERE c.finance_disposition='NO_PRIOR_TRANSFER_VERIFIED'
   AND c.original_source_fingerprint=older.source_fingerprint AND c.replacement_source_fingerprint=newer.source_fingerprint
   AND published.run_source_fingerprint=newer.source_fingerprint AND previous.run_source_fingerprint=older.source_fingerprint
   AND older.currency=newer.currency AND older.payroll_period_id=newer.payroll_period_id AND older.generation<newer.generation
   AND NOT EXISTS(SELECT 1 FROM finance_payroll_instruction i
     JOIN payroll_run_version r ON r.organization_id=i.organization_id AND r.id=i.run_version_id
     WHERE i.organization_id=c.organization_id AND r.payroll_period_id=newer.payroll_period_id AND r.generation<newer.generation
       AND (EXISTS(SELECT 1 FROM finance_payroll_result_event e WHERE e.organization_id=i.organization_id AND e.instruction_id=i.id)
         OR EXISTS(SELECT 1 FROM finance_payroll_result_proposal p WHERE p.organization_id=i.organization_id AND p.instruction_id=i.id)))
   AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal j
     WHERE j.organization_id=c.organization_id AND j.payroll_period_id=newer.payroll_period_id
       AND j.entry_type='PAYROLL_ACCRUAL' AND j.run_version_id<>newer.id
       AND j.id IS DISTINCT FROM original.id
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal replaced WHERE replaced.organization_id=j.organization_id
           AND replaced.entry_type='PAYROLL_ACCRUAL' AND replaced.original_journal_id=j.id))
   AND (original.id IS NULL OR NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal replaced
       WHERE replaced.organization_id=original.organization_id AND replaced.original_journal_id=original.id
          AND replaced.run_version_id<>newer.id));

CREATE OR REPLACE FUNCTION finance_payroll_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 r payroll_run_version%ROWTYPE;
 rel payroll_run_release%ROWTYPE;
 payment_batch finance_payroll_payment_batch%ROWTYPE;
 e finance_payroll_result_event%ROWTYPE;
 i finance_payroll_instruction%ROWTYPE;
 prior finance_payroll_result_event%ROWTYPE;
 accrual finance_payroll_gl_journal%ROWTYPE;
 original finance_payroll_gl_journal%ROWTYPE;
 fiscal finance_gl_period%ROWTYPE;
 bank_account uuid;
 correction_source record;
BEGIN
 IF NOT finance_payroll_current_authority(NEW.organization_id,NEW.posted_by_user_id,'PREPARE')
 THEN RAISE EXCEPTION 'Current finance payroll preparation authority required' USING ERRCODE='23514'; END IF;
 -- Match the lock order of payroll result writers: instruction, payroll period,
 -- accounting period. A result correction cannot race a posting of its source.
 IF NEW.source_event_id IS NOT NULL THEN
   SELECT * INTO e FROM finance_payroll_result_event
     WHERE organization_id=NEW.organization_id AND id=NEW.source_event_id;
   IF NOT FOUND THEN RAISE EXCEPTION 'Recorded payroll result required' USING ERRCODE='23514'; END IF;
   SELECT * INTO i FROM finance_payroll_instruction
     WHERE organization_id=NEW.organization_id AND id=e.instruction_id FOR UPDATE;
   IF NOT FOUND OR i.run_version_id<>NEW.run_version_id
   THEN RAISE EXCEPTION 'Payroll instruction and run differ' USING ERRCODE='23514'; END IF;
 END IF;
 PERFORM 1 FROM payroll_period WHERE organization_id=NEW.organization_id AND id=NEW.payroll_period_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Payroll period unavailable' USING ERRCODE='23514'; END IF;
 SELECT * INTO r FROM payroll_run_version WHERE organization_id=NEW.organization_id AND id=NEW.run_version_id;
 SELECT * INTO rel FROM payroll_run_release WHERE organization_id=NEW.organization_id AND id=NEW.release_id;
 SELECT * INTO payment_batch FROM finance_payroll_payment_batch
   WHERE organization_id=NEW.organization_id AND run_version_id=NEW.run_version_id;
 IF r.id IS NULL OR rel.id IS NULL OR payment_batch.id IS NULL
 OR r.payroll_period_id<>NEW.payroll_period_id OR rel.run_version_id<>r.id
 OR rel.run_source_fingerprint<>r.source_fingerprint OR r.currency<>NEW.currency
 OR (i.id IS NOT NULL AND i.batch_id<>payment_batch.id)
 THEN RAISE EXCEPTION 'Exact released and finance-prepared payroll source required' USING ERRCODE='23514'; END IF;
 SELECT * INTO fiscal FROM finance_gl_period WHERE organization_id=NEW.organization_id AND id=NEW.period_id FOR UPDATE;
 IF NOT FOUND OR fiscal.state<>'OPEN'
 OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN fiscal.date_start AND fiscal.date_end
 OR EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=NEW.organization_id AND c.period_id=fiscal.id)
 THEN RAISE EXCEPTION 'Payroll posting requires an open accounting period' USING ERRCODE='23514'; END IF;
 IF NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=NEW.debit_account_id
    AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=NEW.credit_account_id
    AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 THEN RAISE EXCEPTION 'Approved same-currency posting accounts required' USING ERRCODE='23514'; END IF;
 IF NEW.entry_type='PAYROLL_ACCRUAL' THEN
   IF r.gross<=0 OR NEW.amount<>r.gross
     OR NEW.source_recorded_at<>payment_batch.prepared_at
     OR EXISTS(SELECT 1 FROM payroll_run_version newer JOIN payroll_run_release release ON release.run_version_id=newer.id
       WHERE newer.organization_id=r.organization_id AND newer.payroll_period_id=r.payroll_period_id AND newer.generation>r.generation)
     OR NOT finance_payroll_gl_components_complete(r.organization_id,r.id)
     OR NOT finance_payroll_gl_allocations_valid(r.organization_id,r.id,NEW.currency,NEW.component_allocations)
     OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.id=NEW.debit_account_id AND a.account_kind='PAYROLL_EXPENSE')
     OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.id=NEW.credit_account_id AND a.account_kind='PAYROLL_PAYABLE')
   THEN RAISE EXCEPTION 'Accrual requires exact released components and approved accounting treatment' USING ERRCODE='23514'; END IF;
   IF EXISTS(SELECT 1 FROM payroll_run_correction c WHERE c.organization_id=r.organization_id AND c.replacement_run_version_id=r.id) THEN
     SELECT * INTO correction_source FROM finance_payroll_gl_correction_source c
       WHERE c.organization_id=r.organization_id AND c.replacement_run_version_id=r.id;
     IF NOT FOUND OR correction_source.original_journal_id IS DISTINCT FROM NEW.original_journal_id
       OR (correction_source.original_posted_at IS NOT NULL AND correction_source.original_posted_at>NEW.posted_at)
     THEN RAISE EXCEPTION 'Replacement accrual requires exact no-transfer clearance and original journal' USING ERRCODE='23514'; END IF;
   ELSIF NEW.original_journal_id IS NOT NULL OR EXISTS(
     SELECT 1 FROM finance_payroll_gl_journal j WHERE j.organization_id=r.organization_id
       AND j.payroll_period_id=r.payroll_period_id AND j.entry_type='PAYROLL_ACCRUAL')
   THEN RAISE EXCEPTION 'Original payroll accrual may only be posted once' USING ERRCODE='23514'; END IF;
   RETURN NEW;
 END IF;
 IF NEW.component_allocations<>'[]'::jsonb
 THEN RAISE EXCEPTION 'Only accrual can contain component allocations' USING ERRCODE='23514'; END IF;
 SELECT * INTO accrual FROM finance_payroll_gl_journal j WHERE j.organization_id=NEW.organization_id
   AND j.run_version_id=r.id AND j.entry_type='PAYROLL_ACCRUAL';
 IF accrual.id IS NULL OR e.amount<>NEW.amount OR e.currency<>NEW.currency OR e.recorded_at<>NEW.source_recorded_at
 OR NEW.posted_at<accrual.posted_at
 THEN RAISE EXCEPTION 'Payroll settlement requires its posted accrual and exact result' USING ERRCODE='23514'; END IF;
 IF NEW.entry_type='PAYROLL_PAYMENT' THEN
   IF EXISTS(SELECT 1 FROM finance_payroll_gl_journal correction WHERE correction.organization_id=NEW.organization_id
       AND correction.original_journal_id=accrual.id AND correction.entry_type='PAYROLL_ACCRUAL')
   OR e.action<>'SUCCESS' OR e.outcome<>'SUCCESS' OR NEW.debit_account_id<>accrual.credit_account_id
   OR EXISTS(SELECT 1 FROM finance_payroll_result_event v WHERE v.organization_id=e.organization_id AND v.corrects_event_id=e.id)
   OR EXISTS(SELECT 1 FROM finance_payroll_gl_journal j JOIN finance_payroll_result_event older
       ON older.organization_id=j.organization_id AND older.id=j.source_event_id
       WHERE j.organization_id=NEW.organization_id AND j.entry_type='PAYROLL_PAYMENT'
       AND older.instruction_id=e.instruction_id AND older.version<e.version
       AND NOT EXISTS(SELECT 1 FROM finance_payroll_gl_journal reversal WHERE reversal.organization_id=j.organization_id
           AND reversal.original_journal_id=j.id))
   THEN RAISE EXCEPTION 'Payroll payment must be a current successful match without an unreversed prior posting' USING ERRCODE='23514'; END IF;
 ELSIF NEW.entry_type IN('PAYROLL_RETURN','PAYROLL_VOID') THEN
   SELECT * INTO prior FROM finance_payroll_result_event WHERE organization_id=e.organization_id
     AND instruction_id=e.instruction_id AND version=e.version-1;
   SELECT * INTO original FROM finance_payroll_gl_journal WHERE organization_id=NEW.organization_id
     AND id=NEW.original_journal_id AND source_event_id=prior.id AND entry_type='PAYROLL_PAYMENT';
   IF original.id IS NULL OR prior.action<>'SUCCESS' OR original.run_version_id<>r.id
     OR original.amount<>NEW.amount OR NEW.posted_at<original.posted_at
     OR NEW.credit_account_id<>original.debit_account_id
     OR (NEW.entry_type='PAYROLL_RETURN' AND (e.action<>'RETURNED' OR e.outcome<>'RETURNED'))
     OR (NEW.entry_type='PAYROLL_VOID' AND (e.action<>'VOID_MATCH' OR e.outcome<>'UNKNOWN'
       OR e.corrects_event_id IS DISTINCT FROM prior.id OR NEW.debit_account_id<>original.credit_account_id))
   THEN RAISE EXCEPTION 'Payroll return or void must match its exact posted payment' USING ERRCODE='23514'; END IF;
 END IF;
 IF NEW.entry_type<>'PAYROLL_VOID' THEN
   SELECT a.id INTO bank_account FROM finance_bank_statement_intake_line b
     JOIN finance_gl_account a ON a.organization_id=b.organization_id AND a.treasury_bank_account_id=b.account_id
     WHERE b.organization_id=e.organization_id AND b.id=e.bank_statement_line_id
       AND b.amount=e.amount AND b.currency=e.currency AND a.state='APPROVED' AND a.currency=e.currency
       AND b.flow=(CASE WHEN NEW.entry_type='PAYROLL_PAYMENT' THEN 'DEBIT' ELSE 'CREDIT' END);
   IF bank_account IS NULL OR (NEW.entry_type='PAYROLL_PAYMENT' AND NEW.credit_account_id<>bank_account)
     OR (NEW.entry_type='PAYROLL_RETURN' AND NEW.debit_account_id<>bank_account)
   THEN RAISE EXCEPTION 'Exact approved bank ledger account required' USING ERRCODE='23514'; END IF;
 END IF;
 RETURN NEW;
END; $$;

CREATE VIEW finance_payroll_gl_base_line AS
 SELECT j.organization_id,j.id AS journal_id,1 AS line_number,j.debit_account_id AS gl_account_id,'DEBIT'::varchar AS side,j.amount
 FROM finance_payroll_gl_journal j
 UNION ALL
 SELECT j.organization_id,j.id,2,j.credit_account_id,'CREDIT'::varchar,
   (CASE WHEN j.entry_type='PAYROLL_ACCRUAL' THEN r.net ELSE j.amount END)::numeric(24,6)
 FROM finance_payroll_gl_journal j JOIN payroll_run_version r ON r.id=j.run_version_id AND r.organization_id=j.organization_id
 WHERE j.entry_type<>'PAYROLL_ACCRUAL' OR r.net>0
 UNION ALL
 SELECT j.organization_id,j.id,(2+i.ordinality)::integer,(i.value->>'accountId')::uuid,
   (CASE WHEN g.component_kind='DEDUCTION' THEN 'CREDIT' ELSE 'DEBIT' END)::varchar,g.amount::numeric(24,6)
 FROM finance_payroll_gl_journal j CROSS JOIN LATERAL jsonb_array_elements(j.component_allocations) WITH ORDINALITY i(value,ordinality)
 JOIN finance_payroll_gl_component_group g ON g.organization_id=j.organization_id AND g.run_version_id=j.run_version_id
   AND g.component_kind=i.value->>'kind' AND g.source_domain=i.value->>'domain' AND g.component_code=i.value->>'code';

-- Base lines are not recursive: a later correction reverses only the active
-- accrual's frozen obligation, not the already reversed predecessor.
CREATE OR REPLACE VIEW finance_payroll_gl_journal_line AS
 SELECT organization_id,journal_id,line_number,gl_account_id,side,amount FROM finance_payroll_gl_base_line
 UNION ALL
 SELECT j.organization_id,j.id,32+l.line_number,l.gl_account_id,
   (CASE WHEN l.side='DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END)::varchar,l.amount
 FROM finance_payroll_gl_journal j JOIN finance_payroll_gl_base_line l
   ON l.organization_id=j.organization_id AND l.journal_id=j.original_journal_id
 WHERE j.entry_type='PAYROLL_ACCRUAL';
