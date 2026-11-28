-- Financial cutover uses the physical, independently verified opening movement.
-- Candidate spreadsheet values are not accounting approvals. Finance enters a
-- reviewed total carrying amount for each exact movement and its source reference.
-- This creates no warehouse movement or purchasing/consumption expense.
CREATE VIEW finance_stock_opening_source AS
 SELECT m.organization_id,m.id AS movement_id,m.stock_item_id,i.item_code,i.name AS item_name,
   m.to_storage_location_id,l.code AS location_code,m.quantity,m.unit_code,
   (m.occurred_at AT TIME ZONE 'UTC')::date AS opening_date,m.recorded_at,
   encode(sha256(convert_to(jsonb_build_array(m.id,m.stock_item_id,m.to_storage_location_id,
     m.quantity,m.unit_code,m.occurred_at,v.source_row_id,v.source_row_version,v.source_sha256)::text,'UTF8')),'hex') AS source_fingerprint
 FROM stock_movement m
 JOIN stock_item i ON i.organization_id=m.organization_id AND i.id=m.stock_item_id
 JOIN storage_location l ON l.organization_id=m.organization_id AND l.id=m.to_storage_location_id
 JOIN stock_import_verification v ON v.organization_id=m.organization_id AND v.source_row_id=m.source_import_row_id
 JOIN inventory_import_row r ON r.organization_id=v.organization_id AND r.id=v.source_row_id
 JOIN inventory_import_batch b ON b.organization_id=r.organization_id AND b.id=r.batch_id
 WHERE m.movement_type='INITIALIZE_OPENING' AND m.from_storage_location_id IS NULL
   AND v.storage_location_id=m.to_storage_location_id AND v.category_definition_id=i.category_definition_id
   AND v.cutover_approver_id=m.recorded_by AND v.cutover_at=m.occurred_at
   AND r.id=i.source_import_row_id AND r.review_state='APPROVED'
   AND r.approved_target_type='STOCK_ITEM' AND r.approved_target_id=i.id
   AND r.version=v.source_row_version+1 AND r.candidate_quantity=m.quantity AND r.candidate_uom=m.unit_code
   AND b.source_sha256=v.source_sha256 AND b.state<>'CANCELLED';

-- This immutable snapshot is intentionally independent of subsequent stock
-- movements or master-data edits; the carrying amount is an opening value only.
CREATE VIEW finance_gl_opening_stock_value AS
 SELECT j.organization_id,j.id AS opening_journal_id,j.opening_date,j.currency,
   (l.value->>'accountId')::uuid AS inventory_account_id,(v.value->>'movementId')::uuid AS movement_id,
   (v.value->>'quantity')::numeric(20,6) AS quantity,v.value->>'unit' AS unit_code,
   v.value->>'sourceFingerprint' AS source_fingerprint,(v.value->>'amount')::numeric(24,6) AS carrying_amount,
   v.value->>'valuationReference' AS valuation_reference
 FROM finance_gl_opening_journal j
 CROSS JOIN LATERAL jsonb_array_elements(j.lines) l(value)
 CROSS JOIN LATERAL jsonb_array_elements(coalesce(l.value->'stockValues','[]'::jsonb)) v(value);

CREATE OR REPLACE FUNCTION finance_gl_opening_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE fiscal finance_gl_period%ROWTYPE; item jsonb; account finance_gl_account%ROWTYPE;
 bank finance_bank_statement_intake%ROWTYPE; bank_count int; amount numeric; debit numeric:=0; credit numeric:=0;
 seen uuid[]:=ARRAY[]::uuid[];
 obligation jsonb; obligation_total numeric; obligation_key text; obligation_kind varchar(2);
 seen_obligations text[]:=ARRAY[]::text[]; obligation_count int:=0;
 stock_value jsonb; stock_source finance_stock_opening_source%ROWTYPE; stock_total numeric;
 seen_stock uuid[]:=ARRAY[]::uuid[]; stock_count int:=0;
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
      OR EXISTS(SELECT 1 FROM jsonb_object_keys(item) k WHERE k NOT IN('accountId','side','amount','reference','bankStatementId','obligations','stockValues'))
      OR EXISTS(SELECT 1 FROM jsonb_each(item) v WHERE v.key NOT IN('obligations','stockValues') AND jsonb_typeof(v.value)<>'string')
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
     OR account.account_kind NOT IN('FIXED_ASSET','OTHER_ASSET','OTHER_LIABILITY','EQUITY','BANK_ASSET','CUSTOMER_RECEIVABLE','SUPPLIER_PAYABLE','INVENTORY_ASSET')
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
   IF item->>'side'='DEBIT' THEN debit:=debit+amount; ELSE credit:=credit+amount; END IF;
 END LOOP;
 IF debit<=0 OR debit<>credit THEN RAISE EXCEPTION 'Opening debit and credit must balance exactly' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END; $$;
