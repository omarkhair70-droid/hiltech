-- Retained employee advances and imprests are cutover obligations, not new
-- funding. The source schedule attests historical residuals, never a bank debit.
CREATE VIEW finance_gl_opening_employee_obligation AS
 SELECT j.organization_id,md5(j.id::text||':employee:'||l.ordinality::text||':'||o.ordinality::text)::uuid AS id,
   j.id AS opening_journal_id,j.opening_date,j.posted_at,j.currency,
   CASE WHEN a.account_kind='EMPLOYEE_ADVANCE_ASSET' THEN 'EMPLOYEE_ADVANCE'::varchar(24) ELSE 'FINANCIAL_IMPREST'::varchar(24) END AS draft_kind,
   a.id AS control_account_id,(o.value->>'employeeId')::uuid AS employee_id,
   o.value->>'documentReference' AS document_reference,(o.value->>'documentDate')::date AS document_date,
   (o.value->>'amount')::numeric(18,6) AS amount,
   encode(sha256(convert_to(jsonb_build_array(j.id,a.id,j.currency,j.opening_date,o.value)::text,'UTF8')),'hex') AS source_fingerprint
 FROM finance_gl_opening_journal j CROSS JOIN LATERAL jsonb_array_elements(j.lines) WITH ORDINALITY l(value,ordinality)
 JOIN finance_gl_account a ON a.organization_id=j.organization_id AND a.id=(l.value->>'accountId')::uuid
 CROSS JOIN LATERAL jsonb_array_elements(coalesce(l.value->'employeeObligations','[]'::jsonb)) WITH ORDINALITY o(value,ordinality)
 WHERE a.account_kind IN('EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET');

