# ERP S0 retained approval version clone

Task `ERP-S0-TRANSFER-CLONE-20261020`; Base `6556994ed9658e719ca6786a6548e63f8998f0f7`; Owner root, DIRECT. Worktree `C:/Users/Steve/cretas-erp-w3-acceptance-20261019` reopened from merged1037 on new branch `codex/erp-transfer-clone-20261020`.

Real QA found the published F006 transfer workflow with zero running instances exposes only save-in-place. Clone is rendered only for `readOnlyWorkflow`, which is false when count0. User-authorized pilot requires a separate definition/version preserving original graph/history; no transfer approval or stock move occurred.

Scope: only approval-workflow-editor/index.vue and components/__tests__/runningInstanceLock.spec.ts, plus root dispatch bookkeeping. Make clone available for published/archived workflows, independently of the edit lock. Draft must not clone an unsaved/incomplete state. Existing in-flight lock and no-force behavior remain covered.

Acceptance: mounted same component, actual zero-count load and clone click creates a separate draft through existing API; original definition is never updated. Preserve positive/unknown in-flight locks, draft negative and force=false tests. Target Vitest for this file and related editor contracts, then one necessary final Web suite/type/build/CI. Same-cause search bounded to clone visibility and running-instance edit locks in workflow editor/system approval contexts.

No backend, permissions, migration, scripts, production configuration or business writes. Do not deploy or start a second QA writer while existing post1037 QA is active. After local/CI/manual merge and official Web-only release, resume same QA owner at SAFE to clone/publish bounded F006 route and reuse retained transfer draft. This source fix is not completed business acceptance.

## Frozen source review

Status `review`; source scope freezes in this final candidate commit and releases on its merge. This archive entry does not claim CI, merge, release or business acceptance complete. Parent ERP-STAGES remains active; root retains PR/CI/release responsibility.

One production change: clone visibility uses published/archived status instead of the in-flight editing lock. Existing clone handler, API, graph persistence, authorization and force=false behavior are reused.

Same-cause search `cloneWorkflowDraft|克隆为新版本|cloneCurrentAsDraft` across workflow/system/platform editor found this sole production clone-button visibility branch. Other hits are handler/import/help text/tests, not sibling hidden buttons. Existing read-only lock still guards save when running-count positive or unknown; these branches are safe and tested.

Mounted pre-fix test result:2 failed/6 passed, exactly the zero-running published and archived clone cases. After the one-line fix:4 target files/16 tests passed, including actual clone-button click, separate draft readback, no original update, draft negative, positive/unknown in-flight states, force=false, editor props and graph-persistence contracts. Private raw evidence is retained under execution/transfer-clone-20261020. These are component tests; actual production UI/data acceptance remains with the original QA owner.

## Same-page layout increment before release

Base of increment `3820a56a8ef9a06ead198ae838b64a10bb2c71cd`; PR1039 initial source/build is historical and has not deployed. Actual QA screenshot shows the advanced-canvas banner overlays the canvas toolbar/delete-selected action. The same absolute placement affects the graph-to-step return bar. Root is the sole DIRECT writer for this increment.

Expanded exact scope: the same index.vue and runningInstanceLock.spec.ts, plus `components/__tests__/stepListFallbackBanner.spec.ts` and `lib/__tests__/stepListToGraphEscape.source.spec.ts`. Keep notices in normal rows of the column editor, above the horizontal palette/canvas/properties area, so they neither consume canvas width nor intercept controls. Existing static absolute-position assertion encoded the old workaround for shrinking canvas width; replace it with semantic protection of the new placement, while retaining real mounted parent/graph-mode behavior and a fresh negative pre-fix result. Do not weaken graph integrity or edit-lock assertions. No backend, permissions, business data or release-script change.

Source is not refrozen until the actual layout/component regression and final Web gates complete. Preserve the initial clone-only build/CI separately; only the final current-head artifact can be released after QA SAFE.

Final layout validation: mounted banner parent placement, normal accepted step-to-graph confirmation and source layout constraints. Initial test harness omitted acceptance of the normal message-box confirmation; that instrumentation failure is preserved separately and fixed without changing product behavior. With the corrected harness, exact prior3820a56 source yields3 failures/9 passes; restored final source yields7 files/32 tests passed. Root restored source in a finally block and retained raw mutation/restored logs. Same-cause sibling return bar was moved with the advanced-canvas warning, both into independent column-editor rows; no warning text or graph integrity check was removed. Final Web CI/build and production toolbar geometry/click checks remain pending.
