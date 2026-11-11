import io
import hashlib
import json
import tempfile
import unittest
import uuid
import zipfile
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch

from stage_legacy_inventory import compare_batch, main, preview_text, read_rows


def workbook(data_row, worksheet_prefix=""):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr("xl/workbook.xml", """
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"
                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                <sheets><sheet name="أكسسوارات" r:id="rId1" /></sheets>
            </workbook>
        """)
        archive.writestr("xl/_rels/workbook.xml.rels", """
            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                <Relationship Id="rId1" Target="worksheets/sheet1.xml"
                    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" />
            </Relationships>
        """)
        archive.writestr("xl/sharedStrings.xml", """
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <si><t>الرقم التسلسلي</t></si><si><t>LOCAL</t></si>
            </sst>
        """)
        archive.writestr("xl/worksheets/sheet1.xml", worksheet_prefix + f"""
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
                <sheetData>
                    <row r="4">
                        <c r="A4" t="s"><v>0</v></c>
                        <c r="B4" t="inlineStr"><is><t>العدد</t></is></c>
                        <c r="C4" t="inlineStr"><is><t>السعر الإجمالي</t></is></c>
                    </row>
                    <row r="7">{data_row}</row>
                    <row r="8"><c r="A8"><v>999</v></c></row>
                </sheetData>
            </worksheet>
        """)
    return buffer.getvalue()


class LegacyInventoryIntakeTest(unittest.TestCase):
    def test_preserves_physical_row_labels_zero_and_formula_evidence(self):
        data = workbook("""
            <c r="A7" t="s"><v>1</v></c>
            <c r="B7"><v>0</v></c>
            <c r="C7"><f>B7*1</f><v>0</v></c>
        """)
        [(number, payload)] = read_rows(data, "أكسسوارات", 4, 7)
        self.assertEqual(7, number)
        self.assertEqual({
            "الرقم التسلسلي": "LOCAL",
            "العدد": "0",
            "السعر الإجمالي": {"formula": "B7*1", "cached": "0", "type": None},
        }, json.loads(payload))

    def test_rejects_unlabelled_cells_before_any_staging(self):
        data = workbook('<c r="D7"><v>1</v></c>')
        with self.assertRaisesRegex(ValueError, "without a column label"):
            read_rows(data, "أكسسوارات", 4, 7)

    def test_rejects_entity_declarations(self):
        data = workbook('<c r="B7"><v>0</v></c>', '<!DOCTYPE worksheet [<!ENTITY data "test">]>')
        with self.assertRaisesRegex(ValueError, "entity declarations"):
            read_rows(data, "أكسسوارات", 4, 7)

    def test_compare_checks_exact_row_scope_and_redacted_source_values(self):
        source = workbook('<c r="A7" t="s"><v>1</v></c><c r="B7"><v>0</v></c><c r="C7"><v>123</v></c>')
        rows = read_rows(source, "أكسسوارات", 4, 7)
        batch_id = uuid.uuid4()
        org_id = uuid.uuid4()
        digest = hashlib.sha256(source).hexdigest()
        staged = {
            "batchId": str(batch_id),
            "sourceSheet": "أكسسوارات",
            "sourceRowNumber": 7,
            "sourcePreview": {"الرقم التسلسلي": "LOCAL", "العدد": "0"},
        }
        batch = {
            "batchId": str(batch_id),
            "organizationId": str(org_id),
            "sourceSha256": digest,
            "sourceType": "LEGACY_EXCEL",
            "rows": [staged],
        }

        def compare():
            return compare_batch(batch, batch_id, org_id, digest, "أكسسوارات", rows)

        self.assertEqual(2, compare())
        staged["sourcePreview"]["العدد"] = "1"
        with self.assertRaisesRegex(ValueError, "visible source cell differs at row 7"):
            compare()
        staged["sourceRowNumber"] = 8
        with self.assertRaisesRegex(ValueError, r"Missing \[7\]. Unexpected \[8\]"):
            compare()
        batch["sourceSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "SHA-256 does not match"):
            compare()

    def test_digest_mismatch_exits_before_token_prompt_and_api_request(self):
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "synthetic-accessories.xlsx"
            source.write_bytes(workbook('<c r="B7"><v>0</v></c>'))
            argv = [
                "stage_legacy_inventory.py", str(source), "--sheet", "أكسسوارات",
                "--header-row", "4", "--last-row", "7", "--expected-sha256", "0" * 64,
                "--api-url", "https://warehouse.example.invalid", "--compare-batch", str(uuid.uuid4()),
            ]
            with patch("sys.argv", argv), patch("stage_legacy_inventory.getpass.getpass") as token, \
                    patch("stage_legacy_inventory.request_json") as request, patch("sys.stderr", io.StringIO()):
                with self.assertRaises(SystemExit) as failure:
                    main()
                self.assertEqual(1, failure.exception.code)
                token.assert_not_called()
                request.assert_not_called()

    def test_operator_comparison_uses_the_read_route_and_does_not_print_cell_data(self):
        batch_id, org_id, device_id = (str(uuid.uuid4()) for _ in range(3))
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "synthetic-accessories.xlsx"
            contents = workbook('<c r="A7" t="s"><v>1</v></c>')
            source.write_bytes(contents)
            argv = [
                "stage_legacy_inventory.py", str(source), "--sheet", "أكسسوارات",
                "--header-row", "4", "--last-row", "7", "--api-url", "https://warehouse.example.invalid",
                "--compare-batch", batch_id, "--organization-id", org_id, "--device-id", device_id,
            ]
            output = io.StringIO()
            with patch("sys.argv", argv), patch("sys.stdin.isatty", return_value=True), \
                    patch("stage_legacy_inventory.getpass.getpass", return_value="synthetic-token"), \
                    patch("stage_legacy_inventory.request_json") as request, redirect_stdout(output):
                request.return_value = {
                    "batchId": batch_id, "organizationId": org_id, "sourceType": "LEGACY_EXCEL",
                    "sourceSha256": hashlib.sha256(contents).hexdigest(),
                    "rows": [{
                        "batchId": batch_id, "sourceSheet": "أكسسوارات", "sourceRowNumber": 7,
                        "sourcePreview": {"الرقم التسلسلي": "LOCAL"},
                    }],
                }
                main()
                request.assert_called_once_with(
                    "https://warehouse.example.invalid", f"/v1/inventory-imports/{batch_id}",
                    "synthetic-token", device_id,
                )
            self.assertIn("Compared 1 visible source cells", output.getvalue())
            self.assertNotIn("LOCAL", output.getvalue())

    def test_preview_limit_preserves_unicode_character_boundary(self):
        self.assertEqual("x" * 511, preview_text("x" * 511 + "📦"))
        self.assertEqual("x" * 510 + "📦", preview_text("x" * 510 + "📦y"))


if __name__ == "__main__":
    unittest.main()
