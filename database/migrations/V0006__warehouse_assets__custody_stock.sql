-- HILTECH OS warehouse / asset / stock custody schema.

CREATE TABLE warehouse (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    facility_ref varchar(255) NULL,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (organization_id, code)
);

CREATE TABLE storage_location (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    kind varchar(32) NOT NULL CHECK (kind IN ('MAIN_WAREHOUSE','WAREHOUSE','PROJECT_STORAGE','SITE_STORAGE','OTHER_TYPED')),
    parent_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    warehouse_id uuid NULL REFERENCES warehouse(id) ON DELETE RESTRICT,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    temporary boolean NOT NULL DEFAULT false,
    active_from timestamptz NULL,
    active_until timestamptz NULL,
    responsible_relationship_code varchar(64) NULL,
    restricted_access boolean NOT NULL DEFAULT false,
    address_or_location_ref varchar(255) NULL,
    notes text NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (parent_storage_location_id IS NULL OR parent_storage_location_id <> id),
    CHECK (active_until IS NULL OR active_from IS NULL OR active_until > active_from),
    CHECK (kind NOT IN ('MAIN_WAREHOUSE','WAREHOUSE') OR warehouse_id IS NOT NULL),
    CHECK (kind <> 'PROJECT_STORAGE' OR project_id IS NOT NULL),
    CHECK (kind <> 'SITE_STORAGE' OR (project_id IS NOT NULL AND site_id IS NOT NULL)),
    UNIQUE (organization_id, code)
);

CREATE TABLE asset (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    asset_code varchar(64) NOT NULL,
    asset_type_definition_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    asset_type_revision integer NOT NULL CHECK (asset_type_revision > 0),
    manufacturer varchar(160) NULL,
    model varchar(160) NULL,
    serial_number varchar(160) NULL,
    ownership_type_code varchar(64) NOT NULL,
    lifecycle_state varchar(32) NOT NULL CHECK (lifecycle_state IN ('ACTIVE','RETIREMENT_REQUESTED','RETIRED')),
    condition_state varchar(16) NOT NULL CHECK (condition_state IN ('UNKNOWN','GOOD','FAIR','DAMAGED','UNFIT')),
    purchase_date date NULL,
    acquisition_cost numeric NULL CHECK (acquisition_cost IS NULL OR acquisition_cost >= 0),
    currency_code char(3) NULL,
    warranty_start date NULL,
    warranty_end date NULL,
    calibration_due_at timestamptz NULL,
    maintenance_plan_ref uuid NULL,
    last_observed_at timestamptz NULL,
    last_observed_source varchar(64) NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    retired_at timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (warranty_end IS NULL OR warranty_start IS NULL OR warranty_end >= warranty_start),
    UNIQUE (organization_id, asset_code)
);

CREATE TABLE asset_tag (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    tag_type_code varchar(64) NOT NULL,
    public_opaque_code varchar(255) NOT NULL UNIQUE,
    provider_external_id varchar(255) NULL,
    active boolean NOT NULL,
    is_primary boolean NOT NULL DEFAULT false,
    issued_at timestamptz NOT NULL,
    replaced_tag_id uuid NULL REFERENCES asset_tag(id) ON DELETE RESTRICT,
    CHECK (replaced_tag_id IS NULL OR replaced_tag_id <> id)
);

CREATE TABLE asset_movement (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    movement_type varchar(64) NOT NULL,
    from_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    to_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    from_custodian_target_type varchar(64) NULL,
    from_custodian_target_id uuid NULL,
    to_custodian_target_type varchar(64) NULL,
    to_custodian_target_id uuid NULL,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    source_operation_id uuid NOT NULL,
    condition_at_transfer varchar(16) NULL CHECK (condition_at_transfer IS NULL OR condition_at_transfer IN ('UNKNOWN','GOOD','FAIR','DAMAGED','UNFIT')),
    expected_return_at timestamptz NULL,
    reason_code varchar(64) NULL,
    notes text NULL,
    correction_of_movement_id uuid NULL REFERENCES asset_movement(id) ON DELETE RESTRICT,
    correlation_id varchar(128) NOT NULL,
    CHECK (correction_of_movement_id IS NULL OR correction_of_movement_id <> id),
    CHECK (expected_return_at IS NULL OR expected_return_at >= occurred_at),
    UNIQUE (asset_id, source_operation_id)
);

