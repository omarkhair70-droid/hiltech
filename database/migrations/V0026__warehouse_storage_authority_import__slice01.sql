-- Phase 5 / Slice 01 — Warehouse / Storage / Authority / Import Staging.
-- Forward-only hardening over the V0006 warehouse/assets structural foundation.
-- This migration intentionally does NOT create authoritative stock opening
-- balances, Asset checkout, receiving, reservation or Phase-6 field state.

-- ---------------------------------------------------------------------------
-- Warehouse / StorageLocation identity and organization-safe references.
-- ---------------------------------------------------------------------------

ALTER TABLE warehouse
    ADD CONSTRAINT uq_warehouse_id_organization
        UNIQUE (id, organization_id),
    ADD CONSTRAINT ck_warehouse_code_non_blank
        CHECK (length(trim(code)) BETWEEN 1 AND 64),
    ADD CONSTRAINT ck_warehouse_name_non_blank
        CHECK (length(trim(name)) BETWEEN 1 AND 160);

ALTER TABLE storage_location
    ADD COLUMN lifecycle_state varchar(16) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT uq_storage_location_id_organization
        UNIQUE (id, organization_id),
    ADD CONSTRAINT ck_storage_location_lifecycle
        CHECK (lifecycle_state IN ('ACTIVE', 'RETIRED')),
    ADD CONSTRAINT ck_storage_location_code_non_blank
        CHECK (length(trim(code)) BETWEEN 1 AND 64),
    ADD CONSTRAINT ck_storage_location_name_non_blank
        CHECK (length(trim(name)) BETWEEN 1 AND 160);

UPDATE storage_location
SET temporary = true
WHERE kind IN ('PROJECT_STORAGE', 'SITE_STORAGE')
  AND temporary = false;

