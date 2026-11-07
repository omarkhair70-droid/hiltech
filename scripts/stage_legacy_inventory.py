"""Stage selected XLSX rows through the Warehouse API with an explicit source range."""

import argparse
import getpass
import hashlib
import io
import json
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime
from pathlib import Path


SHEET_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
REL_NS = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
MAX_SOURCE = 24 * 1024 * 1024
MAX_XML = 32 * 1024 * 1024


def xml_part(archive, name):
    if archive.getinfo(name).file_size > MAX_XML:
        raise ValueError(f"Workbook XML part exceeds the intake limit: {name}")
    content = archive.read(name)
    if re.search(rb"<!\s*(DOCTYPE|ENTITY)", content.replace(b"\x00", b""), re.IGNORECASE):
        raise ValueError("Workbook XML must not contain document type or entity declarations.")
    return ET.fromstring(content)


def cell_value(cell, strings):
    value = cell.findtext(SHEET_NS + "v")
    kind = cell.get("t")
    formula = cell.findtext(SHEET_NS + "f")
    if kind == "s" and value is not None:
        index = int(value)
        if not 0 <= index < len(strings):
            raise ValueError("Invalid shared-string index in source cell.")
        value = strings[index]
    elif kind == "inlineStr":
        value = "".join(node.text or "" for node in cell.iter(SHEET_NS + "t"))
    if formula is not None or kind == "e":
        return {"formula": formula, "cached": value, "type": kind}
    return value


def read_rows(source, sheet_name, header_row, last_row):
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Workbook has duplicate ZIP entries.")
        workbook = xml_part(archive, "xl/workbook.xml")
        sheets = [sheet for sheet in workbook.iter(SHEET_NS + "sheet") if sheet.get("name") == sheet_name]
        if len(sheets) != 1:
            raise ValueError("The selected source sheet is unavailable.")
        relation_id = sheets[0].get(REL_NS + "id")
        relations = xml_part(archive, "xl/_rels/workbook.xml.rels")
        targets = [
            rel.get("Target", "") for rel in relations
            if rel.get("Id") == relation_id
            and rel.get("Type", "").endswith("/worksheet")
            and rel.get("TargetMode") != "External"
        ]
        if len(targets) != 1:
            raise ValueError("The selected worksheet has no unique internal data part.")
        target = targets[0]
        part = target.lstrip("/") if target.startswith("/") else f"xl/{target}"
        if not re.fullmatch(r"xl/worksheets/[\w.-]+\.xml", part):
            raise ValueError("Unsupported worksheet part path.")
        strings = []
        if "xl/sharedStrings.xml" in names:
            strings = [
                "".join(node.text or "" for node in item.iter(SHEET_NS + "t"))
                for item in xml_part(archive, "xl/sharedStrings.xml").iter(SHEET_NS + "si")
            ]
        source_rows = {}
        for row in xml_part(archive, part).iter(SHEET_NS + "row"):
            number = int(row.attrib["r"])
            if not header_row <= number <= last_row:
                continue
            if number in source_rows:
                raise ValueError(f"Duplicate source row {number}.")
            cells = {}
            seen = set()
            for cell in row.findall(SHEET_NS + "c"):
                address = re.fullmatch(r"([A-Z]{1,3})([1-9][0-9]*)", cell.attrib["r"])
                if address is None or int(address[2]) != number or address[1] in seen:
                    raise ValueError(f"Invalid or duplicate cell address in source row {number}.")
                seen.add(address[1])
                if number == header_row and cell.get("t") not in {"s", "inlineStr", "str"}:
                    if cell.find(SHEET_NS + "v") is not None:
                        raise ValueError("Source column headers must be text cells.")
                value = cell_value(cell, strings)
                if value is not None:
                    cells[address[1]] = value
            source_rows[number] = cells

    headers = source_rows.pop(header_row, {})
    if not headers or any(not isinstance(label, str) or not label.strip() for label in headers.values()):
        raise ValueError("Select a header row with non-empty, plain-text labels.")
    normalized = {label.strip().casefold() for label in headers.values()}
    if len(normalized) != len(headers):
        raise ValueError("Source column labels must be unique.")
    result = []
    for number, cells in sorted(source_rows.items()):
        if not cells:
            continue
        if cells.keys() - headers.keys():
            raise ValueError(f"Source row {number} contains values without a column label.")
        payload = json.dumps({headers[column]: value for column, value in cells.items()}, ensure_ascii=False)
        if len(payload.encode("utf-8")) > 131072:
            raise ValueError(f"Source row {number} exceeds the API payload limit.")
        result.append((number, payload))
    return result


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, newurl):
        return None


