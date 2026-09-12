async (page) => {
  const __modules = {
    "config/readonly-post-whitelist.js": function(module, exports, require) {
'use strict';

const { pathnameOf } = require('../core/url-utils');

const AUTH_REQUESTS = [
  {
    id: 'ui-login',
    method: 'POST',
    path: /^\/api\/mobile\/auth\/unified-login\/?$/,
  },
];

// Every entry must point to a server contract that is query-only. Do not add
// generic prefixes. AI chat is deliberately absent because some modes may
// execute tools with side effects behind a single HTTP request.
const READONLY_POST_WHITELIST = [
  {
    id: 'purchase-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/purchaseOrder\/?$/,
    rationale: 'Query-only list summary for purchase order rows.',
  },
  {
    id: 'sales-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/salesOrder\/?$/,
    rationale: 'Query-only list summary for sales order rows.',
  },
  {
    id: 'production-plan-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/productionPlan\/?$/,
    rationale: 'Query-only list summary for production plan rows.',
  },
  {
    id: 'internal-transfer-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/internalTransfer\/?$/,
    rationale: 'Query-only list summary for internal transfer rows.',
  },
  {
    // 2026-08-28 菜单巡检抓到: /hr/attendance 一加载就发这个 POST, 被写入护栏拦下,
    // 报成 WRITE_ON_LOAD。查下来它是**读性质**的 —— 后端 DemoReadOnlyInterceptor 把
    // `/list-summary/` 整个前缀标注为「列表底部合计/一表一分析上下文 (读性质 POST)」,
    // 与上面四条同一个 ListSummaryController。⇒ 缺的是白名单条目, 不是产品缺陷。
    // ⛔ 仍然逐个实体登记, 不写成 `list-summary/.*` 通配 —— 通配会让将来某个
    //    写形态的 entityType 悄悄溜进只读跑批。
    id: 'attendance-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/attendance\/?$/,
    rationale: 'Query-only list summary for attendance rows.',
  },
  {
    // 2026-08-28 菜单巡检抓到: /smart-bi/data-completeness 一加载就发这个 POST。
    // 逐行看过实现 (backend/python/client_requirement/completeness_calculator.py:333):
    // 它只 compute 并返回, **整个文件 0 处 INSERT/UPDATE/DELETE/commit**。
    // 用 POST 只是因为要带 entity_types 列表, 不是因为它写数据。
    id: 'client-requirement-completeness-compute',
    method: 'POST',
    path: /^\/smartbi-api\/api\/client-requirement\/completeness\/compute\/?$/,
    rationale: 'Query-only completeness computation; the handler performs no writes.',
  },
  {
    id: 'inventory-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/inventory\/?$/,
    rationale: 'Query-only list summary for inventory rows.',
  },
  {
    id: 'shipment-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/shipment\/?$/,
    rationale: 'Query-only list summary for shipment rows.',
  },
  {
    id: 'return-order-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/returnOrder\/?$/,
    rationale: 'Query-only list summary for return-order rows.',
  },
  {
    id: 'quality-inspection-list-summary',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/list-summary\/qualityInspection\/?$/,
    rationale: 'Query-only list summary for quality-inspection rows.',
  },
  {
    id: 'workflow-resolve-by-outputs',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/product-process-workflows\/resolve-by-outputs\/?$/,
    rationale: 'Read-only workflow resolution; backend service is @Transactional(readOnly = true).',
  },
  {
    id: 'attachment-chip-counts',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/attachments\/batch-3chip-counts\/?$/,
    rationale: 'Batch query for attachment counts; no attachment mutation.',
  },
  {
    id: 'indicator-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'INDICATOR_QUERY'
      && typeof body.userInput === 'string'
      && body.userInput.startsWith('查询指标 ')
      && body.skipSlotFilling === true
      && typeof body.sessionId === 'string'
      && body.sessionId.startsWith('indicator-card-')
      && body.context
      && typeof body.context.indicator_code === 'string'
      && body.context.indicator_code.length > 0
      && Object.keys(body.context).length === 1
      && Object.keys(body).length === 5
    ),
    rationale: 'Read-only indicator lookup with an explicit non-tool-writing intent contract.',
  },
  {
    id: 'purchaser-weekly-plan-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'PURCHASER_WEEKLY_PLAN'
      && body.userInput === '下周采购什么?'
      && Object.keys(body).length === 2
    ),
    rationale: 'Purchaser workdesk mount query mapped to the read-only stock_alert tool.',
  },
  {
    id: 'sales-forecast-7day-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'SALES_FORECAST_7DAY'
      && body.userInput === '7 天销售预测'
      && Object.keys(body).length === 2
    ),
    rationale: 'Purchaser workdesk read-only seven-day demand forecast.',
  },
  {
    id: 'price-history-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'PRICE_HISTORY_QUERY'
      && typeof body.userInput === 'string'
      && body.userInput.endsWith(' 价格历史')
      && body.parameters
      && typeof body.parameters.materialTypeId === 'string'
      && body.parameters.materialTypeId.length > 0
      && body.parameters.monthsBack === 6
      && Object.keys(body.parameters).length === 2
      && Object.keys(body).length === 3
    ),
    rationale: 'Purchaser workdesk read-only material price history lookup.',
  },
  {
    id: 'material-today-receiving-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'MATERIAL_TODAY_RECEIVING_QUERY'
      && body.userInput === '今天要收什么货?'
      && Object.keys(body).length === 2
    ),
    rationale: 'Warehouse workdesk read-only receiving list mounted query.',
  },
  {
    id: 'material-disposal-recommendation-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'MATERIAL_DISPOSAL_RECOMMENDATION'
      && body.userInput === '临期物料建议'
      && Object.keys(body).length === 2
    ),
    rationale: 'Warehouse workdesk read-only expiring-material recommendation query.',
  },
  {
    id: 'receive-quality-check-today-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'RECEIVE_QUALITY_CHECK_TODAY'
      && body.userInput === '今日待质检批次'
      && Object.keys(body).length === 2
    ),
    rationale: 'Warehouse workdesk read-only pending-quality-check query.',
  },
  {
    id: 'daily-customer-followup-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'DAILY_CUSTOMER_FOLLOWUP'
      && body.userInput === '今天该跟谁?'
      && Object.keys(body).length === 2
    ),
    rationale: 'Sales workdesk read-only daily customer follow-up query.',
  },
  {
    id: 'shipment-pending-today-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'SPRINT10_SHIPMENT_PENDING_TODAY'
      && body.userInput === '今日 SO 待发'
      && body.parameters
      && Object.keys(body.parameters).length === 0
      && Object.keys(body).length === 3
    ),
    rationale: 'Sales workdesk read-only pending-shipment query mode.',
  },
  {
    id: 'production-demand-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'PRODUCTION_DEMAND_QUERY'
      && body.userInput === '今天要起产什么?'
      && Object.keys(body).length === 2
    ),
    rationale: 'Production-manager workdesk read-only production-demand query.',
  },
  {
    id: 'quality-chief-workdesk-query',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/ai-intents\/execute\/?$/,
    bodyMatches: (body) => Boolean(
      body
      && body.intentCode === 'QUALITY_CHIEF_WORKDESK'
      && body.userInput === '今天哪些批次待放行?'
      && Object.keys(body).length === 2
    ),
    rationale: 'Quality-chief workdesk read-only pending-release query.',
  },
  {
    id: 'smartbi-chart-smart-recommend',
    method: 'POST',
    path: /^\/smartbi-api\/api\/chart\/smart-recommend\/?$/,
    rationale: 'Read-only SmartBI chart recommendation over supplied data.',
  },
  {
    id: 'smartbi-chart-recommend',
    method: 'POST',
    path: /^\/smartbi-api\/api\/chart\/recommend\/?$/,
    rationale: 'Read-only SmartBI heuristic chart recommendation.',
  },
  {
    id: 'smartbi-chart-batch',
    method: 'POST',
    path: /^\/smartbi-api\/api\/chart\/batch\/?$/,
    rationale: 'Read-only SmartBI batch chart configuration build.',
  },
  {
    id: 'smartbi-chart-build',
    method: 'POST',
    path: /^\/smartbi-api\/api\/chart\/build\/?$/,
    rationale: 'Read-only SmartBI chart configuration build.',
  },
  {
    id: 'smartbi-insight-quick-summary',
    method: 'POST',
    path: /^\/smartbi-api\/api\/insight\/quick-summary\/?$/,
    rationale: 'Read-only SmartBI statistical summary.',
  },
  {
    id: 'smartbi-insight-generate',
    method: 'POST',
    path: /^\/smartbi-api\/api\/insight\/generate\/?$/,
    rationale: 'Read-only SmartBI insight generation.',
  },
  {
    id: 'smartbi-insight-generate-stream',
    method: 'POST',
    path: /^\/smartbi-api\/api\/insight\/generate-stream\/?$/,
    rationale: 'Read-only SmartBI streaming insight generation.',
  },
];

function matchesEntry(entry, method, url, body = null) {
  return entry.method === method.toUpperCase()
    && entry.path.test(pathnameOf(url))
    && (!entry.bodyMatches || entry.bodyMatches(body));
}

function findAuthRequest(method, url) {
  return AUTH_REQUESTS.find((entry) => matchesEntry(entry, method, url)) || null;
}

function findReadonlyPost(method, url, entries = READONLY_POST_WHITELIST, body = null) {
  return entries.find((entry) => matchesEntry(entry, method, url, body)) || null;
}

