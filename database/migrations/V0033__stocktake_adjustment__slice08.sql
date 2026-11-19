-- Phase 5 / Slice 08 — Stocktake / discrepancy / adjustment.
-- Physical count is history. It never edits StockBalance directly.

CREATE TABLE stocktake_session (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL,
    root_storage_location_id uuid,
    state varchar(24) NOT NULL CHECK (state IN ('DRAFT','ACTIVE','SUBMITTED','RECONCILING','CLOSED','CANCELLED')),
    scope_category_definition_ids jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(scope_category_definition_ids) = 'array'),
    scope_stock_item_ids jsonb NOT NULL DEFAULT '[]'::jsonb
        CHECK (jsonb_typeof(scope_stock_item_ids) = 'array'),
    snapshot_at timestamptz,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    submitted_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    submitted_at timestamptz,
    closed_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    closed_at timestamptz,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (warehouse_id, organization_id) REFERENCES warehouse(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (root_storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CHECK ((root_storage_location_id IS NULL) OR warehouse_id IS NOT NULL),
    CHECK ((state = 'DRAFT' AND snapshot_at IS NULL) OR state <> 'DRAFT')
);

CREATE TABLE stocktake_snapshot_line (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    expected_on_hand_qty numeric(20,6) NOT NULL CHECK (expected_on_hand_qty >= 0),
    expected_reserved_qty numeric(20,6) NOT NULL CHECK (expected_reserved_qty >= 0),
    expected_damaged_qty numeric(20,6) NOT NULL CHECK (expected_damaged_qty >= 0),
    expected_quarantine_qty numeric(20,6) NOT NULL CHECK (expected_quarantine_qty >= 0),
    unit_code varchar(32) NOT NULL,
    stock_balance_version bigint NOT NULL CHECK (stock_balance_version >= 1),
    stock_item_version bigint NOT NULL CHECK (stock_item_version >= 1),
    snapshot_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (id, stocktake_session_id, organization_id),
    UNIQUE (stocktake_session_id, stock_item_id, storage_location_id),
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE stock_count_record (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    snapshot_line_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    counted_quantity numeric(20,6) NOT NULL CHECK (counted_quantity >= 0),
    unit_code varchar(32) NOT NULL,
    counted_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    counted_at timestamptz NOT NULL,
    scan_source varchar(255),
    notes text,
    supersedes_count_record_id uuid REFERENCES stock_count_record(id) ON DELETE RESTRICT,
    operation_id uuid NOT NULL UNIQUE,
    correlation_id varchar(128) NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version = 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (snapshot_line_id, stocktake_session_id, organization_id)
        REFERENCES stocktake_snapshot_line(id, stocktake_session_id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE inventory_discrepancy (
    id uuid PRIMARY KEY,
    stocktake_session_id uuid NOT NULL,
    snapshot_line_id uuid NOT NULL,
    latest_count_record_id uuid NOT NULL REFERENCES stock_count_record(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    expected_quantity numeric(20,6) NOT NULL,
    counted_quantity numeric(20,6) NOT NULL,
    variance_quantity numeric(20,6) NOT NULL,
    snapshot_balance_version bigint NOT NULL CHECK (snapshot_balance_version >= 1),
    current_balance_version bigint NOT NULL CHECK (current_balance_version >= 1),
    source_changed_since_snapshot boolean NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN (
        'OPEN','INVESTIGATING','RESOLVED_NO_ADJUSTMENT','ADJUSTMENT_REQUESTED','CLOSED'
    )),
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    UNIQUE (stocktake_session_id, snapshot_line_id),
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (snapshot_line_id, stocktake_session_id, organization_id)
        REFERENCES stocktake_snapshot_line(id, stocktake_session_id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT
);

CREATE TABLE inventory_adjustment_request (
    id uuid PRIMARY KEY,
    inventory_discrepancy_id uuid NOT NULL UNIQUE,
    stocktake_session_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    stock_item_id uuid NOT NULL,
    storage_location_id uuid NOT NULL,
    delta_quantity numeric(20,6) NOT NULL CHECK (delta_quantity <> 0),
    unit_code varchar(32) NOT NULL,
    reason text NOT NULL CHECK (length(btrim(reason)) > 0),
    requested_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    requested_at timestamptz NOT NULL,
    approval_request_id uuid REFERENCES approval_request(id) ON DELETE RESTRICT,
    approval_status varchar(24) CHECK (approval_status IS NULL OR approval_status IN ('PENDING','APPROVED','REJECTED')),
    applied_movement_id uuid UNIQUE REFERENCES stock_movement(id) ON DELETE RESTRICT,
    applied_by uuid REFERENCES user_identity(id) ON DELETE RESTRICT,
    applied_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (id, organization_id),
    FOREIGN KEY (inventory_discrepancy_id, organization_id)
        REFERENCES inventory_discrepancy(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stocktake_session_id, organization_id)
        REFERENCES stocktake_session(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, organization_id) REFERENCES stock_item(id, organization_id) ON DELETE RESTRICT,
    FOREIGN KEY (stock_item_id, unit_code) REFERENCES stock_item(id, unit_of_measure_code) ON DELETE RESTRICT,
    FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        (applied_movement_id IS NULL AND applied_by IS NULL AND applied_at IS NULL)
        OR
        (applied_movement_id IS NOT NULL AND applied_by IS NOT NULL AND applied_at IS NOT NULL)
    )
);

CREATE FUNCTION guard_stocktake_session_mutation() RETURNS trigger LANGUAGE plpgsql AS $stocktake_session$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Stocktake session history is durable' USING ERRCODE = '23514';
    END IF;
    IF NEW.version <> OLD.version + 1
        OR (to_jsonb(NEW) - ARRAY['state','snapshot_at','updated_at','submitted_by','submitted_at',
                                   'closed_by','closed_at','version'])
           IS DISTINCT FROM
           (to_jsonb(OLD) - ARRAY['state','snapshot_at','updated_at','submitted_by','submitted_at',
                                   'closed_by','closed_at','version']) THEN
        RAISE EXCEPTION 'Stocktake scope is immutable after creation' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_session$;
CREATE TRIGGER stocktake_session_guard BEFORE UPDATE OR DELETE ON stocktake_session
FOR EACH ROW EXECUTE FUNCTION guard_stocktake_session_mutation();

CREATE FUNCTION reject_stocktake_snapshot_mutation() RETURNS trigger LANGUAGE plpgsql AS $stocktake_snapshot$
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Stocktake snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM stocktake_session s
        WHERE s.id = NEW.stocktake_session_id AND s.organization_id = NEW.organization_id AND s.state = 'DRAFT'
    ) THEN
        RAISE EXCEPTION 'Snapshot lines may only be captured while Stocktake is DRAFT' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_snapshot$;
CREATE TRIGGER stocktake_snapshot_guard BEFORE INSERT OR UPDATE OR DELETE ON stocktake_snapshot_line
FOR EACH ROW EXECUTE FUNCTION reject_stocktake_snapshot_mutation();

CREATE FUNCTION guard_stock_count_record() RETURNS trigger LANGUAGE plpgsql AS $stocktake_count$
DECLARE
    session_row stocktake_session%ROWTYPE;
    snapshot_row stocktake_snapshot_line%ROWTYPE;
    latest_id uuid;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Stock count history is append-only' USING ERRCODE = '23514';
    END IF;
    SELECT * INTO STRICT session_row FROM stocktake_session WHERE id = NEW.stocktake_session_id FOR NO KEY UPDATE;
    SELECT * INTO STRICT snapshot_row FROM stocktake_snapshot_line WHERE id = NEW.snapshot_line_id FOR SHARE;
    IF session_row.state <> 'ACTIVE'
        OR session_row.organization_id <> NEW.organization_id
        OR snapshot_row.stocktake_session_id <> session_row.id
        OR snapshot_row.organization_id <> NEW.organization_id
        OR snapshot_row.stock_item_id <> NEW.stock_item_id
        OR snapshot_row.storage_location_id <> NEW.storage_location_id
        OR snapshot_row.unit_code <> NEW.unit_code
        OR NEW.counted_at < snapshot_row.snapshot_at THEN
        RAISE EXCEPTION 'Count does not match active Stocktake snapshot truth' USING ERRCODE = '23514';
    END IF;
    SELECT c.id INTO latest_id FROM stock_count_record c
      WHERE c.snapshot_line_id = NEW.snapshot_line_id
        AND NOT EXISTS (
            SELECT 1 FROM stock_count_record newer
            WHERE newer.snapshot_line_id = c.snapshot_line_id
              AND newer.supersedes_count_record_id = c.id
        )
      LIMIT 1;
    IF latest_id IS NULL AND NEW.supersedes_count_record_id IS NOT NULL THEN
        RAISE EXCEPTION 'First count cannot supersede history' USING ERRCODE = '23514';
    END IF;
    IF latest_id IS NOT NULL AND NEW.supersedes_count_record_id IS DISTINCT FROM latest_id THEN
        RAISE EXCEPTION 'Recount must supersede the current count record' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$stocktake_count$;
CREATE TRIGGER stock_count_record_guard BEFORE INSERT OR UPDATE OR DELETE ON stock_count_record
FOR EACH ROW EXECUTE FUNCTION guard_stock_count_record();
