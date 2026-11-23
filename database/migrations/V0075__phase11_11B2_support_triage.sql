-- P11B2: internal support triage ledger; run after 11B_support_internal.sql.
-- This is management classification, not SLA/contract entitlement or field assignment.
CREATE TABLE service_support_triage_event (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    event_number integer NOT NULL CHECK (event_number > 0),
    event_type varchar(32) NOT NULL CHECK (event_type IN ('OWNER_ASSIGNED', 'OWNER_RELEASED', 'CLASSIFICATION_RECORDED')),
    assignee_employee_id uuid NULL,
    proposed_severity_code varchar(64) NULL CHECK (
        proposed_severity_code IS NULL OR proposed_severity_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    proposed_priority_code varchar(64) NULL CHECK (
        proposed_priority_code IS NULL OR proposed_priority_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 4000),
    source_operation_id uuid NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    client_visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY' CHECK (client_visibility = 'INTERNAL_ONLY'),
    FOREIGN KEY(ticket_id, organization_id, project_id)
      REFERENCES service_support_ticket(id, organization_id, project_id) ON DELETE RESTRICT,
    FOREIGN KEY(assignee_employee_id, organization_id)
      REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    UNIQUE(ticket_id, event_number),
    CHECK (
      (event_type = 'OWNER_ASSIGNED' AND assignee_employee_id IS NOT NULL
       AND proposed_severity_code IS NULL AND proposed_priority_code IS NULL)
      OR
      (event_type = 'OWNER_RELEASED' AND assignee_employee_id IS NULL
       AND proposed_severity_code IS NULL AND proposed_priority_code IS NULL)
      OR
      (event_type = 'CLASSIFICATION_RECORDED' AND assignee_employee_id IS NULL
       AND proposed_severity_code IS NOT NULL AND proposed_priority_code IS NOT NULL)
    )
);
CREATE INDEX idx_support_triage_ticket_events
  ON service_support_triage_event(ticket_id, event_number DESC);

-- The owner belongs to active, existing HILTECH employee/employment truth.
-- Do not assign a ticket to a departed or preboarding employee.
CREATE FUNCTION guard_service_support_triage_owner() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.event_type = 'OWNER_ASSIGNED' AND NOT EXISTS (
    SELECT 1 FROM employee e JOIN employment em ON em.employee_id = e.id
    WHERE e.id = NEW.assignee_employee_id
      AND e.organization_id = NEW.organization_id AND e.state = 'ACTIVE'
      AND em.state = 'ACTIVE'
      AND em.start_date <= (NEW.created_at AT TIME ZONE 'UTC')::date
  ) THEN
    RAISE EXCEPTION 'Support triage owner must be an ACTIVE employee with active employment in the same organization'
      USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_service_support_triage_owner
BEFORE INSERT ON service_support_triage_event
FOR EACH ROW EXECUTE FUNCTION guard_service_support_triage_owner();

-- Event history is immutable, and previous ticket source classification remains intact.
CREATE TRIGGER trg_service_support_triage_immutable
BEFORE UPDATE OR DELETE ON service_support_triage_event
FOR EACH ROW EXECUTE FUNCTION service_support_immutable();
CREATE TRIGGER trg_service_support_triage_no_truncate
BEFORE TRUNCATE ON service_support_triage_event
FOR EACH STATEMENT EXECUTE FUNCTION service_support_immutable();
