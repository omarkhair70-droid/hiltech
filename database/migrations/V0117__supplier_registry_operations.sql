-- Internal supplier administration. No identities or authority grants are seeded.
CREATE TABLE supplier_master_data_authority_binding (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    active boolean NOT NULL DEFAULT true,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz NULL,
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE UNIQUE INDEX uq_supplier_master_data_authority_current
    ON supplier_master_data_authority_binding(organization_id, identity_id)
    WHERE active;

-- A company's contact/name corrections never rewrite shared global identities.
CREATE TABLE procurement_supplier_number (
    organization_id uuid PRIMARY KEY REFERENCES organization(id) ON DELETE RESTRICT,
    last_issued bigint NOT NULL CHECK (last_issued >= 1)
);
CREATE TABLE procurement_supplier_profile (
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    supplier_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    supplier_code varchar(80) NOT NULL CHECK (length(btrim(supplier_code)) > 0),
    legal_name varchar(240) NOT NULL CHECK (length(btrim(legal_name)) > 0),
    display_name varchar(160) NOT NULL CHECK (length(btrim(display_name)) > 0),
    contact_name varchar(160) NULL,
    email varchar(254) NULL,
    phone varchar(40) NULL,
    tax_reference varchar(80) NULL,
    address varchar(500) NULL,
    version bigint NOT NULL CHECK (version >= 1),
    created_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    updated_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (organization_id, supplier_organization_id),
    UNIQUE (organization_id, supplier_code),
    CHECK (organization_id <> supplier_organization_id)
);
CREATE INDEX idx_procurement_supplier_profile_name
    ON procurement_supplier_profile(organization_id, lower(display_name));

-- A transactional company counter keeps human codes compact. Rollback and replay
-- do not consume another number; existing locally linked legacy codes are reserved.
CREATE FUNCTION procurement_next_supplier_code(company uuid) RETURNS varchar LANGUAGE plpgsql AS $$
DECLARE number bigint; candidate varchar(80);
BEGIN
    LOOP
        INSERT INTO procurement_supplier_number(organization_id,last_issued) VALUES (company,1)
        ON CONFLICT (organization_id) DO UPDATE SET last_issued=procurement_supplier_number.last_issued+1
        RETURNING last_issued INTO number;
        candidate:='SUP-' || lpad(number::text,greatest(6,length(number::text)),'0');
        IF NOT EXISTS(SELECT 1 FROM procurement_supplier_profile p WHERE p.organization_id=company AND p.supplier_code=candidate)
           AND NOT EXISTS(SELECT 1 FROM organization supplier JOIN procurement_supplier_link_event link
             ON link.supplier_organization_id=supplier.id AND link.organization_id=company
             WHERE supplier.organization_code=candidate AND NOT EXISTS(
               SELECT 1 FROM procurement_supplier_profile p WHERE p.organization_id=company AND p.supplier_organization_id=supplier.id))
        THEN RETURN candidate; END IF;
    END LOOP;
END; $$;
CREATE FUNCTION procurement_supplier_profile_identity_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.organization_id IS DISTINCT FROM OLD.organization_id
       OR NEW.supplier_organization_id IS DISTINCT FROM OLD.supplier_organization_id
       OR NEW.supplier_code IS DISTINCT FROM OLD.supplier_code
       OR NEW.created_by_user_id IS DISTINCT FROM OLD.created_by_user_id
       OR NEW.created_at IS DISTINCT FROM OLD.created_at
    THEN RAISE EXCEPTION 'Supplier identity and issued company code cannot be rewritten' USING ERRCODE='23514'; END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER procurement_supplier_profile_identity_immutable BEFORE UPDATE ON procurement_supplier_profile
FOR EACH ROW EXECUTE FUNCTION procurement_supplier_profile_identity_guard();

CREATE TABLE procurement_supplier_profile_event (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL,
    supplier_organization_id uuid NOT NULL,
    version bigint NOT NULL CHECK (version >= 1),
    profile_snapshot jsonb NOT NULL,
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    FOREIGN KEY (organization_id, supplier_organization_id)
        REFERENCES procurement_supplier_profile(organization_id, supplier_organization_id) ON DELETE RESTRICT,
    UNIQUE (organization_id, supplier_organization_id, version)
);
CREATE TRIGGER procurement_supplier_profile_event_immutable
BEFORE UPDATE OR DELETE ON procurement_supplier_profile_event
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