module.exports = {
  AUTH_REQUESTS,
  READONLY_POST_WHITELIST,
  matchesEntry,
  findAuthRequest,
  findReadonlyPost,
};

},
    "config/routes.js": function(module, exports, require) {
'use strict';

const ROUTES = Object.freeze({
  login: '/login',
  dashboard: '/dashboard',
  bom: '/production/bom',
  purchasing: '/procurement/orders',
  finance: '/finance/ar-ap',
  suppliers: '/procurement/suppliers',
  products: '/system/products',
  workflow: '/system/product-processes',
  productionPlans: '/production/plans',
  bomAchievement: '/production/bom-achievement',
  labelQc: '/quality/label-qc',
  restaurantStaffing: '/restaurant/staffing',
  salesOrders: '/sales/orders',
});

module.exports = { ROUTES };

},
    "core/app-mount.js": function(module, exports, require) {
'use strict';

/**
 * 等 web-admin 这个 SPA 真正挂载完 —— 唯一实现。
 *
 * ## 判据不是「等久一点」，是一个可判定的条件
 *
 * `web-admin/index.html` 里写着：
 *
 * ```css
 * #app:not(:empty) + #app-loader { display: none; }
 * ```
 *
 * 也就是 **闪屏不可见 ⟺ `#app` 有内容 ⟺ 应用挂载了**。所以等的是
 * `#app-loader` 变为 hidden，⛔ 不是拍一个超时数字。
 *
 * ## 🔴 为什么需要它（2026-08-27 生产实测）
 *
 * 少了这一步，`goto` 的 `domcontentloaded` 在**闪屏阶段**就满足了（闪屏是
 * `index.html` 的静态 DOM），随后取到的 `body.innerText` 只有
 * 「白垩纪 AI Agent / 正在加载应用…」二十来个字符：
 *
 * - 16 个场景里 **13 个**报 `UNVERIFIED / rootCause: tool`
 * - 唯一 PASS 的是 `tenant-isolation` —— 因为它**不导航**
 * - 修好之后同一条命令重跑：PASS 1 → 8，并且**多暴露出 1 个真缺陷**
 *
 * ⚠️ 同一个缺陷有**两处**：页面导航（`scenarios/_shared.js`）和**登录落地页**
 * （`core/ui-login.js`）。后者更阴 —— 它让旁边那条「跨租户残留」canary
 * 变成恒真式（闪屏里永远不会出现别的租户名）。
 * ⛔ 所以判据收敛到这一个函数，不要复制第三份。
 *
 * ## 返回值要被记录下来
 *
 * `appMounted` / `appMountMs` 必须进证据（**含 PASS 时**）。否则下一个人分不清
 * 「应用没挂载」和「挂载了但页面缺东西」—— 那两件事的下一步动作相反。
 */
const APP_LOADER_SELECTOR = '#app-loader';
const DEFAULT_APP_MOUNT_TIMEOUT_MS = 30_000;

/**
 * @returns {Promise<{ appMounted: boolean, appMountMs: number }>}
 *   `appMounted=false` 表示在超时内闪屏一直没消失。⛔ 不要把它折叠进
 *   「页面缺 landmark」——调用方要用不同的 rootCause 报出来。
 */
async function waitForAppMount(page, timeoutMs = DEFAULT_APP_MOUNT_TIMEOUT_MS) {
  const startedAt = Date.now();
  try {
    // ⚠️ 元素不存在时 Playwright 认为 hidden 已满足 —— 这是想要的：
    //    没有闪屏就没什么可等的（例如纯静态错误页）。页面本身对不对，
    //    仍然由调用方的 landmark 判据来管。
    await page.locator(APP_LOADER_SELECTOR).waitFor({ state: 'hidden', timeout: timeoutMs });
    return { appMounted: true, appMountMs: Date.now() - startedAt };
  } catch {
    return { appMounted: false, appMountMs: Date.now() - startedAt };
  }
}

module.exports = { APP_LOADER_SELECTOR, DEFAULT_APP_MOUNT_TIMEOUT_MS, waitForAppMount };

},
    "core/clean-session.js": function(module, exports, require) {
'use strict';

const { resolveUrl } = require('./url-utils');

async function establishCleanSession(page, baseUrl) {
  const context = page.context();
  await context.clearCookies();
  await context.clearPermissions().catch(() => {});
  for (const worker of context.serviceWorkers()) await worker.close().catch(() => {});
  await page.goto('about:blank');
  const loginUrl = resolveUrl('/login', baseUrl);
  await page.goto(loginUrl, { waitUntil: 'domcontentloaded', timeout: 45_000 });
  await page.evaluate(() => {
    localStorage.clear();
    sessionStorage.clear();
  });
  await page.reload({ waitUntil: 'domcontentloaded', timeout: 45_000 });
  return { loginUrl, cookiesAfterClear: (await context.cookies()).length };
}

module.exports = { establishCleanSession };

},
    "core/console-recorder.js": function(module, exports, require) {
'use strict';

const { redactText } = require('./sanitizer');

const DEFAULT_NOISE_RULES = [
  { id: 'favicon', pattern: /favicon\.ico/i },
  { id: 'resize-observer', pattern: /ResizeObserver loop/i },
  { id: 'browser-extension', pattern: /chrome-extension:\/\//i },
  { id: 'devtools-prompt', pattern: /(?:Vue|React) Devtools/i },
  {
    id: 'capability-gradual-rollout-503',
    pattern: /Failed to load resource: the server responded with a status of 503/i,
    locationPattern: /\/smartbi-api\/api\/smartbi\/capability\/[^/?#]+(?:[?#]|$)/i,
  },
];

function matchesNoiseRule(rule, text, location) {
  return rule.pattern.test(text)
    && (!rule.locationPattern || rule.locationPattern.test(location));
}

function installConsoleRecorder(context, options = {}) {
  const noiseRules = options.noiseRules || DEFAULT_NOISE_RULES;
  const raw = [];
  const accepted = [];
  const listeners = new Map();

  function record(type, text, location = '') {
    const safeText = redactText(text, 500);
    const safeLocation = redactText(location, 300);
    const matched = noiseRules.find((rule) => matchesNoiseRule(rule, safeText, safeLocation));
    const item = {
      type,
      text: safeText,
      location: safeLocation,
      at: new Date().toISOString(),
      filtered: Boolean(matched),
      filterRule: matched?.id || null,
    };
    raw.push(item);
    if (!matched) accepted.push(item);
  }

  function attach(page) {
    if (listeners.has(page)) return;
    const onConsole = (message) => {
      if (!['error', 'warning'].includes(message.type())) return;
      record(message.type(), message.text(), message.location()?.url || '');
    };
    const onPageError = (error) => record('pageerror', error?.message || String(error));
    page.on('console', onConsole);
    page.on('pageerror', onPageError);
    listeners.set(page, { onConsole, onPageError });
  }

  // Playwright surfaces most unhandled rejections as pageerror. This bridge
  // gives them a stable label without storing the rejected object.
  context.addInitScript(() => {
    // Browser-generated ErrorEvents (for example ResizeObserver delivery errors)
    // may not reach Playwright's pageerror. Classify locally: never emit raw
    // messages, source URLs, stacks or error objects from this bridge.
    window.addEventListener('error', (event) => {
      const message = typeof event.message === 'string' ? event.message : '';
      const kind = message === 'ResizeObserver loop completed with undelivered notifications.'
        || message === 'ResizeObserver loop limit exceeded'
        ? 'resize_observer_delivery'
        : message === 'Script error.' ? 'opaque_script_error' : 'other_window_error';
      console.error(`[window_error_class] ${kind}`);
    });
    window.addEventListener('unhandledrejection', (event) => {
      const reason = event.reason instanceof Error ? event.reason.message : String(event.reason || 'unknown');
      console.error(`[unhandledrejection] ${reason}`);
    });
  });
  for (const page of context.pages()) attach(page);
  context.on('page', attach);

  return {
    snapshot() {
      return {
        rawCount: raw.length,
        filteredCount: raw.length - accepted.length,
        retainedCount: accepted.length,
        consoleErrors: accepted.filter((item) => item.type === 'error'),
        consoleWarnings: accepted.filter((item) => item.type === 'warning'),
        pageErrors: accepted.filter((item) => item.type === 'pageerror'),
        filteredNoise: raw.filter((item) => item.filtered),
      };
    },
    dispose() {
      context.off('page', attach);
      for (const [page, handlers] of listeners) {
        page.off('console', handlers.onConsole);
        page.off('pageerror', handlers.onPageError);
      }
      listeners.clear();
    },
  };
}

module.exports = { DEFAULT_NOISE_RULES, matchesNoiseRule, installConsoleRecorder };

},
    "core/evidence-writer.js": function(module, exports, require) {
'use strict';

const { sanitizeValue } = require('./sanitizer');

const SAFE_EVIDENCE_SEGMENTS = ['.playwright-mcp', 'test-results', 'test-output', 'tmp'];

function normalizePath(value) {
  return String(value || '').replace(/\\/g, '/').replace(/\/+$/, '');
}

function isSafeEvidenceDirectory(directory) {
  const normalized = normalizePath(directory).toLowerCase();
  return SAFE_EVIDENCE_SEGMENTS.some((segment) => normalized.split('/').includes(segment.toLowerCase()));
}

function joinPath(directory, filename) {
  return `${normalizePath(directory)}/${filename}`;
}

function markdownReport(report) {
  const lines = [
    '# Production read-only Playwright report',
    '',
    `- Run: ${report.runId}`,
    `- Base URL: ${report.baseUrl}`,
    `- Scenarios: ${report.scenarios.length}`,
    `- Auth requests: ${report.authRequests.length}`,
    `- Read-only POST requests: ${report.readonlyPostRequests.length}`,
    `- Blocked mutation attempts: ${report.blockedMutationAttempts.length}`,
    `- Actual business writes: ${report.actualBusinessWrites}`,
    `- Console retained/raw: ${report.console.retainedCount}/${report.console.rawCount}`,
    `- HTTP errors: ${report.network.httpErrors.length}`,
    '',
    '| Scenario | Result | Root cause | Writes | Duration |',
    '|---|---|---|---:|---:|',
  ];
  for (const result of report.scenarios) {
    lines.push(`| ${result.scenario} | ${result.result} | ${result.rootCauseClass} | ${result.actualBusinessWrites} | ${result.durationMs} ms |`);
  }
  lines.push('', '## Priorities', '');
  for (const priority of ['P0', 'P1', 'P2']) {
    const items = report.priorities?.[priority] || [];
    lines.push(`### ${priority}`, '', ...(items.length ? items.map((item) => `- ${item}`) : ['- None']), '');
  }
  lines.push('## Screenshots', '');
  const screenshots = report.scenarios.flatMap((result) => result.screenshots || []);
  lines.push(...(screenshots.length ? screenshots.map((item) => `- ${item}`) : ['- None']), '');
  return `${lines.join('\n')}\n`;
}

async function saveTextWithDownload(page, content, target, mimeType) {
  const downloadPromise = page.waitForEvent('download', { timeout: 15_000 });
  await page.evaluate(({ text, filename, type }) => {
    const blob = new Blob([text], { type });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement('a');
    anchor.href = url;
    anchor.download = filename;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
    setTimeout(() => URL.revokeObjectURL(url), 0);
  }, { text: content, filename: target.split(/[\\/]/).pop(), type: mimeType });
  const download = await downloadPromise;
  await download.saveAs(target);
}

async function writeEvidence(report, evidenceDir, options = {}) {
  const directory = normalizePath(evidenceDir);
  if (options.productionReadonly !== false && !isSafeEvidenceDirectory(directory)) {
    throw new Error(`Evidence directory must be gitignored (.playwright-mcp/test-results/test-output/tmp): ${directory}`);
  }
  if (!options.page) throw new Error('A Playwright page is required to write browser-safe evidence');
  const safeReport = sanitizeValue(report);
  const jsonPath = joinPath(directory, 'report.json');
  const markdownPath = joinPath(directory, 'report.md');
  await saveTextWithDownload(options.page, `${JSON.stringify(safeReport, null, 2)}\n`, jsonPath, 'application/json');
  await saveTextWithDownload(options.page, markdownReport(safeReport), markdownPath, 'text/markdown');
  return { evidenceDir: directory, jsonPath, markdownPath };
}

module.exports = { SAFE_EVIDENCE_SEGMENTS, isSafeEvidenceDirectory, markdownReport, writeEvidence };

},
    "core/mutation-guard.js": function(module, exports, require) {
'use strict';

const { findAuthRequest, findReadonlyPost, READONLY_POST_WHITELIST } = require('../config/readonly-post-whitelist');
const { sanitizeUrl, summarizePayload } = require('./sanitizer');

const MUTATING_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function classifyMutation(method, url, whitelist = READONLY_POST_WHITELIST, body = null) {
  const upper = String(method || '').toUpperCase();
  if (!MUTATING_METHODS.has(upper)) return { kind: 'safe-method' };
  const auth = findAuthRequest(upper, url);
  if (auth) return { kind: 'auth', entry: auth };
  const readonly = findReadonlyPost(upper, url, whitelist, body);
  if (readonly) return { kind: 'readonly-post', entry: readonly };
  return { kind: 'business-mutation' };
}

async function installMutationGuard(context, options = {}) {
  const scenarioRef = options.scenarioRef || { value: 'bootstrap' };
  const whitelist = options.readonlyPostWhitelist || READONLY_POST_WHITELIST;
  const authRequests = [];
  const readonlyPostRequests = [];
  const blockedMutationAttempts = [];
  const actualBusinessWriteRequests = [];
  const allowedByRequest = new WeakMap();

  const handler = async (route) => {
    const request = route.request();
    const method = request.method().toUpperCase();
    let requestBody = null;
    if (MUTATING_METHODS.has(method)) {
      try {
        requestBody = request.postDataJSON();
      } catch {
        requestBody = null;
      }
    }
    const classification = classifyMutation(method, request.url(), whitelist, requestBody);
    if (classification.kind === 'safe-method') {
      await route.continue();
      return;
    }

    const record = {
      method,
      url: sanitizeUrl(request.url()),
      scenario: scenarioRef.value || 'unknown',
      at: new Date().toISOString(),
    };
    if (classification.kind === 'auth') {
      authRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }
    if (classification.kind === 'readonly-post') {
      readonlyPostRequests.push({ ...record, classificationId: classification.entry.id });
      allowedByRequest.set(request, classification.kind);
      await route.continue();
      return;
    }

    blockedMutationAttempts.push({
      ...record,
      requestPayload: summarizePayload(requestBody),
      safetyStatus: 'FAIL',
      blockedBeforeSend: true,
    });
    await route.abort('blockedbyclient');
  };

  const responseListener = (response) => {
    const request = response.request();
    const method = request.method().toUpperCase();
    if (!MUTATING_METHODS.has(method)) return;
    const classification = classifyMutation(method, request.url(), whitelist);
    if (classification.kind === 'business-mutation' && !allowedByRequest.has(request)) {
      actualBusinessWriteRequests.push({
        method,
        url: sanitizeUrl(request.url()),
        status: response.status(),
        scenario: scenarioRef.value || 'unknown',
        at: new Date().toISOString(),
      });
    }
  };

  await context.route('**/*', handler);
  context.on('response', responseListener);

  return {
    authRequests,
    readonlyPostRequests,
    blockedMutationAttempts,
    actualBusinessWriteRequests,
    get actualBusinessWrites() {
      return actualBusinessWriteRequests.length;
    },
    snapshot() {
      return {
        authRequests: authRequests.slice(),
        readonlyPostRequests: readonlyPostRequests.slice(),
        blockedMutationAttempts: blockedMutationAttempts.slice(),
        actualBusinessWriteRequests: actualBusinessWriteRequests.slice(),
        actualBusinessWrites: actualBusinessWriteRequests.length,
      };
    },
    async dispose() {
      context.off('response', responseListener);
      await context.unroute('**/*', handler);
    },
  };
}

module.exports = { MUTATING_METHODS, classifyMutation, installMutationGuard };

},
    "core/network-recorder.js": function(module, exports, require) {
'use strict';

const { parsePostData, redactText, sanitizeUrl, summarizePayload } = require('./sanitizer');
const { normalizeQueryValues, splitUrl } = require('./url-utils');

function normalizeDuplicateKey(method, url) {
  try {
    const parsed = splitUrl(url);
    const normalized = splitUrl(normalizeQueryValues(url));
    return `${method} ${parsed.origin}${parsed.pathname}${normalized.search}`;
  } catch {
    return `${method} ${url}`;
  }
}

function pickResponseSummary(body, extractors, url) {
  const summary = {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: body?.message == null ? null : redactText(body.message, 160),
    keyFields: {},
  };
  for (const extractor of extractors || []) {
    if (!extractor.match(url)) continue;
    Object.assign(summary.keyFields, extractor.pick(body) || {});
  }
  return summary;
}

function installNetworkRecorder(context, options = {}) {
  const records = [];
  const httpErrors = [];
  const failedRequests = [];
  const started = new WeakMap();
  const pending = new Set();
  const extractors = options.responseExtractors || [];

  const onRequest = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    started.set(request, {
      at: Date.now(),
      payload: summarizePayload(parsePostData(request)),
    });
  };

  const onResponse = (response) => {
    const request = response.request();
    if (!request.url().includes('/api/mobile/')) return;
    const task = (async () => {
      const start = started.get(request);
      let body = null;
      try {
        const contentType = response.headers()['content-type'] || '';
        if (/json/i.test(contentType)) body = await response.json();
      } catch {
        body = null;
      }
      const record = {
        method: request.method(),
        url: sanitizeUrl(request.url()),
        status: response.status(),
        durationMs: start ? Math.max(0, Date.now() - start.at) : null,
        requestPayload: start?.payload || null,
        response: pickResponseSummary(body, extractors, request.url()),
        at: new Date(start?.at || Date.now()).toISOString(),
      };
      records.push(record);
      if (response.status() >= 400) httpErrors.push(record);
    })();
    pending.add(task);
    task.finally(() => pending.delete(task));
  };

  const onRequestFailed = (request) => {
    if (!request.url().includes('/api/mobile/')) return;
    const errorText = request.failure()?.errorText || 'request failed';
    // Requests aborted by the mutation guard are represented in the guard's
    // blockedMutationAttempts collection, not duplicated as HTTP failures.
    if (/ERR_BLOCKED_BY_CLIENT|blockedbyclient/i.test(errorText)) return;
    failedRequests.push({
      method: request.method(),
      url: sanitizeUrl(request.url()),
      error: redactText(errorText, 160),
      at: new Date().toISOString(),
    });
  };

  context.on('request', onRequest);
  context.on('response', onResponse);
  context.on('requestfailed', onRequestFailed);

  return {
    records,
    httpErrors,
    failedRequests,
    async flush() {
      await Promise.allSettled([...pending]);
    },
    snapshot() {
      const duplicates = new Map();
      for (const record of records) {
        const key = normalizeDuplicateKey(record.method, record.url);
        duplicates.set(key, (duplicates.get(key) || 0) + 1);
      }
      return {
        apiEvidence: records.slice(),
        httpErrors: httpErrors.slice(),
        failedRequests: failedRequests.slice(),
        duplicateRequests: [...duplicates.entries()]
          .filter(([, count]) => count > 1)
          .map(([request, count]) => ({ request, count }))
          .sort((a, b) => b.count - a.count),
      };
    },
    dispose() {
      context.off('request', onRequest);
      context.off('response', onResponse);
      context.off('requestfailed', onRequestFailed);
    },
  };
}

module.exports = { installNetworkRecorder, normalizeDuplicateKey, pickResponseSummary };

},
    "core/result-schema.js": function(module, exports, require) {
'use strict';

const RESULTS = new Set(['PASS', 'CONFIRMED_DEFECT', 'PARTIAL_DEFECT', 'UNVERIFIED', 'TOOL_ERROR']);
const ROOT_CAUSES = new Set(['frontend', 'backend', 'data', 'config', 'tool', 'none']);

function createScenarioResult(scenario, url = '') {
  return {
    scenario,
    result: 'UNVERIFIED',
    url,
    pageEvidence: [],
    apiEvidence: [],
    consoleErrors: [],
    consoleWarnings: [],
    httpErrors: [],
    blockedMutationAttempts: [],
    actualBusinessWrites: 0,
    rootCauseClass: 'none',
    screenshots: [],
    durationMs: 0,
  };
}

function validateScenarioResult(value) {
  const errors = [];
  if (!value || typeof value !== 'object') return ['result must be an object'];
  if (!value.scenario || typeof value.scenario !== 'string') errors.push('scenario must be a non-empty string');
  if (!RESULTS.has(value.result)) errors.push(`result must be one of ${[...RESULTS].join(', ')}`);
  if (typeof value.url !== 'string') errors.push('url must be a string');
  for (const key of ['pageEvidence', 'apiEvidence', 'consoleErrors', 'consoleWarnings', 'httpErrors', 'blockedMutationAttempts', 'screenshots']) {
    if (!Array.isArray(value[key])) errors.push(`${key} must be an array`);
  }
  if (!Number.isInteger(value.actualBusinessWrites) || value.actualBusinessWrites < 0) {
    errors.push('actualBusinessWrites must be a non-negative integer');
  }
  if (!ROOT_CAUSES.has(value.rootCauseClass)) errors.push(`rootCauseClass must be one of ${[...ROOT_CAUSES].join(', ')}`);
  if (!Number.isFinite(value.durationMs) || value.durationMs < 0) errors.push('durationMs must be a non-negative number');
  return errors;
}

function assertScenarioResult(value) {
  const errors = validateScenarioResult(value);
  if (errors.length) throw new Error(`Invalid scenario result: ${errors.join('; ')}`);
  return value;
}

module.exports = { RESULTS, ROOT_CAUSES, createScenarioResult, validateScenarioResult, assertScenarioResult };

},
    "core/run-suite.js": function(module, exports, require) {
'use strict';

const { establishCleanSession } = require('./clean-session');
const { performUiLogin } = require('./ui-login');
const { installMutationGuard } = require('./mutation-guard');
const { installNetworkRecorder } = require('./network-recorder');
const { installConsoleRecorder } = require('./console-recorder');
const { createScreenshotWriter } = require('./screenshot');
const { writeEvidence } = require('./evidence-writer');
const { createScenarioResult, assertScenarioResult } = require('./result-schema');
const { sanitizeValue } = require('./sanitizer');
const { pathnameOf } = require('./url-utils');

const SCENARIOS = [
  require('../scenarios/table-grid-readonly').sales,
  require('../scenarios/table-grid-readonly').label,
  require('../scenarios/transfer-draft-readonly'),
  require('../scenarios/workspace-capacity-readonly'),
  require('../scenarios/workspace-duplicate-readonly'),
  require('../scenarios/workspace-r3-readonly'),
  require('../scenarios/yield-header-r3-readonly'),
  require('../scenarios/finished-inventory-filter-readonly'),
  require('../scenarios/sales-date-draft-readonly'),
  require('../scenarios/workflow-viewport-readonly'),
  require('../scenarios/visual-unverified-followup-readonly'),
  require('../scenarios/production-boards-readonly'),
  require('../scenarios/tenant-isolation'),
  require('../scenarios/bom-readonly'),
  require('../scenarios/bom-cost-caliber-readonly'),
  require('../scenarios/bom-canvas-overlay-readonly'),
  require('../scenarios/purchasing-readonly'),
  require('../scenarios/finance-readonly'),
  require('../scenarios/supplier-readonly'),
  require('../scenarios/ai-readonly'),
  require('../scenarios/workflow-readonly'),
  require('../scenarios/production-plan-routing-readonly'),
  require('../scenarios/material-consumption-price-mask-readonly'),
  require('../scenarios/role-menu-census-readonly'),
  require('../scenarios/label-qc-readonly'),
  require('../scenarios/restaurant-live-command-readonly'),
  require('../scenarios/restaurant-staffing-readonly'),
  require('../scenarios/sales-order-lifecycle-readonly'),
  require('../scenarios/ui-stability'),
  require('../scenarios/visual-page-census-readonly'),
];

function describeHarness() {
  return {
    name: 'cretas-production-readonly',
    scenarios: SCENARIOS.map((scenario) => scenario.id),
    defaultScenarios: SCENARIOS
      .filter((scenario) => scenario.defaultEnabled !== false)
      .map((scenario) => scenario.id),
    safety: {
      requestInterception: 'before-send',
      guardedMethods: ['POST', 'PUT', 'PATCH', 'DELETE'],
      defaultLogin: 'UI',
      actualBusinessWritesRequired: 0,
    },
  };
}

function resolveScenarios(requested) {
  if (!requested || requested.length === 0) {
    return SCENARIOS.filter((scenario) => scenario.defaultEnabled !== false);
  }
  const ids = Array.isArray(requested) ? requested : String(requested).split(',');
  return ids.map((id) => {
    const scenario = SCENARIOS.find((candidate) => candidate.id === id.trim());
    if (!scenario) throw new Error(`Unknown scenario: ${id}. Available: ${SCENARIOS.map((item) => item.id).join(', ')}`);
    return scenario;
  });
}

async function runSuiteWithPage(page, options = {}) {
  const context = page.context();
  const baseUrl = String(options.baseUrl || 'https://admin.cretaceousfuture.com').replace(/\/$/, '');
  const expectedUsername = options.expectedUsername || 'f006_admin';
  const expectedFactoryId = options.expectedFactoryId || 'F006';
  const runId = options.runId || new Date().toISOString().replace(/[:.]/g, '-');
  const evidenceDir = options.evidenceDir || `.playwright-mcp/production-readonly/${runId}`;
  const scenarioRef = { value: 'bootstrap' };

  // The guard is installed before any target-origin navigation or login.
  const mutationGuard = await installMutationGuard(context, { scenarioRef });
  const network = installNetworkRecorder(context, options.network || {});
  const consoleRecorder = installConsoleRecorder(context, options.console || {});
  const screenshot = createScreenshotWriter(page, evidenceDir, { sensitiveTexts: [expectedUsername] });
  const selectedScenarios = resolveScenarios(options.scenarios);
  const startedAt = Date.now();
  const startedAtIso = new Date(startedAt).toISOString();
  let loginEvidence;
  const results = [];

  try {
    try {
      scenarioRef.value = 'clean-session';
      await establishCleanSession(page, baseUrl);
      scenarioRef.value = 'ui-login';
      loginEvidence = await performUiLogin(page, {
        username: options.username,
        password: options.password,
        expectedUsername,
        expectedFactoryId,
        expectedRole: options.expectedRole,
        allowMobileOnlyLanding: options.allowMobileOnlyLanding,
      });

      const scenarioContext = {
        page,
        context,
        baseUrl,
        expectedUsername,
        expectedFactoryId,
        loginEvidence,
        scenarioRef,
        mutationGuard,
        network,
        consoleRecorder,
        screenshot,
        evidenceDir,
      };
      for (const scenario of selectedScenarios) {
        const before = mutationGuard.snapshot();
        if (before.actualBusinessWrites || before.blockedMutationAttempts.length) break;
        results.push(await scenario.run(scenarioContext));
        const after = mutationGuard.snapshot();
        if (after.actualBusinessWrites || after.blockedMutationAttempts.length) break;
      }
    } catch (error) {
      const failure = createScenarioResult(scenarioRef.value || 'bootstrap', page.url());
      failure.result = 'TOOL_ERROR';
      failure.rootCauseClass = 'tool';
      const visibleText = String(await page.locator('body').innerText().catch(() => ''));
      failure.pageEvidence.push({
        error: String(error?.message || error),
        bodyTextLength: visibleText.length,
        finalPath: (() => { try { return pathnameOf(page.url()); } catch { return ''; } })(),
      });
      try { failure.screenshots.push(await screenshot(`${failure.scenario}-failure`)); } catch {}
      failure.durationMs = Date.now() - startedAt;
      results.push(assertScenarioResult(failure));
    }
    await network.flush();

    const guardReport = mutationGuard.snapshot();
    const networkReport = network.snapshot();
    const consoleReport = consoleRecorder.snapshot();
    const report = {
      runId,
      baseUrl,
      productionReadonly: true,
      startedAt: startedAtIso,
      durationMs: Date.now() - startedAt,
      tenant: {
        username: loginEvidence?.username || null,
        factoryId: loginEvidence?.factoryId || null,
        factoryName: loginEvidence?.factoryName || null,
      },
      scenarios: results,
      authRequests: guardReport.authRequests,
      readonlyPostRequests: guardReport.readonlyPostRequests,
      blockedMutationAttempts: guardReport.blockedMutationAttempts,
      actualBusinessWriteRequests: guardReport.actualBusinessWriteRequests,
      actualBusinessWrites: guardReport.actualBusinessWrites,
      network: networkReport,
      console: consoleReport,
      priorities: options.priorities || { P0: [], P1: [], P2: [] },
      safetyPassed: guardReport.actualBusinessWrites === 0 && guardReport.blockedMutationAttempts.length === 0,
    };
    const safeReport = sanitizeValue(report);
    const files = await writeEvidence(safeReport, evidenceDir, { productionReadonly: true, page });
    return { ...safeReport, evidence: files };
  } finally {
    scenarioRef.value = 'teardown';
    network.dispose();
    consoleRecorder.dispose();
    await mutationGuard.dispose();
  }
}

module.exports = { SCENARIOS, describeHarness, resolveScenarios, runSuiteWithPage };

},
    "core/sanitizer.js": function(module, exports, require) {
'use strict';

const { mapQueryValues } = require('./url-utils');

const SENSITIVE_KEY_RE = /authorization|cookie|password|passwd|secret|token|credential|session|jwt|set-cookie|(?:^|[_-])username(?:$|[_-])/i;
const SAFE_STRING_KEYS = new Set([
  'factoryId',
  'mode',
  'moduleCode',
  'action',
  'status',
  'code',
  'category',
  'type',
  'intentCode',
]);

function redactText(value, maxLength = 240) {
  let text = String(value ?? '');
  text = text
    .replace(/Bearer\s+[A-Za-z0-9._~+\/-]+=*/gi, 'Bearer [REDACTED]')
    .replace(/([?&](?:token|access_token|refresh_token|password|secret|authorization)=)[^&#\s]*/gi, '$1[REDACTED]')
    .replace(/\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}\b/g, '[REDACTED_EMAIL]')
    .replace(/(?<!\d)(?:\+?86[- ]?)?1[3-9]\d{9}(?!\d)/g, '[REDACTED_PHONE]')
    .replace(/\beyJ[A-Za-z0-9_-]{12,}\.[A-Za-z0-9_-]{8,}(?:\.[A-Za-z0-9_-]{8,})?\b/g, '[REDACTED_JWT]');
  return text.length > maxLength ? `${text.slice(0, maxLength)}…` : text;
}

function sanitizeUrl(input) {
  try {
    return redactText(mapQueryValues(String(input), (key, value) => (
      SENSITIVE_KEY_RE.test(key) ? '[REDACTED]' : value
    )), 500);
  } catch {
    return redactText(input, 500);
  }
}

function summarizeScalar(key, value) {
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (typeof value === 'string') {
    if (SAFE_STRING_KEYS.has(key)) return redactText(value, 80);
    return `<string:${value.length}>`;
  }
  return `<${typeof value}>`;
}

function summarizePayload(value, depth = 0) {
  if (value == null) return null;
  if (depth > 3) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) {
    return {
      type: 'array',
      length: value.length,
      sample: value.slice(0, 3).map((item) => summarizePayload(item, depth + 1)),
    };
  }
  if (typeof value !== 'object') return summarizeScalar('', value);
  const keys = Object.keys(value).slice(0, 40);
  const fields = {};
  for (const key of keys) {
    const child = value[key];
    if (SENSITIVE_KEY_RE.test(key)) fields[key] = '[REDACTED]';
    else if (child && typeof child === 'object') fields[key] = summarizePayload(child, depth + 1);
    else fields[key] = summarizeScalar(key, child);
  }
  return { type: 'object', keys, fields };
}

function sanitizeValue(value, key = '', depth = 0) {
  if (SENSITIVE_KEY_RE.test(key)) return '[REDACTED]';
  if (value == null || typeof value === 'boolean' || typeof value === 'number') return value;
  if (typeof value === 'string') return redactText(value);
  if (depth > 5) return '[DEPTH_LIMIT]';
  if (Array.isArray(value)) return value.slice(0, 200).map((item) => sanitizeValue(item, '', depth + 1));
  if (typeof value === 'object') {
    const result = {};
    for (const [childKey, childValue] of Object.entries(value)) {
      result[childKey] = sanitizeValue(childValue, childKey, depth + 1);
    }
    return result;
  }
  return redactText(value);
}

function parsePostData(request) {
  try {
    return request.postDataJSON();
  } catch {
    const raw = request.postData();
    return raw ? { rawLength: raw.length } : null;
  }
}

module.exports = {
  SENSITIVE_KEY_RE,
  redactText,
  sanitizeUrl,
  sanitizeValue,
  summarizePayload,
  parsePostData,
};

},
    "core/screenshot.js": function(module, exports, require) {
'use strict';

function slug(value) {
  return String(value || 'evidence').replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 80) || 'evidence';
}

// Mask contact text at capture time, without modifying application values or
// hiding adjacent business quantities. Rectangles are removed even on failure.
async function installContactMasks(page) {
  return page.evaluate(() => {
    const marker = 'data-cretas-contact-mask';
    document.querySelectorAll(`[${marker}]`).forEach((node) => node.remove());
    let count = 0;
    const cover = (rect) => {
      if (rect.width <= 0 || rect.height <= 0) return;
      const mask = document.createElement('div');
      mask.setAttribute(marker, '');
      Object.assign(mask.style, { position: 'absolute', left: `${rect.left + scrollX}px`, top: `${rect.top + scrollY}px`, width: `${rect.width}px`, height: `${rect.height}px`, background: '#1f2937', zIndex: '2147483647', pointerEvents: 'none' });
      document.body.append(mask); count += 1;
    };
    const contactHeader = /电话|手机|邮箱|电子邮件|e-?mail|phone|mobile|\btel\b/i;
    for (const table of document.querySelectorAll('.el-table, table')) {
      const headers = [...table.querySelectorAll('thead th')];
      const indexes = headers.flatMap((header, index) => contactHeader.test(header.textContent || '') ? [index] : []);
      for (const row of table.querySelectorAll('tbody tr')) {
        for (const index of indexes) if (row.children[index]) cover(row.children[index].getBoundingClientRect());
      }
    }
    for (const input of document.querySelectorAll('input')) {
      const label = `${input.getAttribute('type') || ''} ${input.getAttribute('name') || ''} ${input.getAttribute('autocomplete') || ''} ${input.getAttribute('placeholder') || ''} ${input.closest('.el-form-item')?.querySelector('label')?.textContent || ''}`;
      if (contactHeader.test(label)) cover(input.getBoundingClientRect());
    }
    const patterns = [/[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}/gi, /(?<![A-Za-z0-9])(?:\+?86[ -]?)?1[3-9]\d{9}(?!\d)/g, /(?<![A-Za-z0-9])(?:\+\d{1,3}[ -])?(?:\(?\d{2,4}\)?[- ])\d{3,4}[- ]\d{4}(?!\d)/g];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    const texts = [];
    while (walker.nextNode()) {
      const node = walker.currentNode;
      if (!node.parentElement?.closest('script, style, noscript, [data-cretas-contact-mask]')) texts.push(node);
    }
    for (const node of texts) for (const pattern of patterns) {
      pattern.lastIndex = 0;
      for (const match of node.textContent.matchAll(pattern)) {
        const range = document.createRange();
        range.setStart(node, match.index); range.setEnd(node, match.index + match[0].length);
        for (const rect of range.getClientRects()) cover(rect);
      }
    }
    return count;
  });
}

function createScreenshotWriter(page, evidenceDir, config = {}) {
  const root = String(evidenceDir || '').replace(/\\/g, '/').replace(/\/+$/, '');
  if (!root) throw new Error('Evidence directory is required for screenshots');
  let counter = 0;
  return async function screenshot(name, options = {}) {
    const filename = `${String(++counter).padStart(2, '0')}-${slug(name)}.png`;
    const target = `${root}/${filename}`;
    const mask = [
      page.locator('input[type="password"], input[placeholder*="用户名"]'),
      ...(config.sensitiveTexts || []).map((text) => page.getByText(text, { exact: true })),
    ];
    await installContactMasks(page);
    try { await page.screenshot({
      path: target,
      fullPage: Boolean(options.fullPage),
      mask,
      maskColor: '#1f2937',
    }); } finally {
      await page.evaluate(() => document.querySelectorAll('[data-cretas-contact-mask]').forEach((node) => node.remove())).catch(() => {});
    }
    return target;
  };
}

module.exports = { createScreenshotWriter, slug, installContactMasks };

},
    "core/ui-login.js": function(module, exports, require) {
'use strict';

const { redactText, sanitizeUrl } = require('./sanitizer');
const { pathnameOf } = require('./url-utils');
const { waitForAppMount } = require('./app-mount');

// 已知会串到别人会话里的租户标记。无 g 标志: 带 g 的正则跨次 test() 会保留 lastIndex。
const STALE_TENANT_MARKER = /liushanmen_admin/i;

function compactLoginData(body) {
  const data = body?.data || {};
  const factoryUser = data.factoryUser || {};
  return {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: redactText(body?.message || '', 120),
    username: data.username || factoryUser.username || null,
    factoryId: data.factoryId || factoryUser.factoryId || null,
    factoryName: data.factoryName || factoryUser.factoryName || null,
    role: data.role || factoryUser.role || null,
  };
}

async function waitForLoginOutcome(page, matchesLogin, timeoutMs = 20_000) {
  try {
    const response = await page.waitForResponse(
      (candidate) => matchesLogin(candidate.request()),
      { timeout: timeoutMs },
    );
    return { type: 'response', response };
  } catch (error) {
    throw new Error(`UI login request was not observed within ${timeoutMs}ms: ${error?.message || error}`);
  }
}

function allowsMissingFactoryName(pageUrl, allowMobileOnlyLanding) {
  return Boolean(allowMobileOnlyLanding) && pathnameOf(pageUrl) === '/mobile-only';
}

async function performUiLogin(page, options) {
  const username = options.username;
  const password = options.password;
  const expectedUsername = options.expectedUsername || 'f006_admin';
  const expectedFactoryId = options.expectedFactoryId || 'F006';
  const expectedRole = options.expectedRole ? String(options.expectedRole).toLowerCase() : null;
  if (!username || !password) throw new Error('E2E_USERNAME and E2E_PASSWORD are required for UI login');

  const usernameInput = page.locator('input[placeholder*="用户名"], input[type="text"]').first();
  const passwordInput = page.locator('input[placeholder*="密码"], input[type="password"]').first();
  await usernameInput.waitFor({ state: 'visible', timeout: 15_000 });
  await usernameInput.fill(username);
  await passwordInput.fill(password);

  const matchesLogin = (request) => request.url().includes('/api/mobile/auth/unified-login')
    && request.method() === 'POST';
  const outcomePromise = waitForLoginOutcome(page, matchesLogin);
  const submit = page.locator('button.login-button, button[type="submit"]').first();
  await submit.click();
  const outcome = await outcomePromise;
  if (outcome.type === 'failure') {
    throw new Error(`UI login request failed before response: ${outcome.request.failure()?.errorText || 'unknown network error'}`);
  }
  const response = outcome.response;
  let body = null;
  try { body = await response.json(); } catch { body = null; }
  const login = compactLoginData(body);
  if (response.status() >= 400 || login.success === false) {
    throw new Error(`UI login failed: HTTP ${response.status()} ${login.message || 'success=false'}`);
  }
  await page.waitForURL((url) => !url.pathname.includes('/login'), { timeout: 20_000 });

  const stored = await page.evaluate(() => {
    try {
      const value = JSON.parse(localStorage.getItem('cretas_user') || '{}');
      const factoryUser = value.factoryUser || {};
      return {
        username: value.username || factoryUser.username || null,
        factoryId: value.factoryId || factoryUser.factoryId || null,
        factoryName: value.factoryName || factoryUser.factoryName || null,
        role: value.role || factoryUser.role || null,
      };
    } catch {
      return {};
    }
  });
  // 🔴 落地页也必须等应用挂载完再取样 —— 这是同一个缺陷的第二处, 而且更阴:
  //    闪屏阶段的 body 只有二十来个字符, 于是下面
  //      · 工厂名比对必然失败 -> 整个 suite 在 bootstrap 就 TOOL_ERROR
  //      · 「跨租户残留」那条 canary 变成恒真式(闪屏里永远不会出现别的租户名)
  //    判据见 core/app-mount.js。
  const landingMount = await waitForAppMount(page, options.appMountTimeoutMs);
  const pageText = await page.locator('body').innerText().catch(() => '');
  const actualUsername = stored.username || login.username;
  const actualFactoryId = stored.factoryId || login.factoryId;
  const actualFactoryName = stored.factoryName || login.factoryName;
  const businessSuccess = login.success ?? Boolean(actualUsername && actualFactoryId);
  // 这道 canary 抓的是「别的租户残留在本次会话的页面上」。租户名写死成字面量时,
  // 一旦该账号自己成为被测账号, 合法登录会被判成泄漏(本条实测拦下过 liushanmen_admin
  // 的正常登录)。先排除「标记就是当前已认证用户」, 跨租户残留的语义不变。
  const staleTenantDetected = !STALE_TENANT_MARKER.test(String(actualUsername || ''))
    && STALE_TENANT_MARKER.test(pageText);
  if (actualUsername !== expectedUsername) throw new Error(`Tenant login username mismatch: expected ${expectedUsername}, got ${actualUsername}`);
  if (actualFactoryId !== expectedFactoryId) throw new Error(`Tenant factory mismatch: expected ${expectedFactoryId}, got ${actualFactoryId}`);
  const actualRole = String(stored.role || login.role || '').toLowerCase();
  if (expectedRole && actualRole !== expectedRole) throw new Error(`Tenant role mismatch: expected ${expectedRole}, got ${actualRole || 'missing'}`);
  if (staleTenantDetected) throw new Error('Stale tenant marker liushanmen_admin detected after clean UI login');
  if (actualFactoryName && !pageText.includes(actualFactoryName)
      && !allowsMissingFactoryName(page.url(), options.allowMobileOnlyLanding)) {
    throw new Error('Factory display name does not match the authenticated factory name');
  }

  return {
    method: 'POST',
    url: sanitizeUrl(response.url()),
    status: response.status(),
    success: businessSuccess,
    responseBodyParsed: body !== null,
    username: actualUsername,
    factoryId: actualFactoryId,
    factoryName: actualFactoryName,
    role: actualRole || null,
    landingPath: pathnameOf(page.url()),
    staleTenantDetected,
    // ⚠️ 记下来, 否则「落地页没挂载」和「落地页缺东西」在证据里长得一样。
    landingAppMounted: landingMount.appMounted,
    landingAppMountMs: landingMount.appMountMs,
  };
}

module.exports = { allowsMissingFactoryName, compactLoginData, performUiLogin, waitForLoginOutcome };

},
    "core/url-utils.js": function(module, exports, require) {
'use strict';

function splitUrl(input) {
  const value = String(input || '');
  const match = value.match(/^([A-Za-z][A-Za-z0-9+.-]*:\/\/[^/?#]*)([^?#]*)(\?[^#]*)?(#.*)?$/);
  if (!match) throw new Error(`Unsupported URL: ${value}`);
  return {
    origin: match[1],
    pathname: match[2] || '/',
    search: match[3] || '',
    hash: match[4] || '',
  };
}

function resolveUrl(path, baseUrl) {
  const candidate = String(path || '');
  if (/^[A-Za-z][A-Za-z0-9+.-]*:\/\//.test(candidate)) return candidate;
  const base = splitUrl(baseUrl);
  if (candidate.startsWith('/')) return `${base.origin}${candidate}`;
  const directory = base.pathname.replace(/[^/]*$/, '');
  return `${base.origin}${directory}${candidate}`;
}

function pathnameOf(input) {
  return splitUrl(input).pathname;
}

function mapQueryValues(input, mapper) {
  const parsed = splitUrl(input);
  if (!parsed.search) return String(input);
  const mapped = parsed.search.slice(1).split('&').filter(Boolean).map((pair) => {
    const separator = pair.indexOf('=');
    const rawKey = separator >= 0 ? pair.slice(0, separator) : pair;
    const rawValue = separator >= 0 ? pair.slice(separator + 1) : '';
    let decodedKey = rawKey;
    try { decodedKey = decodeURIComponent(rawKey.replace(/\+/g, ' ')); } catch {}
    const nextValue = mapper(decodedKey, rawValue);
    return separator >= 0 || nextValue !== '' ? `${rawKey}=${nextValue}` : rawKey;
  });
  return `${parsed.origin}${parsed.pathname}${mapped.length ? `?${mapped.join('&')}` : ''}${parsed.hash}`;
}

function normalizeQueryValues(input, replacement = '*') {
  return mapQueryValues(input, () => replacement);
}

module.exports = { mapQueryValues, normalizeQueryValues, pathnameOf, resolveUrl, splitUrl };

},
    "scenarios/_shared.js": function(module, exports, require) {
'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { pathnameOf, resolveUrl } = require('../core/url-utils');
const { waitForAppMount } = require('../core/app-mount');

function arrayDelta(after, beforeLength) {
  return (after || []).slice(beforeLength);
}

async function runReadOnlyPageScenario(ctx, definition) {
  const startedAt = Date.now();
  const result = createScenarioResult(definition.id);
  ctx.scenarioRef.value = definition.id;
  const beforeNetwork = ctx.network.snapshot();
  const beforeConsole = ctx.consoleRecorder.snapshot();
  const beforeGuard = ctx.mutationGuard.snapshot();

  try {
    await ctx.page.goto(resolveUrl(definition.path, ctx.baseUrl), {
      waitUntil: 'domcontentloaded',
      timeout: definition.timeoutMs || 45_000,
    });
    // 🔴 取样必须发生在应用挂载【之后】—— 判据与理由见 core/app-mount.js。
    //    少了这一步, goto 的 domcontentloaded 在闪屏阶段就满足, 2026-08-27 生产实测
    //    16 个场景里 13 个因此报 UNVERIFIED/tool。
    const { appMounted, appMountMs } = await waitForAppMount(
      ctx.page,
      definition.appMountTimeoutMs,
    );
    await ctx.page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
    result.url = ctx.page.url();
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const matched = (definition.landmarks || []).filter((landmark) => body.includes(landmark));
    result.pageEvidence.push({
      expectedLandmarks: definition.landmarks || [],
      matchedLandmarks: matched,
      bodyTextLength: body.trim().length,
      finalPath: pathnameOf(result.url),
      appMounted,
      appMountMs,
    });
    let assessment = null;
    if (definition.inspect) {
      const inspected = await definition.inspect(ctx.page, body, ctx);
      if (inspected) {
        assessment = inspected.assessment || null;
        const { assessment: _assessment, screenshots = [], ...evidence } = inspected;
        result.pageEvidence.push(evidence);
        result.screenshots.push(...screenshots);
      }
    }
    if (definition.screenshot) result.screenshots.push(await ctx.screenshot(definition.id));
    // ⚠️ 「应用压根没挂载」与「挂载了但缺 landmark」必须分开报 —— 下一步动作相反:
    //    前者去查前端加载(构建/资源/首屏), 后者去查这条场景的期望或页面本身。
    //    ⛔ 都折叠成 UNVERIFIED/tool 就是把「没量到」塞进了「判不了」。
    if (!appMounted) {
      result.result = 'UNVERIFIED';
      result.rootCauseClass = 'frontend';
    } else {
      result.result = matched.length === (definition.landmarks || []).length && body.trim().length > 40
        ? 'PASS'
        : 'UNVERIFIED';
      result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
    }
    if (result.result === 'PASS' && assessment) {
      result.result = assessment.result;
      result.rootCauseClass = assessment.rootCauseClass;
    }
  } catch (error) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ error: String(error?.message || error) });
  }

  await ctx.network.flush();
  const afterNetwork = ctx.network.snapshot();
  const afterConsole = ctx.consoleRecorder.snapshot();
  const afterGuard = ctx.mutationGuard.snapshot();
  result.apiEvidence = arrayDelta(afterNetwork.apiEvidence, beforeNetwork.apiEvidence.length);
  result.httpErrors = arrayDelta(afterNetwork.httpErrors, beforeNetwork.httpErrors.length);
  result.consoleErrors = arrayDelta(afterConsole.consoleErrors, beforeConsole.consoleErrors.length)
    .concat(arrayDelta(afterConsole.pageErrors, beforeConsole.pageErrors.length));
  result.consoleWarnings = arrayDelta(afterConsole.consoleWarnings, beforeConsole.consoleWarnings.length);
  result.blockedMutationAttempts = arrayDelta(afterGuard.blockedMutationAttempts, beforeGuard.blockedMutationAttempts.length);
  result.actualBusinessWrites = afterGuard.actualBusinessWrites - beforeGuard.actualBusinessWrites;
  if (result.blockedMutationAttempts.length || result.actualBusinessWrites > 0) {
    result.result = 'TOOL_ERROR';
    result.rootCauseClass = 'tool';
    result.pageEvidence.push({ safetyStatus: 'FAIL', reason: 'Unexpected mutation attempted during read-only scenario' });
  } else if (result.httpErrors.length || result.consoleErrors.length) {
    result.result = result.result === 'PASS' ? 'PARTIAL_DEFECT' : result.result;
    result.rootCauseClass = result.rootCauseClass === 'none' ? 'frontend' : result.rootCauseClass;
  }
  result.durationMs = Date.now() - startedAt;
  return assertScenarioResult(result);
}

module.exports = { runReadOnlyPageScenario };

},
    "scenarios/ai-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ai-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ai-readonly',
    path: ROUTES.products,
    landmarks: ['SKU'],
    inspect: async (page, body) => ({
      aiEntryVisible: /AI/.test(body),
      note: 'AI chat is not sent in production; /ai/chat is intentionally not whitelisted.',
      buttonCount: await page.getByRole('button').count(),
    }),
  }),
};

},
    "scenarios/bom-canvas-overlay-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

