-- Phase 10 SQL acceptance: real FK/triggers/views with SYNTHETIC records only.
-- Disposable PostgreSQL after base Flyway + pending commercial schema.
-- A successful run confirms schema contract, not server/OIDC/native acceptance.
-- All data is rolled back. Never run this against production.
BEGIN;
DO $$
DECLARE
  company uuid := gen_random_uuid();
  client uuid := gen_random_uuid();
  actor uuid := gen_random_uuid();
  opportunity uuid := gen_random_uuid();
  scope_id uuid := gen_random_uuid();
  boq_id uuid := gen_random_uuid();
  quote_id uuid := gen_random_uuid();
  contract_id uuid := gen_random_uuid();
  po_id uuid := gen_random_uuid();
  project_id uuid := gen_random_uuid();
  wrong_project uuid := gen_random_uuid();
  contract_file uuid := gen_random_uuid();
  po_file uuid := gen_random_uuid();
  partial_po uuid := gen_random_uuid();
  partial_file uuid := gen_random_uuid();
  digest text := repeat('a',64);
  err text;
BEGIN
  INSERT INTO organization (id,organization_code,legal_name,display_name,organization_type,status,created_at)
  VALUES (company,'PHASE10_POS_CO_'||company,'Synthetic HILTECH test','Synthetic HILTECH','HILTECH','ACTIVE',now()),
         (client,'PHASE10_POS_CLI_'||client,'Synthetic customer test','Synthetic Client','CLIENT','ACTIVE',now());
  INSERT INTO user_identity(id,auth_provider,auth_subject,status,created_at)
  VALUES(actor,'phase10-positive',actor::text,'ACTIVE',now());
  INSERT INTO commercial_opportunity
    (id,organization_id,client_organization_id,owner_user_id,source,
     opportunity_code,title,created_by,created_at,updated_at)
  VALUES(opportunity,company,client,actor,'RFQ',
         'SYNTHETIC-'||opportunity,'Synthetic quotation integration',actor,now(),now());
  INSERT INTO commercial_scope_version
    (id,organization_id,opportunity_id,version_number,description,inclusions,
     exclusions,assumptions,technical_constraints,client_responsibilities,created_by,created_at)
  VALUES(scope_id,company,opportunity,1,'Synthetic field survey',
         'Installation','Not included','Dry weather','No fiber splicing','Client provides access',actor,now());
  INSERT INTO commercial_boq_version
    (id,organization_id,opportunity_id,scope_version_id,version_number,currency,created_by,created_at)
  VALUES(boq_id,company,opportunity,scope_id,1,'EGP',actor,now());
  INSERT INTO commercial_boq_cost_line
    (id,boq_version_id,line_code,description,category,quantity,unit,
     internal_unit_cost,sell_unit_price,created_at)
  VALUES(gen_random_uuid(),boq_id,'LABOR-1','Synthetic labor','LABOR',2,'hour',10,20,now());
  UPDATE commercial_boq_version SET sealed_at=now() WHERE id=boq_id;
  INSERT INTO commercial_quote_version
    (id,organization_id,opportunity_id,client_organization_id,scope_version_id,
     boq_version_id,version_number,quote_code,currency,total_price,
     payment_terms,delivery_terms,exclusions,validity_date,created_by,created_at)
  VALUES(quote_id,company,opportunity,client,scope_id,boq_id,1,'SYNTHETIC-Q',
         'EGP',40,'Synthetic 30-day payment','Synthetic on-site delivery',
         'Synthetic exclusions',CURRENT_DATE+30,actor,now());
  INSERT INTO commercial_quote_line
    (id,quote_version_id,line_code,description,quantity,unit,unit_price)
  VALUES(gen_random_uuid(),quote_id,'LABOR-1','Synthetic labor',2,'hour',20);
  UPDATE commercial_quote_version SET state='APPROVAL_PENDING',version=version+1 WHERE id=quote_id;
  INSERT INTO approval_policy_version
    (id,organization_id,policy_key,version_number,lifecycle_state,approval_required,
     step_mode,authority_key,effective_from,created_by_user_id,created_at)
  VALUES(gen_random_uuid(),company,'COMMERCIAL_QUOTE_RELEASE',1,'ACTIVE',true,'SINGLE',
         'SYNTHETIC_SALES_APPROVER',now()-interval '1 day',actor,now());
  INSERT INTO approval_request
    (id,organization_id,subject_type,subject_id,subject_version,policy_key,
     policy_version,requester_user_id,state,reason_code,created_at,completed_at)
  VALUES(gen_random_uuid(),company,'COMMERCIAL_QUOTE',quote_id,2,
         'COMMERCIAL_QUOTE_RELEASE',1,actor,'APPROVED','COMMERCIAL_QUOTE_REVIEW',now(),now());
  UPDATE commercial_quote_version SET state='RELEASED',released_at=now(),version=version+1
  WHERE id=quote_id;
  IF NOT EXISTS (SELECT 1 FROM commercial_released_client_quote
                 WHERE quote_id=quote_id AND total_price=40) THEN
    RAISE EXCEPTION 'Exact approved quote was not released to customer projection';
  END IF;
  INSERT INTO commercial_contract
    (id,organization_id,opportunity_id,quote_version_id,contract_code,
     state,warranty_terms,maintenance_terms,created_by,created_at)
  VALUES(contract_id,company,opportunity,quote_id,'SYNTHETIC-CONTRACT',
         'DRAFT','Synthetic coverage','Synthetic support',actor,now());
  INSERT INTO commercial_client_po
    (id,organization_id,opportunity_id,contract_id,client_po_number,
     issued_on,amount,currency,created_by,created_at)
  VALUES(po_id,company,opportunity,contract_id,'SYNTHETIC-PO',
         CURRENT_DATE,40,'EGP',actor,now());

  -- Two documents with bytes that would be READY only after the canonical
  -- uploader has verified matching PDF bytes. These are synthetic DB fixtures.
  INSERT INTO evidence(
    id,organization_id,target_type,target_id,evidence_type_code,
    content_type,size_bytes,sha256,captured_at,captured_by_user_id,
    storage_state,object_key_ref,finalized_at,classification_code,
    client_visibility_mode,security_scan_class,created_at)
  VALUES
    (contract_file,company,'COMMERCIAL_CONTRACT',contract_id,
     'COMMERCIAL_SIGNED_CONTRACT','application/pdf',120,digest,now(),actor,
     'READY','test-contract/'||contract_file,now(),'HIGHLY_RESTRICTED',
     'INTERNAL_ONLY','SIGNED_DOCUMENT',now()),
    (po_file,company,'COMMERCIAL_CLIENT_PO',po_id,
     'COMMERCIAL_CLIENT_PO','application/pdf',120,digest,now(),actor,
     'READY','test-po/'||po_file,now(),'HIGHLY_RESTRICTED',
     'INTERNAL_ONLY','SIGNED_DOCUMENT',now());
  INSERT INTO evidence_upload_session(
    id,evidence_id,expected_sha256,expected_size_bytes,
    expires_at,state,created_at,finalized_at,operation_id)
  VALUES
    (gen_random_uuid(),contract_file,digest,120,now()+interval '1 hour',
     'READY',now(),now(),gen_random_uuid()),
    (gen_random_uuid(),po_file,digest,120,now()+interval '1 hour',
     'READY',now(),now(),gen_random_uuid());
  UPDATE commercial_contract
    SET state='SIGNED',signed_evidence_id=contract_file WHERE id=contract_id;
  UPDATE commercial_client_po
    SET verified=true,evidence_id=po_file WHERE id=po_id;

  -- This Project is a synthetic proxy for ProjectsService.createProject;
  -- its exact source reference is checked by the handoff trigger.
  INSERT INTO project(id,organization_id,project_code,name,client_organization_id,
                      lifecycle_state,source_type,source_external_reference,
                      created_at,created_by,updated_at)
  VALUES(project_id,company,'SYNTHETIC-PROJECT','Synthetic awarded project',client,
         'DRAFT','IMPORT','COMMERCIAL:'||opportunity||':QUOTE:'||quote_id,
         now(),actor,now());
  -- A partially funded PO may be retained as evidence, but must NOT silently
  -- convert the whole quotation into a won Project.
  INSERT INTO commercial_client_po
    (id,organization_id,opportunity_id,contract_id,client_po_number,
     issued_on,amount,currency,created_by,created_at)
  VALUES(partial_po,company,opportunity,contract_id,'SYNTHETIC-PARTIAL-PO',
         CURRENT_DATE,30,'EGP',actor,now());
  INSERT INTO evidence(
    id,organization_id,target_type,target_id,evidence_type_code,
    content_type,size_bytes,sha256,captured_at,captured_by_user_id,
    storage_state,object_key_ref,finalized_at,classification_code,
    client_visibility_mode,security_scan_class,created_at)
  VALUES(partial_file,company,'COMMERCIAL_CLIENT_PO',partial_po,
         'COMMERCIAL_CLIENT_PO','application/pdf',120,digest,now(),actor,
         'READY','test-partial-po/'||partial_file,now(),'HIGHLY_RESTRICTED',
         'INTERNAL_ONLY','SIGNED_DOCUMENT',now());
  INSERT INTO evidence_upload_session(
    id,evidence_id,expected_sha256,expected_size_bytes,
    expires_at,state,created_at,finalized_at,operation_id)
  VALUES(gen_random_uuid(),partial_file,digest,120,now()+interval '1 hour',
         'READY',now(),now(),gen_random_uuid());
  UPDATE commercial_client_po SET verified=true,evidence_id=partial_file
  WHERE id=partial_po;
  BEGIN
    INSERT INTO commercial_project_handoff(
      opportunity_id,organization_id,quote_version_id,contract_id,client_po_id,
      project_id,created_by,created_at)
    VALUES(opportunity,company,quote_id,contract_id,partial_po,project_id,actor,now());
    RAISE EXCEPTION 'EXPECTED_PARTIAL_CUSTOMER_PO_REJECT';
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS err = MESSAGE_TEXT;
    IF err NOT LIKE '%Project handoff requires exact ready award and same client%' THEN
      RAISE EXCEPTION 'Partially valued PO converted full project: %',err;
    END IF;
  END;

  INSERT INTO commercial_project_handoff(
    opportunity_id,organization_id,quote_version_id,contract_id,client_po_id,
    project_id,created_by,created_at)
  VALUES(opportunity,company,quote_id,contract_id,po_id,project_id,actor,now());

  UPDATE commercial_opportunity
    SET state='AWARDED',version=version+1,updated_at=now()
    WHERE id=opportunity;
  IF NOT EXISTS (SELECT 1 FROM commercial_opportunity
                 WHERE id=opportunity AND state='AWARDED') THEN
    RAISE EXCEPTION 'Canonical award handoff failed to close opportunity';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_finance_award_source
                WHERE opportunity_id=opportunity AND project_id=project_id
                  AND contract_id=contract_id AND client_po_id=po_id
                  AND quote_revision=1 AND total_price=40) THEN
    RAISE EXCEPTION 'Finance source handoff was not complete';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_project_scope_source
                WHERE project_id=project_id AND scope_version_id=scope_id) THEN
    RAISE EXCEPTION 'Project source scope reference missing';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_support_contract_source
                WHERE project_id=project_id AND signed_evidence_id=contract_file) THEN
    RAISE EXCEPTION 'Support contract source reference missing';
  END IF;
  IF (SELECT count(*) FROM commercial_project_handoff WHERE opportunity_id=opportunity)<>1 THEN
    RAISE EXCEPTION 'Exactly one canonical Project handoff was expected';
  END IF;

  -- Nobody may rebind the awarded Project ID after the fact.
  BEGIN
    UPDATE commercial_project_handoff SET project_id=gen_random_uuid()
    WHERE opportunity_id=opportunity;
    RAISE EXCEPTION 'EXPECTED_HANDOFF_IMMUTABILITY';
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS err = MESSAGE_TEXT;
    IF err NOT LIKE '%snapshot is immutable%' THEN
      RAISE EXCEPTION 'Handoff immutability failed: %',err;
    END IF;
  END;

  RAISE NOTICE 'PHASE10_SYNTHETIC_AWARD_HANDOFF_PASS';
END $$;
ROLLBACK;
