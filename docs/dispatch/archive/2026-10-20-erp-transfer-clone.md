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