/**
 * BOM 融入工序画布后的三条口径, 都只读可验:
 *
 *   1. 辅料/包材以浮层 cell 形式长在画布上(vue-flow 节点类型 bomAuxiliary / bomPackaging)。
 *   2. 画布不再拉旧的 BOM 抽屉, 入口改成跳 BOM 菜单页, 文案「去 BOM 配置」。
 *      旧文案「在右侧配置 BOM」若还在, 说明按钮行为改了而文案没跟上(上线前正是这处漏过一次)。
 *   3. 不点击任何按钮 —— 入口只验文案与存在性, 跳转由路由单测覆盖。
 *
 * ⚠️ 「画布上没有辅料 cell」有两种成因, 必须分开判, 否则会把没数据报成缺陷
 * (bom-readonly 就是没分开: 产品没有任何 BOM 版本, 却连报 5 条 contract failure):
 *   - 画布压根没有工序节点 → 数据前提不满足 → UNVERIFIED / data
 *   - 有工序节点却没有辅料 cell → 浮层没派生出来 → CONFIRMED_DEFECT / frontend
 */
module.exports = {
  id: 'bom-canvas-overlay-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-canvas-overlay-readonly',
    path: ROUTES.workflow,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => {
      // 画布是异步水合的: networkidle 之后节点仍可能未挂载。不等就去数, 会把
      // 「还没渲染完」读成「没有节点」, 于是整条滑进 UNVERIFIED 而看不出真假。
      const settle = async () => {
        await page.locator('.vue-flow__node').first()
          .waitFor({ state: 'attached', timeout: 20_000 })
          .catch(() => {});
      };
      await settle();

      const count = (selector) => page.locator(selector).count();

      /**
       * ⛔ 2026-08-06: 这条场景**长期 UNVERIFIED**, 原因不是画布坏了, 是它落在
       * 页面**默认选中的那个产品**上, 而那个产品恰好一个工序节点都没有。
       * 于是每次都走「数据前提不满足」的分支 —— 一条永远不会给出结论的检查,
       * 比没有这条检查更糟(它看起来在跑)。
       *
       * 所以让场景**自己换一个产品再看**: 打开「归属对象」下拉, 逐个选前 N 个选项,
       * 命中有工序节点的就停。只点下拉与选项, 不碰任何写按钮。
       *
       * ⚠️ 第一版是在页面里 `fetch(/product-types/options)` 拿候选 —— **跑了 20 分钟
       * 不返回**: 只读 harness 的 mutation guard 会 abort 非白名单请求, 被 abort 的
       * promise 永远 pending, `page.evaluate` 就挂死了, 而且当时没有任何超时兜底。
       * 教训写在这: **只读场景里不要自己发请求**(要的数据页面已经渲染出来了),
       * 且**每一步都必须有超时 + 整体 deadline**, 否则一个 hang 会拖垮整轮。
       */
      let processNodes = await count('.vue-flow__node-process');
      const probedProducts = [];
      if (processNodes === 0) {
        const DEADLINE_MS = 90_000;
        const MAX_TRIES = 6;
        const startedAt = Date.now();
        const ownerSelect = page.locator('.el-select').first();

        for (let index = 0; index < MAX_TRIES; index += 1) {
          if (Date.now() - startedAt > DEADLINE_MS) {
            probedProducts.push({ note: 'deadline reached, stop probing' });
            break;
          }
          try {
            await ownerSelect.click({ timeout: 5_000 });
            // 选项挂在 body 下的 popper 里, 懒渲染 —— 等它出来再数
            const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item');
            await options.first().waitFor({ state: 'visible', timeout: 5_000 });
            const total = await options.count();
            if (index >= total) {
              await page.keyboard.press('Escape').catch(() => {});
              break;
            }
            const label = (await options.nth(index).innerText().catch(() => '')).trim();
            await options.nth(index).click({ timeout: 5_000 });
            await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
            await settle();
            processNodes = await count('.vue-flow__node-process');
            probedProducts.push({ option: label.slice(0, 60), processNodes });
            if (processNodes > 0) break;
          } catch (error) {
            probedProducts.push({ index, error: String(error && error.message).slice(0, 120) });
            await page.keyboard.press('Escape').catch(() => {});
            break;
          }
        }
      }
      const materialNodes = await count('.vue-flow__node-material');
      const auxiliaryCells = await count('.vue-flow__node-bomAuxiliary');
      const packagingCells = await count('.vue-flow__node-bomPackaging');
      const auxDetailEntries = await count('[data-testid="aux-open-detail"]');
      const packSubtitles = await count('[data-testid="pack-subtitle"]');

      /**
       * ⛔ 2026-08-06 修正: 这里原本断言「缺 BOM 横幅出现时必须有『去 BOM 配置』入口」——
       * 那是**旧设计**。冷启动已在画布内闭环(PR#2314): 零版本时直接点辅料/包材/副产 cell
       * 就会建首版草稿, 横幅不再把用户支去别的页面, 文案换成了「直接在下方的
       * 辅料 / 包材 / 副产 cell 上配置即可」。
       * 仓里 `bomMenuRetired.source.spec.ts` 已经明确断言**不许再出现**「去 BOM 配置」。
       *
       * 探针没跟上产品口径, 于是它一旦真的跑起来就报一条**假缺陷** ——
       * 比它此前长期 UNVERIFIED 更坏(UNVERIFIED 至少不冤枉人)。
       * 判据: **改了产品口径要回头看有没有闸还在断言旧口径**, 否则修好探针的同一天
       * 就会收到一条假报警。
       */
      const bomMissingBannerVisible = body.includes('暂未读取到生效 BOM');
      // 现在要求的是「横幅把人指回 cell」, 而不是把人支走
      const bomEntryCopyVisible = body.includes('辅料 / 包材 / 副产 cell 上配置');
      const legacyJumpCopyVisible = body.includes('去 BOM 配置');
      // 旧抽屉文案则是无条件的: 抽屉已下掉, 它在任何状态下都不该再出现。
      const legacyDrawerCopyVisible = body.includes('在右侧配置 BOM');

      const evidence = {
        processNodes,
        materialNodes,
        auxiliaryCells,
        packagingCells,
        auxDetailEntries,
        packSubtitles,
        bomMissingBannerVisible,
        bomEntryCopyVisible,
        legacyJumpCopyVisible,
        legacyDrawerCopyVisible,
        probedProducts,
        note: 'No button is clicked; entry is verified by copy and presence only.',
      };

      // 数据前提: 没有工序节点就没有可派生浮层的宿主, 不能据此判缺陷。
      // ⚠️ 走到这里意味着**试过的每一个产品**都没有工序节点(见 probedProducts),
      // 不再是「默认那个恰好是空的」—— 这时 UNVERIFIED 才是诚实的结论。
      if (processNodes === 0) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: probedProducts.length
            ? `probed ${probedProducts.length} products, none has a PROCESS node`
            : 'canvas has no PROCESS node and no product candidates could be listed',
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const contractFailures = [];
      if (auxiliaryCells === 0) contractFailures.push('auxiliary overlay cell missing while process nodes exist');
      if (auxiliaryCells > 0 && auxDetailEntries === 0) contractFailures.push('auxiliary cell rendered without its detail entry');
      if (bomMissingBannerVisible && !bomEntryCopyVisible) {
        contractFailures.push('missing-BOM banner rendered without its on-canvas 「辅料 / 包材 / 副产 cell」 guidance');
      }
      // 旧的「支走用户」入口已下线, 任何状态下都不该再出现
      if (legacyJumpCopyVisible) contractFailures.push('retired 「去 BOM 配置」 jump-away entry still rendered');
      if (legacyDrawerCopyVisible) contractFailures.push('legacy drawer copy 「在右侧配置 BOM」 still rendered');

      return {
        ...evidence,
        contractFailures,
        assessment: contractFailures.length === 0
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};

},
    "scenarios/bom-cost-caliber-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

/**
 * BOM 页的成本口径: 人工成本与均摊费用已从配置期整体下掉 —— 它们是报工之后
 * 结算/成本分析阶段才产生的数据, 在 BOM 配置期填的是拍脑袋的数。
 *
 * 页面上应只剩「当前归集成本」这一张卡(只聚包材; 原料实际用量待生产报工),
 * 「人工成本」「均摊费用」两块不应再出现在 BOM 页。
 *
 * 这条与后端 calculateProductCost 只对 PACKAGING 求和是同一口径的两个承载面;
 * 后端那面由 Java 单测钉住, 这里钉前端这面。
 */
const RETIRED_COST_BLOCKS = ['人工成本', '均摊费用', '人工与均摊'];

module.exports = {
  id: 'bom-cost-caliber-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-cost-caliber-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (_page, body) => {
      const aggregatedCostCardVisible = body.includes('当前归集成本');
      const retiredBlocksPresent = RETIRED_COST_BLOCKS.filter((label) => body.includes(label));

      const evidence = {
        aggregatedCostCardVisible,
        retiredBlocksPresent,
        retiredBlocksChecked: RETIRED_COST_BLOCKS,
        packagingCardVisible: body.includes('包材'),
        auxiliaryCardVisible: body.includes('工序辅料'),
        note: 'Read-only: no product is created, cloned, or saved.',
      };

      // 数据前提: BOM 页要先落到产品上下文才会渲染成本卡组。
      if (!body.includes('配方管理')) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: 'BOM page did not reach a product context; cost cards are not rendered',
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const contractFailures = [];
      if (!aggregatedCostCardVisible) contractFailures.push('aggregated cost card 「当前归集成本」 missing');
      for (const label of retiredBlocksPresent) {
        contractFailures.push(`retired cost block 「${label}」 still rendered on the BOM page`);
      }

      return {
        ...evidence,
        contractFailures,
        assessment: contractFailures.length === 0
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};

},
    "scenarios/bom-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

async function selectTargetProduct(page) {
  if (await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false)) {
    return { selected: true, selectedLabel: null, failure: null };
  }

  const productSelect = page.locator('.bom-hero-card .el-select, .header-card .el-select').first();
  const productInput = productSelect.locator('input').first();
  if (!(await productSelect.isVisible().catch(() => false))
      || !(await productInput.isVisible().catch(() => false))) {
    return { selected: false, selectedLabel: null, failure: 'product selector missing' };
  }

  await productSelect.click();
  await productInput.fill('干式熟成鸡 400g');
  const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)');
  let targetOption = options.filter({ hasText: '干式熟成鸡 400g' }).first();
  await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});

  if (!(await targetOption.isVisible().catch(() => false))) {
    await productInput.fill('干式熟成鸡');
    targetOption = options.filter({ hasText: /干式熟成鸡.*400g/ }).first();
    await targetOption.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => {});
  }
  if (!(await targetOption.isVisible().catch(() => false))) {
    await page.keyboard.press('Escape');
    const selectedBomLoaded = await page.locator(
      '[data-testid="bom-version-lifecycle"], .bom-summary-grid, .recipe-status-card',
    ).first().isVisible().catch(() => false);
    if (selectedBomLoaded) {
      const selectedLabel = (
        await page.locator('.bom-hero__sku').first().innerText().catch(() => '')
        || await productInput.inputValue().catch(() => '')
      ).trim() || null;
      return {
        selected: true,
        selectedLabel,
        selectionMode: 'deterministic-page-selection',
        failure: null,
      };
    }
    return { selected: false, selectedLabel: null, selectionMode: null, failure: 'target product option missing' };
  }

  const selectedLabel = (await targetOption.innerText()).trim();
  await targetOption.click();
  await page.getByText('BOM 配方版本', { exact: true })
    .waitFor({ state: 'visible', timeout: 15_000 })
    .catch(() => {});
  return {
    selected: await page.getByText('BOM 配方版本', { exact: true }).isVisible().catch(() => false),
    selectedLabel,
    selectionMode: 'target-search',
    failure: null,
  };
}

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: ROUTES.bom,
    landmarks: ['BOM'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const productSelection = await selectTargetProduct(page);
      const versionHistoryButton = page.getByRole('button', { name: '版本历史', exact: true }).first();
      if (await versionHistoryButton.isVisible().catch(() => false)) {
        await versionHistoryButton.click();
        await page.getByText('BOM 配方版本', { exact: true })
          .waitFor({ state: 'visible', timeout: 10_000 })
          .catch(() => {});
      }
      const activeRow = page.locator('.recipe-status-card .el-table__row')
        .filter({ hasText: '已生效' })
        .first();
      const lifecycle = page.locator('[data-testid="bom-version-lifecycle"]');
      let activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
        .isVisible()
        .catch(() => false);
      if (!activeViewOpened && await activeRow.isVisible().catch(() => false)) {
        const viewActive = activeRow.getByRole('button', { name: '查看', exact: true }).first();
        if (await viewActive.isVisible().catch(() => false)) {
          await viewActive.click();
          await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .waitFor({ state: 'visible', timeout: 10_000 })
            .catch(() => {});
          activeViewOpened = await lifecycle.filter({ hasText: /当前生效.*内容已锁定/ })
            .isVisible()
            .catch(() => false);
        }
      }
      const currentBody = await page.locator('body').innerText();
      const obsoleteControls = ['对话微调', 'Excel 导入', '一键重算出成率', 'kg/份']
        .filter((label) => currentBody.includes(label));
      const contract = {
        productSelection,
        hasVersionTable: currentBody.includes('BOM 配方版本'),
        hasHistoricalYield: currentBody.includes('系统历史出成率'),
        obsoleteControls,
        hasPricingUnit: /元\/(?:kg|g|袋|盒|箱)/.test(currentBody),
        hasSkuCostBasis: /元\/(?:袋|盒|箱|件|只|份)/.test(currentBody),
        tableCount: await page.locator('.el-table').count(),
        activeViewOpened,
        activeVersionVisible: /已生效/.test(currentBody),
        readOnlyGuidanceVisible: /当前生效.*内容已锁定|历史版本.*仅供查看/.test(currentBody),
        cloneActionVisible: false,
        addRawButtonVisible: false,
      };

      const rawTab = page.locator('.el-tabs__item').filter({ hasText: /^原料/ }).first();
      if (await rawTab.isVisible().catch(() => false)) await rawTab.click();
      const addRaw = page.getByRole('button', { name: /添加原料/ }).first();
      contract.addRawButtonVisible = await addRaw.isVisible().catch(() => false);
      contract.cloneActionVisible = await page.getByRole('button', {
        name: /克隆为新版本.*修改|前往 v\d+ 草稿.*修改|继续修改 v\d+ 草稿|新建版本/,
      }).first().isVisible().catch(() => false);
      const screenshots = [await ctx.screenshot('bom-active-readonly')];

      const failures = [];
      if (!productSelection.selected) failures.push(productSelection.failure || 'product selection failed');
      if (!contract.hasVersionTable) failures.push('BOM version table missing');
      if (!contract.hasHistoricalYield) failures.push('system historical yield missing');
      if (contract.obsoleteControls.length) failures.push('obsolete BOM controls visible');
      if (!contract.activeVersionVisible) failures.push('active BOM version missing');
      if (!contract.activeViewOpened) failures.push('active BOM view did not open');
      if (!contract.readOnlyGuidanceVisible) failures.push('active BOM read-only guidance missing');
      if (!contract.cloneActionVisible) failures.push('clone-to-new-version action missing');
      if (contract.addRawButtonVisible) failures.push('active BOM exposes raw material mutation action');
      return {
        ...contract,
        contractFailures: failures,
        screenshots,
        assessment: failures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'PASS', rootCauseClass: 'none' },
      };
    },
  }),
};

},
    "scenarios/finance-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'finance-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'finance-readonly',
    path: ROUTES.finance,
    landmarks: ['财务'],
    inspect: async (page, body) => ({ hasPaymentMethodColumn: /支付方式|付款方式/.test(body), tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/finished-inventory-filter-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');
async function inspect(page, _body, ctx) {
  await step(ctx, () => page.getByText('成品库存', { exact: true }).last().click());
  const table = page.getByTestId('finished-stock-summary');
  await table.waitFor({ state: 'visible', timeout: 15000 });
  await page.locator('.el-loading-mask').first().waitFor({ state: 'hidden', timeout: 15000 }).catch(() => {});
  const rows = table.locator('.el-table__body tr');
  await rows.first().waitFor({ state: 'visible', timeout: 15000 });
  const count = await rows.count();
  const name = (await rows.first().locator('td').first().innerText()).trim();
  if (!name) throw new Error('No named finished product to verify filtering');
  const input = page.getByPlaceholder('成品名称 / 产品ID', { exact: true });
  await step(ctx, () => input.fill(name));
  const names = await rows.locator('td:first-child').allTextContents();
  if (!names.length || names.some(v => !v.toLowerCase().includes(name.toLowerCase()))) throw new Error('Finished product filter includes unrelated products');
  const screenshots = [await ctx.screenshot('finished-filter-matched')];
  await step(ctx, () => input.fill('NO_MATCH_VISUAL_ACCEPTANCE_7e9c11'));
  await page.waitForFunction(() => document.querySelectorAll('[data-testid="finished-stock-summary"] .el-table__body tr').length === 0);
  await table.getByText('暂无符合条件的成品库存', { exact: true }).waitFor({ state: 'visible' });
  screenshots.push(await ctx.screenshot('finished-filter-empty'));
  await step(ctx, () => input.fill(''));
  await page.waitForFunction(n => document.querySelectorAll('[data-testid="finished-stock-summary"] .el-table__body tr').length === n, count);
  assertSafe(ctx);
  return { initialRows: count, filteredRows: names.length, noMatchEmpty: true, clearRestored: true, screenshots, limits: 'Read-only UI filtering; not an independent inventory/accounting/large-data performance audit', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'finished-inventory-filter-readonly', defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id: 'finished-inventory-filter-readonly', path: '/warehouse/inventory-query?tab=total', screenshot: true, inspect }) };

},
    "scenarios/label-qc-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'label-qc-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'label-qc-readonly',
    path: ROUTES.labelQc,
    landmarks: ['包装标签拍检', '待人工审核', '已审核整理', '归档记录'],
    screenshot: true,
    inspect: async (page) => ({
      reviewButtonCount: await page.getByRole('button', { name: '人工审核' }).count(),
      visibleTableCount: await page.locator('.el-table:visible').count(),
      note: 'Read-only page load only. Review, retry, archive, restore, backup, export, and training actions are intentionally not invoked.',
    }),
  }),
};

},
    "scenarios/material-consumption-price-mask-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { pathnameOf } = require('../core/url-utils');
