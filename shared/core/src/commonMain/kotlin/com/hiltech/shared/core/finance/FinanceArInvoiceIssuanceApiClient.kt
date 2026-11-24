package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceArInvoiceIssuancePrepareDto(
    val operationId:String,
    val invoiceNumber:String,
    val invoiceDocumentEvidenceId:String,
)
@Serializable
data class FinanceArInvoiceIssuanceOperationDto(val operationId:String)
@Serializable
data class FinanceArInvoiceIssuanceReceiptDto(
    val proposalId:String,val candidateId:String,val organizationId:String,
    val invoiceNumber:String,val state:String,
    val approvalRequestId:String?=null,val issuedInvoiceId:String?=null,
    val replayed:Boolean=false,val recordedInternally:Boolean=false,
    val invoiceSentExternally:Boolean=false,val receivableRecognized:Boolean=false,
    val paymentCollected:Boolean=false,
)
/** The native client never claims external sending or receivable posting. */
class FinanceArInvoiceIssuanceApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun prepare(
        org:String,candidate:String,operation:String,
        companyInvoiceNumber:String,documentEvidenceId:String,installation:String,
    ):FinanceArInvoiceIssuanceReceiptDto {
        require(companyInvoiceNumber.trim().length in 3..100)
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/invoice-candidates/"+uuid(candidate)+"/issuance-proposals",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=id,
            ),
            requestBody=json.encodeToString(
                FinanceArInvoiceIssuancePrepareDto.serializer(),
                FinanceArInvoiceIssuancePrepareDto(
                    id,companyInvoiceNumber.trim(),uuid(documentEvidenceId),
                ),
            ),
            decode={ json.decodeFromString(
                FinanceArInvoiceIssuanceReceiptDto.serializer(),it,
            ) },
        )
    }
    suspend fun requestReview(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceArInvoiceIssuanceReceiptDto =
        command(org,proposal,operation,installation,"request-review")

    suspend fun record(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceArInvoiceIssuanceReceiptDto =
        command(org,proposal,operation,installation,"record")

    suspend fun operatorQueue(org:String,installation:String):List<FinanceArInvoiceIssuanceReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+uuid(org)+"/invoice-issuance-proposals/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceArInvoiceIssuanceReceiptDto.serializer()),it)},
        )

    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceArInvoiceIssuanceReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/invoice-issuance-proposals/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={ json.decodeFromString(
            FinanceArInvoiceIssuanceReceiptDto.serializer(),it,
        ) },
    )

    private suspend fun command(
        org:String,proposal:String,operation:String,installation:String,action:String,
    ):FinanceArInvoiceIssuanceReceiptDto {
        require(action=="request-review" || action=="record")
        val id=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/invoice-issuance-proposals/"+
                uuid(proposal)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=id,
            ),
            requestBody=json.encodeToString(
                FinanceArInvoiceIssuanceOperationDto.serializer(),
                FinanceArInvoiceIssuanceOperationDto(id),
            ),
            decode={ json.decodeFromString(
                FinanceArInvoiceIssuanceReceiptDto.serializer(),it,
            ) },
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(x:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(x)) { "Valid UUID required." }
        return x
    }
}
