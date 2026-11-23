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
 contract_id uuid := '90000000-0000-4000-8000-00000000000a';
 po_id uuid := '90000000-0000-4000-8000-00000000000b';
 second_opportunity uuid := '90000000-0000-4000-8000-00000000000c';
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
 INSERT INTO commercial_tender_revision
  (id,organization_id,opportunity_id,revision_number,tender_type,received_at,created_by,created_at)
 VALUES(gen_random_uuid(),company_a,opportunity,1,'RFQ',now(),actor,now());
 BEGIN
  UPDATE commercial_tender_revision SET submission_state='SUBMITTED'
  WHERE opportunity_id=opportunity;
  RAISE EXCEPTION 'EXPECTED_TENDER_APPEND_ONLY_GUARD_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%snapshot is immutable%' THEN
   RAISE EXCEPTION 'Tender revision history can be overwritten: %',error_message;
  END IF;
 END;
 INSERT INTO commercial_qualification_record
  (id,organization_id,opportunity_id,discovery_summary,open_questions,qualified_by,qualified_at)
 VALUES(gen_random_uuid(),company_a,opportunity,'Synthetic discovery','Unresolved',actor,now());
 BEGIN
  UPDATE commercial_qualification_record SET discovery_summary='Rewritten'
  WHERE opportunity_id=opportunity;
  RAISE EXCEPTION 'EXPECTED_QUALIFICATION_APPEND_ONLY_GUARD_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%snapshot is immutable%' THEN
   RAISE EXCEPTION 'Recorded qualification can be overwritten: %',error_message;
  END IF;
 END;

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

 -- Publish only fully assembled BOQ snapshots; afterwards even INSERT is denied.
 UPDATE commercial_boq_version SET sealed_at=now() WHERE id=boq_id;
 BEGIN
  INSERT INTO commercial_boq_cost_line
   (id,boq_version_id,line_code,description,category,quantity,unit,sell_unit_price,created_at)
  VALUES(gen_random_uuid(),boq_id,'LATE','Late unapproved line','OTHER',1,'unit',1,now());
  RAISE EXCEPTION 'EXPECTED_SEALED_BOQ_APPEND_DENIAL_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Cannot append lines to sealed BOQ%' THEN
   RAISE EXCEPTION 'BOQ line append after publication succeeded: %',error_message;
  END IF;
 END;

 -- A missing internal cost is UNKNOWN, never interpreted as a zero-cost job.
 INSERT INTO commercial_boq_version
  (id,organization_id,opportunity_id,scope_version_id,version_number,currency,created_by,created_at)
 VALUES(gen_random_uuid(),company_a,opportunity,scope_id,2,'EGP',actor,now());
 INSERT INTO commercial_boq_cost_line
  (id,boq_version_id,line_code,description,category,quantity,unit,sell_unit_price,created_at)
 SELECT gen_random_uuid(),b.id,'UNCOSTED','Synthetic unknown cost',
        'MATERIAL',1,'item',5.0000,now()
 FROM commercial_boq_version b WHERE b.opportunity_id=opportunity AND b.version_number=2;
 UPDATE commercial_boq_version SET sealed_at=now()
 WHERE opportunity_id=opportunity AND version_number=2;
 IF NOT EXISTS(
   SELECT 1 FROM commercial_private_boq_cost_summary
   WHERE opportunity_id=opportunity AND version_number=2
     AND missing_cost_lines=1 AND complete_internal_cost_total IS NULL
     AND proposed_sell_total=5.0000
 ) THEN RAISE EXCEPTION 'Unknown internal costing was shown as complete'; END IF;


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
   'EGP',40.0000,'Synthetic terms','Synthetic delivery','N/A',CURRENT_DATE+30,actor,now());
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

 -- A quote may be queued for Approval Engine, but SQL cannot release it without
 -- an APPROVED request for that same immutable subject version.
 UPDATE commercial_quote_version SET state='APPROVAL_PENDING',version=version+1
 WHERE id=quote_id;
 BEGIN
  UPDATE commercial_quote_version
   SET state='RELEASED',released_at=now(),version=version+1
   WHERE id=quote_id;
  RAISE EXCEPTION 'EXPECTED_APPROVAL_GATE_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Exact latest quote approval is required%' THEN
   RAISE EXCEPTION 'SQL quote release authority gate failed: %',error_message;
  END IF;
 END;

 -- Even a valid released quote cannot be reversed into a draft.
 BEGIN
  UPDATE commercial_quote_version SET state='DRAFT',version=version+1 WHERE id=quote_id;
  RAISE EXCEPTION 'EXPECTED_QUOTE_STATE_MACHINE_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Quote state transition is not permitted%' THEN
   RAISE EXCEPTION 'SQL quote state guard failed: %',error_message;
  END IF;
 END;

 INSERT INTO commercial_opportunity
  (id,organization_id,client_organization_id,owner_user_id,source,opportunity_code,
   title,state,created_by,created_at,updated_at)
 VALUES(second_opportunity,company_a,client_a,actor,'DIRECT','PHASE10_SECOND_OPP',
        'Synthetic second opportunity','OPEN',actor,now(),now());
 INSERT INTO commercial_contract
  (id,organization_id,opportunity_id,quote_version_id,contract_code,state,created_by,created_at)
 VALUES(contract_id,company_a,opportunity,quote_id,'PHASE10_TEST_CONTRACT','DRAFT',actor,now());

 -- PO must belong to the *same opportunity* as its contract, not just company.
 BEGIN
  INSERT INTO commercial_client_po
   (id,organization_id,opportunity_id,contract_id,client_po_number,issued_on,
    amount,currency,created_by,created_at)
  VALUES(po_id,company_a,second_opportunity,contract_id,'PHASE10_BAD_PO',
         CURRENT_DATE,40.0000,'EGP',actor,now());
  RAISE EXCEPTION 'EXPECTED_CROSS_OPPORTUNITY_PO_DENIAL_MISSING';
 EXCEPTION WHEN foreign_key_violation THEN
  RAISE NOTICE 'PHASE10_CROSS_OPPORTUNITY_CONTRACT_PO_DENIED';
 END;

 INSERT INTO commercial_client_po
  (id,organization_id,opportunity_id,contract_id,client_po_number,issued_on,
   amount,currency,created_by,created_at)
 VALUES(po_id,company_a,opportunity,contract_id,'PHASE10_VALID_PO_DRAFT',
        CURRENT_DATE,40.0000,'EGP',actor,now());
 BEGIN
  UPDATE commercial_client_po SET verified=true WHERE id=po_id;
  RAISE EXCEPTION 'EXPECTED_PO_EVIDENCE_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Only verified evidence attachment is allowed%' THEN
   RAISE EXCEPTION 'Unverified client PO gate failed: %',error_message;
  END IF;
 END;

 BEGIN
  UPDATE commercial_contract SET state='SIGNED' WHERE id=contract_id;
  RAISE EXCEPTION 'EXPECTED_CONTRACT_EVIDENCE_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Only verified signing of draft contract is allowed%' THEN
   RAISE EXCEPTION 'Unsigned contract gate failed: %',error_message;
  END IF;
 END;

 BEGIN
  DELETE FROM commercial_contract WHERE id=contract_id;
  RAISE EXCEPTION 'EXPECTED_SIGNED_DOCUMENT_DELETION_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Commercial award documents cannot be deleted%' THEN
   RAISE EXCEPTION 'Award contract deletion guard failed: %',error_message;
  END IF;
 END;


 -- Direct SQL must not create a pre-approved quotation or forged signed award.
 BEGIN
  INSERT INTO commercial_quote_version
   (id,organization_id,opportunity_id,client_organization_id,scope_version_id,boq_version_id,
    version_number,quote_code,currency,total_price,payment_terms,delivery_terms,exclusions,
    validity_date,state,released_at,created_by,created_at)
  VALUES (gen_random_uuid(),company_a,opportunity,client_a,scope_id,boq_id,
          99,'FORGED-RELEASE','EGP',40,'fake','fake','fake',CURRENT_DATE+30,
          'RELEASED',now(),actor,now());
  RAISE EXCEPTION 'EXPECTED_FAKE_QUOTE_INSERT_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Quote must begin as an unreleased draft%' THEN
   RAISE EXCEPTION 'Direct released quotation insert was not rejected: %',error_message;
  END IF;
 END;

 BEGIN
  INSERT INTO commercial_contract
   (id,organization_id,opportunity_id,quote_version_id,contract_code,state,
    signed_evidence_id,created_by,created_at)
  VALUES(gen_random_uuid(),company_a,opportunity,quote_id,'FAKE-SIGNED-CONTRACT',
         'SIGNED',gen_random_uuid(),actor,now());
  RAISE EXCEPTION 'EXPECTED_FAKE_SIGNED_CONTRACT_INSERT_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Contract must begin unsigned%' THEN
   RAISE EXCEPTION 'Direct signed contract insert was not rejected: %',error_message;
  END IF;
 END;

 BEGIN
  INSERT INTO commercial_client_po
   (id,organization_id,opportunity_id,contract_id,client_po_number,issued_on,
    amount,currency,verified,evidence_id,created_by,created_at)
  VALUES(gen_random_uuid(),company_a,opportunity,contract_id,'FAKE-PO',
         CURRENT_DATE,40,'EGP',true,gen_random_uuid(),actor,now());
  RAISE EXCEPTION 'EXPECTED_FAKE_VERIFIED_PO_INSERT_BLOCK_MISSING';
 EXCEPTION WHEN OTHERS THEN
  GET STACKED DIAGNOSTICS error_message = MESSAGE_TEXT;
  IF error_message NOT LIKE '%Client PO must begin unverified%' THEN
   RAISE EXCEPTION 'Direct verified PO insert was not rejected: %',error_message;
  END IF;
 END;

 RAISE NOTICE 'PHASE10_NEGATIVE_SCHEMA_CONTRACT_PASS';
END $$;
ROLLBACK;
