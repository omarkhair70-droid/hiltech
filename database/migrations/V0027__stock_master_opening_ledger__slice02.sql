-- Phase 5 Slice 02. Movement inserts are the only writes to the balance projection.
CREATE TABLE stock_unit (
    organization_id uuid NOT NULL REFERENCES organization(id),
    code varchar(32) NOT NULL CHECK (length(btrim(code)) > 0),
    name varchar(160) NOT NULL CHECK (length(btrim(name)) > 0),
    active boolean NOT NULL,
    PRIMARY KEY (organization_id, code)
);

-- Keep any scaffold records addressable without approving their historical unit labels.
INSERT INTO stock_unit (organization_id, code, name, active)
SELECT DISTINCT organization_id, unit_of_measure_code, unit_of_measure_code, false FROM stock_item;

ALTER TABLE inventory_import_row ADD UNIQUE (id, organization_id);

-- The trusted deployment operator records independently reviewed evidence for this exact row version.
CREATE TABLE stock_import_verification (
    source_row_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    source_row_version bigint NOT NULL CHECK (source_row_version > 0),
    source_sha256 char(64) NOT NULL CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    storage_location_id uuid NOT NULL,
    category_definition_id uuid NOT NULL REFERENCES stock_item_category_definition(config_revision_id),
    verified_by uuid NOT NULL REFERENCES user_identity(id),
    cutover_approver_id uuid NOT NULL REFERENCES user_identity(id),
    evidence_ref varchar(255) NOT NULL CHECK (length(btrim(evidence_ref)) > 0),
    resolved_issue_codes varchar(64)[] NOT NULL DEFAULT '{}',
    cutover_at timestamptz NOT NULL,
    verified_at timestamptz NOT NULL,
    CHECK (verified_by <> cutover_approver_id),
    FOREIGN KEY (source_row_id, organization_id) REFERENCES inventory_import_row(id, organization_id),
    FOREIGN KEY (storage_location_id, organization_id) REFERENCES storage_location(id, organization_id)
);

ALTER TABLE stock_item
    ADD COLUMN description text,
    ADD COLUMN brand varchar(160),
    ADD COLUMN part_number varchar(160),
    ADD COLUMN classification varchar(24),
    ADD COLUMN source_import_row_id uuid UNIQUE,
    ADD UNIQUE (id, organization_id),
    ADD UNIQUE (id, unit_of_measure_code),
    ADD FOREIGN KEY (organization_id, unit_of_measure_code) REFERENCES stock_unit(organization_id, code),
    ADD FOREIGN KEY (source_import_row_id, organization_id) REFERENCES inventory_import_row(id, organization_id);

UPDATE stock_item SET classification = CASE WHEN serialized THEN 'STOCK_SERIALIZED' ELSE 'STOCK_QUANTITY' END;
ALTER TABLE stock_item
    ALTER COLUMN classification SET NOT NULL,
    ADD CHECK (classification IN ('STOCK_QUANTITY', 'STOCK_SERIALIZED') AND
               serialized = (classification = 'STOCK_SERIALIZED'));

ALTER TABLE stock_movement ADD COLUMN organization_id uuid;
ALTER TABLE stock_balance ADD COLUMN organization_id uuid;
UPDATE stock_movement m SET organization_id = i.organization_id FROM stock_item i WHERE i.id = m.stock_item_id;
UPDATE stock_balance b SET organization_id = i.organization_id FROM stock_item i WHERE i.id = b.stock_item_id;

ALTER TABLE stock_balance
    ALTER COLUMN organization_id SET NOT NULL,
    ADD FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id),
    ADD FOREIGN KEY (storage_location_id, organization_id) REFERENCES storage_location(id, organization_id);

