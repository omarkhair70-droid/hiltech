-- P11B4: immutable INTERNAL service response/hold observations.
-- Requires P11B/B2/B3. These are not contractual SLA clocks or permitted SLA pauses.
CREATE TABLE service_support_time_event (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    event_number integer NOT NULL CHECK(event_number>0),
    event_type varchar(40) NOT NULL CHECK(event_type IN (
        'INTERNAL_ATTENTION_RECORDED','INTERNAL_HOLD_STARTED','INTERNAL_HOLD_ENDED'
    )),
    reason text NOT NULL CHECK(length(btrim(reason)) BETWEEN 1 AND 4000),
    source_operation_id uuid NOT NULL UNIQUE,
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY'
        CHECK(visibility='INTERNAL_ONLY'),
    CONSTRAINT fk_support_time_ticket
      FOREIGN KEY(ticket_id,organization_id,project_id)
      REFERENCES service_support_ticket(id,organization_id,project_id) ON DELETE RESTRICT,
    CONSTRAINT uq_support_time_event_sequence UNIQUE(ticket_id,event_number)
);
CREATE INDEX idx_support_time_ticket_history
 ON service_support_time_event(ticket_id,event_number DESC);

CREATE FUNCTION service_support_time_source_gate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  ticket_opened_at timestamptz;
  latest_number integer;
  latest_at timestamptz;
  case_state text;
  case_event_at timestamptz;
  last_hold_type text;
BEGIN
  -- Same source-ticket row used to serialize case, triage and timing writes.
  SELECT created_at INTO ticket_opened_at FROM service_support_ticket
    WHERE id=NEW.ticket_id AND organization_id=NEW.organization_id
      AND project_id=NEW.project_id FOR UPDATE;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'Internal time entry requires original support ticket context'
      USING ERRCODE='23514';
  END IF;
  SELECT event_number,recorded_at INTO latest_number,latest_at
    FROM service_support_time_event WHERE ticket_id=NEW.ticket_id
   ORDER BY event_number DESC LIMIT 1;
  IF NEW.event_number<>COALESCE(latest_number,0)+1
    OR NEW.recorded_at < COALESCE(latest_at,ticket_opened_at)
    OR NEW.recorded_at > now()+interval '5 minutes' THEN
    RAISE EXCEPTION 'Internal support timing must have monotonic source sequence and timestamp'
      USING ERRCODE='23514';
  END IF;
  SELECT resulting_state,recorded_at INTO case_state,case_event_at
    FROM service_support_case_event WHERE ticket_id=NEW.ticket_id
   ORDER BY event_number DESC LIMIT 1;
  IF COALESCE(case_state,'OPEN') NOT IN ('OPEN','INVESTIGATING')
    OR (case_event_at IS NOT NULL AND NEW.recorded_at < case_event_at) THEN
    RAISE EXCEPTION 'Internal timing requires current unresolved case and source time'
      USING ERRCODE='23514';
  END IF;
  SELECT event_type INTO last_hold_type FROM service_support_time_event
   WHERE ticket_id=NEW.ticket_id
     AND event_type IN ('INTERNAL_HOLD_STARTED','INTERNAL_HOLD_ENDED')
   ORDER BY event_number DESC LIMIT 1;
  IF NEW.event_type='INTERNAL_ATTENTION_RECORDED'
     AND EXISTS (SELECT 1 FROM service_support_time_event
                  WHERE ticket_id=NEW.ticket_id
                    AND event_type='INTERNAL_ATTENTION_RECORDED') THEN
    RAISE EXCEPTION 'First internal attention can only be recorded once'
      USING ERRCODE='23514';
  END IF;
  IF (NEW.event_type='INTERNAL_HOLD_STARTED'
        AND last_hold_type='INTERNAL_HOLD_STARTED')
     OR (NEW.event_type='INTERNAL_HOLD_ENDED'
        AND last_hold_type IS DISTINCT FROM 'INTERNAL_HOLD_STARTED') THEN
    RAISE EXCEPTION 'Internal hold events must be paired'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_support_time_source
BEFORE INSERT ON service_support_time_event
FOR EACH ROW EXECUTE FUNCTION service_support_time_source_gate();

-- Do not let a real case be marked resolved/closed while its internal hold
-- is unclosed, including a direct SQL write bypassing application checks.
CREATE FUNCTION service_support_time_case_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
  last_hold_type text;
BEGIN
  IF NEW.event_type NOT IN ('RESOLUTION_RECORDED','INTERNAL_CLOSE_RECORDED') THEN
    RETURN NEW;
  END IF;
  SELECT event_type INTO last_hold_type FROM service_support_time_event
   WHERE ticket_id=NEW.ticket_id
     AND event_type IN ('INTERNAL_HOLD_STARTED','INTERNAL_HOLD_ENDED')
   ORDER BY event_number DESC LIMIT 1;
  IF last_hold_type='INTERNAL_HOLD_STARTED' THEN
    RAISE EXCEPTION 'Internal hold must end before a support case is resolved or closed'
      USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_support_time_case_source
BEFORE INSERT ON service_support_case_event
FOR EACH ROW EXECUTE FUNCTION service_support_time_case_guard();
CREATE TRIGGER trg_support_time_immutable
BEFORE UPDATE OR DELETE ON service_support_time_event
FOR EACH ROW EXECUTE FUNCTION service_support_immutable();
CREATE TRIGGER trg_support_time_no_truncate
BEFORE TRUNCATE ON service_support_time_event
FOR EACH STATEMENT EXECUTE FUNCTION service_support_immutable();
