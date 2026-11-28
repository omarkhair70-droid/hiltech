-- Cutover AP/AR obligations are the remaining historical amounts, not
-- new invoices, tax, revenue or expense. The opening journal is immutable;
-- individual obligations are a projection of its sealed source schedule.
CREATE VIEW finance_opening_counterparty AS
 SELECT DISTINCT organization_id,'AR'::varchar(2) AS source_kind,client_organization_id AS counterparty_id
 FROM commercial_client_relationship WHERE organization_id<>client_organization_id
 UNION
 SELECT organization_id,'AP'::varchar(2),supplier_organization_id
 FROM procurement_supplier_link_event
 UNION
 SELECT organization_id,'AP'::varchar(2),supplier_organization_id
 FROM procurement_supplier_profile;

CREATE VIEW finance_gl_opening_obligation AS
 SELECT j.organization_id,
   md5(j.id::text||':'||l.ordinality::text||':'||o.ordinality::text)::uuid AS id,
   j.id AS opening_journal_id,j.period_id AS fiscal_period_id,j.opening_date,j.posted_at,j.currency,
   CASE WHEN a.account_kind='CUSTOMER_RECEIVABLE' THEN 'AR'::varchar(2) ELSE 'AP'::varchar(2) END AS source_kind,
   a.id AS control_account_id,(o.value->>'counterpartyId')::uuid AS counterparty_id,
   o.value->>'documentReference' AS document_reference,(o.value->>'documentDate')::date AS document_date,
   (o.value->>'dueDate')::date AS contractual_due_date,(o.value->>'amount')::numeric(24,6) AS gross_amount,
   encode(sha256(convert_to(jsonb_build_array(j.id,a.id,j.currency,j.opening_date,o.value)::text,'UTF8')),'hex')::char(64) AS ledger_fingerprint_sha256
 FROM finance_gl_opening_journal j CROSS JOIN LATERAL jsonb_array_elements(j.lines) WITH ORDINALITY l(value,ordinality)
 JOIN finance_gl_account a ON a.organization_id=j.organization_id AND a.id=(l.value->>'accountId')::uuid
 CROSS JOIN LATERAL jsonb_array_elements(coalesce(l.value->'obligations','[]'::jsonb)) WITH ORDINALITY o(value,ordinality)
 WHERE a.account_kind IN('CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE');

