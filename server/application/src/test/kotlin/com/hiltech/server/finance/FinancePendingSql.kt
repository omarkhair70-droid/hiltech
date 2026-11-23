package com.hiltech.server.finance

import java.nio.file.Files
import java.nio.file.Path

/**
 * Original finance suites executed pending scripts explicitly on a migrated
 * V0080 database. After integration's V0081-V0096, Flyway owns those scripts.
 * Keep each test's constraints and fixtures, never execute the DDL twice.
 * Any byte drift between its pending source and numbered release migration
 * is a hard test error, not a silent test skip.
 */
object FinancePendingSql {
 private val versions = mapOf(
   "ap_ledger_posting" to "0081",
   "gl_period_close" to "0082",
   "ap_journal_reversal" to "0083",
   "ar_invoice_candidate" to "0084",
   "ar_issued_invoice" to "0085",
   "ar_journal_posting" to "0086",
   "bank_statement_intake" to "0087",
   "ap_ar_cash_allocation" to "0088",
   "cashbox_vouchers" to "0089",
   "employee_funding" to "0090",
   "employee_settlement" to "0091",
   "expense_reimbursement" to "0092",
   "ap_ar_source_balance" to "0093",
   "bank_reconciliation_exceptions" to "0094",
   "project_finance_sources" to "0095",
   "cashbox_count" to "0096",
 )
 fun read(path:Path):String {
   val contents=Files.readString(path)
   val file=path.fileName.toString()
   if(!file.startsWith("pending_") || !file.endsWith(".sql")) return contents
   val base=file.removePrefix("pending_").removeSuffix(".sql")
   val version=versions[base] ?: return contents
   val migrations=Path.of(System.getenv("HILTECH_MIGRATIONS_PATH")
     ?: "database/migrations")
   val numbered=migrations.resolve("V${version}__phase8_${base}.sql")
   if(!Files.exists(numbered)) return contents
   check(Files.readString(numbered)==contents) {
     "Finance migration differs from its immutable numbered release source: $file"
   }
   return "SELECT 1"
 }
}