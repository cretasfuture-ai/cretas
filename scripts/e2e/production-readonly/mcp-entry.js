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

function isPositiveInteger(value) {
  return Number.isSafeInteger(value) && value > 0;
}

function factoryAnalysisBodyMatches(body) {
  if (!body || typeof body !== 'object' || typeof body.question !== 'string'
    || !body.question.trim() || body.question.length > 1000) return false;
  const keys = Object.keys(body).sort();
  const followupKeys = ['contextId', 'question'];
  if (keys.length === followupKeys.length && keys.every((key, index) => key === followupKeys[index])) {
    return typeof body.contextId === 'string' && body.contextId.length > 0 && body.contextId.length <= 128;
  }
  const clarificationKeys = ['analysisSubject', 'contextId', 'question'];
  if (keys.length === clarificationKeys.length && keys.every((key, index) => key === clarificationKeys[index])) {
    return typeof body.contextId === 'string' && body.contextId.length > 0 && body.contextId.length <= 128
      && typeof body.analysisSubject === 'string' && body.analysisSubject.trim().length > 0
      && body.analysisSubject.length <= 200;
  }
  const requiredInitial = ['analysisSubject', 'endMonth', 'periodType', 'question', 'source', 'startMonth', 'year'];
  if (!requiredInitial.every((key) => keys.includes(key))) return false;
  const allowedInitial = new Set([...requiredInitial, 'uploadId', 'inspectionUploadId', 'costUploadId']);
  if (keys.some((key) => !allowedInitial.has(key))) return false;
  if (body.source !== 'business' && body.source !== 'upload') return false;
  if (!Number.isSafeInteger(body.year) || body.year < 2000 || body.year > 2100) return false;
  if (!['all', 'year', 'month_range'].includes(body.periodType)) return false;
  if (!Number.isInteger(body.startMonth) || body.startMonth < 1 || body.startMonth > 12
    || !Number.isInteger(body.endMonth) || body.endMonth < body.startMonth || body.endMonth > 12) return false;
  if (body.analysisSubject !== null && (typeof body.analysisSubject !== 'string' || body.analysisSubject.length > 200)) return false;
  if (body.source === 'upload') {
    if (!keys.includes('uploadId') || !isPositiveInteger(body.uploadId)) return false;
    const hasInspection = keys.includes('inspectionUploadId');
    const hasCost = keys.includes('costUploadId');
    if (hasInspection !== hasCost) return false;
    return !hasInspection || (isPositiveInteger(body.inspectionUploadId) && isPositiveInteger(body.costUploadId)
      && new Set([body.uploadId, body.inspectionUploadId, body.costUploadId]).size === 3);
  }
  return !keys.includes('uploadId') && !keys.includes('inspectionUploadId') && !keys.includes('costUploadId');
}

// Every entry must point to a server contract that is query-only. Do not add
// generic prefixes. AI chat is deliberately absent because some modes may
// execute tools with side effects behind a single HTTP request.
const READONLY_POST_WHITELIST = [
  {
    id: 'smartbi-factory-analysis',
    method: 'POST',
    path: /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/,
    bodyMatches: factoryAnalysisBodyMatches,
    rationale: 'Root-approved Java factory analysis contract; authenticated planner reads existing tenant-qualified chart facts and returns no business mutation.',
  },
  {
    id: 'smartbi-factory-details',
    method: 'POST',
    path: /^\/smartbi-api\/api\/smartbi\/financial-dashboard\/factory-details\/?$/,
    rationale: 'Read-only tenant-qualified original-row details from a short-lived server snapshot.',
  },
  {
    id: 'smartbi-factory-recommendations',
    method: 'POST',
    path: /^\/smartbi-api\/api\/smartbi\/financial-dashboard\/factory-recommendations\/?$/,
    rationale: 'Read-only tenant-qualified metrics and bounded chart ranking; handler performs no business writes.',
  },
  {
    id: 'smartbi-factory-document-export',
    method: 'POST',
    path: /^\/smartbi-api\/api\/smartbi\/financial-dashboard\/export-(?:excel|pdf|ppt)\/?$/,
    rationale: 'Pure in-memory document generation from supplied qualified chart snapshots; no database writes.',
  },
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

  const observedApi = (url) => {
    try {
      const path = splitUrl(url).pathname;
      return path.startsWith('/api/mobile/') || path.startsWith('/smartbi-api/');
    } catch { return false; }
  };

  const onRequest = (request) => {
    if (!observedApi(request.url())) return;
    started.set(request, {
      at: Date.now(),
      payload: summarizePayload(parsePostData(request)),
    });
  };

  const onResponse = (response) => {
    const request = response.request();
    if (!observedApi(request.url())) return;
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
    if (!observedApi(request.url())) return;
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
  require('../scenarios/smartbi-factory-analysis-readonly'),
  require('../scenarios/smartbi-catalog-matrix-readonly'),
  require('../scenarios/smartbi-factory-readonly'),
  require('../scenarios/smartbi-guided-readonly'),
  require('../scenarios/scheduling-capabilities-readonly').create,
  require('../scenarios/scheduling-capabilities-readonly').workers,
  require('../scenarios/table-top-scroll-readonly').sales,
  require('../scenarios/table-top-scroll-readonly').purchase,
  require('../scenarios/table-top-scroll-readonly').transfer,
  require('../scenarios/workspace-sidebar-readonly'),
  require('../scenarios/merged-order-state-readonly'),
  require('../scenarios/order-entry-readonly').purchase,
  require('../scenarios/order-entry-readonly').sales,
  require('../scenarios/order-entry-readonly').salesHeaders,
  require('../scenarios/order-entry-readonly').labelHeaders,
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
  // Opt out of optional UI telemetry at its source. Its POST remains a guarded
  // mutation, and older deployed applications that ignore this marker still fail.
  await context.addInitScript(() => {
    window.__CRETAS_READONLY_HARNESS__ = true;
  });
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
        appMountTimeoutMs: options.appMountTimeoutMs,
      });

      const scenarioContext = {
        page,
        context,
        baseUrl,
        expectedUsername,
        expectedFactoryId,
        loginEvidence,
        factoryAnalysisSubjectId: options.factoryAnalysisSubjectId,
        factoryAnalysisUploadedJoint: options.factoryAnalysisUploadedJoint,
        factoryAnalysisLifecycle: options.factoryAnalysisLifecycle,
        factoryCatalogFamilies: options.factoryCatalogFamilies,
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

  const loginMount = await waitForAppMount(page, options.appMountTimeoutMs);
  if (!loginMount.appMounted) throw new Error('Login application did not mount before credential entry');

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
    loginAppMounted: loginMount.appMounted,
    loginAppMountMs: loginMount.appMountMs,
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
const { inspectCanvas, classifyCanvas, hasOnCanvasBomGuidance } = require('./bom-canvas-readonly-shared');

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
      const currentBody = await page.locator('body').innerText();
      const shared = await inspectCanvas(page, currentBody);
      const inspectedBody = await page.locator('body').innerText();
      const { materialNodes, auxiliaryCells, packagingCells, auxDetailEntries, packSubtitles } = shared;

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
      const bomMissingBannerVisible = inspectedBody.includes('暂未读取到生效 BOM') || shared.status.noActive;
      // 现在要求的是「横幅把人指回 cell」, 而不是把人支走
      const bomEntryCopyVisible = hasOnCanvasBomGuidance(inspectedBody);
      const legacyJumpCopyVisible = inspectedBody.includes('去 BOM 配置');
      // 旧抽屉文案则是无条件的: 抽屉已下掉, 它在任何状态下都不该再出现。
      const legacyDrawerCopyVisible = inspectedBody.includes('在右侧配置 BOM');

      const evidence = {
        ...shared,
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
      const classification = classifyCanvas(shared);
      if (classification.result === 'UNVERIFIED' && classification.reason === 'no PROCESS node hosts BOM overlays') {
        return {
          ...evidence,
          contractFailures: [],
          precondition: probedProducts.length
            ? `probed ${probedProducts.length} products, none has a PROCESS node`
            : 'canvas has no PROCESS node and no product candidates could be listed',
          assessment: classification,
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
        classification: contractFailures.length ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: contractFailures.join('; ') } : classification,
        assessment: contractFailures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : classification,
      };
    },
  }),
};

},
    "scenarios/bom-canvas-readonly-shared.js": function(module, exports, require) {
'use strict';

const STATUS_MARKERS = Object.freeze({
  active: /BOM\s*生效\s*v\d+/,
  draft: /草稿\s*v\d+\s*未生效/,
  noActive: /未建\s*BOM|还没有生效\s*BOM/,
  mismatch: /与已启用工艺不一致/,
});

function hasOnCanvasBomGuidance(text) {
  return /辅料\s*\/\s*包材(?:\s*\/\s*副产)?\s*cell\s*上配置/.test(text);
}

function stateFromRows(rows) {
  const rowStates = rows.map((text) => ({
    text,
    active: STATUS_MARKERS.active.test(text),
    draft: STATUS_MARKERS.draft.test(text),
    noActive: STATUS_MARKERS.noActive.test(text),
    mismatch: STATUS_MARKERS.mismatch.test(text),
  }));
  return {
    active: rowStates.some((row) => row.active),
    draft: rowStates.some((row) => row.draft),
    noActive: rowStates.some((row) => row.noActive),
    mismatch: rowStates.some((row) => row.mismatch),
    conflictingProductRows: rowStates.filter((row) => row.active && row.noActive).length,
    rowStates,
  };
}

async function count(locator) {
  return locator.count();
}

async function selectCanvasProduct(page) {
  const selector = page.locator('.toolbar-left .el-select').first();
  if (!(await selector.isVisible().catch(() => false))) {
    return { selected: false, selectionMode: null, failure: 'canonical top product selector missing', probedProducts: [] };
  }
  const selectedText = () => selector.locator('.el-select__selected-item').innerText().catch(() => '');
  const probedProducts = [];
  const startedAt = Date.now();
  const MAX_TRIES = 6;
  const DEADLINE_MS = 90_000;
  for (let index = 0; index < MAX_TRIES && Date.now() - startedAt <= DEADLINE_MS; index += 1) {
    const processNodes = await count(page.locator('.vue-flow__node-process'));
    const label = (await selectedText()).trim();
    if (processNodes > 0 && label && !/选择本条工艺|选择产品/.test(label)) {
      return { selected: true, selectedLabel: label, selectionMode: 'current-top-selector', probedProducts };
    }
    try {
      await selector.click({ timeout: 5_000 });
      const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)');
      await options.first().waitFor({ state: 'visible', timeout: 5_000 });
      if (index >= await options.count()) break;
      const optionLabel = (await options.nth(index).innerText().catch(() => '')).trim();
      await options.nth(index).click({ timeout: 5_000 });
      await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
      await page.locator('.vue-flow__node-process').first().waitFor({ state: 'attached', timeout: 20_000 }).catch(() => {});
      probedProducts.push({ index, option: optionLabel.slice(0, 80), processNodes: await count(page.locator('.vue-flow__node-process')) });
    } catch (error) {
      probedProducts.push({ index, error: String(error?.message || error).slice(0, 120) });
      await page.keyboard.press('Escape').catch(() => {});
      break;
    }
  }
  return { selected: false, selectionMode: 'bounded-top-selector-probe', probedProducts, failure: 'no selected product with PROCESS context' };
}

async function inspectCanvas(page, body) {
  const processNodes = await count(page.locator('.vue-flow__node-process'));
  const materialNodes = await count(page.locator('.vue-flow__node-material'));
  const auxiliaryCells = await count(page.locator('.vue-flow__node-bomAuxiliary'));
  const packagingCells = await count(page.locator('.vue-flow__node-bomPackaging'));
  const byproductCells = await count(page.locator('[data-testid="byproduct-cell"]'));
  let rowTexts = [];
  let versionStatusVisible = await page.getByTestId('workflow-version-status').isVisible().catch(() => false);
  let versionRows = await count(page.getByTestId('workflow-version-status-row'));
  if (versionStatusVisible && versionRows === 0) {
    await page.getByTestId('workflow-version-status-toggle').click().catch(() => {});
    await page.getByTestId('workflow-version-status-row').first().waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
    versionRows = await count(page.getByTestId('workflow-version-status-row'));
    body = await page.locator('body').innerText().catch(() => body);
  }
  rowTexts = await page.getByTestId('workflow-version-status-row').allInnerTexts().catch(() => []);
  const status = stateFromRows(rowTexts.length ? rowTexts : [body]);
  const packSubtitleTexts = await page.getByTestId('pack-subtitle').allInnerTexts().catch(() => []);
  const packagingUnitEvidence = packSubtitleTexts.length > 0
    && packSubtitleTexts.every((text) => /(?:\d+\s*种|分\s*\d+\s*层).*每\s*1\s*\S+成品/.test(text));
  return {
    processNodes,
    materialNodes,
    auxiliaryCells,
    packagingCells,
    byproductCells,
    versionStatusVisible,
    versionRows,
    bomBubbles: await count(page.getByTestId('bom-status-bubble')),
    auxDetailEntries: await count(page.getByTestId('aux-open-detail')),
    packSubtitles: await count(page.getByTestId('pack-subtitle')),
    packSubtitleTexts,
    packagingUnitEvidence,
    status,
    versionRowTexts: rowTexts,
    basicUnitVisible: packagingUnitEvidence || (packagingCells === 0 && /基本单位|投入基准单位/.test(body)),
    legacyVersionTableVisible: body.includes('BOM 配方版本'),
    legacyDrawerCopyVisible: body.includes('在右侧配置 BOM'),
    legacyJumpCopyVisible: body.includes('去 BOM 配置'),
  };
}

function classifyCanvas(evidence, productSelection = { selected: true }) {
  if (!productSelection.selected) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'real product context was not selected' };
  }
  if (evidence.processNodes === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'no PROCESS node hosts BOM overlays' };
  }
  if (!evidence.versionStatusVisible || evidence.versionRows === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'formal BOM version status is absent' };
  }
  const { active, draft, noActive, mismatch } = evidence.status;
  if (!active && !draft && !noActive && !mismatch) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'formal BOM state is not classifiable' };
  }
  if (evidence.status.conflictingProductRows > 0) {
    return { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: 'same product asserts both active and no-active BOM state' };
  }
  if (evidence.packagingCells > 0 && evidence.packSubtitles === 0) {
    return { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: 'packaging cell has no role/unit/quantity evidence' };
  }
  if (!evidence.basicUnitVisible || (evidence.packagingCells > 0 && !evidence.packagingUnitEvidence)) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'basic-unit semantics are absent' };
  }
  if (evidence.packagingCells === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'no packaging fixture is present to verify packaging semantics' };
  }
  return { result: 'PASS', rootCauseClass: 'none', reason: 'canonical canvas has a classifiable BOM state and unit/packaging evidence' };
}

module.exports = { STATUS_MARKERS, selectCanvasProduct, inspectCanvas, classifyCanvas, stateFromRows, hasOnCanvasBomGuidance };


},
    "scenarios/bom-cost-caliber-readonly.js": function(module, exports, require) {
'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { inspectCanvas, classifyCanvas } = require('./bom-canvas-readonly-shared');

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
    path: `${ROUTES.workflow}?tab=product-processes`,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body) => {
      const canvas = await inspectCanvas(page, body);
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
      const classification = classifyCanvas(canvas);
      if (classification.result !== 'PASS' || !body.includes('Workflow')) {
        return {
          ...evidence,
          contractFailures: [],
          precondition: 'Canonical canvas did not reach a product context; cost cards are not rendered',
          assessment: classification.result === 'PASS' ? { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'cost carrier not present on modern canvas' } : classification,
        };
      }

      if (!aggregatedCostCardVisible) {
        return {
          ...evidence,
          contractFailures: [],
          applicability: { result: 'UNVERIFIED', reason: 'retired BOM cost cards have no current canvas carrier' },
          assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' },
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
const { inspectCanvas, classifyCanvas, selectCanvasProduct } = require('./bom-canvas-readonly-shared');

async function selectTargetProduct(page) {
  return selectCanvasProduct(page);
}

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: `${ROUTES.workflow}?tab=product-processes`,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const productSelection = await selectTargetProduct(page);
      const currentBody = await page.locator('body').innerText();
      const contract = await inspectCanvas(page, currentBody);
      const assessment = classifyCanvas(contract, productSelection);
      const unsupportedLegacyContracts = {
        result: 'UNVERIFIED',
        reason: 'retired BOM page carrier has no current equivalent selector',
        checks: ['system historical yield', 'price-unit card', 'finished-good cost denominator', 'active immutable view', 'new-version/clone entry'],
      };
      const screenshots = [await ctx.screenshot('bom-active-readonly')];
      if (assessment.result !== 'PASS') return { ...contract, productSelection, modernCarrierAssessment: assessment, unsupportedLegacyContracts, contractFailures: [], screenshots, assessment };
      const failures = [];
      if (!productSelection.selected) failures.push(productSelection.failure);
      if (contract.legacyVersionTableVisible) failures.push('retired BOM version table still rendered');
      if (contract.legacyDrawerCopyVisible) failures.push('retired drawer copy still rendered');
      if (contract.legacyJumpCopyVisible) failures.push('retired jump-away copy still rendered');
      if (contract.auxiliaryCells > 0 && contract.auxDetailEntries === 0) failures.push('auxiliary cell lacks detail entry');
      if (contract.packagingCells > 0 && contract.packSubtitles === 0) failures.push('packaging cell lacks unit/role subtitle');
      return {
        ...contract,
        productSelection,
        unsupportedLegacyContracts,
        contractFailures: failures,
        screenshots,
        modernCarrierAssessment: failures.length ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: failures.join('; ') } : assessment,
        assessment: failures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'UNVERIFIED', rootCauseClass: 'tool', reason: unsupportedLegacyContracts.reason },
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
    "scenarios/merged-order-state-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { assertSafe } = require('./visual-unverified-followup-readonly');

async function inspect(page, _body, ctx) {
  const listResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && /\/production-plans\?/.test(r.url()));
  await page.reload();
  const response = await listResponse;
  if (!response.ok()) throw new Error('Production plan list failed');
  const payload = await response.json();
  const plans = Array.isArray(payload.data) ? payload.data : payload.data?.content;
  if (payload.success !== true || !Array.isArray(plans)) throw new Error('Unexpected production plan list');
  const candidate = plans.find(p => p.status === 'IN_PROGRESS' && p.productTypeId && p.sourceOrderId
    && Array.isArray(p.sourceOrderIds) && p.sourceOrderIds.some(id => id !== p.sourceOrderId));
  if (!candidate) return { scannedPlanCount: plans.length, limits: 'Current list page only; no existing active merged plan found; no production data created', assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' } };
  const extraId = candidate.sourceOrderIds.find(id => id !== candidate.sourceOrderId);
  const orderResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && new URL(r.url()).pathname.endsWith('/sales/orders/' + encodeURIComponent(extraId)));
  const linkedResponse = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.startsWith('/api/mobile/')
    && new URL(r.url()).pathname.endsWith('/production-plans/by-sales-order/' + encodeURIComponent(extraId)));
  await page.goto(ctx.baseUrl + '/sales/orders/' + encodeURIComponent(extraId));
  const [orderRes, linkedRes] = await Promise.all([orderResponse, linkedResponse]);
  if (!orderRes.ok() || !linkedRes.ok()) throw new Error('Merged order detail read failed');
  const orderPayload = await orderRes.json(), linkedPayload = await linkedRes.json();
  const items = orderPayload.data?.items;
  if (orderPayload.success !== true || linkedPayload.success !== true) throw new Error('Merged order read unsuccessful');
  if (!Array.isArray(linkedPayload.data) || !linkedPayload.data.some(p => p.id === candidate.id)) throw new Error('Merged plan absent from associated order');
  if (!Array.isArray(items) || !items.length || !items.every(i => i.productTypeId === candidate.productTypeId)) {
    return { limits: 'Existing merged order includes other products; full coverage not proven by this narrow scenario', assessment: { result: 'UNVERIFIED', rootCauseClass: 'data' } };
  }
  const button = page.getByRole('button', { name: '生产中', exact: true });
  await button.waitFor({ state: 'visible', timeout: 20000 });
  if (!await button.isDisabled()) throw new Error('Merged order offers duplicate production');
  await button.scrollIntoViewIfNeeded();
  assertSafe(ctx);
  return { scannedPlanCount: plans.length, sourceOrderCount: candidate.sourceOrderIds.length,
    matchedItemCount: items.length, linkedPlanFound: true, productionDisabled: true,
    screenshots: [await ctx.screenshot('merged-order-production-state')],
    limits: 'Existing active merged order read only; no creation, database transaction or cancellation mutation',
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'merged-order-state-readonly', defaultEnabled: false, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'merged-order-state-readonly', path: '/production/plans', screenshot: true, inspect }) };

},
    "scenarios/order-entry-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

