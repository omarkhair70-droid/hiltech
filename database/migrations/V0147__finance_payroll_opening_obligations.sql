-- Historical net payroll payable at cutover. A sealed opening source is an
-- existing liability, never a new payroll run, expense, transfer or advance.
-- documentDate is the historical payroll period end, not the import date.
CREATE VIEW finance_gl_opening_payroll_obligation AS
 SELECT j.organization_id,
   md5(j.id::text||':payroll:'||l.ordinality::text||':'||o.ordinality::text)::uuid AS id,
   j.id AS opening_journal_id,j.period_id AS fiscal_period_id,j.opening_date,j.posted_at,j.currency,
   a.id AS control_account_id,(o.value->>'employeeId')::uuid AS employee_id,
   o.value->>'documentReference' AS document_reference,
   (o.value->>'documentDate')::date AS payroll_period_end,
   (o.value->>'amount')::numeric(18,6) AS amount,
   encode(sha256(convert_to(jsonb_build_array(j.id,a.id,j.currency,j.opening_date,o.value)::text,'UTF8')),'hex') AS source_fingerprint
 FROM finance_gl_opening_journal j
 CROSS JOIN LATERAL jsonb_array_elements(j.lines) WITH ORDINALITY l(value,ordinality)
 JOIN finance_gl_account a ON a.organization_id=j.organization_id AND a.id=(l.value->>'accountId')::uuid
 CROSS JOIN LATERAL jsonb_array_elements(coalesce(l.value->'payrollObligations','[]'::jsonb)) WITH ORDINALITY o(value,ordinality)
 WHERE a.account_kind='PAYROLL_PAYABLE';

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
 seen_payroll_period text[]:=ARRAY[]::text[]; seen_payroll_ref text[]:=ARRAY[]::text[];
 payroll_key text; payroll_ref text; payroll_end date;
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
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN('accountId','side','amount','reference','bankStatementId','obligations','stockValues','employeeObligations','payrollObligations'))
      OR EXISTS(SELECT 1 FROM jsonb_each(item) v WHERE v.key NOT IN('obligations','stockValues','employeeObligations','payrollObligations') AND jsonb_typeof(v.value)<>'string')
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
     OR account.account_kind NOT IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET','CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE','INVENTORY_ASSET','EMPLOYEE_ADVANCE_ASSET','EMPLOYEE_IMPREST_ASSET','PAYROLL_PAYABLE')
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
           JOIN finance_bank_statement_intake_line funding_line ON funding_line.organization_id=f.organization_id AND funding_line.id=f.bank_statement_line_id
           WHERE f.organization_id=NEW.organization_id AND f.employee_id=(employee_item->>'employeeId')::uuid
             AND f.draft_kind=employee_kind AND f.currency=NEW.currency AND funding_line.booked_on<NEW.opening_date)
       THEN RAISE EXCEPTION 'Employee historical source overlaps an already recorded obligation or funding' USING ERRCODE='23514'; END IF;
       seen_employee:=array_append(seen_employee,employee_key);
       employee_total:=employee_total+(employee_item->>'amount')::numeric;
     END LOOP;
     IF employee_total<>amount THEN RAISE EXCEPTION 'Employee control opening must equal historical residual schedule' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'employeeObligations' THEN
     RAISE EXCEPTION 'Employee residual schedule cannot support an unrelated account' USING ERRCODE='23514';
   END IF;
   IF account.account_kind='PAYROLL_PAYABLE' THEN
     IF item->>'side'<>'CREDIT' OR NOT (item ? 'payrollObligations')
       OR jsonb_typeof(item->'payrollObligations')<>'array'
       OR jsonb_array_length(item->'payrollObligations')=0
     THEN RAISE EXCEPTION 'Payroll opening requires individual historical unpaid salaries' USING ERRCODE='23514'; END IF;
     employee_count:=employee_count+jsonb_array_length(item->'payrollObligations');
     IF employee_count>1000 THEN RAISE EXCEPTION 'Opening employee and payroll payload exceeds supported size' USING ERRCODE='23514'; END IF;
     employee_total:=0;
     FOR employee_item IN SELECT value FROM jsonb_array_elements(item->'payrollObligations') LOOP
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
       THEN RAISE EXCEPTION 'Payroll opening needs a company employee, historical period end and positive residual' USING ERRCODE='23514'; END IF;
       payroll_end:=(employee_item->>'documentDate')::date;
       payroll_key:=((employee_item->>'employeeId')::uuid)::text||':'||payroll_end::text;
       payroll_ref:=((employee_item->>'employeeId')::uuid)::text||':'||lower(employee_item->>'documentReference');
       IF payroll_key=ANY(seen_payroll_period) OR payroll_ref=ANY(seen_payroll_ref)
         OR EXISTS(SELECT 1 FROM finance_gl_opening_payroll_obligation o
           WHERE o.organization_id=NEW.organization_id AND o.currency=NEW.currency
             AND o.employee_id=(employee_item->>'employeeId')::uuid
             AND (o.payroll_period_end=payroll_end OR lower(o.document_reference)=lower(employee_item->>'documentReference')))
         OR EXISTS(SELECT 1 FROM payroll_run_release rel JOIN payroll_run_version r
           ON r.organization_id=rel.organization_id AND r.id=rel.run_version_id
           JOIN payroll_period pp ON pp.organization_id=r.organization_id AND pp.id=r.payroll_period_id
           JOIN payroll_run_employee_line l ON l.run_version_id=r.id
           WHERE r.organization_id=NEW.organization_id AND r.currency=NEW.currency
             AND l.employee_id=(employee_item->>'employeeId')::uuid
             AND payroll_end BETWEEN pp.start_date AND pp.end_date)
       THEN RAISE EXCEPTION 'Historical payroll overlaps an existing opening or released payroll' USING ERRCODE='23514'; END IF;
       seen_payroll_period:=array_append(seen_payroll_period,payroll_key);
       seen_payroll_ref:=array_append(seen_payroll_ref,payroll_ref);
       employee_total:=employee_total+(employee_item->>'amount')::numeric;
     END LOOP;
     IF employee_total<>amount THEN RAISE EXCEPTION 'Payroll payable must equal its individual historical residuals' USING ERRCODE='23514'; END IF;
   ELSIF item ? 'payrollObligations' THEN
     RAISE EXCEPTION 'Payroll residuals require a payroll payable account' USING ERRCODE='23514';
   END IF;
   IF item->>'side'='DEBIT' THEN debit:=debit+amount; ELSE credit:=credit+amount; END IF;
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;

-- Serialize payroll release and opening posting on the same company row. An
-- ordinary released payroll cannot recreate a historical opening obligation.
CREATE FUNCTION finance_payroll_opening_release_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 PERFORM 1 FROM organization WHERE id=NEW.organization_id FOR UPDATE;
 IF EXISTS(SELECT 1 FROM payroll_run_version r
   JOIN payroll_period pp ON pp.organization_id=r.organization_id AND pp.id=r.payroll_period_id
   JOIN payroll_run_employee_line l ON l.run_version_id=r.id
   JOIN finance_gl_opening_payroll_obligation o ON o.organization_id=r.organization_id
     AND o.currency=r.currency AND o.employee_id=l.employee_id
     AND o.payroll_period_end BETWEEN pp.start_date AND pp.end_date
   WHERE r.organization_id=NEW.organization_id AND r.id=NEW.run_version_id)
 THEN RAISE EXCEPTION 'Payroll release overlaps a historical opening obligation' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER finance_payroll_opening_release_guard BEFORE INSERT ON payroll_run_release
 FOR EACH ROW EXECUTE FUNCTION finance_payroll_opening_release_guard();
