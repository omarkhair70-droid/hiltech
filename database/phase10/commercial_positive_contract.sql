-- Phase 10 SQL acceptance: real FK/triggers/views with SYNTHETIC records only.
-- Disposable PostgreSQL after base Flyway + pending commercial schema.
-- A successful run confirms schema contract, not server/OIDC/native acceptance.
-- All data is rolled back. Never run this against production.
BEGIN;
DO $$
<<phase10_fixture>>
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
  receipt_id uuid := gen_random_uuid();
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
  INSERT INTO commercial_tender_revision
    (id,organization_id,opportunity_id,revision_number,tender_type,
     reference_number,received_at,submission_state,created_by,created_at)
  VALUES(gen_random_uuid(),company,opportunity,1,'RFQ',
         'SYNTHETIC-RFQ-1',now(),'RECEIVED',actor,now());
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
     payment_terms,delivery_terms,exclusions,validity_date,created_by,created_at,discount_amount,tax_amount)
  VALUES(quote_id,company,opportunity,client,scope_id,boq_id,1,'SYNTHETIC-Q',
         'EGP',42,'Synthetic 30-day payment','Synthetic on-site delivery',
         'Synthetic exclusions',CURRENT_DATE+30,actor,now(),5,7);
  INSERT INTO commercial_quote_line
    (id,quote_version_id,line_code,description,quantity,unit,unit_price)
  VALUES(gen_random_uuid(),quote_id,'LABOR-1','Synthetic labor',2,'hour',20);
  UPDATE commercial_quote_version SET state='APPROVAL_PENDING',version=version+1 WHERE id=quote_id;
  IF NOT EXISTS(SELECT 1 FROM commercial_quote_version q WHERE q.id=quote_id AND q.discount_amount=5 AND q.tax_amount=7 AND q.total_price=42) THEN
    RAISE EXCEPTION 'Net quotation amount did not retain separate discount and tax';
  END IF;
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
  IF NOT EXISTS (SELECT 1 FROM commercial_released_client_quote AS released
                 WHERE released.quote_id=phase10_fixture.quote_id AND released.total_price=42) THEN
    RAISE EXCEPTION 'Exact approved quote was not released to customer projection';
  END IF;
  -- New document settings, immutable issuer snapshot and export archive.
  INSERT INTO commercial_document_settings(organization_id,version,legal_name,updated_by,updated_at)
  VALUES(company,1,'Synthetic issuer before update',actor,now());
  INSERT INTO commercial_quote_issuer_snapshot(quote_version_id,legal_name)
  VALUES(quote_id,'Synthetic issuer before update');
  UPDATE commercial_document_settings SET legal_name='Synthetic issuer after update',version=2 WHERE organization_id=company;
  IF NOT EXISTS(SELECT 1 FROM commercial_quote_issuer_snapshot s WHERE s.quote_version_id=quote_id AND s.legal_name='Synthetic issuer before update') THEN
    RAISE EXCEPTION 'Released issuer snapshot changed';
  END IF;
  INSERT INTO commercial_customer_pdf_archive(quote_version_id,filename,template_version,pdf_bytes,sha256,created_at,created_by)
  VALUES(quote_id,'synthetic.pdf',1,convert_to('%PDF-fixture','UTF8'),digest,now(),actor);
  BEGIN
    UPDATE commercial_customer_pdf_archive SET filename='changed.pdf' WHERE quote_version_id=quote_id;
    RAISE EXCEPTION 'EXPECTED_ARCHIVE_IMMUTABILITY';
  EXCEPTION WHEN OTHERS THEN
    GET STACKED DIAGNOSTICS err=MESSAGE_TEXT;
    IF err NOT LIKE '%archive is immutable%' THEN RAISE EXCEPTION 'Archive immutability failed: %',err; END IF;
  END;
  INSERT INTO commercial_quote_email_attempt(id,quote_version_id,recipient,actor_id,state)
  VALUES(gen_random_uuid(),quote_id,'fixture@example.invalid',actor,'API_ACCEPTED');
  INSERT INTO commercial_quote_whatsapp_attempt(id,quote_version_id,recipient,actor_id,state,sender_phone_id,provider_message_id)
  VALUES(receipt_id,quote_id,'201000000000',actor,'API_ACCEPTED','123456','wamid.synthetic');
  UPDATE commercial_quote_whatsapp_attempt SET state='DELIVERED',provider_status_at=now() WHERE id=receipt_id;
  UPDATE commercial_quote_whatsapp_attempt SET state='READ',provider_status_at=now() WHERE id=receipt_id;
  IF NOT EXISTS(SELECT 1 FROM commercial_quote_whatsapp_attempt WHERE id=receipt_id AND state='READ') THEN
    RAISE EXCEPTION 'Provider receipt status was not persisted';
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
         CURRENT_DATE,42,'EGP',actor,now());

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
  INSERT INTO commercial_tender_revision
    (id,organization_id,opportunity_id,revision_number,tender_type,
     reference_number,received_at,submission_state,created_by,created_at)
  VALUES(gen_random_uuid(),company,opportunity,2,'RFQ',
         'SYNTHETIC-RFQ-1',now(),'AWARDED',actor,now());
  IF (SELECT count(*) FROM commercial_tender_revision
      WHERE opportunity_id=opportunity AND submission_state='AWARDED')<>1 THEN
    RAISE EXCEPTION 'Canonical tender award must produce one terminal revision';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM commercial_opportunity
                 WHERE id=opportunity AND state='AWARDED') THEN
    RAISE EXCEPTION 'Canonical award handoff failed to close opportunity';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_finance_award_source AS finance
                WHERE finance.opportunity_id=opportunity AND finance.project_id=phase10_fixture.project_id
                  AND finance.contract_id=phase10_fixture.contract_id AND finance.client_po_id=po_id
                  AND quote_revision=1 AND total_price=42) THEN
    RAISE EXCEPTION 'Finance source handoff was not complete';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_project_scope_source AS scope
                WHERE scope.project_id=phase10_fixture.project_id AND scope.scope_version_id=scope_id) THEN
    RAISE EXCEPTION 'Project source scope reference missing';
  END IF;
  IF NOT EXISTS(SELECT 1 FROM commercial_support_contract_source AS support
                WHERE support.project_id=phase10_fixture.project_id AND support.signed_evidence_id=contract_file) THEN
    RAISE EXCEPTION 'Support contract source reference missing';
  END IF;
  -- Unified contract proof: the SAME released customer award is consumed by
  -- Finance for review and by Service11 for written coverage, scoped to the
  -- same organization/project/contract. No invoice or warranty activation.
  IF NOT EXISTS(
      SELECT 1 FROM commercial_finance_award_source finance
      JOIN commercial_support_contract_source support
        ON support.organization_id = finance.organization_id
       AND support.project_id = finance.project_id
       AND support.contract_id = finance.contract_id
      WHERE finance.organization_id = company
        AND finance.opportunity_id = opportunity
        AND finance.project_id = phase10_fixture.project_id
        AND finance.contract_id = phase10_fixture.contract_id
        AND finance.client_po_id = po_id
        AND finance.total_price = 42 AND finance.currency = 'EGP'
        AND support.signed_evidence_id = contract_file
        AND support.warranty_terms = 'Synthetic coverage'
        AND support.maintenance_terms = 'Synthetic support'
  ) THEN
    RAISE EXCEPTION 'Finance8 and Service11 did not consume the same canonical Sales10 award';
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
