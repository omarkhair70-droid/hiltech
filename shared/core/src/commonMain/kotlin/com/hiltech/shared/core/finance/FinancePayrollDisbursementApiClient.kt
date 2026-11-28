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
@Serializable data class FinancePayrollGlAllocationDto(val kind:String,val domain:String,val code:String,val accountId:String,val reference:String)
@Serializable data class FinancePayrollGlGroupDto(val kind:String,val domain:String,val code:String,val amount:String)
@Serializable data class FinancePayrollGlPostDto(
    val operationId:String,val entryType:String,val sourceEventId:String?=null,val originalJournalId:String?=null,
    val periodId:String,val debitAccountId:String,val creditAccountId:String,val amount:String,val currency:String,
    val allocations:List<FinancePayrollGlAllocationDto> = emptyList(),
)
@Serializable data class FinancePayrollGlEventDto(
    val eventId:String,val entryType:String,val employeeName:String,val amount:String,val currency:String,
    val recordedAt:String,val originalJournalId:String?=null,val debitAccountId:String?=null,val debitAccountName:String?=null,
    val creditAccountId:String?=null,val creditAccountName:String?=null,val ready:Boolean,
)
@Serializable data class FinancePayrollGlCorrectionDto(val originalJournalId:String?=null,val clearanceReference:String,
    val reversalLines:List<FinanceDeskJournalLineDto>,val disposition:String="NO_PRIOR_TRANSFER_VERIFIED")
@Serializable data class FinancePayrollGlViewDto(
    val organizationId:String,val runVersionId:String,val period:FinanceDeskPeriodDto?=null,
    val accrualState:String,val gross:String,val currency:String,val offset:Int,val hasMore:Boolean,
    val expenseAccounts:List<FinanceSettlementAccountChoiceDto>,val payableAccounts:List<FinanceSettlementAccountChoiceDto>,
    val hasMoreAccounts:Boolean,val events:List<FinancePayrollGlEventDto>,
    val net:String=gross,val deductions:String="0",val reimbursements:String="0",val groups:List<FinancePayrollGlGroupDto> = emptyList(),
    val deductionAccounts:List<FinanceSettlementAccountChoiceDto> = emptyList(),val reimbursementAccounts:List<FinanceSettlementAccountChoiceDto> = emptyList(),
    val correction:FinancePayrollGlCorrectionDto?=null,
)
internal fun payrollGlIntentValid(input:FinancePayrollGlPostDto):Boolean =
    input.entryType in payrollDeskSources &&
    listOfNotNull(input.operationId,input.periodId,input.debitAccountId,input.creditAccountId,input.sourceEventId,input.originalJournalId).all(::financeLookupIdIsUuid) &&
    !input.debitAccountId.equals(input.creditAccountId,true) && settlementCanonicalAmount(input.amount)!=null &&
    input.currency.matches(Regex("[A-Z]{3}")) &&
    (input.entryType=="PAYROLL_ACCRUAL")==(input.sourceEventId==null) &&
    (input.entryType=="PAYROLL_ACCRUAL" || (input.entryType in setOf("PAYROLL_RETURN","PAYROLL_VOID"))==(input.originalJournalId!=null)) &&
    input.allocations.size<=30 && (input.entryType=="PAYROLL_ACCRUAL" || input.allocations.isEmpty()) &&
    input.allocations.map{Triple(it.kind,it.domain,it.code)}.distinct().size==input.allocations.size &&
    input.allocations.all{it.kind in setOf("DEDUCTION","REIMBURSEMENT") && financeLookupIdIsUuid(it.accountId) &&
        it.domain.matches(Regex("[A-Z][A-Z0-9_-]{0,79}")) && it.code.matches(Regex("[A-Z][A-Z0-9_-]{0,79}")) &&
        it.reference.length in 8..240 && it.reference==it.reference.trim() && it.reference.none{c->c.code<32||c.code==127}}

