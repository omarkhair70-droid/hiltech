-- Phase 6 Slice 04C — configurable structured engineering tests.
-- No OTDR/Fluke fields or thresholds are hard-coded. An EvidencePolicy requirement
-- may bind one immutable template revision of type ENGINEERING_TEST_SCHEMA.

ALTER TABLE evidence_policy_requirement
    ADD COLUMN structured_test_template_id uuid NULL
        REFERENCES config_revision(id) ON DELETE RESTRICT;

CREATE FUNCTION enforce_engineering_test_template_ref()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    template_family varchar(64);
    template_kind varchar(64);
BEGIN
    IF NEW.structured_test_template_id IS NULL THEN
        RETURN NEW;
    END IF;

    SELECT cr.family, td.template_type
      INTO template_family, template_kind
      FROM config_revision cr
      JOIN template_definition td ON td.config_revision_id = cr.id
     WHERE cr.id = NEW.structured_test_template_id;

    IF NOT FOUND
       OR template_family <> 'templates'
       OR template_kind <> 'ENGINEERING_TEST_SCHEMA' THEN
        RAISE EXCEPTION 'Structured engineering test requirement must reference an ENGINEERING_TEST_SCHEMA template revision'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_evidence_requirement_engineering_test_template
BEFORE INSERT OR UPDATE OF structured_test_template_id
ON evidence_policy_requirement
FOR EACH ROW EXECUTE FUNCTION enforce_engineering_test_template_ref();

CREATE TABLE field_engineering_test_result (
    operation_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    work_order_id uuid NOT NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL REFERENCES work_assignment(id) ON DELETE RESTRICT,
    assignment_version bigint NOT NULL CHECK (assignment_version >= 1),
    captured_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    device_installation_id uuid NOT NULL,
    work_order_version bigint NOT NULL CHECK (work_order_version >= 1),
    evidence_policy_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    evidence_policy_revision integer NOT NULL CHECK (evidence_policy_revision >= 1),
    requirement_key varchar(64) NOT NULL,
    schema_template_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    schema_template_revision integer NOT NULL CHECK (schema_template_revision >= 1),
    schema_version integer NOT NULL CHECK (schema_version >= 1),
    test_subject_ref varchar(160) NOT NULL CHECK (length(btrim(test_subject_ref)) > 0),
    values_json jsonb NOT NULL CHECK (jsonb_typeof(values_json) = 'object'),
    instrument_asset_id uuid NULL REFERENCES asset(id) ON DELETE RESTRICT,
    raw_evidence_id uuid NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    device_reported_status varchar(16) NULL
        CHECK (device_reported_status IN ('PASS','FAIL','UNKNOWN')),
    evaluated_status varchar(16) NOT NULL
        CHECK (evaluated_status IN ('PASS','FAIL','UNKNOWN')),
    evaluation_json jsonb NOT NULL CHECK (jsonb_typeof(evaluation_json) = 'object'),
    captured_at timestamptz NOT NULL,
    client_occurred_at timestamptz NULL,
    supersedes_result_id uuid NULL
        REFERENCES field_engineering_test_result(operation_id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (supersedes_result_id IS NULL OR supersedes_result_id <> operation_id)
);

CREATE UNIQUE INDEX uq_field_engineering_test_single_supersession
    ON field_engineering_test_result (supersedes_result_id)
    WHERE supersedes_result_id IS NOT NULL;

CREATE INDEX idx_field_engineering_test_work_requirement
    ON field_engineering_test_result
       (work_order_id, requirement_key, test_subject_ref, created_at DESC);

CREATE FUNCTION guard_field_engineering_test_result_immutable()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'Field engineering test results are immutable; create a superseding result'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER field_engineering_test_result_immutable
BEFORE UPDATE OR DELETE ON field_engineering_test_result
FOR EACH ROW EXECUTE FUNCTION guard_field_engineering_test_result_immutable();