async function measureHeaderContrast(page) {
  return page.locator('main.app-content .el-table').first().evaluate(table => {
    const rgb = color => {
      if (!/^rgba?\(/.test(color)) throw new Error('Unsupported computed color format');
      return color.match(/[\d.]+/g).map(Number);
    };
    const luminance = channels => channels.slice(0, 3).map(value => {
      const s = value / 255; return s <= 0.04045 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4;
    }).reduce((sum, value, index) => sum + value * [0.2126, 0.7152, 0.0722][index], 0);
    return [...table.querySelectorAll('.el-table__header th .cell')].filter(cell => cell.textContent.trim()).map(cell => {
      const foreground = getComputedStyle(cell).color;
      let background = 'rgb(255, 255, 255)';
      for (let parent = cell; parent; parent = parent.parentElement) {
        const value = getComputedStyle(parent).backgroundColor, channels = rgb(value), alpha = channels[3] ?? 1;
        if (alpha === 0) continue;
        if (alpha !== 1) throw new Error('Translucent table background needs separate contrast measurement');
        background = value; break;
      }
      const fg = rgb(foreground), bg = rgb(background);
      if ((fg[3] ?? 1) !== 1) throw new Error('Translucent table text needs separate contrast measurement');
      const a = luminance(fg), b = luminance(bg);
      return { header: cell.textContent.trim(), foreground, background, ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
    });
  });
}

function assertHeaderContrast(headers) {
  if (!headers.length) throw new Error('UNVERIFIED: no table header text was measured');
  if (headers.some(header => !Number.isFinite(header.ratio) || header.ratio < 4.5)) {
    throw new Error('Table header text contrast is below 4.5:1');
  }
}

async function contrastInspect(page, _body, ctx) {
  const table = page.locator('main.app-content .el-table').first();
  await table.locator('.el-table__body tbody tr').first().waitFor({ state: 'visible', timeout: 15000 });
  const headers = await measureHeaderContrast(page);
  assertHeaderContrast(headers);
  const screenshot = await ctx.screenshot('table-header-contrast');
  assertSafe(ctx);
  return { measuredHeaderCount: headers.length, minimumContrast: Math.min(...headers.map(header => header.ratio)),
    contrastRatios: headers.map(header => header.ratio), foregrounds: [...new Set(headers.map(header => header.foreground))],
    backgrounds: [...new Set(headers.map(header => header.background))],
    screenshots: [screenshot], limits: 'Current populated table header text only; not all page text or icons',
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}

async function settleVisual(locator) {
  await locator.evaluate(async element => {
    const animations = [...element.getAnimations({ subtree: true })];
    for (let parent = element.parentElement; parent; parent = parent.parentElement) animations.push(...parent.getAnimations());
    await Promise.all([...new Set(animations)]
      .filter(animation => Number.isFinite(animation.effect?.getComputedTiming().endTime))
      .map(animation => animation.finished.catch(() => {})));
  });
}

async function purchaseInspect(page, _body, ctx) {
  const screenshots = [];
  const filter = page.locator('.search-bar .el-select').first();
  await step(ctx, () => filter.click());
  const responsePromise = page.waitForResponse(r => r.request().method() === 'GET'
    && new URL(r.url()).pathname.endsWith('/purchase/orders/by-status')
    && new URL(r.url()).searchParams.get('status') === 'DRAFT', { timeout: 15000 }).catch(() => null);
  await step(ctx, () => page.getByRole('option', { name: '草稿', exact: true }).click());
  const response = await responsePromise;
  if (!response || response.status() >= 400) throw new Error('Draft list query was not successful');
  const payload = await response.json();
  const items = payload.data?.content;
  if (!payload.success || !Array.isArray(items)) throw new Error('Draft list response was not recognized');
  if (!items.length) return { reason: 'No existing draft purchase order available; no data created', assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
  const row = page.locator('.el-table__body tbody tr').filter({ has: page.locator('.el-tag', { hasText: /^草稿$/ }) }).first();
  await row.waitFor({ state: 'visible', timeout: 15000 });
  const dialog = page.getByRole('dialog', { name: '编辑草稿采购订单', exact: true });
  const rounds = [];
  for (let i = 0; i < 2; i++) {
    await step(ctx, () => row.getByRole('button', { name: /更多/ }).click());
    const edit = page.locator('.el-dropdown-menu:visible .el-dropdown-menu__item').filter({ has: page.locator('.row-action-menu__label', { hasText: /^编辑$/ }) });
    await step(ctx, () => edit.click());
    await page.waitForURL(url => Boolean(url.searchParams.get('edit')), { timeout: 10000 });
    await dialog.waitFor({ state: 'visible', timeout: 20000 });
    await settleVisual(dialog);
    const formCount = await dialog.locator('.el-form-item').count();
    if (formCount < 3) throw new Error('Purchase editor opened without its form');
    screenshots.push(await ctx.screenshot(`purchase-edit-open-${i + 1}`));
    await step(ctx, () => dialog.getByRole('button', { name: '取消', exact: true }).click());
    await dialog.waitFor({ state: 'hidden', timeout: 10000 });
    await page.waitForURL(url => !url.searchParams.has('edit'), { timeout: 10000 });
    rounds.push({ opened: true, formCount, closed: true, editQueryCleared: true });
  }
  assertSafe(ctx);
  return { roundsCompleted: rounds.length, formCounts: rounds.map(round => round.formCount), allDialogsClosed: true,
    editQueryCleared: true, screenshots, limits: 'Existing draft opened and cancelled without editing or saving; persistence not verified', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}

async function salesInspect(page, _body, ctx) {
  const screenshots = [];
  await step(ctx, () => page.getByRole('button', { name: '+ 添加产品', exact: true }).click());
  const picker = page.getByRole('dialog', { name: '选择销售产品', exact: true });
  await picker.waitFor({ state: 'visible' });
  const product = picker.locator('.product-picker-row:not(:disabled)').first();
  await product.waitFor({ state: 'visible', timeout: 15000 });
  await step(ctx, () => product.click());
  await step(ctx, () => picker.getByRole('button', { name: /完成选择/ }).click());
  const table = page.locator('.line-table');
  const header = table.locator('.el-table__header th').filter({ hasText: /^来源仓库$/ });
  await header.scrollIntoViewIfNeeded();
  const headers = await table.locator('.el-table__header th').allTextContents();
  const index = headers.findIndex(text => text.trim() === '来源仓库');
  if (index < 0) throw new Error('Sales source warehouse heading is missing');
  const select = table.locator('.el-table__body tbody tr').first().locator('td').nth(index).locator('.el-select');
  await select.scrollIntoViewIfNeeded();
  const control = select.getByRole('combobox');
  const listId = await control.getAttribute('aria-controls');
  await step(ctx, () => select.click());
  const options = page.locator(`[id="${listId}"] .el-select-dropdown__item:not(.is-disabled)`);
  await options.first().waitFor({ state: 'visible', timeout: 15000 });
  const optionCount = await options.count();
  const chosenLabel = (await options.first().innerText()).trim();
  await settleVisual(options.first());
  screenshots.push(await ctx.screenshot('sales-source-warehouse-options'));
  await step(ctx, () => options.first().click());
  await options.first().waitFor({ state: 'hidden', timeout: 5000 });
  if (!(await select.innerText()).includes(chosenLabel)) throw new Error('Warehouse selection label was not retained');
  screenshots.push(await ctx.screenshot('sales-source-warehouse-selected'));
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await table.waitFor({ state: 'hidden', timeout: 10000 });
  assertSafe(ctx);
  return { heading: '来源仓库', optionCount, selectedLabelRetained: true, localDraftClosed: true, screenshots,
    limits: 'Warehouse wording and local selection only; no order saved or stock moved', assessment: { result: 'PASS', rootCauseClass: 'none' } };
}

function scenario(id, path, inspect) {
  return { id, defaultEnabled: false, inspect,
    run: ctx => runReadOnlyPageScenario(ctx, { id, path, inspect: page => inspect(page, '', ctx), screenshot: false }) };
}
module.exports = {
  measureHeaderContrast,
  assertHeaderContrast,
  purchase: scenario('purchase-edit-entry-readonly', '/procurement/orders', purchaseInspect),
  sales: scenario('sales-source-warehouse-readonly', '/sales/orders/new', salesInspect),
  salesHeaders: scenario('sales-header-contrast-readonly', '/sales/orders', contrastInspect),
  labelHeaders: scenario('label-header-contrast-readonly', '/quality/label-qc', contrastInspect),
};

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
      const stockSource = planDialog.getByRole('radio', { name: '存货生产', exact: true });
      await stockSource.check({ force: true });
      if (!await stockSource.isChecked() || await stockSource.inputValue() !== 'SAFETY_STOCK') {
        throw new Error('Stock production source radio did not match SAFETY_STOCK');
      }
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
    "scenarios/scheduling-capabilities-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

// expected: { status, mode } the server must report. UNAVAILABLE ⇒ the automation button must be
// disabled; AVAILABLE ⇒ it must be enabled. APS v2 (Steve 2026-10-28): aiGeneration = DAILY_CAPACITY
// (按产品日产能按天排), 取代 v1 的 DAG_DURATION —— 线上还报 DAG_DURATION 就是旧后端, 判后端缺陷。
const UNAVAILABLE = { status: 'UNAVAILABLE', mode: 'UNAVAILABLE' };
const CREATE_EXPECTED = Object.freeze({ status: 'AVAILABLE', mode: 'DAILY_CAPACITY' });
const CREATE_BUTTON = '按日产能自动排程';

function assessCapability(kind, payload, ui, expected = UNAVAILABLE) {
  if (payload === null) return { result: 'UNVERIFIED', rootCauseClass: 'tool' };
  const data = payload?.success === true ? payload.data : null;
  const capability = data?.[kind];
  const contractMatches = data?.contractVersion === 'scheduling-capabilities/v1'
    && capability?.status === expected.status && capability?.mode === expected.mode
    && typeof capability.reason === 'string' && capability.reason.trim().length > 0;
  const buttonMatches = expected.status === 'AVAILABLE'
    ? ui.automationDisabled === false : ui.automationDisabled === true;
  const consistent = contractMatches && buttonMatches
    && ui.reasonVisible === true && ui.manualExplanationVisible === true;
  return { result: consistent ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: consistent ? 'none' : contractMatches ? 'frontend' : 'backend' };
}

function scenario(kind, id, route, landmark, buttonName, explanation, expected = UNAVAILABLE) {
  return {
    id,
    defaultEnabled: false,
    async run(ctx) {
      let payload = null;
      const reads = [];
      const endpoint = `/api/mobile/${encodeURIComponent(ctx.expectedFactoryId)}/scheduling/capabilities`;
      const listener = response => {
        if (response.request().method() !== 'GET' || new URL(response.url()).pathname !== endpoint) return;
        reads.push(response.json().then(body => { if (response.ok()) payload = body; }).catch(() => {}));
      };
      ctx.page.on('response', listener);
      try {
        return await runReadOnlyPageScenario(ctx, {
          id, path: route, landmarks: [landmark], screenshot: true,
          inspect: async page => {
            await Promise.all(reads);
            const capability = payload?.data?.[kind];
            const button = page.getByRole('button', { name: buttonName });
            const ui = {
              automationDisabled: await button.isDisabled().catch(() => false),
              reasonVisible: typeof capability?.reason === 'string'
                && await page.getByText(capability.reason, { exact: true }).first().isVisible().catch(() => false),
              manualExplanationVisible: await page.getByText(explanation, { exact: true }).first().isVisible().catch(() => false),
            };
            return { capabilityResponseObserved: payload !== null, contractVersion: payload?.data?.contractVersion,
              capability, ui, assessment: assessCapability(kind, payload, ui, expected) };
          },
        });
      } finally {
        ctx.page.off('response', listener);
      }
    },
  };
}

module.exports = {
  create: scenario('aiGeneration', 'scheduling-create-capabilities-readonly', '/scheduling/plans/create',
    '人工确认的排程草稿', CREATE_BUTTON, '请逐项填写产线、基本单位整数数量和起止时间；系统不会自动分配工人，也不会替你确认工艺、物料或换产可行性。',
    CREATE_EXPECTED),
  workers: scenario('workerOptimization', 'scheduling-worker-capabilities-readonly', '/scheduling/workers',
    '人员分配', 'AI 优化分配（暂不可用）', '请使用每个排程卡片上的“添加工人”进行人工指派。'),
  assessCapability,
  CREATE_EXPECTED,
  CREATE_BUTTON,
};

},
    "scenarios/smartbi-catalog-matrix-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis-hub?tab=factory';
const RECOMMENDATIONS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const EXPORTS = [
  { key: 'excel', label: 'Excel', button: '导出当前显示图表 Excel', path: '/smartbi-api/api/smartbi/financial-dashboard/export-excel', extension: 'xlsx' },
  { key: 'pdf', label: 'PDF', button: '导出当前显示图表 PDF', path: '/smartbi-api/api/smartbi/financial-dashboard/export-pdf', extension: 'pdf' },
  { key: 'ppt', label: 'PPT', button: '导出当前显示图表 PPT', path: '/smartbi-api/api/smartbi/financial-dashboard/export-ppt', extension: 'pptx' },
];
const MAX_FAMILIES = 4;
const MAX_OPTION_FAMILIES = 60;
const STEP_TIMEOUT_MS = 30_000;
const TOTAL_TIMEOUT_MS = 10 * 60_000;

function testId(page, id) { return page.locator(`[data-testid="${String(id).replace(/"/gu, '\\"')}"]`); }

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function normalizedPath(url) { return endpointPath(url).replace(/\/$/u, ''); }

function isRecommendationsResponse(response) {
  return response.request().method() === 'POST' && normalizedPath(response.url()) === RECOMMENDATIONS_PATH;
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST' && normalizedPath(response.url()) === DETAILS_PATH;
}

function isExportResponse(response, format) {
  const item = typeof format === 'string' ? EXPORTS.find((entry) => entry.key === format) : format;
  return response.request().method() === 'POST' && Boolean(item)
    && normalizedPath(response.url()) === item.path;
}

function requestBody(response) {
  try { return response.request().postDataJSON() || {}; } catch { return {}; }
}

async function responseBody(response) {
  return response ? response.json().catch(() => ({})) : {};
}

function familyOf(candidate) {
  return String(candidate?.analysis?.familyId || candidate?.familyId || candidate?.id || '').trim() || null;
}

function familyLabelOf(candidate) {
  return String(candidate?.analysis?.familyLabel || candidate?.title || familyOf(candidate) || '').trim() || null;
}

function isRestaurantOnly(candidate) {
  const domain = String(candidate?.domain || candidate?.analysis?.domain || '').toLowerCase();
  const family = String(candidate?.analysis?.familyId || candidate?.familyId || '').toLowerCase();
  const sourceKey = String(candidate?.sourceKey || candidate?.analysis?.sourceKey || '').toLowerCase();
  return domain === 'restaurant' || domain === '餐饮' || family.startsWith('restaurant.')
    || family.startsWith('餐饮.') || sourceKey === 'restaurant' || sourceKey.startsWith('restaurant.');
}

function validateCatalogFamilies(value) {
  if (value == null) return null;
  if (!Array.isArray(value)) throw new Error('factoryCatalogFamilies must be an array of family IDs');
  if (value.length > MAX_OPTION_FAMILIES) throw new Error(`factoryCatalogFamilies must contain at most ${MAX_OPTION_FAMILIES} IDs`);
  const result = value.map((entry) => {
    if (typeof entry !== 'string' || !entry.trim()) throw new Error('factoryCatalogFamilies contains an empty family ID');
    return entry.trim();
  });
  if (new Set(result).size !== result.length) throw new Error('factoryCatalogFamilies contains duplicate family IDs');
  return result;
}

function groupCandidates(candidates) {
  const groups = new Map();
  for (const candidate of Array.isArray(candidates) ? candidates : []) {
    const id = familyOf(candidate);
    if (!id) continue;
    if (!groups.has(id)) groups.set(id, {
      familyId: id,
      familyLabel: familyLabelOf(candidate) || id,
      candidates: [],
    });
    groups.get(id).candidates.push(candidate);
  }
  return [...groups.values()];
}

function candidateIsQualified(candidate) {
  const lazyDetails = typeof candidate?.detailSnapshotKey === 'string'
    && candidate.detailSnapshotKey.trim().length > 0;
  return Boolean(candidate?.id && candidate?.source && candidate?.chart
    && typeof candidate.chart === 'object' && candidate?.analysis?.familyId
    && (candidate?.detailStatus?.state !== 'unavailable' || lazyDetails)
    && candidate?.detailStatus?.state !== 'capacity');
}

function candidateUnitsAligned(candidate) {
  const unit = String(candidate?.unit || '').trim();
  if (!unit) return true; // Financial table candidates may legitimately be multi-unit.
  const factUnits = (Array.isArray(candidate?.kpis) ? candidate.kpis : [])
    .map((fact) => String(fact?.unit || '').trim()).filter(Boolean);
  return factUnits.every((factUnit) => factUnit === unit);
}

function selectCatalogCandidates(data, requestedFamilies) {
  const candidates = Array.isArray(data?.recommendations) ? data.recommendations : [];
  const groups = groupCandidates(candidates);
  const available = new Set(groups.map((group) => group.familyId));
  const requested = requestedFamilies || groups.map((group) => group.familyId);
  const requestedUnknown = requested.filter((family) => !available.has(family));
  const excluded = [];
  const blocked = [];
  const eligibleGroups = groups.filter((group) => requested.includes(group.familyId)).map((group) => {
    const sorted = [...group.candidates];
    const restaurantOnly = sorted.filter(isRestaurantOnly);
    if (restaurantOnly.length) excluded.push(...restaurantOnly.map((candidate) => ({
      familyId: group.familyId,
      familyLabel: group.familyLabel,
      candidateId: candidate.id,
      reason: 'restaurant-only',
    })));
    const candidate = sorted.find((item) => !isRestaurantOnly(item) && candidateIsQualified(item));
    if (!candidate) {
      const blockedCandidate = sorted.find((item) => !isRestaurantOnly(item));
      blocked.push({
        familyId: group.familyId,
        familyLabel: group.familyLabel,
        candidateId: blockedCandidate?.id || null,
        reason: blockedCandidate?.detailStatus?.state || 'candidate-not-qualified',
      });
      return null;
    }
    return { familyId: group.familyId, familyLabel: group.familyLabel, candidate };
  }).filter(Boolean);
  // Families are covered in batches of four by inspectCatalog. Keep the
  // explicit 60-family run bound, but never hide the remainder from the
  // assessment.
  const selected = eligibleGroups.slice(0, MAX_OPTION_FAMILIES);
  const deferred = eligibleGroups.slice(MAX_OPTION_FAMILIES).map((item) => ({
    familyId: item.familyId, familyLabel: item.familyLabel, candidateId: item.candidate.id, reason: 'family-limit-60',
  }));
  return { selected, deferred, blocked, excluded, requestedUnknown };
}

function detailPeriod(data) {
  return data?.period?.label || null;
}

function summarizeExportIds(body) {
  const ids = [];
  for (const chart of Array.isArray(body?.charts) ? body.charts : []) {
    const id = chart?.id || chart?.candidate_id;
    if (id) ids.push(String(id));
  }
  for (const id of Array.isArray(body?.selected_ids) ? body.selected_ids : []) if (id) ids.push(String(id));
  return [...new Set(ids)];
}

function compactDetail(response, body, candidate, expectedPeriod) {
  const data = body?.data;
  return {
    attempted: Boolean(response),
    httpStatus: response ? response.status() : null,
    success: Boolean(response && response.status() === 200 && body?.success === true),
    candidateId: data?.candidateId || null,
    factoryId: data?.factoryId || null,
    source: data?.source || null,
    period: data?.period || null,
    rowTotal: Number.isFinite(data?.total) ? data.total : null,
    rowsPresent: Array.isArray(data?.rows),
    scopeMatches: Boolean(data?.candidateId === candidate.id && data?.source === candidate.source
      && data?.period === expectedPeriod && Array.isArray(data?.rows)),
  };
}

function checkFailures(checks) {
  return Object.entries(checks).filter(([, value]) => value !== true).map(([key]) => key);
}

function assessCatalog({ families, requestedUnknown, deferred, excluded, eligibilityGaps = [], sourceErrors = [], remainingFamilyCount = 0, ui }) {
  const familyPass = families.length > 0 && families.every((item) => item.status === 'PASS');
  const result = familyPass && requestedUnknown.length === 0 && sourceErrors.length === 0
    && deferred.length === 0 && remainingFamilyCount === 0 ? 'PASS' : 'UNVERIFIED';
  const rootCauseClass = result === 'PASS' ? 'none'
    : ui?.missing ? 'frontend'
      : ui?.httpFailure ? 'backend'
        : 'data';
  return {
    result,
    rootCauseClass,
    fullPass: result === 'PASS',
    selectedFamilyCount: families.length,
    familyCount: families.length,
    coveredFamilyCount: families.filter((item) => item.status === 'PASS').length,
    failedFamilyCount: families.filter((item) => item.status !== 'PASS').length,
    remainingFamilyCount,
    requestedUnknown,
    deferredFamilies: deferred,
    excludedFamilies: excluded,
    eligibilityGaps,
    sourceErrors,
    checkFailures: [
      ...families.flatMap((item) => item.checkFailures.map((key) => `${item.familyId}.${key}`)),
      ...deferred.map((item) => `deferred.${item.familyId}`),
      ...sourceErrors.map((item) => `source.${item.source || 'unknown'}`),
    ],
  };
}

function remainingTimeout(deadline) {
  return Math.max(1, Math.min(STEP_TIMEOUT_MS, deadline - Date.now()));
}

async function waitResponse(page, predicate, deadline) {
  return page.waitForResponse(predicate, { timeout: remainingTimeout(deadline) });
}

async function captureExport(page, format, inspectCtx, deadline) {
  const result = { format: format.key, buttonFound: false, downloadStarted: false, httpStatus: null, filename: null, saved: false, candidateIds: [], success: false };
  const button = page.locator('button:visible').filter({ hasText: format.button }).first();
  result.buttonFound = await button.count() > 0;
  if (!result.buttonFound) return result;
  try {
    const responsePromise = waitResponse(page, (response) => isExportResponse(response, format), deadline).catch(() => null);
    const downloadPromise = page.waitForEvent('download', { timeout: remainingTimeout(deadline) }).catch(() => null);
    await button.click();
    const [response, download] = await Promise.all([responsePromise, downloadPromise]);
    result.httpStatus = response ? response.status() : null;
    result.candidateIds = summarizeExportIds(response ? requestBody(response) : {});
    result.downloadStarted = Boolean(download);
    result.filename = download ? download.suggestedFilename() : null;
    if (download && inspectCtx.evidenceDir && result.filename) {
      try {
        await download.saveAs(`${String(inspectCtx.evidenceDir).replace(/[\\/]+$/u, '')}/${result.filename}`);
        result.saved = true;
      } catch {}
    }
    result.success = result.downloadStarted && result.httpStatus === 200
      && (!inspectCtx.evidenceDir || result.saved);
  } catch {}
  return result;
}

async function chooseBrowseMode(page, deadline) {
  const mode = testId(page, 'factory-chart-mode');
  if (!await mode.count()) return false;
  const browse = mode.locator('button, label').filter({ hasText: '按分类浏览' }).first();
  if (!await browse.count()) return false;
  await browse.click();
  await testId(page, 'factory-chart-selection').waitFor({ state: 'visible', timeout: remainingTimeout(deadline) });
  return true;
}

async function clearBrowseSelection(page) {
  const rows = testId(page, 'factory-chart-family');
  const count = await rows.count();
  for (let index = 0; index < count; index += 1) {
    const checkbox = rows.nth(index).locator('input[type="checkbox"]').first();
    if (await checkbox.count() && await checkbox.isChecked().catch(() => false)) {
      await checkbox.click().catch(() => {});
    }
  }
}

async function selectFamilyInBrowse(page, familyId, candidate, deadline) {
  const escapedFamilyId = String(familyId).replace(/"/gu, '\\"');
  const rows = page.locator(`[data-testid="factory-chart-family"][data-family-id="${escapedFamilyId}"]`);
  // The family id is the server identity. The label is presentation-only and
  // may be duplicated across domains or sources.
  if (!await rows.count()) return false;
  const row = rows.first();
  const checkbox = row.locator('input[type="checkbox"]').first();
  if (await checkbox.count() && !(await checkbox.isChecked().catch(() => false))) await checkbox.check().catch(() => {});
  const variant = row.locator('.el-select').first();
  if (await variant.count()) {
    await variant.click().catch(() => {});
    const option = page.locator('.el-select-dropdown__item:visible').filter({ hasText: String(candidate.unit) }).first();
    if (await option.count()) await option.click().catch(() => {});
  }
  await page.waitForTimeout(50);
  return true;
}

async function inspectCatalog(page, _body, inspectCtx) {
  if (inspectCtx.expectedFactoryId !== 'F006') throw new Error('Factory catalog acceptance is limited to F006');
  const startedAt = Date.now();
  const deadline = startedAt + TOTAL_TIMEOUT_MS;
  const optionValue = inspectCtx.factoryCatalogFamilies || inspectCtx.options?.factoryCatalogFamilies;
  const requestedFamilies = validateCatalogFamilies(optionValue);
  const ui = { browseVisible: false, missing: false, httpFailure: false };

  await testId(page, 'factory-chart-guide').waitFor({ state: 'visible', timeout: remainingTimeout(deadline) }).catch(() => {});
  const refresh = testId(page, 'factory-chart-refresh');
  let recommendationResponse = null;
  if (await refresh.count()) {
    const pending = waitResponse(page, isRecommendationsResponse, deadline).catch(() => null);
    await refresh.click().catch(() => {});
    recommendationResponse = await pending;
  }
  if (!recommendationResponse) ui.httpFailure = true;
  const body = await responseBody(recommendationResponse);
  const data = body?.data || {};
  const selected = selectCatalogCandidates(data, requestedFamilies);
  const browse = await chooseBrowseMode(page, deadline).catch(() => false);
  ui.browseVisible = browse;
  if (!browse) ui.missing = true;

  const families = selected.blocked.map((item) => ({
    familyId: item.familyId,
    family: item.familyLabel,
    candidateId: item.candidateId,
    unit: null,
    source: null,
    period: detailPeriod(data),
    facts: [],
    rowTotal: null,
    filenames: [],
    exportCoverage: [],
    http: { recommendation: recommendationResponse?.status() || null, detail: null, exports: {} },
    checks: { familySelected: false, cardRendered: false, chartRendered: false, factsPresent: false, detailHttp: false, detailScope: false, detailRows: false, exportAtLeastOne: false },
    checkFailures: ['candidateQualified'],
    status: 'UNVERIFIED',
    blockedReason: item.reason,
  }));
  const exports = [];
  const processedFamilyIds = new Set();
  const batches = [];
  for (let index = 0; index < selected.selected.length; index += MAX_FAMILIES) {
    batches.push(selected.selected.slice(index, index + MAX_FAMILIES));
  }
  let batchNumber = 0;
  for (const batch of batches) {
    if (Date.now() >= deadline) break;
    batchNumber += 1;
    await clearBrowseSelection(page);
    const batchFamilies = [];
    for (const item of batch) {
      if (Date.now() >= deadline) break;
      const candidate = item.candidate;
      const summary = {
        familyId: item.familyId,
        family: item.familyLabel,
        candidateId: candidate.id,
        unit: candidate.unit || null,
        source: candidate.source || null,
        period: detailPeriod(data),
        facts: Array.isArray(candidate.kpis) ? candidate.kpis.slice(0, 20).map((fact) => ({ label: fact?.label || null, value: fact?.value ?? null, unit: fact?.unit || null })) : [],
        rowTotal: null,
        filenames: [],
        exportCoverage: [],
        http: { recommendation: recommendationResponse?.status() || null, detail: null, exports: {} },
        checks: { familySelected: false, cardRendered: false, chartRendered: false, unitAligned: false, factsPresent: false, detailHttp: false, detailScope: false, detailRows: false, exportAtLeastOne: false },
        checkFailures: [],
        status: 'UNVERIFIED',
        batch: batchNumber,
      };
      summary.checks.unitAligned = candidateUnitsAligned(candidate);
      summary.checks.factsPresent = summary.facts.length > 0;
      summary.checks.familySelected = await selectFamilyInBrowse(page, item.familyId, candidate, deadline).catch(() => false);
      const card = testId(page, `factory-chart-card-${candidate.id}`).first();
      summary.checks.cardRendered = await card.count() > 0 && await card.isVisible().catch(() => false);
      if (!summary.checks.cardRendered) {
        await testId(page, 'factory-chart-guide-generate').click().catch(() => {});
        summary.checks.cardRendered = await card.isVisible().catch(() => false);
      }
      summary.checks.chartRendered = summary.checks.cardRendered && await card.locator('.factory-chart-canvas canvas').count() > 0;

      if (summary.checks.cardRendered) {
    const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true }).first();
        if (await detailsButton.count()) {
          const recommendationRefresh = candidate.detailContext
            ? Promise.resolve(null)
            : waitResponse(page, isRecommendationsResponse, deadline).catch(() => null);
          const detailsResponsePromise = waitResponse(page, isDetailsResponse, deadline).catch(() => null);
          await detailsButton.click().catch(() => {});
          const refreshed = await recommendationRefresh;
          const detailsResponse = await detailsResponsePromise;
          const detailBody = await responseBody(detailsResponse);
          const detail = compactDetail(detailsResponse, detailBody, candidate, detailPeriod(data));
          summary.http.detail = detail.httpStatus;
          summary.rowTotal = detail.rowTotal;
          summary.checks.detailHttp = detail.success;
          summary.checks.detailScope = detail.scopeMatches;
          summary.checks.detailRows = detail.rowsPresent && detail.rowTotal > 0;
          if (refreshed && refreshed.status() !== 200) ui.httpFailure = true;
        }
        if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
      }
      batchFamilies.push(summary);
    }
    if (batchFamilies.length && Date.now() < deadline) {
      const result = await captureExport(page, EXPORTS[0], inspectCtx, deadline);
      exports.push({ batch: batchNumber, ...result });
      for (const family of batchFamilies) {
        if (result.success && result.candidateIds.includes(family.candidateId)) {
          family.exportCoverage.push(result.format);
          family.filenames.push(result.filename);
        }
        family.http.exports[result.format] = result.httpStatus;
      }
    }
    for (const family of batchFamilies) {
      family.checks.exportAtLeastOne = family.exportCoverage.length > 0;
      family.checkFailures = checkFailures(family.checks);
      family.status = family.checkFailures.length ? 'UNVERIFIED' : 'PASS';
      families.push(family);
      processedFamilyIds.add(family.familyId);
    }
  }
  const eligibilityGaps = Array.isArray(data.unavailable) ? data.unavailable.map((item) => ({
    id: item?.id || null,
    title: item?.title || null,
    missing: Array.isArray(item?.missing) ? item.missing.slice(0, 12) : [],
  })) : [];
  const sourceErrors = Array.isArray(data.sourceErrors) ? data.sourceErrors.map((item) => ({
    source: item?.source || null,
    message: item?.message || null,
  })) : [];
  const remainingFamilyCount = selected.selected.filter((item) => !processedFamilyIds.has(item.familyId)).length;
  const assessment = assessCatalog({ families, requestedUnknown: selected.requestedUnknown, deferred: selected.deferred, excluded: selected.excluded, eligibilityGaps, sourceErrors, remainingFamilyCount, ui });
  return {
    assessment,
    ...assessment,
    families,
    exports,
    eligibilityGaps,
    sourceErrors,
    ui,
    durationMs: Date.now() - startedAt,
  };
}

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-catalog-matrix-readonly',
    path: PATH,
    // The browse control appears after asynchronous catalogue discovery;
    // inspectCatalog waits for it and proves selection/details/exports.
    landmarks: ['工厂分析'],
    timeoutMs: 60_000,
    appMountTimeoutMs: 90_000,
    inspect: inspectCatalog,
  });
}

