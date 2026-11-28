package com.hiltech.shared.core.finance

import com.hiltech.shared.core.network.HiltechApiClient
import com.hiltech.shared.core.network.HiltechApiException
import com.hiltech.shared.core.network.HiltechRequestOptions
import io.ktor.http.HttpMethod
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class FinancePayrollCapabilityDto(val organizationId: String, val canPrepare: Boolean, val canReview: Boolean, val reviewConfigured: Boolean)
@Serializable
data class FinancePayrollBatchDto(
    val reviewBatchId: String, val runVersionId: String, val periodStart: String, val periodEnd: String,
    val generation: Int, val currency: String, val payableTotal: String, val employeeCount: Int,
    val transferCount: Int, val prepared: Boolean,
)
@Serializable
data class FinancePayrollInstructionDto(
    val instructionId: String, val employeeId: String, val employeeName: String, val employeeCode: String,
    val amount: String, val currency: String, val resultVersion: Long, val outcome: String,
    val resultEventId: String? = null, val resultAction: String? = null, val recordedAt: String? = null,
)
@Serializable
data class FinancePayrollBankLineDto(
    val bankStatementLineId: String, val reference: String, val bookedOn: String,
    val bankName: String, val maskedAccount: String, val amount: String, val currency: String,
)
@Serializable
data class FinancePayrollProposalDto(
    val proposalId: String, val instructionId: String, val employeeName: String, val amount: String, val currency: String,
    val action: String, val reason: String, val state: String, val preparedByName: String, val preparedAt: String,
    val bankReference: String? = null, val bookedOn: String? = null, val bankName: String? = null,
    val evidenceId: String, val canReview: Boolean,
    val canOpenEvidence: Boolean = false, val evidenceViewed: Boolean = false,
    val canCloseStale: Boolean = false,
)
@Serializable
data class FinancePayrollEvidenceDocumentDto(
    val organizationId: String, val proposalId: String, val evidenceId: String, val viewReceiptId: String,
    val fileName: String, val contentType: String, val sizeBytes: Long, val sha256: String,
    val matchedTransactionReference: String, val contentUtf8: String,
)
@Serializable
data class FinancePayrollRunDto(val organizationId: String, val runVersionId: String,
    val instructions: List<FinancePayrollInstructionDto>, val proposals: List<FinancePayrollProposalDto>)
@Serializable
data class FinancePayrollOperationDto(val operationId: String)
@Serializable
data class FinancePayrollPrepareResultDto(
    val operationId: String, val baseResultVersion: Long, val action: String,
    val bankStatementLineId: String? = null, val correctsEventId: String? = null,
    val beneficiaryConfirmed: Boolean = false, val reason: String,
)
@Serializable
data class FinancePayrollReviewResultDto(val operationId: String, val decision: String, val comment: String? = null)
@Serializable
data class FinancePayrollCommandReceiptDto(
    val operationId: String, val organizationId: String, val targetId: String, val state: String,
    val replayed: Boolean, val bankExecutionInitiated: Boolean, val ledgerPosted: Boolean,
)
@Serializable data class FinancePayrollGlPostDto(
    val operationId:String,val entryType:String,val sourceEventId:String?=null,val originalJournalId:String?=null,
    val periodId:String,val debitAccountId:String,val creditAccountId:String,val amount:String,val currency:String,
)
@Serializable data class FinancePayrollGlEventDto(
    val eventId:String,val entryType:String,val employeeName:String,val amount:String,val currency:String,
    val recordedAt:String,val originalJournalId:String?=null,val debitAccountId:String?=null,val debitAccountName:String?=null,
    val creditAccountId:String?=null,val creditAccountName:String?=null,val ready:Boolean,
)
@Serializable data class FinancePayrollGlViewDto(
    val organizationId:String,val runVersionId:String,val period:FinanceDeskPeriodDto?=null,
    val accrualState:String,val gross:String,val currency:String,val offset:Int,val hasMore:Boolean,
    val expenseAccounts:List<FinanceSettlementAccountChoiceDto>,val payableAccounts:List<FinanceSettlementAccountChoiceDto>,
    val hasMoreAccounts:Boolean,val events:List<FinancePayrollGlEventDto>,
)
internal fun payrollGlIntentValid(input:FinancePayrollGlPostDto):Boolean =
    input.entryType in payrollDeskSources &&
    listOfNotNull(input.operationId,input.periodId,input.debitAccountId,input.creditAccountId,input.sourceEventId,input.originalJournalId).all(::financeLookupIdIsUuid) &&
    !input.debitAccountId.equals(input.creditAccountId,true) && settlementCanonicalAmount(input.amount)!=null &&
    input.currency.matches(Regex("[A-Z]{3}")) &&
    (input.entryType=="PAYROLL_ACCRUAL")==(input.sourceEventId==null) &&
    (input.entryType in setOf("PAYROLL_RETURN","PAYROLL_VOID"))==(input.originalJournalId!=null)

