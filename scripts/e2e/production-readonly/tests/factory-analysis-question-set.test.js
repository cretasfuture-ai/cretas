'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { readFileSync } = require('node:fs');
const path = require('node:path');
const { parseQuestionFile, runQuestionSet, sourceEvidence } = require('../scenarios/factory-analysis-question-set-readonly');
const { describeHarness, resolveScenarios, reportExitCode } = require('../core/run-suite');

const id = 'factory-analysis-question-set-readonly';

test('question files preserve JSON text and accept the checked-in Blacklake table', () => {
  const questions = ['第 1 题？', '  产品 C P2026100002\n是多少？  ', '𠮷'.repeat(200)];
  assert.deepEqual(parseQuestionFile(`\uFEFF${JSON.stringify(questions)}`), questions);
  const markdown = readFileSync(path.resolve(__dirname, '../../../../docs/smartbi/liushanmen-blacklake/questions.md'), 'utf8');
  const actual = parseQuestionFile(markdown, true);
  assert.equal(actual.length, 12);
  assert.equal(actual[2], '11 月产品 C P2026100002 的每日成品入库量怎样？');
});

test('question files reject bad JSON, wrong types, empty, oversized and overlong sets', () => {
  for (const input of [null, {}, [], Array(21).fill('问题')]) {
    assert.throws(() => parseQuestionFile(JSON.stringify(input)), /1–20 questions/u);
  }
  for (const input of [[''], ['  \n'], [42], ['𠮷'.repeat(201)]]) {
    assert.throws(() => parseQuestionFile(JSON.stringify(input)), /Question 1 .* 200 characters/u);
  }
  assert.throws(() => parseQuestionFile('invalid'), /JSON string array/u);
  assert.throws(() => parseQuestionFile('# Missing table', true), /1–20 questions/u);
  assert.equal(parseQuestionFile(JSON.stringify(Array(20).fill('问题'))).length, 20);
});

test('a failed question remains failed in evidence while the next question is attempted', async () => {
  const attempted = [];
  const rows = await runQuestionSet(['先问', '后问'], (question, row) => {
    attempted.push(question);
    row.screenshot = `${row.index}.png`;
    if (question === '先问') return 'timeout';
    row.answerCompleted = true;
    return null;
  });
  assert.deepEqual(attempted, ['先问', '后问']);
  assert.deepEqual(rows.map(row => row.status), ['failed', 'answered']);
  assert.equal(rows[0].failureReason, 'timeout');
});

test('zero completed answers exits 2; partial failures and safety failures exit 1', () => {
  const report = { safetyPassed: true, scenarios: [{ questionSet: { attempted: 2, answered: 0 }, result: 'UNVERIFIED' }] };
  assert.equal(reportExitCode(report), 2);
  assert.equal(reportExitCode({ ...report, safetyPassed: false }), 1);
  report.scenarios[0].questionSet.answered = 1;
  assert.equal(reportExitCode(report), 1);
  report.scenarios[0].result = 'PASS';
  assert.equal(reportExitCode(report), 0);
});

test('the registered opt-in scenario reuses the two-tenant scope gate and rejects LSMDEMO', () => {
  const [scenario] = resolveScenarios([id]);
  const scope = require('../config/factory-analysis-scope');
  assert.equal(scenario.validateFactory, scope.assertFactoryAnalysisFactory);
  for (const factory of ['F006', 'LIUSHANMEN']) assert.doesNotThrow(() => scenario.validateFactory(factory));
  assert.throws(() => scenario.validateFactory('LSMDEMO'), /tenant is not allowed/u);
  assert.ok(describeHarness().scenarios.includes(id));
  assert.ok(!describeHarness().defaultScenarios.includes(id));
});

test('a Blacklake catalog candidate alone is not a citation', () => {
  const data = {
    source: 'business', selectedIds: ['internal'],
    catalog: [{ id: 'internal', sourceKey: 'production' }, { id: 'external', sourceKey: 'blacklake-production' }],
    sourceErrors: [{ source: '黑湖' }],
  };
  assert.deepEqual(sourceEvidence(data), { sourceIdentifiers: ['business', 'production'], selectedIds: ['internal'], cites_blacklake: false });
  assert.equal(sourceEvidence({ ...data, selectedIds: ['external'] }).cites_blacklake, true);
  assert.equal(sourceEvidence({ ...data, facts: [{ source: 'blacklake-production · 黑湖生产报工' }] }).cites_blacklake, true);
});
