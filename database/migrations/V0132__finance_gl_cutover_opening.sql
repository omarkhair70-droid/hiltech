-- Initial ledger cutover, not a second cash, customer or supplier subledger.
-- Non-control accounts use a company-attested source schedule. Bank amounts
-- must equal the unique approved bank statement opening on the cutover date.
-- No automatic balancing, inferred equity, payment or expense is created.
ALTER TABLE finance_gl_account DROP CONSTRAINT finance_gl_account_account_kind_check;
ALTER TABLE finance_gl_account ADD CONSTRAINT finance_gl_account_account_kind_check CHECK(account_kind IN(
 'SUPPLIER_PAYABLE','EXPENSE','INVENTORY_ASSET','FIXED_ASSET','TAX_RECOVERABLE',
 'OTHER_ASSET','CUSTOMER_RECEIVABLE','SALES_REVENUE','TAX_PAYABLE','BANK_ASSET','CASH_ASSET',
 'EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET','PAYROLL_EXPENSE','PAYROLL_PAYABLE','PAYROLL_DEDUCTION_PAYABLE','EQUITY','OTHER_LIABILITY'));

CREATE OR REPLACE FUNCTION finance_ap_no_treasury_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
    AND a.id=NEW.gl_account_id AND a.account_kind IN('BANK_ASSET','CASH_ASSET',
        'EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET','PAYROLL_EXPENSE','PAYROLL_PAYABLE','PAYROLL_DEDUCTION_PAYABLE','EQUITY','OTHER_LIABILITY'))
 THEN RAISE EXCEPTION 'Use the recorded settlement, employee or payroll source for this posting' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

CREATE TABLE finance_gl_opening_journal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 period_id uuid NOT NULL,
 opening_date date NOT NULL,
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 company_reference varchar(160) NOT NULL CHECK(length(btrim(company_reference))>=8),
 source_reference varchar(240) NOT NULL CHECK(length(btrim(source_reference))>=8),
 lines jsonb NOT NULL CHECK(jsonb_typeof(lines)='array' AND jsonb_array_length(lines) BETWEEN 2 AND 32),
 posted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 posted_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),UNIQUE(organization_id,currency),
 FOREIGN KEY(organization_id,period_id) REFERENCES finance_gl_period(organization_id,id) ON DELETE RESTRICT
);
CREATE TRIGGER finance_gl_opening_immutable BEFORE UPDATE OR DELETE ON finance_gl_opening_journal
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