CREATE OR REPLACE FUNCTION finance_gl_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fiscal finance_gl_period%ROWTYPE; item jsonb; account finance_gl_account%ROWTYPE;
 bank finance_bank_statement_intake%ROWTYPE; bank_count int; amount numeric; debit numeric:=0; credit numeric:=0;
 seen uuid[]:=ARRAY[]::uuid[];
 obligation jsonb; obligation_total numeric; obligation_key text; obligation_kind varchar(2);
 seen_obligations text[]:=ARRAY[]::text[]; obligation_count int:=0;
 stock_value jsonb; stock_source finance_stock_opening_source%ROWTYPE; stock_total numeric;
 seen_stock uuid[]:=ARRAY[]::uuid[]; stock_count int:=0;
 employee_item jsonb; employee_total numeric; employee_key text; employee_kind varchar(24);
 seen_employee text[]:=ARRAY[]::text[]; employee_count int:=0;
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
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN('accountId','side','amount','reference','bankStatementId','obligations','stockValues','employeeObligations'))
      OR EXISTS(SELECT 1 FROM jsonb_each(item) v WHERE v.key NOT IN('obligations','stockValues','employeeObligations') AND jsonb_typeof(v.value)<>'string')
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
     OR account.account_kind NOT IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET','CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE','INVENTORY_ASSET','EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET')
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
         OR obligation->>'counterpartyId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
         OR length(btrim(obligation->>'documentReference')) NOT BETWEEN 2 AND 96
         OR obligation->>'documentReference'<>btrim(obligation->>'documentReference')
         OR obligation->>'documentReference' ~ '[[:cntrl:]]'
         OR obligation->>'documentDate' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
         OR (obligation->>'documentDate')::date>=NEW.opening_date
         OR (obligation ? 'dueDate' AND (obligation->>'dueDate' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
              OR (obligation->>'dueDate')::date<(obligation->>'documentDate')::date))
         OR obligation->>'amount' !~ '^[0-9]{1,18}([.][0-9]{1,6})?$'
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
   IF account.account_kind='INVENTORY_ASSET' THEN
     IF item->>'side'<>'DEBIT' OR NOT (item ? 'stockValues') OR jsonb_typeof(item->'stockValues')<>'array'
       OR jsonb_array_length(item->'stockValues')=0
     THEN RAISE EXCEPTION 'Inventory opening needs exact verified stock movements and reviewed values' USING ERRCODE='23514'; END IF;
     stock_count:=stock_count+jsonb_array_length(item->'stockValues');
     IF stock_count>1000 THEN RAISE EXCEPTION 'Opening stock payload exceeds supported size' USING ERRCODE='23514'; END IF;
     stock_total:=0;
     FOR stock_value IN SELECT value FROM jsonb_array_elements(item->'stockValues') LOOP
       IF jsonb_typeof(stock_value)<>'object'
         OR NOT (stock_value ?& ARRAY['movementId','quantity','unit','sourceFingerprint','amount','valuationReference'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(stock_value) k WHERE k NOT IN('movementId','quantity','unit','sourceFingerprint','amount','valuationReference'))
         OR EXISTS(SELECT 1 FROM jsonb_each(stock_value) v WHERE jsonb_typeof(v.value)<>'string')
         OR stock_value->>'movementId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
         OR stock_value->>'sourceFingerprint' !~ '^[0-9a-f]{64}$'
         OR stock_value->>'quantity' !~ '^[0-9]{1,14}([.][0-9]{1,6})?$'
         OR stock_value->>'amount' !~ '^[0-9]{1,18}([.][0-9]{1,6})?$'
         OR (stock_value->>'amount')::numeric<=0
         OR length(btrim(stock_value->>'valuationReference')) NOT BETWEEN 8 AND 240
         OR btrim(stock_value->>'valuationReference')<>stock_value->>'valuationReference'
         OR stock_value->>'valuationReference' ~ '[[:cntrl:]]'
       THEN RAISE EXCEPTION 'Invalid inventory valuation schedule entry' USING ERRCODE='23514'; END IF;
       -- Lock the actual movement, reviewed import and verification. Quantity
       -- cannot be invented by Finance or reused across accounts/currencies.
       PERFORM 1 FROM stock_movement m
         JOIN stock_import_verification v ON v.source_row_id=m.source_import_row_id AND v.organization_id=m.organization_id
         JOIN inventory_import_row r ON r.id=v.source_row_id AND r.organization_id=v.organization_id
       WHERE m.organization_id=NEW.organization_id AND m.id=(stock_value->>'movementId')::uuid
       FOR SHARE OF m,v,r;
       SELECT * INTO stock_source FROM finance_stock_opening_source s WHERE s.organization_id=NEW.organization_id
         AND s.movement_id=(stock_value->>'movementId')::uuid AND s.opening_date=NEW.opening_date
         AND s.recorded_at<=NEW.posted_at;
       IF NOT FOUND OR stock_source.quantity<>(stock_value->>'quantity')::numeric
         OR stock_source.unit_code<>stock_value->>'unit'
         OR stock_source.source_fingerprint<>stock_value->>'sourceFingerprint'
         OR stock_source.movement_id=ANY(seen_stock)
         OR EXISTS(SELECT 1 FROM finance_gl_opening_stock_value v WHERE v.organization_id=NEW.organization_id
             AND v.movement_id=stock_source.movement_id)
       THEN RAISE EXCEPTION 'Stock opening source changed, wrong cutover or already valued' USING ERRCODE='23514'; END IF;
       seen_stock:=array_append(seen_stock,stock_source.movement_id);
       stock_total:=stock_total+(stock_value->>'amount')::numeric;
     END LOOP;
     IF stock_total<>amount THEN RAISE EXCEPTION 'Inventory account must equal its reviewed source values' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'stockValues' THEN
     RAISE EXCEPTION 'Inventory valuation cannot support an unrelated account' USING ERRCODE='23514';
   END IF;
   IF account.account_kind IN('EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET') THEN
     employee_kind:=CASE WHEN account.account_kind='EMPLOYEE_ADVANCE_ASSET' THEN 'EMPLOYEE_ADVANCE' ELSE 'FINANCIAL_IMPREST' END;
     IF item->>'side'<>'DEBIT' OR NOT (item ? 'employeeObligations') OR jsonb_typeof(item->'employeeObligations')<>'array'
       OR jsonb_array_length(item->'employeeObligations')=0
     THEN RAISE EXCEPTION 'Employee opening requires individual historical residuals' USING ERRCODE='23514'; END IF;
     employee_count:=employee_count+jsonb_array_length(item->'employeeObligations');
     IF employee_count>1000 THEN RAISE EXCEPTION 'Employee opening payload exceeds supported size' USING ERRCODE='23514'; END IF;
     employee_total:=0;
     FOR employee_item IN SELECT value FROM jsonb_array_elements(item->'employeeObligations') LOOP
       IF jsonb_typeof(employee_item)<>'object'
         OR NOT (employee_item ?& ARRAY['employeeId','documentReference','documentDate','amount'])
         OR EXISTS(SELECT 1 FROM jsonb_object_keys(employee_item) k WHERE k NOT IN('employeeId','documentReference','documentDate','amount'))
         OR EXISTS(SELECT 1 FROM jsonb_each(employee_item) v WHERE jsonb_typeof(v.value)<>'string')
         OR employee_item->>'employeeId' !~ '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$'
         OR employee_item->>'amount' !~ '^[0-9]{1,12}([.][0-9]{1,6})?$'
         OR (employee_item->>'amount')::numeric<=0
         OR employee_item->>'documentDate' !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}$'
         OR (employee_item->>'documentDate')::date>=NEW.opening_date
         OR length(btrim(employee_item->>'documentReference')) NOT BETWEEN 2 AND 96
         OR btrim(employee_item->>'documentReference')<>employee_item->>'documentReference'
         OR employee_item->>'documentReference' ~ '[[:cntrl:]]'
         OR NOT EXISTS(SELECT 1 FROM employee e WHERE e.organization_id=NEW.organization_id
             AND e.id=(employee_item->>'employeeId')::uuid)
       THEN RAISE EXCEPTION 'Employee opening needs company employee, historical document and exact residual' USING ERRCODE='23514'; END IF;
       employee_key:=employee_kind||':'||((employee_item->>'employeeId')::uuid)::text||':'||lower(employee_item->>'documentReference');
       IF employee_key=ANY(seen_employee)
         OR EXISTS(SELECT 1 FROM finance_gl_opening_employee_obligation e WHERE e.organization_id=NEW.organization_id
           AND e.draft_kind=employee_kind AND e.employee_id=(employee_item->>'employeeId')::uuid
           AND lower(e.document_reference)=lower(employee_item->>'documentReference'))
         OR EXISTS(SELECT 1 FROM finance_employee_funding_record f
           JOIN finance_bank_statement_intake_line bank ON bank.organization_id=f.organization_id AND bank.id=f.bank_statement_line_id
           WHERE f.organization_id=NEW.organization_id AND f.employee_id=(employee_item->>'employeeId')::uuid
             AND f.draft_kind=employee_kind AND f.currency=NEW.currency AND bank.booked_on<NEW.opening_date)
       THEN RAISE EXCEPTION 'Employee historical source overlaps an already recorded obligation or funding' USING ERRCODE='23514'; END IF;
       seen_employee:=array_append(seen_employee,employee_key);
       employee_total:=employee_total+(employee_item->>'amount')::numeric;
     END LOOP;
     IF employee_total<>amount THEN RAISE EXCEPTION 'Employee control opening must equal historical residual schedule' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'employeeObligations' THEN
     RAISE EXCEPTION 'Employee residual schedule cannot support an unrelated account' USING ERRCODE='23514';
   END IF;
   IF item->>'side'='DEBIT' THEN debit:=debit+amount; ELSE credit:=credit+amount; END IF;
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