module.exports = {
  id: 'smartbi-catalog-matrix-readonly',
  defaultEnabled: false,
  run,
  inspectCatalog,
  assessCatalog,
  candidateIsQualified,
  candidateUnitsAligned,
  familyOf,
  groupCandidates,
  isRestaurantOnly,
  selectCatalogCandidates,
  summarizeExportIds,
  validateCatalogFamilies,
  isRecommendationsResponse,
  isDetailsResponse,
  isExportResponse,
};

},
    "scenarios/smartbi-factory-analysis-lifecycle.js": function(module, exports, require) {
'use strict';

const FOLLOWUP_ROUNDS = 12;
const MAX_CANDIDATES = 4;
const MAX_WAIT_STEP_MS = 30_000;
const MAX_WAIT_TOTAL_MS = 360_000;

function stable(value) {
  if (value === null || typeof value !== 'object') return JSON.stringify(value);
  if (Array.isArray(value)) return `[${value.map(stable).join(',')}]`;
  return `{${Object.keys(value).sort().map((key) => `${JSON.stringify(key)}:${stable(value[key])}`).join(',')}}`;
}

function periodSignature(period) {
  if (!period || typeof period !== 'object') return null;
  return stable({
    periodType: period.periodType || period.period_type || period.type || null,
    year: period.year || null,
    startMonth: period.startMonth || period.start_month || null,
    endMonth: period.endMonth || period.end_month || null,
    label: period.label || null,
  });
}

function uploadSignature(data) {
  return stable({
    source: data?.source || null,
    uploadId: data?.uploadId || null,
    jointUploads: data?.jointUploads || null,
  });
}

function requestUploadSignature(request) {
  return stable({
    source: request?.source || null,
    uploadId: request?.uploadId || null,
    inspectionUploadId: request?.inspectionUploadId || null,
    costUploadId: request?.costUploadId || null,
  });
}

function candidateFingerprint(candidate) {
  return stable({
    id: candidate?.id || null,
    source: candidate?.source || null,
    unit: candidate?.unit || null,
    detailSnapshotKey: candidate?.detailSnapshotKey || null,
    chart: candidate?.chart || null,
    kpis: candidate?.kpis || null,
  });
}

function factsFingerprint(facts) {
  return stable((Array.isArray(facts) ? facts : []).map((fact) => ({
    id: fact?.id || null, label: fact?.label || null, value: fact?.value ?? null,
    unit: fact?.unit || null, source: fact?.source || null,
  })));
}

function selectedFingerprint(data) {
  return (Array.isArray(data?.recommendations) ? data.recommendations : [])
    .slice(0, MAX_CANDIDATES).map(candidateFingerprint);
}

function selectedDetailsStable(data, baseline) {
  const current = Array.isArray(data?.recommendations) ? data.recommendations : [];
  const expected = new Set(baseline?.selected || []);
  return current.every((item) => {
    return expected.has(candidateFingerprint(item));
  });
}

function validateContextOnlyTurn({ baseline, body, request, round, expectedContextId, requireFactsStable = false }) {
  const data = body?.data || {};
  const recommendations = Array.isArray(data.recommendations) ? data.recommendations : [];
  const requestKeys = Object.keys(request || {}).sort().join(',');
  const checks = {
    round: Number.isInteger(round) && round >= 1 && round <= FOLLOWUP_ROUNDS,
    httpSuccess: body?.success === true && data.status === 'answered',
    noUnavailable: data.status !== 'unavailable' && !data.detailStatus?.state?.includes?.('unavailable'),
    contextOnlyRequest: requestKeys === 'contextId,question' && typeof request?.contextId === 'string' && typeof request?.question === 'string',
    requestContextMatches: !expectedContextId || request?.contextId === expectedContextId,
    factory: data.factoryId === baseline.factoryId,
    source: uploadSignature(data) === baseline.uploadSignature,
    period: periodSignature(data.period) === baseline.periodSignature,
    subject: (data.analysisSubject || null) === baseline.analysisSubject,
    boundedRecommendations: recommendations.length > 0 && recommendations.length <= MAX_CANDIDATES,
    detailsScoped: recommendations.every((candidate) => !candidate.detailContext || candidate.detailContext.id)
      && recommendations.every((candidate) => !candidate.detailSnapshotKey || typeof candidate.detailSnapshotKey === 'string'),
    selectedDetailsStable: selectedDetailsStable(data, baseline),
    factsStableWhenRequired: !requireFactsStable || factsFingerprint(data.facts) === baseline.factsSignature,
  };
  return {
    round,
    checks,
    pass: Object.values(checks).every(Boolean),
    unavailable: data.status === 'unavailable' || data.detailStatus?.state === 'unavailable',
    source: data.source || null,
    period: data.period || null,
    subject: data.analysisSubject || null,
    recommendationCount: recommendations.length,
    selected: selectedFingerprint(data),
  };
}

function lifecycleBaseline(data, request) {
  return {
    factoryId: data?.factoryId || null,
    uploadSignature: uploadSignature(data),
    requestUploadSignature: requestUploadSignature(request),
    periodSignature: periodSignature(data?.period),
    analysisSubject: data?.analysisSubject || null,
    factsSignature: factsFingerprint(data?.facts),
    selected: selectedFingerprint(data),
  };
}

function validateRefresh({ original, refreshed, detail, baseline, originalDetail }) {
  const data = refreshed?.data || {};
  const candidate = (Array.isArray(data.recommendations) ? data.recommendations : [])
    .find((item) => item?.id === original?.id);
  const checks = {
    httpSuccess: refreshed?.success === true,
    factory: data.factoryId === baseline.factoryId,
    source: uploadSignature(data) === baseline.uploadSignature,
    period: periodSignature(data.period) === baseline.periodSignature,
    subject: (data.analysisSubject || null) === baseline.analysisSubject,
    candidatePresent: Boolean(candidate),
    snapshotUnchanged: Boolean(candidate && original?.detailSnapshotKey && candidate.detailSnapshotKey === original.detailSnapshotKey),
    candidateSource: Boolean(candidate && candidate.source === original.source),
    candidateUnit: Boolean(candidate && candidate.unit === original.unit),
    detailSuccess: detail?.success === true && detail?.data?.candidateId === original?.id,
    detailRowsUnchanged: !Array.isArray(originalDetail?.data?.rows)
      || stable(originalDetail.data.rows) === stable(detail?.data?.rows || []),
  };
  const dataChanged = checks.candidatePresent && !checks.snapshotUnchanged;
  return {
    checks,
    pass: Object.values(checks).every(Boolean),
    dataChanged,
    oldSnapshotKey: original?.detailSnapshotKey || null,
    refreshedSnapshotKey: candidate?.detailSnapshotKey || null,
    rows: Array.isArray(detail?.data?.rows) ? detail.data.rows : [],
  };
}

async function waitForExpiry(page, expiresAt, { now = () => Date.now(), sleep = (ms) => page.waitForTimeout(ms), startedAt = now() } = {}) {
  const expiryMs = Date.parse(expiresAt || '');
  if (!Number.isFinite(expiryMs)) return { state: 'unavailable', reason: 'missing-or-invalid-expiresAt', waits: [], elapsedMs: now() - startedAt };
  const waits = [];
  while (now() < expiryMs) {
    const elapsedMs = now() - startedAt;
    const budgetMs = MAX_WAIT_TOTAL_MS - elapsedMs;
    if (budgetMs <= 0) return { state: 'timeout', waits, elapsedMs };
    const remaining = expiryMs - now();
    const waitMs = Math.min(MAX_WAIT_STEP_MS, budgetMs, Math.max(1, remaining));
    waits.push(waitMs);
    await sleep(waitMs);
  }
  return { state: 'expired', waits, elapsedMs: now() - startedAt };
}

module.exports = {
  FOLLOWUP_ROUNDS,
  MAX_CANDIDATES,
  MAX_WAIT_STEP_MS,
  MAX_WAIT_TOTAL_MS,
  stable,
  periodSignature,
  uploadSignature,
  requestUploadSignature,
  candidateFingerprint,
  factsFingerprint,
  selectedFingerprint,
  selectedDetailsStable,
  lifecycleBaseline,
  validateContextOnlyTurn,
  validateRefresh,
  waitForExpiry,
};

},
    "scenarios/smartbi-factory-analysis-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');
const {
  FOLLOWUP_ROUNDS,
  lifecycleBaseline,
  validateContextOnlyTurn,
  validateRefresh,
  waitForExpiry,
} = require('./smartbi-factory-analysis-lifecycle');

const PATH = '/smart-bi/analysis?tab=query&mode=factory';
const ANALYSIS_PATH = /^\/api\/mobile\/[^/]+\/smart-bi\/factory-analysis\/?$/;
const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const QUESTION = '汇总当前库存、生产和销售表现';
const JOINT_QUESTION = '关联生产、检验和成本做多表分析';

const EXPORT_FORMATS = [
  { key: 'excel', label: 'Excel', path: '/smartbi-api/api/smartbi/financial-dashboard/export-excel', extension: 'xlsx' },
  { key: 'pdf', label: 'PDF', path: '/smartbi-api/api/smartbi/financial-dashboard/export-pdf', extension: 'pdf' },
  { key: 'ppt', label: 'PPT', path: '/smartbi-api/api/smartbi/financial-dashboard/export-ppt', extension: 'pptx' },
];

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function isAnalysisResponse(response) {
  return response.request().method() === 'POST' && ANALYSIS_PATH.test(endpointPath(response.url()));
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST'
    && endpointPath(response.url()).replace(/\/$/, '') === DETAILS_PATH;
}

function isRecommendationsResponse(response) {
  return response.request().method() === 'POST'
    && endpointPath(response.url()).replace(/\/$/, '') === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
}

function isExportResponse(response, format) {
  const expectedPath = typeof format === 'string'
    ? EXPORT_FORMATS.find((item) => item.key === format)?.path
    : format?.path;
  return response.request().method() === 'POST'
    && Boolean(expectedPath)
    && endpointPath(response.url()).replace(/\/$/, '') === expectedPath;
}

function requestBody(response) {
  try { return response.request().postDataJSON() || {}; } catch { return {}; }
}

async function responseBody(response) {
  return response.json().catch(() => ({}));
}

