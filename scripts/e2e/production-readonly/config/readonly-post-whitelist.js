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