CREATE FUNCTION finance_gl_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fiscal finance_gl_period%ROWTYPE; item jsonb; account finance_gl_account%ROWTYPE;
 bank finance_bank_statement_intake%ROWTYPE; bank_count int; amount numeric; debit numeric:=0; credit numeric:=0;
 seen uuid[]:=ARRAY[]::uuid[];
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id AND status='ACTIVE' AND organization_type='HILTECH' FOR UPDATE;
 IF NOT FOUND OR NOT EXISTS(SELECT 1 FROM organization_membership m JOIN user_identity u ON u.id=m.user_identity_id
    WHERE m.organization_id=NEW.organization_id AND u.id=NEW.posted_by_user_id AND u.status='ACTIVE' AND m.state='ACTIVE'
      AND m.valid_from<=NEW.posted_at AND (m.valid_until IS NULL OR m.valid_until>NEW.posted_at))
 THEN RAISE EXCEPTION 'Current company membership required' USING ERRCODE='23514'; END IF;
 SELECT * INTO fiscal FROM finance_gl_period WHERE organization_id=NEW.organization_id AND id=NEW.period_id FOR UPDATE;
 IF NOT FOUND OR fiscal.state<>'OPEN' OR fiscal.date_start<>NEW.opening_date
    OR NEW.opening_date>(NEW.posted_at AT TIME ZONE 'UTC')::date
    OR EXISTS(SELECT 1 FROM finance_gl_period p WHERE p.organization_id=NEW.organization_id AND p.id<>fiscal.id
        AND p.date_start<=fiscal.date_end)
    OR EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=NEW.organization_id AND c.period_id=fiscal.id)
 THEN RAISE EXCEPTION 'Opening requires the first unclosed non-overlapping period' USING ERRCODE='23514'; END IF;
 IF jsonb_typeof(NEW.lines)<>'array' OR jsonb_array_length(NEW.lines) NOT BETWEEN 2 AND 32
 THEN RAISE EXCEPTION 'Opening requires a complete balanced schedule' USING ERRCODE='23514'; END IF;
 FOR item IN SELECT value FROM jsonb_array_elements(NEW.lines) LOOP
   IF jsonb_typeof(item)<>'object' OR NOT (item ?& ARRAY['accountId','side','amount','reference'])
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN('accountId','side','amount','reference','bankStatementId'))
      OR EXISTS(SELECT 1 FROM jsonb_each(item) v WHERE jsonb_typeof(v.value)<>'string')
      OR item->>'accountId' !~ '^[0-9a-fA-F-]{36}$'
      OR item->>'side' NOT IN('DEBIT','CREDIT')
      OR item->>'amount' !~ '^[0-9]{1,18}([.][0-9]{1,6})?$'
      OR length(btrim(item->>'reference')) NOT BETWEEN 8 AND 240
      OR item->>'reference'<>btrim(item->>'reference') OR item->>'reference' ~ '[[:cntrl:]]'
   THEN RAISE EXCEPTION 'Invalid opening line' USING ERRCODE='23514'; END IF;
   amount:=(item->>'amount')::numeric;
   SELECT * INTO account FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id AND a.id=(item->>'accountId')::uuid;
   IF account.id IS NULL OR account.id=ANY(seen) OR account.state<>'APPROVED' OR amount<=0
     OR (account.currency IS NOT NULL AND account.currency<>NEW.currency)
     OR account.account_kind NOT IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET')
   THEN RAISE EXCEPTION 'Unique approved supported opening accounts required' USING ERRCODE='23514'; END IF;
   seen:=array_append(seen,account.id);
   IF account.account_kind='BANK_ASSET' THEN
     IF NOT (item ? 'bankStatementId') OR item->>'bankStatementId' !~ '^[0-9a-fA-F-]{36}$'
     THEN RAISE EXCEPTION 'Exact approved bank statement required' USING ERRCODE='23514'; END IF;
     SELECT count(*) INTO bank_count FROM finance_bank_statement_intake s WHERE s.organization_id=NEW.organization_id
       AND s.account_id=account.treasury_bank_account_id AND s.date_start=NEW.opening_date AND s.currency=NEW.currency;
     SELECT * INTO bank FROM finance_bank_statement_intake s WHERE s.organization_id=NEW.organization_id
       AND s.id=(item->>'bankStatementId')::uuid AND s.account_id=account.treasury_bank_account_id
       AND s.date_start=NEW.opening_date AND s.currency=NEW.currency;
     IF bank_count<>1 OR bank.id IS NULL OR bank.opening_balance=0 OR bank.imported_at>NEW.posted_at
       OR bank.opening_balance<>(CASE WHEN item->>'side'='DEBIT' THEN amount ELSE -amount END)
     THEN RAISE EXCEPTION 'Bank opening must equal its unique approved statement' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'bankStatementId' THEN
     RAISE EXCEPTION 'Bank evidence cannot support an unrelated account' USING ERRCODE='23514';
   END IF;
   IF item->>'side'='DEBIT' THEN debit:=debit+amount; ELSE credit:=credit+amount; END IF;
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_gl_opening_guard BEFORE INSERT ON finance_gl_opening_journal
 FOR EACH ROW EXECUTE FUNCTION finance_gl_opening_guard();

CREATE FUNCTION finance_gl_opening_calendar_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM finance_gl_opening_journal j JOIN finance_gl_period p ON p.organization_id=j.organization_id AND p.id=j.period_id
    WHERE j.organization_id=NEW.organization_id AND NEW.date_start<=p.date_end)
 THEN RAISE EXCEPTION 'Cannot insert an earlier or overlapping period after recorded cutover' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_gl_opening_calendar_guard BEFORE INSERT ON finance_gl_period
 FOR EACH ROW EXECUTE FUNCTION finance_gl_opening_calendar_guard();