CREATE TABLE finance_employee_custody_registry (
 organization_id uuid NOT NULL,id uuid NOT NULL,funding_id uuid,opening_journal_id uuid,
 PRIMARY KEY(organization_id,id),
 FOREIGN KEY(funding_id,organization_id) REFERENCES finance_employee_funding_record(id,organization_id) ON DELETE RESTRICT,
 FOREIGN KEY(organization_id,opening_journal_id) REFERENCES finance_gl_opening_journal(organization_id,id) ON DELETE RESTRICT,
 CHECK((funding_id=id AND opening_journal_id IS NULL) IS TRUE OR
       (funding_id IS NULL AND opening_journal_id IS NOT NULL))
);
CREATE FUNCTION finance_employee_custody_registry_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.opening_journal_id IS NOT NULL AND NOT EXISTS(
   SELECT 1 FROM finance_gl_opening_employee_obligation o WHERE o.organization_id=NEW.organization_id
     AND o.id=NEW.id AND o.opening_journal_id=NEW.opening_journal_id)
 THEN RAISE EXCEPTION 'Unknown sealed employee opening source' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_employee_custody_registry_guard BEFORE INSERT ON finance_employee_custody_registry
 FOR EACH ROW EXECUTE FUNCTION finance_employee_custody_registry_guard();
