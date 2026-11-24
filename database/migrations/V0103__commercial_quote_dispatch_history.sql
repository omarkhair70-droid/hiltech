-- Records operator-confirmed dispatch of the exact archived released PDF.
-- Never treats a share dialog, email draft, or export as delivery confirmation.
CREATE TABLE commercial_quote_dispatch (
    id uuid PRIMARY KEY,
    quote_version_id uuid NOT NULL REFERENCES commercial_customer_pdf_archive(quote_version_id) ON DELETE RESTRICT,
    channel varchar(24) NOT NULL CHECK (channel IN ('EMAIL','WHATSAPP','PRINT','OTHER')),
    recipient_reference varchar(240) NOT NULL CHECK (length(trim(recipient_reference)) > 0),
    recorded_at timestamptz NOT NULL,
    recorded_by uuid NOT NULL REFERENCES user_identity(id) ON DELETE RESTRICT
);
CREATE TRIGGER commercial_quote_dispatch_immutable BEFORE UPDATE OR DELETE
    ON commercial_quote_dispatch FOR EACH ROW EXECUTE FUNCTION reject_commercial_pdf_archive_mutation();
