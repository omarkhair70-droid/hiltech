package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinanceArJournalAllocationDto(
    val glAccountId:String,val side:String,val amount:String,
)
@Serializable
data class FinanceArJournalProposalDto(
    val operationId:String,val fiscalPeriodId:String,
    val accountingTreatmentReference:String,val taxDispositionReference:String,
    val taxEvidenceId:String?=null,
    val lines:List<FinanceArJournalAllocationDto>,
)
@Serializable
data class FinanceArJournalOperationDto(val operationId:String)
@Serializable
data class FinanceArJournalReceiptDto(
    val proposalId:String,val issuedInvoiceId:String,
    val organizationId:String,val sourceVersion:Long,
    val ledgerFingerprintSha256:String,val amount:String,val currency:String,
    val state:String,val approvalRequestId:String?=null,
    val postedJournalId:String?=null,val replayed:Boolean=false,
    val receivableRecognized:Boolean=false,val cashCollected:Boolean=false,
    val bankReconciled:Boolean=false,
)
class FinanceArJournalPostingApiClient(
    private val api:HiltechApiClient,
    private val json:Json=Json { ignoreUnknownKeys=true; explicitNulls=false },
) {
    suspend fun propose(
        org:String,invoice:String,body:FinanceArJournalProposalDto,
        installation:String,
    ):FinanceArJournalReceiptDto {
        require(body.lines.size in 2..32)
        require(body.lines.all {
            uuid(it.glAccountId).isNotBlank() &&
              it.side in setOf("DEBIT","CREDIT") &&
              Regex("^[0-9]+(\\.[0-9]{1,6})?$").matches(it.amount) &&
              it.amount.any { ch->ch in '1'..'9' }
        })
        val operation=uuid(body.operationId)
        uuid(body.fiscalPeriodId)
        body.taxEvidenceId?.let { uuid(it) }
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/issued-invoices/"+uuid(invoice)+"/ar-journal-proposals",
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=operation,
            ),
            requestBody=json.encodeToString(FinanceArJournalProposalDto.serializer(),body),
            decode={json.decodeFromString(FinanceArJournalReceiptDto.serializer(),it)},
        )
    }
    suspend fun requestApproval(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceArJournalReceiptDto=command(
        org,proposal,operation,installation,"request-approval",
    )
    suspend fun post(
        org:String,proposal:String,operation:String,installation:String,
    ):FinanceArJournalReceiptDto=command(org,proposal,operation,installation,"post")
    suspend fun operatorQueue(org:String,installation:String):List<FinanceArJournalReceiptDto> =
        api.request(
            method=HttpMethod.Get,
            path="/v1/finance/organizations/"+uuid(org)+"/ar-journal-proposals/operator-queue",
            options=HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString(ListSerializer(FinanceArJournalReceiptDto.serializer()),it)},
        )

    suspend fun status(
        org:String,proposal:String,installation:String,
    ):FinanceArJournalReceiptDto=api.request(
        method=HttpMethod.Get,
        path=base(org)+"/ar-journal-proposals/"+uuid(proposal),
        options=HiltechRequestOptions(installationId=installation),
        decode={json.decodeFromString(FinanceArJournalReceiptDto.serializer(),it)},
    )
    private suspend fun command(
        org:String,proposal:String,operation:String,installation:String,action:String,
    ):FinanceArJournalReceiptDto {
        require(action=="post" || action=="request-approval")
        val key=uuid(operation)
        return api.request(
            method=HttpMethod.Post,
            path=base(org)+"/ar-journal-proposals/"+uuid(proposal)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installation,idempotencyKey=key,
            ),
            requestBody=json.encodeToString(
                FinanceArJournalOperationDto.serializer(),
                FinanceArJournalOperationDto(key),
            ),
            decode={json.decodeFromString(FinanceArJournalReceiptDto.serializer(),it)},
        )
    }
    private fun base(org:String)="/v1/finance/organizations/"+uuid(org)
    private fun uuid(x:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(x)) {"Valid UUID required"}
        return x
    }
}
