# Typography & Bidirectional Layout Research

Status: RESEARCHING / NOT FROZEN
Research date: 2026-09-18

## Product reality
HILTECH will mix:
- Arabic
- English
- project/client names
- IDs
- asset tags
- serial numbers
- IP addresses
- dates/times
- percentages
- currencies
- measurements
- table-heavy financial/warehouse data

Therefore typography and bidi behavior are functional architecture, not branding decoration.

## Baseline requirements

### Arabic layout
- Arabic screen structure can run RTL.
- Embedded Latin/numeric runs remain directionally correct.
- Use logical start/end concepts rather than hard-coded left/right wherever platform allows.
- Breadcrumbs, tables, icons with directional meaning, panels and navigation require RTL variants/testing.

### Numeric data
Need explicit rules for:
- tabular numerals
- money
- decimal separators
- percentages
- dates
- IDs
- IP/MAC addresses
- serial numbers

Do not rely on natural bidi behavior without test cases.

## Typeface candidate research

### IBM Plex Sans Arabic
Why candidate:
- Arabic and Latin within the Plex family.
- Industrial/technical character suitable for HILTECH.
- broad language family.
- strong numeric/technical ecosystem through Plex family.

Status: CANDIDATE, not selected.

Other Arabic/Latin families must be compared during visual research using real HILTECH screens.

## Required specimen set
Every font candidate must be tested with:

1. Mohamed dashboard.
2. Payroll table.
3. PO table.
4. Technician job card.
5. Warehouse asset ID/serial.
6. Mixed Arabic client name + English project code.
7. IP + MAC + Arabic label.
8. Large numbers/currency.
9. Tiny metadata.
10. Dense 20-row table.
11. Error/alert messages.
12. Arabic + English search results.

## Density requirements
Need at least:
- Display / executive number.
- Section title.
- Screen title.
- Body.
- Compact body/table.
- Metadata/caption.
- Monospace or technical treatment for identifiers if required.

## RTL table requirements
- logical start/end pinning.
- numbers intentionally aligned.
- action columns remain discoverable.
- status icons do not reverse when meaning is non-directional.
- arrows/flow icons reverse only when semantics require direction reversal.

## References
- W3C Arabic & Persian Layout Requirements: https://www.w3.org/International/alreq/
- IBM Design Language Typeface: https://www.ibm.com/design/language/typography/typeface/

## Freeze gate
No font is frozen until:
- Arabic native reading review,
- mixed-direction test suite,
- mobile/desktop density test,
- finance/technical numeric test,
- accessibility/size test,
- licensing/distribution review.