async function runFactoryAnalysisLifecycle(page, inspectCtx, initialBody, initialRequest, initialCandidate, initialDetail) {
  const optionValue = inspectCtx.factoryAnalysisLifecycle || inspectCtx.options?.factoryAnalysisLifecycle;
  const option = optionValue === true ? {} : (optionValue || null);
  if (!option) return { enabled: false, result: 'NOT_RUN', rounds: [], unavailableRounds: [], expiry: null };
  const baseline = lifecycleBaseline(initialBody?.data || {}, initialRequest || {});
  const startedAt = Date.now();
  const rounds = [];
  let currentContextId = initialBody?.data?.contextId || null;
  let expiry = null;
  const screenshots = [];
  const waitForNaturalExpiry = option.waitForDetailExpiry === true || option.waitForExpiry === true;
  for (let round = 1; round <= FOLLOWUP_ROUNDS; round += 1) {
    if (!currentContextId) {
      rounds.push({ round, pass: false, reason: 'missing-context-id' });
      break;
    }
    const followups = page.locator('.followups:visible .el-button');
    const followup = followups.first();
    if (!await followup.count()) {
      rounds.push({ round, pass: false, reason: 'missing-followup-control' });
      break;
    }
    const responsePromise = page.waitForResponse(isAnalysisResponse, { timeout: 30_000 });
    await followup.click();
    const response = await responsePromise;
    const body = await responseBody(response);
    const request = requestBody(response);
    const checked = validateContextOnlyTurn({
      baseline,
      body: { ...body, success: response.status() === 200 && body.success !== false },
      request,
      round,
      expectedContextId: currentContextId,
      requireFactsStable: true,
    });
    checked.status = response.status();
    checked.requestContextId = request.contextId || null;
    checked.responseContextId = body?.data?.contextId || null;
    checked.snapshotKeys = checked.selected
      .map((item) => { try { return JSON.parse(item).detailSnapshotKey || null; } catch { return null; } })
      .filter(Boolean);
    rounds.push(checked);
    if (checked.unavailable || !checked.pass) break;
    currentContextId = body?.data?.contextId || currentContextId;
  }

  if (waitForNaturalExpiry) {
    if (!initialCandidate?.detailContext?.expiresAt) {
      expiry = { state: 'unavailable', reason: 'missing-expiresAt', waits: [], elapsedMs: Date.now() - startedAt };
    } else {
      expiry = await waitForExpiry(page, initialCandidate.detailContext.expiresAt, {
        sleep: (ms) => page.waitForTimeout(ms),
      });
    }
    if (expiry.state === 'expired') {
      let refreshVisible = false;
      let recommendationRefreshObserved = false;
      let phaseReason = 'expired-card-not-found';
      let recovery = { pass: false, reason: 'missing-expired-detail-button', checks: {} };
      try {
        const card = page.getByTestId(`factory-chart-card-${initialCandidate.id}`).first();
        const detailsButton = card.locator('button:visible').filter({ hasText: '查看原始明细' }).first();
        const detailsFallback = await detailsButton.count()
          ? detailsButton
          : card.getByTestId('factory-analysis-detail-button');
        if (!await detailsFallback.count()) throw new Error('expired-card-not-visible');
        await detailsFallback.click();
        // Expiry is fail-closed in the UI before a request is sent; wait for
        // the visible recovery action rather than a nonexistent detail call.
        const refreshControl = page.locator('button:visible').filter({ hasText: '重新获取原始明细' }).first();
        const refreshFallback = await refreshControl.count()
          ? refreshControl
          : page.locator('[data-testid="factory-analysis-refresh-button"]:visible').first();
        try {
          await refreshFallback.waitFor({ state: 'visible', timeout: 30_000 });
          refreshVisible = true;
        } catch {}
        if (refreshVisible) {
          screenshots.push(await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-lifecycle-expired'));
        }
        let refreshed = null;
        let refreshedDetail = null;
        if (refreshVisible) {
          const recommendationResponsePromise = page.waitForResponse(isRecommendationsResponse, { timeout: 30_000 });
          const detailResponsePromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
          await refreshFallback.click();
          const recommendationResponse = await recommendationResponsePromise;
          recommendationRefreshObserved = true;
          refreshed = await responseBody(recommendationResponse);
          const detailResponseAfterRefresh = await detailResponsePromise;
          refreshedDetail = detailResponseAfterRefresh ? await responseBody(detailResponseAfterRefresh) : null;
        }
        recovery = validateRefresh({
          original: initialCandidate,
          refreshed: refreshed || { success: false, data: {} },
          detail: refreshedDetail,
          baseline,
          originalDetail: initialDetail,
        });
        phaseReason = recovery.pass ? 'expiry-refresh-recovered' : recovery.dataChanged ? 'expiry-refresh-data-changed' : 'expiry-refresh-check-failed';
        if (recovery.pass && await page.locator('.el-drawer:visible').count()) {
          screenshots.push(await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-lifecycle-recovered'));
        }
        if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
      } catch (error) {
        phaseReason = error?.message === 'expired-card-not-visible' ? 'expired-card-not-visible' : 'expiry-refresh-click-or-request-failed';
      }
      expiry = { ...expiry, refreshVisible, recommendationRefreshObserved, phaseReason, recovery, oldDetail: initialDetail?.data || null };
    }
  }

  const unavailableRounds = rounds.filter((round) => round.unavailable).map((round) => round.round);
  const roundsPass = rounds.length === FOLLOWUP_ROUNDS && rounds.every((round) => round.pass);
  const recoveryPass = !waitForNaturalExpiry || (expiry?.state === 'expired' && expiry.recovery?.pass === true);
  const naturalChange = expiry?.recovery?.dataChanged === true;
  const checkFailKeys = [
    ...rounds.flatMap((round) => Object.entries(round.checks || {}).filter(([, value]) => value !== true).map(([key]) => `round${round.round}.${key}`)),
    ...Object.entries(expiry?.recovery?.checks || {}).filter(([, value]) => value !== true).map(([key]) => `expiry.${key}`),
  ];
  return {
    enabled: true,
    result: roundsPass && recoveryPass ? 'PASS' : naturalChange ? 'UNVERIFIED_DATA_CHANGED' : 'UNVERIFIED',
    rounds,
    roundsPass,
    unavailableRounds,
    expiry,
    baseline,
    screenshots,
    lifecycleRoundsPassed: roundsPass,
    lifecycleExpiryRecovered: recoveryPass,
    lifecycleContextIds: [currentContextId, ...rounds.flatMap((round) => [round.requestContextId, round.responseContextId])].filter(Boolean),
    idsCreatedCount: new Set([currentContextId, ...rounds.flatMap((round) => [round.requestContextId, round.responseContextId])].filter(Boolean)).size,
    refreshVisible: expiry?.refreshVisible === true,
    recommendationRefreshObserved: expiry?.recommendationRefreshObserved === true,
    phaseReason: expiry?.phaseReason || (waitForNaturalExpiry ? expiry?.reason || 'expiry-not-run' : 'expiry-disabled'),
    checkFailKeys,
    durationMs: Date.now() - startedAt,
  };
}

function periodSignature(period) {
  if (!period || typeof period !== 'object') return null;
  return JSON.stringify({
    type: period.periodType || period.period_type || period.type || null,
    year: period.year || null,
    startMonth: period.startMonth || period.start_month || null,
    endMonth: period.endMonth || period.end_month || null,
    label: period.label || null,
  });
}

function familyId(candidate) {
  return candidate?.analysis?.familyId || candidate?.familyId || candidate?.id || null;
}

function isFusedCandidate(candidate) {
  return familyId(candidate) === 'production.inspection_fused';
}

function selectAuthorizedSubject(subjects, requestedId) {
  if (!requestedId) return subjects[0];
  const subject = subjects.find((item) => String(item?.id) === String(requestedId));
  if (!subject) throw new Error('Requested joint-analysis subject is absent from the authorized response');
  return subject;
}

function validateUploadedJointOptions(value) {
  if (value == null) return null;
  if (!value || typeof value !== 'object') throw new Error('factoryAnalysisUploadedJoint must be an object');
  const ids = ['uploadId', 'inspectionUploadId', 'costUploadId'].map((key) => value[key]);
  if (ids.some((id) => !Number.isSafeInteger(id) || id <= 0) || new Set(ids).size !== 3) {
    throw new Error('factoryAnalysisUploadedJoint requires three positive distinct upload IDs');
  }
  if (value.subjectId != null && (typeof value.subjectId !== 'string' || !value.subjectId.trim())) {
    throw new Error('factoryAnalysisUploadedJoint subjectId must be a non-empty string when provided');
  }
  return {
    uploadId: ids[0],
    inspectionUploadId: ids[1],
    costUploadId: ids[2],
    subjectId: value.subjectId || null,
    expectedSubjectCount: Number.isSafeInteger(value.expectedSubjectCount) ? value.expectedSubjectCount : 100,
    expectedRows: Number.isSafeInteger(value.expectedRows) ? value.expectedRows : 5,
    expectedKpis: value.expectedKpis && typeof value.expectedKpis === 'object' ? value.expectedKpis : { production: 500, inspection: 96, quality: 95, cost: 6250 },
  };
}

function exactJointUploads(data, expected) {
  const joint = data?.jointUploads;
  return data?.source === 'upload' && data?.uploadId === expected.uploadId
    && joint?.inspectionUploadId === expected.inspectionUploadId
    && joint?.costUploadId === expected.costUploadId;
}

function candidateHasExpectedKpis(candidate, expectedKpis) {
  const parse = (value) => {
    if (typeof value === 'number') return Number.isFinite(value) ? value : NaN;
    if (typeof value !== 'string') return NaN;
    const normalized = value.replace(/[,%，％\s]/gu, '');
    return Number(normalized);
  };
  const values = (candidate?.kpis || []).map((item) => parse(item?.value));
  return Object.values(expectedKpis).every((value, index) => values[index] === parse(value));
}

function assessUploadedJoint({ initial, selected, followup, details, expected, exportAttempt }) {
  const selectedData = selected?.body?.data || {};
  const candidate = (selectedData.recommendations || [])[0];
  const rows = candidate?.tableData?.rows;
  const selectedRequest = selected?.request || {};
  const followupRequest = followup?.request || {};
  const checks = {
    initialUploadRequest: initial?.request?.source === 'upload'
      && initial.request.uploadId === expected.uploadId
      && initial.request.inspectionUploadId === expected.inspectionUploadId
      && initial.request.costUploadId === expected.costUploadId,
    initialSubjectCount: initial?.body?.data?.subjects?.length === expected.expectedSubjectCount,
    selectedRequestExactIds: selectedRequest.source === 'upload'
      && selectedRequest.uploadId === expected.uploadId
      && selectedRequest.inspectionUploadId === expected.inspectionUploadId
      && selectedRequest.costUploadId === expected.costUploadId,
    selectedResponseExactIds: exactJointUploads(selectedData, expected),
    selectedSubject: selectedRequest.analysisSubject === expected.subjectId
      && selectedData.analysisSubject === expected.subjectId,
    samePeriod: JSON.stringify(selectedData.period || null) === JSON.stringify(initial?.body?.data?.period || null),
    oneCandidate: Array.isArray(selectedData.recommendations) && selectedData.recommendations.length === 1,
    expectedRows: Array.isArray(rows) && rows.length === expected.expectedRows,
    expectedKpis: candidateHasExpectedKpis(candidate, expected.expectedKpis),
    detailAttempted: details?.attempted === true,
    detailSucceeded: details?.success === true && details?.total === expected.expectedRows,
    followupContextOnly: followupRequest.contextId === selectedData.contextId
      && typeof followupRequest.question === 'string'
      && Object.keys(followupRequest).sort().join(',') === 'contextId,question',
    followupResponseExactIds: exactJointUploads(followup?.body?.data, expected),
  };
  // The uploaded-joint export gate is opt-in so legacy callers and fixtures
  // retain their prior contract. When supplied, it is independent from the
  // business-joint export and is required for an uploaded-joint PASS.
  if (exportAttempt !== undefined) {
    checks.uploadedJointExportAttempted = exportAttempt?.attempted === true;
    checks.uploadedJointExportSucceeded = exportAttempt?.success === true;
  }
  const pass = Object.values(checks).every(Boolean);
  return {
    result: pass ? 'PASS' : 'PARTIAL_DEFECT',
    rootCauseClass: pass ? 'none' : 'frontend',
    checks,
    requestIds: { uploadId: selectedRequest.uploadId || null, inspectionUploadId: selectedRequest.inspectionUploadId || null, costUploadId: selectedRequest.costUploadId || null },
    responseJointUploads: selectedData.jointUploads || null,
    subjectId: selectedData.analysisSubject || null,
    period: selectedData.period || null,
    candidateCount: Array.isArray(selectedData.recommendations) ? selectedData.recommendations.length : 0,
    rowCount: Array.isArray(rows) ? rows.length : 0,
    uploadedJointExportAttempted: exportAttempt === undefined ? null : exportAttempt?.attempted === true,
    uploadedJointExportSucceeded: exportAttempt === undefined ? null : exportAttempt?.success === true,
    uploadedJointExportFormats: exportAttempt === undefined ? null : exportAttempt?.formats || {},
    uploadedJointExport: exportAttempt === undefined ? null : {
      attempted: exportAttempt?.attempted === true,
      success: exportAttempt?.success === true,
      formats: exportAttempt?.formats || {},
    },
    note: pass ? 'Explicit three-upload UI selection, metadata, subject, bounded candidate, original details, KPI, and context-only follow-up passed.' : 'Optional explicit three-upload acceptance was attempted but one or more grounded UI/API checks failed.',
  };
}

function hasThreeAxis(candidate) {
  const option = candidate?.chart?.echartsOption;
  return Array.isArray(option?.yAxis) && option.yAxis.length >= 3
    && Array.isArray(option?.series) && option.series.length >= 3;
}

function normalizedDomain(value) {
  return String(value || '').trim().toLocaleLowerCase();
}

function domainGroup(value) {
  const text = normalizedDomain(value);
  if (/(inventory|stock|库存)/u.test(text)) return 'inventory';
  if (/(production|manufactur|生产)/u.test(text)) return 'production';
  if (/(sales|order|shipment|销售)/u.test(text)) return 'sales';
  return null;
}

function qualitySemantic(value) {
  return /(quality|inspection|defect|良品|质量|检验|不良|production\.fused)/iu.test(String(value || ''));
}

function summaryFromResponse(status, body, request) {
  const data = body?.data || {};
  const recommendations = Array.isArray(data.recommendations) ? data.recommendations : [];
  const facts = Array.isArray(data.facts) ? data.facts : [];
  const limitations = Array.isArray(data.limitations) ? data.limitations : [];
  const catalog = Array.isArray(data.catalog) ? data.catalog : [];
  const families = new Set(recommendations.map(familyId).filter(Boolean).map(String));
  const fusedRecommendations = recommendations.filter(isFusedCandidate);
  const recommendationDomains = [...new Set(recommendations.flatMap((item) => [item?.domain, familyId(item)]).filter(Boolean).map(String))];
  const catalogDomains = [...new Set(catalog.flatMap((item) => [item?.domain, item?.familyId]).filter(Boolean).map(String))];
  const availableOverviewGroups = new Set(catalogDomains.map(domainGroup).filter(Boolean));
  const selectedOverviewGroups = new Set(recommendationDomains.map(domainGroup).filter(Boolean));
  const missingOverviewGroups = ['inventory', 'production', 'sales']
    .filter((group) => availableOverviewGroups.has(group) && !selectedOverviewGroups.has(group));
  return {
    http200: status === 200,
    envelopeSuccess: body?.success === true,
    status: typeof data.status === 'string' ? data.status : null,
    answered: data.status === 'answered',
    unavailable: data.status === 'unavailable',
    clarification: data.status === 'clarification',
    factoryScope: data.factoryId === 'F006',
    source: data.source || null,
    period: periodSignature(data.period),
    recommendationCount: recommendations.length,
    factCount: facts.length,
    sourceErrorCount: Array.isArray(data.sourceErrors) ? data.sourceErrors.length : 0,
    limitationCount: limitations.length,
    familyCount: families.size,
    familyIds: [...families],
    domains: recommendationDomains,
    catalogDomains,
    qualityGrounded: recommendationDomains.some(qualitySemantic),
    overviewScopeCoverage: missingOverviewGroups.length === 0 || limitations.length > 0,
    fusedCount: fusedRecommendations.length,
    fusedThreeAxis: fusedRecommendations.some(hasThreeAxis),
    fusedTableRowCount: fusedRecommendations.reduce((count, item) => count + (Array.isArray(item?.tableData?.rows) ? item.tableData.rows.length : 0), 0),
    catalogCount: catalog.length,
    contextPresent: typeof data.contextId === 'string' && data.contextId.length > 0,
    followUpCount: Array.isArray(data.followUpQuestions) ? data.followUpQuestions.length : 0,
    requestContract: Object.keys(request).sort(),
    requestSource: request.source || null,
    requestPeriod: request.periodType
      ? JSON.stringify({ periodType: request.periodType, year: request.year, startMonth: request.startMonth, endMonth: request.endMonth })
      : null,
    requestContextPresent: typeof request.contextId === 'string' && request.contextId.length > 0,
    requestSubjectPresent: typeof request.analysisSubject === 'string' && request.analysisSubject.trim().length > 0,
  };
}

function assessJoint({ initial, selected, ui, details, exportAttempt }) {
  const initialSummary = initial?.summary || {};
  const selectedSummary = selected?.summary || {};
  const hasFused = selectedSummary.fusedCount > 0;
  const expectedLimitation = !hasFused
    && selectedSummary.sourceErrorCount === 0
    && selectedSummary.limitationCount > 0
    && (selectedSummary.unavailable || selectedSummary.answered || selectedSummary.clarification);
  const checks = {
    initialClarification: initialSummary.clarification === true,
    selectedSubjectRetried: selected?.attempted === true,
    selectedSubjectProvided: selectedSummary.requestSubjectPresent === true,
    selectedFactoryF006: selectedSummary.factoryScope === true,
    selectedSourceBusiness: selectedSummary.source === 'business',
    selectedAnswered: selectedSummary.answered === true,
    fusedFamily: hasFused,
    fusedThreeAxis: selectedSummary.fusedThreeAxis === true,
    fusedTable: selectedSummary.fusedTableRowCount > 0,
    fusedFacts: selectedSummary.factCount > 0,
    detailAttempted: details?.attempted === true,
    detailSucceeded: details?.success === true,
    exportAttempted: exportAttempt?.attempted === true,
    exportSucceeded: exportAttempt?.success === true,
  };
  const positivePass = Object.values(checks).every(Boolean);
  return {
    result: positivePass ? 'PASS' : expectedLimitation ? 'NEGATIVE_PASS' : 'UNVERIFIED',
    positiveJoint: positivePass ? 'PASS' : 'UNVERIFIED_DATA_NOT_ELIGIBLE',
    negativePass: expectedLimitation,
    expectedLimitation,
    checks,
    status: selectedSummary.status || null,
    note: positivePass
      ? 'Real production.inspection_fused candidate passed family, three-axis, table, facts, details, and export checks.'
      : expectedLimitation
        ? 'Expected limitation recorded: no eligible complete joint dataset; positive joint acceptance remains UNVERIFIED_DATA_NOT_ELIGIBLE.'
        : 'Joint analysis did not provide either a grounded fused candidate or an evidenced data limitation.',
  };
}

function assess({ initial, followup, ui, details, exportAttempt, joint, uploadedJoint }) {
  const initialSummary = initial?.summary || {};
  const followupSummary = followup?.summary || {};
  const initialAnswered = initialSummary.answered === true;
  const unavailable = initialSummary.unavailable === true || followupSummary.unavailable === true;
  const checks = {
    initialHttp200: initialSummary.http200 === true,
    initialSuccess: initialSummary.envelopeSuccess === true,
    factoryF006: initialSummary.factoryScope === true && followupSummary.factoryScope === true,
    sourceBusiness: initialSummary.source === 'business' && followupSummary.source === 'business',
    samePeriod: Boolean(initialSummary.period) && initialSummary.period === followupSummary.period,
    answeredWithChartsAndFacts: initialAnswered && initialSummary.recommendationCount > 0 && initialSummary.factCount > 0,
    followupAnsweredWithChartsOrFacts: followupSummary.answered === true
      && (followupSummary.recommendationCount > 0 || followupSummary.factCount > 0),
    boundedFamilies: initialSummary.familyCount > 0 && initialSummary.familyCount <= 4
      && followupSummary.familyCount > 0 && followupSummary.familyCount <= 4,
    catalogRetained: initialSummary.catalogCount > 0 && ui?.catalogVisibleCount > 0,
    visibleChartsRendered: ui?.visibleChartCount > 0 && ui?.renderedCanvasCount > 0 && ui?.drawingErrorCount === 0,
    groundedFactsVisible: initialSummary.factCount === 0 || ui?.visibleFactCount > 0,
    limitationsVisible: initialSummary.limitationCount === 0 || ui?.visibleLimitationCount > 0,
    sourceErrorsVisible: initialSummary.sourceErrorCount === 0 || ui?.visibleSourceErrorCount > 0,
    noSourceErrors: initialSummary.sourceErrorCount === 0 && followupSummary.sourceErrorCount === 0,
    overviewScopeCoverage: initialSummary.overviewScopeCoverage === true,
    followupContract: followupSummary.requestContract?.join(',') === 'contextId,question'
      && followupSummary.requestContextPresent === true,
    followupDifferentDimension: ui?.followupDifferentDimension === true,
    followupQualityGrounded: followupSummary.qualityGrounded === true,
    detailsAttempted: details?.attempted === true,
    detailsSucceeded: details?.success === true,
    exportAttempted: exportAttempt?.attempted === true,
    exportSucceeded: exportAttempt?.success === true,
  };
  const validChecks = Object.values(checks).every(Boolean);
  const jointAssessment = joint || assessJoint({});
  const acceptedJoint = jointAssessment.positiveJoint === 'PASS' || jointAssessment.negativePass === true;
  const result = unavailable ? 'UNVERIFIED' : validChecks && acceptedJoint && (!uploadedJoint || uploadedJoint.result === 'PASS') ? 'PASS' : uploadedJoint ? 'PARTIAL_DEFECT' : 'UNVERIFIED';
  return {
    result,
    rootCauseClass: result === 'PASS' ? 'none' : result === 'PARTIAL_DEFECT' ? 'frontend' : 'tool',
    checks,
    fullPass: result === 'PASS' && validChecks && jointAssessment.positiveJoint === 'PASS' && (!uploadedJoint || uploadedJoint.result === 'PASS'),
    partialReadWarn: !checks.noSourceErrors,
    joint: jointAssessment,
    status: initialSummary.status,
    followupStatus: followupSummary.status,
    chartCount: ui?.visibleChartCount || 0,
    factCount: initialSummary.factCount || 0,
    familyCount: initialSummary.familyCount || 0,
    catalogCount: initialSummary.catalogCount || 0,
    unavailableIsUnverified: unavailable ? result === 'UNVERIFIED' : true,
    clarificationIsUnverified: initialSummary.clarification ? result === 'UNVERIFIED' : true,
    note: unavailable || initialSummary.clarification
      ? 'AI returned unavailable/clarification; this run is UNVERIFIED and does not claim AI acceptance.'
      : validChecks && acceptedJoint
        ? jointAssessment.negativePass
          ? 'Overview checks passed; joint analysis recorded an expected data limitation without claiming a positive AI result.'
          : 'Grounded overview/joint chart-fact, follow-up, details, and export checks passed.'
        : 'One or more grounded UI checks were incomplete.',
  };
}

async function screenshotRedacted(ctx, page, name) {
  const changed = await page.evaluate(() => {
    const selectors = [
      '[data-testid="factory-analysis-question"]',
      '[data-testid="factory-analysis-answer"]',
      '[data-testid="factory-analysis-facts"]',
      '.scope-hint',
      '.details-meta',
      '.point-context',
      '.details-table-wrap',
    ];
    const records = [];
    for (const selector of selectors) {
      [...document.querySelectorAll(selector)].forEach((element, index) => {
        records.push({ selector, index, html: element.innerHTML });
        element.textContent = '[脱敏证据]';
      });
    }
    return records;
  });
  try { return await ctx.screenshot(name); }
  finally {
    await page.evaluate((records) => {
      for (const record of records) {
        const element = document.querySelectorAll(record.selector)[record.index];
        if (element) element.innerHTML = record.html;
      }
    }, changed).catch(() => {});
  }
}

async function waitForResponse(page, predicate, action, timeout = 120_000) {
  const responsePromise = page.waitForResponse(predicate, { timeout });
  await action();
  return responsePromise;
}

function summarizeExportResults(results) {
  const formats = {};
  for (const result of results) formats[result.format] = {
    attempted: result.attempted === true,
    buttonFound: result.buttonFound === true,
    downloadStarted: result.downloadStarted === true,
    httpStatus: Number.isInteger(result.httpStatus) ? result.httpStatus : null,
    saved: result.saved === true,
    success: result.success === true,
    fileName: result.fileName || null,
  };
  const complete = EXPORT_FORMATS.every((format) => formats[format.key]);
  const attempted = complete && EXPORT_FORMATS.every((format) => formats[format.key].attempted);
  const success = attempted && EXPORT_FORMATS.every((format) => formats[format.key].success);
  return { attempted, success, formats };
}

async function downloadExportFormats(page, answer, evidenceDir, prefix, timeout = 30_000) {
  const results = [];
  const tools = answer.locator('.answer-tools:visible').first();
  for (const format of EXPORT_FORMATS) {
    const fileName = `${prefix}-${format.key}.${format.extension}`;
    const result = {
      format: format.key,
      attempted: true,
      buttonFound: false,
      downloadStarted: false,
      httpStatus: null,
      saved: false,
      success: false,
      fileName,
    };
    try {
      const button = tools.getByRole('button', { name: format.label, exact: true }).first();
      result.buttonFound = await button.count() > 0;
      if (!result.buttonFound) {
        results.push(result);
        continue;
      }
      const downloadPromise = page.waitForEvent('download', { timeout }).catch(() => null);
      const exportResponsePromise = page.waitForResponse(
        (response) => isExportResponse(response, format),
        { timeout },
      ).catch(() => null);
      await button.click();
      const [download, exportResponse] = await Promise.all([downloadPromise, exportResponsePromise]);
      result.downloadStarted = Boolean(download);
      result.httpStatus = exportResponse ? exportResponse.status() : null;
      if (download && exportResponse && exportResponse.status() === 200) {
        const target = `${String(evidenceDir || '').replace(/[\\/]+$/, '')}/${fileName}`;
        if (evidenceDir) {
          await download.saveAs(target);
          result.saved = true;
        }
      }
      result.success = result.downloadStarted && result.httpStatus === 200 && (Boolean(evidenceDir) ? result.saved : true);
    } catch {
      result.success = false;
    }
    results.push(result);
  }
  return summarizeExportResults(results);
}

async function run(ctx) {
  return runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-analysis-readonly',
    path: PATH,
    landmarks: ['工厂 AI 问答分析', '当前授权图表目录'],
    timeoutMs: 60_000,
    appMountTimeoutMs: 90_000,
    inspect: async (page, _body, inspectCtx) => {
      if (inspectCtx.expectedFactoryId !== 'F006') throw new Error('Factory AI acceptance is limited to F006');
      await page.getByTestId('factory-analysis-chat').waitFor({ timeout: 60_000 });
      const initialResponse = await waitForResponse(
        page,
        isAnalysisResponse,
        async () => {
          await page.getByTestId('factory-analysis-input').fill(QUESTION);
          await page.getByTestId('factory-analysis-submit').click();
        },
      );
      const initialBody = await responseBody(initialResponse);
      const initial = {
        status: initialResponse.status(),
        summary: summaryFromResponse(initialResponse.status(), initialBody, requestBody(initialResponse)),
      };

      const uploadedJoint = validateUploadedJointOptions(
        inspectCtx.factoryAnalysisUploadedJoint || inspectCtx.options?.factoryAnalysisUploadedJoint,
      );

      if (!initial.summary.answered) {
        const screenshot = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-unverified');
        const assessment = assess({ initial, followup: { summary: {} }, ui: {}, details: {}, exportAttempt: {} });
        return { assessment, ...assessment, screenshots: [screenshot] };
      }

      await page.getByTestId('factory-analysis-answer').waitFor({ timeout: 30_000 });
      const visibleCards = page.locator('[data-testid^="factory-chart-card-"]:visible');
      await visibleCards.first().waitFor({ timeout: 30_000 });
      const visibleChartCount = await visibleCards.count();
      const renderedCanvasCount = await visibleCards.locator('.factory-chart-canvas canvas').count();
      const drawingErrorCount = await visibleCards.locator('[role="alert"]').count();
      const visibleFactCount = await page.locator('[data-testid="factory-analysis-facts"] .fact:visible').count();
      const visibleLimitationCount = await page.locator('[data-testid="factory-analysis-limitations"]:visible').count();
      const visibleSourceErrorCount = await page.locator('[data-testid="factory-analysis-source-errors"]:visible').count();

      const catalogToggle = page.getByRole('button', { name: '展开目录', exact: true });
      if (await catalogToggle.count()) await catalogToggle.click();
      const catalogVisibleCount = await page.locator('.catalog-item:visible').count();
      const screenshotBeforeInteraction = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-answer');

      const firstDetailId = initialBody?.data?.recommendations?.find((item) => item?.detailContext?.id)?.id;
      let details = { attempted: false, success: false, drawerVisible: false };
      if (firstDetailId) {
        const card = page.getByTestId(`factory-chart-card-${firstDetailId}`);
        const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true });
        if (await detailsButton.count()) {
          details.attempted = true;
          const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
          await detailsButton.click();
          const detailResponse = await responsePromise;
          const detailBody = detailResponse ? await responseBody(detailResponse) : {};
          details.success = Boolean(detailResponse && detailResponse.status() === 200 && detailBody.success === true
            && detailBody.data?.factoryId === 'F006' && Array.isArray(detailBody.data?.rows));
          details.drawerVisible = await page.locator('.el-drawer:visible').count() > 0;
        }
      }
      const screenshotAfterDetails = details.drawerVisible
        ? await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-details')
        : null;
      if (details.drawerVisible) await page.keyboard.press('Escape').catch(() => {});

      let lifecycle = { enabled: false, result: 'NOT_RUN', rounds: [], unavailableRounds: [], expiry: null };

      const exportAttempt = await downloadExportFormats(
        page,
        page.locator('.turn.turn--assistant:visible').last(),
        inspectCtx.evidenceDir,
        'smartbi-factory-analysis-overview',
      );

      const followupButtons = page.locator('.followups:visible .el-button');
      const qualityFollowup = followupButtons.filter({ hasText: '质量' }).first();
      const followupButton = await qualityFollowup.count() ? qualityFollowup : followupButtons.first();
      const followupDifferentDimension = await followupButton.count()
        ? (await followupButton.innerText()).includes('质量')
        : false;
      let followup = { summary: {} };
      if (await followupButton.count()) {
        const followupResponse = await waitForResponse(page, isAnalysisResponse, () => followupButton.click());
        const followupBody = await responseBody(followupResponse);
        followup = {
          status: followupResponse.status(),
          summary: summaryFromResponse(followupResponse.status(), followupBody, requestBody(followupResponse)),
        };
      }

      // Start a fresh conversation for the explicit cross-table branch. The
      // backend must first ask for a real product subject; the selected
      // subject is taken from that response, never invented by the harness.
      let jointInitial = { summary: {} };
      let jointSelected = { attempted: false, summary: {} };
      let jointSelectedBody = {};
      let jointDetails = { attempted: false, success: false };
      let jointExport = { attempted: false, success: false, formats: {} };
      let jointScreenshot = null;
      let jointSubjectId = null;
      const newAnalysisButton = page.getByRole('button', { name: '新分析', exact: true });
      if (await newAnalysisButton.count()) {
        const jointInitialResponse = await waitForResponse(
          page,
          isAnalysisResponse,
          async () => {
            await newAnalysisButton.click();
            await page.getByTestId('factory-analysis-input').fill(JOINT_QUESTION);
            await page.getByTestId('factory-analysis-submit').click();
          },
        );
        const jointInitialBody = await responseBody(jointInitialResponse);
        jointInitial = {
          status: jointInitialResponse.status(),
          summary: summaryFromResponse(jointInitialResponse.status(), jointInitialBody, requestBody(jointInitialResponse)),
        };

        const subjects = Array.isArray(jointInitialBody?.data?.subjects) ? jointInitialBody.data.subjects : [];
        if (jointInitial.summary.clarification && subjects.length > 0) {
          const subject = selectAuthorizedSubject(subjects, inspectCtx.factoryAnalysisSubjectId);
          jointSubjectId = subject.id;
          if (typeof subject?.label !== 'string' || !subject.label.trim()) throw new Error('Factory AI clarification returned an unusable subject label');
          const subjectControl = page.getByTestId('factory-analysis-subject');
          await subjectControl.click();
          const subjectOption = page.locator('[role="option"], .el-select-dropdown__item')
            .filter({ hasText: subject.label }).first();
          await subjectOption.waitFor({ timeout: 15_000 });
          await subjectOption.click();
          const retryButton = page.getByRole('button', { name: '按当前产品重试', exact: true });
          await retryButton.waitFor({ timeout: 15_000 });
          const selectedResponse = await waitForResponse(page, isAnalysisResponse, () => retryButton.click());
          jointSelectedBody = await responseBody(selectedResponse);
          jointSelected = {
            attempted: true,
            status: selectedResponse.status(),
            summary: summaryFromResponse(selectedResponse.status(), jointSelectedBody, requestBody(selectedResponse)),
          };

          if (jointSelected.summary.fusedCount > 0) {
            const fusedId = jointSelectedBody?.data?.recommendations?.find(isFusedCandidate)?.id;
            const fusedCard = fusedId ? page.getByTestId(`factory-chart-card-${fusedId}`) : null;
            if (fusedCard) {
              await fusedCard.waitFor({ state: 'visible', timeout: 30_000 });
              jointScreenshot = await screenshotRedacted(inspectCtx, page, 'smartbi-factory-analysis-joint');
              const detailsButton = fusedCard.getByRole('button', { name: '查看原始明细', exact: true });
              if (await detailsButton.count()) {
                jointDetails.attempted = true;
                const detailsPromise = page.waitForResponse(isDetailsResponse, { timeout: 30_000 }).catch(() => null);
                await detailsButton.click();
                const detailsResponse = await detailsPromise;
                const detailBody = detailsResponse ? await responseBody(detailsResponse) : {};
                jointDetails.success = Boolean(detailsResponse && detailsResponse.status() === 200
                  && detailBody.success === true && detailBody.data?.factoryId === 'F006'
                  && Array.isArray(detailBody.data?.rows));
                if (await page.locator('.el-drawer:visible').count()) await page.keyboard.press('Escape').catch(() => {});
              }
            }
            const jointAnswer = page.locator('.turn.turn--assistant:visible').last();
            jointExport = await downloadExportFormats(
              page,
              jointAnswer,
              inspectCtx.evidenceDir,
              'smartbi-factory-analysis-joint',
            );
          }
        }
      }

      let uploadedJointAssessment = null;
      let uploadedJointExport = { attempted: false, success: false, formats: {} };
      if (uploadedJoint) {
        const uploadedPath = `${inspectCtx.baseUrl}${PATH}&source=upload&upload_id=${uploadedJoint.uploadId}&inspection_upload_id=${uploadedJoint.inspectionUploadId}&cost_upload_id=${uploadedJoint.costUploadId}`;
        await page.goto(uploadedPath, { waitUntil: 'domcontentloaded', timeout: 60_000 });
        await page.getByTestId('factory-analysis-chat').waitFor({ timeout: 60_000 });
        const jointMode = page.getByTestId('factory-analysis-joint-mode');
        const productionUpload = page.getByTestId('factory-analysis-upload');
        const inspectionUpload = page.getByTestId('factory-analysis-inspection-upload');
        const costUpload = page.getByTestId('factory-analysis-cost-upload');
        if (!await jointMode.count() || !await productionUpload.count() || !await inspectionUpload.count() || !await costUpload.count()) {
          throw new Error('Explicit uploaded-joint UI selectors are not mounted');
        }
        const uploadedInitialResponse = await waitForResponse(
          page,
          isAnalysisResponse,
          async () => {
            await page.getByTestId('factory-analysis-input').fill(JOINT_QUESTION);
            await page.getByTestId('factory-analysis-submit').click();
          },
        );
        const uploadedInitialBody = await responseBody(uploadedInitialResponse);
        const uploadedInitial = { status: uploadedInitialResponse.status(), body: uploadedInitialBody, request: requestBody(uploadedInitialResponse) };
        let uploadedSelected = { attempted: false, body: {}, request: {} };
        let uploadedFollowup = { attempted: false, body: {}, request: {} };
        const uploadedSubjects = Array.isArray(uploadedInitialBody?.data?.subjects) ? uploadedInitialBody.data.subjects : [];
        let uploadedDetails = { attempted: false, success: false, total: null, body: null };
        if (uploadedInitialBody?.data?.status === 'clarification' && uploadedSubjects.length > 0) {
          const subject = selectAuthorizedSubject(uploadedSubjects, uploadedJoint.subjectId);
          const selectedSubjectId = subject.id;
          const subjectControl = page.getByTestId('factory-analysis-subject');
          await subjectControl.click();
          await page.locator('[role="option"], .el-select-dropdown__item').filter({ hasText: subject.label }).first().click();
          const retryButton = page.getByRole('button', { name: '按当前产品重试', exact: true });
          const selectedResponse = await waitForResponse(page, isAnalysisResponse, () => retryButton.click());
          uploadedSelected = { attempted: true, body: await responseBody(selectedResponse), request: requestBody(selectedResponse) };
          uploadedJoint.subjectId = selectedSubjectId;
          const uploadedCandidate = (uploadedSelected.body?.data?.recommendations || []).find(isFusedCandidate) || uploadedSelected.body?.data?.recommendations?.[0];
          if (uploadedCandidate) {
            const card = page.getByTestId(`factory-chart-card-${uploadedCandidate.id}`);
            const detailsButton = card.getByRole('button', { name: '查看原始明细', exact: true });
            if (await detailsButton.count()) {
              uploadedDetails.attempted = true;
              const detailResponse = await waitForResponse(page, isDetailsResponse, () => detailsButton.click());
              const detailBody = await responseBody(detailResponse);
              uploadedDetails.body = detailBody;
              uploadedDetails.total = Number(detailBody?.data?.total);
              uploadedDetails.success = detailResponse.status() === 200
                && detailBody?.success === true
                && Array.isArray(detailBody?.data?.rows)
                && detailBody.data.rows.length > 0
                && await page.locator('.el-drawer:visible').count() > 0;
              if (uploadedDetails.success) await page.keyboard.press('Escape').catch(() => {});
            }
          }
          const positiveFused = uploadedSelected.body?.data?.recommendations?.filter(isFusedCandidate) || [];
          const uploadedSubjectMatches = uploadedSelected.body?.data?.analysisSubject === uploadedJoint.subjectId;
          if (positiveFused.length === 1 && uploadedSubjectMatches) {
            const uploadedAnswer = page.locator('.turn.turn--assistant:visible').last();
            uploadedJointExport = await downloadExportFormats(
              page,
              uploadedAnswer,
              inspectCtx.evidenceDir,
              'smartbi-factory-analysis-uploaded-joint',
            );
          }
          if (inspectCtx.factoryAnalysisLifecycle || inspectCtx.options?.factoryAnalysisLifecycle) {
            if (positiveFused.length === 1 && uploadedSubjectMatches) {
              lifecycle = await runFactoryAnalysisLifecycle(
                page,
                inspectCtx,
                uploadedSelected.body,
                uploadedSelected.request,
                positiveFused[0],
                uploadedDetails.body,
              );
            } else {
              lifecycle = {
                enabled: true,
                result: 'UNVERIFIED',
                reason: 'lifecycle-requires-one-fused-candidate-and-explicit-subject',
                rounds: [],
                unavailableRounds: [],
                expiry: null,
              };
            }
          }
          const followupButtons = page.locator('.followups:visible .el-button');
          const followupButton = followupButtons.filter({ hasText: '质量' }).first();
          const followupResponse = await waitForResponse(page, isAnalysisResponse, async () => {
            if (await followupButton.count()) await followupButton.click();
            else {
              await page.getByTestId('factory-analysis-input').fill('再看质量指标');
              await page.getByTestId('factory-analysis-submit').click();
            }
          });
          uploadedFollowup = { attempted: true, body: await responseBody(followupResponse), request: requestBody(followupResponse) };
          uploadedJointAssessment = assessUploadedJoint({ initial: uploadedInitial, selected: uploadedSelected, followup: uploadedFollowup, details: uploadedDetails, expected: uploadedJoint, exportAttempt: uploadedJointExport });
        } else {
          uploadedJointAssessment = assessUploadedJoint({ initial: uploadedInitial, selected: uploadedSelected, followup: uploadedFollowup, details: uploadedDetails, expected: uploadedJoint, exportAttempt: uploadedJointExport });
        }
      }
      const assessment = assess({
        initial,
        followup,
        ui: { catalogVisibleCount, visibleChartCount, renderedCanvasCount, drawingErrorCount, visibleFactCount, visibleLimitationCount, visibleSourceErrorCount, followupDifferentDimension },
        details,
        exportAttempt,
        joint: assessJoint({
          initial: jointInitial,
          selected: jointSelected,
          ui: {},
          details: jointDetails,
          exportAttempt: jointExport,
        }),
        uploadedJoint: uploadedJointAssessment,
      });
      if (lifecycle.enabled && lifecycle.result !== 'PASS') {
        assessment.result = lifecycle.result;
        assessment.rootCauseClass = lifecycle.result === 'UNVERIFIED_DATA_CHANGED'
          ? 'data'
          : lifecycle.unavailableRounds?.length || lifecycle.expiry?.state === 'unavailable'
            ? 'backend'
            : lifecycle.reason ? 'data' : 'frontend';
        assessment.fullPass = false;
        assessment.lifecycle = lifecycle;
      }
      return {
        assessment,
        ...assessment,
        detailsAttempted: details.attempted,
        detailsSucceeded: details.success,
        exportAttempted: exportAttempt.attempted,
        exportSucceeded: exportAttempt.success,
        exportFormats: exportAttempt.formats,
        joint: assessment.joint,
        jointSubjectId,
        jointTables: (jointSelectedBody?.data?.recommendations || []).filter(isFusedCandidate)
          .map((item) => ({ unit: item.unit, headers: item.tableData?.headers, rows: item.tableData?.rows })),
        jointExportFormats: jointExport.formats,
        uploadedJoint: uploadedJointAssessment,
        uploadedJointExport,
        uploadedJointExportAttempted: uploadedJointAssessment?.uploadedJointExportAttempted ?? null,
        uploadedJointExportSucceeded: uploadedJointAssessment?.uploadedJointExportSucceeded ?? null,
        uploadedJointExportFormats: uploadedJointAssessment?.uploadedJointExportFormats || {},
        lifecycle,
        lifecycleRoundsPassed: lifecycle.lifecycleRoundsPassed ?? null,
        lifecycleExpiryRecovered: lifecycle.lifecycleExpiryRecovered ?? null,
        lifecycleContextIds: lifecycle.lifecycleContextIds || [],
        idsCreatedCount: lifecycle.idsCreatedCount ?? null,
        refreshVisible: lifecycle.refreshVisible ?? null,
        recommendationRefreshObserved: lifecycle.recommendationRefreshObserved ?? null,
        phaseReason: lifecycle.phaseReason || null,
        checkFailKeys: lifecycle.checkFailKeys || [],
        screenshots: [screenshotBeforeInteraction, ...(screenshotAfterDetails ? [screenshotAfterDetails] : []), ...(jointScreenshot ? [jointScreenshot] : []), ...(lifecycle.screenshots || [])],
      };
    },
  });
}

