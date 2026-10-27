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
const fusedCandidate = {
  id: 'production-inspection-fused-P1-kg',
  analysis: { familyId: 'production.inspection_fused' },
  chart: { echartsOption: { yAxis: [{}, {}, {}], series: [{}, {}, {}, {}] } },
  tableData: { rows: [['2026-01-01', 100, 90, 90, 200]] },
};

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

function jointAnsweredData(overrides = {}) {
  return answeredData({
    recommendations: [fusedCandidate],
    facts: [{ id: 'joint-fact', label: '检验合格率', value: 90, unit: '%', source: '业务系统' }],
    ...overrides,
  });
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
    joint: scenario.assessJoint({
      initial: response(200, answeredData({ status: 'clarification', recommendations: [], facts: [], subjects: [{ id: 'P1', label: 'P1' }] }), { question: '跨表问题' }),
      selected: { attempted: true, ...response(200, jointAnsweredData(), { question: '跨表问题 P1', source: 'business', periodType: 'all', year: 2026, startMonth: 1, endMonth: 12, analysisSubject: 'P1' }) },
      details: { attempted: true, success: true },
      exportAttempt: { attempted: true, success: true },
    }),
    ...overrides,
  });
}

test('factory analysis assessment requires grounded chart/fact UI and successful detail/export checks', () => {
  const result = passAssessment();
  assert.equal(result.result, 'PASS');
  assert.equal(result.checks.boundedFamilies, true);
  assert.equal(result.checks.samePeriod, true);
  assert.equal(result.fullPass, true);
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

test('expected joint data limitation is a negative PASS, never a positive joint PASS', () => {
  const result = passAssessment({
    joint: scenario.assessJoint({
      initial: response(200, answeredData({ status: 'clarification', recommendations: [], facts: [], subjects: [{ id: 'P1', label: 'P1' }] }), { question: '跨表问题' }),
      selected: { attempted: true, ...response(200, answeredData({
        status: 'unavailable', contextId: null, recommendations: [], facts: [],
        limitations: ['缺少同厂同产品且完整对齐的生产、真实质检与成本来源'],
      }), { question: '跨表问题 P1', contextId: 'opaque-context', analysisSubject: 'P1' }) },
      details: { attempted: false, success: false },
      exportAttempt: { attempted: false, success: false },
    }),
  });
  assert.equal(result.result, 'PASS');
  assert.equal(result.fullPass, false);
  assert.equal(result.joint.negativePass, true);
  assert.equal(result.joint.positiveJoint, 'UNVERIFIED_DATA_NOT_ELIGIBLE');
});

test('an unavailable response with a fabricated fused candidate cannot pass joint acceptance', () => {
  const result = passAssessment({
    joint: scenario.assessJoint({
      initial: response(200, answeredData({ status: 'clarification', recommendations: [], facts: [], subjects: [{ id: 'P1', label: 'P1' }] }), { question: '跨表问题' }),
      selected: { attempted: true, ...response(200, jointAnsweredData({ status: 'unavailable', contextId: null }), { question: '跨表问题 P1', contextId: 'opaque-context', analysisSubject: 'P1' }) },
      details: { attempted: true, success: true },
      exportAttempt: { attempted: true, success: true },
    }),
  });
  assert.equal(result.result, 'UNVERIFIED');
  assert.equal(result.fullPass, false);
  assert.equal(result.joint.positiveJoint, 'UNVERIFIED_DATA_NOT_ELIGIBLE');
  assert.equal(result.joint.negativePass, false);
});

test('partial source reads warn and prevent a full PASS even when visible', () => {
  const result = passAssessment({
    followup: response(200, answeredData({ sourceErrors: [{ source: 'quality', message: '读取失败' }] }), { question: '再看质量', contextId: 'rotated-context' }),
    ui: {
      catalogVisibleCount: 2, visibleChartCount: 2, renderedCanvasCount: 2, drawingErrorCount: 0,
      visibleFactCount: 1, visibleLimitationCount: 0, visibleSourceErrorCount: 1, followupDifferentDimension: true,
    },
  });
  assert.equal(result.result, 'UNVERIFIED');
  assert.equal(result.checks.noSourceErrors, false);
  assert.equal(result.partialReadWarn, true);
  assert.equal(result.fullPass, false);
});