@Serializable data class FinancePayrollCorrectionCandidateDto(
    val originalRunVersionId:String,val replacementRunVersionId:String,val periodStart:String,val periodEnd:String,
    val originalGeneration:Int,val replacementGeneration:Int,val currency:String,val originalNet:String,val replacementNet:String,
    val eligibleDisposition:String?=null,val alreadyCleared:Boolean,val canRecord:Boolean,
)
@Serializable data class FinancePayrollCorrectionCandidatesDto(val organizationId:String,val records:List<FinancePayrollCorrectionCandidateDto>,val hasMore:Boolean)
@Serializable data class FinancePayrollCorrectionEmployeeReviewDto(
    val employeeId:String,val employeeName:String,val employeeCode:String,
    val originalNet:String,val replacementNet:String,val entitlementDelta:String,
    val postedPayments:String,val postedReturns:String,val recordedNet:String?=null,val differenceFromRecorded:String?=null,
    val evidenceState:String,val pendingCount:Long,val unpostedCount:Long,val voidCount:Long,val noResultCount:Long,
)
@Serializable data class FinancePayrollCorrectionReviewPageDto(val organizationId:String,val originalRunVersionId:String,val replacementRunVersionId:String,
    val currency:String,val offset:Int,val rows:List<FinancePayrollCorrectionEmployeeReviewDto>,val hasMore:Boolean)
internal fun validPayrollCorrectionReview(row:FinancePayrollCorrectionEmployeeReviewDto):Boolean {
    val nonnegative=Regex("[0-9]+([.][0-9]{1,6})?")
    val signed=Regex("-?[0-9]+([.][0-9]{1,6})?")
    return financeLookupIdIsUuid(row.employeeId)&&row.employeeName.isNotBlank()&&row.employeeCode.isNotBlank()&&
        listOf(row.originalNet,row.replacementNet,row.postedPayments,row.postedReturns).all{it.matches(nonnegative)}&&
        row.entitlementDelta.matches(signed)&&listOf(row.pendingCount,row.unpostedCount,row.voidCount,row.noResultCount).all{it>=0}&&
        row.evidenceState in setOf("UNRESOLVED","RECORDED_NET_PAYMENT","RECORDED_RETURN","NO_RECORDED_TRANSFER")&&
        (if(row.evidenceState=="UNRESOLVED")row.recordedNet==null&&row.differenceFromRecorded==null
         else row.pendingCount==0L&&row.unpostedCount==0L&&row.voidCount==0L&&
             row.recordedNet?.matches(nonnegative)==true&&row.differenceFromRecorded?.matches(signed)==true)
}
@Serializable data class FinancePayrollClearanceInputDto(val operationId:String,val originalRunVersionId:String,val replacementRunVersionId:String,
    val disposition:String,val reasonReference:String,val financeReference:String)
internal fun payrollClearanceIntentValid(input:FinancePayrollClearanceInputDto)=
    listOf(input.operationId,input.originalRunVersionId,input.replacementRunVersionId).all(::financeLookupIdIsUuid) &&
    !input.originalRunVersionId.equals(input.replacementRunVersionId,true) &&
    input.disposition in setOf("NO_PRIOR_TRANSFER_VERIFIED","REVERSAL_VERIFIED") &&
    listOf(input.reasonReference,input.financeReference).all{it.length in 8..240 && it==it.trim() && it.none{c->c.code<32||c.code==127}}

