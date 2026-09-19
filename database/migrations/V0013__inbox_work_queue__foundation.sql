-- Phase 2 / Slice 05 — Inbox / Work Queue foundation.

CREATE TABLE inbox_item (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    producer_key varchar(220) NOT NULL UNIQUE
        CHECK (length(trim(producer_key)) BETWEEN 3 AND 220),
    source_type varchar(64) NOT NULL
        CHECK (source_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    source_id uuid NOT NULL,
    source_version bigint NOT NULL
        CHECK (source_version >= 1),
    action_key varchar(80) NOT NULL
        CHECK (action_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    attention_class varchar(32) NOT NULL
        CHECK (attention_class = 'ACTION_REQUIRED'),
    target_principal_type varchar(16) NOT NULL
        CHECK (target_principal_type IN ('USER', 'TEAM')),
    target_user_id uuid NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    target_team_id uuid NULL
        REFERENCES team(id) ON DELETE RESTRICT,
    state varchar(16) NOT NULL
        CHECK (state IN ('OPEN', 'RESOLVED')),
    safe_title_code varchar(80) NOT NULL
        CHECK (safe_title_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    safe_summary varchar(500) NULL,
    source_event_id uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    resolved_at timestamptz NULL,
    correlation_id varchar(128) NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (
        (
            target_principal_type = 'USER'
            AND target_user_id IS NOT NULL
            AND target_team_id IS NULL
        )
        OR
        (
            target_principal_type = 'TEAM'
            AND target_team_id IS NOT NULL
            AND target_user_id IS NULL
        )
    ),
    CHECK (
        (
            state = 'OPEN'
            AND resolved_at IS NULL
        )
        OR
        (
            state = 'RESOLVED'
            AND resolved_at IS NOT NULL
        )
    ),
    CHECK (updated_at >= created_at)
);

CREATE INDEX idx_inbox_item_user_open
    ON inbox_item (
        target_user_id,
        state,
        created_at ASC,
        id ASC
    )
    WHERE target_user_id IS NOT NULL;

CREATE INDEX idx_inbox_item_team_open
    ON inbox_item (
        target_team_id,
        state,
        created_at ASC,
        id ASC
    )
    WHERE target_team_id IS NOT NULL;

CREATE INDEX idx_inbox_item_source
    ON inbox_item (
        source_type,
        source_id,
        state
    );

CREATE INDEX idx_inbox_item_updated
    ON inbox_item (
        updated_at DESC,
        id DESC
    );

CREATE TABLE inbox_user_state (
    inbox_item_id uuid NOT NULL
        REFERENCES inbox_item(id) ON DELETE CASCADE,
    user_identity_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    read_at timestamptz NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    PRIMARY KEY (
        inbox_item_id,
        user_identity_id
    )
);

CREATE INDEX idx_inbox_user_state_user
    ON inbox_user_state (
        user_identity_id,
        updated_at DESC,
        inbox_item_id
    );
