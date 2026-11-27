-- Posted accounting effects of recorded AP/AR settlements. No bank transfers.
-- Each treasury account belongs to one explicitly approved bank or cashbox.
ALTER TABLE finance_gl_account DROP CONSTRAINT finance_gl_account_account_kind_check;
ALTER TABLE finance_gl_account
 ADD CONSTRAINT finance_gl_account_account_kind_check CHECK(account_kind IN(
 'SUPPLIER_PAYABLE','EXPENSE','INVENTORY_ASSET','FIXED_ASSET','TAX_RECOVERABLE',
 'OTHER_ASSET','CUSTOMER_RECEIVABLE','SALES_REVENUE','TAX_PAYABLE','BANK_ASSET','CASH_ASSET')),
 ADD COLUMN treasury_bank_account_id uuid,
 ADD COLUMN treasury_cashbox_id uuid,
 ADD CONSTRAINT finance_gl_treasury_kind_check CHECK(
    (account_kind='BANK_ASSET' AND treasury_bank_account_id IS NOT NULL AND treasury_cashbox_id IS NULL AND currency IS NOT NULL)
 OR (account_kind='CASH_ASSET' AND treasury_cashbox_id IS NOT NULL AND treasury_bank_account_id IS NULL AND currency IS NOT NULL)
 OR (account_kind NOT IN('BANK_ASSET','CASH_ASSET') AND treasury_bank_account_id IS NULL AND treasury_cashbox_id IS NULL)),
 ADD CONSTRAINT finance_gl_treasury_bank_fk FOREIGN KEY(organization_id,treasury_bank_account_id)
    REFERENCES finance_bank_account_register(organization_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT finance_gl_treasury_cashbox_fk FOREIGN KEY(organization_id,treasury_cashbox_id)
    REFERENCES finance_cashbox_register(organization_id,id) ON DELETE RESTRICT,
 ADD CONSTRAINT finance_gl_treasury_bank_unique UNIQUE(organization_id,treasury_bank_account_id),
 ADD CONSTRAINT finance_gl_treasury_cashbox_unique UNIQUE(organization_id,treasury_cashbox_id);

CREATE FUNCTION finance_gl_treasury_account_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.account_kind='BANK_ASSET' AND NOT EXISTS(
    SELECT 1 FROM finance_bank_account_register b WHERE b.organization_id=NEW.organization_id
    AND b.id=NEW.treasury_bank_account_id AND b.currency=NEW.currency AND b.state='APPROVED')
 OR NEW.account_kind='CASH_ASSET' AND NOT EXISTS(
    SELECT 1 FROM finance_cashbox_register b WHERE b.organization_id=NEW.organization_id
    AND b.id=NEW.treasury_cashbox_id AND b.currency=NEW.currency AND b.status='ACTIVE')
 THEN RAISE EXCEPTION 'Treasury account requires its actual approved same-currency instrument' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_gl_treasury_account_guard BEFORE INSERT ON finance_gl_account
 FOR EACH ROW EXECUTE FUNCTION finance_gl_treasury_account_guard();

-- Do not let an AP invoice expense allocation post directly to cash/bank.
CREATE FUNCTION finance_ap_no_treasury_allocation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
    AND a.id=NEW.gl_account_id AND a.account_kind IN('BANK_ASSET','CASH_ASSET'))
 THEN RAISE EXCEPTION 'Use the recorded settlement source for a treasury posting' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_ap_no_treasury_allocation BEFORE INSERT ON finance_ap_posting_proposal_line
 FOR EACH ROW EXECUTE FUNCTION finance_ap_no_treasury_allocation();