module.exports = {
  selectAuthorizedSubject,
  validateUploadedJointOptions,
  assessUploadedJoint,
  id: 'smartbi-factory-analysis-readonly',
  defaultEnabled: false,
  run,
  assess,
  assessJoint,
  runFactoryAnalysisLifecycle,
  summaryFromResponse,
  isAnalysisResponse,
  isRecommendationsResponse,
  isExportResponse,
  summarizeExportResults,
  downloadExportFormats,
};

},
    "scenarios/smartbi-factory-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const DETAILS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
const DETAIL_PAGE_SIZE = 50;
const MAX_CANVAS_CLICK_ATTEMPTS = 7;
const MAX_DETAIL_CANDIDATES = 6;
const DETAIL_ACCEPTANCE_DEADLINE_MS = 85_000;
const DETAIL_RESPONSE_TIMEOUT_MS = 8_000;
const POINT_CLICK_BUDGET_MS = (MAX_CANVAS_CLICK_ATTEMPTS * 3_500) + 5_000;

function endpointPath(url) {
  try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; }
}

function isDetailsResponse(response) {
  return response.request().method() === 'POST' && endpointPath(response.url()).replace(/\/$/, '') === DETAILS_PATH;
}

function requestJson(request) {
  try { return request.postDataJSON(); } catch { return null; }
}

function expectedDetailIdentity(expected) {
  return {
    factoryId: expected.factoryId,
    candidateId: expected.candidateId,
    source: expected.source,
    period: expected.period,
    contextIdPresent: typeof expected.contextId === 'string' && expected.contextId.length > 0,
  };
}

const DATE_DIMENSIONS = new Set(['日期', '月份', '生产日期', '入库日期', '销售日期', '会计期间', '月', '年月', 'date', 'month']);
// Mirrors the backend details.py _classify semantic allow-list, narrowed to
// dimensions that can identify a chart point. Free-text remarks and measures
// are deliberately excluded even when they happen to equal point.name.
const POINT_DIMENSION_BASES = new Set([
  ...DATE_DIMENSIONS,
  '产品', '产品名称', 'SKU', '品类', '工序', '工序名称', '产线', '原料', '原料名称', '物料',
  '供应商', '供应商名称', '批次', '批次号', '订单号', '单号', '账单号', '仓库', '库位',
  '部门', '渠道', '车间', '班组', '状态', '规格', '型号', '单位', '数量单位',
  '订单ID', '订单行ID', '报工ID', '设备ID', '部门ID', '工序序', '原料ID', '流转记录ID',
  '来源节点', '去向节点',
  '来源WIP号', '目标WIP号', '产出WIP号', '库存库龄桶', '库存效期桶',
]);

function dimensionBaseName(field) {
  return String(field || '').replace(/[（(].*?[）)]$/, '').trim();
}

function pointValueMatches(value, name, field) {
  const text = String(value ?? '').trim();
  const wanted = String(name ?? '').trim();
  if (text === wanted) return true;
  if (!DATE_DIMENSIONS.has(dimensionBaseName(field))) return false;
  const parsed = new Date(text);
  if (Number.isNaN(parsed.getTime())) return false;
  if (/^\d{4}-\d{2}$/.test(wanted)) {
    return `${parsed.getUTCFullYear()}-${String(parsed.getUTCMonth() + 1).padStart(2, '0')}` === wanted;
  }
  if (/^\d{1,2}月$/.test(wanted)) return String(parsed.getUTCMonth() + 1) === wanted.slice(0, -1);
  if (/^\d{4}$/.test(wanted)) return String(parsed.getUTCFullYear()) === wanted;
  return false;
}