CREATE VIEW finance_gl_opening_journal_line AS
 SELECT j.organization_id,j.id AS journal_id,i.ordinality::integer AS line_number,
   (i.value->>'accountId')::uuid AS gl_account_id,i.value->>'side' AS side,(i.value->>'amount')::numeric(24,6) AS amount
 FROM finance_gl_opening_journal j CROSS JOIN LATERAL jsonb_array_elements(j.lines) WITH ORDINALITY i(value,ordinality);

CREATE OR REPLACE VIEW finance_gl_period_close_source AS
SELECT p.id AS period_id,p.organization_id,p.date_start,p.date_end,p.state,e.journal_count,
 encode(sha256(convert_to(concat_ws('|','FINANCE_GL_PERIOD_CLOSE_SOURCE_V3',
    p.id::text,p.organization_id::text,p.date_start::text,p.date_end::text,p.state,
    e.journal_count::text,COALESCE(e.event_fingerprints,'EMPTY')),'UTF8')),'hex') AS snapshot_sha256
FROM finance_gl_period p CROSS JOIN LATERAL (
 SELECT COUNT(*)::bigint AS journal_count,string_agg(x.fingerprint,'|' ORDER BY x.fingerprint) AS event_fingerprints
 FROM (
 SELECT concat_ws(':','AP',j.id::text,j.payable_candidate_id::text,j.gross_amount::text,j.currency,j.ledger_fingerprint_sha256) AS fingerprint
 FROM finance_ap_posted_journal j WHERE j.organization_id=p.organization_id AND j.fiscal_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','AP_REV',r.id::text,r.original_journal_id::text,r.gross_amount::text,r.currency,r.source_ledger_fingerprint_sha256)
 FROM finance_ap_journal_reversal r WHERE r.organization_id=p.organization_id AND r.target_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','AR',a.id::text,a.issued_invoice_id::text,a.gross_amount::text,a.currency,a.ledger_fingerprint_sha256)
 FROM finance_ar_posted_journal a WHERE a.organization_id=p.organization_id AND a.fiscal_period_id=p.id
 UNION ALL
 SELECT concat_ws(':','SETTLEMENT',s.id::text,s.source_type,s.source_id::text,s.source_journal_id::text,
    s.source_approval_id::text,s.debit_account_id::text,s.credit_account_id::text,s.amount::text,s.currency,
    COALESCE(s.original_settlement_id::text,'-'),
    s.employee_funding_record_id::text,s.expense_claim_id::text,s.accounting_treatment_ref)
 FROM finance_settlement_gl_journal s WHERE s.organization_id=p.organization_id AND s.period_id=p.id
 UNION ALL
 SELECT concat_ws(':','PAYROLL_GL',j.id::text,j.entry_type,j.run_version_id::text,j.release_id::text,
    j.source_event_id::text,j.original_journal_id::text,j.debit_account_id::text,j.credit_account_id::text,
    j.amount::text,j.currency,extract(epoch FROM j.source_recorded_at)::text,
    CASE WHEN j.component_allocations<>'[]'::jsonb THEN encode(sha256(convert_to(j.component_allocations::text,'UTF8')),'hex') END)
 FROM finance_payroll_gl_journal j WHERE j.organization_id=p.organization_id AND j.period_id=p.id
 UNION ALL
 SELECT concat_ws(':','OPENING_BALANCE',j.id::text,j.currency,j.opening_date::text,
    encode(sha256(convert_to(jsonb_build_array(j.company_reference,j.source_reference,j.lines)::text,'UTF8')),'hex'))
 FROM finance_gl_opening_journal j WHERE j.organization_id=p.organization_id AND j.period_id=p.id
 ) x
) e;

CREATE FUNCTION finance_gl_close_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_gl_opening_journal j WHERE j.organization_id=NEW.organization_id
    AND j.period_id=NEW.period_id AND j.posted_at>NEW.closed_at)
 THEN RAISE EXCEPTION 'Closure cannot precede a posted opening journal' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_gl_close_opening_guard BEFORE INSERT ON finance_gl_period_close
 FOR EACH ROW EXECUTE FUNCTION finance_gl_close_opening_guard();
