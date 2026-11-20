# Cretas production read-only Playwright harness

This is the canonical harness for evidence-based, read-only acceptance of the
Cretas Web Admin. The MCP entry, Node CLI, and Playwright fixture tests share
the same session cleanup, UI login, mutation guard, evidence schema, and
scenario implementations.

## Safety contract

- Install the mutation guard before the first target-origin navigation.
- Use the real login UI. Authentication is the only default write-shaped
  request that may pass.
- Allow query-only `POST` endpoints only through the exact registry in
  `config/readonly-post-whitelist.js`.
- Abort every other `POST`, `PUT`, `PATCH`, or `DELETE` before it is sent.
- Treat any blocked mutation attempt as a harness failure, even though the
  server did not receive it.
- A successful production run must report `actualBusinessWrites: 0`.
- Never put credentials, authorization headers, cookies, tokens, raw request
  bodies, emails, or phone numbers in evidence.
- Write evidence only below `.playwright-mcp/`, `test-results/`, `test-output/`,
  or `tmp/`.

The repository's task-scoped F006 production-write exception does not apply
inside this harness. Even when the authenticated tenant is F006, this entry
remains strictly zero-write. Run an authorized F006 write through a separate
task-specific UI/API/SQL path; never add its business mutations to the
query-only POST registry.

AI chat is intentionally not in the read-only POST registry. A chat request can
dispatch a tool with side effects even when the visible UI looks like a draft.

## Direct Playwright MCP

Codex Playwright MCP evaluates filename entries in an isolated VM without
`process`, `require`, or dynamic imports. `mcp-entry.js` is therefore a
deterministic, dependency-free bundle generated from the canonical
`core/`/`scenarios/` source graph. `tests/mcp-entry.test.js` fails if it drifts.

The host must place run options on the current MCP `page` as
`page.__cretasReadonlyOptions` through a secure, non-logging injection path.
The entry copies and immediately deletes that temporary property before doing
any work. If the host cannot inject credentials without exposing them in tool
output, use the CLI for the production run; never paste a real password into a
tool call that echoes source.

After secure option injection, run the checked-in entry as a filename. Do not
paste the generated entry source into the tool call.

```text
browser_run_code_unsafe(
  filename="C:\\path\\to\\repo\\scripts\\e2e\\production-readonly\\mcp-entry.js"
)
```

MCP option fields:

```text
username          required for a real run; source from gitignored credentials
password          required for a real run
baseUrl           default https://admin.cretaceousfuture.com
expectedUsername  default f006_admin
expectedFactoryId default F006
evidenceDir       absolute path under a gitignored evidence directory
scenarios         array of scenario IDs
factoryAccessCases required by platform-factory-access-readonly: 2-4 exact {factoryId, username, protectedRole} cases; at least two factories and both protection states
factoryAnalysisSubjectId optional exact product ID from the authorized clarification response; unknown IDs fail closed
factoryAnalysisQuestions required for factory-analysis-question-set-readonly: array of 1–20 question strings
dryRun            true loads/describes only; no navigation or login
```

The MCP entry consumes the page supplied by Codex Playwright MCP. It does not
launch a second browser and does not close the shared MCP session.

The CLI passes the same case list through `E2E_FACTORY_ACCESS_CASES` (JSON).
Use existing active accounts: the ordinary case proves editable permission
controls, and the protected case proves all native comboboxes disabled. Never
change an account role to fit the acceptance fixture. Both cases share the same
clean platform login session and retain the zero-business-write guard.

### Bounded parallel F006 acceptance

When the task explicitly authorizes parallel UI acceptance, set `parallelRuns`
to two or three run-option objects. Each needs its own `username`, `password`,
`expectedUsername`, positive numeric `expectedActorId`, unique safe `runId` and
`evidenceDir`. Credentials still use the secure injection path above. Each lane
gets a fresh context, UI login and finally cleanup within the existing browser;
no storageState or authenticated session is shared. The parent page stays open.

