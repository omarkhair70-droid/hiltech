-- HILTECH OS Project / Site / Area authoritative schema.

CREATE TABLE project (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    project_code varchar(64) NOT NULL,
    name varchar(240) NOT NULL,
    client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    source_opportunity_id uuid NULL,
    contract_id uuid NULL,
    client_po_id uuid NULL,
    lifecycle_state varchar(32) NOT NULL CHECK (lifecycle_state IN (
        'DRAFT','KICKOFF','PLANNING','READY','ACTIVE','ON_HOLD',
        'DELIVERY_REVIEW','HANDOVER','DELIVERED','CLOSED'
    )),
    project_manager_id uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    start_date_planned date NULL,
    end_date_planned date NULL,
    start_date_actual timestamptz NULL,
    end_date_actual timestamptz NULL,
    baseline_version integer NOT NULL DEFAULT 1 CHECK (baseline_version >= 1),
    currency_code char(3) NULL,
    internal_budget_amount numeric NULL,
    sell_value_amount numeric NULL,
    payment_terms_ref varchar(255) NULL,
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (end_date_planned IS NULL OR start_date_planned IS NULL OR end_date_planned >= start_date_planned),
    CHECK (internal_budget_amount IS NULL OR internal_budget_amount >= 0),
    CHECK (sell_value_amount IS NULL OR sell_value_amount >= 0),
    UNIQUE (organization_id, project_code)
);

CREATE TABLE project_health_projection (
    project_id uuid PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    health_state varchar(16) NOT NULL CHECK (health_state IN ('UNKNOWN','HEALTHY','ATTENTION','CRITICAL','ON_HOLD')),
    signal_summary_json jsonb NOT NULL,
    baseline_version integer NOT NULL CHECK (baseline_version >= 1),
    as_of timestamptz NOT NULL
);

CREATE TABLE project_progress_projection (
    project_id uuid PRIMARY KEY REFERENCES project(id) ON DELETE CASCADE,
    baseline_version integer NOT NULL CHECK (baseline_version >= 1),
    accepted_weight numeric(20,6) NOT NULL CHECK (accepted_weight >= 0),
    total_weight numeric(20,6) NOT NULL CHECK (total_weight >= 0),
    progress_percent numeric(7,4) NULL CHECK (progress_percent IS NULL OR (progress_percent >= 0 AND progress_percent <= 100)),
    as_of timestamptz NOT NULL,
    CHECK (accepted_weight <= total_weight),
    CHECK (total_weight <> 0 OR progress_percent IS NULL)
);

CREATE TABLE site (
    id uuid PRIMARY KEY,
    client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    site_code varchar(64) NOT NULL,
    name varchar(240) NOT NULL,
    address_text text NULL,
    latitude numeric NULL CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    longitude numeric NULL CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    timezone varchar(64) NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (client_organization_id, site_code)
);

CREATE TABLE project_site (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    site_id uuid NOT NULL REFERENCES site(id) ON DELETE RESTRICT,
    project_site_code varchar(64) NULL,
    lifecycle_state varchar(16) NOT NULL CHECK (lifecycle_state IN ('PLANNED','ACTIVE','ON_HOLD','COMPLETED','CLOSED')),
    access_instructions text NULL,
    project_specific_notes text NULL,
    active_from timestamptz NULL,
    active_until timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (active_until IS NULL OR active_from IS NULL OR active_until > active_from),
    UNIQUE (project_id, site_id),
    UNIQUE (id, project_id, site_id)
);

CREATE TABLE area (
    id uuid PRIMARY KEY,
    site_id uuid NOT NULL REFERENCES site(id) ON DELETE RESTRICT,
    parent_area_id uuid NULL REFERENCES area(id) ON DELETE RESTRICT,
    type_code varchar(64) NOT NULL,
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    sequence integer NULL,
    restricted_access boolean NOT NULL DEFAULT false,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (parent_area_id IS NULL OR parent_area_id <> id),
    UNIQUE (site_id, code)
);
