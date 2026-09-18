package com.hiltech.spike.dense

import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DenseModelTest {
    @Test
    fun generate_and_query_fifty_thousand_rows_without_pathological_cost() {
        lateinit var rows: List<PayrollRow>

        val generateMs = measureTimeMillis {
            rows = generatePayrollRows(50_000)
        }

        lateinit var exceptions: List<PayrollRow>
        val queryMs = measureTimeMillis {
            exceptions = applyDenseQuery(
                source = rows,
                query = "project a",
                exceptionsOnly = true,
                sortVarianceDescending = true,
            )
        }

        lateinit var summary: DenseSummary
        val groupMs = measureTimeMillis {
            summary = summarize(rows)
        }

        println(
            "SPIKE-06 timings generateMs=$generateMs queryMs=$queryMs groupMs=$groupMs " +
                "rows=${rows.size} exceptions=${summary.exceptionCount}",
        )

        assertEquals(50_000, rows.size)
        assertTrue(exceptions.isNotEmpty())
        assertEquals(12, summary.byProject.size)

        assertTrue(generateMs < 5_000, "Generation was pathologically slow: ${generateMs}ms")
        assertTrue(queryMs < 5_000, "Filter/sort was pathologically slow: ${queryMs}ms")
        assertTrue(groupMs < 5_000, "Grouping was pathologically slow: ${groupMs}ms")
    }

    @Test
    fun bulk_selection_of_ten_thousand_rows_is_cheap_and_deterministic() {
        val rows = generatePayrollRows(50_000)
        lateinit var selected: Set<Int>

        val selectionMs = measureTimeMillis {
            selected = rows.asSequence()
                .filter { it.id % 5 == 0 }
                .map { it.id }
                .toSet()
        }

        println("SPIKE-06 bulkSelectionMs=$selectionMs selected=${selected.size}")

        assertEquals(10_000, selected.size)
        assertTrue(selectionMs < 5_000)
    }
}
