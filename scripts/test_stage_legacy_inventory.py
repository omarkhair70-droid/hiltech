import io
import json
import unittest
import zipfile

from stage_legacy_inventory import read_rows


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


if __name__ == "__main__":
    unittest.main()
