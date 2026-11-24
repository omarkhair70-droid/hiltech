-- Customer PDFs are immutable exports of an already released quote version.
CREATE TABLE commercial_customer_pdf_archive (
    quote_version_id uuid PRIMARY KEY REFERENCES commercial_quote_version(id) ON DELETE RESTRICT,
    filename varchar(160) NOT NULL,
    template_version integer NOT NULL CHECK (template_version > 0),
    pdf_bytes bytea NOT NULL CHECK (octet_length(pdf_bytes) BETWEEN 6 AND 10485760),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    created_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT
);
CREATE FUNCTION reject_commercial_pdf_archive_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Released customer PDF archive is immutable';
END;
$$;
CREATE TRIGGER commercial_customer_pdf_archive_immutable
    BEFORE UPDATE OR DELETE ON commercial_customer_pdf_archive
    FOR EACH ROW EXECUTE FUNCTION reject_commercial_pdf_archive_mutation();