const { runReadOnlyPageScenario } = require('./_shared');

const TARGET_BATCH_NUMBER = 'PB-PLAN-1787036063746-8719B078-74084';
const EXPECTED_ITEM_COUNT = 4;
const EXPECTED_ROLE = 'quality_inspector';
const ROOT_PRICE_KEYS = ['totalPlannedCost', 'totalActualCost', 'totalCost', 'subtotal'];
const ITEM_PRICE_KEYS = ['unitPrice', 'cost', 'totalCost', 'subtotal', 'lineAmount'];
const PRICE_KEY_PATTERN = /(price|cost|amount|subtotal)/i;

async function findTargetBatch(page, factoryId) {
  return page.evaluate(async ({ expectedFactoryId, batchNumber }) => {
    const pageSize = 100;
    for (let pageNumber = 1; pageNumber <= 50; pageNumber += 1) {
      const url = `/api/mobile/${encodeURIComponent(expectedFactoryId)}/processing/batches?page=${pageNumber}&size=${pageSize}`;
      const response = await fetch(url, { credentials: 'same-origin' });
      const payload = await response.json().catch(() => null);
      if (!response.ok || payload?.success === false) {
        return { ok: false, status: response.status, pageNumber, reason: 'batch-list-request-failed' };
      }
      const data = payload?.data || payload || {};
      const rows = Array.isArray(data.content) ? data.content : [];
      const match = rows.find((row) => String(row?.batchNumber || '') === batchNumber);
      if (match) {
        return {
          ok: true,
          status: response.status,
          pageNumber,
          id: String(match.id),
          batchNumber: String(match.batchNumber),
        };
      }
      const totalPages = Number(data.totalPages || 0);
      if ((totalPages > 0 && pageNumber >= totalPages) || rows.length < pageSize) break;
    }
    return { ok: false, status: 200, reason: 'target-batch-not-found' };
  }, { expectedFactoryId: factoryId, batchNumber: TARGET_BATCH_NUMBER });
}

