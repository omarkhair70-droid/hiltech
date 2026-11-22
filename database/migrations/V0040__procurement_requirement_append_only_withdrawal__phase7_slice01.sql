-- Phase 7 / Slice 01: withdrawal is an append-only transition, not a rewrite
-- of the original V0039 source/stock snapshot. Later transitions get explicit
-- authority and state-machine migrations without mutating purchase history.
ALTER TABLE procurement_purchase_requirement
    ADD CONSTRAINT uq_procurement_requirement_org UNIQUE (id, organization_id);

CREATE TABLE procurement_requirement_transition (
    id uuid PRIMARY KEY,
    operation_id uuid NOT NULL UNIQUE,
    requirement_id uuid NOT NULL,
    organization_id uuid NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 1),
    result_version bigint NOT NULL CHECK (result_version = source_version + 1),
    transition_type varchar(32) NOT NULL CHECK (transition_type IN ('WITHDRAW')),
    from_state varchar(24) NOT NULL CHECK (from_state = 'DRAFT'),
    to_state varchar(24) NOT NULL CHECK (to_state = 'WITHDRAWN'),
    reason varchar(500),
    actor_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL CHECK (length(btrim(correlation_id)) > 0),
    CONSTRAINT fk_procurement_transition_requirement
        FOREIGN KEY (requirement_id, organization_id)
        REFERENCES procurement_purchase_requirement (id, organization_id) ON DELETE RESTRICT,
    CONSTRAINT uq_procurement_transition_version UNIQUE (requirement_id, result_version),
    CONSTRAINT uq_procurement_initial_withdrawal UNIQUE (requirement_id, transition_type),
    CONSTRAINT ck_procurement_withdrawal_first
        CHECK (source_version = 1 AND result_version = 2)
);

CREATE INDEX idx_procurement_requirement_transition_history
    ON procurement_requirement_transition (requirement_id, result_version DESC);

-- Trigger prevents history rewriting. Existing DRAFT row remains immutable.
CREATE TRIGGER procurement_requirement_transition_immutable
BEFORE UPDATE OR DELETE ON procurement_requirement_transition
FOR EACH ROW EXECUTE FUNCTION reject_procurement_draft_mutation();
