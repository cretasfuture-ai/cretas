# FOOD-WORKSPACE-READONLY-READINESS-20261106

State review (source frozen, scope released for delivery). Base278742b8efe8e65e96792ada5efeb9d5083609a2. Owner Food root plus artifact_merge_safety. Worktree C:/Users/Steve/cretas-food-dag-closure-20261105; branch codex/workspaces-readonly-readiness-20261106. PR1507 already normally merged/deployed; do not commit again on its merged branch.

The first canonical headed production run used a fresh F006 admin UI login, reached dashboard and took a screenshot while the real application showed its pending sandbox-status shell. ui-login immediately compared the factory display name after the static SPA loader disappeared and reported TOOL_ERROR. The run recorded business writes0 and blocked mutations0. This proves premature tool sampling, not a confirmed product failure; retain evidence. Separately the workspace scenario returns visible state link when a real workspace-link is present, while its positive assertion expects ready. Normalize the actual positive state without accepting loading/error/unavailable/empty/denied or missing links.

Executor may modify only these canonical harness files:

- scripts/e2e/production-readonly/core/ui-login.js: bounded observable factory-shell readiness after verifying authenticated username/factory/role, before sampling body/cross-tenant canary; no sleeps, no identity bypass. Preserve exact factory-name check and existing explicitly allowed mobile-only landing behavior. Keep static appMounted semantics separate. On failure/timeout retain truthful TOOL_ERROR with evidence.
- scripts/e2e/production-readonly/scenarios/factory-management-workspaces-readonly.js: only link-to-ready positive normalization and any necessary strict assertions; no new navigation, requests or mutation policy.
- scripts/e2e/production-readonly/tests/unit.test.js
- scripts/e2e/production-readonly/tests/fixture.spec.js
- scripts/e2e/production-readonly/tests/factory-management-workspaces-readonly.test.js
- scripts/e2e/production-readonly/mcp-entry.js: canonical generator output only

Do not modify product Web/backend/Auth, app-mount.js, guard/whitelist/sanitizer, credentials, other scenarios, source scopes or production data. Do not make factory-name mismatch, login/404 redirect, denied/loading page or missing session pass. New tests must actually exercise delayed pending shell then real factory header, genuine mismatch rejection and real link state versus all bad states. Root owns ACTIVE/archive/Git/PR and production execution.

Acceptance: focused workspace tests plus canonical unit/MCP drift checks, canonical generation, node --check and the existing13 local Playwright fixtures including new delayed-shell coverage. Preserve failed initial production report and screenshots. Executor may run deterministic local tests sequentially in this worktree only, using its existing root dependencies; no production browser/API/credentials. Return files, commands/counts/exits/log paths/SHAs and any blocker; no commit/push/deploy, no grandchildren, do not revert other writers.

## Final root validation and actual production acceptance

Root corrected the last locator ambiguity after the v3 screenshot showed a valid shortage page: business landmarks are selected only inside main.app-content and only when visible, excluding sidebar labels and hidden cached copies. A real local fixture proves shell-only/hidden copies cannot pass and delayed visible business content can. The report now emits primitive per-route and per-landmark arrays so the existing sanitizer depth limit cannot erase the actual readbacks; no sanitizer or guard rule was changed.

Final canonical unit/MCP-drift122/0fail/skip, full local fixture16/0fail, then the evidence-only flattened fields were checked by the changed workspace fixture1/0fail plus unit122 again. All six source files are frozen. Actual fresh headed F006 admin v5 passed tenant isolation and four workspaces/four clicked first business destinations with four real landmark results true and four ready states;13 visible links are not claimed as13 tested destinations. Warehouse v2 passed tenant isolation and four direct routes, each final/403, fresh auth/me200/F006, zero workspace links. Both runs recorded actualBusinessWrites0 and blocked mutations0. Original v1/v2/v3 tool failures and prior successful passes remain preserved. This is canonical harness delivery only; product1507 already formally deployed as2787 with receipt cretas-1793931707-1232, no unchanged product build or deployment is repeated.

Root retains normal PR/required CI/merge ownership. Archived from ACTIVE in this final delivery commit; source owners have stopped editing, other owner rows/scopes are preserved. Evidence lives in owned test-output/food-four-workspaces-production-admin-20261106-v5 and production-warehouse-20261106-v2, with copied reports in Food outputs.
