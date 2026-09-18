-- HILTECH OS evidence metadata; binary bytes remain in private object storage.

CREATE TABLE evidence (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    target_type varchar(64) NOT NULL,
    target_id uuid NOT NULL,
    work_order_id uuid NULL REFERENCES work_order(id) ON DELETE RESTRICT,
    evidence_requirement_key varchar(64) NULL,
    evidence_policy_id uuid NULL REFERENCES config_revision(id) ON DELETE RESTRICT,
    evidence_policy_revision integer NULL,
    evidence_type_code varchar(64) NOT NULL,
    content_type varchar(160) NOT NULL,
    original_file_name varchar(255) NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 16777216),
    sha256 char(64) NOT NULL CHECK (char_length(sha256) = 64),
    captured_at timestamptz NOT NULL,
    client_occurred_at timestamptz NULL,
    captured_by_user_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    source_device_id uuid NULL REFERENCES device(id) ON DELETE RESTRICT,
    instruction_revision bigint NULL,
    work_order_version_at_capture bigint NULL,
    storage_state varchar(32) NOT NULL CHECK (storage_state IN ('RESERVED','UPLOADED_UNVERIFIED','QUARANTINED','READY','REJECTED')),
    object_key_ref varchar(512) NULL,
    finalized_at timestamptz NULL,
    classification_code varchar(64) NOT NULL,
    client_visibility_mode varchar(64) NOT NULL,
    supersedes_evidence_id uuid NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (supersedes_evidence_id IS NULL OR supersedes_evidence_id <> id)
);

CREATE TABLE evidence_upload_session (
    id uuid PRIMARY KEY,
    evidence_id uuid NOT NULL REFERENCES evidence(id) ON DELETE RESTRICT,
    expected_sha256 char(64) NOT NULL CHECK (char_length(expected_sha256) = 64),
    expected_size_bytes bigint NOT NULL CHECK (expected_size_bytes > 0 AND expected_size_bytes <= 16777216),
    expires_at timestamptz NOT NULL,
    state varchar(32) NOT NULL CHECK (state IN ('RESERVED','UPLOADED_UNVERIFIED','QUARANTINED','READY','REJECTED')),
    created_at timestamptz NOT NULL,
    finalized_at timestamptz NULL,
    operation_id uuid NOT NULL UNIQUE,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (expires_at > created_at)
);