CREATE TRIGGER finance_employee_custody_registry_immutable BEFORE UPDATE OR DELETE ON finance_employee_custody_registry
 FOR EACH ROW EXECUTE FUNCTION finance_ap_reject_snapshot_mutation();
INSERT INTO finance_employee_custody_registry(organization_id,id,funding_id)
 SELECT organization_id,id,id FROM finance_employee_funding_record;
INSERT INTO finance_employee_custody_registry(organization_id,id,opening_journal_id)
 SELECT organization_id,id,opening_journal_id FROM finance_gl_opening_employee_obligation;
CREATE FUNCTION finance_register_employee_custody_source() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_TABLE_NAME='finance_employee_funding_record' THEN
   INSERT INTO finance_employee_custody_registry(organization_id,id,funding_id) VALUES(NEW.organization_id,NEW.id,NEW.id);
 ELSE
   INSERT INTO finance_employee_custody_registry(organization_id,id,opening_journal_id)
     SELECT organization_id,id,opening_journal_id FROM finance_gl_opening_employee_obligation
     WHERE organization_id=NEW.organization_id AND opening_journal_id=NEW.id;
 END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_employee_custody_register_funding AFTER INSERT ON finance_employee_funding_record
 FOR EACH ROW EXECUTE FUNCTION finance_register_employee_custody_source();
CREATE TRIGGER finance_employee_custody_register_opening AFTER INSERT ON finance_gl_opening_journal
 FOR EACH ROW EXECUTE FUNCTION finance_register_employee_custody_source();

DO $$
DECLARE t regclass; c text;
BEGIN
 FOREACH t IN ARRAY ARRAY['finance_employee_settlement_proposal'::regclass,'finance_employee_settlement_record'::regclass,'finance_settlement_gl_journal'::regclass] LOOP
   SELECT conname INTO STRICT c FROM pg_constraint WHERE conrelid=t AND contype='f' AND confrelid='finance_employee_funding_record'::regclass;
   EXECUTE format('ALTER TABLE %s DROP CONSTRAINT %I',t,c);
 END LOOP;
END; $$;
ALTER TABLE finance_employee_settlement_proposal ADD CONSTRAINT finance_employee_settlement_proposal_custody_fk
 FOREIGN KEY(organization_id,funding_record_id) REFERENCES finance_employee_custody_registry(organization_id,id) ON DELETE RESTRICT;
ALTER TABLE finance_employee_settlement_record ADD CONSTRAINT finance_employee_settlement_record_custody_fk
 FOREIGN KEY(organization_id,funding_record_id) REFERENCES finance_employee_custody_registry(organization_id,id) ON DELETE RESTRICT;
ALTER TABLE finance_settlement_gl_journal ADD CONSTRAINT finance_settlement_gl_employee_custody_fk
 FOREIGN KEY(organization_id,employee_funding_record_id) REFERENCES finance_employee_custody_registry(organization_id,id) ON DELETE RESTRICT;

