CREATE TABLE commercial_document_settings (
    organization_id uuid PRIMARY KEY REFERENCES organization(id),
    version bigint NOT NULL CHECK (version > 0),
    legal_name text NOT NULL DEFAULT '',
    address text NOT NULL DEFAULT '',
    phone text NOT NULL DEFAULT '',
    email text NOT NULL DEFAULT '',
    website text NOT NULL DEFAULT '',
    registration text NOT NULL DEFAULT '',
    tax_number text NOT NULL DEFAULT '',
    logo_base64 text NOT NULL DEFAULT '',
    quote_prefix text NOT NULL DEFAULT 'Q',
    payment_terms text NOT NULL DEFAULT '',
    delivery_terms text NOT NULL DEFAULT '',
    updated_by uuid NOT NULL REFERENCES user_identity(id),
    updated_at timestamptz NOT NULL
);
CREATE TABLE commercial_quote_issuer_snapshot (
    quote_version_id uuid PRIMARY KEY REFERENCES commercial_quote_version(id),
    legal_name text NOT NULL DEFAULT '',
    address text NOT NULL DEFAULT '',
    phone text NOT NULL DEFAULT '',
    email text NOT NULL DEFAULT '',
    website text NOT NULL DEFAULT '',
    registration text NOT NULL DEFAULT '',
    tax_number text NOT NULL DEFAULT '',
    logo_base64 text NOT NULL DEFAULT '',
    quote_prefix text NOT NULL DEFAULT 'Q',
    payment_terms text NOT NULL DEFAULT '',
    delivery_terms text NOT NULL DEFAULT ''
);
CREATE TRIGGER commercial_quote_issuer_snapshot_immutable BEFORE UPDATE OR DELETE
ON commercial_quote_issuer_snapshot FOR EACH ROW EXECUTE FUNCTION reject_commercial_pdf_archive_mutation();
