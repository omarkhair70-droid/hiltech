# HILTECH isolated Phase7 + Finance 8A-I + Native UX + Payroll9 assembly

Date: 2026-11-23. State: DRAFT / NO-GO for main, company rollout or payroll execution.

- Parent integration PR #200, exact branch head a4856bdc81d56e7d262f3f52c22b388702220c4d, includes latest P7 Android/Windows service entry (source 35a301fab030e2a0fe89d3e5d64215f10312d364), current UX shell (c290aaf20831b1b77d71051380d69bc2f7f72dc8), and previously verified P7+Finance8I integration PR #195 (d616a828d98951e637a5ace3f1c4d005765695e2).
- P9 source was retrieved as 9b35256805ab7b14dd66f6b8daad3f744fdf7b62, from standalone Payroll PR #166; this is a pinned source, not a claim latest owner CI has passed. Existing P9 code was merged cleanly in isolated commit 30105b7f389fb83e9b0a3ca7e91490d6ef301809.
- Only integration-branch provisional SQL names changed, preserving byte-identical main V0001-V0038: P9 V0070 (period) -> V0067, P9 V0071 (draft run) -> V0068, P9 V0072 (release) -> V0069, P9 V0073 (correction) -> V0070. Existing Phase7 V0039-V0064 and Finance V0065-V0066 retained.
- Contiguous unique migrations V0001-V0070 checked programmatically; DatabaseMigrationContractTest updated for 37 post-V0033 migrations; Payroll path filters also updated to renumbered paths.
- No payroll company payroll/tax policy invented. All P9 operation flags OFF, real salary sources/time/HR attestations and Finance payment execution not integrated. Offboarding final-pay only source-readiness and review, not actual paid status. NO bank action, transfer, payroll records or compensation data created.

Required exact merged-head tests:
1. New devops-p7-finance-native-ux-payroll9.yml full server/Modulith/bootJar/disposable PostgreSQL 18/Flyway immutability and Windows/Android/KMP build.
2. Existing Phase9 specialized PostgreSQL negative/security and native tests now running on integration branch.
3. Finance8I targeted review tests still run as a regression on integration branch.
4. Real payroll actor/org/revocation and source version tests plus signed-in native shell and staged OIDC/OpenFGA/Evidence/Finance read paths are separate acceptance gates.

Other downstream phase sources P10/P11 have latest owner-green isolated CI but are NOT included here. Phase13A/B require explicit source-owned read scopes, no sensitive Payroll leakage. Main, company data, production/staging services, backups and host workloads are unchanged by this integration.