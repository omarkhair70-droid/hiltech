CREATE TABLE commercial_company_whatsapp_connection (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    phone_id varchar(40) NOT NULL,
    display_phone varchar(80) NOT NULL,
    template_name varchar(512) NOT NULL,
    language_code varchar(20) NOT NULL,
    api_version varchar(16) NOT NULL,
    encrypted_token bytea NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    updated_by uuid NOT NULL REFERENCES user_identity(id),
    updated_at timestamptz NOT NULL
);
