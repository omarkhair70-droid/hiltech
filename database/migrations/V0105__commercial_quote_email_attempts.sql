CREATE TABLE commercial_quote_email_attempt (
    id uuid PRIMARY KEY,
    quote_version_id uuid NOT NULL REFERENCES commercial_customer_pdf_archive(quote_version_id),
    recipient varchar(254) NOT NULL,
    actor_id uuid NOT NULL REFERENCES user_identity(id),
    state varchar(24) NOT NULL CHECK (state IN ('SENDING','SMTP_ACCEPTED','UNKNOWN')),
    created_at timestamptz NOT NULL DEFAULT now(),
    completed_at timestamptz
);
CREATE INDEX commercial_quote_email_attempt_quote ON commercial_quote_email_attempt(quote_version_id,created_at DESC);
