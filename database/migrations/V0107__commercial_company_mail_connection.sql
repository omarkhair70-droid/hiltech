CREATE TABLE commercial_company_mail_connection (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    host varchar(253) NOT NULL,
    username varchar(254) NOT NULL,
    encrypted_password bytea NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    updated_by uuid NOT NULL REFERENCES user_identity(id),
    updated_at timestamptz NOT NULL
);