function pointMatchingDimensions(data, point) {
  const available = Array.isArray(data?.availableFilters)
    ? data.availableFilters.map((item) => item?.field).filter((field) => typeof field === 'string' && field)
    : [];
  const semanticColumns = Array.isArray(data?.columns)
    ? data.columns.filter((field) => typeof field === 'string' && POINT_DIMENSION_BASES.has(dimensionBaseName(field)))
    : [];
  const allowed = [...new Set([...available, ...semanticColumns])]
    .filter((field) => POINT_DIMENSION_BASES.has(dimensionBaseName(field)));
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const matches = rows.map((row) => allowed.filter((field) => pointValueMatches(row?.[field], point.name, field)));
  const expectedDimension = point.dimension;
  const dimensionContract = expectedDimension
    ? allowed.includes(expectedDimension) && matches.every((fields) => fields.includes(expectedDimension))
    : true;
  return {
    allowedFields: allowed,
    expectedDimension: expectedDimension || null,
    rowMatches: matches,
    everyRowMatchesAllowedDimension: rows.length > 0 && matches.every((fields) => fields.length > 0),
    dimensionContract,
  };
}

function assessDetailsResponse({ status, request, body, expected, point = null }) {
  const payload = request || {};
  const data = body?.data;
  const rows = Array.isArray(data?.rows) ? data.rows : [];
  const expectedPeriod = expected.period == null ? null : String(expected.period);
  const checks = {
    http200: status === 200,
    success: body?.success === true,
    factory: data?.factoryId === expected.factoryId,
    candidate: data?.candidateId === expected.candidateId,
    source: data?.source === expected.source,
    period: expectedPeriod === null || String(data?.period) === expectedPeriod,
    context: payload.context_id === expected.contextId,
    page: payload.page === 1 && data?.page === 1,
    pageSize: payload.page_size === DETAIL_PAGE_SIZE && data?.pageSize === DETAIL_PAGE_SIZE,
    rows: rows.length <= DETAIL_PAGE_SIZE,
    total: Number.isInteger(data?.total) && data.total >= rows.length,
  };
  if (point) {
    checks.pointRequest = payload.point?.name === point.name
      && (point.dataIndex == null || payload.point?.dataIndex === point.dataIndex)
      && (!point.seriesName || payload.point?.seriesName === point.seriesName);
    const pointDimensions = pointMatchingDimensions(data, point);
    checks.pointRows = rows.length > 0 && Number.isInteger(data?.total) && data.total > 0
      && pointDimensions.everyRowMatchesAllowedDimension && pointDimensions.dimensionContract;
    checks.pointDimensionContract = pointDimensions.dimensionContract;
    checks.pointRowsMatchDimension = pointDimensions.everyRowMatchesAllowedDimension;
  } else {
    checks.noPointRequest = payload.point == null;
  }
  const passed = Object.values(checks).every(Boolean);
  return {
    result: passed ? 'PASS' : 'CONFIRMED_DEFECT',
    rootCauseClass: passed ? 'none' : 'backend',
    checks,
    identity: expectedDetailIdentity(expected),
    point: point || null,
    pointDimensions: point ? pointMatchingDimensions(data, point) : null,
    rowCount: rows.length,
    total: Number.isInteger(data?.total) ? data.total : null,
    columns: Array.isArray(data?.columns) ? data.columns : [],
    hasMore: data?.hasMore === true,
    request: {
      contextIdMatches: payload.context_id === expected.contextId,
      page: payload.page ?? null,
      pageSize: payload.page_size ?? null,
      pointPresent: payload.point != null,
      pointName: typeof payload.point?.name === 'string' ? payload.point.name : null,
    },
  };
}

function stableRowKey(row) {
  if (!row || typeof row !== 'object') return JSON.stringify(row);
  return JSON.stringify(Object.keys(row).sort().reduce((out, key) => {
    out[key] = row[key];
    return out;
  }, {}));
}

function duplicateCount(rows) {
  const counts = new Map();
  for (const row of rows || []) {
    const key = stableRowKey(row);
    counts.set(key, (counts.get(key) || 0) + 1);
  }
  return [...counts.values()].filter((count) => count > 1).reduce((sum, count) => sum + count - 1, 0);
}

function assessPageContinuity(first, second, expected = {}) {
  const firstData = first?.body?.data;
  const secondData = second?.body?.data;
  const firstRows = Array.isArray(firstData?.rows) ? firstData.rows : [];
  const secondRows = Array.isArray(secondData?.rows) ? secondData.rows : [];
  const total = firstData?.total;
  const expectedSecondCount = Number.isInteger(total) ? Math.min(DETAIL_PAGE_SIZE, Math.max(0, total - DETAIL_PAGE_SIZE)) : -1;
  const firstRequest = first?.request || {};
  const secondRequest = second?.request || {};
  const checks = {
    firstHttp200: first?.status === 200,
    secondHttp200: second?.status === 200,
    firstPage: firstData?.page === 1,
    secondPage: secondData?.page === 2,
    sameTotal: Number.isInteger(total) && total === secondData?.total,
    firstPageSize: firstData?.pageSize === DETAIL_PAGE_SIZE,
    secondPageSize: secondData?.pageSize === DETAIL_PAGE_SIZE,
    firstHasMore: firstData?.hasMore === true,
    secondCount: secondRows.length === expectedSecondCount,
    loadedCount: firstRows.length + secondRows.length === Math.min(Number.isInteger(total) ? total : 0, DETAIL_PAGE_SIZE * 2),
    firstRequestContext: !expected.contextId || firstRequest.context_id === expected.contextId,
    secondRequestContext: !expected.contextId || secondRequest.context_id === expected.contextId,
    firstRequestPage: firstRequest.page === 1,
    secondRequestPage: secondRequest.page === 2,
    firstRequestPageSize: firstRequest.page_size === DETAIL_PAGE_SIZE,
    secondRequestPageSize: secondRequest.page_size === DETAIL_PAGE_SIZE,
    secondFactory: !expected.factoryId || secondData?.factoryId === expected.factoryId,
    secondCandidate: !expected.candidateId || secondData?.candidateId === expected.candidateId,
    secondSource: !expected.source || secondData?.source === expected.source,
    secondPeriod: !expected.period || String(secondData?.period) === String(expected.period),
  };
  const allRows = [...firstRows, ...secondRows];
  const passed = Object.values(checks).every(Boolean);
  return {
    result: passed ? 'PASS' : 'CONFIRMED_DEFECT',
    rootCauseClass: passed ? 'none' : 'frontend',
    completeness: 'UNVERIFIED',
    completenessReason: 'Page responses prove the navigation contract only; an independent source snapshot or SQL comparison is required to prove no missing rows.',
    checks,
    total: Number.isInteger(total) ? total : null,
    firstRowCount: firstRows.length,
    secondRowCount: secondRows.length,
    loadedRowCount: allRows.length,
    duplicateRowCount: duplicateCount(allRows),
    request: {
      contextIdMatches: checks.secondRequestContext,
      page: secondRequest.page ?? null,
      pageSize: secondRequest.page_size ?? null,
      candidateId: secondData?.candidateId ?? null,
      factoryId: secondData?.factoryId ?? null,
      source: secondData?.source ?? null,
      period: secondData?.period ?? null,
    },
    note: 'Rows are counted in response order; duplicate business rows are not content-deduplicated. Completeness remains UNVERIFIED pending an independent source comparison.',
  };
}

function axisData(option, axisName) {
  const axis = option?.[axisName];
  const value = Array.isArray(axis) ? axis[0]?.data : axis?.data;
  return Array.isArray(value) ? value : [];
}

function seriesList(option) {
  return Array.isArray(option?.series) ? option.series : [];
}

function echartsAssetUrl(name, origin = '') {
  try {
    const url = new URL(name, origin || 'http://localhost');
    return url.origin === (origin || url.origin) && /^\/assets\/echarts-[A-Za-z0-9_-]+\.js$/.test(url.pathname) ? url.href : null;
  } catch { return null; }
}

function selectEChartsAssetUrls(entries, origin) {
  return (entries || []).map((entry) => echartsAssetUrl(typeof entry === 'string' ? entry : entry?.name, origin)).filter(Boolean).slice(0, 4);
}

function chartPointForCandidate(candidate) {
  if (candidate?.detailContext && candidate.detailContext.drilldown === false) return null;
  const option = candidate?.chart?.echartsOption;
  if (!option || typeof option !== 'object') return null;
  const series = seriesList(option);
  const type = String(candidate?.chart?.chartType || '').toLowerCase();
  const pie = series.find((item) => String(item?.type || '').toLowerCase() === 'pie')
    || ((type === 'pie' || type === 'donut' || type === 'nested_donut') ? series[0] : null);
  if (pie && Array.isArray(pie.data)) {
    const index = pie.data.findIndex((value) => value && typeof value === 'object' && String(value.name || '').trim());
    const dimension = pie.encode?.itemName || option.dataset?.dimensions?.[0] || null;
    if (index >= 0) {
      const seriesIndex = series.indexOf(pie);
      return { name: String(pie.data[index].name).trim(), seriesName: pie.name, dataIndex: index, dimension, kind: 'pie', ...(seriesIndex > 0 ? { seriesIndex } : {}) };
    }
  }
  const bar = series.find((item) => String(item?.type || '').toLowerCase() === 'bar');
  if (bar) {
    const horizontal = type === 'bar_horizontal' || (axisData(option, 'xAxis').length === 0 && axisData(option, 'yAxis').length > 0);
    const categories = horizontal ? axisData(option, 'yAxis') : axisData(option, 'xAxis');
    const dataPoint = Array.isArray(bar.data) ? bar.data.find((value) => value && typeof value === 'object' && String(value.name || '').trim()) : null;
    const dataIndex = dataPoint ? bar.data.indexOf(dataPoint) : 0;
    const name = dataPoint?.name ?? categories[dataIndex];
    const axis = horizontal ? option.yAxis : option.xAxis;
    const dimension = (Array.isArray(axis) ? axis[0]?.name : axis?.name) || null;
    if (name != null && String(name).trim()) {
      const seriesIndex = series.indexOf(bar);
      return { name: String(name).trim(), seriesName: bar.name, dataIndex, dimension, kind: horizontal ? 'bar_horizontal' : 'bar', ...(seriesIndex > 0 ? { seriesIndex } : {}) };
    }
  }
  return null;
}

function numericDataValue(candidate, point) {
  const series = seriesList(candidate?.chart?.echartsOption);
  const item = series.find((entry) => String(entry?.name || '') === String(point?.seriesName || '')) || series[0];
  const value = Array.isArray(item?.data) ? item.data[point?.dataIndex ?? 0] : null;
  const raw = value && typeof value === 'object' ? value.value : value;
  return Number.isFinite(Number(raw)) ? Math.max(0, Number(raw)) : 1;
}

function pointCandidateCategoryCount(candidate, point) {
  const option = candidate?.chart?.echartsOption || {};
  if (point?.kind === 'pie') {
    const series = seriesList(option).find((item) => String(item?.name || '') === String(point.seriesName || '')) || seriesList(option)[point?.seriesIndex || 0];
    return Array.isArray(series?.data) ? series.data.length : Number.MAX_SAFE_INTEGER;
  }
  return point?.kind === 'bar_horizontal' ? axisData(option, 'yAxis').length : axisData(option, 'xAxis').length;
}

function selectPointCandidate(mountedCandidates) {
  return (mountedCandidates || [])
    .map((entry) => ({ entry, point: chartPointForCandidate(entry?.candidate) }))
    .filter(({ point }) => point)
    .sort((a, b) => {
      const aCartesian = a.point.kind === 'bar' || a.point.kind === 'bar_horizontal' ? 1 : 0;
      const bCartesian = b.point.kind === 'bar' || b.point.kind === 'bar_horizontal' ? 1 : 0;
      const aNonZero = numericDataValue(a.entry.candidate, a.point) > 0 ? 1 : 0;
      const bNonZero = numericDataValue(b.entry.candidate, b.point) > 0 ? 1 : 0;
      return bCartesian - aCartesian
        || bNonZero - aNonZero
        || pointCandidateCategoryCount(a.entry.candidate, a.point) - pointCandidateCategoryCount(b.entry.candidate, b.point);
    })[0]?.entry || null;
}

function resolveOptionInset(value, total, fallback) {
  if (typeof value === 'number' && Number.isFinite(value)) return value;
  if (typeof value === 'string' && /%$/.test(value)) {
    const percent = Number.parseFloat(value);
    if (Number.isFinite(percent)) return total * percent / 100;
  }
  return fallback;
}

function optionGridRect(option, width, height) {
  const grid = Array.isArray(option?.grid) ? option.grid[0] || {} : option?.grid || {};
  const left = resolveOptionInset(grid.left, width, width * 0.08);
  const right = resolveOptionInset(grid.right, width, width * 0.08);
  const top = resolveOptionInset(grid.top, height, 45);
  const bottom = resolveOptionInset(grid.bottom, height, 65);
  return {
    left: Math.max(0, Math.min(width, left)),
    right: Math.max(0, Math.min(width, width - right)),
    top: Math.max(0, Math.min(height, top)),
    bottom: Math.max(0, Math.min(height, height - bottom)),
  };
}

function axisAt(option, axisName, index = 0) {
  const axis = option?.[axisName];
  return Array.isArray(axis) ? axis[index] || {} : axis || {};
}

function numericValue(value) {
  const raw = Array.isArray(value) ? value[value.length - 1] : value;
  const number = Number(raw && typeof raw === 'object' ? raw.value : raw);
  return Number.isFinite(number) ? number : 0;
}

function valueAxisBounds(axis, values) {
  const finite = values.map(numericValue).filter(Number.isFinite);
  const observedMax = finite.length ? Math.max(...finite) : 1;
  const observedMin = finite.length ? Math.min(...finite) : 0;
  let min = Number.isFinite(Number(axis?.min)) ? Number(axis.min) : Math.min(0, observedMin);
  let max = Number.isFinite(Number(axis?.max)) ? Number(axis.max) : Math.max(1, observedMax);
  if (max <= min) max = min + 1;
  return { min, max };
}

function chartPointCoordinate(box, candidate, point) {
  const width = Number(box?.width || 0);
  const height = Number(box?.height || 0);
  const left = Number(box?.x || 0);
  const top = Number(box?.y || 0);
  if (width <= 0 || height <= 0 || !point) return null;
  const option = candidate?.chart?.echartsOption || {};
  const series = seriesList(option);
  const selected = series.find((item) => String(item?.name || '') === String(point.seriesName || ''))
    || series.find((item) => String(item?.type || '').toLowerCase() === (point.kind === 'pie' ? 'pie' : 'bar'))
    || series[0];
  if (!selected) return null;

  if (point.kind === 'pie') {
    const center = Array.isArray(selected.center) ? selected.center : ['50%', '50%'];
    const centerX = left + resolveOptionInset(center[0], width, width / 2);
    const centerY = top + resolveOptionInset(center[1], height, height / 2);
    const radius = Array.isArray(selected.radius) ? selected.radius : [0, selected.radius || '70%'];
    const radiusBase = Math.min(width, height) / 2;
    const inner = resolveOptionInset(radius[0], radiusBase, 0);
    const outer = resolveOptionInset(radius[1], radiusBase, radiusBase * 0.7);
    const values = Array.isArray(selected.data) ? selected.data.map(numericValue) : [];
    const total = values.reduce((sum, value) => sum + Math.max(0, value), 0) || values.length || 1;
    const before = values.slice(0, point.dataIndex).reduce((sum, value) => sum + Math.max(0, value), 0);
    const value = Math.max(0, values[point.dataIndex] || numericDataValue(candidate, point));
    const startAngle = Number.isFinite(Number(selected.startAngle)) ? Number(selected.startAngle) : 90;
    const angle = -Math.PI / 2 + ((90 - startAngle) * Math.PI / 180) + ((before + value / 2) / total) * Math.PI * 2;
    const radiusMid = (inner + outer) / 2;
    return { x: centerX + Math.cos(angle) * radiusMid, y: centerY + Math.sin(angle) * radiusMid };
  }

  const horizontal = point.kind === 'bar_horizontal';
  const categories = horizontal ? axisData(option, 'yAxis') : axisData(option, 'xAxis');
  const index = Math.max(0, Number.isInteger(point.dataIndex) ? point.dataIndex : 0);
  const count = Math.max(1, categories.length || (Array.isArray(selected.data) ? selected.data.length : 0));
  const grid = optionGridRect(option, width, height);
  const gridWidth = Math.max(1, grid.right - grid.left);
  const gridHeight = Math.max(1, grid.bottom - grid.top);
  const values = Array.isArray(selected.data) ? selected.data : [];
  const value = numericValue(values[index]);
  const axis = axisAt(option, horizontal ? 'xAxis' : 'yAxis');
  const bounds = valueAxisBounds(axis, values);
  const baseline = (0 - bounds.min) / (bounds.max - bounds.min);
  const endpoint = (value - bounds.min) / (bounds.max - bounds.min);
  if (horizontal) {
    const categoryAxis = axisAt(option, 'yAxis');
    const physicalIndex = categoryAxis.inverse === true ? index : count - index - 1;
    const y = grid.top + gridHeight * ((physicalIndex + 0.5) / count);
    const x = left + grid.left + gridWidth * ((baseline + endpoint) / 2);
    return { x, y: top + y };
  }
  const categoryAxis = axisAt(option, 'xAxis');
  const physicalIndex = categoryAxis.inverse === true ? count - index - 1 : index;
  const x = grid.left + gridWidth * ((physicalIndex + 0.5) / count);
  const y = grid.bottom - gridHeight * ((baseline + endpoint) / 2);
  return { x: left + x, y: top + y };
}

function clickAttempts(box, candidate, point) {
  const width = Number(box?.width || 0);
  const height = Number(box?.height || 0);
  const left = Number(box?.x || 0);
  const top = Number(box?.y || 0);
  if (width <= 0 || height <= 0 || !point) return [];
  const primary = chartPointCoordinate(box, candidate, point);
  if (!primary) return [];
  const offsets = [{ x: 0, y: 0 }, { x: -6, y: 0 }, { x: 6, y: 0 }, { x: 0, y: -6 }, { x: 0, y: 6 }, { x: -6, y: -6 }, { x: 6, y: 6 }];
  return offsets.slice(0, MAX_CANVAS_CLICK_ATTEMPTS).map(({ x, y }) => ({
    x: Math.max(left + 1, Math.min(left + width - 1, primary.x + x)),
    y: Math.max(top + 1, Math.min(top + height - 1, primary.y + y)),
  }));
}

async function mountedEChartsPointCoordinate(chartHost, point, value) {
  if (!chartHost || !await chartHost.count()) return null;
  const resourceState = await chartHost.evaluate(() => ({
    origin: window.location.origin,
    resources: performance.getEntriesByType('resource').map((entry) => entry.name),
  })).catch(() => null);
  if (!resourceState?.origin) return null;
  const resources = selectEChartsAssetUrls(resourceState.resources, resourceState.origin);
  if (!resources.length) return null;
  return chartHost.evaluate(async (element, target) => {
    for (const resource of target.resources) {
      try {
        const url = new URL(resource, window.location.href);
        if (url.origin !== window.location.origin || !/^\/assets\/echarts-[A-Za-z0-9_-]+\.js$/.test(url.pathname)) continue;
        const module = await import(resource);
        const exports = [module, module.default, ...Object.values(module)];
        const holder = exports.find((candidate) => candidate && typeof candidate.getInstanceByDom === 'function');
        const instance = holder?.getInstanceByDom(element);
        if (!instance || typeof instance.convertToPixel !== 'function') continue;
        const axisValue = Number.isFinite(Number(target.value)) ? Math.max(0, Number(target.value) / 2) : 0;
        const dataValue = target.kind === 'bar_horizontal' ? [axisValue, target.name] : [target.name, axisValue];
        const pixel = instance.convertToPixel({ seriesIndex: target.seriesIndex ?? 0 }, dataValue);
        if (Array.isArray(pixel) && pixel.length >= 2 && Number.isFinite(Number(pixel[0])) && Number.isFinite(Number(pixel[1]))) {
          return { x: Number(pixel[0]), y: Number(pixel[1]), source: 'mounted-echarts-convertToPixel' };
        }
      } catch { /* bounded first-party module probing; geometry remains diagnostic only */ }
    }
    return null;
  }, { kind: point.kind, name: point.name, value, seriesIndex: point.seriesIndex ?? 0, resources });
}

function estimateVirtualRowCount({ contentHeight, rowHeight } = {}) {
  if (!Number.isFinite(contentHeight) || !Number.isFinite(rowHeight) || contentHeight <= 0 || rowHeight <= 0) return null;
  const ratio = contentHeight / rowHeight;
  return Number.isInteger(ratio) ? ratio : null;
}

function availablePointAttempts(now, deadline) {
  return Math.max(0, Math.floor(Math.max(0, deadline - now) / 3_500));
}

function summarizeCandidateCheck(candidateId, result) {
  const checks = result?.checks || {};
  const ui = result?.ui || {};
  return {
    candidateId,
    result: result?.result || 'UNVERIFIED',
    rootCauseClass: result?.rootCauseClass || null,
    total: result?.total ?? null,
    rowCount: result?.rowCount ?? null,
    failedChecks: Object.keys(checks).filter((key) => checks[key] === false),
    drawerVisible: ui.drawerVisible === true,
    virtualTableVisible: ui.tableVisible === true,
    virtualTableRowCount: ui.rowCountMatches === true,
    displayedValues: ui.displayedValues === true,
    concreteValueVisible: ui.concreteValueVisible === true,
    reason: typeof result?.reason === 'string' ? result.reason : null,
  };
}

function assessmentFailureReason(assessment) {
  const failedChecks = Object.keys(assessment?.checks || {}).filter((key) => assessment.checks[key] === false);
  return failedChecks.join(',') || assessment?.reason || assessment?.ui?.valueEvidenceReason || 'point evidence did not pass';
}