CREATE OR REPLACE FUNCTION finance_gl_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fiscal finance_gl_period%ROWTYPE; item jsonb; account finance_gl_account%ROWTYPE;
 bank finance_bank_statement_intake%ROWTYPE; bank_count int; amount numeric; debit numeric:=0; credit numeric:=0;
 seen uuid[]:=ARRAY[]::uuid[];
 obligation jsonb; obligation_total numeric; obligation_key text; obligation_kind varchar(2);
 seen_obligations text[]:=ARRAY[]::text[]; obligation_count int:=0;
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
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN('accountId','side','amount','reference','bankStatementId','obligations'))
      OR EXISTS(SELECT 1 FROM jsonb_each(item) v WHERE v.key<>'obligations' AND jsonb_typeof(v.value)<>'string')
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
     OR account.account_kind NOT IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET','CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE')
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
   IF account.account_kind IN('CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE') THEN
     obligation_kind:=CASE WHEN account.account_kind='CUSTOMER_RECEIVABLE' THEN 'AR' ELSE 'AP' END;
     IF item->>'side'<>(CASE WHEN obligation_kind='AR' THEN 'DEBIT' ELSE 'CREDIT' END)
        OR NOT (item ? 'obligations') OR jsonb_typeof(item->'obligations')<>'array'
        OR jsonb_array_length(item->'obligations')=0
     THEN RAISE EXCEPTION 'Control opening requires its complete individual obligations' USING ERRCODE='23514'; END IF;
     obligation_count:=obligation_count+jsonb_array_length(item->'obligations');
     IF obligation_count>1000 THEN RAISE EXCEPTION 'Opening obligation payload exceeds supported size' USING ERRCODE='23514'; END IF;
     obligation_total:=0;
     FOR obligation IN SELECT value FROM jsonb_array_elements(item->'obligations') LOOP
       IF jsonb_typeof(obligation)<>'object'
         OR NOT (obligation ?& ARRAY['counterpartyId','documentReference','documentDate','amount'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(obligation) k WHERE k NOT IN('counterpartyId','documentReference','documentDate','dueDate','amount'))
         OR EXISTS(SELECT 1 FROM jsonb_each(obligation) v WHERE jsonb_typeof(v.value)<>'string')
         OR obligation->>'counterpartyId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
         OR length(btrim(obligation->>'documentReference')) NOT BETWEEN 2 AND 96
         OR obligation->>'documentReference'<>btrim(obligation->>'documentReference')
         OR obligation->>'documentReference' ~ '[[:cntrl:]]'
         OR obligation->>'documentDate' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
         OR (obligation->>'documentDate')::date>=NEW.opening_date
         OR (obligation ? 'dueDate' AND (obligation->>'dueDate' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
              OR (obligation->>'dueDate')::date<(obligation->>'documentDate')::date))
         OR obligation->>'amount' !~ '^[0-9]{1,18}([.][0-9]{1,6})?
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
         OR (obligation->>'amount')::numeric<=0
         OR NOT EXISTS(SELECT 1 FROM finance_opening_counterparty c WHERE c.organization_id=NEW.organization_id
             AND c.source_kind=obligation_kind AND c.counterparty_id=(obligation->>'counterpartyId')::uuid)
       THEN RAISE EXCEPTION 'Opening obligation must identify a company counterparty, historical document and positive residual' USING ERRCODE='23514'; END IF;
       obligation_key:=obligation_kind||':'||((obligation->>'counterpartyId')::uuid)::text||':'||lower(obligation->>'documentReference');
       IF obligation_key=ANY(seen_obligations)
         OR EXISTS(SELECT 1 FROM finance_gl_opening_obligation o WHERE o.organization_id=NEW.organization_id
              AND o.source_kind=obligation_kind AND o.counterparty_id=(obligation->>'counterpartyId')::uuid
              AND lower(o.document_reference)=lower(obligation->>'documentReference'))
         OR (obligation_kind='AR' AND EXISTS(SELECT 1 FROM finance_ar_issued_invoice i
              WHERE i.organization_id=NEW.organization_id AND i.customer_organization_id=(obligation->>'counterpartyId')::uuid
              AND lower(btrim(i.invoice_number))=lower(obligation->>'documentReference')))
         OR (obligation_kind='AP' AND EXISTS(SELECT 1 FROM procurement_supplier_invoice_intake i
              WHERE i.organization_id=NEW.organization_id AND i.supplier_organization_id=(obligation->>'counterpartyId')::uuid
              AND lower(btrim(i.invoice_number))=lower(obligation->>'documentReference')))
       THEN RAISE EXCEPTION 'Historical document is already registered' USING ERRCODE='23514'; END IF;
       seen_obligations:=array_append(seen_obligations,obligation_key);
       obligation_total:=obligation_total+(obligation->>'amount')::numeric;
     END LOOP;
     IF obligation_total<>amount THEN RAISE EXCEPTION 'Opening control account must equal its individual obligations' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'obligations' THEN
     RAISE EXCEPTION 'Only receivable and payable control accounts can contain obligations' USING ERRCODE='23514';
   END IF;
   IF item->>'side'='DEBIT' THEN debit:=debit+amount; ELSE credit:=credit+amount; END IF;
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

-- Serialize historical document import against subsequent normal invoice
-- intake/issuance. Neither workflow can register the same known reference.
CREATE FUNCTION finance_opening_invoice_duplicate_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE kind varchar(2); counterparty uuid;
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id FOR UPDATE;
 IF TG_TABLE_NAME='finance_ar_issued_invoice' THEN
   kind:='AR';counterparty:=NEW.customer_organization_id;
 ELSE
   kind:='AP';counterparty:=NEW.supplier_organization_id;
 END IF;
 IF EXISTS(SELECT 1 FROM finance_gl_opening_obligation o WHERE o.organization_id=NEW.organization_id
     AND o.source_kind=kind AND o.counterparty_id=counterparty
     AND lower(o.document_reference)=lower(btrim(NEW.invoice_number)))
 THEN RAISE EXCEPTION 'Document is already carried as an opening obligation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_opening_ar_duplicate BEFORE INSERT ON finance_ar_issued_invoice
 FOR EACH ROW EXECUTE FUNCTION finance_opening_invoice_duplicate_guard();
CREATE TRIGGER finance_opening_ap_duplicate BEFORE INSERT ON procurement_supplier_invoice_intake
 FOR EACH ROW EXECUTE FUNCTION finance_opening_invoice_duplicate_guard();

-- The same settlement source contract works for both current journals and
-- opening residuals, without inserting synthetic journal/invoice rows.
CREATE VIEW finance_obligation_source AS
 SELECT organization_id,'AP'::varchar(2) AS source_kind,id,fiscal_period_id,gross_amount,currency,ledger_fingerprint_sha256
 FROM finance_ap_posted_journal
 UNION ALL
 SELECT organization_id,'AR'::varchar(2),id,fiscal_period_id,gross_amount,currency,ledger_fingerprint_sha256
 FROM finance_ar_posted_journal
 UNION ALL
 SELECT organization_id,source_kind,id,fiscal_period_id,gross_amount,currency,ledger_fingerprint_sha256
 FROM finance_gl_opening_obligation;

CREATE FUNCTION finance_lock_obligation_source(org uuid,kind varchar,source uuid) RETURNS SETOF finance_obligation_source
LANGUAGE plpgsql AS $$
BEGIN
 IF kind='AP' THEN
   PERFORM 1 FROM finance_ap_posted_journal j WHERE j.organization_id=org AND j.id=source FOR UPDATE;
 ELSIF kind='AR' THEN
   PERFORM 1 FROM finance_ar_posted_journal j WHERE j.organization_id=org AND j.id=source FOR UPDATE;
 ELSE RETURN;
 END IF;
 IF NOT FOUND THEN
   PERFORM 1 FROM finance_gl_opening_obligation o JOIN finance_gl_opening_journal j
      ON j.organization_id=o.organization_id AND j.id=o.opening_journal_id
   WHERE o.organization_id=org AND o.source_kind=kind AND o.id=source FOR UPDATE OF j;
   IF NOT FOUND THEN RETURN; END IF;
 END IF;
 RETURN QUERY SELECT s.* FROM finance_obligation_source s WHERE s.organization_id=org AND s.source_kind=kind AND s.id=source;
END; $$;

CREATE OR REPLACE FUNCTION finance_cash_allocation_record_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_allocation_proposal%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        source_amount numeric(24,6);
        source_currency char(3);
        source_hash char(64);
        old_source_total numeric(24,6);
        old_bank_total numeric(24,6);
        old_cash_total numeric;
        source_matched uuid;
        evidence finance_bank_statement_intake%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_cash_allocation_proposal
   WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.source_kind<>NEW.source_kind
    OR p.source_journal_id<>NEW.source_journal_id
    OR p.bank_statement_line_id<>NEW.bank_statement_line_id
    OR p.currency<>NEW.currency OR p.allocated_amount<>NEW.allocated_amount
    OR p.approval_subject_version<>NEW.approval_subject_version
    OR p.policy_key<>NEW.policy_key
    OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Cash allocation must exactly match prepared immutable proposal'
   USING ERRCODE='23514';
 END IF;
  SELECT j.id,j.gross_amount,j.currency,j.ledger_fingerprint_sha256
 INTO source_matched,source_amount,source_currency,source_hash
 FROM finance_lock_obligation_source(NEW.organization_id,NEW.source_kind,NEW.source_journal_id) j;
 IF NEW.source_kind='AP' AND EXISTS(
   SELECT 1 FROM finance_ap_journal_reversal r
   WHERE r.organization_id=NEW.organization_id
     AND r.original_journal_id=NEW.source_journal_id
 ) THEN
   RAISE EXCEPTION 'Reversed AP cannot be linked to new bank payment'
     USING ERRCODE='23514';
 END IF;
 IF source_matched IS NULL OR source_currency<>NEW.currency
    OR source_hash<>p.source_journal_sha256 THEN
  RAISE EXCEPTION 'Original posted accounting journal required'
    USING ERRCODE='23514';
 END IF;
 SELECT * INTO bank FROM finance_bank_statement_intake_line
  WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id
  FOR UPDATE;
 SELECT * INTO evidence FROM finance_bank_statement_intake
  WHERE id=bank.intake_id AND organization_id=NEW.organization_id;
 IF bank.id IS NULL OR evidence.id IS NULL
    OR evidence.statement_sha256<>p.bank_statement_sha256
    OR bank.currency<>NEW.currency OR EXISTS(SELECT 1 FROM finance_gl_opening_obligation o
       WHERE o.organization_id=NEW.organization_id AND o.source_kind=NEW.source_kind AND o.id=NEW.source_journal_id
         AND bank.booked_on<o.opening_date) OR
    (NEW.source_kind='AR' AND bank.flow<>'CREDIT') OR
    (NEW.source_kind='AP' AND bank.flow<>'DEBIT') THEN
   RAISE EXCEPTION 'Verified company bank credit/debit required'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(allocated_amount),0) INTO old_source_total
 FROM finance_cash_allocation_effective
 WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
   AND source_journal_id=NEW.source_journal_id;
  SELECT coalesce(sum(amount),0) INTO old_bank_total
 FROM finance_bank_committed_usage WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
 SELECT coalesce(sum(amount),0) INTO old_cash_total
 FROM finance_cash_voucher_record WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
   AND source_journal_id=NEW.source_journal_id;
 IF old_source_total+old_cash_total+NEW.allocated_amount>source_amount
    OR old_bank_total+NEW.allocated_amount>bank.amount THEN
  RAISE EXCEPTION 'Source, bank or employee funding cannot be over-allocated'
    USING ERRCODE='23514';
 END IF;
 -- All policy, separation and decision checks must belong to the
 -- same exact approved request. Avoid PL/pgSQL record/SQL alias ambiguity
 -- in nested EXISTS during the actual BEFORE INSERT trigger.
 IF NOT EXISTS (
   SELECT 1 FROM approval_request approved_request
   WHERE approved_request.id=NEW.approval_request_id
     AND approved_request.organization_id=NEW.organization_id
     AND approved_request.state='APPROVED'
     AND approved_request.subject_type='FINANCE_AP_AR_CASH_ALLOCATION'
     AND approved_request.subject_id=p.id
     AND approved_request.subject_version=p.approval_subject_version
     AND approved_request.policy_key=p.policy_key
     AND approved_request.requester_user_id=p.prepared_by_user_id
     AND approved_request.requester_user_id<>NEW.recorded_by_user_id
     AND approved_request.completed_at IS NOT NULL
     AND approved_request.completed_at<=NEW.recorded_at
     AND EXISTS (
       SELECT 1 FROM approval_decision decision_row
       WHERE decision_row.approval_request_id=approved_request.id
         AND decision_row.decision='APPROVE'
         AND decision_row.subject_version=approved_request.subject_version
         AND decision_row.actor_user_id<>approved_request.requester_user_id
         AND decision_row.actor_user_id<>NEW.recorded_by_user_id
     )
     AND EXISTS (
       SELECT 1 FROM approval_policy_version active_policy
       WHERE active_policy.organization_id=NEW.organization_id
         AND active_policy.policy_key=approved_request.policy_key
         AND active_policy.version_number=approved_request.policy_version
         AND active_policy.lifecycle_state='ACTIVE'
         AND active_policy.approval_required=true
         AND active_policy.step_mode='SINGLE'
         AND active_policy.effective_from<=NEW.recorded_at
         AND (active_policy.effective_to IS NULL
              OR active_policy.effective_to>NEW.recorded_at)
     )
 ) THEN
   RAISE EXCEPTION 'Independent original-bound bank allocation approval required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION finance_cash_allocation_void_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_allocation_void_proposal%ROWTYPE;
        original finance_cash_allocation_record%ROWTYPE;
        original_proposal finance_cash_allocation_proposal%ROWTYPE;
        a approval_request%ROWTYPE;
        original_gl_period uuid;
        fiscal finance_gl_period%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        ledger_hash char(64);
        bank_hash char(64);