CREATE TABLE finance_settlement_gl_journal (
 id uuid PRIMARY KEY,
 operation_id uuid NOT NULL UNIQUE,
 organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
 source_type varchar(24) NOT NULL CHECK(source_type IN('BANK_ALLOCATION','CASH_VOUCHER','BANK_VOID')),
 source_id uuid NOT NULL,
 source_kind varchar(2) NOT NULL CHECK(source_kind IN('AP','AR')),
 source_journal_id uuid NOT NULL,
 source_approval_id uuid NOT NULL REFERENCES approval_request(id) ON DELETE RESTRICT,
 original_settlement_id uuid,
 period_id uuid NOT NULL,
 debit_account_id uuid NOT NULL,
 credit_account_id uuid NOT NULL,
 amount numeric(24,6) NOT NULL CHECK(amount>0),
 currency char(3) NOT NULL CHECK(currency ~ '^[A-Z]{3}$'),
 source_recorded_at timestamptz NOT NULL,
 posted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
 posted_at timestamptz NOT NULL,
 correlation_id varchar(128) NOT NULL CHECK(length(btrim(correlation_id))>0),
 UNIQUE(organization_id,id),
 UNIQUE(organization_id,source_type,source_id),
 UNIQUE(organization_id,original_settlement_id),
 FOREIGN KEY(organization_id,original_settlement_id) REFERENCES finance_settlement_gl_journal(organization_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(organization_id,period_id) REFERENCES finance_gl_period(organization_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(organization_id,debit_account_id) REFERENCES finance_gl_account(organization_id,id) ON DELETE RESTRICT,
 FOREIGN KEY(organization_id,credit_account_id) REFERENCES finance_gl_account(organization_id,id) ON DELETE RESTRICT,
 CHECK(debit_account_id<>credit_account_id),
 CHECK(posted_at>=source_recorded_at),
 CHECK((source_type='BANK_VOID')=(original_settlement_id IS NOT NULL))
);
CREATE INDEX finance_settlement_gl_period_idx ON finance_settlement_gl_journal(organization_id,period_id,posted_at,id);
CREATE TRIGGER finance_settlement_gl_immutable BEFORE UPDATE OR DELETE ON finance_settlement_gl_journal
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();

-- This is a source projection, not a duplicate payment register. A match
-- voided before any GL posting needs no GL reversal; a posted match does.
CREATE VIEW finance_settlement_gl_source AS
WITH movements AS (
 SELECT r.organization_id,'BANK_ALLOCATION'::varchar AS source_type,r.id AS source_id,r.source_kind,
    r.source_journal_id,r.approval_request_id AS source_approval_id,r.allocated_amount AS amount,r.currency,
    r.recorded_at AS source_recorded_at,'BANK'::varchar AS instrument_kind,b.account_id AS instrument_id,
    a.bank_name || ' · ' || a.masked_account_reference AS instrument_name,
    NULL::uuid AS original_settlement_id,NULL::uuid AS reverse_debit,NULL::uuid AS reverse_credit
 FROM finance_cash_allocation_effective r
 JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
 JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
 UNION ALL
 SELECT r.organization_id,'CASH_VOUCHER',r.id,r.source_kind,r.source_journal_id,r.approval_request_id,
    r.amount,r.currency,r.recorded_at,'CASH',r.cashbox_id,b.cashbox_code,NULL::uuid,NULL::uuid,NULL::uuid
 FROM finance_cash_voucher_record r
 JOIN finance_cashbox_register b ON b.organization_id=r.organization_id AND b.id=r.cashbox_id
 UNION ALL
 SELECT v.organization_id,'BANK_VOID',v.id,v.source_kind,v.source_journal_id,v.approval_request_id,
    v.amount,v.currency,v.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
    o.id,o.credit_account_id,o.debit_account_id
 FROM finance_cash_allocation_void_record v
 JOIN finance_settlement_gl_journal o ON o.organization_id=v.organization_id
    AND o.source_type='BANK_ALLOCATION' AND o.source_id=v.original_record_id
 JOIN finance_bank_statement_intake_line b ON b.organization_id=v.organization_id AND b.id=v.bank_statement_line_id
 JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
), controls AS (
 SELECT j.organization_id,'AP'::varchar AS source_kind,j.id AS source_journal_id,
    l.gl_account_id AS control_account_id,p.supplier_organization_id AS counterparty_id
 FROM finance_ap_posted_journal j JOIN finance_ap_posted_journal_line l
    ON l.organization_id=j.organization_id AND l.journal_id=j.id AND l.side='CREDIT'
 JOIN finance_gl_account a ON a.organization_id=l.organization_id AND a.id=l.gl_account_id
    AND a.account_kind='SUPPLIER_PAYABLE'
 JOIN finance_ap_posting_proposal p ON p.organization_id=j.organization_id AND p.id=j.proposal_id
 UNION ALL
 SELECT j.organization_id,'AR',j.id,l.gl_account_id,p.customer_organization_id
 FROM finance_ar_posted_journal j JOIN finance_ar_posted_journal_line l
    ON l.organization_id=j.organization_id AND l.journal_id=j.id AND l.side='DEBIT'
 JOIN finance_gl_account a ON a.organization_id=l.organization_id AND a.id=l.gl_account_id
    AND a.account_kind='CUSTOMER_RECEIVABLE'
 JOIN finance_ar_issued_invoice p ON p.organization_id=j.organization_id AND p.id=j.issued_invoice_id
)
SELECT m.organization_id,m.source_type,m.source_id,m.source_kind,m.source_journal_id,m.source_approval_id,
 m.amount,m.currency,m.source_recorded_at,m.instrument_kind,m.instrument_id,m.instrument_name,
 c.counterparty_id,o.display_name AS counterparty_name,m.original_settlement_id,
 CASE WHEN m.source_type='BANK_VOID' THEN m.reverse_debit
      WHEN m.source_kind='AR' THEN a.id ELSE c.control_account_id END AS debit_account_id,
 CASE WHEN m.source_type='BANK_VOID' THEN m.reverse_credit
      WHEN m.source_kind='AR' THEN c.control_account_id ELSE a.id END AS credit_account_id
FROM movements m
LEFT JOIN controls c ON c.organization_id=m.organization_id AND c.source_kind=m.source_kind
    AND c.source_journal_id=m.source_journal_id
LEFT JOIN organization o ON o.id=c.counterparty_id
LEFT JOIN finance_gl_account a ON a.organization_id=m.organization_id AND a.currency=m.currency AND a.state='APPROVED'
    AND ((m.instrument_kind='BANK' AND a.treasury_bank_account_id=m.instrument_id)
      OR (m.instrument_kind='CASH' AND a.treasury_cashbox_id=m.instrument_id));

CREATE FUNCTION finance_settlement_gl_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE s finance_settlement_gl_source%ROWTYPE;
        p finance_gl_period%ROWTYPE;
        original_id uuid;
BEGIN
 -- Serialize with an allocation correction, which locks the original match
 -- before the period. Closing locks the same period row as every GL writer.
 IF NEW.source_type='BANK_ALLOCATION' THEN
   PERFORM id FROM finance_cash_allocation_record WHERE organization_id=NEW.organization_id AND id=NEW.source_id FOR UPDATE;
 ELSIF NEW.source_type='BANK_VOID' THEN
   SELECT original_record_id INTO original_id FROM finance_cash_allocation_void_record
     WHERE organization_id=NEW.organization_id AND id=NEW.source_id;
   PERFORM id FROM finance_cash_allocation_record WHERE organization_id=NEW.organization_id AND id=original_id FOR UPDATE;
 END IF;
 SELECT * INTO p FROM finance_gl_period WHERE organization_id=NEW.organization_id AND id=NEW.period_id FOR UPDATE;
 IF NOT FOUND OR p.state<>'OPEN' OR (NEW.posted_at AT TIME ZONE 'UTC')::date NOT BETWEEN p.date_start AND p.date_end
 OR EXISTS(SELECT 1 FROM finance_gl_period_close c WHERE c.organization_id=NEW.organization_id AND c.period_id=p.id)
 THEN RAISE EXCEPTION 'Settlement requires a current open accounting period' USING ERRCODE='23514'; END IF;
 SELECT * INTO s FROM finance_settlement_gl_source WHERE organization_id=NEW.organization_id
    AND source_type=NEW.source_type AND source_id=NEW.source_id;
 IF NOT FOUND OR s.source_kind IS DISTINCT FROM NEW.source_kind
 OR s.source_journal_id IS DISTINCT FROM NEW.source_journal_id
 OR s.source_approval_id IS DISTINCT FROM NEW.source_approval_id
 OR s.original_settlement_id IS DISTINCT FROM NEW.original_settlement_id
 OR s.debit_account_id IS DISTINCT FROM NEW.debit_account_id
 OR s.credit_account_id IS DISTINCT FROM NEW.credit_account_id
 OR s.amount IS DISTINCT FROM NEW.amount OR s.currency IS DISTINCT FROM NEW.currency
 OR s.source_recorded_at IS DISTINCT FROM NEW.source_recorded_at
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
     AND a.id=NEW.debit_account_id AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 OR NOT EXISTS(SELECT 1 FROM finance_gl_account a WHERE a.organization_id=NEW.organization_id
     AND a.id=NEW.credit_account_id AND a.state='APPROVED' AND (a.currency IS NULL OR a.currency=NEW.currency))
 THEN RAISE EXCEPTION 'Settlement must match exact recorded source and approved accounts' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_settlement_gl_guard BEFORE INSERT ON finance_settlement_gl_journal
 FOR EACH ROW EXECUTE FUNCTION finance_settlement_gl_guard();

CREATE VIEW finance_settlement_gl_journal_line AS
 SELECT organization_id,id AS journal_id,1 AS line_number,debit_account_id AS gl_account_id,'DEBIT'::varchar AS side,amount
 FROM finance_settlement_gl_journal
 UNION ALL
 SELECT organization_id,id,2,credit_account_id,'CREDIT',amount FROM finance_settlement_gl_journal;

-- Preserve the exact historical V3 snapshot when there are no settlement
-- journals. New posted sources participate in the same closure fingerprint.
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
    COALESCE(s.original_settlement_id::text,'-'))
 FROM finance_settlement_gl_journal s WHERE s.organization_id=p.organization_id AND s.period_id=p.id
 ) x
) e;

CREATE FUNCTION finance_gl_close_settlement_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF EXISTS(SELECT 1 FROM finance_settlement_gl_journal j WHERE j.organization_id=NEW.organization_id
    AND j.period_id=NEW.period_id AND j.posted_at>NEW.closed_at)
 THEN RAISE EXCEPTION 'Closure cannot precede a posted settlement' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_gl_close_settlement_guard BEFORE INSERT ON finance_gl_period_close
 FOR EACH ROW EXECUTE FUNCTION finance_gl_close_settlement_guard();
