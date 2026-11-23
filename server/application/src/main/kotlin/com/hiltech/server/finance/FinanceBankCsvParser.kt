package com.hiltech.server.finance

import com.hiltech.server.platform.HiltechRequestContext
import com.hiltech.server.platform.IdempotencyKeyContract
import com.hiltech.server.platform.ProductApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.util.Base64
import java.util.UUID

data class FinanceBankCsvIntakePrepareRequest(
    val operationId:UUID,
    val bankAccountId:UUID,
    val statementEvidenceId:UUID,
    val statementSha256:String,
    val periodStart:LocalDate,
    val periodEnd:LocalDate,
    val openingBalance:String,
    val closingBalance:String,
    val csvUtf8Base64:String,
)
data class FinanceBankParsedCsv(
    val sourceSha256:String,
    val lines:List<FinanceBankIntakeLineRequest>,
)

/**
 * Bank-neutral CANONICAL export, not an arbitrary provider's undocumented CSV.
 * The signed/finalized Evidence owner authenticates the file separately.
 *
 * File bytes must match the independently uploaded Evidence SHA-256 exactly.
 * A reader cannot edit a row after company review without changing the hash.
 */
object FinanceBankCsvParser {
    const val MAX_BYTES=2*1024*1024
    private const val HEADER=
        "external_transaction_id,booked_on,flow,currency,amount"
    private val transactionId=Regex("^[A-Za-z0-9][A-Za-z0-9._:/-]{4,179}$")
    private val amountPattern=Regex("^[0-9]+(\\.[0-9]{1,6})?$")
    private val digestPattern=Regex("^[a-f0-9]{64}$")
    fun decodeAndValidate(
        base64:String,expectedDocumentSha256:String,
    ):FinanceBankParsedCsv {
        require(base64.length<=((MAX_BYTES+2)/3)*4+4){
            "BANK_CSV_TOO_LARGE"
        }
        require(expectedDocumentSha256.matches(digestPattern)){
            "INVALID_BANK_DOCUMENT_SHA256"
        }
        val data=runCatching{Base64.getDecoder().decode(base64)}
            .getOrElse{throw IllegalArgumentException("INVALID_BANK_CSV_BASE64")}
        require(data.isNotEmpty() && data.size<=MAX_BYTES) {
            "BANK_CSV_TOO_LARGE_OR_EMPTY"
        }
        val sourceHash=sha(data)
        require(sourceHash==expectedDocumentSha256){
            "UPLOADED_BANK_EVIDENCE_SHA256_MISMATCH"
        }
        val decoder=StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val content=runCatching{decoder.decode(ByteBuffer.wrap(data)).toString()}
            .getOrElse{throw IllegalArgumentException("INVALID_BANK_CSV_UTF8")}
            .removePrefix("\uFEFF")
        require(!content.contains('\u0000')){"INVALID_BANK_CSV_CONTROL"}
        val raw=content.split('\n').toMutableList()
        if(raw.lastOrNull()=="")raw.removeAt(raw.lastIndex)
        require(raw.size in 2..10001){"INVALID_BANK_CSV_ROW_COUNT"}
        val header=raw[0].removeSuffix("\r")
        require(header==HEADER){"BANK_CSV_CANONICAL_HEADER_REQUIRED"}
        val ids=HashSet<String>()
        val lines=raw.drop(1).map { originalRow->
            val row=originalRow.removeSuffix("\r")
            require(row.isNotBlank() && !row.contains('\r') &&
                !row.contains('"') && !row.contains('\u0000')){
                "INVALID_BANK_CSV_ROW"
            }
            val fields=row.split(',')
            require(fields.size==5){"INVALID_BANK_CSV_COLUMNS"}
            val externalId=fields[0]
            val date=runCatching{LocalDate.parse(fields[1])}
                .getOrElse{throw IllegalArgumentException("INVALID_BANK_BOOKING_DATE")}
            require(transactionId.matches(externalId) && ids.add(externalId)){
                "INVALID_OR_DUPLICATE_BANK_TRANSACTION_ID"
            }
            require(fields[2] in setOf("DEBIT","CREDIT")){
                "INVALID_BANK_DIRECTION"
            }
            require(Regex("^[A-Z]{3}$").matches(fields[3])){
                "INVALID_BANK_CURRENCY"
            }
            require(amountPattern.matches(fields[4])){
                "INVALID_BANK_AMOUNT"
            }
            val amount=BigDecimal(fields[4])
            require(amount.signum()>0 && amount.precision()<=24 &&
                amount.stripTrailingZeros().scale()<=6){
                "INVALID_BANK_AMOUNT_PRECISION"
            }
            FinanceBankIntakeLineRequest(
                externalTransactionId=externalId,
                bookedOn=date,
                flow=FinanceBankFlow.valueOf(fields[2]),
                amount=amount.toPlainString(),
                originalLineSha256=sha(row.toByteArray(Charsets.UTF_8)),
                currency=fields[3],
            )
        }
        return FinanceBankParsedCsv(sourceHash,lines)
    }
    private fun sha(bytes:ByteArray):String=MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString(""){"%02x".format(it)}
}

/** Only the registered uploaded Evidence bytes are accepted, never guessed CSV. */
@RestController
class FinanceBankCsvIntakeController(
    private val intake:FinanceBankStatementIntakeService,
) {
    @PostMapping("/v1/finance/organizations/{orgId}/bank-statement-intakes/prepare-csv")
    fun prepare(
        http:HttpServletRequest,@PathVariable orgId:UUID,
        @RequestBody body:FinanceBankCsvIntakePrepareRequest,
    ):FinanceBankIntakeReceipt {
        val ctx=HiltechRequestContext.current(http)
        IdempotencyKeyContract.requireMatches(ctx.idempotencyKey,body.operationId)
        val actor=ctx.identityId
            ?.let{runCatching{UUID.fromString(it)}.getOrNull()}
            ?:throw ProductApiException(
                "UNAUTHENTICATED","Finance identity required.",
                HttpStatus.UNAUTHORIZED)
        val verified=runCatching {
            FinanceBankCsvParser.decodeAndValidate(
                body.csvUtf8Base64,body.statementSha256,
            )
        }.getOrElse {
            throw ProductApiException(
                "FINANCE_BANK_CSV_INVALID",
                "The supplied canonical UTF-8 bank file does not match its Evidence source.",
                HttpStatus.BAD_REQUEST)
        }
        return intake.prepare(actor,orgId,
            FinanceBankIntakePrepareRequest(
                operationId=body.operationId,
                bankAccountId=body.bankAccountId,
                statementEvidenceId=body.statementEvidenceId,
                statementSha256=verified.sourceSha256,
                periodStart=body.periodStart,periodEnd=body.periodEnd,
                openingBalance=body.openingBalance,
                closingBalance=body.closingBalance,
                lines=verified.lines,
            ),ctx.correlationId)
    }
}
