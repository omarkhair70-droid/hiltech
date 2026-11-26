CREATE TABLE commercial_mail_oauth_pending (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    actor_id uuid NOT NULL REFERENCES user_identity(id),
    provider varchar(16) NOT NULL CHECK(provider IN ('GOOGLE','MICROSOFT')),
    state_hash char(64) NOT NULL UNIQUE,
    encrypted_verifier bytea NOT NULL,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','EXCHANGING','READY','FAILED')),
    account_email varchar(254),
    encrypted_refresh bytea,
    base_version bigint NOT NULL
);
CREATE TABLE commercial_mail_oauth_connection (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    provider varchar(16) NOT NULL CHECK(provider IN ('GOOGLE','MICROSOFT')),
    account_email varchar(254) NOT NULL,
    encrypted_refresh bytea NOT NULL,
    version bigint NOT NULL CHECK(version > 0),
    updated_at timestamptz NOT NULL,
    updated_by uuid NOT NULL REFERENCES user_identity(id)
);
