package com.hiltech.spike.dense

data class PayrollRow(
    val id: Int,
    val employeeCode: String,
    val employeeName: String,
    val project: String,
    val baseSalary: Long,
    val overtime: Long,
    val deductions: Long,
    val netPay: Long,
    val variance: Long,
    val status: String,
)

data class DenseSummary(
    val rowCount: Int,
    val totalNet: Long,
    val exceptionCount: Int,
    val byProject: Map<String, Int>,
)

fun generatePayrollRows(count: Int): List<PayrollRow> =
    List(count) { index ->
        val base = 6_000L + (index % 190) * 75L
        val overtime = (index % 9) * 125L
        val deductions = (index % 7) * 80L
        val variance = if (index % 37 == 0) 450L + (index % 5) * 100L else 0L

        PayrollRow(
            id = index + 1,
            employeeCode = "EMP-" + (index + 1).toString().padStart(5, '0'),
            employeeName = "Employee " + (index + 1),
            project = "Project " + ('A'.code + (index % 12)).toChar(),
            baseSalary = base,
            overtime = overtime,
            deductions = deductions,
            netPay = base + overtime - deductions,
            variance = variance,
            status = if (variance > 0) "EXCEPTION" else "READY",
        )
    }

fun applyDenseQuery(
    source: List<PayrollRow>,
    query: String,
    exceptionsOnly: Boolean,
    sortVarianceDescending: Boolean,
): List<PayrollRow> {
    val normalized = query.trim().lowercase()

    val filtered = source.asSequence()
        .filter { !exceptionsOnly || it.variance > 0 }
        .filter {
            normalized.isEmpty() ||
                it.employeeCode.lowercase().contains(normalized) ||
                it.employeeName.lowercase().contains(normalized) ||
                it.project.lowercase().contains(normalized)
        }

    return if (sortVarianceDescending) {
        filtered.sortedWith(
            compareByDescending<PayrollRow> { it.variance }
                .thenBy { it.employeeCode },
        ).toList()
    } else {
        filtered.sortedBy { it.employeeCode }.toList()
    }
}

fun summarize(rows: List<PayrollRow>): DenseSummary =
    DenseSummary(
        rowCount = rows.size,
        totalNet = rows.sumOf { it.netPay },
        exceptionCount = rows.count { it.variance > 0 },
        byProject = rows.groupingBy { it.project }.eachCount(),
    )