async function inspectSummaryResponse(page, factoryId, batchId) {
  return page.evaluate(async ({ expectedFactoryId, productionBatchId, rootPriceKeys, itemPriceKeys }) => {
    const url = `/api/mobile/${encodeURIComponent(expectedFactoryId)}/processing/material-consumptions/batch/${encodeURIComponent(productionBatchId)}/summary`;
    const response = await fetch(url, { credentials: 'same-origin' });
    const payload = await response.json().catch(() => null);
    const data = payload?.data || {};
    const items = Array.isArray(data.items) ? data.items : [];
    const rootKeys = Object.keys(data);
    const itemKeys = [...new Set(items.flatMap((item) => Object.keys(item || {})))];
    const hasMaterialNames = items.every((item) => Boolean(item?.materialTypeName || item?.materialName));
    const hasPlannedAndActualQuantities = items.every((item) => item?.plannedQty != null && item?.actualQty != null);
    const forbiddenRootKeys = rootPriceKeys.filter((key) => rootKeys.includes(key));
    const forbiddenItemKeys = itemPriceKeys.filter((key) => itemKeys.includes(key));
    const suspiciousRootKeys = rootKeys.filter((key) => /(price|cost|amount|subtotal)/i.test(key));
    const suspiciousItemKeys = itemKeys.filter((key) => /(price|cost|amount|subtotal)/i.test(key));
    return {
      ok: response.ok && payload?.success !== false,
      status: response.status,
      itemCount: items.length,
      hasMaterialNames,
      hasPlannedAndActualQuantities,
      forbiddenRootKeyCount: forbiddenRootKeys.length,
      forbiddenItemKeyCount: forbiddenItemKeys.length,
      suspiciousRootKeyCount: suspiciousRootKeys.length,
      suspiciousItemKeyCount: suspiciousItemKeys.length,
      forbiddenRootKeys,
      forbiddenItemKeys,
      suspiciousRootKeys,
      suspiciousItemKeys,
      rootKeys,
      itemKeys,
    };
  }, {
    expectedFactoryId: factoryId,
    productionBatchId: batchId,
    rootPriceKeys: ROOT_PRICE_KEYS,
    itemPriceKeys: ITEM_PRICE_KEYS,
  });
}

const scenario = {
  id: 'material-consumption-price-mask-readonly',
  path: ROUTES.bomAchievement,
  targetBatchNumber: TARGET_BATCH_NUMBER,
  expectedItemCount: EXPECTED_ITEM_COUNT,
  expectedRole: EXPECTED_ROLE,
  rootPriceKeys: ROOT_PRICE_KEYS,
  itemPriceKeys: ITEM_PRICE_KEYS,
  priceKeyPattern: PRICE_KEY_PATTERN,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'material-consumption-price-mask-readonly',
    path: '/mobile-only?role=quality_inspector',
    landmarks: ['请使用移动端 App'],
    timeoutMs: 60_000,
    inspect: async (page) => {
      const target = await findTargetBatch(page, ctx.expectedFactoryId);
      if (!target.ok || !target.id) {
        return {
          targetBatchFound: false,
          targetLookup: target,
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' },
        };
      }

      const summary = await inspectSummaryResponse(page, ctx.expectedFactoryId, target.id);
      const screenshot = await ctx.screenshot('material-consumption-price-mask-quality-inspector');
      const authenticatedRole = String(ctx.loginEvidence?.role || '').toLowerCase();
      const pass = authenticatedRole === EXPECTED_ROLE
        && ctx.loginEvidence?.landingPath === '/mobile-only'
        && target.batchNumber === TARGET_BATCH_NUMBER
        && summary.ok
        && summary.itemCount === EXPECTED_ITEM_COUNT
        && summary.hasMaterialNames
        && summary.hasPlannedAndActualQuantities
        && summary.forbiddenRootKeyCount === 0
        && summary.forbiddenItemKeyCount === 0
        && summary.suspiciousRootKeyCount === 0
        && summary.suspiciousItemKeyCount === 0;

      return {
        targetBatchFound: true,
        authenticatedRole,
        targetBatchNumber: target.batchNumber,
        targetBatchPage: target.pageNumber,
        webBoundary: {
          landingPath: pathnameOf(page.url()),
          mobileOnlyExpected: true,
          appUiFollowupRequired: true,
        },
        summary,
        screenshots: [screenshot],
        assessment: pass
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'backend' },
      };
    },
  }),
};

module.exports = scenario;

},
    "scenarios/production-boards-readonly.js": function(module, exports, require) {
'use strict';

const followup = require('./visual-unverified-followup-readonly');
const id = 'production-boards-readonly';
const targets = Object.freeze(followup.FOLLOWUP_PAGES.filter(page =>
  ['pending-queue', 'restock-board', 'bom-achievement'].includes(page.id)));

// Independently bounded acceptance. The full follow-up retains label-QC and
// still fails on its blocked telemetry; this scenario never visits that page.
module.exports = { id, defaultEnabled: false, targets,
  run: ctx => followup.run(ctx, targets, id) };

},
    "scenarios/production-plan-routing-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

const TOPOLOGY_KEYS = ['1_TO_1', '1_TO_MANY', 'MANY_TO_1', 'MANY_TO_MANY'];

function topologyKey(candidate) {
  const inputCount = new Set(candidate?.rootInputProductTypeIds || []).size;
  const outputCount = new Set((candidate?.terminalOutputs || []).map((item) => item.productTypeId).filter(Boolean)).size;
  if (candidate?.workflowType === 'RAW_MATERIAL_SPLIT') return '1_TO_MANY';
  if (candidate?.workflowType === 'JOINT_PRODUCTION') return 'MANY_TO_MANY';
  if (outputCount === 1 && inputCount <= 1) return '1_TO_1';
  // The resolver DTO exposes physical raw roots but not the EXACTLY_ONE logical
  // grouping needed to distinguish alternatives from simultaneous many-to-one.
  if (outputCount === 1 && inputCount > 1) return 'MULTI_RAW_TO_1_UNQUALIFIED';
  return 'UNQUALIFIED';
}

function chooseRepresentatives(inventory) {
  const byTopology = {};
  let ambiguous = null;
  let superset = null;
  for (const item of inventory) {
    if (!ambiguous && item.candidates.length > 1) ambiguous = item;
    for (const candidate of item.candidates) {
      if (!byTopology[candidate.topology]) byTopology[candidate.topology] = item;
      if (!superset && candidate.exactMatch === false) superset = item;
    }
  }
  return { byTopology, ambiguous, superset };
}

async function inventoryWorkflowRoutes(page, expectedFactoryId) {
  return page.evaluate(async ({ factoryId, topologyKeys }) => {
    const apiRoot = `/api/mobile/${encodeURIComponent(factoryId)}`;
    const readJson = async (url, init) => {
      const response = await fetch(url, { credentials: 'same-origin', ...init });
      const payload = await response.json().catch(() => null);
      return { ok: response.ok, status: response.status, payload };
    };
    const productResponse = await readJson(`${apiRoot}/product-types/active`);
    const rawRows = productResponse.payload?.data?.content || productResponse.payload?.data || [];
    const excluded = new Set(['RAW_MATERIAL', 'PACKAGING', 'SEASONING']);
    const products = (Array.isArray(rawRows) ? rawRows : [])
      .filter((row) => row?.id && !excluded.has(String(row.productCategory || '')))
      .map((row) => ({ id: String(row.id), name: String(row.name || row.id) }));
    const priority = /E2E|替代|熟成|联产|鸡|DEMO|测试/i;
    products.sort((left, right) => Number(priority.test(right.name)) - Number(priority.test(left.name)));

    const records = [];
    const coverage = new Set();
    let ambiguousFound = false;
    let supersetFound = false;
    let resolverCalls = 0;
    for (const product of products.slice(0, 200)) {
      const resolution = await readJson(`${apiRoot}/product-process-workflows/resolve-by-outputs`, {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ productTypeIds: [product.id] }),
      });
      resolverCalls += 1;
      const candidates = resolution.payload?.data?.candidates || [];
      if (!resolution.ok || !Array.isArray(candidates) || candidates.length === 0) continue;
      const normalized = candidates.map((candidate) => {
        const roots = [...new Set(candidate.rootInputProductTypeIds || [])];
        const outputs = (candidate.terminalOutputs || []).map((output) => ({
          id: String(output.productTypeId || ''),
          name: String(output.productName || output.productTypeId || ''),
        })).filter((output) => output.id);
        const workflowType = String(candidate.workflowType || '');
        const topology = workflowType === 'RAW_MATERIAL_SPLIT'
          ? '1_TO_MANY'
          : workflowType === 'JOINT_PRODUCTION'
            ? 'MANY_TO_MANY'
            : outputs.length === 1 && roots.length <= 1
              ? '1_TO_1'
              : outputs.length === 1 && roots.length > 1
                ? 'MULTI_RAW_TO_1_UNQUALIFIED'
                : 'UNQUALIFIED';
        coverage.add(topology);
        if (candidate.exactMatch === false) supersetFound = true;
        return {
          workflowId: candidate.workflowId,
          definitionVersion: candidate.definitionVersion,
          workflowType,
          topology,
          exactMatch: candidate.exactMatch === true,
          rootInputCount: roots.length,
          terminalOutputs: outputs,
          processSteps: (candidate.processSteps || []).map(String),
          previewNodeCount: (candidate.previewNodes || []).length,
          previewEdgeCount: (candidate.previewEdges || []).length,
        };
      });
      ambiguousFound ||= normalized.length > 1;
      records.push({ selection: [product], candidates: normalized });
      if (topologyKeys.every((key) => coverage.has(key)) && ambiguousFound && supersetFound) break;
    }
    return {
      activeFinishedGoodCount: products.length,
      resolverCalls,
      records,
      coverage: [...coverage],
      ambiguousFound,
      supersetFound,
    };
  }, { factoryId: expectedFactoryId, topologyKeys: TOPOLOGY_KEYS });
}

async function selectProducts(page, planDialog, names) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  const wrapper = productField.locator('.el-select__wrapper');
  const input = productField.locator('input.el-select__input');
  for (let index = 0; index < names.length; index += 1) {
    await wrapper.click();
    await input.waitFor({ state: 'attached', timeout: 8_000 });
    await input.fill(names[index]);
    const option = page.locator('.el-select-dropdown:visible .el-select-dropdown__item')
      .filter({ hasText: names[index] })
      .first();
    await option.waitFor({ state: 'visible', timeout: 8_000 });
    const finalResponse = index === names.length - 1
      ? page.waitForResponse((response) => response.request().method() === 'POST'
          && response.url().includes('/product-process-workflows/resolve-by-outputs'), { timeout: 15_000 })
      : null;
    await option.click();
    if (finalResponse) await finalResponse;
  }
  await page.keyboard.press('Escape').catch(() => {});
  await page.keyboard.press('Tab').catch(() => {});
  const dateLabel = planDialog.locator('.el-form-item__label').filter({ hasText: '计划生产日' }).first();
  if (await dateLabel.isVisible().catch(() => false)) await dateLabel.click();
  await page.locator('.el-select-dropdown:visible').waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {});
  await page.getByText('正在解析工序图…').waitFor({ state: 'hidden', timeout: 15_000 }).catch(() => {});
}

async function clearSelectedProducts(planDialog) {
  const productField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
  for (let attempts = 0; attempts < 12; attempts += 1) {
    const closer = productField.locator('.el-tag__close').first();
    if (!await closer.isVisible().catch(() => false)) break;
    await closer.click();
  }
}

async function inspectUiCase(ctx, planDialog, item, caseId) {
  const names = item.selection.map((product) => product.name);
  await selectProducts(ctx.page, planDialog, names);
  const routeDialog = ctx.page.getByRole('dialog', { name: '选择本计划使用的生产工序路线' });
  const decisionVisible = await routeDialog.isVisible().catch(() => false);
  const selectedRoute = planDialog.locator('.selected-workflow-route');
  const autoResolved = await selectedRoute.isVisible().catch(() => false);
  const trigger = decisionVisible
    ? routeDialog.locator('.workflow-preview-trigger').first()
    : selectedRoute.getByRole('button', { name: '悬浮查看 Cell 图' }).first();
  let preview = { visible: false, nodeCount: 0, edgeCount: 0, labels: [] };
  if (await trigger.isVisible().catch(() => false)) {
    await trigger.scrollIntoViewIfNeeded();
    await trigger.hover();
    const previewRoot = ctx.page.locator('[data-testid="workflow-route-preview"]:visible').first();
    await previewRoot.waitFor({ state: 'visible', timeout: 8_000 }).catch(() => {});
    preview = {
      visible: await previewRoot.isVisible().catch(() => false),
      nodeCount: await previewRoot.locator('.preview-cell').count(),
      edgeCount: await previewRoot.locator('.preview-edge').count(),
      labels: (await previewRoot.locator('.preview-cell').allInnerTexts()).slice(0, 12),
    };
  }
  const screenshot = await ctx.screenshot(`production-plan-routing-${caseId}`);
  const evidence = {
    caseId,
    selectedProducts: names,
    expectedTopologies: [...new Set(item.candidates.map((candidate) => candidate.topology))],
    candidateCountFromResolver: item.candidates.length,
    decisionDialogVisible: decisionVisible,
    candidateCardCount: decisionVisible ? await routeDialog.locator('.workflow-candidate-card').count() : 0,
    autoResolved,
    preview,
    screenshot,
  };
  if (decisionVisible) await routeDialog.getByRole('button', { name: '暂不选择' }).click();
  await clearSelectedProducts(planDialog);
  return evidence;
}

module.exports = {
  id: 'production-plan-routing-readonly',
  topologyKey,
  chooseRepresentatives,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'production-plan-routing-readonly',
    path: ROUTES.productionPlans,
    landmarks: ['生产计划管理'],
    timeoutMs: 60_000,
    inspect: async (page) => {
      await page.getByRole('button', { name: '新建计划', exact: true }).click();
      const planDialog = page.getByRole('dialog', { name: '新建生产计划' });
      await planDialog.waitFor({ state: 'visible', timeout: 10_000 });
      const manualSource = planDialog.getByRole('radio', { name: '手动', exact: true });
      await manualSource.check({ force: true });
      if (!await manualSource.isChecked()) throw new Error('Manual production source radio did not become checked');
      const productionField = planDialog.locator('.el-form-item').filter({ hasText: '生产成品' }).first();
      await productionField.waitFor({ state: 'visible', timeout: 10_000 });
      await productionField.locator('.el-select__wrapper').waitFor({ state: 'visible', timeout: 10_000 });
      const inventory = await inventoryWorkflowRoutes(page, ctx.expectedFactoryId);
      const representatives = chooseRepresentatives(inventory.records);

      const uiCases = [];
      const usedSelections = new Set();
      for (const topology of TOPOLOGY_KEYS) {
        const item = representatives.byTopology[topology];
        if (!item) continue;
        const key = item.selection.map((product) => product.id).sort().join(',');
        if (usedSelections.has(key)) continue;
        usedSelections.add(key);
        uiCases.push(await inspectUiCase(ctx, planDialog, item, topology.toLowerCase()));
      }
      if (representatives.ambiguous) {
        const key = representatives.ambiguous.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.ambiguous, 'ambiguous'));
        }
      }
      if (representatives.superset) {
        const key = representatives.superset.selection.map((product) => product.id).sort().join(',');
        if (!usedSelections.has(key)) {
          usedSelections.add(key);
          uiCases.push(await inspectUiCase(ctx, planDialog, representatives.superset, 'superset'));
        }
      }
      await planDialog.getByRole('button', { name: '取消', exact: true }).click();

      const topologyCoverage = Object.fromEntries(TOPOLOGY_KEYS.map((key) => [key, inventory.coverage.includes(key)]));
      const previewCases = uiCases.filter((item) => item.preview.visible && item.preview.nodeCount > 0).length;
      const expectedCaseCount = Object.values(topologyCoverage).filter(Boolean).length;
      const complete = Object.values(topologyCoverage).every(Boolean)
        && uiCases.length >= expectedCaseCount
        && previewCases === uiCases.length;
      return {
        activeFinishedGoodCount: inventory.activeFinishedGoodCount,
        resolverCalls: inventory.resolverCalls,
        topologyCoverage,
        unqualifiedMultiRawSingleOutputFound: inventory.coverage.includes('MULTI_RAW_TO_1_UNQUALIFIED'),
        ambiguousFound: inventory.ambiguousFound,
        supersetFound: inventory.supersetFound,
        uiCaseSummaries: uiCases.map((item) => ({
          caseId: item.caseId,
          selectedProducts: item.selectedProducts,
          expectedTopologies: item.expectedTopologies,
          candidateCountFromResolver: item.candidateCountFromResolver,
          decisionDialogVisible: item.decisionDialogVisible,
          candidateCardCount: item.candidateCardCount,
          autoResolved: item.autoResolved,
          previewVisible: item.preview.visible,
          previewNodeCount: item.preview.nodeCount,
          previewEdgeCount: item.preview.edgeCount,
          previewLabels: item.preview.labels,
        })),
        note: 'The create dialog was cancelled; no production plan was submitted.',
        screenshots: uiCases.map((item) => item.screenshot),
        assessment: complete
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'UNVERIFIED', rootCauseClass: 'data' },
      };
    },
  }),
};

},
    "scenarios/purchasing-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'purchasing-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'purchasing-readonly',
    path: ROUTES.purchasing,
    landmarks: ['采购'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/restaurant-live-command-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { pathnameOf } = require('../core/url-utils');

const STAFFING_METRIC_KEYS = [
  'reservationOrders', 'liveGuests', 'predictedGuests', 'staffing', 'gap', 'confidence',
];
const EXPECTED_TRANSMISSION_LABELS = [
  '今日经营汇总',
  '预订 / POS / 客流',
  '预测 FactBook',
  '大模型解释',
];

module.exports = {
  id: 'restaurant-live-command-readonly',
  path: ROUTES.dashboard,
  expectedTransmissionLabels: EXPECTED_TRANSMISSION_LABELS,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'restaurant-live-command-readonly',
    path: ROUTES.dashboard,
    landmarks: ['餐饮 AI 实时经营指挥屏', '数据传输链路', '明日预测客流', '连锁预订事件流'],
    screenshot: true,
    inspect: async (page) => {
      await page.waitForFunction(() => {
        const value = document.querySelector(
          '[data-testid="restaurant-live-metric-predictedGuests"] .digital-metric__value',
        );
        return value && value.textContent.trim() !== '—';
      }, null, { timeout: 15_000 }).catch(() => {});
      const command = page.locator('[data-testid="restaurant-live-command"]');
      const metricValues = Object.fromEntries(await Promise.all(STAFFING_METRIC_KEYS.map(async (key) => {
        const value = await page
          .locator(`[data-testid="restaurant-live-metric-${key}"] .digital-metric__value`)
          .innerText()
          .catch(() => '');
        return [key, value.trim()];
      })));
      const populatedMetricCount = Object.values(metricValues)
        .filter((value) => value && value !== '—')
        .length;
      const finalPath = pathnameOf(page.url());
      const commandCount = await command.count();
      const streamCount = await page.locator('[data-testid="restaurant-live-stream"]').count();
      const transmissionLabels = (await command.locator('.transmission-rail li strong').allInnerTexts())
        .map((label) => label.trim());
      const transmissionLabel = await command.locator('.live-command__eyebrow').innerText().catch(() => '');
      const loadError = await page.locator('.load-alert.el-alert--error').innerText().catch(() => '');
      const assessment = finalPath === ROUTES.dashboard
        && commandCount === 1
        && streamCount === 1
        && transmissionLabels.length === EXPECTED_TRANSMISSION_LABELS.length
        && transmissionLabels.every((label, index) => label === EXPECTED_TRANSMISSION_LABELS[index])
        && populatedMetricCount === STAFFING_METRIC_KEYS.length
        && !loadError
        ? { result: 'PASS', rootCauseClass: 'none' }
        : { result: 'CONFIRMED_DEFECT', rootCauseClass: loadError ? 'backend' : 'frontend' };
      return {
        finalPath,
        commandCount,
        streamCount,
        transmissionNodeCount: transmissionLabels.length,
        transmissionLabels,
        transmissionLabel,
        staffingMetricValues: metricValues,
        populatedMetricCount,
        loadError: loadError || null,
        assessment,
      };
    },
  }),
};

},
    "scenarios/restaurant-staffing-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { pathnameOf } = require('../core/url-utils');