CREATE VIEW finance_employee_custody_source AS
 SELECT f.organization_id,f.id,f.employee_id,f.draft_kind,f.amount,f.currency,f.bank_statement_line_id,
   f.approval_request_id,f.recorded_at,NULL::date AS opening_date,NULL::timestamptz AS opening_posted_at,
   NULL::uuid AS opening_control_account_id,NULL::text AS opening_fingerprint,NULL::text AS document_reference
 FROM finance_employee_funding_record f JOIN finance_employee_custody_registry r
   ON r.organization_id=f.organization_id AND r.funding_id=f.id
 UNION ALL
 SELECT o.organization_id,o.id,o.employee_id,o.draft_kind,o.amount,o.currency,NULL::uuid,NULL::uuid,o.posted_at,
   o.opening_date,o.posted_at,o.control_account_id,o.source_fingerprint,o.document_reference
 FROM finance_gl_opening_employee_obligation o JOIN finance_employee_custody_registry r
   ON r.organization_id=o.organization_id AND r.id=o.id AND r.opening_journal_id=o.opening_journal_id;

CREATE VIEW finance_employee_custody_balance AS
 SELECT s.*,s.amount-coalesce(r.settled,0) AS remaining_amount
 FROM finance_employee_custody_source s LEFT JOIN LATERAL (
   SELECT sum(amount) AS settled FROM finance_employee_settlement_record r
    WHERE r.organization_id=s.organization_id AND r.funding_record_id=s.id
 ) r ON true;

CREATE FUNCTION finance_employee_historical_funding_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM finance_gl_opening_employee_obligation o
    JOIN finance_bank_statement_intake_line b ON b.organization_id=o.organization_id AND b.id=NEW.bank_statement_line_id
    WHERE o.organization_id=NEW.organization_id AND o.employee_id=NEW.employee_id
      AND o.draft_kind=NEW.draft_kind AND o.currency=NEW.currency AND b.booked_on<o.opening_date)
 THEN RAISE EXCEPTION 'Pre-cutover employee funding overlaps a historical residual' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER aa_finance_employee_historical_funding_guard BEFORE INSERT ON finance_employee_funding_record
 FOR EACH ROW EXECUTE FUNCTION finance_employee_historical_funding_guard();

CREATE OR REPLACE FUNCTION finance_employee_settlement_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p finance_employee_settlement_proposal%ROWTYPE;
        funded finance_employee_custody_source%ROWTYPE;
        bank finance_bank_statement_intake_line%ROWTYPE;
        a approval_request%ROWTYPE;
        total_settled numeric(24,6);
        cash_used numeric(24,6);
        funding_used numeric(24,6);
        bank_settled numeric(24,6);