ALTER TABLE storage_location
    ADD CONSTRAINT ck_storage_location_temporary_context
        CHECK (
            kind NOT IN ('PROJECT_STORAGE', 'SITE_STORAGE')
            OR temporary = true
        ),
    ADD CONSTRAINT ck_storage_location_retirement_time
        CHECK (
            lifecycle_state <> 'RETIRED'
            OR active_until IS NOT NULL
        ),
    ADD CONSTRAINT fk_storage_parent_org
        FOREIGN KEY (parent_storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_storage_warehouse_org
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES warehouse(id, organization_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_storage_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_storage_site_org
        FOREIGN KEY (site_id, organization_id)
        REFERENCES site(id, organization_id)
        ON DELETE RESTRICT;

CREATE INDEX idx_warehouse_org_active_code
    ON warehouse (
        organization_id,
        active,
        code,
        id
    );

CREATE INDEX idx_storage_location_org_current
    ON storage_location (
        organization_id,
        lifecycle_state,
        kind,
        warehouse_id,
        project_id,
        site_id,
        id
    );

CREATE INDEX idx_storage_location_parent
    ON storage_location (
        organization_id,
        parent_storage_location_id,
        id
    );

-- A Site-storage context must point to a canonical ProjectSite relationship.
CREATE OR REPLACE FUNCTION enforce_storage_location_business_context()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.kind = 'SITE_STORAGE' THEN
        IF NEW.project_id IS NULL OR NEW.site_id IS NULL THEN
            RAISE EXCEPTION
                'SITE_STORAGE requires project_id and site_id'
                USING ERRCODE = '23514';
        END IF;

        IF NOT EXISTS (
            SELECT 1
            FROM project_site ps
            WHERE ps.organization_id = NEW.organization_id
              AND ps.project_id = NEW.project_id
              AND ps.site_id = NEW.site_id
        ) THEN
            RAISE EXCEPTION
                'SITE_STORAGE project/site context is not a canonical ProjectSite'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF NEW.kind = 'PROJECT_STORAGE'
       AND NEW.project_id IS NULL THEN
        RAISE EXCEPTION
            'PROJECT_STORAGE requires project_id'
            USING ERRCODE = '23514';
    END IF;

    IF NEW.kind IN ('MAIN_WAREHOUSE', 'WAREHOUSE')
       AND NEW.warehouse_id IS NULL THEN
        RAISE EXCEPTION
            'Warehouse storage requires warehouse_id'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_storage_location_business_context
BEFORE INSERT OR UPDATE OF
    organization_id,
    kind,
    warehouse_id,
    project_id,
    site_id
ON storage_location
FOR EACH ROW
EXECUTE FUNCTION enforce_storage_location_business_context();

-- Prevent arbitrary-depth hierarchy cycles, not only self-parenting.
CREATE OR REPLACE FUNCTION prevent_storage_location_cycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    cycle_found boolean;
BEGIN
    IF NEW.parent_storage_location_id IS NULL THEN
        RETURN NEW;
    END IF;

    IF NEW.parent_storage_location_id = NEW.id THEN
        RAISE EXCEPTION
            'StorageLocation cannot parent itself'
            USING ERRCODE = '23514';
    END IF;

    WITH RECURSIVE ancestors AS (
        SELECT
            sl.id,
            sl.parent_storage_location_id
        FROM storage_location sl
        WHERE sl.id = NEW.parent_storage_location_id
          AND sl.organization_id = NEW.organization_id

        UNION

        SELECT
            parent.id,
            parent.parent_storage_location_id
        FROM storage_location parent
        JOIN ancestors child
          ON parent.id = child.parent_storage_location_id
        WHERE parent.organization_id = NEW.organization_id
    )
    SELECT EXISTS (
        SELECT 1
        FROM ancestors
        WHERE id = NEW.id
    )
    INTO cycle_found;

    IF cycle_found THEN
        RAISE EXCEPTION
            'StorageLocation hierarchy cycle detected'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_storage_location_no_cycle
BEFORE INSERT OR UPDATE OF
    parent_storage_location_id,
    organization_id
ON storage_location
FOR EACH ROW
EXECUTE FUNCTION prevent_storage_location_cycle();

-- The old responsible_relationship_code field is retained for backward
-- compatibility only. Slice 01 authority below is the current source truth.
COMMENT ON COLUMN storage_location.responsible_relationship_code IS
    'Legacy non-authoritative hint. Phase 5 current authority uses effective-dated authority bindings.';

-- ---------------------------------------------------------------------------
-- Current-source Warehouse / StorageLocation authority.
-- ---------------------------------------------------------------------------

CREATE TABLE warehouse_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    warehouse_id uuid NOT NULL,
    authority_key varchar(48) NOT NULL
        CHECK (
            authority_key IN (
                'WAREHOUSE_MANAGER',
                'WAREHOUSE_OPERATOR',
                'WAREHOUSE_VIEWER',
                'ASSET_MASTER_MANAGER',
                'INVENTORY_ADJUSTMENT_APPROVER',
                'INVENTORY_VALUE_VIEWER'
            )
        ),
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    active boolean NOT NULL DEFAULT true,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CONSTRAINT fk_warehouse_authority_warehouse_org
        FOREIGN KEY (warehouse_id, organization_id)
        REFERENCES warehouse(id, organization_id)
        ON DELETE RESTRICT,
    CHECK (
        (
            principal_type = 'USER'
            AND principal_user_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_user_id IS NULL
        )
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    )
);

CREATE UNIQUE INDEX uq_warehouse_authority_current_user
    ON warehouse_authority_binding (
        warehouse_id,
        authority_key,
        principal_user_id
    )
    WHERE active = true
      AND principal_type = 'USER'
      AND effective_to IS NULL;

CREATE UNIQUE INDEX uq_warehouse_authority_current_team
    ON warehouse_authority_binding (
        warehouse_id,
        authority_key,
        principal_team_id
    )
    WHERE active = true
      AND principal_type = 'TEAM'
      AND effective_to IS NULL;

CREATE INDEX idx_warehouse_authority_current
    ON warehouse_authority_binding (
        organization_id,
        warehouse_id,
        authority_key,
        active,
        effective_from,
        effective_to
    );

CREATE TABLE storage_location_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    storage_location_id uuid NOT NULL,
    authority_key varchar(48) NOT NULL
        CHECK (
            authority_key IN (
                'LOCATION_RESPONSIBLE',
                'LOCATION_OPERATOR',
                'LOCATION_VIEWER'
            )
        ),
    principal_type varchar(16) NOT NULL
        CHECK (principal_type IN ('USER', 'TEAM')),
    principal_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    principal_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    active boolean NOT NULL DEFAULT true,
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CONSTRAINT fk_storage_authority_location_org
        FOREIGN KEY (storage_location_id, organization_id)
        REFERENCES storage_location(id, organization_id)
        ON DELETE RESTRICT,
    CHECK (
        (
            principal_type = 'USER'
            AND principal_user_id IS NOT NULL
            AND principal_team_id IS NULL
        )
        OR
        (
            principal_type = 'TEAM'
            AND principal_team_id IS NOT NULL
            AND principal_user_id IS NULL
        )
    ),
    CHECK (
        effective_to IS NULL
        OR effective_to >= effective_from
    )
);

