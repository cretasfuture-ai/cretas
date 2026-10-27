'use strict';

const assert = require('node:assert/strict');
const test = require('node:test');
const scenario = require('../scenarios/smartbi-factory-analysis-readonly');

function response(status, data, request) {
  return {
    summary: scenario.summaryFromResponse(status, { success: true, data }, request),
  };
}

const candidate = (id, familyId) => ({ id, analysis: { familyId } });

function answeredData(overrides = {}) {
  return {
    factoryId: 'F006',
    source: 'business',
    period: { periodType: 'all', year: 2026, startMonth: 1, endMonth: 12, label: '全部历史' },
    status: 'answered',
    contextId: 'opaque-context',
    recommendations: [candidate('production-output', 'production.output'), candidate('quality-yield', 'quality.yield')],
    facts: [{ id: 'fact-redacted', label: '产量', value: 12, unit: 'kg', source: '业务系统' }],
    catalog: [{ id: 'production-output', familyId: 'production.output' }, { id: 'quality-yield', familyId: 'quality.yield' }],
    followUpQuestions: ['再看质量'],
    ...overrides,
  };
}

function passAssessment(overrides = {}) {
  return scenario.assess({
    initial: response(200, answeredData(), { question: '敏感问题不应进入证据', source: 'business', periodType: 'all', year: 2026, startMonth: 1, endMonth: 12 }),
    followup: response(200, answeredData({ contextId: 'rotated-context', period: { periodType: 'all', year: 2026, startMonth: 1, endMonth: 12, label: '全部历史' } }), { question: '敏感追问', contextId: 'opaque-context' }),
    ui: {
      catalogVisibleCount: 2, visibleChartCount: 2, renderedCanvasCount: 2, drawingErrorCount: 0,
      visibleFactCount: 1, visibleLimitationCount: 0, visibleSourceErrorCount: 0, followupDifferentDimension: true,
    },
    details: { attempted: true, success: true },
    exportAttempt: { attempted: true, success: true },
    ...overrides,
  });
}

test('factory analysis assessment requires grounded chart/fact UI and successful detail/export checks', () => {
  const result = passAssessment();
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks.boundedFamilies, true);
  assert.equal(result.checks.samePeriod, true);
  assert.doesNotMatch(JSON.stringify(result), /敏感问题|敏感追问|12/);
});

test('unavailable or clarification is explicitly UNVERIFIED, never an AI PASS', () => {
  const result = passAssessment({
    initial: response(200, answeredData({ status: 'unavailable', contextId: null, recommendations: [], facts: [], catalog: [] }), { question: '不可记录的问题' }),
  });
  assert.equal(result.result, 'UNVERIFIED');
  assert.equal(result.unavailableIsUnverified, true);
  assert.equal(result.clarificationIsUnverified, true);
  assert.equal(result.checks.answeredWithChartsAndFacts, false);

  const clarification = passAssessment({
    initial: response(200, answeredData({ status: 'clarification', recommendations: [], facts: [], contextId: 'clarify-context' }), { question: '需澄清的问题' }),
  });
  assert.equal(clarification.result, 'UNVERIFIED');
  assert.equal(clarification.clarificationIsUnverified, true);
});

test('assessment rejects family overflow, scope drift, and a non-followup request body', () => {
  const result = passAssessment({
    initial: response(200, answeredData({ recommendations: [
      candidate('a', 'a'), candidate('b', 'b'), candidate('c', 'c'), candidate('d', 'd'), candidate('e', 'e'),
    ] }), { source: 'business', periodType: 'all' }),
    followup: response(200, answeredData({ source: 'upload', period: { label: 'different' } }), { question: 'bad followup', source: 'upload', contextId: 'opaque-context' }),
  });
  assert.equal(result.result, 'UNVERIFIED');
  assert.equal(result.checks.boundedFamilies, false);
  assert.equal(result.checks.sourceBusiness, false);
  assert.equal(result.checks.samePeriod, false);
  assert.equal(result.checks.followupContract, false);
});