ALTER TABLE stock_movement
    ALTER COLUMN organization_id SET NOT NULL,
    ADD COLUMN source_import_row_id uuid UNIQUE REFERENCES stock_import_verification(source_row_id),
    ADD FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id),
    ADD FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code),
    ADD FOREIGN KEY (from_storage_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    ADD FOREIGN KEY (to_storage_location_id, organization_id) REFERENCES storage_location(id, organization_id),
    ADD CHECK (movement_type <> 'INITIALIZE_OPENING' OR
        (from_storage_location_id IS NULL AND to_storage_location_id IS NOT NULL AND source_import_row_id IS NOT NULL)),
    ADD CHECK (movement_type <> 'TRANSFER' OR
        (from_storage_location_id IS NOT NULL AND to_storage_location_id IS NOT NULL
            AND from_storage_location_id <> to_storage_location_id AND source_import_row_id IS NULL));

CREATE UNIQUE INDEX uq_stock_opening_location ON stock_movement (stock_item_id, to_storage_location_id)
    WHERE movement_type = 'INITIALIZE_OPENING';

CREATE FUNCTION reject_stock_movement_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Stock movement history is append-only' USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER stock_movement_append_only BEFORE UPDATE OR DELETE ON stock_movement
FOR EACH ROW EXECUTE FUNCTION reject_stock_movement_mutation();
CREATE TRIGGER stock_movement_no_truncate BEFORE TRUNCATE ON stock_movement
FOR EACH STATEMENT EXECUTE FUNCTION reject_stock_movement_mutation();

CREATE FUNCTION guard_stock_balance_projection() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF pg_trigger_depth() = 1 THEN
        RAISE EXCEPTION 'Stock balance is a movement projection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER stock_balance_projection_only BEFORE INSERT OR UPDATE OR DELETE ON stock_balance
FOR EACH ROW EXECUTE FUNCTION guard_stock_balance_projection();
CREATE TRIGGER stock_balance_no_truncate BEFORE TRUNCATE ON stock_balance
FOR EACH STATEMENT EXECUTE FUNCTION guard_stock_balance_projection();

CREATE FUNCTION project_stock_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item stock_item%ROWTYPE;
    location storage_location%ROWTYPE;
BEGIN
    SELECT * INTO STRICT item FROM stock_item WHERE id = NEW.stock_item_id FOR NO KEY UPDATE;
    IF NOT item.active OR NOT EXISTS (
        SELECT 1 FROM stock_unit WHERE organization_id = item.organization_id
        AND code = item.unit_of_measure_code AND active
    ) THEN
        RAISE EXCEPTION 'Stock item or canonical unit is inactive' USING ERRCODE = '23514';
    END IF;

    FOR location IN
        SELECT * FROM storage_location WHERE id IN (NEW.from_storage_location_id, NEW.to_storage_location_id)
        ORDER BY id FOR SHARE
    LOOP
        IF location.lifecycle_state <> 'ACTIVE'
            OR (location.active_from IS NOT NULL AND location.active_from > NEW.recorded_at)
            OR (location.active_until IS NOT NULL AND location.active_until <= NEW.recorded_at) THEN
            RAISE EXCEPTION 'Stock movement location is inactive' USING ERRCODE = '23514';
        END IF;
    END LOOP;

    IF NEW.movement_type = 'INITIALIZE_OPENING' THEN
        IF NOT EXISTS (
            SELECT 1 FROM stock_import_verification v
            JOIN inventory_import_row r ON r.id = v.source_row_id
            JOIN inventory_import_batch b ON b.id = r.batch_id
            WHERE v.source_row_id = NEW.source_import_row_id
              AND v.storage_location_id = NEW.to_storage_location_id
              AND v.category_definition_id = item.category_definition_id
              AND v.cutover_approver_id = NEW.recorded_by
              AND r.id = item.source_import_row_id AND r.review_state = 'APPROVED'
              AND r.approved_target_type = 'STOCK_ITEM' AND r.approved_target_id = item.id
              AND r.version = v.source_row_version + 1
              AND r.candidate_quantity = NEW.quantity AND r.candidate_uom = NEW.unit_code
              AND b.source_sha256 = v.source_sha256
              AND b.state <> 'CANCELLED' AND v.cutover_at = NEW.occurred_at
        ) THEN
            RAISE EXCEPTION 'Opening stock needs verified import evidence' USING ERRCODE = '23514';
        END IF;
    ELSIF NEW.movement_type = 'TRANSFER' THEN
        UPDATE stock_balance
           SET on_hand_qty = on_hand_qty - NEW.quantity, updated_at = NEW.recorded_at, version = version + 1
         WHERE stock_item_id = NEW.stock_item_id AND storage_location_id = NEW.from_storage_location_id
           AND on_hand_qty - reserved_qty - damaged_qty - quarantine_qty >= NEW.quantity;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'Insufficient transferable stock' USING ERRCODE = '23514';
        END IF;
    ELSE
        RAISE EXCEPTION 'Unsupported stock movement type' USING ERRCODE = '23514';
    END IF;

    INSERT INTO stock_balance (stock_item_id, storage_location_id, organization_id,
        on_hand_qty, reserved_qty, damaged_qty, quarantine_qty, updated_at)
    VALUES (NEW.stock_item_id, NEW.to_storage_location_id, NEW.organization_id,
        NEW.quantity, 0, 0, 0, NEW.recorded_at)
    ON CONFLICT (stock_item_id, storage_location_id) DO UPDATE
    SET on_hand_qty = stock_balance.on_hand_qty + EXCLUDED.on_hand_qty,
        updated_at = EXCLUDED.updated_at, version = stock_balance.version + 1;
    RETURN NEW;
END;
$$;

CREATE TRIGGER stock_movement_projects_balance AFTER INSERT ON stock_movement
FOR EACH ROW EXECUTE FUNCTION project_stock_movement();

CREATE VIEW stock_ledger_on_hand AS
SELECT stock_item_id, storage_location_id, sum(delta) AS on_hand_qty
FROM (
    SELECT stock_item_id, to_storage_location_id AS storage_location_id, quantity AS delta
    FROM stock_movement WHERE to_storage_location_id IS NOT NULL
    UNION ALL
    SELECT stock_item_id, from_storage_location_id, -quantity
    FROM stock_movement WHERE from_storage_location_id IS NOT NULL
) entries
GROUP BY stock_item_id, storage_location_id;
