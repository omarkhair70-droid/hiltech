-- Phase 2 / Slice 06 — provider-neutral Notification Abstraction foundation.

CREATE TABLE notification_intent (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL
        REFERENCES organization(id) ON DELETE RESTRICT,
    producer_key varchar(220) NOT NULL UNIQUE
        CHECK (length(trim(producer_key)) BETWEEN 3 AND 220),
    source_event_id uuid NOT NULL UNIQUE,
    source_type varchar(64) NOT NULL
        CHECK (source_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    source_id uuid NOT NULL,
    source_version bigint NOT NULL
        CHECK (source_version >= 1),
    recipient_user_identity_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    notification_class varchar(32) NOT NULL
        CHECK (notification_class = 'ACTION_REQUIRED'),
    template_code varchar(80) NOT NULL
        CHECK (template_code ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    safe_preview varchar(240) NULL,
    deep_link_type varchar(64) NOT NULL
        CHECK (deep_link_type ~ '^[A-Z][A-Z0-9_]{2,63}$'),
    deep_link_id uuid NOT NULL,
    policy_state varchar(16) NOT NULL
        CHECK (policy_state IN ('PENDING', 'SUPPRESSED', 'DISPATCHED')),
    suppression_reason varchar(80) NULL
        CHECK (
            suppression_reason IS NULL
            OR suppression_reason ~ '^[A-Z][A-Z0-9_]{2,79}$'
        ),
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    dispatched_at timestamptz NULL,
    correlation_id varchar(128) NULL,
    version bigint NOT NULL DEFAULT 1
        CHECK (version >= 1),
    CHECK (updated_at >= created_at),
    CHECK (
        (
            policy_state = 'PENDING'
            AND suppression_reason IS NULL
            AND dispatched_at IS NULL
        )
        OR
        (
            policy_state = 'SUPPRESSED'
            AND suppression_reason IS NOT NULL
            AND dispatched_at IS NULL
        )
        OR
        (
            policy_state = 'DISPATCHED'
            AND suppression_reason IS NULL
            AND dispatched_at IS NOT NULL
        )
    )
);

CREATE INDEX idx_notification_intent_recipient_state
    ON notification_intent (
        recipient_user_identity_id,
        policy_state,
        created_at DESC,
        id DESC
    );

CREATE INDEX idx_notification_intent_source
    ON notification_intent (
        source_type,
        source_id,
        source_version
    );

CREATE TABLE notification_delivery_attempt (
    id uuid PRIMARY KEY,
    notification_intent_id uuid NOT NULL
        REFERENCES notification_intent(id) ON DELETE RESTRICT,
    channel varchar(16) NOT NULL
        CHECK (channel IN ('PUSH', 'EMAIL', 'SMS', 'DESKTOP')),
    provider_key varchar(80) NOT NULL
        CHECK (provider_key ~ '^[A-Z][A-Z0-9_]{2,79}$'),
    attempt_number integer NOT NULL
        CHECK (attempt_number >= 1),
    attempted_at timestamptz NOT NULL,
    outcome varchar(24) NOT NULL
        CHECK (
            outcome IN (
                'ACCEPTED',
                'FAILED_RETRYABLE',
                'FAILED_FINAL'
            )
        ),
    provider_message_id varchar(220) NULL,
    failure_code varchar(80) NULL
        CHECK (
            failure_code IS NULL
            OR failure_code ~ '^[A-Z][A-Z0-9_]{2,79}$'
        ),
    delivered_at timestamptz NULL,
    correlation_id varchar(128) NULL,
    UNIQUE (
        notification_intent_id,
        channel,
        provider_key,
        attempt_number
    ),
    CHECK (
        (
            outcome = 'ACCEPTED'
            AND failure_code IS NULL
        )
        OR
        (
            outcome <> 'ACCEPTED'
            AND failure_code IS NOT NULL
        )
    ),
    CHECK (
        delivered_at IS NULL
        OR delivered_at >= attempted_at
    )
);

CREATE INDEX idx_notification_attempt_intent
    ON notification_delivery_attempt (
        notification_intent_id,
        attempted_at DESC,
        id DESC
    );
