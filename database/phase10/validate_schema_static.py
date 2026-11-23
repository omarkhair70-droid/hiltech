#!/usr/bin/env python3
"""Fail before PostgreSQL if the unnumbered commercial DDL is truncated or duplicated."""
from pathlib import Path
import re

sql = (Path(__file__).parent / "pending_commercial_schema.sql").read_text()
tables = re.findall(r"^CREATE TABLE (\w+) \(", sql, re.MULTILINE)
views = re.findall(r"^CREATE VIEW (\w+) AS", sql, re.MULTILINE)
functions = re.findall(r"^CREATE FUNCTION (\w+)\(", sql, re.MULTILINE)
triggers = re.findall(r"^CREATE TRIGGER (\w+) ", sql, re.MULTILINE)
expected = {
    "commercial_authority_binding", "commercial_opportunity", "commercial_qualification_record",
    "commercial_tender_revision", "commercial_site_visit", "commercial_scope_version",
    "commercial_boq_version", "commercial_boq_cost_line", "commercial_quote_version",
    "commercial_quote_line", "commercial_contract", "commercial_client_po",
    "commercial_project_handoff",
}
assert set(tables) == expected and len(tables) == len(expected), tables
for kind, values in (("views", views), ("functions", functions), ("triggers", triggers)):
    assert len(values) == len(set(values)), f"duplicate {kind}: {values}"
assert len(functions) == 9 and len(triggers) >= 16
assert sql.count("LANGUAGE plpgsql AS $$") == sql.count("END $$;")
assert "LANGUAGE plpgsql AS $\n" not in sql
assert "END $;" not in sql
assert sql.count("currency char(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$')") >= 3
assert "sealed_at timestamptz" in sql
assert "Cannot append lines to sealed BOQ" in sql
assert "Quote must begin as an unreleased draft" in sql
assert "Canonical signed contract Evidence is not READY" in sql
assert "security_scan_class='SIGNED_DOCUMENT'" in sql
assert "security_scan_class='NATIVE_MEDIA'" in sql
assert "security_scan_class='ARBITRARY_FILE'" not in sql
assert "Canonical client PO Evidence is not READY" in sql
# Every table-dependent field must be inspected only inside its table branch,
# never in a combined AND expression (PostgreSQL can evaluate both operands).
assert "IF TG_TABLE_NAME='commercial_contract' THEN" in sql
assert "ELSIF TG_TABLE_NAME='commercial_client_po' THEN" in sql
assert "TG_TABLE_NAME='commercial_client_po' AND" not in sql
assert "Project handoff requires exact ready award and same client" in sql
assert "commercial_private_boq_cost_summary" in sql
assert "commercial_finance_award_source" in sql
assert "commercial_support_contract_source" in sql
assert "cp.amount=q.total_price AND cp.currency=q.currency" in sql
assert "Tender award requires a canonical won Project" in sql
assert "Commercial opportunity must begin as OPEN" in sql
assert "CREATE TABLE commercial_project_handoff" in sql
assert "V006" not in sql[:300], "Do not use a provisional migration version"
print(f"Commercial SQL static contract PASS: {len(tables)} tables, "
      f"{len(views)} views, {len(functions)} functions, {len(triggers)} triggers")
