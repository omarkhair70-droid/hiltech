package com.hiltech.server.finance

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/**
 * A verified commercial award plus an immutable Finance draft is not enough
 * to issue a customer invoice. An exact invoice number and canonical final PDF
 * must receive their own independent issuance approval.
 */
data class FinanceArIssuanceSnapshot(
    val organizationId:UUID,
    val candidateId:UUID,
    val customerOrganizationId:UUID,
    val invoiceNumber:String,
    val documentEvidenceId:UUID,
    val commercialSourceHash:String,
    val approvedAmount:BigDecimal,
    val currency:String,
    val issueDate:LocalDate,
    val dueDate:LocalDate,
    val candidatePreparedBy:UUID,
    val issuer:UUID,
    val sourceCurrent:Boolean,
    val verifiedInvoiceDocument:Boolean,
    val numberAssignedByCompany:Boolean,
)

object FinanceArInvoiceIssuanceRules {
    fun validate(s:FinanceArIssuanceSnapshot) {
        require(s.organizationId!=s.customerOrganizationId) {"CROSS_PARTY_INVOICE"}
        require(s.candidatePreparedBy!=s.issuer) {"SELF_ISSUANCE_NOT_ALLOWED"}
        require(s.invoiceNumber.trim().length in 3..100 &&
            !s.invoiceNumber.any { it.isISOControl() }) {"INVALID_COMPANY_INVOICE_NUMBER"}
        require(s.commercialSourceHash.matches(Regex("^[a-f0-9]{64}$"))) {
            "UNVERIFIED_COMMERCIAL_AWARD"
        }
        require(s.currency.matches(Regex("^[A-Z]{3}$"))) {"INVALID_CURRENCY"}
        require(s.approvedAmount>BigDecimal.ZERO &&
            s.approvedAmount.scale()<=6) {"INVALID_AMOUNT"}
        require(!s.dueDate.isBefore(s.issueDate)) {"INVALID_DUE_DATE"}
        require(s.sourceCurrent && s.verifiedInvoiceDocument &&
            s.numberAssignedByCompany) {"COMPANY_ISSUANCE_EVIDENCE_MISSING"}
    }
}

object FinanceArInvoiceIssuanceVersion {
    fun derive(s:FinanceArIssuanceSnapshot,policyVersion:Int,
               reviewerBindingId:UUID,reviewerBindingVersion:Long):Long {
        require(policyVersion>0 && reviewerBindingVersion>0)
        val material=listOf(
            "HILTECH_AR_ISSUANCE_V1",s.organizationId,s.candidateId,
            s.customerOrganizationId,s.invoiceNumber.trim(),s.documentEvidenceId,
            s.commercialSourceHash,s.approvedAmount.toPlainString(),s.currency,
            s.issueDate,s.dueDate,s.candidatePreparedBy,
            policyVersion,reviewerBindingId,reviewerBindingVersion,
        ).joinToString("|")
        val digest=MessageDigest.getInstance("SHA-256")
            .digest(material.toByteArray(Charsets.UTF_8))
        return (ByteBuffer.wrap(digest,0,8).long and Long.MAX_VALUE)
            .coerceAtLeast(1L)
    }
}
