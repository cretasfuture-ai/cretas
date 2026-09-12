# R7 workspace cache lifetime

- Task: FIX-WORKSPACE-CACHE-R7-20260912
- Base SHA: b08b707a02125fa17b41a87c6764f5e507021378
- Owner: current visual acceptance task, DIRECT isolated worktree.
- Scope: AppLayout, WorkspaceRouteCache, corresponding layout tests, canonical capacity scenario/registry/tests/bundle.

R6 actual Vue baseline retained eleven dirty tabs but lost the original draft date after returning: the shared KeepAlive maximum of ten evicted an instance while its tab remained open. Each live tab now owns a one-instance KeepAlive. Removing or changing the tab cache generation destroys that cache; closing tabs cannot leave orphan instances. A factory key change clears the entire cache boundary. Reference frames have no registered task tab and render their own route directly.

Validation before release:

- 28 target tests passed, including eleven distinct retained drafts, 25 open/close iterations with only the live instances remaining, explicit invalidation, scope replacement and existing freshness/lifecycle wiring.
- vue-tsc passed; 78 canonical harness tests passed.
- Actual Vue capacity: all eleven different dates retained, ten local copies discarded, original preserved, zero errors/writes/blocked attempts.
- Actual Vue duplicate/ordinal regression passed. Existing reference/focus regression: independent scroll 40, primary width 804, synchronized ratio 0.5, bottom ratio 1; both focus returns and draft retained; zero writes/blocked attempts.
- New opt-in production scenario: workspace-capacity-readonly. Production acceptance follows exact-main deployment; fixtures do not establish production persistence.

Tradeoff: retaining additional dirty tabs necessarily retains their page instances. This preserves the store's existing dirty-tab policy; it does not introduce unbounded closed-cache accumulation. We measured live-instance counts, not heap usage or a latency improvement. Browser reload recovery, all business fields/modules, and backend persistence remain outside this proof; main72 must not be closed as an all-module claim.

Evidence root: `C:/Users/Steve/cretas-visual-fixes-20260912-r3/`; local capacity `.playwright-mcp/workspace-r7-capacity-local/`. Implementation scope releases on merge; deployment/production receipts remain separate facts.