function unverified(reason, extra = {}) {
  return { result: 'UNVERIFIED', rootCauseClass: 'data', reason, ...extra };
}

async function cardForCandidate(page, candidate) {
  const cards = page.locator('.recommendation-card');
  const wanted = `factory-chart-card-${candidate.id}`;
  for (let index = 0; index < await cards.count(); index += 1) {
    if (await cards.nth(index).getAttribute('data-testid') === wanted) return cards.nth(index);
  }
  return null;
}

async function closeDetailsDrawer(page) {
  const drawer = page.locator('.el-drawer:visible').first();
  if (!await drawer.count()) return;
  const closeButton = drawer.locator('button.el-drawer__close-btn').first();
  if (await closeButton.count()) await closeButton.click().catch(() => {});
  else await page.keyboard.press('Escape').catch(() => {});
  await drawer.waitFor({ state: 'hidden', timeout: 5_000 }).catch(() => {});
}

function displayDetailCell(value) {
  return value === null || value === undefined ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value);
}

function readVirtualTableMetrics(element) {
  const body = element.querySelector('.el-table-v2__main > .el-vl__wrapper.el-table-v2__body')
    || element.querySelector('.el-table-v2__main .el-vl__wrapper.el-table-v2__body')
    || element.querySelector('.el-table-v2__body-wrapper');
  const viewport = body?.firstElementChild || body;
  const content = viewport?.firstElementChild || viewport;
  const row = element.querySelector('.el-table-v2__row');
  return {
    contentHeight: content ? Math.max(content.getBoundingClientRect().height || 0, content.scrollHeight || 0) : 0,
    viewportHeight: viewport?.getBoundingClientRect().height || body?.getBoundingClientRect().height || 0,
    rowHeight: row?.getBoundingClientRect().height || 0,
    visibleCellTexts: Array.from(row?.querySelectorAll('.el-table-v2__cell-text, .el-table-v2__row-cell') || [])
      .map((cell) => String(cell.textContent || '').trim()),
  };
}

function detailCellEvidence(expectedData, visibleCellTexts) {
  const expectedRow = Array.isArray(expectedData?.rows) ? expectedData.rows[0] : null;
  const expectedColumns = Array.isArray(expectedData?.columns) ? expectedData.columns : [];
  const expectedCells = expectedRow && expectedColumns.length
    ? expectedColumns.map((column) => displayDetailCell(expectedRow?.[column]))
    : [];
  const visible = Array.isArray(visibleCellTexts) ? visibleCellTexts : [];
  const displayedValues = expectedCells.length > 0 && expectedCells.every((expectedCell) => visible.includes(expectedCell));
  const concreteValueVisible = expectedCells.some((expectedCell) => expectedCell !== '—')
    && expectedCells.some((expectedCell) => expectedCell !== '—' && visible.includes(expectedCell));
  return {
    displayedValues,
    concreteValueVisible,
    valueEvidenceReason: displayedValues
      ? 'first mounted virtual row displays the API row values'
      : 'mounted virtual row cells did not display every expected first-row value',
  };
}

async function inspectDetailsTableOnce(page, expectedRows, expectedData = null, sampleTimeoutMs = 250) {
  const drawer = page.locator('.el-drawer:visible').first();
  const drawerVisible = await drawer.count() > 0 && await drawer.isVisible().catch(() => false);
  const table = drawer.locator('.el-table-v2').first();
  if (drawerVisible && await table.count()) await table.waitFor({ state: 'visible', timeout: Math.max(1, sampleTimeoutMs) }).catch(() => {});
  const tableVisible = drawerVisible && await table.count() > 0 && await table.isVisible().catch(() => false);
  if (!tableVisible) return {
    drawerVisible, tableVisible: false, renderedRowCount: 0, rowCountMatches: false,
    displayedValues: false, concreteValueVisible: false, valueEvidenceReason: 'mounted virtual table is not visible',
  };
  const rows = table.locator('.el-table-v2__row');
  const renderedRowCount = await rows.count();
  const ariaRowCount = await table.getAttribute('aria-rowcount').catch(() => null);
  const metrics = await table.evaluate(readVirtualTableMetrics)
    .catch(() => ({ contentHeight: 0, viewportHeight: 0, rowHeight: 0, visibleCellTexts: [] }));
  const estimatedRowCount = estimateVirtualRowCount(metrics);
  const rowCountMatches = expectedRows > 0 && (
    renderedRowCount === expectedRows
    || ariaRowCount === String(expectedRows)
    || estimatedRowCount === expectedRows
  );
  const evidence = detailCellEvidence(expectedData, metrics.visibleCellTexts);
  return {
    drawerVisible,
    tableVisible,
    renderedRowCount,
    ariaRowCount,
    estimatedRowCount,
    contentHeight: metrics.contentHeight,
    viewportHeight: metrics.viewportHeight,
    rowHeight: metrics.rowHeight,
    rowCountMatches,
    ...evidence,
  };
}

function mountedTableEvidenceComplete(result) {
  return result?.drawerVisible === true
    && result?.tableVisible === true
    && result?.rowCountMatches === true
    && result?.displayedValues === true
    && result?.concreteValueVisible === true;
}

async function retryMountedTableEvidence(sample, { timeoutMs = 5_000, sleep = async () => {} } = {}) {
  const deadline = Date.now() + Math.max(1, timeoutMs);
  let attempts = 0;
  let latest = null;
  while (Date.now() <= deadline) {
    attempts += 1;
    latest = await sample(Math.max(1, deadline - Date.now()));
    if (mountedTableEvidenceComplete(latest)) return { ...latest, sampleAttempts: attempts };
    if (Date.now() >= deadline) break;
    await sleep(Math.min(100, Math.max(1, deadline - Date.now())));
  }
  return { ...(latest || {}), sampleAttempts: attempts };
}

async function inspectDetailsTable(page, expectedRows, expectedData = null, timeoutMs = 5_000) {
  return retryMountedTableEvidence(
    (remainingMs) => inspectDetailsTableOnce(page, expectedRows, expectedData, Math.min(1_000, remainingMs)),
    { timeoutMs, sleep: (delay) => page.waitForTimeout(delay) },
  );
}

async function captureBasicDetails(page, card, candidate, expected, deadline = Date.now() + DETAIL_RESPONSE_TIMEOUT_MS) {
  const button = card?.locator('button.details-button').first();
  if (!button || !await button.count()) return unverified('mounted card has no 查看原始明细 button');
  const stepTimeout = () => Math.max(1, Math.min(DETAIL_RESPONSE_TIMEOUT_MS, deadline - Date.now()));
  if (deadline <= Date.now()) return unverified('detail acceptance deadline reached before opening the mounted details button', { candidateId: candidate.id });
  const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: stepTimeout() });
  try { await button.click({ timeout: stepTimeout() }); } catch { return unverified('查看原始明细 button could not be clicked', { candidateId: candidate.id }); }
  let response;
  try { response = await responsePromise; } catch { return unverified('查看原始明细 click produced no factory-details response within the bounded budget', { candidateId: candidate.id }); }
  const body = await response.json().catch(() => null);
  await page.locator('.el-drawer:visible').first().waitFor({ timeout: Math.max(1, Math.min(5_000, deadline - Date.now())) }).catch(() => {});
  const request = requestJson(response.request());
  const assessment = assessDetailsResponse({ status: response.status(), request, body, expected });
  const ui = await inspectDetailsTable(page, assessment.rowCount, body?.data, Math.max(1, Math.min(5_000, deadline - Date.now())));
  assessment.ui = ui;
  assessment.checks.drawerVisible = ui.drawerVisible;
  assessment.checks.virtualTableVisible = ui.tableVisible;
  assessment.checks.virtualTableRowCount = ui.rowCountMatches;
  assessment.checks.displayedValues = ui.displayedValues === true;
  assessment.checks.concreteValueVisible = ui.concreteValueVisible === true;
  if (assessment.result === 'PASS' && (!ui.drawerVisible || !ui.tableVisible || !ui.rowCountMatches || !ui.displayedValues || !ui.concreteValueVisible)) {
    assessment.result = 'UNVERIFIED';
    assessment.rootCauseClass = 'tool';
    assessment.reason = 'API details passed but mounted drawer, virtual-table row count, or concrete cell values could not be proven';
  }
  // Keep the exact first response only in memory for the page-boundary check;
  // non-enumerable fields cannot enter the sanitized evidence JSON.
  Object.defineProperty(assessment, '_rawBody', { value: body, enumerable: false });
  Object.defineProperty(assessment, '_status', { value: response.status(), enumerable: false });
  assessment.candidateId = candidate.id;
  assessment.interaction = 'mounted-card-view-details';
  return assessment;
}

async function capturePointDetails(page, candidate, expected, deadline = Date.now() + 30_000) {
  const point = chartPointForCandidate(candidate);
  const card = await cardForCandidate(page, candidate);
  const canvas = card?.locator('.factory-chart-canvas canvas').first();
  const chartHost = card?.locator('.factory-chart-canvas').first();
  if (!point) return unverified('no eligible visible pie/bar point in the recommendation echartsOption', { candidateId: candidate.id });
  if (!canvas || !await canvas.count()) return unverified('eligible pie/bar candidate has no mounted canvas', { candidateId: candidate.id, point });
  await canvas.waitFor({ state: 'visible', timeout: Math.max(1_000, Math.min(5_000, deadline - Date.now())) }).catch(() => {});
  await closeDetailsDrawer(page);
  await canvas.scrollIntoViewIfNeeded().catch(() => {});
  const box = await canvas.boundingBox();
  const mountedPoint = await mountedEChartsPointCoordinate(chartHost, point, numericDataValue(candidate, point));
  const availableAttempts = availablePointAttempts(Date.now(), deadline);
  const attempts = (mountedPoint
    ? clickAttempts(box, candidate, point).map((coordinate, index) => index === 0
      ? { x: (box?.x || 0) + mountedPoint.x, y: (box?.y || 0) + mountedPoint.y }
      : coordinate)
    : clickAttempts(box, candidate, point)).slice(0, Math.min(MAX_CANVAS_CLICK_ATTEMPTS, availableAttempts));
  if (!attempts.length) return unverified('mounted canvas has no usable click coordinates', { candidateId: candidate.id, point });
  const attemptEvidence = [];
  for (const [index, coordinate] of attempts.entries()) {
    if (Date.now() >= deadline) break;
    const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: Math.max(1_000, Math.min(3_500, deadline - Date.now())) }).catch(() => null);
    await page.mouse.click(coordinate.x, coordinate.y);
    const response = await responsePromise;
    if (!response) { attemptEvidence.push({ index, response: false, result: 'UNVERIFIED', reason: 'no factory-details response within the bounded click wait' }); continue; }
    const body = await response.json().catch(() => null);
    const request = requestJson(response.request());
    const assessment = assessDetailsResponse({ status: response.status(), request, body, expected, point });
    const ui = await inspectDetailsTable(page, assessment.rowCount, body?.data, Math.max(1_000, Math.min(5_000, deadline - Date.now())));
    assessment.ui = ui;
    assessment.checks.drawerVisible = ui.drawerVisible;
    assessment.checks.virtualTableVisible = ui.tableVisible;
    assessment.checks.virtualTableRowCount = ui.rowCountMatches;
    assessment.checks.displayedValues = ui.displayedValues === true;
    assessment.checks.concreteValueVisible = ui.concreteValueVisible === true;
    if (assessment.result === 'PASS' && (!mountedPoint || !ui.drawerVisible || !ui.tableVisible || !ui.rowCountMatches || !ui.displayedValues || !ui.concreteValueVisible)) {
      assessment.result = 'UNVERIFIED';
      assessment.rootCauseClass = 'tool';
      assessment.reason = mountedPoint
        ? 'Point-filtered API response passed but mounted drawer, virtual-table row count, or concrete cell evidence was incomplete'
        : 'Point-filtered API response passed after geometry fallback; a mounted ECharts convertToPixel coordinate was unavailable';
    }
    attemptEvidence.push({
      index,
      response: true,
      status: response.status(),
      result: assessment.result,
      rowCount: assessment.rowCount,
      total: assessment.total,
      reason: assessmentFailureReason(assessment),
    });
    if (assessment.result === 'PASS') {
      await page.locator('.el-drawer:visible').first().waitFor({ timeout: 10_000 }).catch(() => {});
      return { ...assessment, candidateId: candidate.id, interaction: 'mounted-canvas-mouse-click', attempts: attemptEvidence };
    }
    await closeDetailsDrawer(page);
  }
  return unverified('bounded real canvas clicks did not produce a valid point-filtered details response', { candidateId: candidate.id, point, attempts: attemptEvidence });
}

module.exports = {
  id: 'smartbi-factory-readonly',
  defaultEnabled: false,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-readonly',
    path: '/smart-bi/analysis-hub?tab=factory',
    landmarks: ['工厂图表推荐', '数据源'],
    inspect: async (page, _body, context) => {
      if (context.expectedFactoryId !== 'F006') throw new Error('SmartBI acceptance is limited to F006');
      await page.getByTestId('factory-chart-ranking').waitFor({ timeout: 60_000 });
      const pending = page.waitForResponse((response) => endpointPath(response.url()).replace(/\/$/, '') === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations' && response.request().method() === 'POST', { timeout: 60_000 });
      await page.getByTestId('factory-chart-refresh').click();
      const recommendationResponse = await pending;
      const recommendationBody = await recommendationResponse.json();
      const data = recommendationBody.data;
      await page.getByTestId('factory-chart-ranking').waitFor({ timeout: 60_000 });
      await page.locator('.recommendation-card canvas').first().waitFor({ timeout: 30_000 }).catch(() => {});
      const cards = await page.locator('.recommendation-card').count();
      const drawingErrors = await page.locator('.recommendation-card [role="alert"]').count();
      const scope = recommendationResponse.status() === 200 && recommendationBody.success === true && data?.factoryId === 'F006' && data?.source === 'business';
      const recommendations = Array.isArray(data?.recommendations) ? data.recommendations : [];
      const factsMatch = await page.locator('.recommendation-card').evaluateAll((elements, items) => elements.every((el) => {
        const testId = el.getAttribute('data-testid') || '';
        const id = testId.slice('factory-chart-card-'.length);
        const item = items.find((candidate) => candidate.id === id);
        const text = (el.innerText || '').replace(/\s/g, '');
        return item && Array.isArray(item.kpis) && item.kpis.every((kpi) => kpi.value === null || text.includes(String(kpi.value).replace(/\s/g, '')));
      }), recommendations);
      const summary = {
        scope,
        cards,
        drawingErrors,
        factsMatch,
        candidateCount: recommendations.length,
        sourceErrorCount: Array.isArray(data?.sourceErrors) ? data.sourceErrors.length : 0,
        rankingMode: data?.ranking?.mode,
      };
      const mountedDetailCandidates = [];
      for (const candidate of recommendations.filter((item) => item?.detailContext?.id)) {
        const card = await cardForCandidate(page, candidate);
        const canvas = card?.locator('.factory-chart-canvas canvas').first();
        if (card && canvas && await canvas.count()) mountedDetailCandidates.push({ candidate, card });
      }
      let basic = unverified('no recommendation with detailContext is mounted with a real details button');
      let pagination = unverified('basic details interaction was not verified');
      let point = unverified('no mounted point candidate was available');
      let selectedCandidate = null;
      let selectedExpected = null;
      const candidateChecks = [];
      const detailDeadline = Date.now() + DETAIL_ACCEPTANCE_DEADLINE_MS;
      const nonPointDeadline = detailDeadline - POINT_CLICK_BUDGET_MS;
      for (const candidateRef of mountedDetailCandidates.slice(0, MAX_DETAIL_CANDIDATES)) {
        if (Date.now() >= nonPointDeadline) break;
        await closeDetailsDrawer(page);
        const expected = {
          factoryId: 'F006', candidateId: candidateRef.candidate.id, source: candidateRef.candidate.source,
          period: data?.period?.label, contextId: candidateRef.candidate.detailContext.id,
        };
        const candidateBasic = await captureBasicDetails(page, candidateRef.card, candidateRef.candidate, expected, nonPointDeadline);
        candidateChecks.push(summarizeCandidateCheck(candidateRef.candidate.id, candidateBasic));
        if (candidateBasic.result === 'PASS' && (!selectedCandidate || (candidateBasic.total || 0) > (basic.total || 0))) {
          selectedCandidate = candidateRef;
          selectedExpected = expected;
          basic = candidateBasic;
        }
        if (candidateBasic.result === 'PASS' && candidateBasic.total >= DETAIL_PAGE_SIZE + 1) break;
      }
      if (selectedCandidate && basic.result === 'PASS') {
        const total = basic.total;
        if (total >= DETAIL_PAGE_SIZE + 1) {
          if (Date.now() >= nonPointDeadline) {
            pagination = unverified('details pagination could not start while preserving the reserved mounted point-click budget', { total, reservedPointClickMs: POINT_CLICK_BUDGET_MS });
          } else {
          const drawer = page.locator('.el-drawer:visible').first();
          const loadMore = drawer.getByRole('button', { name: '加载更多' }).first();
          const remaining = Math.max(1_000, Math.min(DETAIL_RESPONSE_TIMEOUT_MS, nonPointDeadline - Date.now()));
          if (await loadMore.count()) {
            const responsePromise = page.waitForResponse(isDetailsResponse, { timeout: remaining });
            await loadMore.click({ timeout: remaining });
            const response = await responsePromise;
            const body = await response.json();
            const request = requestJson(response.request());
            const pageTwo = { status: response.status(), request, body };
            // Keep raw page bodies in the Node page object for root's independent
            // SQL/source comparison; never put them in the sanitized report.
            page.__smartbiDetailAcceptance = {
              contextId: selectedExpected.contextId,
              candidateId: selectedExpected.candidateId,
              first: { status: basic._status || 200, body: basic._rawBody },
              second: { status: response.status(), body },
            };
            const firstPage = { status: basic._status || 200, request: { context_id: selectedExpected.contextId, page: 1, page_size: DETAIL_PAGE_SIZE }, body: basic._rawBody };
            pagination = assessPageContinuity(firstPage, pageTwo, selectedExpected);
            pagination.request = { contextIdMatches: request?.context_id === selectedExpected.contextId, page: request?.page ?? null, pageSize: request?.page_size ?? null };
            if (pagination.result === 'PASS') pagination.rootCauseClass = 'none';
          } else pagination = unverified('details response has >=51 rows but mounted drawer has no 加载更多 control', { total });
          }
        } else pagination = unverified('details total is below the 51-row pagination qualification threshold', { total, requiredTotal: DETAIL_PAGE_SIZE + 1 });
      }
      const pointCandidate = selectPointCandidate(mountedDetailCandidates.slice(0, MAX_DETAIL_CANDIDATES));
      if (pointCandidate && Date.now() < detailDeadline) {
        const pointExpected = {
          factoryId: 'F006', candidateId: pointCandidate.candidate.id, source: pointCandidate.candidate.source,
          period: data?.period?.label, contextId: pointCandidate.candidate.detailContext.id,
        };
        point = await capturePointDetails(page, pointCandidate.candidate, pointExpected, Math.min(detailDeadline, Date.now() + POINT_CLICK_BUDGET_MS));
      } else if (!pointCandidate) point = unverified('no mounted candidate exposes a visible pie/bar point target');
      else point = unverified('detail acceptance deadline reached before bounded canvas point probing');
      const summaryPass = scope && cards > 0 && factsMatch && drawingErrors === 0 && summary.sourceErrorCount === 0;
      const deepPass = summaryPass && basic.result === 'PASS' && pagination.result === 'PASS' && pagination.completeness === 'PASS' && point.result === 'PASS';
      const assessment = deepPass
        ? { result: 'PASS', rootCauseClass: 'none' }
        : basic.result === 'CONFIRMED_DEFECT' || pagination.result === 'CONFIRMED_DEFECT' || point.result === 'CONFIRMED_DEFECT'
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'backend' }
          : { result: summaryPass ? 'UNVERIFIED' : 'CONFIRMED_DEFECT', rootCauseClass: summaryPass ? 'data' : 'frontend' };
      return {
        ...summary,
        detailBasicResult: String(basic.result || 'UNVERIFIED'),
        detailBasicReason: String(basic.reason || basic.ui?.valueEvidenceReason || 'none'),
        detailPointResult: String(point.result || 'UNVERIFIED'),
        detailPointReason: String(point.reason || point.ui?.valueEvidenceReason || 'none'),
        detailPointAttempts: Array.isArray(point.attempts)
          ? point.attempts.map((attempt) => `#${attempt.index}:${attempt.result}:${attempt.reason || 'unknown'}`).join('|')
          : 'none',
        candidateSummary: candidateChecks.map((item) => `${item.candidateId}:${item.result}:cells=${item.displayedValues ? 'visible' : 'missing'}:concrete=${item.concreteValueVisible ? 'yes' : 'no'}`).join('|') || 'none',
        details: { basic, pagination, point, candidateId: selectedCandidate?.candidate.id || null, candidateChecks },
        screenshots: [await context.screenshot('smartbi-factory-business'), await context.screenshot('smartbi-factory-details')],
        assessment,
      };
    },
  }),
};