module.exports = {
  id: 'restaurant-staffing-readonly',
  path: ROUTES.restaurantStaffing,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'restaurant-staffing-readonly',
    path: ROUTES.restaurantStaffing,
    landmarks: ['预测 FactBook', '各门店 · 各时段', '问餐饮 AI'],
    screenshot: true,
    inspect: async (page) => {
      const finalPath = pathnameOf(page.url());
      const loadError = await page.locator('.page-alert.el-alert--error').innerText().catch(() => '');
      const metricCardCount = await page.locator('.metric-grid .metric-card').count();
      const tableCount = await page.locator('.table-card .el-table').count();
      const assessment = finalPath === ROUTES.restaurantStaffing
        && !loadError
        && metricCardCount === 6
        && tableCount === 1
        ? { result: 'PASS', rootCauseClass: 'none' }
        : { result: 'CONFIRMED_DEFECT', rootCauseClass: loadError ? 'backend' : 'frontend' };
      return {
        finalPath,
        loadError: loadError || null,
        metricCardCount,
        tableCount,
        assessment,
      };
    },
  }),
};

},
    "scenarios/role-menu-census-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { pathnameOf } = require('../core/url-utils');
const { runReadOnlyPageScenario } = require('./_shared');

const ERROR_MARKERS = [
  '403',
  '无权访问',
  '没有权限',
  '权限不足',
  '页面不存在',
  '加载失败',
  '请求失败',
  '服务器错误',
];

async function visibleMenuTitles(page, selector) {
  return page.locator(selector).evaluateAll((nodes) => nodes
    .filter((node) => {
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    })
    .map((node) => String(node.textContent || '').replace(/\s+/g, ' ').trim())
    .filter(Boolean));
}

async function inspectCurrentPage(page, title) {
  await page.waitForLoadState('networkidle', { timeout: 4_000 }).catch(() => {});
  const body = await page.locator('body').innerText().catch(() => '');
  const markers = ERROR_MARKERS.filter((marker) => body.includes(marker));
  return {
    title,
    path: pathnameOf(page.url()),
    bodyTextLength: body.trim().length,
    errorMarkers: markers,
    failed: body.trim().length < 40 || markers.length > 0,
  };
}

async function crawlVisibleRoleMenu(page, ctx) {
  const visitedTitles = new Set();
  const openedGroupTitles = new Set();
  const pages = [];

  for (let iteration = 0; iteration < 240; iteration += 1) {
    const leafTitles = await visibleMenuTitles(page, '.app-sidebar .el-menu-item');
    const nextLeafTitle = leafTitles.find((title) => !visitedTitles.has(title));
    if (nextLeafTitle) {
      const leaf = page.locator('.app-sidebar .el-menu-item:visible')
        .filter({ hasText: nextLeafTitle })
        .first();
      const beforeUrl = page.url();
      await leaf.click();
      visitedTitles.add(nextLeafTitle);
      await page.waitForFunction(
        ({ beforeUrl, title }) => title === '首页' || window.location.href !== beforeUrl,
        { beforeUrl, title: nextLeafTitle },
        { timeout: 3_000 },
      ).catch(() => {});
      const inspected = await inspectCurrentPage(page, nextLeafTitle);
      if (inspected.failed) {
        inspected.screenshot = await ctx.screenshot(`role-menu-failure-${pages.length + 1}`);
      }
      pages.push(inspected);
      continue;
    }

    const groupTitles = await visibleMenuTitles(page, '.app-sidebar .el-sub-menu__title');
    const nextGroupTitle = groupTitles.find((title) => !openedGroupTitles.has(title));
    if (nextGroupTitle) {
      const group = page.locator('.app-sidebar .el-sub-menu__title:visible')
        .filter({ hasText: nextGroupTitle })
        .first();
      await group.click();
      openedGroupTitles.add(nextGroupTitle);
      continue;
    }
    break;
  }

  return { pages, openedGroupTitles: [...openedGroupTitles] };
}

const scenario = {
  id: 'role-menu-census-readonly',
  path: ROUTES.dashboard,
  errorMarkers: ERROR_MARKERS,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'role-menu-census-readonly',
    path: ROUTES.dashboard,
    landmarks: [],
    timeoutMs: 60_000,
    inspect: async (page) => {
      const crawl = await crawlVisibleRoleMenu(page, ctx);
      const failedPages = crawl.pages.filter((item) => item.failed);
      const uniquePaths = [...new Set(crawl.pages.map((item) => item.path))];
      const pass = crawl.pages.length > 0
        && uniquePaths.length >= Math.min(2, crawl.pages.length)
        && failedPages.length === 0;
      return {
        authenticatedRole: ctx.loginEvidence?.role || null,
        visitedPageCount: crawl.pages.length,
        uniquePathCount: uniquePaths.length,
        openedGroupCount: crawl.openedGroupTitles.length,
        failedPageCount: failedPages.length,
        visitedPathSummary: crawl.pages.map((item) => `${item.title}:${item.path}`).join(' | '),
        failedPathSummary: failedPages.map((item) => `${item.title}:${item.path}:${item.errorMarkers.join(',')}`).join(' | '),
        assessment: pass
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};

module.exports = scenario;

},
    "scenarios/sales-date-draft-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe, verifyTarget } = require('./visual-unverified-followup-readonly');
const { pathnameOf } = require('../core/url-utils');
module.exports = { id: 'sales-date-draft-readonly', defaultEnabled: false, run: ctx => runReadOnlyPageScenario(ctx, {
  id: 'sales-date-draft-readonly', path: '/sales/orders/new', screenshot: false,
  inspect: async page => {
    await verifyTarget(page, ctx, '/sales/orders/new', '新建销售订单');
    const input = page.getByRole('textbox', { name: '交货日期', exact: true });
    const screenshots = [];
    await step(ctx, () => input.fill('20300115'));
    await step(ctx, () => input.press('Tab'));
    await page.waitForFunction(() => document.querySelector('input[aria-label="交货日期"]')?.value === '2030-01-15', null, { timeout: 5000 });
    const valid = await input.inputValue();
    screenshots.push(await ctx.screenshot('sales-date-valid'));
    await step(ctx, () => input.fill('20260229'));
    await step(ctx, () => input.press('Tab'));
    const error = page.getByRole('alert').filter({ hasText: '日期无效' });
    await error.waitFor({ state: 'visible', timeout: 5000 });
    const invalid = await input.getAttribute('aria-invalid') === 'true';
    screenshots.push(await ctx.screenshot('sales-date-invalid'));
    await step(ctx, () => page.locator('.order-delivery-date .calendar-picker').click({ timeout: 5000 }));
    const calendar = page.locator('.el-picker-panel:visible');
    await calendar.waitFor({ state: 'visible', timeout: 5000 });
    screenshots.push(await ctx.screenshot('sales-date-calendar'));
    await step(ctx, () => calendar.locator('td.available:not(.disabled):not(.prev-month):not(.next-month)').first().click({ timeout: 5000 }));
    await error.waitFor({ state: 'hidden', timeout: 5000 });
    const chosen = await input.inputValue();
    const calendarRecovered = /^\d{4}-\d{2}-\d{2}$/.test(chosen) && await input.getAttribute('aria-invalid') === 'false';
    screenshots.push(await ctx.screenshot('sales-date-calendar-selected'));
    // Clear our only draft field before client-side leave; never invoke save validation.
    await step(ctx, () => input.fill(''));
    await step(ctx, () => input.press('Tab'));
    // Tab can reopen the calendar. Dismiss its popup before the page action.
    await step(ctx, () => page.keyboard.press('Escape'));
    await calendar.waitFor({ state: 'hidden', timeout: 5000 });
    await step(ctx, () => page.getByRole('button', { name: '取消', exact: true }).first().click({ timeout: 5000 }));
    const leave = page.getByRole('button', { name: '离开', exact: true });
    // Element Plus mounts the confirmation asynchronously after the click.
    // A clean draft can navigate directly; wait for either actual outcome.
    try {
      await Promise.race([
        page.waitForURL('**/sales/orders', { timeout: 8000 }),
        leave.waitFor({ state: 'visible', timeout: 8000 }),
      ]);
      if (await leave.isVisible()) await step(ctx, () => leave.click({ timeout: 5000 }));
      await page.waitForURL('**/sales/orders', { timeout: 8000 });
    } catch {
      assertSafe(ctx);
      screenshots.push(await ctx.screenshot('sales-date-leave-unverified'));
      return { validDate: valid, invalidInputDisplayed: invalid, calendarRecovered,
        leftWithoutSave: false, finalPath: pathnameOf(page.url()),
        visibleDialogs: await page.getByRole('dialog').count(), screenshots,
        limitation: 'Client-side leave did not reach the expected list URL; no save submitted',
        assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
    }
    assertSafe(ctx);
    return { validDate: valid, invalidInputDisplayed: invalid, backendValidation: 'UNVERIFIED: no save submitted', calendarRecovered, draftCleared: true, leftWithoutSave: true, screenshots, assessment: { result: invalid && calendarRecovered ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: invalid && calendarRecovered ? 'none' : 'frontend' } };
  },
}) };

},
    "scenarios/sales-order-lifecycle-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

const EXPECTED_LIFECYCLES = ['全部', '待处理', '审批中', '待履约', '履约中', '已完成', '已关闭'];

function normalizedTabLabel(value) {
  return String(value || '').replace(/\d+/g, '').replace(/\s+/g, '');
}

async function waitForTableSettled(page) {
  await page.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 30_000 });
  await Promise.race([
    page.locator('.el-table__body-wrapper tbody tr').first().waitFor({ state: 'visible', timeout: 30_000 }),
    page.locator('.el-table__empty-block').first().waitFor({ state: 'visible', timeout: 30_000 }),
  ]);
}

function isSalesOrderListResponse(response) {
  const request = response.request();
  const urlWithoutQuery = String(response.url() || '').split(/[?#]/, 1)[0];
  return request.method() === 'GET'
    && /\/sales\/orders(?:\/by-status)?$/.test(urlWithoutQuery);
}

async function interactAndWaitForSalesOrderRefresh(page, action) {
  const responsePromise = page.waitForResponse(isSalesOrderListResponse, { timeout: 30_000 });
  await action();
  const response = await responsePromise;
  if (!response.ok()) {
    throw new Error(`Sales order list refresh failed with HTTP ${response.status()}`);
  }
  await waitForTableSettled(page);
}

async function selectReadOnlyDimension(page, label, optionLabel) {
  const control = page.getByRole('combobox', { name: label }).first();
  await control.waitFor({ state: 'visible' });
  const tagName = await control.evaluate((element) => element.tagName);
  if (tagName === 'SELECT') {
    await interactAndWaitForSalesOrderRefresh(
      page,
      () => control.selectOption({ label: optionLabel }),
    );
    return control.locator('option:checked').innerText();
  }

  const wrapper = control
    .locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    .locator('.el-select__wrapper');
  await wrapper.click();
  await interactAndWaitForSalesOrderRefresh(
    page,
    () => page.getByRole('option', { name: optionLabel, exact: true }).click(),
  );
  return wrapper.innerText();
}

module.exports = {
  id: 'sales-order-lifecycle-readonly',
  defaultEnabled: false,
  path: ROUTES.salesOrders,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'sales-order-lifecycle-readonly',
    path: ROUTES.salesOrders,
    landmarks: ['销售订单管理', '待处理', '审批中', '待履约', '履约中', '已完成', '已关闭'],
    timeoutMs: 60_000,
    inspect: async (page, _body, scenarioCtx) => {
      await page.locator('.sales-lifecycle-tabs').waitFor({ state: 'visible', timeout: 30_000 });
      await page.locator('.el-table__body-wrapper').waitFor({ state: 'visible', timeout: 30_000 });
      await waitForTableSettled(page);

      const tabs = page.locator('.sales-lifecycle-tabs [role="tab"]');
      const lifecycleLabels = (await tabs.allInnerTexts()).map(normalizedTabLabel);
      const lifecycleExact = EXPECTED_LIFECYCLES.every((label) => lifecycleLabels.includes(label))
        && lifecycleLabels.length === EXPECTED_LIFECYCLES.length;
      const shipmentFilterCount = await page.getByRole('combobox', { name: '出库状态' }).count();
      const paymentFilterCount = await page.getByRole('combobox', { name: '收款状态' }).count();
      const nextActionHeaderCount = await page
        .locator('.el-table__header-wrapper th')
        .filter({ hasText: '下一步' })
        .count();
      const orderRowCount = await page.locator('.el-table__body-wrapper tbody tr').count();

      let traceExpanded = false;
      let traceSameViewport = false;
      let traceLabels = [];
      const screenshots = [];
      const expandButton = page.locator('.el-table__expand-icon').first();
      if (await expandButton.count()) {
        await expandButton.click();
        const trace = page.locator('.order-trace-panel').first();
        await trace.waitFor({ state: 'visible' });
        traceLabels = await trace.locator('.trace-section-title').allInnerTexts();
        const viewportWidth = page.viewportSize()?.width || 0;
        const boxes = await trace.locator('.trace-section').evaluateAll((elements) => elements.map((element) => {
          const rect = element.getBoundingClientRect();
          return { left: rect.left, right: rect.right, width: rect.width };
        }));
        traceExpanded = ['订单明细', '出库履约', '收款进度'].every((label) => traceLabels.includes(label));
        traceSameViewport = boxes.length === 3
          && boxes.every((box) => box.left >= 0 && box.right <= viewportWidth + 1 && box.width > 0);
        screenshots.push(await scenarioCtx.screenshot('sales-order-lifecycle-expanded'));
      }

      const shipmentValue = await selectReadOnlyDimension(page, '出库状态', '部分出库');
      const paymentValue = await selectReadOnlyDimension(page, '收款状态', '部分收款');
      const combinationApplied = shipmentValue.includes('部分出库') && paymentValue.includes('部分收款');
      const filteredRows = page.locator('.el-table__body-wrapper tbody tr');
      const filteredRowTexts = await filteredRows.allInnerTexts();
      const combinationSemanticsVerified = filteredRowTexts.length > 0
        && filteredRowTexts.every((text) => text.includes('部分出库') && text.includes('部分收款'));

      const fulfillingTab = tabs.filter({ hasText: '履约中' }).first();
      await interactAndWaitForSalesOrderRefresh(page, () => fulfillingTab.click());
      const fulfillmentSelected = await fulfillingTab.getAttribute('aria-selected') === 'true'
        || (await fulfillingTab.getAttribute('class') || '').includes('is-active');

      await selectReadOnlyDimension(page, '出库状态', '全部出库状态');
      await selectReadOnlyDimension(page, '收款状态', '全部收款状态');
      await interactAndWaitForSalesOrderRefresh(
        page,
        () => tabs.filter({ hasText: '全部' }).first().click(),
      );

      const structuralPassed = lifecycleExact
        && shipmentFilterCount === 1
        && paymentFilterCount === 1
        && nextActionHeaderCount === 1
        && combinationApplied
        && combinationSemanticsVerified
        && fulfillmentSelected;
      const fullyVerified = structuralPassed && orderRowCount > 0 && traceExpanded && traceSameViewport;
      return {
        lifecycleLabels,
        shipmentFilterCount,
        paymentFilterCount,
        nextActionHeaderCount,
        orderRowCount,
        traceLabels,
        traceExpanded,
        traceSameViewport,
        combinationApplied,
        combinationSemanticsVerified,
        filteredRowCount: filteredRowTexts.length,
        fulfillmentSelected,
        screenshots,
        assessment: fullyVerified
          ? { result: 'PASS', rootCauseClass: 'none' }
          : orderRowCount === 0 || filteredRowTexts.length === 0
            ? { result: 'UNVERIFIED', rootCauseClass: 'data' }
            : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};

},
    "scenarios/supplier-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'supplier-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'supplier-readonly',
    path: ROUTES.suppliers,
    landmarks: ['供应商'],
    inspect: async (page) => ({ tableCount: await page.locator('.el-table').count() }),
  }),
};

},
    "scenarios/table-grid-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertGrid(data) {
  if (!data.columns.length || data.rows < 1) throw new Error('UNVERIFIED: no populated table was measured');
  for (const column of data.columns) {
    if (column.header <= 0 || column.body <= 0 || Math.abs(column.header - column.body) >= 2) throw new Error('Table header/body width mismatch');
    if (column.right !== '1px' || column.bottom !== '1px') throw new Error('Table cell grid is incomplete');
  }
  if (data.outline !== '1px') throw new Error('Table viewport outline is missing');
}
function scenario(id, path, resize) {
  const inspect = async (page, _body, ctx) => {
    const table = page.locator('main.app-content .el-table').first();
    await table.locator('.el-table__body tbody tr').first().waitFor({ state: 'visible', timeout: 15000 });
    const measure = () => table.evaluate(el => {
      const heads = [...el.querySelectorAll('.el-table__header th:not(.gutter)')];
      const cells = [...el.querySelector('.el-table__body tbody tr').querySelectorAll('td')];
      return { rows: el.querySelectorAll('.el-table__body tbody tr').length,
        rowHeight: el.querySelector('.el-table__body tbody tr').getBoundingClientRect().height,
        outline: getComputedStyle(el).outlineWidth,
        columns: heads.map((header, i) => ({ header: header.getBoundingClientRect().width,
          body: cells[i]?.getBoundingClientRect().width || 0,
          right: cells[i] ? getComputedStyle(cells[i]).borderRightWidth : null,
          bottom: cells[i] ? getComputedStyle(cells[i]).borderBottomWidth : null })) };
    });
    const measurements = [], screenshots = [];
    for (const width of [1440, 1024]) {
      await page.setViewportSize({ width, height: 900 }); await page.waitForTimeout(200);
      const data = await measure(); assertGrid(data); measurements.push({ width, ...data });
      screenshots.push(await ctx.screenshot(`${id}-${width}`));
    }
    if (resize) {
      const header = table.locator('.el-table__header th').filter({ hasText: '订单编号' }).first();
      await header.scrollIntoViewIfNeeded();
      const initial = await header.boundingBox();
      let enlarged = false;
      for (const delta of [80, -160, -160]) {
        const box = await header.boundingBox();
        await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2); await page.mouse.down();
        await page.mouse.move(Math.max(box.x + 2, box.x + box.width + delta), box.y + box.height / 2, { steps: 12 });
        await step(ctx, () => page.mouse.up()); await page.waitForTimeout(150);
        const data = await measure(); assertGrid(data); measurements.push({ resizeDelta: delta, ...data });
        const resizedWidth = (await header.boundingBox()).width;
        if (resizedWidth < 120) throw new Error('Order identifier column collapsed below its readable minimum');
        if (delta > 0) enlarged = resizedWidth > initial.width + 20;
      }
      if (!enlarged) throw new Error('Resize gesture did not enlarge the target column');
      screenshots.push(await ctx.screenshot(`${id}-resized`));
    }
    assertSafe(ctx);
    return { measurements, screenshots, limits: 'Only current populated table; native pointer, persisted width memory and all-page coverage are not verified',
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  };
  return { id, defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id, path, screenshot: true, inspect: page => inspect(page, '', ctx) }) };
}
module.exports = { assertGrid,
  sales: scenario('table-grid-sales-readonly', '/sales/orders', true),
  label: scenario('table-grid-label-readonly', '/quality/label-qc', false) };

},
    "scenarios/tenant-isolation.js": function(module, exports, require) {
'use strict';

const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { ROUTES } = require('../config/routes');

module.exports = {
  id: 'tenant-isolation',
  async run(ctx) {
    const startedAt = Date.now();
    const result = createScenarioResult(this.id, ctx.page.url());
    ctx.scenarioRef.value = this.id;
    const body = await ctx.page.locator('body').innerText().catch(() => '');
    const login = ctx.loginEvidence;
    const staleTenantDetected = /liushanmen_admin/i.test(body);
    result.pageEvidence.push({
      username: login.username,
      factoryId: login.factoryId,
      factoryName: login.factoryName,
      displayedUsername: body.includes(login.username),
      displayedFactoryName: login.factoryName ? body.includes(login.factoryName) : null,
      staleTenantDetected,
    });
    result.apiEvidence.push(login);
    result.result = login.username === ctx.expectedUsername
      && login.factoryId === ctx.expectedFactoryId
      && !staleTenantDetected ? 'PASS' : 'CONFIRMED_DEFECT';
    result.rootCauseClass = result.result === 'PASS' ? 'none' : 'config';
    result.screenshots.push(await ctx.screenshot(this.id));
    result.durationMs = Date.now() - startedAt;
    return assertScenarioResult(result);
  },
  path: ROUTES.dashboard,
};

},
    "scenarios/transfer-draft-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertPreserved(before, after) {
  for (const key of Object.keys(before)) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) throw new Error(`Transfer draft lost field: ${key}`);
  }
}
function distinctOptionIndex(labels, excluded) {
  return labels.findIndex(label => label.trim() !== excluded.trim());
}
async function inspect(page, _body, ctx) {
  const screenshots = [];
  await step(ctx, () => page.getByRole('button', { name: '手动新建调拨单', exact: true }).click());
  const form = page.locator('.create-workspace');
  await form.waitFor({ state: 'visible' });
  const remark = page.getByPlaceholder('如: 领用 / 研发样品 / 余料退总仓 等');
  const item = label => form.locator('.el-form-item').filter({ has: page.locator('.el-form-item__label', { hasText: label }) });
  async function choose(select, text, excluded, selectedIndex = 0) {
    const control = select.getByRole('combobox');
    const id = await control.getAttribute('aria-controls');
    await step(ctx, () => select.click());
    const options = page.locator(`[id="${id}"] .el-select-dropdown__item:not(.is-disabled)`);
    await options.first().waitFor({ state: 'visible', timeout: 10000 });
    const index = excluded ? distinctOptionIndex(await options.allTextContents(), excluded) : selectedIndex;
    if (index < 0) throw new Error('UNVERIFIED: no distinct target warehouse is available');
    const option = text ? options.filter({ hasText: text }).first() : options.nth(index);
    const count = await options.count();
    await option.waitFor({ state: 'visible', timeout: 10000 });
    await step(ctx, () => option.click());
    return count;
  }
  async function selectSource(index = 0) {
    const responsePromise = page.waitForResponse(response => response.request().method() === 'GET'
      && new URL(response.url()).pathname.endsWith('/inventory/by-warehouse'), { timeout: 15000 }).catch(() => null);
    const count = await choose(item('调出仓库').locator('.el-select'), undefined, undefined, index);
    const response = await responsePromise;
    if (!response) throw new Error('UNVERIFIED: inventory query response was not observed');
    if (response.status() >= 400) throw new Error('Inventory query failed');
    await response.finished(); await page.waitForTimeout(100);
    return count;
  }
  await choose(item('调拨类型').locator('.el-select'), '同一工厂仓库调拨');
  const warehouseCount = await selectSource();
  const sourceLabel = await item('调出仓库').locator('.el-select__selection').innerText();
  await choose(item('调入仓库').locator('.el-select'), undefined, sourceLabel);
  for (const [label, value] of [['调拨日期', '2030-01-15'], ['预计到货', '2030-01-16']]) {
    const input = item(label).locator('input');
    await step(ctx, () => input.fill(value));
    await input.press('Tab'); await page.keyboard.press('Escape');
  }
  await step(ctx, () => remark.fill('只读验收本地草稿，未提交'));
  await step(ctx, () => page.getByRole('button', { name: '添加物料', exact: true }).click());
  const category = form.locator('.el-table__body .el-select').nth(0);
  let warehousesInspected = 1;
  while (await category.getByText('所选仓库暂无可调拨库存', { exact: true }).isVisible()
    && warehousesInspected < Math.min(warehouseCount, 5)) {
    await selectSource(warehousesInspected++);
  }
  await choose(item('调入仓库').locator('.el-select'), undefined,
    await item('调出仓库').locator('.el-select__selection').innerText());
  await choose(category);
  await choose(form.locator('.el-table__body .el-select').nth(1));
  const quantity = form.getByPlaceholder('数量', { exact: true });
  await step(ctx, () => quantity.fill('1')); await remark.click();
  const snapshot = async () => ({
    remark: await remark.inputValue(),
    date: await item('调拨日期').locator('input').inputValue(),
    arrival: await item('预计到货').locator('input').inputValue(),
    source: await item('调出仓库').locator('.el-select__selection').innerText(),
    target: await item('调入仓库').locator('.el-select__selection').innerText(),
    quantity: await quantity.inputValue(),
    selectedMaterial: await form.locator('.el-table__body .el-select').nth(1).innerText(),
  });
  const before = await snapshot();
  if (!before.source || !before.target || !before.selectedMaterial || Number(before.quantity) !== 1) throw new Error('Transfer draft baseline incomplete');
  const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
  async function activate(target) {
    const tabs = page.locator('.workspace-tab');
    for (let i = 0; i < await tabs.count(); i++) if (await tabs.nth(i).getAttribute('data-workspace-key') === target) { await step(ctx, () => tabs.nth(i).click()); return; }
    throw new Error('Expected transfer workspace tab missing');
  }
  screenshots.push(await ctx.screenshot('transfer-draft-before'));
  for (let round = 0; round < 3; round++) {
    await activate('/transfer/list');
    await page.waitForURL(url => url.pathname === '/transfer/list');
    await activate(key);
    await remark.waitFor({ state: 'visible' });
    assertPreserved(before, await snapshot());
  }
  screenshots.push(await ctx.screenshot('transfer-draft-three-returns'));
  await quantity.scrollIntoViewIfNeeded();
  screenshots.push(await ctx.screenshot('transfer-draft-material-fields'));
  // Closing only the locally edited tab discards our unsaved input.
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await page.waitForURL(url => url.pathname === '/transfer/list');
  assertSafe(ctx);
  return { retainedFields: Object.keys(before), roundTrips: 3, warehousesInspected, localDraftClosed: true, screenshots,
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'transfer-draft-readonly', defaultEnabled: false, assertPreserved, distinctOptionIndex, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'transfer-draft-readonly', path: '/transfer/list', screenshot: true, inspect }) };

},
    "scenarios/ui-stability.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'ui-stability',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'ui-stability',
    path: ROUTES.dashboard,
    landmarks: ['工作台'],
    inspect: async (page, body) => ({
      visibleToastCount: await page.locator('.el-message:visible, .el-notification:visible').count(),
      rawEnglishEnums: (body.match(/\b[A-Z][A-Z_]{3,}\b/g) || []).slice(0, 20),
      horizontalOverflowCount: await page.locator('*').evaluateAll((elements) => elements.filter((element) => {
        const style = getComputedStyle(element);
        return element.scrollWidth > element.clientWidth + 5 && ['auto', 'scroll'].includes(style.overflowX);
      }).length),
    }),
  }),
};

},
    "scenarios/visual-page-census-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');
