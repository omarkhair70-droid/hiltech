-- P11 PostgreSQL negative source proof on disposable CI database.
-- Fixtures are synthetic and transaction-local. The entire script rolls back.
BEGIN;

INSERT INTO organization(id,organization_code,legal_name,display_name,organization_type,status,created_at)
VALUES
 ('a11a11a1-0000-4000-8000-000000000001','P11-CI-OWNER','CI HILTECH','CI HILTECH','HILTECH','ACTIVE',now()),
 ('a11a11a1-0000-4000-8000-000000000002','P11-CI-CLIENT','CI Client','CI Client','CLIENT','ACTIVE',now());
INSERT INTO user_identity(id,auth_provider,auth_subject,status,primary_organization_id,created_at)
VALUES('a11a11a1-0000-4000-8000-000000000003','phase11-ci','phase11-ci-actor','ACTIVE',
       'a11a11a1-0000-4000-8000-000000000001',now());
INSERT INTO project(id,organization_id,project_code,name,client_organization_id,
    lifecycle_state,source_type,created_at,created_by,updated_at)
VALUES('a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000001','P11-CI-PROJECT','CI Project',
       'a11a11a1-0000-4000-8000-000000000002','DRAFT','INTERNAL',
       now(),'a11a11a1-0000-4000-8000-000000000003',now());
INSERT INTO site(id,organization_id,client_organization_id,site_code,name,status,
    created_at,created_by,updated_at)
VALUES('a11a11a1-0000-4000-8000-000000000005',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000002',
       'P11-CI-SITE','CI Site','ACTIVE',
       now(),'a11a11a1-0000-4000-8000-000000000003',now());
