package com.hiltech.server.commercial

import jakarta.mail.internet.InternetAddress
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.UUID

data class CommercialQuoteEmailRequest(val operationId: String, val recipient: String)
data class CommercialQuoteEmailStatus(val id: String, val recipient: String, val state: String, val createdAt: String)

/** SMTP acceptance is not delivery. Ambiguous outcomes are never automatically retried. */
@Component
class CommercialQuoteEmail(
    private val commercial: CommercialService,
    private val jdbc: JdbcTemplate,
    private val mail: ObjectProvider<JavaMailSender>,
    private val connections: CommercialMailConnection,
    @Value("\${hiltech.commercial.legacy-mail-company:}") private val legacyCompany: String,
    @Value("\${hiltech.commercial.email-from:}") private val from: String,
) {
    fun available(): Boolean = from.isNotBlank() && mail.ifAvailable != null
    fun available(id: UUID, actor: UUID): Boolean {
        val company = commercial.quoteCompanyForDispatch(id,actor)
        return connections.forSending(company) != null || (legacyCompany == company.toString() && available())
    }

    private fun email(raw: String): String {
        val address = raw.trim()
        if (address.length > 254 || address.any { it == '\r' || it == '\n' })
            throw commercialError("COMMERCIAL_EMAIL_INVALID", "Invalid email address.", HttpStatus.BAD_REQUEST)
        try {
            val parsed = InternetAddress(address, true)
            parsed.validate()
            require(parsed.personal == null && parsed.address == address)
        } catch (_: Exception) {
            throw commercialError("COMMERCIAL_EMAIL_INVALID", "Invalid email address.", HttpStatus.BAD_REQUEST)
        }
        return address
    }

    fun history(quote: UUID, actor: UUID): List<CommercialQuoteEmailStatus> {
        commercial.customerQuote(quote, actor)
        return jdbc.queryForList("SELECT id,recipient,state,created_at FROM commercial_quote_email_attempt WHERE quote_version_id=? ORDER BY created_at DESC", quote)
            .map { status(it) }
    }

    private fun status(row: Map<String, Any?>) = CommercialQuoteEmailStatus(
        row["id"].toString(), row["recipient"].toString(), row["state"].toString(), row["created_at"].toString())

    fun send(quote: UUID, actor: UUID, key: UUID, recipient: String): CommercialQuoteEmailStatus {
        val projection = commercial.requireQuoteDispatch(quote, actor)
        val to = email(recipient)
        val company = commercial.quoteCompanyForDispatch(quote,actor)
        val connected = connections.forSending(company)
        val sender = connected?.first ?: mail.ifAvailable?.takeIf { legacyCompany == company.toString() && available() }
            ?: throw commercialError("COMMERCIAL_EMAIL_NOT_CONFIGURED", "Company email service is not configured.", HttpStatus.SERVICE_UNAVAILABLE)
        val senderAddress = email(connected?.second ?: from)
        val pdf = commercial.customerPdf(quote, actor)
        val message = sender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")
        helper.setFrom(senderAddress)
        helper.setTo(to)
        helper.setSubject("HILTECH | " + projection.quoteCode + " / v" + projection.versionNumber)
        helper.setText("Please find the released quotation attached.\nيرجى الاطلاع على عرض السعر المرفق.")
        helper.addAttachment(pdf.filename, ByteArrayResource(Base64.getDecoder().decode(pdf.base64)), "application/pdf")
        commercial.requireQuoteDispatch(quote, actor)
        val inserted = jdbc.update(
            "INSERT INTO commercial_quote_email_attempt(id,quote_version_id,recipient,actor_id,state) VALUES (?,?,?,?,'SENDING') ON CONFLICT(id) DO NOTHING",
            key, quote, to, actor)
        fun current(): Map<String, Any?> = jdbc.queryForList(
            "SELECT id,recipient,state,created_at,quote_version_id,actor_id FROM commercial_quote_email_attempt WHERE id=?", key).single()
        if (inserted == 0) {
            val old = current()
            if (old["quote_version_id"] != quote || old["actor_id"] != actor || old["recipient"] != to)
                throw commercialError("COMMERCIAL_EMAIL_OPERATION_CONFLICT", "Operation already used.", HttpStatus.CONFLICT)
            return status(old)
        }
        val state = try {
            sender.send(message)
            "SMTP_ACCEPTED"
        } catch (_: Exception) {
            "UNKNOWN"
        }
        jdbc.update("UPDATE commercial_quote_email_attempt SET state=?,completed_at=now() WHERE id=? AND state='SENDING'", state, key)
        return status(current())
    }
}