BEGIN
 SELECT * INTO p FROM finance_cash_allocation_void_proposal
  WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.original_record_id<>NEW.original_record_id
  OR p.source_kind<>NEW.source_kind
  OR p.source_journal_id<>NEW.source_journal_id
  OR p.bank_statement_line_id<>NEW.bank_statement_line_id
  OR p.amount<>NEW.amount OR p.currency<>NEW.currency
  OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Correction must match reviewed immutable proposal'
   USING ERRCODE='23514';
 END IF;
 -- Match writer lock order to normal cash allocation: source before bank.
  SELECT fiscal_period_id,ledger_fingerprint_sha256
 INTO original_gl_period,ledger_hash
 FROM finance_lock_obligation_source(NEW.organization_id,NEW.source_kind,NEW.source_journal_id);

 SELECT * INTO bank FROM finance_bank_statement_intake_line
  WHERE id=NEW.bank_statement_line_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT * INTO original FROM finance_cash_allocation_record
  WHERE id=NEW.original_record_id AND organization_id=NEW.organization_id FOR UPDATE;
 SELECT * INTO original_proposal FROM finance_cash_allocation_proposal
  WHERE id=original.proposal_id AND organization_id=original.organization_id;
 SELECT h.statement_sha256 INTO bank_hash FROM finance_bank_statement_intake h
  WHERE h.id=bank.intake_id AND h.organization_id=bank.organization_id;
 IF original.id IS NULL OR original.source_kind<>p.source_kind
   OR original.source_journal_id<>p.source_journal_id
   OR original.bank_statement_line_id<>p.bank_statement_line_id
   OR original.allocated_amount<>p.amount OR original.currency<>p.currency
   OR bank.id IS NULL OR bank.currency<>p.currency
   OR ledger_hash IS DISTINCT FROM p.source_ledger_sha256
   OR bank_hash IS DISTINCT FROM p.bank_statement_sha256
   OR original_proposal.id IS NULL
   OR original.recorded_at>NEW.recorded_at THEN
  RAISE EXCEPTION 'Original verified accounting match changed'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO fiscal FROM finance_gl_period
  WHERE id=original_gl_period AND organization_id=NEW.organization_id FOR UPDATE;
 IF fiscal.id IS NULL OR fiscal.state<>'OPEN' OR EXISTS(
   SELECT 1 FROM finance_gl_period_close c
   WHERE c.period_id=fiscal.id AND c.organization_id=fiscal.organization_id) THEN
   RAISE EXCEPTION 'Closed GL period requires separate period adjustment policy'
     USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
   OR a.subject_type<>'FINANCE_CASH_ALLOCATION_VOID'
   OR a.subject_id<>p.id OR a.subject_version<>p.approval_subject_version
   OR a.policy_key<>p.policy_key
   OR a.requester_user_id<>p.prepared_by_user_id
   OR a.requester_user_id=NEW.recorded_by_user_id
   OR a.requester_user_id=original_proposal.prepared_by_user_id
   OR a.requester_user_id=original.recorded_by_user_id
   OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
   OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
        AND d.subject_version=a.subject_version
        AND d.actor_user_id<>a.requester_user_id
        AND d.actor_user_id<>NEW.recorded_by_user_id
        AND d.actor_user_id<>original_proposal.prepared_by_user_id
        AND d.actor_user_id<>original.recorded_by_user_id)
   OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id
        AND v.policy_key=a.policy_key
        AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
        AND v.step_mode='SINGLE' AND v.effective_from<=NEW.recorded_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
  RAISE EXCEPTION 'Independent approved original-bound cash correction required'
   USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;

