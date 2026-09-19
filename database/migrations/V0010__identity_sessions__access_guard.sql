-- Phase 1 identity session and access-revocation foundation.
-- Provider tokens remain outside business persistence; only a SHA-256
-- provider-session reference is retained for product-side revocation binding.

CREATE TABLE identity_session (
    id uuid PRIMARY KEY,
    user_identity_id uuid NOT NULL
        REFERENCES user_identity(id) ON DELETE RESTRICT,
    device_id uuid NOT NULL
        REFERENCES device(id) ON DELETE RESTRICT,
    provider_session_ref_hash char(64) NOT NULL,
    created_at timestamptz NOT NULL,
    last_seen_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz NULL,
    authentication_strength varchar(64) NOT NULL,
    reauth_satisfied_until timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (
        user_identity_id,
        device_id,
        provider_session_ref_hash
    ),
    CHECK (expires_at > created_at),
    CHECK (last_seen_at >= created_at),
    CHECK (revoked_at IS NULL OR revoked_at >= created_at),
    CHECK (
        reauth_satisfied_until IS NULL
        OR reauth_satisfied_until >= created_at
    )
);

CREATE INDEX idx_identity_session_user_state
    ON identity_session (
        user_identity_id,
        revoked_at,
        expires_at DESC
    );

CREATE INDEX idx_identity_session_device_state
    ON identity_session (
        device_id,
        revoked_at,
        expires_at DESC
    );

CREATE INDEX idx_identity_session_provider_hash
    ON identity_session (provider_session_ref_hash);