Use `smartbi-commerce-joint-readonly` for normal joint analysis and
`smartbi-parallel-recovery-readonly` for controlled client-offline recovery.
Both require `smartbiCommerceJoint` with independently checked family/KPI
expectations. Recovery uses the factory question tab, checks an explicit error
without fabricated charts/facts, restores connectivity and repeats the strict
business/detail/Excel assertions. It does not interrupt any production server.

Only one exact F006 analysis POST failing with `ERR_INTERNET_DISCONNECTED` may
be classified as the controlled fault in each context. It remains recorded in
`expectedTransportFailures` and, where emitted, `expectedConsoleErrors`.
Additional network, HTTP, console or page errors still fail acceptance.

The outer result is PASS only if all business scenarios pass, all contexts
close, authenticated actor IDs match and differ, and successful analysis HTTP
intervals actually overlap across actors. Read-only business writes and blocked
mutation attempts must both remain zero. This bounded check is separate from
production load testing or native App acceptance.

After changing any bundled module, regenerate the entry:

```powershell
node scripts/e2e/production-readonly/build-mcp-entry.js
```

The local `package.json` only defines the CommonJS module boundary inherited
from `scripts/e2e/package.json`; it declares no dependencies. Runtime packages
continue to come from the repository root.

## Node CLI

Use the repository's Playwright dependency; do not create a separate package or
dependency tree for this harness.

```powershell
node scripts/e2e/production-readonly/cli-runner.mjs --dry-run --production-readonly
node scripts/e2e/production-readonly/cli-runner.mjs `
  --production-readonly `
  --base-url https://admin.cretaceousfuture.com `
  --scenario tenant-isolation,workflow-readonly
```

The CLI always creates and closes an isolated browser context. A non-local base
URL automatically enables production-read-only mode. CLI credentials are read
from `E2E_USERNAME` and `E2E_PASSWORD`; expected identity comes from
`E2E_EXPECTED_USERNAME` and `E2E_FACTORY_ID`. Never echo those variables.

### LIUSHANMEN 黑湖只读验收

生产验收通过上方的 MCP filename 入口运行。通过安全注入把本地
`.env.test` 中的 `TEST_LIUSHANMEN_ADMIN_USER` / `TEST_LIUSHANMEN_ADMIN_PASS`
映射到 `username` / `password`，将 `expectedUsername` 设为同一管理员，
`expectedFactoryId` 设为 `LIUSHANMEN`。可分别选择场景，也可在同一登录会话顺序运行。

```text
scenarios: ["lsm-production-data-analysis-readonly", "lsm-production-cost-readonly", "lsm-smartbi-dashboard-insight-readonly"]
```

本地可先验证 CLI 的场景选择，无需加载凭证。第二条命令用于已启动的本地环境，
从 gitignored `.env.test` 读取管理员凭证。

```powershell
node scripts/e2e/production-readonly/cli-runner.mjs --dry-run --production-readonly --scenario lsm-production-data-analysis-readonly,lsm-production-cost-readonly,lsm-smartbi-dashboard-insight-readonly

$env:E2E_FACTORY_ID = 'LIUSHANMEN'
node --env-file=.env.test scripts/e2e/production-readonly/cli-runner.mjs --headed --base-url http://localhost:5173 --scenario lsm-production-data-analysis-readonly,lsm-production-cost-readonly
```

数据分析、成本和经营驾驶舱场景保留截图、页面状态、控制台错误与 HTTP 4xx/5xx 计数。
这三个场景只为同源网关的 `POST /smartbi-api/api/smartbi/chart-insight`（Python 路由 `/api/smartbi/chart-insight`）增加放行。请求体 `factory_id` 必须与实时登录响应一致，且拒绝 LSMDEMO。此请求允许写入本厂派生洞察缓存或语料，并可消耗本厂 LLM 额度。
通过须有实时登录响应证明租户，并满足 `actualBusinessWrites == 0` 且无被阻断的写入尝试。
工厂问数只允许 `F006` 和 `LIUSHANMEN`。

#### 逐题验证工厂问数

单独选择 `factory-analysis-question-set-readonly`，从仓库根目录运行以下命令。
题目文件支持 UTF-8 JSON 字符串数组，也可直接使用六膳门 Markdown 题表的「问题」列。
题目须有 1–20 道，每题 1–200 个字符。`--questions-file` 优先于
`E2E_FACTORY_ANALYSIS_QUESTIONS_FILE`。Dry run 会验证传入的文件并打印题数。

```powershell
$questions = 'docs/smartbi/liushanmen-blacklake/questions.md'
node scripts/e2e/production-readonly/cli-runner.mjs --dry-run --production-readonly --scenario factory-analysis-question-set-readonly --questions-file "$questions"

