-- One bounded company operating-settings authority; no grant or policy is seeded.
DO $$ DECLARE source_check record;
BEGIN
    FOR source_check IN SELECT conname FROM pg_constraint
        WHERE conrelid='organization_administration_authority_binding'::regclass
          AND contype='c' AND pg_get_constraintdef(oid) LIKE '%authority_key%'
    LOOP
        EXECUTE format('ALTER TABLE organization_administration_authority_binding DROP CONSTRAINT %I', source_check.conname);
    END LOOP;
END; $$;
ALTER TABLE organization_administration_authority_binding
    ADD CONSTRAINT ck_org_admin_authority_catalog CHECK (authority_key IN ('APPROVAL_POLICY_ADMIN','COMPANY_SETTINGS_ADMIN'));
ALTER TABLE responsibility_administration_binding
    DROP CONSTRAINT responsibility_administration_binding_authority_key_check;
ALTER TABLE responsibility_administration_binding
    ADD CONSTRAINT responsibility_administration_binding_authority_key_check CHECK (authority_key IN (
        'PEOPLE_ADMIN',
        'PROJECT_ADMIN',
        'SALES_VIEW',
        'SALES_WRITE',
        'SALES_COST_VIEW',
        'SALES_RELEASE',
        'SALES_AWARD',
        'PAYROLL_READ',
        'PAYROLL_PREPARE',
        'PAYROLL_REVIEW',
        'SUPPLIER_MASTER_DATA_ADMIN',
        'APPROVAL_POLICY_ADMIN',
        'COMPANY_SETTINGS_ADMIN',
        'FINANCE_PAYROLL_PREPARE',
        'FINANCE_PAYROLL_REVIEW',
        'PROCUREMENT_BUYER',
        'PROCUREMENT_TECHNICAL_REVIEW',
        'PROCUREMENT_NEED_REQUEST',
        'PROCUREMENT_NEED_REVIEW',
        'PROCUREMENT_ORDER_ISSUE',
        'PROCUREMENT_SERVICE_ACCEPT',
        'FINANCE_SUPPLIER_REVIEW',
        'FINANCE_EMPLOYEE_REVIEW',
        'FINANCE_COMMERCIAL_REVIEW',
        'FINANCE_PAYABLE_PREPARE',
        'FINANCE_PAYABLE_POST',
        'FINANCE_RECEIVABLE_PREPARE',
        'FINANCE_RECEIVABLE_EXECUTE',
        'FINANCE_EMPLOYEE_PREPARE',
        'FINANCE_EMPLOYEE_EXECUTE',
        'FINANCE_BANK_STATEMENT_PREPARE',
        'FINANCE_BANK_STATEMENT_IMPORT',
        'FINANCE_BANK_RECONCILIATION_PREPARE',
        'FINANCE_BANK_RECONCILIATION_FINALIZE',
        'FINANCE_CASH_PREPARE',
        'FINANCE_CASH_EXECUTE',
        'FINANCE_PERIOD_CLOSE_PREPARE',
        'FINANCE_PERIOD_CLOSE_EXECUTE',
        'FINANCE_REPORT_READ'
    ));

CREATE TABLE purchase_order_numbering_revision (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    revision bigint NOT NULL CHECK (revision >= 1),
    prefix varchar(24) NOT NULL CHECK (length(prefix) BETWEEN 2 AND 24 AND prefix ~ '^[A-Z][A-Z0-9]*(-[A-Z0-9]+)*$'),
    previous_source varchar(24) NOT NULL CHECK (previous_source IN ('COMPANY','DEPLOYMENT','NOT_CONFIGURED')),
    previous_prefix varchar(24),
    reason varchar(1000) NOT NULL CHECK (length(trim(reason)) BETWEEN 3 AND 1000),
    changed_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    changed_at timestamptz NOT NULL,
    UNIQUE (id, organization_id),
    UNIQUE (organization_id, revision),
    CHECK ((previous_source = 'NOT_CONFIGURED') = (previous_prefix IS NULL))
);
CREATE TRIGGER purchase_order_numbering_immutable
    BEFORE UPDATE OR DELETE ON purchase_order_numbering_revision
    FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();

CREATE FUNCTION guard_purchase_order_numbering_revision() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE prior purchase_order_numbering_revision%ROWTYPE;
BEGIN
    PERFORM 1 FROM organization WHERE id=NEW.organization_id
        AND organization_type='HILTECH' AND status='ACTIVE' FOR UPDATE;
    IF NOT FOUND OR NOT EXISTS (
        SELECT 1 FROM organization_administration_authority_binding b
        JOIN user_identity u ON u.id=b.identity_id
        JOIN organization_membership m ON m.user_identity_id=u.id AND m.organization_id=b.organization_id
        WHERE b.organization_id=NEW.organization_id AND b.identity_id=NEW.changed_by_user_id
          AND b.authority_key='COMPANY_SETTINGS_ADMIN' AND b.active=true AND u.status='ACTIVE'
          AND b.effective_from<=NEW.changed_at AND (b.effective_to IS NULL OR b.effective_to>NEW.changed_at)
          AND m.state='ACTIVE' AND m.valid_from<=NEW.changed_at AND (m.valid_until IS NULL OR m.valid_until>NEW.changed_at)
    ) THEN RAISE EXCEPTION 'Current company settings authority is required' USING ERRCODE='23514'; END IF;
    SELECT * INTO prior FROM purchase_order_numbering_revision WHERE organization_id=NEW.organization_id ORDER BY revision DESC LIMIT 1;
    IF prior.id IS NULL THEN
        IF NEW.revision<>1 OR NEW.previous_source='COMPANY' THEN
            RAISE EXCEPTION 'Initial numbering revision is invalid' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.revision<>prior.revision+1 OR NEW.previous_source<>'COMPANY'
        OR NEW.previous_prefix IS DISTINCT FROM prior.prefix OR NEW.prefix=prior.prefix THEN
        RAISE EXCEPTION 'Numbering source changed or prefix is unchanged' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER purchase_order_numbering_source_guard
    BEFORE INSERT ON purchase_order_numbering_revision
    FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_numbering_revision();

ALTER TABLE procurement_purchase_order
    ADD COLUMN numbering_revision_id uuid,
    ADD CONSTRAINT fk_po_numbering_revision_org FOREIGN KEY (numbering_revision_id, organization_id)
        REFERENCES purchase_order_numbering_revision(id, organization_id) ON DELETE RESTRICT;

CREATE FUNCTION guard_purchase_order_current_numbering() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_numbering purchase_order_numbering_revision%ROWTYPE;
BEGIN
    -- Share the company lock with configuration commands and serial allocation.
    PERFORM 1 FROM organization WHERE id=NEW.organization_id FOR UPDATE;
    SELECT * INTO current_numbering FROM purchase_order_numbering_revision
        WHERE organization_id=NEW.organization_id ORDER BY revision DESC LIMIT 1;
    IF current_numbering.id IS NULL THEN
        IF NEW.numbering_revision_id IS NOT NULL THEN
            RAISE EXCEPTION 'Numbering revision is unavailable' USING ERRCODE='23514';
        END IF;
    ELSIF NEW.numbering_revision_id IS DISTINCT FROM current_numbering.id OR
        NEW.po_code <> current_numbering.prefix || '-' || lpad(NEW.po_serial::text, greatest(6,length(NEW.po_serial::text)), '0') THEN
        RAISE EXCEPTION 'Purchase order must use current company numbering' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER purchase_order_current_numbering
    BEFORE INSERT ON procurement_purchase_order
    FOR EACH ROW EXECUTE FUNCTION guard_purchase_order_current_numbering();
