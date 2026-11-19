-- Phase 5 / Slice 07 — Receiving / discrepancy / serialized intake.
-- Receiving is physical source truth only; it does not implement Procurement, AP or invoice truth.

CREATE TABLE receiving_record (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    destination_storage_location_id uuid NOT NULL,
    source_type varchar(24) NOT NULL CHECK (source_type IN ('PO','TRANSFER','RETURN','INITIALIZATION','OTHER')),
    source_ref varchar(255),
    delivery_document_ref varchar(255),
    state varchar(32) NOT NULL DEFAULT 'DRAFT'
        CHECK (state IN ('DRAFT','IN_PROGRESS','RECEIVED','RECEIVED_WITH_DISCREPANCY','CANCELLED')),
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    received_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    received_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (destination_storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        (state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY') AND received_by IS NOT NULL AND received_at IS NOT NULL)
        OR (state IN ('DRAFT','IN_PROGRESS','CANCELLED') AND received_by IS NULL AND received_at IS NULL)
    )
);

CREATE TABLE receiving_line (
    id uuid PRIMARY KEY,
    receiving_record_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    candidate_class varchar(24) NOT NULL
        CHECK (candidate_class IN ('STOCK_QUANTITY','STOCK_SERIALIZED','COMPANY_ASSET')),
    expected_stock_item_id uuid,
    expected_asset_type_definition_id uuid REFERENCES config_revision(id) ON DELETE RESTRICT,
    expected_quantity numeric(20,6) CHECK (expected_quantity IS NULL OR expected_quantity > 0),
    received_quantity numeric(20,6) CHECK (received_quantity IS NULL OR received_quantity > 0),
    unit_code varchar(32),
    condition_received varchar(16)
        CHECK (condition_received IS NULL OR condition_received IN ('GOOD','FAIR','DAMAGED','UNFIT')),
    serials jsonb NOT NULL DEFAULT '[]'::jsonb CHECK (jsonb_typeof(serials) = 'array'),
    source_line_ref varchar(255),
    received_asset_id uuid,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    received_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    received_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    UNIQUE (id, receiving_record_id, organization_id),
    FOREIGN KEY (receiving_record_id, organization_id)
        REFERENCES receiving_record(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (expected_stock_item_id, organization_id)
        REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (expected_stock_item_id, unit_code)
        REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (received_asset_id, organization_id)
        REFERENCES asset(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        (candidate_class IN ('STOCK_QUANTITY','STOCK_SERIALIZED')
            AND expected_stock_item_id IS NOT NULL AND expected_asset_type_definition_id IS NULL AND unit_code IS NOT NULL)
        OR
        (candidate_class = 'COMPANY_ASSET'
            AND expected_stock_item_id IS NULL AND expected_asset_type_definition_id IS NOT NULL)
    ),
    CHECK (
        (received_at IS NULL AND received_by IS NULL AND received_quantity IS NULL AND condition_received IS NULL
            AND received_asset_id IS NULL)
        OR
        (received_at IS NOT NULL AND received_by IS NOT NULL AND received_quantity IS NOT NULL AND condition_received IS NOT NULL)
    ),
    CHECK (
        (candidate_class = 'COMPANY_ASSET' AND
            (received_at IS NULL OR (received_quantity = 1 AND received_asset_id IS NOT NULL)))
        OR
        (candidate_class <> 'COMPANY_ASSET' AND received_asset_id IS NULL)
    )
);

CREATE TABLE receiving_discrepancy (
    id uuid PRIMARY KEY,
    receiving_record_id uuid NOT NULL,
    receiving_line_id uuid,
    organization_id uuid NOT NULL,
    discrepancy_type varchar(32) NOT NULL CHECK (discrepancy_type IN (
        'SHORT_QTY','OVER_QTY','WRONG_ITEM','DAMAGED_ON_RECEIPT',
        'SERIAL_MISMATCH','UNEXPECTED_ITEM','DOCUMENT_MISMATCH'
    )),
    description text NOT NULL CHECK (length(btrim(description)) > 0),
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    FOREIGN KEY (receiving_record_id, organization_id)
        REFERENCES receiving_record(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (receiving_line_id, receiving_record_id, organization_id)
        REFERENCES receiving_line(id, receiving_record_id, organization_id) ON DELETE RESTRICT
);

CREATE FUNCTION guard_receiving_record_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Receiving history is append-only' USING ERRCODE = '23514';
    END IF;
    IF OLD.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY','CANCELLED')
        OR NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['state','received_by','received_at','updated_at','version'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['state','received_by','received_at','updated_at','version'])
        OR NOT (
            (OLD.state = 'DRAFT' AND NEW.state IN ('IN_PROGRESS','CANCELLED'))
            OR (OLD.state = 'IN_PROGRESS' AND NEW.state IN ('IN_PROGRESS','RECEIVED','RECEIVED_WITH_DISCREPANCY','CANCELLED'))
        ) THEN
        RAISE EXCEPTION 'Invalid Receiving state transition' USING ERRCODE = '23514';
    END IF;
    IF NEW.state = 'CANCELLED' AND EXISTS (
        SELECT 1 FROM receiving_line WHERE receiving_record_id = OLD.id AND received_at IS NOT NULL
    ) THEN
        RAISE EXCEPTION 'A Receiving record with physical receipts cannot be cancelled' USING ERRCODE = '23514';
    END IF;
    IF NEW.state IN ('RECEIVED','RECEIVED_WITH_DISCREPANCY') THEN
        IF NOT EXISTS (SELECT 1 FROM receiving_line WHERE receiving_record_id = OLD.id)
            OR EXISTS (SELECT 1 FROM receiving_line WHERE receiving_record_id = OLD.id AND received_at IS NULL) THEN
            RAISE EXCEPTION 'Receiving completion requires every line to be physically received' USING ERRCODE = '23514';
        END IF;
        IF (NEW.state = 'RECEIVED_WITH_DISCREPANCY') <>
           EXISTS (SELECT 1 FROM receiving_discrepancy WHERE receiving_record_id = OLD.id) THEN
            RAISE EXCEPTION 'Receiving completion state must match discrepancy truth' USING ERRCODE = '23514';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER receiving_record_guard BEFORE UPDATE OR DELETE ON receiving_record
FOR EACH ROW EXECUTE FUNCTION guard_receiving_record_mutation();

CREATE FUNCTION guard_receiving_line_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    header receiving_record%ROWTYPE;
    duplicate_serial boolean;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Receiving line history is append-only' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT header FROM receiving_record WHERE id = NEW.receiving_record_id FOR NO KEY UPDATE;
    IF TG_OP = 'INSERT' THEN
        IF header.state NOT IN ('DRAFT','IN_PROGRESS') THEN
            RAISE EXCEPTION 'Receiving lines cannot be added to a closed record' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    IF header.state <> 'IN_PROGRESS' OR OLD.received_at IS NOT NULL OR NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['received_quantity','condition_received','serials','received_asset_id','received_by','received_at','version'])
           IS DISTINCT FROM (to_jsonb(OLD) - ARRAY['received_quantity','condition_received','serials','received_asset_id','received_by','received_at','version'])
        OR NEW.received_at IS NULL OR NEW.received_by IS NULL OR NEW.received_quantity IS NULL
        OR NEW.condition_received IS NULL THEN
        RAISE EXCEPTION 'Invalid Receiving line receipt' USING ERRCODE = '23514';
    END IF;
    IF EXISTS (
        SELECT 1 FROM jsonb_array_elements(NEW.serials) value
        WHERE jsonb_typeof(value) <> 'string' OR length(btrim(value #>> '{}')) NOT BETWEEN 1 AND 160
    ) THEN
        RAISE EXCEPTION 'Invalid Receiving serial evidence' USING ERRCODE = '23514';
    END IF;
    SELECT count(*) <> count(DISTINCT value #>> '{}') INTO duplicate_serial
      FROM jsonb_array_elements(NEW.serials) value;
    IF duplicate_serial THEN
        RAISE EXCEPTION 'Duplicate Receiving serial evidence' USING ERRCODE = '23514';
    END IF;
    IF NEW.candidate_class = 'STOCK_SERIALIZED' AND (
        NEW.received_quantity <> trunc(NEW.received_quantity)
        OR jsonb_array_length(NEW.serials)::numeric <> NEW.received_quantity
    ) THEN
        RAISE EXCEPTION 'Serialized Stock receipt requires one serial per unit' USING ERRCODE = '23514';
    END IF;
    IF NEW.candidate_class = 'STOCK_QUANTITY' AND jsonb_array_length(NEW.serials) <> 0 THEN
        RAISE EXCEPTION 'Serial evidence does not change Stock classification' USING ERRCODE = '23514';
    END IF;
    IF NEW.candidate_class = 'COMPANY_ASSET' AND (
        NEW.received_quantity <> 1 OR NEW.received_asset_id IS NULL OR jsonb_array_length(NEW.serials) > 1
    ) THEN
        RAISE EXCEPTION 'Asset Receiving requires one explicitly classified Asset' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER receiving_line_guard BEFORE INSERT OR UPDATE OR DELETE ON receiving_line
FOR EACH ROW EXECUTE FUNCTION guard_receiving_line_mutation();

CREATE FUNCTION reject_receiving_discrepancy_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Receiving discrepancy history is append-only' USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER receiving_discrepancy_append_only BEFORE UPDATE OR DELETE ON receiving_discrepancy
FOR EACH ROW EXECUTE FUNCTION reject_receiving_discrepancy_mutation();

ALTER TABLE stock_movement
    ADD COLUMN receiving_line_id uuid UNIQUE,
    ADD FOREIGN KEY (receiving_line_id, organization_id)
        REFERENCES receiving_line(id, organization_id) ON DELETE RESTRICT;

ALTER TABLE asset_movement
    ADD COLUMN receiving_line_id uuid UNIQUE,
    ADD FOREIGN KEY (receiving_line_id, organization_id)
        REFERENCES receiving_line(id, organization_id) ON DELETE RESTRICT;

CREATE FUNCTION validate_receiving_asset_movement() RETURNS trigger LANGUAGE plpgsql AS $
DECLARE
    line receiving_line%ROWTYPE;
    header receiving_record%ROWTYPE;
    passport asset%ROWTYPE;
BEGIN
    IF NEW.receiving_line_id IS NULL THEN
        RETURN NEW;
    END IF;
    SELECT * INTO line FROM receiving_line WHERE id = NEW.receiving_line_id FOR SHARE;
    SELECT * INTO header FROM receiving_record WHERE id = line.receiving_record_id FOR SHARE;
    SELECT * INTO passport FROM asset WHERE id = NEW.asset_id FOR SHARE;
    IF line.id IS NULL OR header.id IS NULL OR passport.id IS NULL
        OR line.candidate_class <> 'COMPANY_ASSET'
        OR line.received_asset_id IS DISTINCT FROM passport.id
        OR line.expected_asset_type_definition_id IS DISTINCT FROM passport.asset_type_definition_id
        OR line.received_quantity IS DISTINCT FROM 1::numeric
        OR line.received_by IS DISTINCT FROM NEW.recorded_by
        OR line.received_at IS DISTINCT FROM NEW.occurred_at
        OR line.condition_received::text IS DISTINCT FROM NEW.condition_at_transfer
        OR header.state <> 'IN_PROGRESS'
        OR header.organization_id <> passport.organization_id
        OR header.destination_storage_location_id IS DISTINCT FROM NEW.to_storage_location_id
        OR NEW.movement_type <> 'REGISTER_INITIAL_LOCATION'
        OR NEW.from_storage_location_id IS NOT NULL
        OR NEW.to_storage_location_id IS NULL
        OR NEW.to_custodian_target_id IS NOT NULL
        OR NEW.source_import_row_id IS NOT NULL THEN
        RAISE EXCEPTION 'Asset Receiving movement must match the exact Receiving line' USING ERRCODE = '23514';
    END IF;
    IF (passport.serial_number IS NULL AND jsonb_array_length(line.serials) <> 0)
        OR (passport.serial_number IS NOT NULL AND (
            jsonb_array_length(line.serials) <> 1 OR line.serials ->> 0 IS DISTINCT FROM passport.serial_number
        )) THEN
        RAISE EXCEPTION 'Asset Receiving serial evidence must match created Asset identity' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$;
CREATE TRIGGER asset_receiving_movement_guard BEFORE INSERT ON asset_movement
FOR EACH ROW EXECUTE FUNCTION validate_receiving_asset_movement();


CREATE OR REPLACE FUNCTION project_stock_movement() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    item stock_item%ROWTYPE;
    location storage_location%ROWTYPE;
    received_line receiving_line%ROWTYPE;
    received_header receiving_record%ROWTYPE;
    received_damaged numeric(20,6);
BEGIN
    IF NEW.movement_type IN ('ISSUE', 'RETURN', 'CONSUME') THEN
        IF NEW.receiving_line_id IS NOT NULL THEN
            RAISE EXCEPTION 'Fulfilment movement cannot be a Receiving movement' USING ERRCODE = '23514';
        END IF;
        PERFORM post_stock_fulfillment(NEW);
        RETURN NEW;
    END IF;
    IF NEW.work_requirement_instance_id IS NOT NULL OR NEW.fulfilled_reservation_id IS NOT NULL THEN
        RAISE EXCEPTION 'Unexpected fulfilment context for stock movement' USING ERRCODE = '23514';
    END IF;
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

    IF NEW.movement_type = 'RECEIVE' THEN
        SELECT * INTO received_line FROM receiving_line WHERE id = NEW.receiving_line_id FOR SHARE;
        SELECT * INTO received_header FROM receiving_record WHERE id = received_line.receiving_record_id FOR SHARE;
        IF received_line.id IS NULL OR received_header.id IS NULL
            OR NEW.from_storage_location_id IS NOT NULL OR NEW.to_storage_location_id IS NULL
            OR NEW.source_import_row_id IS NOT NULL OR NEW.purchase_receipt_id IS NOT NULL
            OR NEW.project_id IS NOT NULL OR NEW.site_id IS NOT NULL OR NEW.work_order_id IS NOT NULL
            OR NEW.recipient_target_type IS NOT NULL OR NEW.recipient_target_id IS NOT NULL
            OR received_header.state <> 'IN_PROGRESS'
            OR received_header.organization_id <> item.organization_id
            OR received_header.destination_storage_location_id <> NEW.to_storage_location_id
            OR received_line.organization_id <> item.organization_id
            OR received_line.expected_stock_item_id <> item.id
            OR received_line.received_quantity IS DISTINCT FROM NEW.quantity
            OR received_line.unit_code IS DISTINCT FROM NEW.unit_code
            OR received_line.received_by IS DISTINCT FROM NEW.recorded_by
            OR received_line.received_at IS DISTINCT FROM NEW.occurred_at
            OR received_line.candidate_class <> item.classification
            OR NEW.reason_code <> 'RECEIVE' THEN
            RAISE EXCEPTION 'Stock RECEIVE requires exact authoritative Receiving line truth' USING ERRCODE = '23514';
        END IF;
        received_damaged := CASE WHEN received_line.condition_received IN ('DAMAGED','UNFIT')
            THEN NEW.quantity ELSE 0 END;
        INSERT INTO stock_balance (stock_item_id, storage_location_id, organization_id,
            on_hand_qty, reserved_qty, damaged_qty, quarantine_qty, updated_at)
        VALUES (NEW.stock_item_id, NEW.to_storage_location_id, NEW.organization_id,
            NEW.quantity, 0, received_damaged, 0, NEW.recorded_at)
        ON CONFLICT (stock_item_id, storage_location_id) DO UPDATE
        SET on_hand_qty = stock_balance.on_hand_qty + EXCLUDED.on_hand_qty,
            damaged_qty = stock_balance.damaged_qty + EXCLUDED.damaged_qty,
            updated_at = greatest(stock_balance.updated_at, EXCLUDED.updated_at),
            version = stock_balance.version + 1;
        RETURN NEW;
    END IF;

    IF NEW.receiving_line_id IS NOT NULL THEN
        RAISE EXCEPTION 'Receiving reference requires RECEIVE movement' USING ERRCODE = '23514';
    END IF;
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