CREATE UNIQUE INDEX uq_storage_authority_current_user
    ON storage_location_authority_binding (
        storage_location_id,
        authority_key,
        principal_user_id
    )
    WHERE active = true
      AND principal_type = 'USER'
      AND effective_to IS NULL;

CREATE UNIQUE INDEX uq_storage_authority_current_team
    ON storage_location_authority_binding (
        storage_location_id,
        authority_key,
        principal_team_id
    )
    WHERE active = true
      AND principal_type = 'TEAM'
      AND effective_to IS NULL;

CREATE INDEX idx_storage_authority_current
    ON storage_location_authority_binding (
        organization_id,
        storage_location_id,
        authority_key,
        active,
        effective_from,
        effective_to
    );

-- Binding creation must not create cross-organization TEAM authority or grant
-- authority to a USER who is not a current organization member. Ongoing
-- currentness is still revalidated at command time so ended membership denies
-- immediately without deleting history.
CREATE OR REPLACE FUNCTION enforce_inventory_authority_principal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.principal_type = 'TEAM' THEN
        IF NOT EXISTS (
            SELECT 1
            FROM team t
            WHERE t.id = NEW.principal_team_id
              AND t.organization_id = NEW.organization_id
              AND t.active = true
        ) THEN
            RAISE EXCEPTION
                'Inventory authority TEAM is not current in organization'
                USING ERRCODE = '23514';
        END IF;
    ELSE
        IF NOT EXISTS (
            SELECT 1
            FROM user_identity ui
            JOIN organization_membership om
              ON om.user_identity_id = ui.id
             AND om.organization_id = NEW.organization_id
             AND om.state = 'ACTIVE'
             AND om.valid_from <= NEW.effective_from
             AND (
                 om.valid_until IS NULL
                 OR om.valid_until > NEW.effective_from
             )
            WHERE ui.id = NEW.principal_user_id
              AND ui.status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION
                'Inventory authority USER is not a current organization member'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_warehouse_authority_principal
BEFORE INSERT OR UPDATE OF
    organization_id,
    principal_type,
    principal_user_id,
    principal_team_id,
    effective_from
ON warehouse_authority_binding
FOR EACH ROW
EXECUTE FUNCTION enforce_inventory_authority_principal();

CREATE TRIGGER trg_storage_authority_principal
BEFORE INSERT OR UPDATE OF
    organization_id,
    principal_type,
    principal_user_id,
    principal_team_id,
    effective_from
ON storage_location_authority_binding
FOR EACH ROW
EXECUTE FUNCTION enforce_inventory_authority_principal();

-- ---------------------------------------------------------------------------
-- Legacy inventory import staging.
-- Staging is review evidence only; it has no FK that can mutate Stock/Asset.
-- ---------------------------------------------------------------------------

CREATE TABLE inventory_import_batch (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    source_file_name varchar(255) NOT NULL
        CHECK (length(trim(source_file_name)) BETWEEN 1 AND 255),
    source_sha256 char(64) NOT NULL
        CHECK (source_sha256 ~ '^[0-9a-f]{64}$'),
    source_type varchar(32) NOT NULL
        CHECK (
            source_type IN (
                'LEGACY_EXCEL',
                'CSV',
                'MANUAL_IMPORT'
            )
        ),
    cutover_date date NULL,
    state varchar(24) NOT NULL
        CHECK (
            state IN (
                'UPLOADED',
                'PARSED',
                'IN_REVIEW',
                'REVIEWED',
                'CANCELLED'
            )
        ),
    created_by_user_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (updated_at >= created_at),
    UNIQUE (organization_id, source_sha256)
);

CREATE INDEX idx_inventory_import_batch_org_state
    ON inventory_import_batch (
        organization_id,
        state,
        created_at DESC,
        id
    );

CREATE TABLE inventory_import_row (
    id uuid PRIMARY KEY,
    batch_id uuid NOT NULL
        REFERENCES inventory_import_batch(id) ON DELETE CASCADE,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    source_sheet varchar(160) NOT NULL
        CHECK (length(trim(source_sheet)) BETWEEN 1 AND 160),
    source_row_number integer NOT NULL
        CHECK (source_row_number > 0),
    raw_payload jsonb NOT NULL,
    candidate_class varchar(24) NOT NULL DEFAULT 'UNCLASSIFIED'
        CHECK (
            candidate_class IN (
                'UNCLASSIFIED',
                'STOCK_QUANTITY',
                'STOCK_SERIALIZED',
                'COMPANY_ASSET'
            )
        ),
    normalized_brand varchar(160) NULL,
    normalized_part_number varchar(160) NULL,
    normalized_description text NULL,
    candidate_uom varchar(32) NULL,
    candidate_quantity numeric(20,6) NULL
        CHECK (candidate_quantity IS NULL OR candidate_quantity >= 0),
    candidate_value numeric(20,6) NULL
        CHECK (candidate_value IS NULL OR candidate_value >= 0),
    candidate_currency_code char(3) NULL,
    issue_codes varchar(64)[] NOT NULL DEFAULT ARRAY[]::varchar[],
    review_state varchar(24) NOT NULL DEFAULT 'PENDING'
        CHECK (
            review_state IN (
                'PENDING',
                'NEEDS_REVIEW',
                'APPROVED',
                'REJECTED'
            )
        ),
    review_notes text NULL,
    reviewed_by_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    reviewed_at timestamptz NULL,
    approved_target_type varchar(16) NULL
        CHECK (
            approved_target_type IS NULL
            OR approved_target_type IN ('STOCK_ITEM', 'ASSET')
        ),
    approved_target_id uuid NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    UNIQUE (batch_id, source_sheet, source_row_number),
    CHECK (updated_at >= created_at),
    CHECK (
        (
            reviewed_by_user_id IS NULL
            AND reviewed_at IS NULL
        )
        OR
        (
            reviewed_by_user_id IS NOT NULL
            AND reviewed_at IS NOT NULL
        )
    ),
    CHECK (
        (approved_target_type IS NULL AND approved_target_id IS NULL)
        OR
        (approved_target_type IS NOT NULL AND approved_target_id IS NOT NULL)
    ),
    CHECK (
        review_state <> 'APPROVED'
        OR reviewed_at IS NOT NULL
    )
);

CREATE INDEX idx_inventory_import_row_batch_review
    ON inventory_import_row (
        batch_id,
        review_state,
        source_sheet,
        source_row_number,
        id
    );

CREATE INDEX idx_inventory_import_row_org_issues
    ON inventory_import_row (
        organization_id,
        review_state,
        candidate_class,
        id
    );

-- Prevent a row from being attached to a batch from another organization.
CREATE OR REPLACE FUNCTION enforce_inventory_import_row_batch_org()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM inventory_import_batch b
        WHERE b.id = NEW.batch_id
          AND b.organization_id = NEW.organization_id
    ) THEN
        RAISE EXCEPTION
            'Inventory import row organization does not match batch'
            USING ERRCODE = '23514';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_import_row_batch_org
BEFORE INSERT OR UPDATE OF
    batch_id,
    organization_id
ON inventory_import_row
FOR EACH ROW
EXECUTE FUNCTION enforce_inventory_import_row_batch_org();
