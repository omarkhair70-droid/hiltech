-- HILTECH OS platform foundations.

CREATE TABLE code_sequence (
    scope_key varchar(255) NOT NULL,
    target_object_type varchar(64) NOT NULL,
    policy_code varchar(64) NOT NULL,
    sequence_period varchar(32) NOT NULL,
    next_value bigint NOT NULL CHECK (next_value >= 1),
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    updated_at timestamptz NOT NULL,
    PRIMARY KEY (scope_key, target_object_type, policy_code, sequence_period)
);

CREATE TABLE idempotent_operation (
    operation_id uuid PRIMARY KEY,
    actor_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    command_type varchar(120) NOT NULL,
    target_type varchar(80) NULL,
    target_id uuid NULL,
    request_fingerprint varchar(128) NULL,
    state varchar(16) NOT NULL CHECK (state IN ('STARTED','APPLIED','FAILED')),
    result_code varchar(120) NULL,
    result_payload jsonb NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz NULL,
    correlation_id varchar(128) NOT NULL,
    CHECK (completed_at IS NULL OR completed_at >= created_at)
);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    actor_user_id uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    action varchar(160) NOT NULL,
    target_type varchar(80) NOT NULL,
    target_id uuid NULL,
    previous_state_ref varchar(255) NULL,
    new_state_ref varchar(255) NULL,
    safe_diff jsonb NULL,
    occurred_at timestamptz NOT NULL,
    correlation_id varchar(128) NOT NULL,
    trace_id varchar(64) NULL,
    reason text NULL,
    delegation_id uuid NULL,
    config_revision_refs jsonb NULL
);
