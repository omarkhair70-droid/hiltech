ALTER TABLE commercial_quote_email_attempt DROP CONSTRAINT commercial_quote_email_attempt_state_check;
ALTER TABLE commercial_quote_email_attempt ADD CONSTRAINT commercial_quote_email_attempt_state_check CHECK (state IN ('SENDING','SMTP_ACCEPTED','API_ACCEPTED','UNKNOWN'));
