# Platform factory assembly integration

Task: PLATFORM-ASSEMBLY-20261015; owner Codex root. Includes completed
PLATFORM-ASSEMBLY-PG/TAXONOMY/FORMAL-20261015 slices. Target repository:
cretasfuture-ai/cretas, additive integration over main. Source assembly/main
cf184bfd29a048793ff2cce23308618080615ff6 is reference only; no repository merge.

## Isolation and behavior

- Java and Python assembly are disabled by default. UI entry requires backend
  capability and an explicit platform namespace with raw super_admin role.
- Dedicated /api/platform/factory-assembly endpoints recheck the live platform
  account; factory users and equal numeric IDs cannot become platform actors.
- Python generation additionally requires an internal service credential and a
  valid signed platform JWT. Existing mobile conversation and old AI initializer
  remain separate. The existing main LLM router supplies generation/extraction.
- Interview evidence is confirmed before canonical save, validation, simulation,
  publication and explicit application. None automatically initializes factories.
- Typed formal adapters record platform actor identity before the first save;
  legacy factory-user wrappers remain available. No invented users/FK fallback.
- Configuration publication does not claim Workflow publication. Workflow stays
  blocked without its BOM and explicit formal publication prerequisites.
- Shared prerequisites are separate PRs: #989 (unit constraint), #991 (platform
  login namespace), #993 (authoritative packaging aliases).

## Nonproduction real UI evidence

Native Windows Java + PostgreSQL 17 + Vite + loopback Python; no production,
Docker or WSL operation. Existing source environment reused and preserved.

Actual platform login, interview extraction, confirmation, structured generation,
canonical save/validate/simulate/publish, explicit application and page reload
passed. Initial missing-storage rejection rolled back all selected business rows.
After explicit UI choices (flour dry, water normal), version 2 applied successfully:

| Readback | Result |
| --- | --- |
| Product | 1, 原味面包, unit 个, legacy created_by NULL, platform actor |
| Materials | 2, kg, storage dry/normal, legacy created_by NULL, platform actor |
| Processes / product process links | 2 / 2, output units kg and 个 |
| Warehouses / form templates | 3 / 3 |
| Apply receipt | 15 business plan items, state APPLIED after reload |
| Module configs / settings / users / BOM / Workflow | 0 / 0 / 0 / 0 / 0 |

Existing factory: all 504 table fingerprints unchanged between the post-scheduler
baseline and application. Against the original baseline, 503 unchanged; one
ordinary main daily report appeared at 20:00 from the unchanged existing scheduler.
The original comparison failure is retained, not erased or counted as unchanged.

Actual factory login: capabilities 403, application POST 403, assembly page 404;
new factory's 504 table fingerprints unchanged before/after those denied requests.
Default-off capability/route evidence is retained separately. Real Java-to-Python
generation after internal-header hardening returned 200 with structured schemas
and suggestedData; that request generated preview only and did not apply writes.

Private evidence directory (not committed):
C:/Users/Steve/Documents/assembly-integration-assessment-20261015.
Screenshots, DB readbacks, fingerprint comparisons and executable readback
assertions are retained there. Tracked report contains no credentials/session data.

## Validation and limits

Relevant Java access, actor, replay, adapter, storage, generation and phase tests;
Python 13 tests; frontend auth/route/draft/reply tests passed during development.
Python namespace mutation changed actual source, caused an assertion failure,
then exact-source restoration returned green. Shared alias mutation is in #993.
Final frozen build and CI execution results are recorded in the integration PR.

Integration gates also exposed source-repository assumptions. Fixes keep the main
ratchets intact: all 70 new soft-delete entities declare their own delete SQL;
26 versioned tombstones atomically increment lock_version and check row count.
Real PostgreSQL tests cover physical-row retention, query invisibility, stale
delete rejection and two-transaction stale-update rejection after deletion.
The final real PostgreSQL class passed 8 tests. Removing the version increment
from the actual execution entity made the reverse-concurrency test fail with
1 assertion failure and 0 errors; exact source restoration passed that test.
All 150 entity-declared unique keys were independently checked against the native
Flyway V63 indexes: every matching key is full, not partial. Identity/tenant keys,
request idempotency keys and membership keys inside an immutable draft version
are intentionally retained; registration records that decision per key.

Quantity identity uses factory canonical-unit authority. Confirmation metadata,
canonical-code requirements and snapshot digests remain exact comparisons, so
an alias cannot silently change a confirmed replay. An alias simulation test
checks that input 公斤 produces the declared output kg without changing value.
The existing 10 rule compiler scenarios are reused, not rebuilt.

The field-default source scanner now distinguishes actual fields from same-named
builder methods; positive, method-only and duplicate-field controls retain its
fail-closed behavior. Nested receipt serialization/getters/builders preserve their
public JSON contract. New 409 guards carry action hints and new user messages
are Chinese. Web gate trigger coverage includes the exact InterviewDefaults
source it reads; the legacy menu test now includes the gated platform group.

Native Flyway built from main schema baseline 20261101.37 through V62, then
applied V63 on the same owned fixture. No sentinel factory business changes.
Real PostgreSQL-dialect JPA query/context tests passed; V63 nullable binding was
flushed and reloaded in an additional real PostgreSQL test. V42 is byte-identical
to source (SHA256 A475425EF84F799A7A4A6F6B717CA71B2C9A12071A4C89BCC57DABB529A72B6E).
Source V51 is excluded; shared main V58 is retained; final resource packaging
must not contain stale source V51. Flyway out-of-order remains false.

Not verified: production deployment/activation, production rollback, every draft
capability's complete UI lifecycle, or actual Workflow/BOM publication in this
bread scenario. These are not claimed as completed acceptance.

Before production activation, V63 requires a recovery decision: after canonical
publication creates applied_version=NULL, rolling back to old main 095ce82d2f
would hit legacy unboxing paths. Use a validated forward recovery or a separately
verified data/rollback procedure. Default-off integration does not authorize
production activation or existing-factory data changes.
