-- Preserve the exact legal customer name the approved quotation was released to.
-- Existing released quotes retain their source names and archived PDF bytes unchanged.
CREATE TABLE commercial_quote_client_snapshot (
    quote_version_id uuid PRIMARY KEY REFERENCES commercial_quote_version(id) ON DELETE RESTRICT,
    client_organization_id uuid NOT NULL REFERENCES organization(id) ON DELETE RESTRICT,
    client_legal_name text NOT NULL CHECK (length(trim(client_legal_name)) > 0)
);
CREATE TRIGGER commercial_quote_client_snapshot_immutable BEFORE UPDATE OR DELETE
ON commercial_quote_client_snapshot FOR EACH ROW EXECUTE FUNCTION reject_commercial_pdf_archive_mutation();