CREATE OR REPLACE FUNCTION finance_cash_voucher_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_cash_voucher_proposal%ROWTYPE;
        b finance_cashbox_register%ROWTYPE;
        a approval_request%ROWTYPE;
        src_hash char(64);
        src_amount numeric(24,6);
        src_currency char(3);
        total_bank numeric(24,6);
        total_cash numeric(24,6);
        current_cash numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_cash_voucher_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.cashbox_id<>NEW.cashbox_id
   OR p.source_kind<>NEW.source_kind
   OR p.source_journal_id<>NEW.source_journal_id
   OR p.direction<>NEW.direction OR p.amount<>NEW.amount
   OR p.currency<>NEW.currency OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
  RAISE EXCEPTION 'Cash voucher must equal immutable reviewed proposal'
   USING ERRCODE='23514';
 END IF;
 -- All bank and cash allocation writers serialize on the source journal
 -- BEFORE locking their cash carrier (bank statement line / cashbox).
  SELECT gross_amount,currency,ledger_fingerprint_sha256 INTO src_amount,src_currency,src_hash
 FROM finance_lock_obligation_source(NEW.organization_id,NEW.source_kind,NEW.source_journal_id);
 IF NEW.source_kind='AP' AND EXISTS(SELECT 1 FROM finance_ap_journal_reversal r
     WHERE r.organization_id=NEW.organization_id AND r.original_journal_id=NEW.source_journal_id)
 THEN RAISE EXCEPTION 'Cannot cash-pay reversed supplier payable' USING ERRCODE='23514'; END IF;
 IF EXISTS(SELECT 1 FROM finance_gl_opening_obligation o WHERE o.organization_id=NEW.organization_id
     AND o.source_kind=NEW.source_kind AND o.id=NEW.source_journal_id AND (NEW.recorded_at AT TIME ZONE 'UTC')::date<o.opening_date)
 THEN RAISE EXCEPTION 'Cannot allocate a pre-cutover cash voucher to an opening obligation' USING ERRCODE='23514'; END IF;

 SELECT * INTO b FROM finance_cashbox_register
  WHERE id=NEW.cashbox_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF b.id IS NULL OR b.status<>'ACTIVE' OR b.currency<>NEW.currency
    OR b.custodian_user_id<>NEW.recorded_by_user_id
    OR src_amount IS NULL OR src_currency<>NEW.currency
    OR src_hash<>p.source_ledger_sha256 THEN
  RAISE EXCEPTION 'Approved company cashbox custodian and source required'
    USING ERRCODE='23514';
 END IF;
 IF (NEW.source_kind='AR' AND NEW.direction<>'RECEIPT') OR
    (NEW.source_kind='AP' AND NEW.direction<>'PAYMENT') THEN
  RAISE EXCEPTION 'Cash voucher direction contradicts accounting source'
   USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(
  SELECT 1 FROM evidence e
  JOIN evidence_upload_session u ON u.evidence_id=e.id
  WHERE e.id=NEW.custody_evidence_id AND e.organization_id=NEW.organization_id
    AND e.target_type='FINANCE_CASH_VOUCHER'
    AND e.target_id=p.id AND e.storage_state='READY'
    AND u.state='READY' AND e.finalized_at IS NOT NULL
    AND u.finalized_at IS NOT NULL AND e.sha256=u.expected_sha256
    AND e.size_bytes=u.expected_size_bytes
    AND e.object_key_ref IS NOT NULL
    AND e.client_visibility_mode='INTERNAL_ONLY'
 ) THEN
  RAISE EXCEPTION 'Physical signed receipt/payment custody evidence required'
   USING ERRCODE='23514';
 END IF;
 SELECT COALESCE(SUM(allocated_amount),0) INTO total_bank
  FROM finance_cash_allocation_effective
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 SELECT COALESCE(SUM(amount),0) INTO total_cash
  FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND source_kind=NEW.source_kind
    AND source_journal_id=NEW.source_journal_id;
 IF total_bank+total_cash+NEW.amount>src_amount THEN
  RAISE EXCEPTION 'Cash and bank together exceed source invoice'
   USING ERRCODE='23514';
 END IF;
 SELECT COALESCE(SUM(CASE WHEN direction='RECEIPT' THEN amount ELSE -amount END),0)
 INTO current_cash FROM finance_cash_voucher_record
  WHERE organization_id=NEW.organization_id AND cashbox_id=NEW.cashbox_id;
 IF NEW.direction='PAYMENT' AND
    b.opening_balance+current_cash<NEW.amount THEN
  RAISE EXCEPTION 'Cashbox cannot pay money it does not hold'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
   OR a.subject_type<>'FINANCE_CASH_VOUCHER'
   OR a.subject_id<>p.id OR a.subject_version<>p.approval_subject_version
   OR a.policy_key<>p.policy_key
   OR a.requester_user_id<>p.prepared_by_user_id
   OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
   OR a.requester_user_id=NEW.recorded_by_user_id
   OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
        AND d.subject_version=a.subject_version
        AND d.actor_user_id<>p.prepared_by_user_id
        AND d.actor_user_id<>NEW.recorded_by_user_id)
   OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id AND v.policy_key=a.policy_key
        AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
        AND v.step_mode='SINGLE' AND v.effective_from<=NEW.recorded_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)
   ) THEN
   RAISE EXCEPTION 'Independent approved receipt/payment with custody required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;

