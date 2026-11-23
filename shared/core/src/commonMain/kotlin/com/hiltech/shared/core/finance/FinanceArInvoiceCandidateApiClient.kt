package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceArInvoiceCandidateCommandDto(
    val operationId:String,
    val billingMilestoneEvidenceId:String,
    val requestedAmount:String,
    val legalTaxReviewReference:String,
    val contractualBillableReference:String,
    val invoiceIssueDate:String,
    val invoiceDueDate:String,
)
@Serializable
data class FinanceArInvoiceCandidateReceiptDto(
    val candidateId:String,
    val organizationId:String,
    val opportunityId:String,
    val customerOrganizationId:String,
    val commercialSourceSha256:String,
    val requestedAmount:String,
    val currency:String,
    val state:String,
    val replayed:Boolean=false,
    val invoiceIssued:Boolean=false,
    val receivablePosted:Boolean=false,
    val cashCollected:Boolean=false,
    val bankReconciled:Boolean=false,
)
/**
 * KMP API remains unmounted until verified company billing Evidence and
 * independent Finance/Tax policy exist. Draft is NEVER a sent invoice.
 */
class FinanceArInvoiceCandidateApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun create(
        organizationId:String,opportunityId:String,
        operationId:String,billingMilestoneEvidenceId:String,
        requestedAmount:String,legalTaxReviewReference:String,
        contractualBillableReference:String,
        invoiceIssueDate:String,invoiceDueDate:String,
        installationId:String,
    ):FinanceArInvoiceCandidateReceiptDto {
        val id=uuid(operationId)
        val body=FinanceArInvoiceCandidateCommandDto(
            operationId=id,billingMilestoneEvidenceId=uuid(billingMilestoneEvidenceId),
            requestedAmount=requestedAmount,
            legalTaxReviewReference=legalTaxReviewReference,
            contractualBillableReference=contractualBillableReference,
            invoiceIssueDate=invoiceIssueDate,invoiceDueDate=invoiceDueDate,
        )
        return api.request(
            method=HttpMethod.Post,
            path=org(organizationId)+"/client-award-sources/"+
                uuid(opportunityId)+"/invoice-candidates",
            options=HiltechRequestOptions(
                installationId=installationId,idempotencyKey=id,
            ),
            requestBody=json.encodeToString(
                FinanceArInvoiceCandidateCommandDto.serializer(),body,
            ),
            decode={ json.decodeFromString(
                FinanceArInvoiceCandidateReceiptDto.serializer(),it,
            ) },
        )
    }
    suspend fun status(
        organizationId:String,candidateId:String,installationId:String,
    ):FinanceArInvoiceCandidateReceiptDto=api.request(
        method=HttpMethod.Get,
        path=org(organizationId)+"/invoice-candidates/"+uuid(candidateId),
        options=HiltechRequestOptions(installationId=installationId),
        decode={ json.decodeFromString(
            FinanceArInvoiceCandidateReceiptDto.serializer(),it,
        ) },
    )
    private fun org(id:String)="/v1/finance/organizations/"+uuid(id)
    private fun uuid(s:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(s)) { "Valid UUID required" }
        return s
    }
}
