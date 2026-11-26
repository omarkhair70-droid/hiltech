ALTER TABLE commercial_quote_whatsapp_attempt ADD COLUMN sender_phone_id varchar(40);
ALTER TABLE commercial_quote_whatsapp_attempt ADD COLUMN provider_status_at timestamptz;
ALTER TABLE commercial_quote_whatsapp_attempt DROP CONSTRAINT commercial_quote_whatsapp_attempt_state_check;
ALTER TABLE commercial_quote_whatsapp_attempt ADD CONSTRAINT commercial_quote_whatsapp_attempt_state_check
CHECK (state IN ('SENDING','API_ACCEPTED','UNKNOWN','SENT','DELIVERED','READ','FAILED'));
CREATE UNIQUE INDEX commercial_whatsapp_provider_message ON commercial_quote_whatsapp_attempt(sender_phone_id,provider_message_id)
WHERE sender_phone_id IS NOT NULL AND provider_message_id IS NOT NULL;
