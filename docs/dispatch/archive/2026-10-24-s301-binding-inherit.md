# ERP-S301-BINDING-INHERIT-20261024

Base SHA: `8b1dbc6a4e04936dc2b07c592ffc1610ae869aae`.
Worktree: `C:/Users/Steve/cretas-s309-opening-packaging-20261024`.
Owner: `/root/s305_explicit_plan_lock`; Root alone owns dispatch/integration/release; original QA alone owns browser/device/canonical CSV.

Actual normal quality-manager UI left binding overrides empty. Persisted empty string caused effective standard to be empty instead of BOOLEAN item true. Original evidence: private execution/s301-standards-only-original-qa-20261024/binding-inheritance-observation.json. Preserve ERP-S3-QC-BINDING-INHERIT-001 and original FAIL. Same-cause source inspection found binding DTO, dynamic template, evaluation, and new inspection snapshot use the same null-only fallback.

Exclusive five files:
1. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/QualityCheckItemServiceImpl.java`
2. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/impl/ProcessingServiceImpl.java`
3. `backend/java/cretas-api/src/main/java/com/cretas/aims/service/quality/QualityStandardValueResolver.java` (new small shared blank-inherits helper)
4. `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/QualityCheckItemBindingTenantContractTest.java`
5. `backend/java/cretas-api/src/test/java/com/cretas/aims/service/impl/ProcessingServiceImplDynamicQualityStandardContractTest.java`

Treat null/empty/whitespace override as inherit, preserve explicit nonblank values including false/0 without trimming meaningful content. Existing persisted blanks must work without migration or SQL rewrites. Use the same helper at DTO/template/evaluation/new snapshot points. Preserve historical snapshots, standard fingerprint guards, tenant/permissions/required/version controls. No Entity/Repository/query, workflow, security, stock, frontend or existing S309-owner files.

Acceptance: affected two test classes via Maven with matching source globs; retain existing assertions and prove BOOLEAN true inheritance for persisted blank, explicit false override, current template/evaluation/new snapshot consistency, old snapshot unchanged. Share this worktree but serialize all Maven/target operations after S309 Owner release. No commit/push/PR/release by subagent. Root freezes both fixes together, runs necessary final checks/official build, manual merge and clean exact-main official release; original QA verifies actual UI and independent DB. No full scene acceptance inferred from source/tests/configuration.

## Integration handoff

Source Owner released the five-file scope. Only string effective-value resolution changes; numeric, required, sampling and the raw standard revision fingerprint remain unchanged. The final test explicitly rejects the stale whitespace-override form after switching to null, then obtains a fresh revision before submitting. Earlier v2 targeted tests passed 12/0/0/0, but preceded the final fingerprint restoration and fixture correction: they are not final-source proof. Final verification is assigned to Root's combined official eight-class build, including real PostgreSQL, followed by the required full CI gate.

Private receipts are under `execution/coordination/s301-binding-inherit-20261024/`, including `receipt-v3-final-source.json`. This archive hands implementation scope to Root for integration and records pending verification; it does not sign off deployment or S3-01/S3-03. Original QA retains the real inheritance failure until actual published UI/independent readback passes.
