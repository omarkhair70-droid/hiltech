package com.hiltech.server.commercial

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID
import java.util.Base64
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

data class CommercialDocumentProfile(
    val legalName: String = "",
    val address: String = "",
    val phone: String = "",
    val email: String = "",
    val website: String = "",
    val registration: String = "",
    val taxNumber: String = "",
    val logoBase64: String = "",
    val quotePrefix: String = "Q",
    val paymentTerms: String = "",
    val deliveryTerms: String = "",
)
data class CommercialDocumentSettingsDto(val version: Long, val profile: CommercialDocumentProfile)
data class SaveCommercialDocumentSettingsRequest(val operationId: String, val organizationId: String, val baseVersion: Long, val profile: CommercialDocumentProfile)

internal object CommercialDocumentSettings {
    private fun profile(row: Map<String, Any?>) = CommercialDocumentProfile(
        legalName = row["legal_name"] as String,
        address = row["address"] as String,
        phone = row["phone"] as String,
        email = row["email"] as String,
        website = row["website"] as String,
        registration = row["registration"] as String,
        taxNumber = row["tax_number"] as String,
        logoBase64 = row["logo_base64"] as String,
        quotePrefix = row["quote_prefix"] as String,
        paymentTerms = row["payment_terms"] as String,
        deliveryTerms = row["delivery_terms"] as String,
    )
    fun read(jdbc: JdbcTemplate, company: UUID): CommercialDocumentSettingsDto {
        val saved = jdbc.queryForList("SELECT version,legal_name,address,phone,email,website,registration,tax_number,logo_base64,quote_prefix,payment_terms,delivery_terms FROM commercial_document_settings WHERE organization_id=?",company).firstOrNull()
        if (saved != null) return CommercialDocumentSettingsDto((saved["version"] as Number).toLong(),profile(saved))
        val name = requireNotNull(jdbc.queryForObject("SELECT legal_name FROM organization WHERE id=?",String::class.java,company))
        return CommercialDocumentSettingsDto(0,CommercialDocumentProfile(legalName=name))
    }
    fun snapshot(jdbc: JdbcTemplate, quote: UUID): CommercialDocumentProfile? =
        jdbc.queryForList("SELECT legal_name,address,phone,email,website,registration,tax_number,logo_base64,quote_prefix,payment_terms,delivery_terms FROM commercial_quote_issuer_snapshot WHERE quote_version_id=?",quote).firstOrNull()?.let(::profile)
    fun freeze(jdbc: JdbcTemplate, quote: UUID, company: UUID) {
        val p = read(jdbc,company).profile
        jdbc.update("INSERT INTO commercial_quote_issuer_snapshot(quote_version_id,legal_name,address,phone,email,website,registration,tax_number,logo_base64,quote_prefix,payment_terms,delivery_terms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",quote,p.legalName,p.address,p.phone,p.email,p.website,p.registration,p.taxNumber,p.logoBase64,p.quotePrefix,p.paymentTerms,p.deliveryTerms)
    }
    fun save(jdbc: JdbcTemplate, company: UUID, version: Long, p: CommercialDocumentProfile, actor: UUID, at: java.time.OffsetDateTime) {
        jdbc.update("INSERT INTO commercial_document_settings(organization_id,version,legal_name,address,phone,email,website,registration,tax_number,logo_base64,quote_prefix,payment_terms,delivery_terms,updated_by,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(organization_id) DO UPDATE SET version=EXCLUDED.version,legal_name=EXCLUDED.legal_name,address=EXCLUDED.address,phone=EXCLUDED.phone,email=EXCLUDED.email,website=EXCLUDED.website,registration=EXCLUDED.registration,tax_number=EXCLUDED.tax_number,logo_base64=EXCLUDED.logo_base64,quote_prefix=EXCLUDED.quote_prefix,payment_terms=EXCLUDED.payment_terms,delivery_terms=EXCLUDED.delivery_terms,updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at",company,version,p.legalName,p.address,p.phone,p.email,p.website,p.registration,p.taxNumber,p.logoBase64,p.quotePrefix,p.paymentTerms,p.deliveryTerms,actor,at)
    }
    fun validate(p: CommercialDocumentProfile) {
        if (p.legalName.isBlank() || p.legalName.length > 240 ||
            listOf(p.address,p.phone,p.email,p.website,p.registration,p.taxNumber).any { it.length > 240 || it.any { ch -> ch.isISOControl() } } ||
            p.paymentTerms.length > 10000 || p.deliveryTerms.length > 10000 ||
            !Regex("[A-Z0-9-]{1,12}").matches(p.quotePrefix))
            throw commercialError("COMMERCIAL_SETTINGS_INVALID","Invalid document settings.",HttpStatus.BAD_REQUEST)
        if (p.logoBase64.isNotBlank()) {
            try {
                require(p.logoBase64.length <= 1400000)
                val bytes = Base64.getDecoder().decode(p.logoBase64)
                require(bytes.size <= 1024*1024)
                ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val readers = ImageIO.getImageReaders(input)
                    require(readers.hasNext())
                    val reader = readers.next()
                    try {
                        require(reader.formatName.lowercase() in setOf("png","jpeg","jpg"))
                        reader.input = input
                        require(reader.getWidth(0) in 1..2048 && reader.getHeight(0) in 1..2048)
                        require(reader.read(0) != null)
                    } finally { reader.dispose() }
                }
            } catch (_: Exception) {
                throw commercialError("COMMERCIAL_LOGO_INVALID","Use a PNG/JPEG up to 1 MiB and 2048 px.",HttpStatus.BAD_REQUEST)
            }
        }
    }
}
