-- Phase 2 / Slice 03 — durable Activity projection and Spring Modulith event publication registry.

CREATE TABLE event_publication (
    id uuid PRIMARY KEY,
    listener_id text NOT NULL,
    event_type text NOT NULL,
    serialized_event text NOT NULL,
    publication_date timestamptz NOT NULL,
    completion_date timestamptz NULL,
    status text NULL,
    completion_attempts integer NULL,
    last_resubmission_date timestamptz NULL
);

CREATE INDEX event_publication_serialized_event_hash_idx
    ON event_publication USING hash(serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON event_publication (completion_date);

CREATE TABLE activity_event (
    id uuid PRIMARY KEY,
    source_event_id uuid NOT NULL UNIQUE,
    activity_type varchar(80) NOT NULL CHECK (
        activity_type IN (
            'EVIDENCE_READY',
            'EVIDENCE_QUARANTINED',
            'EVIDENCE_REJECTED'
        )
    ),
    context_type varchar(40) NOT NULL CHECK (
        context_type = 'WORK_ORDER'
    ),
    context_id uuid NOT NULL
        REFERENCES work_order(id) ON DELETE RESTRICT,
    source_type varchar(40) NOT NULL CHECK (
        source_type = 'EVIDENCE'
    ),
    source_id uuid NOT NULL
        REFERENCES evidence(id) ON DELETE RESTRICT,
    source_version bigint NOT NULL CHECK (
        source_version >= 1
    ),
    actor_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL,
    safe_summary jsonb NOT NULL,
    correlation_id varchar(128) NULL,
    classification_code varchar(64) NOT NULL,
    schema_version integer NOT NULL DEFAULT 1
        CHECK (schema_version >= 1),
    CHECK (
        jsonb_typeof(safe_summary) = 'object'
    )
);

CREATE INDEX idx_activity_context_time
    ON activity_event (
        context_type,
        context_id,
        occurred_at DESC,
        id DESC
    );
CREATE INDEX idx_activity_source
    ON activity_event (
        source_type,
        source_id,
        source_version
    );
CREATE INDEX idx_activity_recorded_at
    ON activity_event (recorded_at);