CREATE OR REPLACE VIEW finance_ap_ar_source_balance AS
WITH recognized_movements AS (
 SELECT organization_id,source_kind,source_journal_id,
        allocated_amount AS amount
 FROM finance_cash_allocation_effective
 UNION ALL
 SELECT organization_id,source_kind,source_journal_id,
        amount
 FROM finance_cash_voucher_record
), cash_used AS (
 SELECT organization_id,source_kind,source_journal_id,
        sum(amount) AS allocated_amount
 FROM recognized_movements
 GROUP BY organization_id,source_kind,source_journal_id
)
SELECT j.organization_id, 'AP'::varchar(2) AS source_kind,
       j.id AS journal_id, p.supplier_organization_id AS counterparty_id,
       j.currency,j.posted_at AS recognized_at,
       NULL::date AS contractual_due_date,
       j.gross_amount,
       coalesce(c.allocated_amount,0::numeric) AS allocated_amount,
       CASE WHEN r.id IS NOT NULL THEN NULL::numeric
         ELSE j.gross_amount-coalesce(c.allocated_amount,0::numeric)
       END AS outstanding_amount,
       (r.id IS NOT NULL) AS reversed,
       CASE WHEN r.id IS NOT NULL AND c.allocated_amount>0
            THEN 'REVERSED_WITH_BANK_ALLOCATION_EXCEPTION'
            WHEN r.id IS NOT NULL THEN 'REVERSED_NO_OUTSTANDING'
            WHEN c.allocated_amount IS NULL THEN 'UNALLOCATED'
            WHEN c.allocated_amount<j.gross_amount THEN 'PARTIALLY_ALLOCATED'
            WHEN c.allocated_amount=j.gross_amount
                 THEN 'ALLOCATED_NOT_BANK_RECONCILED'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS accounting_state