@Serializable
data class FinancePayrollIntent(
    val operationId: String, val organizationId: String, val kind: String, val targetId: String, val title: String,
    val result: FinancePayrollPrepareResultDto? = null, val review: FinancePayrollReviewResultDto? = null,
    val ledgerPost:FinancePayrollGlPostDto?=null,
)

class FinancePayrollDisbursementApiClient(private val api: HiltechApiClient,
    private val json: Json = Json { ignoreUnknownKeys = true; explicitNulls = false }) {
    suspend fun capabilities(org: String, installation: String): FinancePayrollCapabilityDto = api.request(
        HttpMethod.Get, path(org) + "/capabilities", HiltechRequestOptions(installationId = installation),
        decode = { json.decodeFromString<FinancePayrollCapabilityDto>(it).also { value -> check(sameId(value.organizationId, org)) } },
    )
    suspend fun batches(org: String, installation: String): List<FinancePayrollBatchDto> = api.request(
        HttpMethod.Get, path(org) + "/batches", HiltechRequestOptions(installationId = installation),
        decode = { json.decodeFromString(ListSerializer(FinancePayrollBatchDto.serializer()), it) },
    )
    suspend fun run(org: String, run: String, installation: String): FinancePayrollRunDto = api.request(
        HttpMethod.Get, path(org) + "/runs/" + uuid(run), HiltechRequestOptions(installationId = installation),
        decode = { json.decodeFromString<FinancePayrollRunDto>(it).also { value ->
            check(sameId(value.organizationId, org) && sameId(value.runVersionId, run))
        } },
    )
    suspend fun bankLines(org: String, instruction: String, action: String, installation: String): List<FinancePayrollBankLineDto> {
        require(action in setOf("SUCCESS", "RETURNED"))
        return api.request(HttpMethod.Get, path(org) + "/instructions/" + uuid(instruction) + "/bank-lines?action=" + action,
            HiltechRequestOptions(installationId = installation),
            decode = { json.decodeFromString(ListSerializer(FinancePayrollBankLineDto.serializer()), it) })
    }
    suspend fun evidenceContent(org: String, proposal: String, expectedEvidence: String, installation: String): FinancePayrollEvidenceDocumentDto = api.request(
        HttpMethod.Post, path(org) + "/result-proposals/" + uuid(proposal) + "/evidence-content", HiltechRequestOptions(installationId = installation),
        decode = { json.decodeFromString<FinancePayrollEvidenceDocumentDto>(it).also { source ->
            check(sameId(source.organizationId, org) && sameId(source.proposalId, proposal) && sameId(source.evidenceId, expectedEvidence))
            uuid(source.viewReceiptId)
            check(source.contentType == "text/csv" && source.sizeBytes in 1..(2 * 1024 * 1024).toLong())
            check(source.contentUtf8.encodeToByteArray().size.toLong() == source.sizeBytes && Regex("^[a-f0-9]{64}$").matches(source.sha256))
            check(source.matchedTransactionReference.length in 5..180 && source.fileName.isNotBlank() && source.fileName.none { it.code < 32 || it.code == 127 })
        } },
    )
    suspend fun ledger(org:String,run:String,offset:Int,installation:String):FinancePayrollGlViewDto {
        require(offset in 0..1_000_000)
        return api.request(HttpMethod.Get,path(org)+"/runs/"+uuid(run)+"/ledger?offset="+offset,HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString<FinancePayrollGlViewDto>(it).also { v ->
                check(sameId(v.organizationId,org) && sameId(v.runVersionId,run) && v.offset==offset &&
                    v.currency.matches(Regex("[A-Z]{3}")) && v.gross.matches(Regex("[0-9]+([.][0-9]+)?")) &&
                    v.accrualState in setOf("POSTED","NOT_PREPARED","CORRECTION_REQUIRED","NO_AMOUNT","TREATMENT_REQUIRED","READY") &&
                    (v.period==null || (financeLookupIdIsUuid(v.period.id) && v.period.state=="OPEN" && financeValidCalendarDate(v.period.dateStart) && financeValidCalendarDate(v.period.dateEnd) && v.period.dateStart<=v.period.dateEnd)) &&
                    v.events.size<=50 && v.events.map{e->e.eventId.lowercase()}.distinct().size==v.events.size &&
                    v.events.all{e-> e.entryType in payrollDeskSources-"PAYROLL_ACCRUAL" && financeLookupIdIsUuid(e.eventId) &&
                        listOfNotNull(e.originalJournalId,e.debitAccountId,e.creditAccountId).all(::financeLookupIdIsUuid) &&
                        e.currency==v.currency && settlementCanonicalAmount(e.amount)!=null && e.employeeName.isNotBlank() &&
                        (!e.ready || (e.debitAccountId!=null && e.creditAccountId!=null && !e.debitAccountId.equals(e.creditAccountId,true) &&
                            !e.debitAccountName.isNullOrBlank() && !e.creditAccountName.isNullOrBlank() &&
                            (e.entryType in setOf("PAYROLL_RETURN","PAYROLL_VOID"))==(e.originalJournalId!=null)))} &&
                    listOf(v.expenseAccounts to "PAYROLL_EXPENSE",v.payableAccounts to "PAYROLL_PAYABLE").all{(rows,kind)->
                        rows.size<=100 && rows.map{a->a.id.lowercase()}.distinct().size==rows.size &&
                        rows.all{a->financeLookupIdIsUuid(a.id) && a.name.isNotBlank() && a.accountKind==kind}})
            }})
    }
    suspend fun apply(intent: FinancePayrollIntent, installation: String): FinancePayrollCommandReceiptDto {
        val (suffix, body, expectedState) = when (intent.kind) {
            "POST_GL" -> {
                val post=requireNotNull(intent.ledgerPost)
                require(sameId(intent.operationId,post.operationId) && payrollGlIntentValid(post))
                Triple("/runs/"+uuid(intent.targetId)+"/ledger",json.encodeToString(FinancePayrollGlPostDto.serializer(),post),"POSTED")
            }
            "PREPARE_BATCH" -> Triple("/batches/${uuid(intent.targetId)}/prepare",
                json.encodeToString(FinancePayrollOperationDto.serializer(), FinancePayrollOperationDto(intent.operationId)), "PREPARED")
            "PROPOSE_RESULT" -> {
                val result = requireNotNull(intent.result)
                require(sameId(intent.operationId, result.operationId))
                Triple("/instructions/${uuid(intent.targetId)}/result-proposals", json.encodeToString(FinancePayrollPrepareResultDto.serializer(), result), "SUBMITTED_FOR_REVIEW")
            }
            "REVIEW_RESULT" -> {
                val review = requireNotNull(intent.review)
                require(sameId(intent.operationId, review.operationId) && review.decision in setOf("APPROVE", "REJECT", "CLOSE_STALE"))
                Triple("/result-proposals/${uuid(intent.targetId)}/review", json.encodeToString(FinancePayrollReviewResultDto.serializer(), review),
                    when (review.decision) { "APPROVE" -> "RECORDED"; "CLOSE_STALE" -> "SUPERSEDED"; else -> "REJECTED" })
            }
            else -> error("Unknown Finance payroll command")
        }
        return api.request(HttpMethod.Post, path(intent.organizationId) + suffix,
            HiltechRequestOptions(installationId = installation, idempotencyKey = uuid(intent.operationId)), requestBody = body,
            decode = { json.decodeFromString<FinancePayrollCommandReceiptDto>(it).also { receipt ->
                check(sameId(receipt.operationId, intent.operationId) && sameId(receipt.organizationId, intent.organizationId))
                uuid(receipt.targetId)
                check(receipt.state == expectedState && !receipt.bankExecutionInitiated && receipt.ledgerPosted==(intent.kind=="POST_GL"))
                if (intent.kind == "REVIEW_RESULT") check(sameId(receipt.targetId, intent.targetId))
            } })
    }
    private fun path(org: String) = "/v1/finance/payroll/organizations/${uuid(org)}/disbursements"
    private fun sameId(a: String, b: String) = uuid(a).equals(uuid(b), ignoreCase = true)
    private fun uuid(value: String): String {
        require(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(value))
        return value
    }
}

data class FinancePayrollFailureDisposition(val unknown: Boolean, val rejected: Boolean)
fun financePayrollFailureDisposition(previouslyUnknown: Boolean, failure: Throwable): FinancePayrollFailureDisposition {
    val api = failure as? HiltechApiException
    if (api?.code in setOf("REAUTH_REQUIRED", "UNAUTHENTICATED")) return FinancePayrollFailureDisposition(previouslyUnknown, false)
    val rejected = api != null && api.httpStatus?.let { it in 400..499 } == true && api.envelope?.code == api.code &&
        !api.retryable && api.code != "IDEMPOTENCY_KEY_REUSED"
    val terminal = rejected && api?.httpStatus == 409 && api.code == "OPERATION_PREVIOUSLY_FAILED"
    return FinancePayrollFailureDisposition(!terminal && (previouslyUnknown || !rejected), rejected && (!previouslyUnknown || terminal))
}
