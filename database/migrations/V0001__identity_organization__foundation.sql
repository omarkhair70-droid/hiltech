-- HILTECH OS first-slice identity / organization foundation.
-- Canonical contracts: Data Dictionary + Bootstrap closure 26.

CREATE TABLE organization (
    id uuid PRIMARY KEY,
    organization_code varchar(64) NOT NULL UNIQUE,
    legal_name varchar(240) NOT NULL,
    display_name varchar(160) NOT NULL,
    organization_type varchar(32) NOT NULL
        CHECK (organization_type IN ('HILTECH','CLIENT','SUPPLIER','SUBCONTRACTOR','PARTNER','OTHER')),
    status varchar(16) NOT NULL
        CHECK (status IN ('ACTIVE','INACTIVE')),
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1)
);

CREATE TABLE user_identity (
    id uuid PRIMARY KEY,
    auth_provider varchar(64) NOT NULL,
    auth_subject varchar(255) NOT NULL,
    person_id uuid NULL,
    status varchar(16) NOT NULL
        CHECK (status IN ('ACTIVE','LOCKED','REVOKED','PENDING')),
    primary_organization_id uuid NULL REFERENCES organization(id) ON DELETE RESTRICT,
    created_at timestamptz NOT NULL,
    last_authenticated_at timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (auth_provider, auth_subject)
);

CREATE TABLE organization_membership (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    user_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    membership_type varchar(64) NOT NULL,
    role_label varchar(120) NULL,
    state varchar(16) NOT NULL
        CHECK (state IN ('PENDING','ACTIVE','SUSPENDED','ENDED')),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NULL,
    invited_by uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE TABLE team (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    code varchar(64) NOT NULL,
    name varchar(160) NOT NULL,
    parent_team_id uuid NULL REFERENCES team(id) ON DELETE RESTRICT,
    manager_user_identity_id uuid NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    active boolean NOT NULL DEFAULT true,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    UNIQUE (organization_id, code),
    CHECK (parent_team_id IS NULL OR parent_team_id <> id)
);

CREATE TABLE team_membership (
    id uuid PRIMARY KEY,
    team_id uuid NOT NULL REFERENCES team(id) ON DELETE RESTRICT,
    user_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    role_in_team varchar(64) NULL,
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1),
    CHECK (valid_until IS NULL OR valid_until >= valid_from)
);

CREATE TABLE device (
    id uuid PRIMARY KEY,
    user_identity_id uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT,
    platform varchar(16) NOT NULL CHECK (platform IN ('ANDROID','WINDOWS','IOS')),
    device_name varchar(160) NULL,
    installation_id uuid NOT NULL UNIQUE,
    app_version varchar(64) NOT NULL,
    os_version varchar(120) NULL,
    last_seen_at timestamptz NULL,
    revoked_at timestamptz NULL,
    created_at timestamptz NOT NULL,
    version bigint NOT NULL DEFAULT 1 CHECK (version >= 1)
);