def post(base_url, path, token, device_id, payload):
    request = urllib.request.Request(
        base_url + path,
        method="POST",
        data=json.dumps(payload).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "Authorization": "Bearer " + token,
            "X-Device-Installation-Id": device_id,
            "X-Correlation-Id": str(uuid.uuid4()),
            "Idempotency-Key": payload["operationId"],
        },
    )
    try:
        with urllib.request.build_opener(NoRedirect).open(request, timeout=30) as response:
            return json.load(response)
    except urllib.error.HTTPError as exc:
        raise ValueError(f"The API returned HTTP {exc.code} for operation {payload['operationId']}.") from None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("--sheet", required=True)
    parser.add_argument("--header-row", required=True, type=int)
    parser.add_argument("--last-row", required=True, type=int)
    parser.add_argument("--api-url", help="HTTPS HILTECH base URL. Omit for a local intake check.")
    parser.add_argument("--organization-id", type=uuid.UUID)
    parser.add_argument("--device-id", type=uuid.UUID)
    parser.add_argument("--operation-id", type=uuid.UUID)
    parser.add_argument("--occurred-at", help="ISO-8601 timestamp with offset. Reuse it when retrying.")
    args = parser.parse_args()
    if not 0 < args.header_row < args.last_row <= 1048576 or args.last_row - args.header_row > 10000:
        parser.error("Select a range of 1 to 10,000 data rows after the header.")
    if args.workbook.suffix.lower() != ".xlsx":
        parser.error("Only XLSX workbooks are accepted.")
    try:
        with args.workbook.open("rb") as stream:
            source = stream.read(MAX_SOURCE + 1)
        if len(source) > MAX_SOURCE:
            raise ValueError("The workbook exceeds the intake size limit.")
        digest = hashlib.sha256(source).hexdigest()
        rows = read_rows(source, args.sheet, args.header_row, args.last_row)
        if not rows:
            raise ValueError("No data rows in the selected source range.")
        print(f"Source SHA-256 {digest}. Selected {len(rows)} rows.", flush=True)
        if args.api_url is None:
            return
        url = urllib.parse.urlsplit(args.api_url)
        if url.scheme != "https" or not url.hostname or url.username or url.password or url.query or url.fragment:
            raise ValueError("Provide an HTTPS base URL without user information, query or fragment.")
        if not all((args.organization_id, args.device_id, args.operation_id, args.occurred_at)):
            raise ValueError("Staging requires the organization, device, operation ID and original timestamp.")
        if datetime.fromisoformat(args.occurred_at.replace("Z", "+00:00")).utcoffset() is None:
            raise ValueError("The timestamp must include an offset.")
        if not sys.stdin.isatty():
            raise ValueError("Send mode requires an interactive terminal for the access token.")
        token = getpass.getpass("Current authorized HILTECH access token: ")
        if not token:
            raise ValueError("An access token is required.")
        base_url = args.api_url.rstrip("/")
        device_id = str(args.device_id)
        batch = post(base_url, "/v1/inventory-imports", token, device_id, {
            "operationId": str(args.operation_id),
            "organizationId": str(args.organization_id),
            "sourceFileName": args.workbook.name,
            "sourceSha256": digest,
            "sourceType": "LEGACY_EXCEL",
            "clientOccurredAt": args.occurred_at,
        })
        batch_id = uuid.UUID(batch["batchId"])
        print(f"Batch {batch_id}.", flush=True)
        for number, payload in rows:
            operation_id = uuid.uuid5(args.operation_id, f"{args.sheet}:{number}")
            post(base_url, f"/v1/inventory-imports/{batch_id}/rows", token, device_id, {
                "operationId": str(operation_id),
                "sourceSheet": args.sheet,
                "sourceRowNumber": number,
                "rawPayloadJson": payload,
                "candidateClass": "UNCLASSIFIED",
                "clientOccurredAt": args.occurred_at,
            })
        print(f"Staged {len(rows)} rows for review.")
    except (ValueError, KeyError, OSError, urllib.error.URLError, zipfile.BadZipFile, ET.ParseError) as exc:
        parser.exit(1, f"Intake stopped. {exc}\n")


if __name__ == "__main__":
    main()
