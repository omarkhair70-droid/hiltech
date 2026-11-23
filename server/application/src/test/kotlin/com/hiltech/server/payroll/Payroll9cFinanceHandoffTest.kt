package com.hiltech.server.payroll

import com.hiltech.server.platform.ProductApiException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

class Payroll9cFinanceHandoffTest {
    private val org = UUID.randomUUID()
    private val period = UUID.randomUUID()
    private val runId = UUID.randomUUID()
    private val releaseId = UUID.randomUUID()
    private val operationId = UUID.randomUUID()
    private val actor = UUID.randomUUID()
    private val first = UUID.randomUUID()
    private val second = UUID.randomUUID()

    private fun line(
        employee: UUID,
        net: String,
    ): Payroll9bRunLineDto = Payroll9bRunLineDto(
        employeeId = employee.toString(),
        employeeCode = "SYNTH_" + employee.toString().take(5),
        displayName = "Synthetic Staff",
        snapshotId = UUID.randomUUID().toString(),
        currency = "EGP",
        gross = net,
        deductions = "0",
        reimbursements = "0",
        net = net,
        sourceFingerprint = "b".repeat(64),
    )

    private val one = line(first, "100.00")
    private val two = line(second, "75.00")
    private fun run(
        lines: List<Payroll9bRunLineDto> = listOf(one, two),
        net: String = "175.00",
    ) = Payroll9bRunDto(
        runVersionId = runId.toString(), organizationId = org.toString(),
        payrollPeriodId = period.toString(), generation = 1,
        state = "DRAFT", currency = "EGP",
        gross = net, deductions = "0", reimbursements = "0",
        net = net, sourceFingerprint = "a".repeat(64),
        lines = lines,
    )

    private fun manifest(current: Payroll9bRunDto = run()) =
        PayrollFinanceReviewManifestPolicy.fromRun(
            current, releaseId, "PAYROLL_REVIEW_TEST", 1, operationId, actor,
        )

    private fun receipt(
        manifest: PayrollFinanceReviewManifest,
    ) = PayrollFinanceReviewReceipt(
        organizationId = org,
        runVersionId = runId,
        releaseId = releaseId,
        operationId = operationId,
        financeReviewBatchId = UUID.randomUUID(),
        manifestSha256 = manifest.manifestSha256,
        currency = "EGP",
        payableTotal = manifest.payableTotal,
        lineCount = manifest.lines.size,
        positiveTransferCount = manifest.positiveTransferCount,
        state = "REVIEW_REQUIRED",
        replayed = false,
    )

    @Test
    fun manifestIncludesEveryEmployeeAndDeterministicTotalInFinanceReviewOnly() {
        val a = manifest()
        val b = manifest(run(lines = listOf(two, one)))
        assertEquals(a, b)
        assertEquals("175.00", a.payableTotal.toPlainString())
        assertEquals(2, a.positiveTransferCount)
        assertEquals(2, a.lines.size)
        assertEquals(64, a.manifestSha256.length)
        assertTrue(a.lines.all { it.requiresTransfer })
        PayrollFinanceReviewManifestPolicy.validateReceipt(a, receipt(a))
        val replay = receipt(a).copy(replayed = true)
        PayrollFinanceReviewManifestPolicy.validateReceipt(a, replay)
    }

    @Test
    fun aZeroNetEmployeeRemainsInReviewButNeverCreatesATransferCandidate() {
        val zero = line(second, "0.00")
        val result = manifest(run(lines = listOf(one, zero), net = "100.00"))
        assertEquals(2, result.lines.size)
        assertEquals(1, result.positiveTransferCount)
        assertFalse(result.lines.single { it.employeeId == second }.requiresTransfer)
    }

    @Test
    fun missingDuplicateOrChangedEmployeeLinesAndWrongTotalsFailClosed() {
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(one), net = "175.00"))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(one, one), net = "200.00"))
        }
        // Text spelling differs, but the two lines refer to one UUID.
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(
                one,
                one.copy(
                    employeeId = one.employeeId.uppercase(),
                    snapshotId = UUID.randomUUID().toString(),
                ),
            ), net = "200.00"))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(
                one,
                two.copy(snapshotId = one.snapshotId.uppercase()),
            )))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(
                one, two.copy(snapshotId = "not-a-uuid"),
            )))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(one, two.copy(currency = "USD"))))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(one, two.copy(net = "-1"))))
        }
        assertThrows<ProductApiException> {
            manifest(run(lines = listOf(one, two), net = "176.00"))
        }
        val zeroNet = manifest(
            run(lines = listOf(one.copy(net = "0"), two.copy(net = "0")), net = "0"),
        )
        assertEquals(0, zeroNet.positiveTransferCount)
        assertEquals("0", zeroNet.payableTotal.stripTrailingZeros().toPlainString())
        assertTrue(zeroNet.lines.all { !it.requiresTransfer })
        assertThrows<ProductApiException> {
            manifest(run().copy(state = "RELEASED"))
        }
        assertThrows<ProductApiException> {
            manifest(run().copy(sourceFingerprint = "forged"))
        }
    }

    @Test
    fun sourceAmountAndOperationChangesProduceDifferentFinanceManifest() {
        val original = manifest()
        val changed = manifest(run(lines = listOf(one, two.copy(net = "76.00")), net = "176.00"))
        assertNotEquals(original.manifestSha256, changed.manifestSha256)
        val differentKey = PayrollFinanceReviewManifestPolicy.fromRun(
            run(), releaseId, "PAYROLL_REVIEW_TEST", 1, UUID.randomUUID(), actor,
        )
        assertNotEquals(original.manifestSha256, differentKey.manifestSha256)
        val differentRelease = PayrollFinanceReviewManifestPolicy.fromRun(
            run(), UUID.randomUUID(), "PAYROLL_REVIEW_TEST", 1, operationId, actor,
        )
        assertNotEquals(original.manifestSha256, differentRelease.manifestSha256)
    }

    @Test
    fun FinanceCannotReportPaidOrAnUnverifiedMismatchedReviewReceipt() {
        val a = manifest()
        val good = receipt(a)
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(state = "PAID"),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(bankExecutionAvailable = true),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(operationId = UUID.randomUUID()),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(manifestSha256 = "0".repeat(64)),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(payableTotal = BigDecimal("175.01")),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(positiveTransferCount = 1),
            )
        }
        assertThrows<ProductApiException> {
            PayrollFinanceReviewManifestPolicy.validateReceipt(
                a, good.copy(financeReviewBatchId = UUID(0L, 0L)),
            )
        }
    }
}
