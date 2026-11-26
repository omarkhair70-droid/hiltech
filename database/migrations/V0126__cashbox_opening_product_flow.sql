-- Human cash custody choices and transactional internal numbering. Existing
-- company codes, zero balances and independently approved openings are preserved.
CREATE TABLE finance_cashbox_number_counter (
    organization_id uuid PRIMARY KEY REFERENCES organization(id) ON DELETE RESTRICT,
    last_issued bigint NOT NULL CHECK (last_issued > 0)
);

ALTER TABLE finance_cashbox_opening_proposal
    ADD COLUMN code_source varchar(16) NOT NULL DEFAULT 'MANUAL',
    ADD COLUMN code_sequence bigint NULL,
    ADD COLUMN custodian_source_version bigint NULL,
    ADD COLUMN review_source_version bigint NULL,
    ADD COLUMN custodian_display_name varchar(240) NULL,
    ADD COLUMN custodian_employee_code varchar(80) NULL,
    ADD CONSTRAINT cashbox_opening_code_source_valid CHECK (
        (code_source = 'MANUAL' AND code_sequence IS NULL)
        OR (code_source = 'AUTOMATIC' AND code_sequence IS NOT NULL AND code_sequence > 0
            AND custodian_source_version IS NOT NULL AND custodian_source_version > 0
            AND review_source_version IS NOT NULL AND review_source_version > 0
            AND custodian_display_name IS NOT NULL AND length(btrim(custodian_display_name)) > 0
            AND custodian_employee_code IS NOT NULL AND length(btrim(custodian_employee_code)) > 0)
    );
CREATE UNIQUE INDEX cashbox_opening_automatic_sequence_unique
    ON finance_cashbox_opening_proposal(organization_id, code_sequence)
    WHERE code_source = 'AUTOMATIC';

-- The caller holds the organization lock and runs this in its idempotent
-- transaction. Rollback does not consume a number. Legacy manual codes reserve
-- their exact values without being renamed or reinterpreted as issued numbers.
CREATE FUNCTION finance_next_cashbox_number(company uuid) RETURNS bigint LANGUAGE plpgsql AS $$
DECLARE number bigint; candidate varchar(64);
BEGIN
    PERFORM id FROM organization WHERE id=company AND organization_type='HILTECH' AND status='ACTIVE' FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Current company is required' USING ERRCODE='23514'; END IF;
    LOOP
        INSERT INTO finance_cashbox_number_counter(organization_id,last_issued) VALUES (company,1)
        ON CONFLICT (organization_id) DO UPDATE SET last_issued=finance_cashbox_number_counter.last_issued+1
        RETURNING last_issued INTO number;
        candidate:='CBX-' || lpad(number::text,greatest(6,length(number::text)),'0');
        IF NOT EXISTS(SELECT 1 FROM finance_cashbox_opening_proposal p
            WHERE p.organization_id=company AND p.cashbox_code=candidate)
           AND NOT EXISTS(SELECT 1 FROM finance_cashbox_register b
            WHERE b.organization_id=company AND b.cashbox_code=candidate)
        THEN RETURN number; END IF;
    END LOOP;
END; $$;

CREATE FUNCTION finance_cashbox_automatic_code_guard() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE current_number bigint;
BEGIN
    IF NEW.code_source='AUTOMATIC' THEN
        SELECT last_issued INTO current_number FROM finance_cashbox_number_counter
        WHERE organization_id=NEW.organization_id FOR UPDATE;
        IF current_number IS NULL OR current_number<>NEW.code_sequence
           OR NEW.cashbox_code<>'CBX-' || lpad(NEW.code_sequence::text,greatest(6,length(NEW.code_sequence::text)),'0')
        THEN RAISE EXCEPTION 'Automatic cashbox code must use its transactional company number' USING ERRCODE='23514'; END IF;
    END IF;
    RETURN NEW;
END; $$;
CREATE TRIGGER finance_cashbox_automatic_code_guard BEFORE INSERT ON finance_cashbox_opening_proposal
FOR EACH ROW EXECUTE FUNCTION finance_cashbox_automatic_code_guard();

CREATE INDEX cashbox_opening_preparer_history
    ON finance_cashbox_opening_proposal(organization_id,prepared_by_user_id,created_at DESC,id);

-- A zero-opening request may be prepared by the named custodian. This command
-- creates no cash. A different reviewer must approve; vouchers keep their own
-- source/evidence and separation rules. Preserve all historical opening rows.
ALTER TABLE finance_cashbox_opening_proposal
    DROP CONSTRAINT finance_cashbox_opening_proposal_check;
ALTER TABLE finance_cashbox_register
    DROP CONSTRAINT finance_cashbox_register_check;

CREATE OR REPLACE FUNCTION finance_cashbox_creation_guard()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a approval_request%ROWTYPE;
        p finance_cashbox_opening_proposal%ROWTYPE;
BEGIN
 SELECT * INTO p FROM finance_cashbox_opening_proposal
 WHERE id=NEW.opening_proposal_id AND organization_id=NEW.organization_id;
 IF p.id IS NULL OR p.id<>NEW.id
    OR p.cashbox_code<>NEW.cashbox_code
    OR p.currency<>NEW.currency
    OR p.custodian_user_id<>NEW.custodian_user_id
    OR p.approved_company_reference<>NEW.approved_company_reference
    OR p.opening_balance<>NEW.opening_balance
    OR p.prepared_by_user_id<>NEW.created_by_user_id THEN
  RAISE EXCEPTION 'Cashbox must equal approved immutable zero-opening proposal'
   USING ERRCODE='23514';
 END IF;
 SELECT * INTO a FROM approval_request
 WHERE id=NEW.opening_approval_request_id AND organization_id=NEW.organization_id;
 IF a.id IS NULL OR a.state<>'APPROVED'
    OR a.subject_type<>'FINANCE_CASHBOX_OPENING'
    OR a.subject_id<>p.id OR a.subject_version<>p.subject_version
    OR a.policy_key<>p.policy_key
    OR a.requester_user_id<>p.prepared_by_user_id
    OR a.requester_user_id<>NEW.created_by_user_id
    OR a.completed_at IS NULL OR a.completed_at>NEW.created_at
    OR NOT EXISTS(
      SELECT 1 FROM approval_decision d
      WHERE d.approval_request_id=a.id AND d.decision='APPROVE'
        AND d.subject_version=p.subject_version
        AND d.actor_user_id<>NEW.created_by_user_id
        AND d.actor_user_id<>NEW.custodian_user_id
    )
    OR NOT EXISTS(
      SELECT 1 FROM approval_policy_version v
      WHERE v.organization_id=NEW.organization_id
        AND v.policy_key=a.policy_key AND v.version_number=a.policy_version
        AND v.lifecycle_state='ACTIVE' AND v.approval_required=true
        AND v.step_mode='SINGLE' AND v.effective_from<=NEW.created_at
        AND (v.effective_to IS NULL OR v.effective_to>NEW.created_at)
    ) THEN
  RAISE EXCEPTION 'Cashbox opening needs approved company custody with zero money'
    USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END; $$;
