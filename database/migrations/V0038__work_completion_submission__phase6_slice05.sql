-- Phase 6 Slice 05 — immutable completion submissions.
-- Review decisions already bind to work_order.submitted_review_version; this table
-- preserves the exact field source and dependency gate that produced each submission.

CREATE TABLE work_completion_submission (
    operation_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL,
    submitted_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL,
    assignment_version bigint NOT NULL CHECK (assignment_version >= 1),
    base_work_order_version bigint NOT NULL CHECK (base_work_order_version >= 1),
    submitted_work_order_version bigint NOT NULL CHECK (submitted_work_order_version >= 2),
    policy_binding_id uuid NOT NULL REFERENCES work_policy_binding(id) ON DELETE RESTRICT,
    policy_binding_revision integer NOT NULL CHECK (policy_binding_revision >= 1),
    evidence_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    evidence_policy_revision integer NOT NULL CHECK (evidence_policy_revision >= 1),
    review_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    review_policy_revision integer NOT NULL CHECK (review_policy_revision >= 1),
    source_lifecycle_state varchar(32) NOT NULL
        CHECK (source_lifecycle_state IN ('IN_PROGRESS','REWORK_REQUIRED')),
    dependency_snapshot_json jsonb NOT NULL
        CHECK (jsonb_typeof(dependency_snapshot_json) = 'object'),
    client_occurred_at timestamptz NOT NULL,
    submitted_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL
        CHECK (length(btrim(correlation_id)) > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    CONSTRAINT fk_work_completion_submission_work
        FOREIGN KEY (work_order_id, organization_id)
        REFERENCES work_order(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_work_completion_submission_assignment
        FOREIGN KEY (assignment_id, work_order_id)
        REFERENCES work_assignment(id, work_order_id) ON DELETE RESTRICT,
    CONSTRAINT uq_work_completion_submission_version
        UNIQUE (work_order_id, submitted_work_order_version)
);

CREATE INDEX idx_work_completion_submission_work
    ON work_completion_submission (work_order_id, submitted_at DESC);

CREATE FUNCTION validate_work_completion_submission()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    binding_row work_policy_binding%ROWTYPE;
BEGIN
    SELECT * INTO binding_row
      FROM work_policy_binding
     WHERE id = NEW.policy_binding_id;

    IF NOT FOUND
       OR binding_row.work_order_id <> NEW.work_order_id
       OR binding_row.binding_revision <> NEW.policy_binding_revision
       OR binding_row.evidence_policy_id <> NEW.evidence_policy_id
       OR binding_row.evidence_policy_revision <> NEW.evidence_policy_revision
       OR binding_row.review_policy_id <> NEW.review_policy_id
       OR binding_row.review_policy_revision <> NEW.review_policy_revision THEN
        RAISE EXCEPTION 'Completion submission policy binding snapshot is invalid'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.submitted_work_order_version <> NEW.base_work_order_version + 1 THEN
        RAISE EXCEPTION 'Completion submission version must be the next WorkOrder version'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_work_completion_submission
BEFORE INSERT ON work_completion_submission
FOR EACH ROW EXECUTE FUNCTION validate_work_completion_submission();

CREATE FUNCTION guard_work_completion_submission_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Completion submissions are immutable'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER work_completion_submission_immutable
BEFORE UPDATE OR DELETE ON work_completion_submission
FOR EACH ROW EXECUTE FUNCTION guard_work_completion_submission_immutable();
