CREATE TABLE commercial_meta_signup_pending (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    actor_id uuid NOT NULL REFERENCES user_identity(id),
    state_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    status varchar(16) NOT NULL CHECK(status IN ('PENDING','EXCHANGING','READY','FAILED')),
    phone_id varchar(40),
    display_phone varchar(80),
    template_name varchar(512) NOT NULL,
    language_code varchar(20) NOT NULL,
    encrypted_token bytea,
    base_version bigint NOT NULL
);