const { splitUrl, resolveUrl } = require('../core/url-utils');

function routeIdentity(path, baseUrl, actualUrl) {
  const expected = splitUrl(resolveUrl(path, baseUrl));
  const actual = splitUrl(actualUrl);
  // Census routes have a fixed, canonical query string; reject extra/changed parameters.
  return { routeMatches: actual.origin === expected.origin && actual.pathname === expected.pathname && actual.search === expected.search,
    expectedTab: expected.search.startsWith('?tab=') ? expected.search.slice(5) : null };
}

// Existing router paths only. No create/edit/detail IDs or arbitrary URL input.
const VISUAL_PAGES = Object.freeze([
  ['dashboard', '/dashboard', '首页'],
  ['purchase-orders', '/procurement/orders', '采购'],
  ['suppliers', '/procurement/suppliers', '供应商'],
  ['sales-orders', '/sales/orders', '销售'],
  ['sales-shipments', '/sales/shipments', '出货'],
  ['warehouse-shipments', '/warehouse/shipments', '出货'],
  ['materials', '/warehouse/materials', '入库'],
  ['inventory-warehouse', '/warehouse/inventory-query?tab=by-warehouse', '库存'],
  ['inventory-total', '/warehouse/inventory-query?tab=total', '库存'],
  ['inventory-batch', '/warehouse/inventory-query?tab=batch', '库存'],
  ['inventory-semi', '/warehouse/inventory-query?tab=semi-finished', '库存'],
  ['stocktake', '/warehouse/stocktake-query?tab=main', '盘点'],
  ['stocktake-semi', '/warehouse/stocktake-query?tab=semi-finished', '盘点'],
  ['transfers', '/transfer/list', '调拨'],
  ['quality', '/quality/inspections', '质检'],
  ['quality-disposition', '/quality/disposition', '处置'],
  ['label-qc', '/quality/label-qc', '标签'],
  ['products', '/system/products', 'SKU'],
  ['workflow', '/system/product-processes', 'Workflow'],
  ['plans', '/production/plans', '生产'],
  ['plan-queue', '/production/pending-queue', '计划'],
  ['restock-board', '/production/restock-board', '备货'],
  ['cost-summary', '/production/bom-achievement', '成本'],
  ['bom', '/production/bom', 'BOM'],
  ['finance', '/finance/ar-ap', '应收'],
].map(([id, path, landmark]) => Object.freeze({ id, path, landmark })));

async function inspectVisualPage(page, _body, ctx, item) {
  const guard = ctx.mutationGuard.snapshot();
  if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) return { assessment: { result: 'TOOL_ERROR', rootCauseClass: 'tool' } };
  const { routeMatches, expectedTab } = routeIdentity(item.path, ctx.baseUrl, page.url());
  const identity = await page.evaluate(({ landmark, tab }) => {
    const visible = el => Boolean(el && el.getBoundingClientRect().width && el.getBoundingClientRect().height && getComputedStyle(el).visibility !== 'hidden');
    const main = ['main.app-content', 'main', '[role="main"]', '.el-main', '.app-main']
      .map(selector => [...document.querySelectorAll(selector)].find(visible)).find(Boolean);
    const content = main?.cloneNode(true);
    content?.querySelectorAll('aside, nav, header, .sidebar, .el-menu, .el-breadcrumb').forEach(el => el.remove());
    const text = content?.textContent || '';
    const selected = main?.querySelector('.hub-tabs [role="tab"][aria-selected="true"]');
    const panel = selected && document.getElementById(selected.getAttribute('aria-controls'));
    return {
      mainFound: Boolean(main), bodyLandmarkMatches: text.includes(landmark),
      errorPage: /404|页面不存在|页面未找到|Page Not Found/i.test(text),
      expectedTab: tab, activeTabId: selected?.id || '',
      activePanelId: panel?.id || '', activePanelVisible: visible(panel),
      tabMatches: !tab || Boolean(selected?.id === `tab-${tab}` && panel?.id === `pane-${tab}` && visible(panel)),
    };
  }, { landmark: item.landmark, tab: expectedTab });
  const metrics = await page.evaluate(() => ({
    viewport: { width: innerWidth, height: innerHeight, devicePixelRatio },
    outerHorizontalOverflow: document.documentElement.scrollWidth > innerWidth + 2,
    tableCount: document.querySelectorAll('.el-table, table').length,
    rowCount: document.querySelectorAll('.el-table__body tbody tr').length,
    emptyStates: document.querySelectorAll('.el-empty, .el-table__empty-block').length,
    loadingVisible: [...document.querySelectorAll('.el-loading-mask')].some(el => el.getBoundingClientRect().width > 0 && getComputedStyle(el).display !== 'none'),
    errorVisible: [...document.querySelectorAll('.el-message--error, .el-alert--error')].some(el => el.getBoundingClientRect().width > 0),
    columnLabels: [...document.querySelectorAll('.el-table__header th')].map(el => (el.textContent || '').trim()).slice(0, 40),
  }));
  // Tab changes focus only. No activation/Enter, mutations or editable values.
  await page.keyboard.press('Tab');
  const focus = await page.evaluate(() => {
    const el = document.activeElement;
    const rect = el?.getBoundingClientRect();
    const style = el ? getComputedStyle(el) : null;
    return { tag: el?.tagName || '', visible: Boolean(rect?.width && rect?.height), outlineStyle: style?.outlineStyle || '', outlineWidth: style?.outlineWidth || '', boxShadow: style?.boxShadow || '' };
  });
  return { ...metrics, focus, routeMatches, ...identity, operation: 'navigate, inspect, one Tab, screenshot; no button activated', assessment: !routeMatches || !identity.mainFound || !identity.bodyLandmarkMatches || identity.errorPage || !identity.tabMatches || metrics.loadingVisible ? { result: 'UNVERIFIED', rootCauseClass: 'tool' } : metrics.errorVisible ? { result: 'PARTIAL_DEFECT', rootCauseClass: 'frontend' } : undefined };
}

async function runVisualCensus(ctx, pages = VISUAL_PAGES) {
  const started = Date.now();
  const result = createScenarioResult('visual-page-census-readonly', ctx.page.url());
  for (const item of pages) {
    const guard = ctx.mutationGuard.snapshot();
    if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) break;
    const pageResult = await runReadOnlyPageScenario(ctx, { id: `visual-${item.id}`, path: item.path, landmarks: [item.landmark], screenshot: true, inspect: (page, body, context) => inspectVisualPage(page, body, context, item) });
    result.pageEvidence.push({ page: item.id, route: item.path, result: pageResult.result, ...Object.assign({}, ...pageResult.pageEvidence), screenshots: pageResult.screenshots });
    for (const key of ['apiEvidence', 'consoleErrors', 'consoleWarnings', 'httpErrors', 'blockedMutationAttempts', 'screenshots']) result[key].push(...pageResult[key]);
    result.actualBusinessWrites += pageResult.actualBusinessWrites;
    if (pageResult.blockedMutationAttempts.length || pageResult.actualBusinessWrites) break;
  }
  const complete = result.pageEvidence.length === pages.length && result.pageEvidence.every(item => item.result === 'PASS');
  result.result = result.blockedMutationAttempts.length || result.actualBusinessWrites ? 'TOOL_ERROR' : complete ? 'PASS' : 'UNVERIFIED';
  result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
  result.durationMs = Date.now() - started;
  return assertScenarioResult(result);
}

module.exports = { id: 'visual-page-census-readonly', defaultEnabled: false, VISUAL_PAGES, routeIdentity, inspectVisualPage, runVisualCensus, run: ctx => runVisualCensus(ctx) };

},
    "scenarios/visual-unverified-followup-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { routeIdentity } = require('./visual-page-census-readonly');
const { createScenarioResult } = require('../core/result-schema');

function assertSafe(ctx) {
  const state = ctx.mutationGuard.snapshot();
  if (state.actualBusinessWrites || state.blockedMutationAttempts.length) throw new Error('Read-only guard stopped follow-up');
}
async function step(ctx, action) { assertSafe(ctx); const value = await action(); assertSafe(ctx); return value; }
async function waitForQueueRows(page, ctx) {
  await page.locator('.queue-table .el-loading-mask').waitFor({ state: 'hidden', timeout: 15000 });
  assertSafe(ctx);
}
async function verifyTarget(page, ctx, path, heading, tab) {
  // Element Plus selects its pane before router.replace resolves. Keep the
  // exact route predicate, but wait for navigation instead of racing the UI.
  await page.waitForURL(url => routeIdentity(path, ctx.baseUrl, String(url)).routeMatches,
    { timeout: 8000 });
  const identity = routeIdentity(path, ctx.baseUrl, page.url());
  if (!identity.routeMatches) throw new Error('UNVERIFIED: final route does not match bounded target');
  const main = page.locator('main.app-content, main[role="main"], main').first();
  await main.waitFor({ state: 'visible', timeout: 8000 });
  if (heading) await main.getByText(heading, { exact: true }).first().waitFor({ state: 'visible', timeout: 8000 });
  if (tab) {
    const selected = main.locator(`.hub-tabs #tab-${tab}`);
    if (await selected.getAttribute('aria-selected') !== 'true' || !await main.locator(`#pane-${tab}`).isVisible()) throw new Error('UNVERIFIED: selected tab/panel mismatch');
  }
  assertSafe(ctx);
  return { actualPath: path, routeMatches: true, headingVisible: Boolean(heading), activeTab: tab || null };
}
const FOLLOWUP_PAGES = Object.freeze([
  { id: 'dashboard', path: '/dashboard', heading: '快捷操作' },
  { id: 'label-qc', path: '/quality/label-qc', heading: '包装标签拍检' },
  { id: 'pending-queue', path: '/production/kanban?tab=pending-queue', heading: '未完成计划队列', tab: 'pending-queue' },
  { id: 'restock-board', path: '/production/kanban?tab=restock-board', tab: 'restock-board' },
  { id: 'bom-achievement', path: '/production/analysis-query?tab=bom-achievement', heading: 'BOM达成率分析', tab: 'bom-achievement' },
]);
async function runFollowup(ctx, targets = FOLLOWUP_PAGES, id = 'visual-unverified-followup-readonly') {
  const result = createScenarioResult(id);
  const start = Date.now();
  for (const target of targets) {
    assertSafe(ctx);
    const pageResult = await runReadOnlyPageScenario(ctx, { id: `followup-${target.id}`, path: target.path, screenshot: true, inspect: async page => {
      try {
        const identity = await verifyTarget(page, ctx, target.path, target.heading, target.tab);
        // Query-only navigation controls. No row action, export, review or create control.
        let operation = 'bounded navigation and visible content';
        if (target.id === 'label-qc') {
          for (const [label, heading] of [['已完成记录', '已完成记录'], ['待人工审核', '审核队列']]) {
            const control = page.locator('.el-radio-button').filter({ hasText: label });
            await step(ctx, () => control.click({ timeout: 5000 }));
            await page.locator('.queue-toolbar h2').filter({ hasText: heading }).waitFor({ state: 'visible', timeout: 5000 });
            if (!await control.locator('input[type="radio"]').isChecked()) throw new Error('UNVERIFIED: queue radio did not become checked');
            await waitForQueueRows(page, ctx);
            assertSafe(ctx);
          }
          operation = 'completed queue then pending queue; checked radio and queue heading verified; no review action';
        } else if (target.tab) {
          const alternate = target.id === 'bom-achievement' ? 'process-io' : target.tab === 'pending-queue' ? 'restock-board' : 'pending-queue';
          await step(ctx, () => page.locator(`.hub-tabs #tab-${alternate}`).click({ timeout: 5000 }));
          await page.locator(`#pane-${alternate}`).waitFor({ state: 'visible', timeout: 8000 });
          await step(ctx, () => page.locator(`.hub-tabs #tab-${target.tab}`).click({ timeout: 5000 }));
          await verifyTarget(page, ctx, target.path, target.heading, target.tab);
          operation = `switch to ${alternate} and return; distinct selected pane verified`;
        }
        return { ...identity, operation, assessment: { result: 'PASS', rootCauseClass: 'none' } };
      } catch (error) { assertSafe(ctx); return { reason: String(error.message), assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } }; }
    } });
    result.pageEvidence.push({ page: target.id, result: pageResult.result, ...Object.assign({}, ...pageResult.pageEvidence) });
    for (const key of ['apiEvidence','consoleErrors','consoleWarnings','httpErrors','blockedMutationAttempts','screenshots']) result[key].push(...pageResult[key]);
    result.actualBusinessWrites += pageResult.actualBusinessWrites;
    if (pageResult.actualBusinessWrites || pageResult.blockedMutationAttempts.length) break;
  }
  result.result = result.actualBusinessWrites || result.blockedMutationAttempts.length ? 'TOOL_ERROR' : result.pageEvidence.length === targets.length && result.pageEvidence.every(p => p.result === 'PASS') ? 'PASS' : 'UNVERIFIED';
  result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool'; result.durationMs = Date.now()-start;
  return result;
}
module.exports = { id: 'visual-unverified-followup-readonly', defaultEnabled: false, assertSafe, step, verifyTarget, waitForQueueRows, FOLLOWUP_PAGES, run: runFollowup };

},
    "scenarios/workflow-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'workflow-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'workflow-readonly',
    path: ROUTES.workflow,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => ({
      rawCellEntryVisible: /原料 Cell|原料 SKU/.test(body),
      publishControlVisible: /发布/.test(body),
      quantityRelationVisible: body.includes('投入产出数量关系'),
      inputUnitChips: await page.getByTestId('input-unit-chip').allInnerTexts(),
      note: 'Publish/save/apply controls are never clicked by this scenario.',
      selectCount: await page.locator('.el-select').count(),
    }),
  }),
};

},
    "scenarios/workflow-viewport-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe, verifyTarget } = require('./visual-unverified-followup-readonly');