FROM finance_ap_posted_journal j
JOIN finance_ap_posting_proposal p ON p.id=j.proposal_id
 AND p.organization_id=j.organization_id
LEFT JOIN cash_used c ON c.organization_id=j.organization_id
 AND c.source_kind='AP' AND c.source_journal_id=j.id
LEFT JOIN finance_ap_journal_reversal r ON r.organization_id=j.organization_id
 AND r.original_journal_id=j.id
UNION ALL
SELECT j.organization_id, 'AR'::varchar(2) AS source_kind,
       j.id AS journal_id, i.customer_organization_id AS counterparty_id,
       j.currency,j.posted_at AS recognized_at,
       i.due_date AS contractual_due_date,j.gross_amount,
       coalesce(c.allocated_amount,0::numeric) AS allocated_amount,
       j.gross_amount-coalesce(c.allocated_amount,0::numeric) AS outstanding_amount,
       false AS reversed,
       CASE WHEN c.allocated_amount IS NULL THEN 'UNALLOCATED'
            WHEN c.allocated_amount<j.gross_amount THEN 'PARTIALLY_ALLOCATED'
            WHEN c.allocated_amount=j.gross_amount
                 THEN 'ALLOCATED_NOT_BANK_RECONCILED'
            ELSE 'OVERALLOCATED_EXCEPTION'
       END AS accounting_state
