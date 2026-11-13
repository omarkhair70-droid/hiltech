-- Phase 5 Slice 03. Extend the existing Asset, tag, movement and custody tables.
ALTER TABLE asset_type_definition
    ADD COLUMN serial_required boolean NOT NULL DEFAULT false,
    ADD COLUMN allowed_tag_types varchar(64)[] NOT NULL DEFAULT '{}',
    ADD COLUMN operator_may_tag boolean NOT NULL DEFAULT false,
    ADD COLUMN maintenance_policy_id uuid REFERENCES config_revision(id),
    ADD COLUMN accessory_template_id uuid REFERENCES config_revision(id),
    ADD COLUMN inspection_required_on_return boolean NOT NULL DEFAULT false;

CREATE TABLE asset_import_verification (
    source_row_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    source_row_version bigint NOT NULL CHECK (source_row_version > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    warehouse_id uuid NOT NULL,
    asset_type_id uuid NOT NULL REFERENCES asset_type_definition(config_revision_id),
    manufacturer varchar(160),
    model varchar(160),
    verified_serial varchar(160),
    opening_location_id uuid,
    opening_employee_id uuid,
    cutover_at timestamptz,
    verified_by uuid NOT NULL REFERENCES user_identity(id),
    approver_id uuid NOT NULL REFERENCES user_identity(id),
    verified_at timestamptz NOT NULL,
    evidence_ref varchar(255) NOT NULL CHECK (length(btrim(evidence_ref)) > 0),
    resolved_issue_codes varchar(64)[] NOT NULL DEFAULT '{}',
    CHECK (verified_by <> approver_id),
    CHECK (num_nonnulls(opening_location_id, opening_employee_id) <= 1),
    CHECK ((num_nonnulls(opening_location_id, opening_employee_id) = 1) = (cutover_at IS NOT NULL)),
    FOREIGN KEY (source_row_id, organization_id) REFERENCES inventory_import_row(id, organization_id),
    FOREIGN KEY (warehouse_id, organization_id) REFERENCES warehouse(id, organization_id),
    FOREIGN KEY (opening_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    FOREIGN KEY (opening_employee_id, organization_id) REFERENCES employee(id, organization_id)
);

ALTER TABLE asset
    ADD COLUMN warehouse_id uuid,
    ADD COLUMN display_name varchar(160),
    ADD COLUMN source_import_row_id uuid UNIQUE,
    ADD UNIQUE (id, organization_id),
    ADD FOREIGN KEY (warehouse_id, organization_id) REFERENCES warehouse(id, organization_id),
    ADD FOREIGN KEY (source_import_row_id, organization_id) REFERENCES inventory_import_row(id, organization_id);
CREATE INDEX idx_asset_normalized_serial ON asset (organization_id, lower(btrim(serial_number)))
    WHERE serial_number IS NOT NULL;

ALTER TABLE asset_tag
    ADD COLUMN ended_at timestamptz,
    ADD UNIQUE (asset_id, id),
    ADD UNIQUE (replaced_tag_id),
    ADD FOREIGN KEY (asset_id, replaced_tag_id) REFERENCES asset_tag(asset_id, id),
    ADD CHECK (ended_at IS NULL OR (NOT active AND NOT is_primary AND ended_at >= issued_at));

ALTER TABLE asset_movement
    ADD COLUMN organization_id uuid,
    ADD COLUMN ledger_version bigint,
    ADD COLUMN source_import_row_id uuid UNIQUE REFERENCES asset_import_verification(source_row_id),
    ADD UNIQUE (asset_id, id),
    ADD UNIQUE (asset_id, ledger_version),
    ADD FOREIGN KEY (asset_id, correction_of_movement_id) REFERENCES asset_movement(asset_id, id);
UPDATE asset_movement m SET organization_id = a.organization_id FROM asset a WHERE a.id = m.asset_id;
ALTER TABLE asset_movement
    ALTER COLUMN organization_id SET NOT NULL,
    ADD FOREIGN KEY (asset_id, organization_id) REFERENCES asset(id, organization_id),
    ADD FOREIGN KEY (from_storage_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    ADD FOREIGN KEY (to_storage_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    ADD FOREIGN KEY (project_id, organization_id) REFERENCES project(id, organization_id),
    ADD FOREIGN KEY (site_id, organization_id) REFERENCES site(id, organization_id),
    ADD FOREIGN KEY (work_order_id, organization_id) REFERENCES work_order(id, organization_id);
CREATE UNIQUE INDEX uq_asset_initial_custody ON asset_movement (asset_id)
    WHERE movement_type IN ('REGISTER_INITIAL_LOCATION', 'INITIALIZE_OPENING_CUSTODY');

ALTER TABLE asset_custody_projection
    ADD FOREIGN KEY (asset_id, source_movement_id) REFERENCES asset_movement(asset_id, id);

CREATE FUNCTION reject_asset_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Asset movement history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER asset_movement_append_only BEFORE UPDATE OR DELETE ON asset_movement
FOR EACH ROW EXECUTE FUNCTION reject_asset_movement_mutation();
CREATE TRIGGER asset_movement_no_truncate BEFORE TRUNCATE ON asset_movement
FOR EACH STATEMENT EXECUTE FUNCTION reject_asset_movement_mutation();

CREATE FUNCTION guard_asset_custody_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() = 1 THEN
        RAISE EXCEPTION 'Asset custody is a movement projection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_custody_projection_only BEFORE INSERT OR UPDATE OR DELETE ON asset_custody_projection
FOR EACH ROW EXECUTE FUNCTION guard_asset_custody_projection();
CREATE TRIGGER asset_custody_no_truncate BEFORE TRUNCATE ON asset_custody_projection
FOR EACH STATEMENT EXECUTE FUNCTION guard_asset_custody_projection();

CREATE FUNCTION validate_asset_custody_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    passport asset%ROWTYPE;
    prior asset_custody_projection%ROWTYPE;
    destination storage_location%ROWTYPE;
BEGIN
    SELECT * INTO STRICT passport FROM asset WHERE id = NEW.asset_id FOR NO KEY UPDATE;
    SELECT * INTO prior FROM asset_custody_projection WHERE asset_id = NEW.asset_id;
    IF passport.lifecycle_state <> 'ACTIVE' OR passport.organization_id <> NEW.organization_id
        OR NEW.movement_type NOT IN ('REGISTER_INITIAL_LOCATION', 'INITIALIZE_OPENING_CUSTODY', 'CONTROLLED_CORRECTION')
        OR NEW.occurred_at > NEW.recorded_at
        OR num_nonnulls(NEW.to_storage_location_id, NEW.to_custodian_target_id) <> 1
        OR ((NEW.to_custodian_target_id IS NULL) <> (NEW.to_custodian_target_type IS NULL))
        OR NEW.condition_at_transfer IS NULL OR NEW.expected_return_at IS NOT NULL THEN
        RAISE EXCEPTION 'Invalid Asset custody movement' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type = 'CONTROLLED_CORRECTION' THEN
        IF prior.asset_id IS NULL OR NEW.correction_of_movement_id IS DISTINCT FROM prior.source_movement_id
            OR NEW.from_storage_location_id IS DISTINCT FROM prior.current_storage_location_id
            OR NEW.from_custodian_target_type IS DISTINCT FROM prior.current_custodian_target_type
            OR NEW.from_custodian_target_id IS DISTINCT FROM prior.current_custodian_target_id
            OR nullif(btrim(NEW.notes), '') IS NULL OR NEW.source_import_row_id IS NOT NULL THEN
            RAISE EXCEPTION 'Correction must reference the current movement and record a reason' USING ERRCODE = '23514';
        END IF;
    ELSIF prior.asset_id IS NOT NULL OR NEW.correction_of_movement_id IS NOT NULL
        OR num_nonnulls(NEW.from_storage_location_id, NEW.from_custodian_target_id, NEW.from_custodian_target_type) <> 0 THEN
        RAISE EXCEPTION 'Initial custody has already been established' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type = 'REGISTER_INITIAL_LOCATION' AND NEW.to_storage_location_id IS NULL THEN
        RAISE EXCEPTION 'Registration requires a storage location' USING ERRCODE = '23514';
    END IF;
    IF NEW.to_storage_location_id IS NOT NULL THEN
        SELECT * INTO destination FROM storage_location WHERE id = NEW.to_storage_location_id FOR SHARE;
        IF destination.id IS NULL OR destination.organization_id <> passport.organization_id
            OR destination.lifecycle_state <> 'ACTIVE'
            OR destination.active_from > NEW.recorded_at OR destination.active_until <= NEW.recorded_at
            OR NEW.project_id IS DISTINCT FROM destination.project_id OR NEW.site_id IS DISTINCT FROM destination.site_id
            OR NEW.work_order_id IS NOT NULL THEN
            RAISE EXCEPTION 'Asset destination is unavailable' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.to_custodian_target_type <> 'EMPLOYEE' OR NOT EXISTS (
        SELECT 1 FROM employee WHERE id = NEW.to_custodian_target_id
            AND organization_id = passport.organization_id AND state = 'ACTIVE'
    ) OR (NEW.work_order_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM work_order WHERE id = NEW.work_order_id AND organization_id = passport.organization_id
            AND project_id = NEW.project_id AND site_id = NEW.site_id AND lifecycle_state NOT IN ('CLOSED', 'CANCELLED')
    )) OR (NEW.work_order_id IS NULL AND num_nonnulls(NEW.project_id, NEW.site_id) <> 0) THEN
        RAISE EXCEPTION 'Employee custody context is unavailable' USING ERRCODE = '23514';
    END IF;
    IF NEW.movement_type <> 'CONTROLLED_CORRECTION' AND passport.source_import_row_id IS NOT NULL THEN
        IF NOT EXISTS (
            SELECT 1 FROM asset_import_verification v
            JOIN inventory_import_row r ON r.id = v.source_row_id
            JOIN inventory_import_batch b ON b.id = r.batch_id
            WHERE v.source_row_id = passport.source_import_row_id AND v.source_row_id = NEW.source_import_row_id
              AND v.approver_id = NEW.recorded_by AND v.cutover_at = NEW.occurred_at
              AND v.opening_location_id IS NOT DISTINCT FROM NEW.to_storage_location_id
              AND v.opening_employee_id IS NOT DISTINCT FROM NEW.to_custodian_target_id
              AND r.review_state = 'APPROVED' AND r.approved_target_type = 'ASSET' AND r.approved_target_id = passport.id
              AND r.version = v.source_row_version + 1 AND b.source_sha256 = v.source_sha256 AND b.state <> 'CANCELLED'
              AND NEW.work_order_id IS NULL AND NEW.movement_type = 'INITIALIZE_OPENING_CUSTODY'
        ) THEN
            RAISE EXCEPTION 'Opening custody needs verified source evidence' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.source_import_row_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected source provenance' USING ERRCODE = '23514';
    END IF;
    NEW.ledger_version := passport.version + 1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_movement_validate BEFORE INSERT ON asset_movement
FOR EACH ROW EXECUTE FUNCTION validate_asset_custody_movement();

CREATE FUNCTION project_asset_custody_movement() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE asset SET version = NEW.ledger_version, condition_state = NEW.condition_at_transfer WHERE id = NEW.asset_id;
    INSERT INTO asset_custody_projection (asset_id, current_storage_location_id, current_custodian_target_type,
        current_custodian_target_id, current_project_id, current_site_id, current_work_order_id,
        custody_started_at, source_movement_id, asset_version, updated_at)
    VALUES (NEW.asset_id, NEW.to_storage_location_id, NEW.to_custodian_target_type, NEW.to_custodian_target_id,
        NEW.project_id, NEW.site_id, NEW.work_order_id, NEW.occurred_at, NEW.id, NEW.ledger_version, NEW.recorded_at)
    ON CONFLICT (asset_id) DO UPDATE SET
        current_storage_location_id = EXCLUDED.current_storage_location_id,
        current_custodian_target_type = EXCLUDED.current_custodian_target_type,
        current_custodian_target_id = EXCLUDED.current_custodian_target_id,
        current_project_id = EXCLUDED.current_project_id, current_site_id = EXCLUDED.current_site_id,
        current_work_order_id = EXCLUDED.current_work_order_id, custody_started_at = EXCLUDED.custody_started_at,
        expected_return_at = NULL, source_movement_id = EXCLUDED.source_movement_id,
        asset_version = EXCLUDED.asset_version, updated_at = EXCLUDED.updated_at;
    RETURN NEW;
END;
$$;
CREATE TRIGGER asset_movement_project AFTER INSERT ON asset_movement
FOR EACH ROW EXECUTE FUNCTION project_asset_custody_movement();

CREATE VIEW asset_ledger_custody AS
SELECT DISTINCT ON (asset_id) asset_id, id AS source_movement_id, to_storage_location_id,
    to_custodian_target_type, to_custodian_target_id, project_id, site_id, work_order_id, occurred_at, ledger_version
FROM asset_movement
ORDER BY asset_id, ledger_version DESC NULLS LAST, recorded_at DESC, id DESC;