CREATE TABLE asset_custody_projection (
    asset_id uuid PRIMARY KEY REFERENCES asset(id) ON DELETE CASCADE,
    current_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    current_custodian_target_type varchar(64) NULL,
    current_custodian_target_id uuid NULL,
    current_project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    current_site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    current_work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    custody_started_at timestamptz NULL,
    expected_return_at timestamptz NULL,
    source_movement_id uuid NOT NULL REFERENCES asset_movement(id) ON DELETE RESTRICT,
    asset_version bigint NOT NULL CHECK (asset_version >= 1),
    updated_at timestamptz NOT NULL
);

CREATE TABLE asset_return_inspection (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    return_movement_id uuid NOT NULL UNIQUE REFERENCES asset_movement(id) ON DELETE RESTRICT,
    inspected_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    inspected_at timestamptz NOT NULL,
    condition_observed varchar(16) NOT NULL CHECK (condition_observed IN ('UNKNOWN','GOOD','FAIR','DAMAGED','UNFIT')),
    accessory_template_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    accessory_template_revision integer NULL,
    accessory_results jsonb NULL,
    damage_incident_id uuid NULL,
    notes text NULL,
    resulting_availability_summary varchar(255) NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1)
);

CREATE TABLE stock_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    item_code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    category_definition_id uuid NOT NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    category_revision integer NOT NULL CHECK (category_revision > 0),
    unit_of_measure_code varchar(32) NOT NULL,
    serialized boolean NOT NULL DEFAULT false,
    lot_tracked boolean NOT NULL DEFAULT false,
    active boolean NOT NULL DEFAULT true,
    valuation_class_ref varchar(255) NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (organization_id, item_code)
);

CREATE TABLE stock_balance (
    stock_item_id uuid NOT NULL REFERENCES stock_item(id) ON DELETE RESTRICT,
    storage_location_id uuid NOT NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    on_hand_qty numeric(20,6) NOT NULL CHECK (on_hand_qty >= 0),
    reserved_qty numeric(20,6) NOT NULL CHECK (reserved_qty >= 0),
    damaged_qty numeric(20,6) NOT NULL CHECK (damaged_qty >= 0),
    quarantine_qty numeric(20,6) NOT NULL CHECK (quarantine_qty >= 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (stock_item_id, storage_location_id),
    CHECK (reserved_qty + damaged_qty + quarantine_qty <= on_hand_qty)
);

CREATE TABLE stock_movement (
    id uuid PRIMARY KEY,
    stock_item_id uuid NOT NULL REFERENCES stock_item(id) ON DELETE RESTRICT,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(32) NOT NULL,
    movement_type varchar(64) NOT NULL,
    from_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    to_storage_location_id uuid NULL REFERENCES storage_location(id) ON DELETE RESTRICT,
    project_id uuid NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    recipient_target_type varchar(64) NULL,
    recipient_target_id uuid NULL,
    purchase_receipt_id uuid NULL,
    reason_code varchar(64) NOT NULL,
    operation_id uuid NOT NULL UNIQUE,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    correlation_id varchar(128) NOT NULL
);

CREATE TABLE asset_reservation (
    id uuid PRIMARY KEY,
    asset_id uuid NOT NULL REFERENCES asset(id) ON DELETE RESTRICT,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    reserved_for_target_type varchar(64) NULL,
    reserved_for_target_id uuid NULL,
    start_at timestamptz NULL,
    end_at timestamptz NULL,
    state varchar(16) NOT NULL CHECK (state IN ('REQUESTED','ACTIVE','RELEASED','CANCELLED','FULFILLED','EXPIRED')),
    priority_code varchar(64) NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at)
);

CREATE TABLE stock_reservation (
    id uuid PRIMARY KEY,
    stock_item_id uuid NOT NULL REFERENCES stock_item(id) ON DELETE RESTRICT,
    quantity numeric(20,6) NOT NULL CHECK (quantity > 0),
    unit_code varchar(32) NOT NULL,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NULL REFERENCES site(id) ON DELETE RESTRICT,
    work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    requested_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    reserved_for_target_type varchar(64) NULL,
    reserved_for_target_id uuid NULL,
    start_at timestamptz NULL,
    end_at timestamptz NULL,
    state varchar(16) NOT NULL CHECK (state IN ('REQUESTED','ACTIVE','RELEASED','CANCELLED','FULFILLED','EXPIRED')),
    priority_code varchar(64) NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (end_at IS NULL OR start_at IS NULL OR end_at > start_at)
);