BEGIN
 SELECT * INTO p FROM finance_employee_settlement_proposal
 WHERE id=NEW.proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.funding_record_id<>NEW.funding_record_id
   OR p.employee_id<>NEW.employee_id OR p.action<>NEW.action
   OR p.bank_statement_line_id IS DISTINCT FROM NEW.bank_statement_line_id
   OR p.settlement_evidence_id<>NEW.settlement_evidence_id
   OR p.amount<>NEW.amount OR p.currency<>NEW.currency
   OR p.prepared_by_user_id=NEW.recorded_by_user_id THEN
   RAISE EXCEPTION 'Settlement must equal exact approved immutable proposal'
     USING ERRCODE='23514';
 END IF;
 PERFORM 1 FROM finance_employee_custody_registry
 WHERE id=p.funding_record_id AND organization_id=p.organization_id FOR UPDATE;
 SELECT * INTO funded FROM finance_employee_custody_source WHERE id=p.funding_record_id AND organization_id=p.organization_id;
 IF funded.id IS NULL OR funded.employee_id<>p.employee_id OR
    funded.currency<>p.currency OR (funded.opening_posted_at IS NOT NULL AND NEW.recorded_at<funded.opening_posted_at) OR
    (funded.draft_kind='EMPLOYEE_ADVANCE'
        AND NEW.action<>'ADVANCE_RECOVERY_CONFIRMED') OR
    (funded.draft_kind='FINANCIAL_IMPREST'
        AND NEW.action NOT IN(
            'IMPREST_CASH_RETURN_CONFIRMED','IMPREST_RECEIPTS_ACCEPTED')) THEN
   RAISE EXCEPTION 'Settlement source is not a funded loan or custody event'
     USING ERRCODE='23514';
 END IF;
 SELECT coalesce(sum(amount),0) INTO total_settled
 FROM finance_employee_settlement_record
 WHERE organization_id=NEW.organization_id
   AND funding_record_id=NEW.funding_record_id;
 IF total_settled+NEW.amount>funded.amount THEN
   RAISE EXCEPTION 'Cannot settle more than company actually funded'
     USING ERRCODE='23514';
 END IF;
 IF NOT EXISTS(
     SELECT 1 FROM evidence e JOIN evidence_upload_session u
        ON u.evidence_id=e.id
     WHERE e.organization_id=p.organization_id
       AND e.id=p.settlement_evidence_id
       AND e.target_type='FINANCE_EMPLOYEE_SETTLEMENT'
       AND e.target_id=p.id AND e.sha256=p.settlement_evidence_sha256
       AND e.storage_state='READY' AND u.state='READY'
       AND e.finalized_at IS NOT NULL AND u.finalized_at IS NOT NULL
       AND e.object_key_ref IS NOT NULL
       AND e.sha256=u.expected_sha256
       AND e.size_bytes=u.expected_size_bytes
       AND e.classification_code='HIGHLY_RESTRICTED'
       AND e.client_visibility_mode='INTERNAL_ONLY') THEN
   RAISE EXCEPTION 'Unverified employee receipts or repayment evidence'
     USING ERRCODE='23514';
 END IF;
 IF NEW.action<>'IMPREST_RECEIPTS_ACCEPTED' THEN
   SELECT * INTO bank FROM finance_bank_statement_intake_line
      WHERE id=NEW.bank_statement_line_id
        AND organization_id=NEW.organization_id FOR UPDATE;
   IF bank.id IS NULL OR bank.flow<>'CREDIT' OR bank.currency<>NEW.currency
      OR (funded.opening_date IS NOT NULL AND bank.booked_on<funded.opening_date) THEN
     RAISE EXCEPTION 'Real separate company bank credit required for return'
       USING ERRCODE='23514';
   END IF;
   SELECT coalesce(sum(allocated_amount),0) INTO cash_used
   FROM finance_cash_allocation_effective
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO funding_used
   FROM finance_employee_funding_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO bank_settled
   FROM finance_employee_settlement_record
   WHERE organization_id=NEW.organization_id
     AND bank_statement_line_id=NEW.bank_statement_line_id;
   SELECT coalesce(sum(amount),0) INTO bank_settled FROM finance_bank_committed_usage
   WHERE organization_id=NEW.organization_id AND bank_statement_line_id=NEW.bank_statement_line_id;
   IF bank_settled+NEW.amount>bank.amount THEN
     RAISE EXCEPTION 'Bank credit already allocated to another collection or return'
       USING ERRCODE='23514';
   END IF;
 END IF;
 SELECT * INTO a FROM approval_request
   WHERE id=NEW.approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_EMPLOYEE_SETTLEMENT'
    OR a.subject_id IS DISTINCT FROM p.id OR a.subject_version<>p.review_version
    OR a.policy_key<>p.policy_key
    OR a.requester_user_id<>p.prepared_by_user_id
    OR a.requester_user_id=NEW.recorded_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.recorded_at
    OR NOT EXISTS(
       SELECT 1 FROM approval_decision d
       WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
         AND d.subject_version=a.subject_version
         AND d.actor_user_id<>p.prepared_by_user_id
         AND d.actor_user_id<>NEW.recorded_by_user_id)
    OR NOT EXISTS(
       SELECT 1 FROM approval_policy_version v
       WHERE v.organization_id=NEW.organization_id
         AND v.policy_key=p.policy_key
         AND v.version_number=a.policy_version
         AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
         AND v.step_mode='SINGLE'
         AND v.effective_from<=NEW.recorded_at
         AND (v.effective_to IS NULL OR v.effective_to>NEW.recorded_at)) THEN
   RAISE EXCEPTION 'Independent approved employee repayment or custody settlement required'
     USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;

