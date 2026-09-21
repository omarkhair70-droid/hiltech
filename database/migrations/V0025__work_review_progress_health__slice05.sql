-- Phase 4 / Slice 05 — Review / Rework / Accepted Progress / Project Health.
-- Forward-only normalization. Existing review/progress/health tables remain canonical.

ALTER TABLE review_policy
    ADD COLUMN mode varchar(24) NOT NULL DEFAULT 'ANY_ONE',
    ADD COLUMN bind_exact_submitted_version boolean NOT NULL DEFAULT true,
    ADD COLUMN client_acceptance_separate boolean NOT NULL DEFAULT false,
    ADD COLUMN allow_delegation boolean NOT NULL DEFAULT false,
    ADD CONSTRAINT ck_review_policy_mode
        CHECK (mode IN ('ANY_ONE','ALL','SEQUENTIAL','QUORUM')),
    ADD CONSTRAINT ck_review_policy_exact_submission
        CHECK (
            exact_submitted_version_required = true
            AND bind_exact_submitted_version = true
        );

ALTER TABLE review_policy_step
    ADD COLUMN sequence integer NULL,
    ADD COLUMN selector_type varchar(32) NULL,
    ADD COLUMN selector_value varchar(160) NULL,
    ADD COLUMN quorum_count integer NULL,
    ADD COLUMN reauth_required boolean NOT NULL DEFAULT false,
    ADD COLUMN reason_required_on_rework boolean NOT NULL DEFAULT true,
    ADD COLUMN reason_required_on_reject boolean NOT NULL DEFAULT true,
    ADD COLUMN evidence_visibility_mode varchar(32) NOT NULL DEFAULT 'POLICY',
    ADD COLUMN escalation_policy_ref uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT;

UPDATE review_policy_step
SET sequence = sort_order,
    selector_type = 'RELATIONSHIP',
    selector_value = reviewer_relationship_code
WHERE sequence IS NULL
   OR selector_type IS NULL;

ALTER TABLE review_policy_step
    ALTER COLUMN sequence SET NOT NULL,
    ALTER COLUMN selector_type SET NOT NULL,
    ALTER COLUMN reviewer_relationship_code DROP NOT NULL,
    ADD CONSTRAINT ck_review_step_sequence
        CHECK (sequence >= 0),
    ADD CONSTRAINT ck_review_step_selector_type
        CHECK (
            selector_type IN (
                'RELATIONSHIP',
                'ROLE',
                'TEAM',
                'SPECIFIC_USER',
                'SUBJECT_MANAGER_CHAIN',
                'CLIENT_RELATIONSHIP'
            )
        ),
    ADD CONSTRAINT ck_review_step_quorum
        CHECK (quorum_count IS NULL OR quorum_count > 0),
    ADD CONSTRAINT ck_review_step_visibility
        CHECK (
            evidence_visibility_mode IN (
                'POLICY',
                'INTERNAL',
                'CLIENT_VISIBLE',
                'RESTRICTED'
            )
        );

CREATE INDEX idx_review_policy_step_execution
    ON review_policy_step (
        config_revision_id,
        sequence,
        step_key
    );

ALTER TABLE work_review_decision
    ADD COLUMN operation_id uuid NULL,
    ADD COLUMN reviewer_source_type varchar(32) NULL,
    ADD COLUMN reviewer_source_ref varchar(255) NULL,
    ADD COLUMN decision_sequence integer NULL,
    ADD CONSTRAINT ck_work_review_decision_sequence
        CHECK (
            decision_sequence IS NULL
            OR decision_sequence >= 0
        );

CREATE UNIQUE INDEX uq_work_review_decision_operation
    ON work_review_decision (operation_id)
    WHERE operation_id IS NOT NULL;

CREATE INDEX idx_work_review_decision_work_version
    ON work_review_decision (
        work_order_id,
        submitted_work_version,
        decided_at,
        id
    );

ALTER TABLE work_order
    ADD COLUMN submitted_review_version bigint NULL,
    ADD COLUMN accepted_review_decision_id uuid NULL
        REFERENCES work_review_decision(id) ON DELETE RESTRICT,
    ADD CONSTRAINT ck_work_order_submitted_review_version
        CHECK (
            submitted_review_version IS NULL
            OR submitted_review_version >= 1
        );

CREATE INDEX idx_work_order_review_queue
    ON work_order (
        project_id,
        lifecycle_state,
        baseline_version,
        submitted_at
    )
    WHERE lifecycle_state IN (
        'SUBMITTED_FOR_REVIEW',
        'REWORK_REQUIRED',
        'ACCEPTED'
    );

