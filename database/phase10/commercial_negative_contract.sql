-- Executed only after base Flyway and pending Phase10 schema, on disposable PostgreSQL.
-- Rollback all synthetic records. No business prices or approval roles are inferred.
BEGIN;
DO $$
DECLARE
 company_a uuid := '90000000-0000-4000-8000-000000000001';
 company_b uuid := '90000000-0000-4000-8000-000000000002';
 client_a uuid := '90000000-0000-4000-8000-000000000003';
 actor uuid := '90000000-0000-4000-8000-000000000004';
 opportunity uuid := '90000000-0000-4000-8000-000000000005';
 scope_id uuid := '90000000-0000-4000-8000-000000000006';
 boq_id uuid := '90000000-0000-4000-8000-000000000007';
 line_id uuid := '90000000-0000-4000-8000-000000000008';
 quote_id uuid := '90000000-0000-4000-8000-000000000009';
 error_message text;
BEGIN
 INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
 VALUES
  (company_a,'PHASE10_TEST_COMPANY_A','Synthetic Company A','Company A','HILTECH','ACTIVE',now()),
  (company_b,'PHASE10_TEST_COMPANY_B','Synthetic Company B','Company B','HILTECH','ACTIVE',now()),
  (client_a,'PHASE10_TEST_CLIENT_A','Synthetic Client A','Client A','CLIENT','ACTIVE',now());
 INSERT INTO user_identity(id,auth_provider,auth_subject,status,created_at)
 VALUES(actor,'phase10-test','synthetic-actor','ACTIVE',now());
 INSERT INTO commercial_opportunity
  (id,organization_id,client_organization_id,owner_user_id,source,opportunity_code,
   title,state,created_by,created_at,updated_at)
 VALUES(opportunity,company_a,client_a,actor,'RFQ','PHASE10_TEST_OPP','Synthetic commercial test','OPEN',actor,now(),now());
 INSERT INTO commercial_scope_version
  (id,organization_id,opportunity_id,version_number,description,inclusions,
   exclusions,assumptions,technical_constraints,client_responsibilities,created_by,created_at)
 VALUES(scope_id,company_a,opportunity,1,'Synthetic scope','Included','Excluded','Assumption','Constraint','Client duty',actor,now());
 INSERT INTO commercial_boq_version
  (id,organization_id,opportunity_id,scope_version_id,version_number,currency,created_by,created_at)
 VALUES(boq_id,company_a,opportunity,scope_id,1,'EGP',actor,now());
 INSERT INTO commercial_boq_cost_line
  (id,boq_version_id,line_code,description,category,quantity,unit,internal_unit_cost,sell_unit_price,created_at)
 VALUES(line_id,boq_id,'T1','Synthetic sample','LABOR',2,'hour',12.0000,20.0000,now());

 BEGIN
  UPDATE commercial_boq_cost_line SET internal_unit_cost=13 WHERE id=line_id;
  RAISE EXCEPTION 'EXPECTED_PRIVATE_SNAPSHOT_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%snapshot is immutable%' THEN
   RAISE EXCEPTION 'Private BOQ edit guard failed: %',error_message;
  END IF;
 END;

 BEGIN
  INSERT INTO commercial_scope_version
   (id,organization_id,opportunity_id,version_number,description,inclusions,
    exclusions,assumptions,technical_constraints,client_responsibilities,created_by,created_at)
  VALUES(gen_random_uuid(),company_b,opportunity,2,'Wrong tenant','Included','Excluded','Assumption','Constraint','Client duty',actor,now());
  RAISE EXCEPTION 'EXPECTED_CROSS_TENANT_DENIAL_MISSING';
 EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'PHASE10_CROSS_TENANT_FK_DENIED';
 END;

 INSERT INTO commercial_quote_version
  (id,organization_id,opportunity_id,client_organization_id,scope_version_id,boq_version_id,
   version_number,quote_code,currency,total_price,payment_terms,delivery_terms,exclusions,
   validity_date,created_by,created_at)
 VALUES(quote_id,company_a,opportunity,client_a,scope_id,boq_id,1,'PHASE10_Q_TEST',
   'EGP',40.0000,'Synthetic terms','Synthetic delivery','N/A',date '2026-12-01',actor,now());
 INSERT INTO commercial_quote_line
  (id,quote_version_id,line_code,description,quantity,unit,unit_price)
 VALUES(gen_random_uuid(),quote_id,'T1','Synthetic client line',2,'hour',20.0000);

 BEGIN
  UPDATE commercial_quote_version SET total_price=100,version=version+1 WHERE id=quote_id;
  RAISE EXCEPTION 'EXPECTED_QUOTE_PRICE_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Quoted commercial content cannot be edited%' THEN
   RAISE EXCEPTION 'Client quote immutability failed: %',error_message;
  END IF;
 END;

 BEGIN
  UPDATE commercial_quote_line SET unit_price=100 WHERE quote_version_id=quote_id;
  RAISE EXCEPTION 'EXPECTED_QUOTE_LINE_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%snapshot is immutable%' THEN
   RAISE EXCEPTION 'Client quote line immutability failed: %',error_message;
  END IF;
 END;
 RAISE NOTICE 'PHASE10_NEGATIVE_SCHEMA_CONTRACT_PASS';
END $$;
ROLLBACK;