FROM finance_ar_posted_journal j
JOIN finance_ar_issued_invoice i ON i.id=j.issued_invoice_id
 AND i.organization_id=j.organization_id
LEFT JOIN cash_used c ON c.organization_id=j.organization_id
 AND c.source_kind='AR' AND c.source_journal_id=j.id
UNION ALL
SELECT j.organization_id,j.source_kind,j.id,j.counterparty_id,j.currency,j.posted_at,
       j.contractual_due_date,j.gross_amount,coalesce(c.allocated_amount,0::numeric),
       j.gross_amount-coalesce(c.allocated_amount,0::numeric),false,
       CASE WHEN c.allocated_amount IS NULL THEN 'UNALLOCATED'
            WHEN c.allocated_amount<j.gross_amount THEN 'PARTIALLY_ALLOCATED'
            WHEN c.allocated_amount=j.gross_amount THEN 'ALLOCATED_NOT_BANK_RECONCILED'
            ELSE 'OVERALLOCATED_EXCEPTION' END
FROM finance_gl_opening_obligation j
LEFT JOIN cash_used c ON c.organization_id=j.organization_id
 AND c.source_kind=j.source_kind AND c.source_journal_id=j.id;

CREATE OR REPLACE VIEW finance_settlement_gl_source AS
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
 UNION ALL
 SELECT organization_id,source_kind,id,control_account_id,counterparty_id
 FROM finance_gl_opening_obligation
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
