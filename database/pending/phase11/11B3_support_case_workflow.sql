-- Phase 11B3: append-only INTERNAL support case history.
-- Requires 11B_support_internal.sql and 11B2_support_triage.sql.
-- Service support_ticket is the immutable opened report; this is a separate
-- versioned CURRENT CASE projection, never a rewrite or customer acceptance.
CREATE TABLE service_support_case_event (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    event_number integer NOT NULL CHECK(event_number > 0),
    event_type varchar(40) NOT NULL CHECK(event_type IN (
      'INVESTIGATION_STARTED','WORK_ORDER_LINKED','RESOLUTION_RECORDED',
      'INTERNAL_CLOSE_RECORDED','REOPENED'
    )),
    resulting_state varchar(32) NOT NULL CHECK(resulting_state IN (
      'OPEN','INVESTIGATING','RESOLVED_INTERNAL','CLOSED_INTERNAL'
    )),
    work_order_id uuid NULL,
    evidence_id uuid NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 4000),
    source_operation_id uuid NOT NULL UNIQUE,
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY' CHECK(visibility='INTERNAL_ONLY'),
    CONSTRAINT fk_support_case_ticket FOREIGN KEY (ticket_id,organization_id,project_id)
      REFERENCES service_support_ticket(id,organization_id,project_id) ON DELETE RESTRICT,
    CONSTRAINT fk_support_case_work FOREIGN KEY(work_order_id,organization_id,project_id)
      REFERENCES work_order(id,organization_id,project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_support_case_sequence UNIQUE(ticket_id,event_number),
    CHECK(
      (event_type='WORK_ORDER_LINKED' AND work_order_id IS NOT NULL AND evidence_id IS NULL)
      OR
      (event_type='RESOLUTION_RECORDED' AND work_order_id IS NOT NULL AND evidence_id IS NOT NULL)
      OR
      (event_type NOT IN ('WORK_ORDER_LINKED','RESOLUTION_RECORDED')
        AND work_order_id IS NULL AND evidence_id IS NULL)
    )
);
CREATE INDEX idx_support_case_ticket_history
 ON service_support_case_event(ticket_id,event_number DESC);

-- DB checks authority-independent invariants too: no API may claim resolved
-- work without accepted source WorkOrder and its actual READY Evidence.
CREATE FUNCTION service_support_case_source_gate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
 previous_state text;
 previous_number integer;
 current_link uuid;
 source_state text;
BEGIN
  -- Explicit row lock: two independent SQL writers cannot race the same ticket.
  PERFORM 1 FROM service_support_ticket
    WHERE id=NEW.ticket_id AND organization_id=NEW.organization_id
      AND project_id=NEW.project_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Support case must belong to its source ticket'
      USING ERRCODE='23514';
  END IF;
  SELECT event_number,resulting_state INTO previous_number,previous_state
    FROM service_support_case_event
    WHERE ticket_id=NEW.ticket_id ORDER BY event_number DESC LIMIT 1;
  previous_number:=COALESCE(previous_number,0);
  previous_state:=COALESCE(previous_state,'OPEN');
  IF NEW.event_number <> previous_number+1 THEN
    RAISE EXCEPTION 'Support case event sequence stale' USING ERRCODE='23514';
  END IF;
  IF NOT (
    (NEW.event_type='INVESTIGATION_STARTED' AND previous_state='OPEN'
      AND NEW.resulting_state='INVESTIGATING')
    OR (NEW.event_type='WORK_ORDER_LINKED'
      AND previous_state IN ('OPEN','INVESTIGATING')
      AND NEW.resulting_state=previous_state)
    OR (NEW.event_type='RESOLUTION_RECORDED'
      AND previous_state='INVESTIGATING' AND NEW.resulting_state='RESOLVED_INTERNAL')
    OR (NEW.event_type='INTERNAL_CLOSE_RECORDED'
      AND previous_state='RESOLVED_INTERNAL' AND NEW.resulting_state='CLOSED_INTERNAL')
    OR (NEW.event_type='REOPENED'
      AND previous_state IN ('RESOLVED_INTERNAL','CLOSED_INTERNAL')
      AND NEW.resulting_state='INVESTIGATING')
  ) THEN
    RAISE EXCEPTION 'Support case transition is not authorized by internal state graph'
      USING ERRCODE='23514';
  END IF;
  IF NEW.event_type='WORK_ORDER_LINKED' AND NOT EXISTS (
    SELECT 1 FROM work_order wo
     WHERE wo.id=NEW.work_order_id
       AND wo.organization_id=NEW.organization_id
       AND wo.project_id=NEW.project_id
       AND wo.lifecycle_state <> 'CANCELLED'
  ) THEN
    RAISE EXCEPTION 'Cannot attach cancelled or foreign source work'
      USING ERRCODE='23514';
  END IF;
  IF NEW.event_type='RESOLUTION_RECORDED' THEN
    SELECT work_order_id INTO current_link FROM service_support_case_event
      WHERE ticket_id=NEW.ticket_id AND event_type='WORK_ORDER_LINKED'
      ORDER BY event_number DESC LIMIT 1;
    IF current_link IS DISTINCT FROM NEW.work_order_id THEN
      RAISE EXCEPTION 'Resolve only against linked existing WorkOrder'
        USING ERRCODE='23514';
    END IF;
    SELECT lifecycle_state INTO source_state FROM work_order
      WHERE id=NEW.work_order_id AND organization_id=NEW.organization_id
      AND project_id=NEW.project_id;
    IF source_state IS DISTINCT FROM 'ACCEPTED' AND source_state IS DISTINCT FROM 'CLOSED' THEN
      RAISE EXCEPTION 'WorkOrder must already be accepted'
        USING ERRCODE='23514';
    END IF;
    IF NOT EXISTS (
      SELECT 1 FROM evidence e
      JOIN service_support_ticket ticket ON ticket.id=NEW.ticket_id
      WHERE e.id=NEW.evidence_id
        AND e.organization_id=NEW.organization_id
        AND e.work_order_id=NEW.work_order_id AND e.target_type='WORK_ORDER'
        AND e.target_id=NEW.work_order_id AND e.storage_state='READY'
        -- An old installation photo is not proof of fixing a later complaint.
        AND e.captured_at >= ticket.created_at
        AND NOT EXISTS(
          SELECT 1 FROM evidence replacement
           WHERE replacement.supersedes_evidence_id=e.id
             AND replacement.storage_state='READY'
        )
    ) THEN
      RAISE EXCEPTION 'Support resolution needs new, non-superseded READY Evidence captured after ticket opening'
        USING ERRCODE='23514';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_support_case_source_gate
BEFORE INSERT ON service_support_case_event FOR EACH ROW
EXECUTE FUNCTION service_support_case_source_gate();
CREATE TRIGGER trg_support_case_immutable
BEFORE UPDATE OR DELETE ON service_support_case_event FOR EACH ROW
EXECUTE FUNCTION service_support_immutable();
CREATE TRIGGER trg_support_case_no_truncate
BEFORE TRUNCATE ON service_support_case_event FOR EACH STATEMENT
EXECUTE FUNCTION service_support_immutable();