module.exports.assessDetailsResponse = assessDetailsResponse;
module.exports.assessPageContinuity = assessPageContinuity;
module.exports.chartPointForCandidate = chartPointForCandidate;
module.exports.chartPointCoordinate = chartPointCoordinate;
module.exports.clickAttempts = clickAttempts;
module.exports.echartsAssetUrl = echartsAssetUrl;
module.exports.selectEChartsAssetUrls = selectEChartsAssetUrls;
module.exports.mountedEChartsPointCoordinate = mountedEChartsPointCoordinate;
module.exports.readVirtualTableMetrics = readVirtualTableMetrics;
module.exports.detailCellEvidence = detailCellEvidence;
module.exports.mountedTableEvidenceComplete = mountedTableEvidenceComplete;
module.exports.retryMountedTableEvidence = retryMountedTableEvidence;
module.exports.inspectDetailsTable = inspectDetailsTable;
module.exports.selectPointCandidate = selectPointCandidate;
module.exports.estimateVirtualRowCount = estimateVirtualRowCount;
module.exports.availablePointAttempts = availablePointAttempts;
module.exports.POINT_CLICK_BUDGET_MS = POINT_CLICK_BUDGET_MS;

},
    "scenarios/smartbi-guided-readonly.js": function(module, exports, require) {
'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const PATH = '/smart-bi/analysis-hub?tab=factory';
const RECOMMENDATIONS_PATH = '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
const MAX_CARDS = 4;

function endpointPath(url) { try { return new URL(url).pathname; } catch { return String(url || '').split('?')[0]; } }
function requestBody(response) { try { return response.request().postDataJSON() || {}; } catch { return {}; } }
function responseIsRecommendations(response) {
  return response.request().method() === 'POST' && endpointPath(response.url()).replace(/\/$/, '') === RECOMMENDATIONS_PATH;
}
async function responseFor(page, action) {
  const pending = page.waitForResponse(responseIsRecommendations, { timeout: 60_000 });
  await action();
  return pending;
}
function safeId(value) { return typeof value === 'string' || typeof value === 'number' ? String(value) : null; }
function candidates(data) { return Array.isArray(data?.recommendations) ? data.recommendations : []; }
async function readVisibleCards(page, candidateMap) {
  const cards = page.locator('[data-testid^="factory-chart-card-"]:visible');
  const values = [];
  for (let i = 0; i < await cards.count(); i += 1) {
    const id = (await cards.nth(i).getAttribute('data-testid')).replace('factory-chart-card-', '');
    const item = candidateMap.get(id);
    values.push({ id: safeId(id), familyId: safeId(item?.analysis?.familyId), domain: item?.domain || null });
  }
  return { count: values.length, values };
}
async function assess({ initial, product, baseline, initialVisible, categoryVisible, categoryDomain, familyRows, beforeGuide, categoryShot, productShot, questionnaireVisible, productVisible, productDomainRetained }) {
  const initialCandidates = candidates(initial?.body?.data);
  const initialFamilies = new Set(initialVisible.values.map(item => item.familyId).filter(Boolean));
  const productId = safeId(product?.request?.analysis_subject);
  const checks = {
    questionnaireVisible: questionnaireVisible === true,
    initialHttp200: initial?.status === 200, initialSuccess: initial?.body?.success === true,
    factoryF006: initial?.body?.data?.factoryId === 'F006', businessSource: initial?.body?.data?.source === 'business',
    guidedRequest: initial?.request?.guided === true, guidedScope: typeof initial?.body?.data?.analysisScopeLabel === 'string',
    visibleCardsBounded: initialVisible.count > 0 && initialVisible.count <= MAX_CARDS,
    visibleCardsAreCandidates: initialVisible.values.every(item => initialCandidates.some(candidate => String(candidate.id) === item.id)),
    visibleFamiliesDistinct: initialFamilies.size === initialVisible.count,
    categoryDomainSelected: typeof categoryDomain === 'string' && categoryDomain.length > 0 && categoryDomain !== 'all',
    familyRowsPresent: familyRows > 0,
    categoryCardsMatchDomain: categoryVisible.count > 0 && categoryVisible.values.every(item => item.domain === categoryDomain),
    categoryCardsBounded: categoryVisible.count <= MAX_CARDS,
    productConditional: !product || (product.status === 200 && product.body?.success === true && productId !== null && product.body?.data?.analysisSubject === productId),
    productQuestionRetained: !product || productDomainRetained === true,
    productCardsMatchQuestion: !product || (productVisible && productVisible.count <= MAX_CARDS && productVisible.values.every(item => item.domain === categoryDomain)),
    noSourceErrors: [initial, product, baseline].filter(Boolean).every(item => !Object.keys(item.body?.data?.sourceErrors || {}).length),
    baselineReturnedToFactory: !product || (baseline?.status === 200 && baseline.request?.analysis_subject == null && baseline.body?.data?.analysisSubject == null),
    screenshots: Boolean(beforeGuide && categoryShot && (!product || productShot)),
  };
  const result = Object.values(checks).every(Boolean) ? 'PASS' : 'CONFIRMED_DEFECT';
  return { result, rootCauseClass: result === 'PASS' ? 'none' : 'frontend', checks,
    source: initial?.body?.data?.source || null, period: initial?.body?.data?.period?.label || null,
    scope: initial?.body?.data?.analysisScopeLabel || null, domain: categoryDomain,
    visibleCandidateIds: initialVisible.values.map(item => item.id), visibleFamilies: initialVisible.values.map(item => item.familyId),
    categoryCandidateIds: categoryVisible.values.map(item => item.id), subjectCount: Array.isArray(initial?.body?.data?.subjects) ? initial.body.data.subjects.length : 0,
    productScope: product?.body?.data?.analysisScopeLabel || null };
}

const scenario = {
  id: 'smartbi-guided-readonly', path: PATH, defaultEnabled: false,
  // The questionnaire arrives with the recommendations, after the initial shell.
  // Its visibility remains a required assessment below, after the real wait.
  landmarks: ['工厂分析'], timeoutMs: 60_000,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'smartbi-guided-readonly', path: PATH, landmarks: scenario.landmarks, timeoutMs: scenario.timeoutMs,
    inspect: async (page, body, inspectCtx) => {
      await page.getByTestId('factory-chart-guide').waitFor({ timeout: 60_000 });
      const questionnaireVisible = await page.getByText('1. 想了解什么？', { exact: true }).isVisible();
      const beforeGuide = await inspectCtx.screenshot('smartbi-guided-before-guide');
      const initialResponse = await responseFor(page, () => page.getByTestId('factory-chart-refresh').click());
      const initial = { status: initialResponse.status(), body: await initialResponse.json().catch(() => ({})), request: requestBody(initialResponse) };
      const candidateMap = new Map(candidates(initial.body.data).map(item => [String(item.id), item]));
      await page.getByTestId('factory-chart-guide-generate').click();
      await page.locator('[data-testid^="factory-chart-card-"]:visible').first().waitFor();
      const initialVisible = await readVisibleCards(page, candidateMap);
      const domainGroup = page.getByTestId('factory-chart-guide-domain');
      const domainOption = domainGroup.locator('input[value="库存"], input[value="生产"], input[value="销售"], input[value="采购"]').first();
      if (!await domainOption.count()) throw new Error('factory-chart-guide-domain has no usable domain radio');
      const categoryDomain = await domainOption.getAttribute('value');
      await domainOption.locator('..').click();
      if (!await domainOption.isChecked()) throw new Error('business question did not become selected');
      const generate = page.getByTestId('factory-chart-guide-generate');
      if (await generate.count()) await generate.click();
      await page.waitForTimeout(100);
      const categoryVisible = await readVisibleCards(page, candidateMap);
      const categoryShot = await inspectCtx.screenshot('smartbi-guided-category');
      const browseRadio = page.getByTestId('factory-chart-mode').locator('input[value="browse"]');
      await browseRadio.locator('..').click();
      if (!await browseRadio.isChecked()) throw new Error('classified browse mode did not become selected');
      const familyRows = await page.getByTestId('factory-chart-family').count();
      const subject = page.getByTestId('factory-chart-guide-subject');
      let product = null; let baseline = null; let productShot = null; let productVisible = null; let productDomainRetained = false;
      const firstSubject = initial.body.data?.subjects?.[0];
      if (await subject.count() && firstSubject) {
        await subject.locator('input, button').first().click();
        {
          const selectedOption = page.getByRole('option', { name: firstSubject.label, exact: true });
          const productResponse = await responseFor(page, () => selectedOption.click());
          product = { status: productResponse.status(), body: await productResponse.json().catch(() => ({})), request: requestBody(productResponse) };
          await page.getByText(product.body.data.analysisScopeLabel, { exact: false }).first().waitFor();
          productDomainRetained = await domainGroup.locator(`input[value="${categoryDomain}"]`).isChecked();
          productVisible = await readVisibleCards(page, new Map(candidates(product.body.data).map(item => [String(item.id), item])));
          productShot = await inspectCtx.screenshot('smartbi-guided-product');
          await subject.locator('input, button').first().click();
          const clearOption = page.getByRole('option', { name: '全部产品 / 全厂范围', exact: true });
          if (await clearOption.count()) {
            const baselineResponse = await responseFor(page, () => clearOption.click());
            baseline = { status: baselineResponse.status(), body: await baselineResponse.json().catch(() => ({})), request: requestBody(baselineResponse) };
          }
        }
      }
      const assessment = await assess({ initial, product, baseline, initialVisible, categoryVisible, categoryDomain, familyRows, beforeGuide, categoryShot, productShot, questionnaireVisible, productVisible, productDomainRetained });
      return { assessment, ...assessment, screenshots: [beforeGuide, categoryShot, ...(productShot ? [productShot] : [])] };
    },
  }),
};

module.exports = scenario;
module.exports.assess = assess;
module.exports.responseIsRecommendations = responseIsRecommendations;

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
function summarizeMeasurements(measurements) {
  return {
    measurementCount: measurements.length,
    rowCounts: measurements.map(data => data.rows),
    firstRowHeights: measurements.map(data => data.rowHeight),
    viewportWidths: measurements.filter(data => data.width).map(data => data.width),
    maximumHeaderBodyDifference: Math.max(...measurements.flatMap(data => data.columns.map(column => Math.abs(column.header - column.body)))),
    rightBorders: [...new Set(measurements.flatMap(data => data.columns.map(column => column.right)))],
    bottomBorders: [...new Set(measurements.flatMap(data => data.columns.map(column => column.bottom)))],
    outlines: [...new Set(measurements.map(data => data.outline))],
  };
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
    const measurements = [], screenshots = [], resizedOrderWidths = [];
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
        resizedOrderWidths.push(resizedWidth);
        if (resizedWidth < 120) throw new Error('Order identifier column collapsed below its readable minimum');
        if (delta > 0) enlarged = resizedWidth > initial.width + 20;
      }
      if (!enlarged) throw new Error('Resize gesture did not enlarge the target column');
      screenshots.push(await ctx.screenshot(`${id}-resized`));
    }
    assertSafe(ctx);
    return { ...summarizeMeasurements(measurements), resizedOrderWidths, screenshots, limits: 'Only current populated table; native pointer, persisted width memory and all-page coverage are not verified',
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  };
  return { id, defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id, path, screenshot: true, inspect: page => inspect(page, '', ctx) }) };
}
module.exports = { assertGrid, summarizeMeasurements,
  sales: scenario('table-grid-sales-readonly', '/sales/orders', true),
  label: scenario('table-grid-label-readonly', '/quality/label-qc', false) };

},
    "scenarios/table-top-scroll-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertPosition(m) {
  if (!m.external || m.overlap > 1 || m.widthDifference > 2) throw new Error('Top scrollbar intrudes into the table or has the wrong width');
  if (m.bodyRange <= 0 || !m.visible) throw new Error('UNVERIFIED: no visible overflowing table');
  if (Math.abs(m.topLeft - m.bodyLeft) > 2 || Math.abs(m.headerLeft - m.bodyLeft) > 2) throw new Error('Top/header/body scroll offsets disagree');
}

function scenario(id, path) {
  const inspect = async (page, _body, ctx) => {
    const table = page.locator('main.app-content .el-table').first();
    await table.locator('.el-table__body tbody tr').first().waitFor({ state: 'visible', timeout: 15000 });
    const bar = table.locator('xpath=preceding-sibling::*[1][contains(@class,"cretas-table-top-scrollbar")]');
    const measure = () => table.evaluate(el => {
      const top = el.previousElementSibling;
      const wrap = el.querySelector('.el-table__body-wrapper .el-scrollbar__wrap');
      const header = el.querySelector('.el-table__header-wrapper');
      const a = top.getBoundingClientRect(), b = el.getBoundingClientRect();
      return { external: top.classList.contains('cretas-table-top-scrollbar') && !el.contains(top),
        visible: !top.hidden && a.height > 0, overlap: a.bottom - b.top,
        widthDifference: Math.abs(a.width - b.width), topRange: top.scrollWidth - top.clientWidth,
        bodyRange: wrap.scrollWidth - wrap.clientWidth, topLeft: top.scrollLeft,
        bodyLeft: wrap.scrollLeft, headerLeft: header.scrollLeft, barHeight: a.height };
    });
    async function wheel(target, delta, horizontal = false) {
      if (!await target.isVisible()) {
        const diagnostic = await target.evaluate(el => ({ className: el.className, hidden: el.hidden,
          display: getComputedStyle(el).display, width: el.getBoundingClientRect().width, height: el.getBoundingClientRect().height,
          parentClass: el.parentElement?.className }));
        await ctx.screenshot(`${id}-hidden-scroll-target`);
        throw new Error(`UNVERIFIED: scroll target hidden ${JSON.stringify(diagnostic)}`);
      }
      await target.scrollIntoViewIfNeeded();
      await target.hover();
      await step(ctx, () => page.mouse.wheel(horizontal ? delta : 0, horizontal ? 0 : delta));
      await page.waitForTimeout(200);
    }
    const snapshots = [], screenshots = [], nonOverflowWidths = [], reverseGestures = [];
    for (const width of [1440, 1024]) {
      await page.setViewportSize({ width, height: 900 });
      await page.waitForTimeout(300);
      const initial = await measure();
      if (initial.bodyRange <= 0) {
        if (initial.visible) throw new Error('Scrollbar occupies space when the table does not overflow');
        nonOverflowWidths.push(width);
        screenshots.push(await ctx.screenshot(`${id}-${width}-no-overflow`));
        continue;
      }
      await bar.waitFor({ state: 'visible', timeout: 5000 });
      await wheel(bar, -100000);
      let m = await measure(); assertPosition(m);
      if (m.bodyLeft > 2) throw new Error('Top scrollbar did not reach the left edge');
      await wheel(bar, 120);
      m = await measure(); assertPosition(m);
      if (m.bodyLeft < 50) throw new Error('Top wheel did not move table content');
      const beforeReverse = m.bodyLeft;
      const bottom = table.locator(':scope > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__bar.is-horizontal');
      if (await bottom.isVisible()) {
        await wheel(bottom, -60);
        reverseGestures.push('bottom-scrollbar-wheel');
      } else {
        // Some tables hide EP's synthetic bottom bar. Exercise their real
        // scrolling content with a horizontal wheel gesture, never force-click hidden UI.
        await wheel(table.locator(':scope > .el-table__inner-wrapper > .el-table__body-wrapper > .el-scrollbar > .el-scrollbar__wrap'), -60, true);
        reverseGestures.push('body-horizontal-wheel');
      }
      m = await measure(); assertPosition(m);
      if (m.bodyLeft >= beforeReverse - 20) throw new Error('Body scrollbar did not drive the top scrollbar');
      await wheel(bar, 100000);
      m = await measure(); assertPosition(m);
      if (Math.abs(m.bodyRange - m.bodyLeft) > 2) throw new Error('Top scrollbar did not reach the right edge');
      snapshots.push({ width, ...m });
      screenshots.push(await ctx.screenshot(`${id}-${width}-right`));
    }
    if (!snapshots.length) throw new Error('UNVERIFIED: no overflowing viewport was exercised');
    await wheel(bar, -100000);
    const key = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    if (!key) throw new Error('Original tab identity is missing');
    await step(ctx, () => page.getByRole('button', { name: '复制当前页面为新任务', exact: true }).click());
    await page.waitForFunction(original => document.querySelector('.workspace-tab-container.active .workspace-tab')?.getAttribute('data-workspace-key') !== original, key);
    const copyKey = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
    await step(ctx, () => page.locator(`.workspace-tab[data-workspace-key=${JSON.stringify(key)}]`).click());
    await table.waitFor({ state: 'visible' });
    if (await bar.count() !== 1) throw new Error('Tab reactivation duplicated or removed the scrollbar');
    await wheel(bar, 120);
    const restored = await measure(); assertPosition(restored);
    if (restored.bodyLeft < 50) throw new Error('Reactivated scrollbar no longer works');
    await wheel(bar, -100000);
    await step(ctx, () => page.locator('.workspace-tab-container').filter({ has: page.locator(`[data-workspace-key=${JSON.stringify(copyKey)}]`) }).getByRole('button', { name: '关闭任务', exact: true }).click());
    screenshots.push(await ctx.screenshot(`${id}-restored`));
    assertSafe(ctx);
    return { viewportWidths: snapshots.map(m => m.width), nonOverflowWidths, reverseGestures, bodyRanges: snapshots.map(m => m.bodyRange),
      topRanges: snapshots.map(m => m.topRange), rightOffsets: snapshots.map(m => m.bodyLeft),
      headerOffsets: snapshots.map(m => m.headerLeft), topOffsets: snapshots.map(m => m.topLeft),
      barHeights: snapshots.map(m => m.barHeight), maximumOverlap: Math.max(...snapshots.map(m => m.overlap)),
      maximumWidthDifference: Math.max(...snapshots.map(m => m.widthDifference)),
      topWheelPassed: true, bodyToTopPassed: true, bothEndpointsPassed: true, reactivationPassed: true,
      screenshots, limits: 'Current populated tables only; native cursor/DPI, touch, all-page coverage and elapsed-time efficiency not measured',
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  };
  return { id, defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id, path, screenshot: true, inspect: page => inspect(page, '', ctx) }) };
}
module.exports = { assertPosition,
  sales: scenario('table-top-scroll-sales-readonly', '/sales/orders'),
  purchase: scenario('table-top-scroll-purchase-readonly', '/procurement/orders'),
  transfer: scenario('table-top-scroll-transfer-readonly', '/transfer/list') };

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
  const expected = ['新建销售订单 1', '新建销售订单 2', '新建销售订单 3'];
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
  const originalKey = await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
  await copy('新建销售订单 2');
  await fillDate('20300216', '2030-02-16');
  await copy('新建销售订单 3');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  await activate('新建销售订单 1');
  if (await page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key') !== originalKey) throw new Error('Numbering changed the original cache identity');
  if (await date.inputValue() !== '2030-01-15') throw new Error('Original draft lost its date');
  await activate('新建销售订单 2');
  if (await date.inputValue() !== '2030-02-16') throw new Error('Copied draft lost its independent date');
  await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  await activate('新建销售订单 1');
  await copy('新建销售订单 2');
  verifyLabels((await labels()).filter(label => label.startsWith('新建销售订单')));
  if (await date.inputValue() === '2030-02-16') throw new Error('New copy reused the closed draft instance');
  const screenshots = [];
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    await tabByTitle('新建销售订单 2').scrollIntoViewIfNeeded();
    for (const ordinal of [1, 2, 3]) {
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
  for (const ordinal of [3, 2]) {
    await activate(`新建销售订单 ${ordinal}`);
    await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  }
  await activate('新建销售订单');
  if (await date.inputValue() !== '2030-01-15') throw new Error('Removing display numbering lost the original draft');
  assertSafe(ctx);
  return { uniqueLabels: true, originalKeyUnchanged: true, unnumberedAfterCopyCleanup: true, ordinalsVisibleAtBothWidths: true, originalDraftPreserved: true, copiedDraftPreserved: true, closedCopyNotReused: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
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
    "scenarios/workspace-sidebar-readonly.js": function(module, exports, require) {
'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

async function inspect(page, _body, ctx) {
  const search = page.getByPlaceholder('搜索 订单号/客户', { exact: true });
  const tab = title => page.locator('.workspace-tab').filter({ has: page.locator('.workspace-tab-title').filter({ hasText: new RegExp(`^${title}$`) }) });
  const activeKey = () => page.locator('.workspace-tab-container.active .workspace-tab').getAttribute('data-workspace-key');
  await search.waitFor({ state: 'visible', timeout: 15000 });
  const original = await activeKey();
  await step(ctx, () => search.fill('R14-original-unsent'));
  const menu = page.locator('.app-sidebar .el-menu-item').filter({ has: page.locator('.menu-title-copy').filter({ hasText: /^销售订单$/ }) });
  // Use the real sidebar selection event; do not synthesize a router push.
  async function openMenu(ordinal) {
    await step(ctx, () => menu.click());
    await page.waitForURL(url => url.searchParams.get('_taskLabel') === `销售订单 ${ordinal}`, { timeout: 10000 });
    await search.waitFor({ state: 'visible' });
    if (await search.inputValue() !== '') throw new Error('Sidebar new task inherited an old filter');
  }
  await openMenu(2);
  const second = await activeKey();
  await step(ctx, () => search.fill('R14-second-unsent'));
  await openMenu(3);
  const third = await activeKey();
  if (new Set([original, second, third]).size !== 3) throw new Error('Sidebar task identities collided');
  async function activate(title) {
    await step(ctx, () => tab(title).click());
    await page.waitForFunction(expected => document.querySelector('.workspace-tab-container.active .workspace-tab-title')?.textContent === expected, title);
  }
  await activate('销售订单 1');
  if (await activeKey() !== original || await search.inputValue() !== 'R14-original-unsent') throw new Error('Original list state lost');
  await activate('销售订单 2');
  if (await search.inputValue() !== 'R14-second-unsent') throw new Error('Second list state lost');
  const screenshots = [];
  for (const width of [1440, 1024]) {
    await page.setViewportSize({ width, height: 900 });
    for (const n of [1, 2, 3]) {
      const number = tab(`销售订单 ${n}`).locator('.workspace-tab-number');
      if (!await number.isVisible()) throw new Error('Sidebar task ordinal is hidden');
    }
    screenshots.push(await ctx.screenshot(`workspace-sidebar-${width}`));
  }
  for (const n of [3, 2]) {
    await activate(`销售订单 ${n}`);
    await step(ctx, () => page.locator('.workspace-tab-container.active .workspace-tab-close').click());
  }
  await activate('销售订单');
  if (await search.inputValue() !== 'R14-original-unsent') throw new Error('Cleanup lost original list state');
  await step(ctx, () => search.fill(''));
  assertSafe(ctx);
  return { sidebarCreatesIndependentTasks: true, freshFilters: true, originalKeyUnchanged: true, originalFilterRetained: true, secondFilterRetained: true, cleanupRetainedOriginal: true, screenshots, assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'workspace-sidebar-readonly', defaultEnabled: false, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'workspace-sidebar-readonly', path: '/sales/orders', screenshot: true, inspect }) };

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
