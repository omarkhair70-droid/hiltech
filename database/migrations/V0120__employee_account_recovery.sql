-- Account recovery records command intent and outcome, never credential material.
CREATE TABLE employee_credential_reissue (
    operation_id uuid PRIMARY KEY,
    sequence_number bigint GENERATED ALWAYS AS IDENTITY NOT NULL,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL,
    identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    membership_id uuid NOT NULL REFERENCES organization_membership(id) ON DELETE RESTRICT,
    actor_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    employee_version bigint NOT NULL CHECK (employee_version >= 1),
    identity_version bigint NOT NULL CHECK (identity_version >= 1),
    membership_version bigint NOT NULL CHECK (membership_version >= 1),
    previous_operation_id uuid REFERENCES employee_credential_reissue(operation_id) ON DELETE RESTRICT,
    previous_operation_version bigint CHECK (previous_operation_version >= 1),
    reason varchar(500) NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 500),
    request_fingerprint varchar(128) NOT NULL,
    state varchar(24) NOT NULL CHECK (state IN ('DISPATCHING','ISSUED','REJECTED','UNCONFIRMED')),
    safe_failure_code varchar(100),
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    FOREIGN KEY (employee_id, organization_id) REFERENCES employee(id, organization_id) ON DELETE RESTRICT,
    CHECK ((previous_operation_id IS NULL) = (previous_operation_version IS NULL)),
    CHECK (previous_operation_id IS NULL OR previous_operation_id <> operation_id),
    CHECK (completed_at IS NULL OR completed_at >= created_at),
    CHECK ((state = 'DISPATCHING') = (completed_at IS NULL))
);

CREATE INDEX idx_employee_credential_reissue_current
    ON employee_credential_reissue (identity_id, sequence_number DESC);

CREATE FUNCTION protect_employee_credential_reissue_intent() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Credential reissue history cannot be deleted';
    END IF;
    IF ROW(NEW.operation_id,NEW.sequence_number,NEW.organization_id,NEW.employee_id,NEW.identity_id,NEW.membership_id,
        NEW.actor_identity_id,NEW.employee_version,NEW.identity_version,NEW.membership_version,NEW.previous_operation_id,
        NEW.previous_operation_version,NEW.reason,NEW.request_fingerprint,NEW.created_at)
       IS DISTINCT FROM ROW(OLD.operation_id,OLD.sequence_number,OLD.organization_id,OLD.employee_id,OLD.identity_id,OLD.membership_id,
        OLD.actor_identity_id,OLD.employee_version,OLD.identity_version,OLD.membership_version,OLD.previous_operation_id,
        OLD.previous_operation_version,OLD.reason,OLD.request_fingerprint,OLD.created_at) THEN
        RAISE EXCEPTION 'Credential reissue intent is immutable';
    END IF;
    IF OLD.state <> 'DISPATCHING' OR NEW.state = 'DISPATCHING' OR NEW.version <> OLD.version + 1 THEN
        RAISE EXCEPTION 'Credential reissue outcome is final';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER employee_credential_reissue_immutable_intent BEFORE UPDATE OR DELETE ON employee_credential_reissue
    FOR EACH ROW EXECUTE FUNCTION protect_employee_credential_reissue_intent();
