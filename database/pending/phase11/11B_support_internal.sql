-- Phase 11B internal service desk source tables. Staging only.
-- Assign the final Flyway number in DevOps integration after all sibling migrations.
-- No contract entitlement, invoice eligibility or SLA clocks are inferred.

CREATE TABLE service_support_ticket (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    title varchar(240) NOT NULL CHECK(length(btrim(title)) BETWEEN 1 AND 240),
    description text NOT NULL CHECK(length(btrim(description)) BETWEEN 1 AND 8000),
    severity_code varchar(64) NOT NULL
        CHECK(severity_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    priority_code varchar(64) NOT NULL
        CHECK(priority_code ~ '^[A-Z][A-Z0-9_]{1,63}$'),
    lifecycle_state varchar(32) NOT NULL DEFAULT 'OPEN' CHECK (lifecycle_state = 'OPEN'),
    coverage_state varchar(32) NOT NULL DEFAULT 'NOT_EVALUATED'
        CHECK (coverage_state = 'NOT_EVALUATED'),
    sla_state varchar(32) NOT NULL DEFAULT 'NOT_CONFIGURED'
        CHECK (sla_state = 'NOT_CONFIGURED'),
    client_visibility varchar(32) NOT NULL DEFAULT 'INTERNAL_ONLY'
        CHECK (client_visibility = 'INTERNAL_ONLY'),
    source_operation_id uuid NOT NULL UNIQUE,
    opened_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK(version = 1),
    UNIQUE(id, organization_id, project_id)
);
CREATE INDEX idx_service_support_ticket_project
    ON service_support_ticket(project_id, created_at DESC);

CREATE TABLE service_support_internal_note (
    id uuid PRIMARY KEY,
    ticket_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    project_id uuid NOT NULL,
    body text NOT NULL CHECK(length(btrim(body)) BETWEEN 1 AND 8000),
    client_visibility varchar(32) NOT NULL DEFAULT 'INTERNAL_ONLY'
        CHECK (client_visibility = 'INTERNAL_ONLY'),
    source_operation_id uuid NOT NULL UNIQUE,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    FOREIGN KEY (ticket_id, organization_id, project_id)
        REFERENCES service_support_ticket(id, organization_id, project_id)
        ON DELETE RESTRICT
);
CREATE INDEX idx_service_support_note_ticket
    ON service_support_internal_note(ticket_id, created_at);

-- Postgres re-checks referenced Project organization and client on every
-- insert even if a buggy API supplies an arbitrary UUID.
CREATE FUNCTION service_ticket_source_context()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    source_project project%ROWTYPE;
BEGIN
    SELECT * INTO source_project FROM project WHERE id = NEW.project_id;
    IF NOT FOUND
       OR source_project.organization_id <> NEW.organization_id
       OR source_project.client_organization_id <> NEW.client_organization_id
    THEN
        RAISE EXCEPTION 'Support ticket must match original Project organization/client'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_service_ticket_source_context
BEFORE INSERT ON service_support_ticket
FOR EACH ROW EXECUTE FUNCTION service_ticket_source_context();

-- This initial desk is append-only: no ungoverned state/coverage edits.
CREATE FUNCTION service_support_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Support entry is immutable until governed workflow is provisioned'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_service_ticket_immutable
BEFORE UPDATE OR DELETE ON service_support_ticket
FOR EACH ROW EXECUTE FUNCTION service_support_immutable();
CREATE TRIGGER trg_service_note_immutable
BEFORE UPDATE OR DELETE ON service_support_internal_note
FOR EACH ROW EXECUTE FUNCTION service_support_immutable();
