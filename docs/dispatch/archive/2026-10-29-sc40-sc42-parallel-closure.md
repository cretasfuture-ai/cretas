# SC40–SC42 F006 parallel acceptance closure

Base: `16f32a750615d6abd82b48fe157cd531ddd4dc2e`. Root owns this archive and
ACTIVE. Implementation scope: canonical read-only harness and documentation;
no product runtime, permission, Entity/Repository, migration or deploy-script
change. Registered scope: [SC40–SC42](2026-10-29-sc40-parallel-scope.md).

## SC40-PARALLEL-UI-20261029

Status: review; source scope released in this final commit. Actual F006 Web
acceptance passed with two fresh independent UI logins, distinct verified
numeric actors and evidence directories. Successful analysis HTTP intervals
overlapped across users; both contexts closed. Existing strict commerce checks
covered trusted source/family/period, unit-specific KPI/fact agreement, rendered
charts, each original-detail context and actual Excel download.

Both lanes also passed controlled client-offline recovery: one exact analysis
request failed with ERR_INTERNET_DISCONNECTED, the UI displayed an explicit
error and no fabricated facts/charts, then real online retry passed the same
strict business/detail/Excel checks. Each expected transport failure remains
in evidence; all additional transport/HTTP/body-read/console/page errors were
zero. Each read-only run had zero business writes and blocked mutation attempts.
The mutation guard and whitelist are unchanged. This is bounded Web concurrency,
not native acceptance or a production load test.

Local final unit/drift: 114 passed. Strict parallel browser fixture: 1 passed.
Canonical browser regression: 12 passed; a bootstrap identity-read failure was
fixed and its existing test separately passed. A wrong recovery-tab expectation
was also fixed; the local fixture now rejects that wrong tab. Both original
tool failures are preserved rather than converted into product PASS.
Final source changes were independently reviewed with no blocking findings.

Private evidence root:
`C:/Users/Steve/my-prototype-logistics/.playwright-mcp/smartbi-complete-20261028/`.
Normal parallel chain: `sc40-parallel-ui-v1/summary.json` (commerce PASS in both
lanes; recovery navigation TOOL_ERROR). Recovery replay:
`sc40-parallel-ui-v2/summary.json` (outer PASS, all scenarios PASS, distinct
actors/HTTP overlap/safety true). Each child directory contains report,
screenshots and downloaded workbook. Frozen source fixture/test logs are in
`C:/Users/Steve/cretas-smartbi-parallel-web-20261029/test-output/sc40/`.

## SC41-RECEIVING-DIAG-20261029

Status: review; investigation scope released. The previous object PUT was
aborted by the private task guard: Playwright did not expose a File upload's
raw request-body buffer, so its length check failed. This was not an OSS policy
or external approval denial. A capture-phase File observation proved the
actual selected PNG's SHA256, 19,291-byte size and MIME before the same exact
granted HTTPS PUT was allowed. No alternate transport or OSS policy change.

The normal F006 Web flow uploaded the marked QA fixture, downloaded the
original object with identical SHA256, registered one attachment and confirmed
the original 1 kg × 10 receipt once. Fresh UI and independent F006-scoped SQL
showed one new 1 kg batch, CONFIRMED receipt and COMPLETED purchase order.
Three F006 business write requests and one object write; other-tenant business
writes zero. No extra receipt was created and no duplicate confirmation ran.
Evidence: `sc41-receiving-success.json`, `sc41-object-preview.png`,
`sc41-inventory-after-confirm.png`, `sc37-receiving-after-1793263247.json`.

## SC42-PREREQUISITES-20261029

Status: review; read-only scope released. No eligible F006 closed period with
nonzero inventory snapshot amount exists: February/March each have 226 closed
snapshot rows but all amounts zero; May–August have no snapshots; September is
pending. No month was closed/reopened, no price invented and the previous
226-row repair was not repeated. Evidence:
`.playwright-mcp/f006-joint-positive-20261028/sc42-period-summary-readonly-1793262470.json`.

The current Web router intentionally routes `quality_inspector` to mobile-only.
This is a missing supported Web-role prerequisite, not permission to widen
global access. Independent quality App acceptance remains deferred with phone.

## Remaining scope and release

Original 22-item ledger: 9 fully passed; 13 remain partly or wholly unverified.
Seven are native continuous-detail/TTL/quality/catalogue/export and unavailable
second-Android/iOS checks. Four combine already-passed Web with deferred native
quality, long-dialogue, concurrency or recovery checks. Two lack the closed-
period and independent quality-Web prerequisites above. Phone remains deferred
by user instruction; Web evidence never substitutes for native acceptance.

The 256-context, 32 MiB total, 8 MiB single-snapshot and 300 s TTL safeguards
remain. Prior real-PostgreSQL quota/reuse/reclamation/owner/tenant tests passed.
The prior full Python run still has its separately recorded expired model
registry audit failure; this batch does not hide or bypass it.

Product fixes were already merged and deployed (Python `95224`, Java/Web
verified shared release `39dc`). This batch changes only test tooling/docs;
there is no product artifact to redeploy. Prior complete service release
receipts are reused. Detailed current state is the private single-source
`scenarios.json`, with derived `remaining22-final-20261029.md`.
