# Commercial quote export and email deployment

The released customer projection excludes private costs. The first PDF export is archived by quote version with SHA-256. The template uses embedded Plex, with rasterized A4 pages; verify real company logo, contacts and legal details before production acceptance.

Direct email uses the company SMTP account. Configure Spring Boot mail properties using deployment secrets (never commit passwords):
- SPRING_MAIL_HOST, SPRING_MAIL_PORT, SPRING_MAIL_USERNAME, SPRING_MAIL_PASSWORD
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_AUTH=true (when required)
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE=true and SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_REQUIRED=true (for STARTTLS providers)
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_CONNECTIONTIMEOUT=10000
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_TIMEOUT=10000
- SPRING_MAIL_PROPERTIES_MAIL_SMTP_WRITETIMEOUT=10000
- HILTECH_COMMERCIAL_EMAIL_FROM: verified company sender address

Verify TLS and authentication settings for the selected provider. No recipient may be sent an actual quotation without the operator confirming the address and release. SMTP_ACCEPTED means SMTP server acceptance, not inbox delivery. SENDING/UNKNOWN means verify with the provider before trying again; no blind automatic retry. WhatsApp currently uses native Android sharing, not a WhatsApp Business API.

Apply migrations in order and verify existing quote approvals, discount/tax amount reconciliation, company client isolation, PDF archive repeatability and authorized dispatch. Discount and tax are explicit monetary amounts in the quote currency, not a statutory rate calculation. Run acceptance on Windows and Android using a controlled internal recipient before enabling live sending.

## In-app SMTP connection
A release-authorized user can test/connect/disconnect SMTP in Commercial > Company & document settings. Use a provider-supported app password or SMTP credential. Server secret HILTECH_COMMERCIAL_MAIL_ENCRYPTION_KEY must be base64 of 32 cryptographically random bytes; preserve it in encrypted backup. HILTECH_COMMERCIAL_SMTP_ALLOWED_HOSTS is a trusted deployment allowlist, default smtp.gmail.com,smtp.office365.com. Only STARTTLS on port 587 is used; OAuth-only tenants need a separate OAuth integration. Existing deployment SMTP is available only if HILTECH_COMMERCIAL_LEGACY_MAIL_COMPANY explicitly maps it to the company's UUID. Disconnecting a native connection does not remove an independently configured legacy account. No secret is returned through status endpoints. Google/Microsoft OAuth and WhatsApp Business embedded signup are not implemented.

## WhatsApp Business document dispatch
Configure a company-specific connection through deployment secrets: HILTECH_COMMERCIAL_WHATSAPP_COMPANY (company UUID), HILTECH_COMMERCIAL_WHATSAPP_PHONE_ID, HILTECH_COMMERCIAL_WHATSAPP_TOKEN, HILTECH_COMMERCIAL_WHATSAPP_TEMPLATE, HILTECH_COMMERCIAL_WHATSAPP_LANGUAGE, HILTECH_COMMERCIAL_WHATSAPP_API_VERSION. The approved template must have a document header and static body (no body parameters). The operator must confirm recipient consent and number. The archived PDF is uploaded to Meta and attached to the template message. API_ACCEPTED is not delivery; provider webhooks and embedded signup are not implemented. Test only with an approved internal recipient before live use. OAuth account setup and Meta embedded signup remain separate tasks. The commercial backend now defaults to enabled; explicit authority and approval requirements still apply.

Public contact footer source (verified 2026-11-24): https://www.hiltech-eg.com/contact . The public website WhatsApp quote link points to +201555357807, not a verified API sender. Website header artwork was inspected and is not a company logo; do not substitute it. Official logo and legal/tax identifiers remain subject to confirmation. Existing archived PDF bytes are never regenerated.
