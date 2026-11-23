package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class FinanceApJournalAllocationDto(
    val glAccountId: String,
    val side: String,
    val amount: String,
)

@Serializable
data class FinanceApJournalProposalDto(
    val operationId: String,
    val fiscalPeriodId: String,
    val accountingTreatmentReference: String,
    val accountingTaxDisposition: String,
    val taxEvidenceId: String? = null,
    val lines: List<FinanceApJournalAllocationDto>,
)

@Serializable
data class FinanceApJournalOperationDto(val operationId: String)

@Serializable
data class FinanceApPostingReceiptDto(
    val proposalId: String,
    val payableCandidateId: String,
    val sourceVersion: Long,
    val ledgerFingerprintSha256: String,
    val invoiceAmount: String,
    val currency: String,
    val state: String,
    val postingReviewRequestId: String? = null,
    val postedJournalId: String? = null,
    val replayed: Boolean = false,
    val recognizedAccountsPayable: Boolean = false,
    val paymentApproved: Boolean = false,
    val moneyTransferred: Boolean = false,
    val bankReconciled: Boolean = false,
)

/**
 * Authenticated accountant interface. Draft -> independent ApprovalEngine
 * decision -> posted double entry. There is deliberately NO bank/payout API.
 * Do not mount until current company Finance roles and chart are verified.
 */
class FinanceApJournalPostingApiClient(
    private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false },
) {
    suspend fun propose(
        organizationId: String,candidateId: String,
        input: FinanceApJournalProposalDto,installationId: String,
    ): FinanceApPostingReceiptDto {
        val op=uuid(input.operationId)
        val checked=input.copy(
            operationId=op,
            fiscalPeriodId=uuid(input.fiscalPeriodId),
            taxEvidenceId=input.taxEvidenceId?.let(::uuid),
            lines=input.lines.map {
                it.copy(glAccountId=uuid(it.glAccountId)).also { _ ->
                    require(it.side=="DEBIT" || it.side=="CREDIT") {
                        "Only double-entry debit/credit allocations allowed."
                    }
                    require(
                        Regex("^[0-9]+(?:\\.[0-9]{1,6})?$").matches(it.amount)
                    ) { "Exact decimal Finance allocation required." }
                }
            },
        )
        return api.request(
            method=HttpMethod.Post,
            path="/v1/finance/organizations/"+uuid(organizationId)+
                "/payable-candidates/"+uuid(candidateId)+"/journal-proposals",
            options=HiltechRequestOptions(
                installationId=installationId,idempotencyKey=op,
            ),
            requestBody=json.encodeToString(
                FinanceApJournalProposalDto.serializer(),checked,
            ),
            decode={
                json.decodeFromString(FinanceApPostingReceiptDto.serializer(),it)
            },
        )
    }

    suspend fun requestPostingApproval(
        organizationId:String,proposalId:String,operationId:String,
        installationId:String,
    ):FinanceApPostingReceiptDto = command(
        organizationId,proposalId,"request-posting-approval",
        operationId,installationId,
    )

    suspend fun commitReviewedJournal(
        organizationId:String,proposalId:String,operationId:String,
        installationId:String,
    ):FinanceApPostingReceiptDto = command(
        organizationId,proposalId,"post",operationId,installationId,
    )

    suspend fun status(
        organizationId:String,proposalId:String,installationId:String,
    ):FinanceApPostingReceiptDto =
        api.request(
            method=HttpMethod.Get,
            path=route(organizationId,proposalId),
            options=HiltechRequestOptions(installationId=installationId),
            decode={
                json.decodeFromString(FinanceApPostingReceiptDto.serializer(),it)
            },
        )

    private suspend fun command(
        organizationId:String,proposalId:String,action:String,operationId:String,
        installationId:String,
    ):FinanceApPostingReceiptDto {
        require(action=="post" || action=="request-posting-approval")
        val op=uuid(operationId)
        return api.request(
            method=HttpMethod.Post,
            path=route(organizationId,proposalId)+"/"+action,
            options=HiltechRequestOptions(
                installationId=installationId,idempotencyKey=op,
            ),
            requestBody=json.encodeToString(
                FinanceApJournalOperationDto.serializer(),
                FinanceApJournalOperationDto(op),
            ),
            decode={
                json.decodeFromString(FinanceApPostingReceiptDto.serializer(),it)
            },
        )
    }

    private fun route(org:String,id:String)=
        "/v1/finance/organizations/"+uuid(org)+
            "/ap-journal-proposals/"+uuid(id)

    private fun uuid(value:String):String {
        require(Regex(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$",
        ).matches(value)) { "Valid UUID required." }
        return value
    }
}