ALTER TABLE project_progress_projection
    ADD COLUMN project_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN included_work_count integer NOT NULL DEFAULT 0,
    ADD COLUMN accepted_work_count integer NOT NULL DEFAULT 0,
    ADD CONSTRAINT ck_project_progress_project_version
        CHECK (project_version >= 1),
    ADD CONSTRAINT ck_project_progress_counts
        CHECK (
            included_work_count >= 0
            AND accepted_work_count >= 0
            AND accepted_work_count <= included_work_count
        );

UPDATE project_progress_projection ppp
SET project_version = p.version
FROM project p
WHERE p.id = ppp.project_id;

ALTER TABLE project_health_projection
    ADD COLUMN project_version bigint NOT NULL DEFAULT 1,
    ADD COLUMN health_policy_id uuid NULL
        REFERENCES config_revision(id) ON DELETE RESTRICT,
    ADD COLUMN health_policy_revision integer NULL,
    ADD CONSTRAINT ck_project_health_project_version
        CHECK (project_version >= 1),
    ADD CONSTRAINT ck_project_health_policy_revision
        CHECK (
            health_policy_revision IS NULL
            OR health_policy_revision > 0
        );

UPDATE project_health_projection php
SET project_version = p.version
FROM project p
WHERE p.id = php.project_id;

CREATE TABLE project_health_signal (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    baseline_version integer NOT NULL CHECK (baseline_version >= 1),
    signal_code varchar(40) NOT NULL
        CHECK (
            signal_code IN (
                'OVERDUE_WORK',
                'BLOCKED_WORK',
                'REWORK_BACKLOG',
                'READINESS_FAILURE',
                'MILESTONE_DELAY',
                'CLIENT_ACTION_REQUIRED'
            )
        ),
    severity varchar(16) NOT NULL
        CHECK (severity IN ('ATTENTION','CRITICAL')),
    source_type varchar(48) NOT NULL,
    source_id uuid NOT NULL,
    first_observed_at timestamptz NOT NULL,
    last_observed_at timestamptz NOT NULL,
    summary text NOT NULL,
    current boolean NOT NULL DEFAULT true,
    source_version bigint NULL,
    CHECK (last_observed_at >= first_observed_at)
);

CREATE UNIQUE INDEX uq_project_health_signal_current
    ON project_health_signal (
        project_id,
        signal_code,
        source_type,
        source_id
    )
    WHERE current = true;

CREATE INDEX idx_project_health_signal_project
    ON project_health_signal (
        project_id,
        current,
        severity,
        signal_code,
        last_observed_at DESC
    );

CREATE TABLE project_hold_record (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES project(id) ON DELETE RESTRICT,
    organization_id uuid NOT NULL,
    put_on_hold_operation_id uuid NOT NULL UNIQUE,
    put_on_hold_project_version bigint NOT NULL CHECK (put_on_hold_project_version >= 1),
    reason text NOT NULL CHECK (length(trim(reason)) BETWEEN 1 AND 4000),
    state varchar(16) NOT NULL CHECK (state IN ('OPEN','RESOLVED','WAIVED')),
    put_on_hold_at timestamptz NOT NULL,
    put_on_hold_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    resolution text NULL,
    resume_operation_id uuid NULL UNIQUE,
    resolved_at timestamptz NULL,
    resolved_by uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CONSTRAINT fk_project_hold_project_org
        FOREIGN KEY (project_id, organization_id)
        REFERENCES project(id, organization_id) ON DELETE RESTRICT,
    CHECK (
        state = 'OPEN'
        OR (
            resolved_at IS NOT NULL
            AND resolved_by IS NOT NULL
            AND resolution IS NOT NULL
        )
    )
);

CREATE UNIQUE INDEX uq_project_hold_open
    ON project_hold_record (project_id)
    WHERE state = 'OPEN';

CREATE INDEX idx_project_hold_history
    ON project_hold_record (
        project_id,
        put_on_hold_at DESC,
        id
    );

CREATE INDEX idx_project_progress_rebuild_work
    ON work_order (
        project_id,
        baseline_version,
        counts_toward_project_progress,
        lifecycle_state,
        progress_weight
    );

CREATE INDEX idx_work_review_evidence_ready
    ON evidence (
        work_order_id,
        evidence_policy_id,
        evidence_policy_revision,
        evidence_requirement_key,
        storage_state
    )
    WHERE storage_state = 'READY';
