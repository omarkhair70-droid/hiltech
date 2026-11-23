-- P11: append-only link from an existing service maintenance finding
-- to an existing canonical project support ticket. No auto-creation of tickets,
-- WorkOrders, procurement, Finance operations or external client messages.
-- Run AFTER 11C_maintenance_visits.sql (and 11B3 case history).

ALTER TABLE service_maintenance_finding
  ADD CONSTRAINT uq_service_finding_project_context
  UNIQUE (id, visit_id, organization_id, project_id);

CREATE TABLE service_maintenance_ticket_followup (
  id uuid PRIMARY KEY,
  finding_id uuid NOT NULL,
  visit_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 4000),
  source_operation_id uuid NOT NULL UNIQUE,
  recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
  recorded_at timestamptz NOT NULL,
  visibility varchar(24) NOT NULL DEFAULT 'INTERNAL_ONLY'
    CHECK (visibility = 'INTERNAL_ONLY'),
  CONSTRAINT fk_maintenance_ticket_finding
    FOREIGN KEY (finding_id, visit_id, organization_id, project_id)
    REFERENCES service_maintenance_finding(id, visit_id, organization_id, project_id)
    ON DELETE RESTRICT,
  CONSTRAINT fk_maintenance_ticket_source
    FOREIGN KEY (ticket_id, organization_id, project_id)
    REFERENCES service_support_ticket(id, organization_id, project_id)
    ON DELETE RESTRICT,
  UNIQUE(finding_id)
);
CREATE INDEX idx_maintenance_ticket_followup_project
 ON service_maintenance_ticket_followup(project_id, ticket_id);

-- The DB does not link historical defects to already internally closed cases.
-- A new case can be opened through the canonical SupportDesk instead.
CREATE FUNCTION service_maintenance_ticket_link_gate() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
 current_state text;
BEGIN
 -- Serialize with B3's case-state inserts via the same canonical ticket lock.
 PERFORM 1 FROM service_support_ticket
  WHERE id=NEW.ticket_id AND organization_id=NEW.organization_id
    AND project_id=NEW.project_id FOR UPDATE;
 IF NOT FOUND THEN
   RAISE EXCEPTION 'Follow-up must reference a ticket in its original Project'
     USING ERRCODE='23514';
 END IF;
 SELECT e.resulting_state INTO current_state
   FROM service_support_case_event e
  WHERE e.ticket_id = NEW.ticket_id
  ORDER BY e.event_number DESC LIMIT 1;
 IF current_state IN ('RESOLVED_INTERNAL', 'CLOSED_INTERNAL') THEN
   RAISE EXCEPTION 'Maintenance follow-up needs an unresolved internal support case'
     USING ERRCODE = '23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER trg_maintenance_ticket_followup_source
BEFORE INSERT ON service_maintenance_ticket_followup
FOR EACH ROW EXECUTE FUNCTION service_maintenance_ticket_link_gate();

CREATE TRIGGER trg_maintenance_ticket_followup_immutable
BEFORE UPDATE OR DELETE ON service_maintenance_ticket_followup
FOR EACH ROW EXECUTE FUNCTION protect_service_maintenance_history();
CREATE TRIGGER trg_maintenance_ticket_followup_no_truncate
BEFORE TRUNCATE ON service_maintenance_ticket_followup
FOR EACH STATEMENT EXECUTE FUNCTION protect_service_maintenance_history();