INSERT INTO project_site(id,organization_id,project_id,site_id,lifecycle_state)
VALUES('a11a11a1-0000-4000-8000-000000000006',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000005','PLANNED');
INSERT INTO work_order(id,organization_id,project_id,site_id,project_site_id,
  work_order_code,title,lifecycle_state,readiness_state,priority_code,
  baseline_version,created_at,created_by,updated_at)
VALUES('a11a11a1-0000-4000-8000-000000000007',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000005',
       'a11a11a1-0000-4000-8000-000000000006',
       'P11-CI-WORK','Synthetic source WorkOrder','DRAFT','NOT_EVALUATED',
       'NORMAL',1,now(),'a11a11a1-0000-4000-8000-000000000003',now());

INSERT INTO service_support_ticket(
    id,organization_id,project_id,client_organization_id,
    title,description,severity_code,priority_code,source_operation_id,
    opened_by_user_id,created_at,updated_at)
VALUES('a11a11a1-0000-4000-8000-000000000008',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000002',
       'CI ticket','Internal synthetic event','MAJOR','P2',
       'a11a11a1-0000-4000-8000-000000000009',
       'a11a11a1-0000-4000-8000-000000000003',now(),now());
INSERT INTO service_support_case_event(
   id,ticket_id,organization_id,project_id,event_number,
   event_type,resulting_state,reason,source_operation_id,
   recorded_by_user_id,recorded_at)
VALUES('a11a11a1-0000-4000-8000-000000000010',
       'a11a11a1-0000-4000-8000-000000000008',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       1,'INVESTIGATION_STARTED','INVESTIGATING',
       'Verified internal investigation','a11a11a1-0000-4000-8000-000000000011',
       'a11a11a1-0000-4000-8000-000000000003',now());
INSERT INTO service_support_case_event(
   id,ticket_id,organization_id,project_id,event_number,
   event_type,resulting_state,work_order_id,reason,source_operation_id,
   recorded_by_user_id,recorded_at)
VALUES('a11a11a1-0000-4000-8000-000000000012',
       'a11a11a1-0000-4000-8000-000000000008',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       2,'WORK_ORDER_LINKED','INVESTIGATING',
       'a11a11a1-0000-4000-8000-000000000007',
       'Existing source work linked','a11a11a1-0000-4000-8000-000000000013',
       'a11a11a1-0000-4000-8000-000000000003',now());

INSERT INTO service_handover_package(
   id,organization_id,project_id,created_by,created_at)
VALUES('a11a11a1-0000-4000-8000-000000000014',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000003',now());
INSERT INTO service_handover_package_version(
   id,package_id,organization_id,project_id,version_number,
   created_operation_id,project_version_at_snapshot,source_digest,
   blockers_json,created_by,created_at)
VALUES('a11a11a1-0000-4000-8000-000000000015',
       'a11a11a1-0000-4000-8000-000000000014',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       1,'a11a11a1-0000-4000-8000-000000000016',1,repeat('a',64),
       '[]'::jsonb,'a11a11a1-0000-4000-8000-000000000003',now());
INSERT INTO service_handover_review_event(
  id,package_version_id,organization_id,project_id,event_number,
  event_type,review_state,source_digest,reason,created_by_user_id,created_at,operation_id)
VALUES('a11a11a1-0000-4000-8000-000000000017',
       'a11a11a1-0000-4000-8000-000000000015',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',1,
       'CHECK_STARTED','CHECKING',repeat('a',64),
       'Internal check only','a11a11a1-0000-4000-8000-000000000003',now(),
       'a11a11a1-0000-4000-8000-000000000018');

INSERT INTO service_maintenance_visit_plan(
    id,organization_id,project_id,work_order_id,site_id,
    planned_for,work_order_version_at_plan,
    source_operation_id,created_by_user_id,created_at)
VALUES('a11a11a1-0000-4000-8000-000000000019',
       'a11a11a1-0000-4000-8000-000000000001',
       'a11a11a1-0000-4000-8000-000000000004',
       'a11a11a1-0000-4000-8000-000000000007',
       'a11a11a1-0000-4000-8000-000000000005',
       now()+interval '1 day',1,'a11a11a1-0000-4000-8000-000000000020',
       'a11a11a1-0000-4000-8000-000000000003',now());

DO $$
DECLARE
 ticket_count integer;
BEGIN
 SELECT count(*) INTO ticket_count FROM service_support_case_event
  WHERE ticket_id='a11a11a1-0000-4000-8000-000000000008';
 IF ticket_count<>2 THEN RAISE EXCEPTION 'Expected two genuine valid support events'; END IF;

 -- Cannot falsely close a still-investigating ticket.
 BEGIN
  INSERT INTO service_support_case_event(
     id,ticket_id,organization_id,project_id,event_number,
     event_type,resulting_state,reason,source_operation_id,recorded_by_user_id,recorded_at)
  VALUES('a11a11a1-0000-4000-8000-000000000021',
         'a11a11a1-0000-4000-8000-000000000008',
         'a11a11a1-0000-4000-8000-000000000001',
         'a11a11a1-0000-4000-8000-000000000004',
         3,'INTERNAL_CLOSE_RECORDED','CLOSED_INTERNAL','Forged closure',
         'a11a11a1-0000-4000-8000-000000000022',
         'a11a11a1-0000-4000-8000-000000000003',now());
  RAISE EXCEPTION 'INVALID: forged support closure accepted';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS support closure without resolution rejected';
 END;

 -- WorkOrder is still DRAFT; an invented completed resolution is rejected.
 BEGIN
  INSERT INTO service_support_case_event(
     id,ticket_id,organization_id,project_id,event_number,
     event_type,resulting_state,work_order_id,evidence_id,reason,source_operation_id,
     recorded_by_user_id,recorded_at)
  VALUES('a11a11a1-0000-4000-8000-000000000023',
         'a11a11a1-0000-4000-8000-000000000008',
         'a11a11a1-0000-4000-8000-000000000001',
         'a11a11a1-0000-4000-8000-000000000004',
         3,'RESOLUTION_RECORDED','RESOLVED_INTERNAL',
         'a11a11a1-0000-4000-8000-000000000007',
         'a11a11a1-0000-4000-8000-000000000024',
         'Forged accepted work','a11a11a1-0000-4000-8000-000000000025',
         'a11a11a1-0000-4000-8000-000000000003',now());
  RAISE EXCEPTION 'INVALID: forged unaccepted-work resolution accepted';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS unaccepted WorkOrder resolution rejected';
 END;

 -- Handover review cannot skip event sequence or fake an approval.
 BEGIN
  INSERT INTO service_handover_review_event(
    id,package_version_id,organization_id,project_id,event_number,
    event_type,review_state,source_digest,reason,created_by_user_id,created_at,operation_id)
  VALUES('a11a11a1-0000-4000-8000-000000000026',
         'a11a11a1-0000-4000-8000-000000000015',
         'a11a11a1-0000-4000-8000-000000000001',
         'a11a11a1-0000-4000-8000-000000000004',
         3,'FINDING_RECORDED','FINDINGS_RECORDED',repeat('a',64),
         'Wrong sequence','a11a11a1-0000-4000-8000-000000000003',now(),
         'a11a11a1-0000-4000-8000-000000000027');
  RAISE EXCEPTION 'INVALID: stale handover sequence accepted';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS handover source sequence guard';
 END;

 -- Append-only protection catches direct SQL updates.
 BEGIN
  UPDATE service_support_ticket SET title='Forged change'
    WHERE id='a11a11a1-0000-4000-8000-000000000008';
  RAISE EXCEPTION 'INVALID: mutable source support ticket';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS support ticket immutability';
 END;

 BEGIN
  UPDATE service_handover_review_event SET reason='Forged edit'
    WHERE id='a11a11a1-0000-4000-8000-000000000017';
  RAISE EXCEPTION 'INVALID: mutable handover review evidence';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS internal review immutability';
 END;

 -- Changing client organization is forbidden even if both org IDs exist.
 BEGIN
  INSERT INTO service_support_ticket(
    id,organization_id,project_id,client_organization_id,
    title,description,severity_code,priority_code,
    source_operation_id,opened_by_user_id,created_at,updated_at)
  VALUES('a11a11a1-0000-4000-8000-000000000028',
         'a11a11a1-0000-4000-8000-000000000001',
         'a11a11a1-0000-4000-8000-000000000004',
         'a11a11a1-0000-4000-8000-000000000001',
         'Wrong client','Attempted cross-tenant service','MAJOR','P2',
         'a11a11a1-0000-4000-8000-000000000029',
         'a11a11a1-0000-4000-8000-000000000003',now(),now());
  RAISE EXCEPTION 'INVALID: support ticket client substitution accepted';
 EXCEPTION WHEN check_violation THEN
  RAISE NOTICE 'PASS client context mismatch rejected';
 END;
 RAISE NOTICE 'PHASE11_SOURCE_NEGATIVE_PROOFS_PASS';
END;
$$;
ROLLBACK;
