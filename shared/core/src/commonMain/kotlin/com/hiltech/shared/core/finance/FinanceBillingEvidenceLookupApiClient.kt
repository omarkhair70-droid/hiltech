package com.hiltech.shared.core.finance

import com.hiltech.shared.core.evidence.EvidenceMetadataDto
import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json

/**
 * Existing Evidence metadata read, never uploads, mutates or approves a bill.
 * Final billing authority is owned by FinanceArInvoiceCandidateService.
 */
class FinanceBillingEvidenceLookupApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json {ignoreUnknownKeys=true;explicitNulls=false},
) {
    suspend fun metadata(evidenceId:String,installation:String):EvidenceMetadataDto {
        require(financeLookupIdIsUuid(evidenceId))
        return api.request(
            method=HttpMethod.Get,
            path="/v1/evidence/"+evidenceId,
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(EvidenceMetadataDto.serializer(),it)},
        )
    }
}

data class FinanceBillingEvidencePreview(
    val evidenceId:String,
    val contentType:String,
    val sizeBytes:Long,
    val verifiedReady:Boolean,
)

suspend fun financeBillingEvidencePreview(
    apis:FinanceEvidenceReadApis,opportunityId:String,evidenceId:String,installation:String,
):FinanceBillingEvidencePreview {
    require(financeLookupIdIsUuid(opportunityId) && financeLookupIdIsUuid(evidenceId))
    val item=apis.billingEvidence.metadata(evidenceId,installation)
    check(item.evidenceId.equals(evidenceId,true) &&
        item.targetType=="FINANCE_CLIENT_BILLING_MILESTONE" &&
        item.targetId.equals(opportunityId,true) &&
        item.storageState=="READY" &&
        item.sizeBytes>0L) {
        "FINANCE_BILLING_EVIDENCE_NOT_READY_OR_WRONG_SOURCE"
    }
    return FinanceBillingEvidencePreview(item.evidenceId,item.contentType,item.sizeBytes,true)
}
