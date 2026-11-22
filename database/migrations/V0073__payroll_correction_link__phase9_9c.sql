-- Phase9 9C: immutable superseding run correction, no Finance ledger writes.
-- PROVISIONAL V0073. DevOps owns final sequential Flyway allocation.
-- Correction of a RELEASED payroll never rewrites the old run or release.

ALTER TABLE payroll_run_release
    ADD CONSTRAINT uq_payroll_release_id_organization
    UNIQUE (id, organization_id);

CREATE TABLE payroll_run_correction (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    original_run_version_id uuid NOT NULL,
    original_release_id uuid NOT NULL,
    replacement_run_version_id uuid NOT NULL,
    original_source_fingerprint char(64) NOT NULL,
    replacement_source_fingerprint char(64) NOT NULL,
    reason_code varchar(80) NOT NULL
        CHECK (reason_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    reason_evidence_ref varchar(240) NOT NULL
        CHECK (length(trim(reason_evidence_ref)) BETWEEN 3 AND 240),
    reason_evidence_version bigint NOT NULL CHECK (reason_evidence_version > 0),
    finance_disposition varchar(40) NOT NULL
        CHECK (finance_disposition IN (
            'NO_PRIOR_TRANSFER_VERIFIED',
            'REVERSAL_VERIFIED',
            'SETTLED_DIFFERENTIAL_AUTHORIZED'
        )),
    finance_clearance_ref varchar(240) NOT NULL
        CHECK (length(trim(finance_clearance_ref)) BETWEEN 3 AND 240),
    finance_clearance_version bigint NOT NULL CHECK (finance_clearance_version > 0),
    recorded_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    recorded_at timestamptz NOT NULL,
    UNIQUE (replacement_run_version_id),
    CONSTRAINT fk_payroll_correction_original
        FOREIGN KEY (original_run_version_id, organization_id)
        REFERENCES payroll_run_version(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_correction_replacement
        FOREIGN KEY (replacement_run_version_id, organization_id)
        REFERENCES payroll_run_version(id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT fk_payroll_correction_original_release
        FOREIGN KEY (original_release_id, organization_id)
        REFERENCES payroll_run_release(id, organization_id) ON DELETE RESTRICT,
    CHECK (original_run_version_id <> replacement_run_version_id),
    CHECK (original_source_fingerprint <> replacement_source_fingerprint)
);

CREATE INDEX idx_payroll_correction_org_date
    ON payroll_run_correction(organization_id, recorded_at DESC);

CREATE FUNCTION payroll_9c_correction_insert_guard() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM payroll_run_version older
        JOIN payroll_run_release released
          ON released.id = NEW.original_release_id
         AND released.run_version_id = older.id
         AND released.organization_id = NEW.organization_id
        JOIN payroll_run_version newer
          ON newer.id = NEW.replacement_run_version_id
         AND newer.organization_id = NEW.organization_id
        WHERE older.id = NEW.original_run_version_id
          AND older.organization_id = NEW.organization_id
          AND older.payroll_period_id = newer.payroll_period_id
          AND older.generation < newer.generation
          AND older.source_fingerprint = NEW.original_source_fingerprint
          AND newer.source_fingerprint = NEW.replacement_source_fingerprint
          AND newer.state = 'DRAFT'
          AND newer.generation = (
              SELECT MAX(generation)
              FROM payroll_run_version
              WHERE payroll_period_id = newer.payroll_period_id
          )
          AND NOT EXISTS (
              SELECT 1 FROM payroll_run_release already
              WHERE already.run_version_id = newer.id
          )
    ) THEN
        RAISE EXCEPTION 'Invalid payroll source-bound correction';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_9c_correction_insert_validate
    BEFORE INSERT ON payroll_run_correction FOR EACH ROW
    EXECUTE FUNCTION payroll_9c_correction_insert_guard();

CREATE TRIGGER payroll_9c_correction_immutable
    BEFORE UPDATE OR DELETE ON payroll_run_correction FOR EACH ROW
    EXECUTE FUNCTION payroll_9a_immutable_guard();
