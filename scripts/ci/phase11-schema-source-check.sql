-- Phase11 integration smoke after installing all pending 11A/11A2/11B/11B2/11B3/11C SQL.
-- This script asserts schema invariants; it does not claim live service coverage.
BEGIN;
DO $$
DECLARE
 tables_found integer;
 immutability_gates integer;
 source_gates integer;
BEGIN
 SELECT count(*) INTO tables_found FROM pg_class
 WHERE relnamespace='public'::regnamespace AND relkind='r'
   AND relname IN (
      'service_handover_package','service_handover_package_version',
      'service_handover_package_item','service_handover_review_event',
      'service_support_ticket','service_support_internal_note',
      'service_support_triage_event','service_support_case_event',
      'service_maintenance_visit_plan','service_maintenance_finding',
      'service_maintenance_ticket_followup'
   );
 IF tables_found<>11 THEN
   RAISE EXCEPTION 'Expected eleven Phase11 service tables, got %',tables_found;
 END IF;
 SELECT count(*) INTO immutability_gates FROM pg_trigger tr
 JOIN pg_class c ON c.oid=tr.tgrelid
 WHERE NOT tr.tgisinternal AND c.relname IN (
      'service_handover_package','service_handover_package_version',
      'service_handover_package_item','service_handover_review_event',
      'service_support_ticket','service_support_internal_note',
      'service_support_triage_event','service_support_case_event',
      'service_maintenance_visit_plan','service_maintenance_finding',
      'service_maintenance_ticket_followup'
 ) AND tr.tgname ~ '(immutable|no_truncate)';
 IF immutability_gates<22 THEN
   RAISE EXCEPTION 'Insufficient append-only mutation gates: %',immutability_gates;
 END IF;
 SELECT count(*) INTO source_gates FROM pg_trigger tr
 JOIN pg_class c ON c.oid=tr.tgrelid
 WHERE NOT tr.tgisinternal AND c.relname IN (
       'service_handover_package_item','service_handover_review_event',
       'service_support_ticket','service_support_triage_event',
       'service_support_case_event','service_maintenance_finding',
       'service_maintenance_ticket_followup'
 ) AND tr.tgname ~ '(source|owner|evidence)';
 IF source_gates<7 THEN
   RAISE EXCEPTION 'Insufficient source validation gates: %',source_gates;
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM pg_constraint
    WHERE conname='fk_support_case_work' AND contype='f'
 ) THEN
   RAISE EXCEPTION 'Missing case to canonical WorkOrder composite FK';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM pg_constraint
    WHERE conname='fk_maintenance_visit_work_context' AND contype='f'
 ) THEN
   RAISE EXCEPTION 'Missing maintenance visit to canonical WorkOrder composite FK';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM pg_constraint
    WHERE conname='fk_maintenance_ticket_source' AND contype='f'
 ) THEN
   RAISE EXCEPTION 'Missing follow-up link to canonical support ticket';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM pg_constraint
    WHERE conname='fk_handover_project_org' AND contype='f'
 ) THEN
   RAISE EXCEPTION 'Missing project/organization handover tenancy FK';
 END IF;
 RAISE NOTICE 'PHASE11_SCHEMA_SOURCE_GATES_PASS: tables=%, immutable_gates=%, source_gates=%',
   tables_found,immutability_gates,source_gates;
END;
$$;
ROLLBACK;
