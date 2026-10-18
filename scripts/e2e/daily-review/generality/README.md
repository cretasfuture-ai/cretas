# P37 factory generality acceptance

The 2026-10-19 continuation is `joint_upgrade.py NON_PRODUCTION_ONLY --stage
prepare|activate|business|complete-costs|metrics|history` (run the stages separately in that
order). It reuses the existing login/checkpoint/HTTP/SQL helpers. `prepare`
submits an old-version plan before switching; `activate` uses the existing
publish-and-activate endpoint, and `business` finishes the old plan then creates
and completes a new plan without manually selecting its workflow. Historical
started/completed transactions and original formal graph/BOM content must stay
unchanged. The existing publisher may rebind PENDING plans with no batches or
settlements; only its returned plan numbers and exact version-pin fields are
allowed to change, and every transition is retained in the private receipt.
`complete-costs` reuses the accepted multi-output workflow with a new plan and
the existing supplemental-cost endpoint. In this local simulation equipment
and other costs are explicitly confirmed zero; positive unallocated costs must
still fail without any business-row change. Old partial-cost samples stay
unchanged, and remain negative controls whose cost metrics must be unknown.
`metrics` checks four factory KPIs, per-SKU output/cost, recomputation and
same-period history under `actual-flow-v3`. No business SQL mutation is allowed.
The private evidence directory is `factory-data-p37/joint-upgrade-20261019`.
Run `node ui-runner.mjs --joint --keep` for the ordinary factory account UI.
The earlier `upgrade` stage below is the historical draft-only baseline; do not
replay it after the continuation's formal activation.

`acceptance.py` is the bounded local API harness for the P37 isolated runtime:

- database: `p37_factory_generality_20261018`
- Java: `127.0.0.1:10072`
- Web: `127.0.0.1:31772`
- owner: `P37-FACTORY-GENERALITY-20261018`
- factory: `FOOD_3101_001`

The runtime must be started by the existing P37 `runtime.py`/`build.py` flow
with `DAILY_REVIEW_PROFILE=P37`. The harness imports the shared support module,
which validates the Java process, private argfile, database target and ordinary
factory administrator login. Credentials remain in the ignored P14 private
fixture directory and are never accepted as command-line values.

Run one resumable stage at a time:

```powershell
$env:DAILY_REVIEW_PROFILE = 'P37'
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage init
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage units
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage multi
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage multi-fresh
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage upgrade
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage output-visibility
python scripts/e2e/daily-review/generality/acceptance.py NON_PRODUCTION_ONLY --stage readback
```

`init` reads real products, customers, warehouses, workflow versions,
activation and the current BOM. `units` creates a new isolated sales fixture
and checks a positive grams-to-box shipment path, FIFO batch allocation,
warehouse confirmation and shipment-cost readback, plus a volume-to-box
negative control whose database snapshot must remain identical. `multi` creates
two dedicated 500g/1000g box products and a real workflow through the
product/process/BOM APIs, obtains output port IDs from the published workflow,
verifies the two terminal BOM drafts share the saved revision and are one
complete package-bearing family, activates that family through the real BOM
and workflow activation endpoints,
then reports two groups with different inputs and allocation ratios through
`outputs[]` and `multiOutputBaseRowId`. It settles and receives four output
batches, verifies the weighted per-SKU pool, total cost conservation and fresh
repeated settlement/receipt requests without duplicate inventory. `multi`
permits the existing cost-recalculation API to recover its pre-fix receipt;
`multi-fresh` creates a separate plan and requires costs at first receipt with
no recovery fallback. `upgrade` reads the original single-product workflow,
adds a distinct kg raw-material node through the existing dictionary API when
needed, saves a draft, and binds a BOM draft to the exact saved revision.
An already-current draft must reject `upgrade-latest` as unavailable. It
proves by row hashes that historical plans, settlements and active BOM rows are
exactly unchanged. It never publishes or activates the old-factory upgrade.
If an interrupted earlier run left the reserved upgrade node with a duplicate
raw SKU, the next run repairs that draft through a fresh normal PUT checkpoint;
it never replays the old draft result blindly.
`readback` captures fresh API and PostgreSQL evidence.
`output-visibility` checks both actual API read paths: eight output batches stay
visible, two runtime containers are excluded, and supported multi-output KPIs
use their real weight snapshots and formal allocated costs under v3. Missing
required facts remain unknown. Reading those endpoints must not change business data.
After both browser modes have passed, `receipt.py NON_PRODUCTION_ONLY` verifies
the frozen product hashes and consolidates selected test reports and completed
business evidence. It performs no login or business write.

Every mutating call has a durable step key in P37's `business-state.json`.
Completed keys are read back rather than replayed; inspect that checkpoint
before retrying a failed or interrupted stage. All paths are factory-relative,
all database access in the harness is read-only, and the source P34 database is
never written.

The manual `repair` stage preserves the corrupted historical revision and
creates a new version through draft, BOM and publication APIs. It is excluded
from `all`; it must not be used to rewrite a historical hash.

The harness intentionally fails closed when the cloned runtime has no suitable
box product, finished-goods stock, or active original BOM. It does not invent
output ports or workflow node kinds. This harness
is an API/database acceptance layer; it does not claim full browser, device or
user acceptance.
