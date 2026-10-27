'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { classifyMutation } = require('../core/mutation-guard');

test('allows only exact authentication and documented query POST contracts', () => {
  assert.equal(classifyMutation('GET', 'https://admin.example/api/mobile/F006/skus').kind, 'safe-method');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/auth/unified-login').kind, 'auth');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/smartbi/financial-dashboard/factory-details').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/internalTransfer').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/inventory').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/shipment').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/returnOrder').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/qualityInspection').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/product-process-workflows/resolve-by-outputs').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/chart/smart-recommend').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/chart/recommend').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/chart/batch').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/chart/build').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/insight/quick-summary').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/insight/generate').kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/insight/generate-stream').kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    {
      userInput: '查询指标 stock_turnover',
      intentCode: 'INDICATOR_QUERY',
      context: { indicator_code: 'stock_turnover' },
      sessionId: 'indicator-card-stock_turnover-1',
      skipSlotFilling: true,
    },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今天要起产什么?', intentCode: 'PRODUCTION_DEMAND_QUERY' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今天哪些批次待放行?', intentCode: 'QUALITY_CHIEF_WORKDESK' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今天该跟谁?', intentCode: 'DAILY_CUSTOMER_FOLLOWUP' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今日 SO 待发', intentCode: 'SPRINT10_SHIPMENT_PENDING_TODAY', parameters: {} },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今天要收什么货?', intentCode: 'MATERIAL_TODAY_RECEIVING_QUERY' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '临期物料建议', intentCode: 'MATERIAL_DISPOSAL_RECOMMENDATION' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '今日待质检批次', intentCode: 'RECEIVE_QUALITY_CHECK_TODAY' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '7 天销售预测', intentCode: 'SALES_FORECAST_7DAY' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    {
      userInput: '猪肉 价格历史',
      intentCode: 'PRICE_HISTORY_QUERY',
      parameters: { materialTypeId: 'RM-001', monthsBack: 6 },
    },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation(
    'POST',
    'https://admin.example/api/mobile/F006/ai-intents/execute',
    undefined,
    { userInput: '下周采购什么?', intentCode: 'PURCHASER_WEEKLY_PLAN' },
  ).kind, 'readonly-post');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/ai/chat').kind, 'business-mutation');
  assert.equal(classifyMutation('PUT', 'https://admin.example/api/mobile/F006/skus/1').kind, 'business-mutation');
  assert.equal(classifyMutation('DELETE', 'https://admin.example/api/mobile/F006/boms/276').kind, 'business-mutation');
});

test('allows only the exact read-only indicator intent payload', () => {
  const url = 'https://admin.example/api/mobile/F006/ai-intents/execute';
  assert.equal(classifyMutation('POST', url).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '查询指标 stock_turnover',
    intentCode: 'APPROVAL_ACTION_EXECUTE',
    sessionId: 'indicator-card-stock_turnover-1',
    skipSlotFilling: true,
    context: { indicator_code: 'stock_turnover' },
  }).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '查询指标 stock_turnover',
    intentCode: 'INDICATOR_QUERY',
    sessionId: 'indicator-card-stock_turnover-1',
    skipSlotFilling: false,
    context: { indicator_code: 'stock_turnover' },
  }).kind, 'business-mutation');
});

test('allows only the exact purchaser workdesk mount query payload', () => {
  const url = 'https://admin.example/api/mobile/F006/ai-intents/execute';
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '下周采购什么?',
    intentCode: 'PURCHASER_WEEKLY_PLAN',
    preview: true,
  }).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '下周采购什么?',
    intentCode: 'PROCUREMENT_ORDER_CREATE',
  }).kind, 'business-mutation');
});

test('rejects altered purchaser forecast and price-history query payloads', () => {
  const url = 'https://admin.example/api/mobile/F006/ai-intents/execute';
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '7 天销售预测',
    intentCode: 'SALES_FORECAST_7DAY',
    preview: true,
  }).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', url, undefined, {
    userInput: '猪肉 价格历史',
    intentCode: 'PRICE_HISTORY_QUERY',
    parameters: { materialTypeId: 'RM-001', monthsBack: 6, execute: true },
  }).kind, 'business-mutation');
});

test('does not allow a suffix or prefix near an allowlisted path', () => {
  assert.equal(classifyMutation('POST', 'https://admin.example/smartbi-api/api/smartbi/financial-dashboard/factory-details/extra').kind, 'business-mutation');
  assert.equal(classifyMutation('GET', 'https://admin.example/smartbi-api/api/smartbi/financial-dashboard/factory-details').kind, 'safe-method');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/list-summary/purchaseOrder/delete').kind, 'business-mutation');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/not-list-summary/purchaseOrder').kind, 'business-mutation');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/product-process-workflows/resolve-by-outputs/delete').kind, 'business-mutation');
});

test('allows only the exact factory analysis contract while generic chat remains blocked', () => {
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/ai/chat').kind, 'business-mutation');
  const url = 'https://admin.example/api/mobile/F006/smart-bi/factory-analysis';
  assert.equal(classifyMutation('POST', url, undefined, {
    question: '汇总当前库存、生产和销售表现', source: 'business', year: 2026,
    periodType: 'all', startMonth: 1, endMonth: 12, analysisSubject: null,
  }).kind, 'readonly-post');
  assert.equal(classifyMutation('POST', url, undefined, {
    question: '再看质量', contextId: 'rotated-context',
  }).kind, 'readonly-post');
  assert.equal(classifyMutation('POST', url, undefined, {
    question: '分析', source: 'upload', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12,
    analysisSubject: null,
  }).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', url, undefined, {
    question: '分析', source: 'business', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12,
    analysisSubject: null, role: 'factory_super_admin',
  }).kind, 'business-mutation');
  assert.equal(classifyMutation('POST', 'https://admin.example/api/mobile/F006/smart-bi/factory-analysis/extra').kind, 'business-mutation');
});
