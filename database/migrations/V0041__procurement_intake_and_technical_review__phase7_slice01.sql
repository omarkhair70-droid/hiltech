-- Phase 7 S01: immutable request intake/technical review events.
-- An approved technical need is NOT procurement authorization or a PO.
-- The V0039 original evidence and V0040 withdrawal remain immutable.
ALTER TABLE procurement_requirement_transition
    DROP CONSTRAINT IF EXISTS procurement_requirement_transition_transition_type_check,
    DROP CONSTRAINT IF EXISTS procurement_requirement_transition_from_state_check,
    DROP CONSTRAINT IF EXISTS procurement_requirement_transition_to_state_check,
    DROP CONSTRAINT IF EXISTS uq_procurement_initial_withdrawal,
    DROP CONSTRAINT IF EXISTS ck_procurement_withdrawal_first;

ALTER TABLE procurement_requirement_transition
    ADD COLUMN spec_quantity numeric(18,6),
    ADD COLUMN spec_justification varchar(500),
    ADD COLUMN spec_work_version bigint,
    ADD COLUMN spec_item_version bigint,
    ADD COLUMN spec_unit_code varchar(32),
    ADD COLUMN spec_assessment_code varchar(40),
    ADD COLUMN spec_visible_free_quantity numeric(18,6),
    ADD COLUMN spec_assessed_at timestamptz,
    ADD CONSTRAINT ck_procurement_intake_transition CHECK (
        (transition_type = 'SUBMIT' AND from_state = 'DRAFT' AND to_state = 'SUBMITTED')
        OR (transition_type = 'WITHDRAW' AND from_state IN ('DRAFT','SUBMITTED','NEEDS_INFO','READY_FOR_SOURCING')
            AND to_state = 'WITHDRAWN')
        OR (transition_type = 'REVISE' AND from_state IN ('DRAFT','NEEDS_INFO')
            AND to_state = 'DRAFT')
        OR (transition_type = 'RETURN_FOR_INFO' AND from_state IN ('SUBMITTED','READY_FOR_SOURCING')
            AND to_state = 'NEEDS_INFO')
        OR (transition_type = 'TECHNICAL_READY' AND from_state = 'SUBMITTED'
            AND to_state = 'READY_FOR_SOURCING')
        OR (transition_type = 'REJECT' AND from_state IN ('SUBMITTED','READY_FOR_SOURCING')
            AND to_state = 'REJECTED')
    ),
    ADD CONSTRAINT ck_procurement_review_reason CHECK (
        transition_type NOT IN ('RETURN_FOR_INFO','REJECT')
        OR (reason IS NOT NULL AND length(btrim(reason)) > 0)
    ),
    ADD CONSTRAINT ck_procurement_revision_snapshot CHECK (
        (
            spec_quantity IS NULL AND spec_justification IS NULL
            AND spec_work_version IS NULL AND spec_item_version IS NULL
            AND spec_unit_code IS NULL AND spec_assessment_code IS NULL
            AND spec_visible_free_quantity IS NULL AND spec_assessed_at IS NULL
            AND transition_type <> 'REVISE'
        )
        OR (
            spec_quantity IS NOT NULL AND spec_justification IS NOT NULL
            AND spec_work_version IS NOT NULL AND spec_item_version IS NOT NULL
            AND spec_unit_code IS NOT NULL AND spec_assessment_code IS NOT NULL
            AND spec_quantity > 0 AND length(btrim(spec_justification)) > 0
            AND spec_work_version >= 1 AND spec_item_version >= 1
            AND length(btrim(spec_unit_code)) > 0 AND spec_assessed_at IS NOT NULL
            AND spec_assessment_code IN (
                'WAREHOUSE_REVIEW_REQUIRED', 'VISIBLE_STOCK_AVAILABLE',
                'VISIBLE_STOCK_SHORTAGE'
            )
            AND (
                (spec_assessment_code = 'WAREHOUSE_REVIEW_REQUIRED'
                    AND spec_visible_free_quantity IS NULL)
                OR (spec_assessment_code <> 'WAREHOUSE_REVIEW_REQUIRED'
                    AND spec_visible_free_quantity IS NOT NULL
                    AND spec_visible_free_quantity >= 0)
            )
        )
    );
-- Preserve the full effective specification on subsequent transitions when
-- a draft has been revised. Old transitions remain valid with all spec_* NULL.
CREATE INDEX idx_procurement_intake_by_root
    ON procurement_requirement_transition (organization_id, requirement_id, result_version DESC);