CREATE OR REPLACE VIEW finance_settlement_gl_source_v2 AS
WITH funding AS (
 SELECT f.*,CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EA' ELSE 'EI' END AS custody_kind,
        CASE WHEN f.draft_kind='EMPLOYEE_ADVANCE' THEN 'EMPLOYEE_ADVANCE_ASSET' ELSE 'EMPLOYEE_IMPREST_ASSET' END AS custody_account_kind,
        p.display_name AS employee_name
 FROM finance_employee_custody_source f
 JOIN employee e ON e.id=f.employee_id AND e.organization_id=f.organization_id
 JOIN person p ON p.id=e.person_id
)
SELECT s.*,NULL::uuid AS employee_funding_record_id,NULL::uuid AS expense_claim_id,NULL::varchar AS required_debit_kind
 FROM finance_settlement_gl_source s
UNION ALL
SELECT f.organization_id,'EMPLOYEE_FUNDING',f.id,f.custody_kind,NULL::uuid,f.approval_request_id,
 f.amount,f.currency,f.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 f.employee_id,f.employee_name,NULL::uuid,NULL::uuid,g.id,f.id,NULL::uuid,f.custody_account_kind
FROM funding f
JOIN finance_bank_statement_intake_line b ON b.organization_id=f.organization_id AND b.id=f.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=f.currency AND g.state='APPROVED'
UNION ALL
SELECT r.organization_id,CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'IMPREST_EXPENSE' ELSE 'EMPLOYEE_RETURN' END,
 r.id,f.custody_kind,NULL::uuid,r.approval_request_id,r.amount,r.currency,r.recorded_at,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'EVIDENCE' ELSE 'BANK' END,b.account_id,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'Accepted custody receipts' ELSE a.bank_name || ' · ' || a.masked_account_reference END,
 r.employee_id,f.employee_name,NULL::uuid,g.id,coalesce(f.opening_control_account_id,j.debit_account_id),f.id,NULL::uuid,
 CASE WHEN r.action='IMPREST_RECEIPTS_ACCEPTED' THEN 'COST' ELSE NULL END
FROM finance_employee_settlement_record r
JOIN funding f ON f.organization_id=r.organization_id AND f.id=r.funding_record_id
LEFT JOIN finance_settlement_gl_journal j ON j.organization_id=f.organization_id AND j.source_type='EMPLOYEE_FUNDING' AND j.source_id=f.id
LEFT JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
LEFT JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED'
UNION ALL
SELECT r.organization_id,'EXPENSE_REIMBURSEMENT',r.id,'ER',NULL::uuid,r.approval_request_id,
 r.amount,r.currency,r.recorded_at,'BANK',b.account_id,a.bank_name || ' · ' || a.masked_account_reference,
 r.employee_id,p.display_name,NULL::uuid,NULL::uuid,g.id,NULL::uuid,r.expense_draft_id,'COST'
FROM finance_expense_reimbursement_record r
JOIN employee e ON e.id=r.employee_id AND e.organization_id=r.organization_id
JOIN person p ON p.id=e.person_id
JOIN finance_bank_statement_intake_line b ON b.organization_id=r.organization_id AND b.id=r.bank_statement_line_id
JOIN finance_bank_account_register a ON a.organization_id=b.organization_id AND a.id=b.account_id
LEFT JOIN finance_gl_account g ON g.organization_id=b.organization_id AND g.treasury_bank_account_id=b.account_id
 AND g.currency=r.currency AND g.state='APPROVED';