$env:E2E_FACTORY_ID = 'LIUSHANMEN'
$env:E2E_FACTORY_ANALYSIS_QUESTIONS_FILE = $questions
node --env-file=.env.test scripts/e2e/production-readonly/cli-runner.mjs --headed --base-url http://localhost:5173 --scenario factory-analysis-question-set-readonly
```

生产验收使用上方的 MCP filename 入口。安全注入 `scenarios: ["factory-analysis-question-set-readonly"]`、
`expectedFactoryId: "LIUSHANMEN"` 和解析后的 `factoryAnalysisQuestions` 数组，再按原有方式注入登录选项。
场景在一次实时 UI 登录后，逐题打开独立的业务数据分析页面并提交。工厂问数页面会去掉题面首尾空白。
该场景只放行当前登录租户的白名单 `factory-analysis` 查询 POST。出现确认卡或被拦的请求时立即停止本批。

每题在 `report.json` 的 `pageEvidence` 中记录题面、脱敏后的完整 `factoryAnalysisAnswer`、
响应来源标识、`cites_blacklake`、HTTP 状态、耗时和截图路径。`cites_blacklake` 依据已选图表或返回事实的结构化来源，
单凭候选目录中有黑湖或回答中提到黑湖不会算作引用。HTTP 错误、超时、澄清和未完成回答记为 `failed`，后续题照常执行。
CLI 全部通过退出 `0`，有回答但本批失败退出 `1`，全部题均未取得完成回答退出 `2`。安全失败优先退出 `1`。
MCP 返回相同的 `exitCode`。使用这些结果与黑湖标准答案逐题对账。

## Validation

```powershell
node --test scripts/e2e/production-readonly/tests/unit.test.js
npx playwright test scripts/e2e/production-readonly/tests/fixture.spec.js --workers=1
node --check scripts/e2e/production-readonly/cli-runner.mjs
node --check scripts/e2e/production-readonly/mcp-entry.js
```

Run unit tests and the local fixture before any production smoke. For the final
production smoke, select tenant isolation plus one pure-GET page scenario. Stop
immediately if the guard reports a blocked mutation attempt or an actual write.

## Adding a scenario

1. Add a module below `scenarios/` and register it in `core/run-suite.js`.
2. Prefer `runReadOnlyPageScenario` for navigation and evidence deltas.
3. Do not click save, create, submit, publish, apply, pay, delete, or report-work
   controls. Opening a dialog is allowed only when cancellation itself is known
   to be client-side and the guard remains installed.
4. Add response extractors only for non-sensitive business fields.
5. Add a local fixture assertion and run the drift/unit gate.

## Retired standalone runners

The reusable contracts from `tests/qa-r1-vue-smoke/run-smoke.mjs` and
`tests/e2e-yield-mixed-sku/readonly-bom-workflow-contract.mjs` now live here.
Their standalone runners and duplicate dependency package were removed. The
SmartBI JSON/screenshots remain archived evidence.

The former `prod-business-flow-audit.mjs` was renamed to
`nonprod-business-flow-audit.mjs`. It is intentionally outside this read-only
harness because it creates plans, reports process rows, and settles production.
It has no default target or factory, rejects known production hosts, and
requires both `E2E_TARGET_ENV=test` and
`E2E_ALLOW_BUSINESS_WRITES=NON_PRODUCTION_ONLY` before it can start.

## Result vocabulary

Scenario results are `PASS`, `CONFIRMED_DEFECT`, `PARTIAL_DEFECT`,
`UNVERIFIED`, or `TOOL_ERROR`. Root-cause classes are `frontend`, `backend`,
`data`, `config`, `tool`, or `none`. HTTP 200 alone is never sufficient for a
PASS; the scenario must also retain page and business-field evidence.
