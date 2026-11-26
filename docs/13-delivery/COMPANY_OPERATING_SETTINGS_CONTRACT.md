# Company operating settings: purchase order numbering

`COMPANY_SETTINGS_ADMIN` is a bounded operating responsibility. Its current supported action is maintaining company purchase order numbering. It does not grant a financial signature, invoice-numbering power, independent approval, employee administration, or the right to delegate responsibilities. Assignment uses the existing explicit per-key administration ceiling and current employee/account checks.

The company administration workspace admits a settings administrator independently of People and policy administration. Its operating-settings panel shows the active source, an example format, and named dated change history. It supports Arabic/English, an explicit reason, recent sign-in confirmation, a frozen saved command, and the same-operation retry when a response is lost.

## Source and history

The effective prefix comes from the newest immutable company revision. Before the first company revision, a valid existing deployment prefix remains a visible read-only fallback. Blank or invalid legacy configuration is shown as needing setup; it is not replaced with an invented company prefix. Adopting the existing fallback is an explicit audited action.

Configuration commands pin the expected revision, source and prior prefix, take the company lock, and recheck current settings authority before writing. Every revision records the exact actor, reason, operation, prior source and timestamp. The database preserves history and independently rejects an unauthorized writer, rewritten history, or skipped revision.

The company purchase order serial counter remains unchanged. Configuration cannot reset it or alter already issued codes. Each new issued order records its exact company numbering revision where present. The database checks that the issued code uses the active company prefix and full serial. Existing orders retain their original codes and their original source lineage.

## Issuer journey

Current issuers can read numbering readiness without holding company settings authority. An unconfigured prefix prevents a new issue and provides an in-app setup direction; it does not hide the whole issuing workbench or block lifecycle reads for an existing order. Issuance rechecks the numbering source inside the company transaction before allocating a serial.

The native issuer chooses a named scoped destination and confirms one frozen online command. A lost response retains the exact candidate, destination and operation. A completed receipt remains visible after its queue item disappears. Replaying a completed issue after an explicit prefix rotation returns the originally issued number and does not allocate another serial.

Independent purchase review, source currentness, issuer authority, delivery scope, and supplier dispatch/receiving/payment separation remain part of their existing source-owned workflows.

## Verification boundary

Service and client tests cover setup authority, explicit fallback adoption, versions, concurrent changes, history, recent authentication, receipt matching and retry identity. Disposable PostgreSQL contracts cover actual source constraints and preservation of the serial counter. These contracts and synthetic native composition evidence are separate from target-device and live-server user acceptance.