module.exports = { id: 'workflow-viewport-readonly', defaultEnabled: false, run: ctx => runReadOnlyPageScenario(ctx, {
  id: 'workflow-viewport-readonly', path: '/production/process-hub?tab=product-processes', screenshot: false,
  inspect: async page => {
    await verifyTarget(page, ctx, '/production/process-hub?tab=product-processes');
    const editor = page.locator('.workflow-editor');
    await editor.waitFor({ state: 'visible', timeout: 15000 });
    await page.locator('.vue-flow__node').first().waitFor({ state: 'attached', timeout: 15000 });
    const nodeIds = () => page.locator('.vue-flow__node').evaluateAll(nodes => nodes.map(n => n.getAttribute('data-id')).sort());
    const before = await nodeIds(); const states = []; const screenshots = [];
    const enter = page.getByTestId('workflow-immersive-enter');
    if (await enter.isVisible()) await step(ctx, () => enter.click({ timeout: 5000 }));
    const capture = async (state, fullscreen) => {
      // Cover the editor's 2500ms debounced autosave after every layout action.
      await page.waitForTimeout(3100); assertSafe(ctx);
      const box = await editor.boundingBox();
      const viewport = page.viewportSize();
      const parentTabs = await page.locator('.process-config-hub > .hub-tabs > .el-tabs__header').isVisible();
      const ids = await nodeIds();
      const nodesPreserved = JSON.stringify(ids) === JSON.stringify(before);
      const geometry = fullscreen ? Boolean(box && Math.abs(box.x)<2 && Math.abs(box.y)<2 && Math.abs(box.width-viewport.width)<2 && Math.abs(box.height-viewport.height)<2 && !parentTabs) : parentTabs;
      states.push({ state, fullscreen, box, parentTabs, nodesPreserved, geometry });
      screenshots.push(await ctx.screenshot(`workflow-${state}`));
    };
    await capture('fullscreen', true);
    // Viewport-change-end can autosave when editable. Fit safety is not established for production.
    const fit = { result: 'UNVERIFIED', reason: 'Editable viewport changes have an autosave path; local fit did not reproduce a write, but production fit remains untested.' };
    const dock = page.locator('.workflow-ai-dock');
    const collapse = dock.getByText('收起', { exact: true });
    let collapsed = false;
    if (await collapse.isVisible()) {
      const beforeHeight = (await dock.boundingBox()).height;
      await step(ctx, () => collapse.click({ timeout: 5000 }));
      await page.waitForTimeout(450);
      collapsed = (await dock.boundingBox()).height < beforeHeight;
    }
    await capture('ai-collapsed', true);
    await step(ctx, () => page.getByTestId('workflow-immersive-exit').click({ timeout: 5000 }));
    await capture('exit', false);
    await step(ctx, () => enter.click({ timeout: 5000 }));
    await capture('reenter', true);
    assertSafe(ctx);
    return { states, fit, collapsed, screenshots, assessment: { result: states.every(s=>s.nodesPreserved&&s.geometry)&&collapsed ? 'UNVERIFIED' : 'PARTIAL_DEFECT', rootCauseClass: states.every(s=>s.nodesPreserved&&s.geometry)&&collapsed ? 'tool' : 'frontend' } };
  },
}) };

},
    "scenarios/workspace-capacity-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertDraft(value, expected) {
  if (value !== expected) throw new Error('Open workspace draft lost its independent date');
}
async function inspect(page, _body, ctx) {
  const date = page.getByRole('textbox', { name: '交货日期', exact: true });
  const copies = [];
  for (let n = 1; n <= 11; n++) {
    if (n > 1) {
      const previous = page.url();
      await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
      await page.waitForURL(url => url.href !== previous, { timeout: 15000 });
    }
    const expected = `2030-01-${String(n).padStart(2, '0')}`;
    await date.waitFor({ state: 'visible', timeout: 15000 });
    await step(ctx, () => date.fill(expected.replaceAll('-', '')));
    await page.keyboard.press('Tab'); await page.keyboard.press('Escape');
    assertDraft(await date.inputValue(), expected);
    const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    copies.push({ key, expected });
  }
  if (await page.locator('.workspace-tab').count() < 11) throw new Error('Workspace silently removed an open draft');
  for (const { key, expected } of copies) {
    const tab = page.locator('.workspace-tab').filter({ has: page.locator('.workspace-tab-title') });
    const index = await tab.evaluateAll((elements, wanted) => elements.findIndex(e => e.getAttribute('data-workspace-key') === wanted), key);
    if (index < 0) throw new Error('Expected draft tab disappeared');
    await step(ctx, () => tab.nth(index).click());
    await page.waitForFunction(wanted => document.querySelector('.workspace-tab-container.active .workspace-tab')?.getAttribute('data-workspace-key') === wanted, key);
    assertDraft(await date.inputValue(), expected);
  }
  await page.setViewportSize({ width: 1024, height: 900 });
  await page.locator('.workspace-tab-container.active').scrollIntoViewIfNeeded();
  const screenshot = await ctx.screenshot('workspace-eleven-drafts');
  // Discard only the local UI copies; never save or submit any business data.
  for (let n = 11; n > 1; n--) {
    await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
    const expectedKey = copies[n - 2].key;
    await page.waitForURL(url => `${url.pathname}${url.searchParams.has('_task') ? '?_task=' + url.searchParams.get('_task') : ''}` === expectedKey);
  }
  assertDraft(await date.inputValue(), copies[0].expected);
  assertSafe(ctx);
  return { independentlyRetainedDrafts: 11, closedCopies: 10, originalRetainedAfterCleanup: true, screenshots: [screenshot], assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-capacity-readonly', defaultEnabled: false, inspect, assertDraft,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-capacity-readonly', path: '/sales/orders/new', screenshot: true, inspect }) };

},
    "scenarios/workspace-duplicate-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function verifyLabels(labels) {
  const expected = ['新建销售订单', '新建销售订单 2', '新建销售订单 3'];
  if (labels.length !== expected.length || expected.some(label => !labels.includes(label))) {
    throw new Error('Workspace copy labels are missing, repeated or compounded');
  }
}

async function inspect(page, _body, ctx) {
  const tabs = page.locator('.workspace-tab');
  const tabByTitle = title => tabs.filter({ has: page.locator('.workspace-tab-title').filter({ hasText: new RegExp(`^${title}$`) }) });
  const date = page.getByRole('textbox', { name: '交货日期', exact: true });
  const labels = () => page.locator('.workspace-tab-title').allTextContents();
  async function fillDate(raw, expected) {
    await date.waitFor({ state: 'visible', timeout: 15000 });
    await step(ctx, () => date.fill(raw));
    await page.keyboard.press('Tab');
    await page.keyboard.press('Escape');
    if (await date.inputValue() !== expected) throw new Error('Draft date did not normalize');
  }
  async function copy(expected) {
    await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
    await page.waitForURL(url => url.searchParams.get('_taskLabel') === expected, { timeout: 10000 });
    await tabs.filter({ hasText: expected }).waitFor({ state: 'visible' });
  }
  async function activate(title) {
    await step(ctx, () => tabByTitle(title).click());
    await page.waitForFunction(expected => document.querySelector('.workspace-tab-container.active .workspace-tab-title')?.textContent === expected, title);
  }
  await fillDate('20300115', '2030-01-15');
  await copy('新建销售订单 2');
  await fillDate('20300216', '2030-02-16');
  await copy('新建销售订单 3');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  await activate('新建销售订单');
  if (await date.inputValue() !== '2030-01-15') throw new Error('Original draft lost its date');
  await activate('新建销售订单 2');
  if (await date.inputValue() !== '2030-02-16') throw new Error('Copied draft lost its independent date');
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await activate('新建销售订单');
  await copy('新建销售订单 2');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  if (await date.inputValue() === '2030-02-16') throw new Error('New copy reused the closed draft instance');
  const screenshots = [];
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    await tabByTitle('新建销售订单 2').scrollIntoViewIfNeeded();
    for (const ordinal of [2, 3]) {
      const tab = tabByTitle(`新建销售订单 ${ordinal}`);
      const number = tab.locator('.workspace-tab-number');
      if (!await number.isVisible()) throw new Error('Workspace ordinal is not visibly separate from truncated text');
      const bounds = await number.boundingBox();
      const button = await tab.boundingBox();
      if (!bounds || !button || bounds.width < 5 || bounds.x < button.x || bounds.x + bounds.width > button.x + button.width + 1) {
        throw new Error('Workspace ordinal is clipped outside its tab');
      }
    }
    await page.mouse.move(250, 170);
    await page.waitForTimeout(350);
    screenshots.push(await ctx.screenshot(`workspace-copy-${width}`));
  }
  assertSafe(ctx);
  return { uniqueLabels: true, ordinalsVisibleAtBothWidths: true, originalDraftPreserved: true, copiedDraftPreserved: true, closedCopyNotReused: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-duplicate-readonly', defaultEnabled: false, inspect, verifyLabels,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-duplicate-readonly', path: '/sales/orders/new', screenshot: true, inspect }) };

},
    "scenarios/workspace-r3-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');
const { waitForAppMount } = require('../core/app-mount');

async function inspect(page, _body, ctx) {
  const screenshots = [];
  await step(ctx, () => page.goto(`${ctx.baseUrl}/sales/orders/new`));
  const initialMount = await waitForAppMount(page);
  if (!initialMount.appMounted) throw new Error('Draft application did not mount');
  const date = page.getByRole('textbox', { name: '交货日期', exact: true });
  await date.waitFor({ state: 'visible', timeout: 15000 });
  const close = page.getByRole('button', { name: '关闭任务', exact: true }).last();
  const before = await page.locator('.workspace-tab').count();
  await close.focus(); await step(ctx, () => page.keyboard.press('Space'));
  await page.waitForFunction(n => document.querySelectorAll('.workspace-tab').length === n - 1, before);
  await page.waitForFunction(() => document.activeElement?.classList.contains('workspace-tab'), null, { timeout: 5000 });
  const closeFocus = await page.evaluate(() => document.activeElement?.classList.contains('workspace-tab'));
  if (!closeFocus) throw new Error('Tab close lost keyboard focus');
  await step(ctx, () => page.goto(`${ctx.baseUrl}/sales/orders/new`));
  const reopenedMount = await waitForAppMount(page);
  if (!reopenedMount.appMounted) throw new Error('Reopened draft application did not mount');
  await date.waitFor({ state: 'visible', timeout: 15000 });
  await step(ctx, () => date.fill('20300115')); await page.keyboard.press('Tab'); await page.keyboard.press('Escape');
  const savedDate = await date.inputValue();
  if (savedDate !== '2030-01-15') throw new Error('Draft date was not normalized');
  await page.setViewportSize({ width: 1024, height: 900 });
  const tab = page.locator('.workspace-tab-container').filter({ hasText: '销售订单' }).first();
  const b = await tab.boundingBox();
  await page.mouse.move(b.x + 25, b.y + 20); await page.mouse.down();
  await page.mouse.move(b.x + 45, b.y + 75, { steps: 12 });
  const drop = page.locator('.reference-drop-zone'); await drop.waitFor({ state: 'visible' });
  const d = await drop.boundingBox(); await page.mouse.move(d.x+d.width/2,d.y+d.height/2,{steps:20}); await step(ctx,()=>page.mouse.up());
  await page.locator('.reference-pane iframe').waitFor({ state: 'attached' });
  const referenceFrame = await page.locator('.reference-pane iframe').elementHandle().then(handle => handle.contentFrame());
  if (!referenceFrame) throw new Error('Reference frame is unavailable');
  await referenceFrame.waitForURL(url => url.searchParams.get('_workspaceReference') === '1', { timeout: 30000 });
  const referenceMount = await waitForAppMount(referenceFrame);
  try {
    await page.waitForFunction(() => document.querySelector('.reference-pane iframe')?.contentDocument?.querySelector('.el-table__body tr'), null, { timeout: 15000 });
  } catch {
    const referenceState = await page.evaluate(() => {
      const frame = document.querySelector('.reference-pane iframe');
      const doc = frame?.contentDocument;
      return { hasFrame: Boolean(frame), readyState: doc?.readyState || null,
        bodyLength: doc?.body?.innerText.length || 0,
        tableCount: doc?.querySelectorAll('.el-table').length || 0,
        rowCount: doc?.querySelectorAll('.el-table__body tr').length || 0 };
    });
    screenshots.push(await ctx.screenshot('workspace-reference-not-ready'));
    return { initialMount, reopenedMount, referenceMount, referenceState, screenshots, assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
  }
  const shield = page.locator('.readonly-shield'); await shield.focus(); await step(ctx, () => page.keyboard.press('ArrowDown'));
  const frame = page.frames().find(f => f.url().includes('_workspaceReference'));
  const referenceScroll = await frame.evaluate(() => scrollY);
  const width = await page.locator('main.app-content').evaluate(e => e.clientWidth);
  if (width < 750 || referenceScroll <= 0) throw new Error('Narrow reference is not usable or independently scrollable');
  await page.locator('main.app-content').evaluate(e => { e.scrollTop = (e.scrollHeight-e.clientHeight)/2; });
  await step(ctx, () => page.getByRole('button', { name: '独立滚动', exact: true }).click());
  await page.waitForTimeout(100);
  const ratio = await frame.evaluate(() => scrollY/(document.documentElement.scrollHeight-innerHeight));
  if (Math.abs(ratio-.5) > .03) throw new Error('Reference synchronization does not follow the primary panel');
  await page.locator('main.app-content').evaluate(e => { e.scrollTop = e.scrollHeight; });
  await page.waitForTimeout(100);
  const bottomRatio = await frame.evaluate(() => scrollY/(document.documentElement.scrollHeight-innerHeight));
  if (Math.abs(bottomRatio-1) > .03) throw new Error('Reference does not reach the bottom with the primary panel');
  screenshots.push(await ctx.screenshot('workspace-r3-narrow'));
  await step(ctx, () => page.getByRole('button', { name: '关闭同屏参考' }).click());
  await page.waitForTimeout(100);
  const refFocus = await page.evaluate(() => document.activeElement?.classList.contains('workspace-tab'));
  if (!refFocus || await date.inputValue() !== savedDate) throw new Error('Reference close lost focus or draft');
  await page.setViewportSize({ width: 1440, height: 900 });
  assertSafe(ctx);
  return { initialMount, reopenedMount, referenceMount, closeFocus, refFocus, referenceScroll, primaryWidth: width, synchronizedRatio: ratio, bottomRatio, draftPreserved: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-r3-readonly', defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-r3-readonly', path: '/sales/orders', screenshot: true, inspect }) };

},
    "scenarios/yield-header-r3-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');
async function inspect(page, _body, ctx) {
  await page.locator('.plan-number-link').first().waitFor({ state: 'visible', timeout: 15000 });
  // Workflow entry always POSTs create-batch, even when the batch already exists.
  // Only an explicitly disabled Workflow on a currently visible plan is safe here.
  const candidates = await page.evaluate(async factoryId => {
    const root = `/api/mobile/${encodeURIComponent(factoryId)}`;
    const read = async path => { const response = await fetch(path, { credentials: 'same-origin' }); if (!response.ok) throw new Error('Read-only preflight failed'); return response.json(); };
    const plans = await read(`${root}/production-plans?page=1&size=10&status=UNFINISHED`);
    if (!plans.success || !Array.isArray(plans.data?.content)) throw new Error('Plan response does not support safe entry selection');
    const visible = new Set(Array.from(document.querySelectorAll('.plan-number-link')).map(e => e.textContent.trim()));
    const safe = [];
    for (const plan of plans.data.content) {
      if (!plan.productTypeId || !visible.has(plan.planNumber)) continue;
      const activation = await read(`${root}/product-process-workflows/${encodeURIComponent(plan.productTypeId)}/activation`);
      if (activation.success && activation.data?.enabled === false) safe.push(plan.planNumber);
    }
    return safe;
  }, ctx.expectedFactoryId);
  assertSafe(ctx);
  let entry;
  for (const number of candidates) {
    const candidate = page.getByRole('row').filter({ hasText: number }).getByRole('button', { name: '继续录入', exact: true });
    if (await candidate.count() === 1 && await candidate.isVisible()) { entry = candidate; break; }
  }
  if (!entry) return { reason: 'No visible plan with explicitly disabled Workflow; entry may create a production batch and is excluded from read-only acceptance', assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
  await entry.waitFor({ state: 'visible', timeout: 15000 });
  await step(ctx, () => entry.click());
  const table = page.locator('.yield-card-table');
  const header = table.locator('th').first();
  await header.waitFor({ state: 'visible', timeout: 15000 });
  const style = await header.evaluate(e => ({
    label: e.textContent.trim(), width: e.getBoundingClientRect().width,
    border: getComputedStyle(e.querySelector('button')).borderWidth,
    background: getComputedStyle(e.querySelector('button')).backgroundColor,
  }));
  if (style.label !== '序号' || style.width < 80 || style.border !== '0px') throw new Error('Yield sequence header does not match the accepted style');
  const rows = table.locator('.el-table__body tr td:first-child');
  const values = async () => (await rows.allTextContents()).map(v => Number(v.trim()));
  await step(ctx, () => header.locator('.sort-caret.ascending').click());
  const ascending = await values();
  await step(ctx, () => header.locator('.sort-caret.descending').click());
  const descending = await values();
  const sortable = ascending.length > 1 && ascending.every(Number.isFinite);
  if (sortable && (ascending.some((v,i) => i>0 && v<ascending[i-1]) || descending.some((v,i) => i>0 && v>descending[i-1]))) throw new Error('Yield sequence sorting regressed');
  assertSafe(ctx);
  return { ...style, rowCount: ascending.length, sorting: sortable ? 'PASS' : 'UNVERIFIED: fewer than two numeric rows', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'yield-header-r3-readonly', defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id: 'yield-header-r3-readonly', path: '/production/plans', screenshot: true, inspect }) };

}
  };
  const __cache = Object.create(null);
  const __resolve = (fromId, request) => {
    const parts = fromId.split('/');
    parts.pop();
    for (const part of request.split('/')) {
      if (!part || part === '.') continue;
      if (part === '..') parts.pop();
      else parts.push(part);
    }
    let id = parts.join('/');
    if (!/\.[A-Za-z0-9]+$/.test(id)) id += '.js';
    return id;
  };
  const __load = (id) => {
    if (__cache[id]) return __cache[id].exports;
    const factory = __modules[id];
    if (!factory) throw new Error('Missing bundled module: ' + id);
    const module = { exports: {} };
    __cache[id] = module;
    factory(module, module.exports, (request) => __load(__resolve(id, request)));
    return module.exports;
  };
  const options = page.__cretasReadonlyOptions || {};
  delete page.__cretasReadonlyOptions;
  const harness = __load("core/run-suite.js");
  if (options.dryRun) return harness.describeHarness();
  return harness.runSuiteWithPage(page, { ...options, productionReadonly: true });
}