@Serializable
data class FinancePayrollIntent(
    val operationId: String, val organizationId: String, val kind: String, val targetId: String, val title: String,
    val result: FinancePayrollPrepareResultDto? = null, val review: FinancePayrollReviewResultDto? = null,
    val ledgerPost:FinancePayrollGlPostDto?=null,val clearance:FinancePayrollClearanceInputDto?=null,
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
                    v.accrualState in setOf("POSTED","REPLACED","NOT_PREPARED","CORRECTION_REQUIRED","NO_AMOUNT","TREATMENT_REQUIRED","READY") &&
                    (v.correction==null || (v.accrualState=="READY" && v.correction.clearanceReference.isNotBlank() && v.correction.disposition in setOf("NO_PRIOR_TRANSFER_VERIFIED","REVERSAL_VERIFIED") &&
                        (v.correction.originalJournalId==null)==v.correction.reversalLines.isEmpty() &&
                        (v.correction.originalJournalId==null || financeLookupIdIsUuid(v.correction.originalJournalId)) &&
                        v.correction.reversalLines.size<=32 &&
                        v.correction.reversalLines.map{it.number}.distinct().size==v.correction.reversalLines.size &&
                        v.correction.reversalLines.all{it.number in 1..32 && it.side in setOf("DEBIT","CREDIT") &&
                            it.accountName.isNotBlank() && settlementCanonicalAmount(it.amount)!=null})) &&
                    listOf(v.net,v.deductions,v.reimbursements).all{it.matches(Regex("[0-9]+([.][0-9]+)?"))} &&
                    v.groups.size<=30 && v.groups.map{Triple(it.kind,it.domain,it.code)}.distinct().size==v.groups.size &&
                    v.groups.all{it.kind in setOf("DEDUCTION","REIMBURSEMENT") && settlementCanonicalAmount(it.amount)!=null &&
                        it.domain.matches(Regex("[A-Z][A-Z0-9_-]{0,79}")) && it.code.matches(Regex("[A-Z][A-Z0-9_-]{0,79}"))} &&
                    listOf(v.deductionAccounts to setOf("PAYROLL_DEDUCTION_PAYABLE","TAX_PAYABLE"),v.reimbursementAccounts to setOf("EXPENSE")).all{(rows,kinds)->
                        rows.size<=100 && rows.map{a->a.id.lowercase()}.distinct().size==rows.size &&
                        rows.all{a->financeLookupIdIsUuid(a.id) && a.name.isNotBlank() && a.accountKind in kinds}} &&
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
    suspend fun corrections(org:String,installation:String):FinancePayrollCorrectionCandidatesDto =
        api.request(HttpMethod.Get,path(org)+"/corrections",HiltechRequestOptions(installationId=installation),
            decode={json.decodeFromString<FinancePayrollCorrectionCandidatesDto>(it).also{v->
                check(sameId(v.organizationId,org)&&v.records.size<=100&&v.records.map{it.replacementRunVersionId.lowercase()}.distinct().size==v.records.size&&v.records.all{r->
                    financeLookupIdIsUuid(r.originalRunVersionId)&&financeLookupIdIsUuid(r.replacementRunVersionId)&&
                    r.originalRunVersionId!=r.replacementRunVersionId&&r.originalGeneration>0&&r.replacementGeneration>r.originalGeneration&&
                    financeValidCalendarDate(r.periodStart)&&financeValidCalendarDate(r.periodEnd)&&r.periodStart<=r.periodEnd&&
                    r.currency.matches(Regex("[A-Z]{3}"))&&listOf(r.originalNet,r.replacementNet).all{a->a.matches(Regex("[0-9]+([.][0-9]+)?"))}&&
                    (r.eligibleDisposition==null||r.eligibleDisposition in setOf("NO_PRIOR_TRANSFER_VERIFIED","REVERSAL_VERIFIED"))&&
                    (!r.canRecord||(r.eligibleDisposition!=null&&!r.alreadyCleared))
                })
            }})
    suspend fun correctionEmployees(org:String,original:String,replacement:String,currency:String,offset:Int,installation:String):FinancePayrollCorrectionReviewPageDto {
        require(listOf(org,original,replacement).all(::financeLookupIdIsUuid)&&offset in 0..1_000_000)
        return api.request(HttpMethod.Get,path(org)+"/corrections/"+replacement+"/employees?offset="+offset,
            HiltechRequestOptions(installationId=installation),decode={json.decodeFromString<FinancePayrollCorrectionReviewPageDto>(it).also{v->
                check(sameId(v.organizationId,org)&&sameId(v.originalRunVersionId,original)&&sameId(v.replacementRunVersionId,replacement)&&
                    v.currency==currency&&v.offset==offset&&v.rows.size<=50&&
                    v.rows.map{it.employeeId.lowercase()}.distinct().size==v.rows.size&&v.rows.all(::validPayrollCorrectionReview)&&
                    (!v.hasMore||v.rows.size==50))
            }})
    }
    suspend fun apply(intent: FinancePayrollIntent, installation: String): FinancePayrollCommandReceiptDto {
        val (suffix, body, expectedState) = when (intent.kind) {
            "CLEAR_CORRECTION" -> {
                val clearance=requireNotNull(intent.clearance)
                require(sameId(clearance.operationId,intent.operationId)&&sameId(clearance.replacementRunVersionId,intent.targetId)&&payrollClearanceIntentValid(clearance))
                Triple("/corrections",json.encodeToString(FinancePayrollClearanceInputDto.serializer(),clearance),"CLEARED")
            }
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
