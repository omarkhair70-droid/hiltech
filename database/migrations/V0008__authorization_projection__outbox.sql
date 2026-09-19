-- HILTECH OS PostgreSQL source relationship -> OpenFGA projection boundary.

CREATE TABLE authorization_relation_projection (
    relation_key varchar(512) PRIMARY KEY,
    subject_type varchar(64) NOT NULL,
    subject_id varchar(255) NOT NULL,
    relation varchar(128) NOT NULL,
    object_type varchar(64) NOT NULL,
    object_id varchar(255) NOT NULL,
    desired_state varchar(16) NOT NULL CHECK (desired_state IN ('PRESENT','ABSENT')),
    source_type varchar(64) NOT NULL,
    source_id varchar(255) NOT NULL,
    source_version bigint NOT NULL CHECK (source_version >= 1),
    projection_state varchar(16) NOT NULL CHECK (projection_state IN ('PENDING','APPLYING','APPLIED','FAILED')),
    authorization_model_id varchar(255) NOT NULL,
    last_attempt_at timestamptz NULL,
    applied_at timestamptz NULL,
    last_error_code varchar(120) NULL,
    retry_count integer NOT NULL DEFAULT 0 CHECK (retry_count >= 0),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE authorization_projection_outbox (
    id uuid PRIMARY KEY,
    relation_key varchar(512) NOT NULL REFERENCES authorization_relation_projection(relation_key) ON DELETE RESTRICT,
    source_version bigint NOT NULL CHECK (source_version >= 1),
    event_type varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    claimed_at timestamptz NULL,
    completed_at timestamptz NULL,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error_code varchar(120) NULL
);
