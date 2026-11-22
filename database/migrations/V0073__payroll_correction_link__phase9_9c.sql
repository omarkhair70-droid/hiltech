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
LANGUAGE plpgsql AS $
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM payroll_authority_binding grant_row
        JOIN user_identity principal
          ON principal.id = grant_row.identity_id
        JOIN organization_membership membership
          ON membership.user_identity_id = principal.id
         AND membership.organization_id = grant_row.organization_id
        JOIN organization org
          ON org.id = grant_row.organization_id
        WHERE grant_row.organization_id = NEW.organization_id
          AND grant_row.identity_id = NEW.recorded_by_user_id
          AND grant_row.capability = 'REVIEW'
          AND grant_row.active = true
          AND grant_row.valid_from <= NEW.recorded_at
          AND (grant_row.valid_until IS NULL OR
               grant_row.valid_until >= NEW.recorded_at)
          AND principal.status = 'ACTIVE'
          AND org.status = 'ACTIVE'
          AND membership.state = 'ACTIVE'
          AND membership.valid_from <= NEW.recorded_at
          AND (membership.valid_until IS NULL OR
               membership.valid_until >= NEW.recorded_at)
    ) THEN
        RAISE EXCEPTION 'Current payroll reviewer authority and membership required';
    END IF;

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
          AND older.generation = (
              SELECT MAX(previous.generation)
              FROM payroll_run_version previous
              JOIN payroll_run_release previous_release
                ON previous_release.run_version_id = previous.id
              WHERE previous.payroll_period_id = older.payroll_period_id
                AND previous.generation < newer.generation
          )
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

-- A direct SQL writer with Payroll REVIEW access cannot skip the company's
-- authoritative Finance correction clearance by inserting a second release.
-- The application records the correction FIRST under the payroll period lock
-- in the same transaction, so this stays compatible with routine operations.
CREATE FUNCTION payroll_9c_release_requires_correction() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    current_period uuid;
    current_generation integer;
    previous_released_run uuid;
BEGIN
    SELECT rv.payroll_period_id, rv.generation
      INTO current_period, current_generation
    FROM payroll_run_version rv
    WHERE rv.id = NEW.run_version_id
      AND rv.organization_id = NEW.organization_id;

    SELECT prior.run_version_id
      INTO previous_released_run
    FROM payroll_run_release prior
    JOIN payroll_run_version older ON older.id = prior.run_version_id
    WHERE older.payroll_period_id = current_period
      AND older.organization_id = NEW.organization_id
      AND older.generation < current_generation
    ORDER BY older.generation DESC
    LIMIT 1;

    IF previous_released_run IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM payroll_run_correction c
        WHERE c.organization_id = NEW.organization_id
          AND c.replacement_run_version_id = NEW.run_version_id
          AND c.original_run_version_id = previous_released_run
          AND c.replacement_source_fingerprint = NEW.run_source_fingerprint
          AND c.recorded_by_user_id = NEW.released_by_user_id
    ) THEN
        RAISE EXCEPTION 'Payroll replacement release requires verified correction';
    END IF;

    RETURN NEW;
END;
$$;

CREATE TRIGGER payroll_9c_release_correction_validate
    BEFORE INSERT ON payroll_run_release FOR EACH ROW
    EXECUTE FUNCTION payroll_9c_release_requires_correction();
