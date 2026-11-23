-- P11C3: immutable internal maintenance visit technical review.
-- Requires 11C. No customer sign-off, contract coverage, recurring schedule or SLA.
CREATE TABLE service_maintenance_review_event (
  id uuid PRIMARY KEY,
  visit_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  work_order_id uuid NOT NULL,
  event_number integer NOT NULL CHECK(event_number>0),
  event_type varchar(40) NOT NULL CHECK(event_type IN (
    'REVIEW_STARTED','INTERNAL_TECHNICAL_COMPLETION','RECHECK_REQUESTED')),
  resulting_state varchar(32) NOT NULL CHECK(resulting_state IN (
    'REVIEWING','COMPLETED_INTERNAL','RECHECK_REQUIRED')),
  source_work_version bigint NOT NULL CHECK(source_work_version>0),
  evidence_id uuid REFERENCES evidence(id) ON DELETE RESTRICT,
  reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 4000),
  source_operation_id uuid NOT NULL UNIQUE,
  recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  recorded_at timestamptz NOT NULL,
  visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY'
    CHECK(visibility='INTERNAL_ONLY'),
  CONSTRAINT fk_maintenance_review_visit FOREIGN KEY (
    visit_id,organization_id,project_id,work_order_id)
    REFERENCES service_maintenance_visit_plan (
      id,organization_id,project_id,work_order_id) ON DELETE RESTRICT,
  CONSTRAINT uq_maintenance_review_number UNIQUE(visit_id,event_number),
  CONSTRAINT ck_maintenance_review_proof_shape CHECK(
    (event_type='INTERNAL_TECHNICAL_COMPLETION' AND
     resulting_state='COMPLETED_INTERNAL' AND evidence_id IS NOT NULL)
    OR
    (event_type<>'INTERNAL_TECHNICAL_COMPLETION' AND evidence_id IS NULL)
  )
);
CREATE INDEX idx_maintenance_review_history
 ON service_maintenance_review_event(visit_id,event_number DESC);

CREATE FUNCTION service_maintenance_review_source_gate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  visit_plan service_maintenance_visit_plan%ROWTYPE;
  work_state text;
  work_ver bigint;
  latest_type text;
  latest_state text;
  latest_number integer;
  latest_at timestamptz;
  total_required integer;
  pending_required integer;
BEGIN
  -- Lock the immutable visit so competing direct writes and service writes
  -- serialize even when there is not yet a review event.
  SELECT * INTO visit_plan FROM service_maintenance_visit_plan
   WHERE id=NEW.visit_id AND organization_id=NEW.organization_id
     AND project_id=NEW.project_id AND work_order_id=NEW.work_order_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Maintenance review must use an existing canonical visit'
      USING ERRCODE='23514';
  END IF;
  SELECT lifecycle_state,version INTO work_state,work_ver FROM work_order
   WHERE id=NEW.work_order_id AND organization_id=NEW.organization_id
     AND project_id=NEW.project_id AND site_id=visit_plan.site_id FOR SHARE;
  IF NOT FOUND OR work_ver IS DISTINCT FROM NEW.source_work_version THEN
    RAISE EXCEPTION 'Maintenance technical review WorkOrder version is stale'
      USING ERRCODE='23514';
  END IF;
  SELECT event_type,resulting_state,event_number,recorded_at
    INTO latest_type,latest_state,latest_number,latest_at
    FROM service_maintenance_review_event WHERE visit_id=NEW.visit_id
   ORDER BY event_number DESC LIMIT 1;
  latest_number:=COALESCE(latest_number,0);
  IF NEW.event_number<>latest_number+1 THEN
    RAISE EXCEPTION 'Maintenance review must append next event number'
      USING ERRCODE='23514';
  END IF;
  IF NEW.recorded_at < COALESCE(latest_at,visit_plan.created_at)
     OR NEW.recorded_at > now()+interval '5 minutes' THEN
    RAISE EXCEPTION 'Maintenance review timestamp invalid'
      USING ERRCODE='23514';
  END IF;
  IF NOT (
    (NEW.event_type='REVIEW_STARTED'
      AND (latest_number=0 OR latest_state='RECHECK_REQUIRED')
      AND NEW.resulting_state='REVIEWING')
    OR (NEW.event_type='RECHECK_REQUESTED'
      AND latest_state='COMPLETED_INTERNAL'
      AND NEW.resulting_state='RECHECK_REQUIRED')
    OR (NEW.event_type='INTERNAL_TECHNICAL_COMPLETION'
      AND latest_state='REVIEWING'
      AND NEW.resulting_state='COMPLETED_INTERNAL')
  ) THEN
    RAISE EXCEPTION 'Invalid internal maintenance review transition'
      USING ERRCODE='23514';
  END IF;
  IF NEW.event_type='INTERNAL_TECHNICAL_COMPLETION' THEN
    IF NEW.recorded_at < visit_plan.planned_for OR
       work_state NOT IN ('ACCEPTED','CLOSED') THEN
      RAISE EXCEPTION 'Technical completion needs visit window and accepted WorkOrder'
        USING ERRCODE='23514';
    END IF;
    SELECT count(*) FILTER (WHERE required),
           count(*) FILTER (WHERE required AND completion_state<>'COMPLETE')
      INTO total_required,pending_required
      FROM work_checklist_item_instance WHERE work_order_id=NEW.work_order_id;
    IF total_required=0 OR pending_required>0 THEN
      RAISE EXCEPTION 'Required canonical technical checklist incomplete'
        USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS(
      SELECT 1 FROM evidence e
       WHERE e.id=NEW.evidence_id AND e.organization_id=NEW.organization_id
         AND e.work_order_id=NEW.work_order_id
         AND e.target_type='WORK_ORDER' AND e.target_id=NEW.work_order_id
         AND e.storage_state='READY'
         AND e.captured_at>=GREATEST(visit_plan.created_at,visit_plan.planned_for)
         AND e.captured_at<=NEW.recorded_at
         AND NOT EXISTS(
           SELECT 1 FROM evidence newer
            WHERE newer.supersedes_evidence_id=e.id AND newer.storage_state='READY')
    ) THEN
      RAISE EXCEPTION 'Technical completion requires current READY visit Evidence'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_maintenance_review_source
BEFORE INSERT ON service_maintenance_review_event
FOR EACH ROW EXECUTE FUNCTION service_maintenance_review_source_gate();
CREATE TRIGGER trg_maintenance_review_immutable
BEFORE UPDATE OR DELETE ON service_maintenance_review_event
FOR EACH ROW EXECUTE FUNCTION protect_service_maintenance_history();
CREATE TRIGGER trg_maintenance_review_no_truncate
BEFORE TRUNCATE ON service_maintenance_review_event
FOR EACH STATEMENT EXECUTE FUNCTION protect_service_maintenance_history();
