'use strict';

const { assertFactoryAnalysisFactory, isFactoryAnalysisUrl } = require('../config/factory-analysis-scope');
const { createScenarioResult, assertScenarioResult } = require('../core/result-schema');

const id = 'factory-analysis-question-set-readonly';
const path = '/smart-bi/analysis?tab=query&mode=factory&source=business';
const confirmationSelector = '[role="alertdialog"], [role="dialog"], .el-message-box, dialog[open]';

function validateQuestions(questions) {
  if (!Array.isArray(questions) || questions.length < 1 || questions.length > 20) {
    throw new Error('Question set must contain 1–20 questions');
  }
  for (const [index, question] of questions.entries()) {
    if (typeof question !== 'string' || !question.trim() || [...question].length > 200) {
      throw new Error(`Question ${index + 1} must be a non-empty string of at most 200 characters`);
    }
  }
  return questions;
}

function parseQuestionFile(text, markdown = false) {
  const content = text.replace(/^\uFEFF/u, '');
  if (markdown) {
    const lines = content.split(/\r?\n/u);
    const header = lines.findIndex(line => /^\|\s*问题\s*\|/u.test(line));
    const questions = [];
    if (header >= 0 && /^\|\s*-+/u.test(lines[header + 1] || '')) {
      for (const line of lines.slice(header + 2)) {
        const row = /^\|\s*([^|]+?)\s*\|/u.exec(line);
        if (!row) break;
        questions.push(row[1]);
      }
    }
    return validateQuestions(questions);
  }
  let questions;
  try { questions = JSON.parse(content); } catch { throw new Error('Question file must be a JSON string array'); }
  return validateQuestions(questions);
}

function sourceEvidence(data) {
  const selectedIds = new Set(Array.isArray(data.selectedIds) ? data.selectedIds : []);
  const items = [
    ...(data.recommendations || []),
    ...(data.facts || []),
    ...(data.catalog || []).filter(item => selectedIds.has(item.id)),
  ];
  const citations = [data.sourceKey, data.dataset, data.datasetId,
    ...items.flatMap(item => [item.source, item.sourceKey, item.dataset, item.datasetId])];
  const labels = [data.source, data.sourceLabel, ...citations].filter(value => typeof value === 'string' && value.trim());
  return {
    sourceIdentifiers: [...new Set(labels)],
    selectedIds: [...selectedIds],
    cites_blacklake: citations.some(value => typeof value === 'string'
      && /^(?:blacklake-production|fact_external_production_daily|黑湖生产报工)(?:$|\s*·)/iu.test(value)),
  };
}

async function askQuestion(ctx, question, row) {
  const { page, baseUrl, expectedFactoryId, mutationGuard, scenarioRef } = ctx;
  const actionTimeout = ctx.factoryAnalysisQuestionTimeoutMs ?? 30_000;
  const answerTimeout = ctx.factoryAnalysisQuestionTimeoutMs ?? 125_000;
  const suffix = `${id}-${row.index}`;
  const submit = page.getByTestId('factory-analysis-submit');
  const answer = page.getByTestId('factory-analysis-answer');
  const input = page.locator('textarea[data-testid="factory-analysis-input"], [data-testid="factory-analysis-input"] textarea');
  const wireQuestion = question.replace(/\r\n?/gu, '\n').trim();
  const onResponse = response => {
    const request = response.request();
    if (request.method() !== 'POST' || !isFactoryAnalysisUrl(response.url(), expectedFactoryId)) return;
    try {
      const body = request.postDataJSON();
      if (body?.source === 'business' && body.question === wireQuestion) {
        row.httpStatus = response.status();
        analysisResponse = response;
      }
    } catch { /* No matching factory-analysis response. */ }
  };
  const onDialog = async dialog => {
    if (dialog.type() === 'confirm' || dialog.type() === 'prompt') {
      mutationGuard.recordBlockedConfirmation(page.url(), dialog.type());
    }
    await dialog.dismiss().catch(() => {});
  };
  let analysisResponse;
  const started = Date.now();
  page.on('dialog', onDialog);
  try {
    await page.goto(`${baseUrl}${path}`, { waitUntil: 'domcontentloaded', timeout: actionTimeout });
    await page.getByTestId('factory-analysis-source').getByText(/^(?:业务系统|业务数据)$/u)
      .waitFor({ timeout: actionTimeout });
    if (await page.locator(confirmationSelector).filter({ visible: true }).count()) {
      mutationGuard.recordBlockedConfirmation(page.url(), 'visible-dialog');
      return 'blocked-confirmation';
    }
    if (mutationGuard.blockedMutationAttempts.length) return 'blocked-mutation';
    await input.fill(question, { timeout: actionTimeout });
    page.on('response', onResponse);
    scenarioRef.factoryAnalysisQuestion = wireQuestion;
    await submit.click({ timeout: actionTimeout });
    if (mutationGuard.blockedMutationAttempts.length) return 'blocked-mutation';
    const terminal = await (await page.waitForFunction(selector => {
      if ([...document.querySelectorAll(selector)].some(element => element.getClientRects().length
        && getComputedStyle(element).visibility === 'visible')) return 'confirmation';
      const chat = document.querySelector('[data-testid="factory-analysis-chat"]');
      const turn = chat?.querySelector('.turn--assistant');
      if (turn && !turn.querySelector('.el-skeleton')) return 'answer-settled';
      if (chat?.querySelector('.state-alert.el-alert--error')) return 'ui-error';
      return false;
    }, confirmationSelector, { timeout: answerTimeout })).jsonValue();
    if (terminal === 'confirmation') mutationGuard.recordBlockedConfirmation(page.url(), 'visible-dialog');
    if (mutationGuard.blockedMutationAttempts.length) return 'blocked-mutation';
    if (!analysisResponse) return 'missing-analysis-response';
    if (!analysisResponse.ok()) return 'analysis-http-error';
    const response = await analysisResponse.json();
    const data = response?.data;
    if (!response?.success || data?.factoryId !== expectedFactoryId || data?.source !== 'business') {
      return 'invalid-analysis-response';
    }
    Object.assign(row, sourceEvidence(data));
    row.analysisStatus = data.status || null;
    if (typeof data.answer === 'string' && data.answer.trim()) row.factoryAnalysisAnswer = data.answer;
    if (data.status !== 'answered' || row.factoryAnalysisAnswer === null) return 'no-completed-answer';
    if (terminal !== 'answer-settled' || await answer.textContent({ timeout: actionTimeout }) !== row.factoryAnalysisAnswer) {
      return 'answer-render-mismatch';
    }
    row.answerCompleted = true;
    return null;
  } catch (error) {
    if (await page.locator(confirmationSelector).filter({ visible: true }).count().catch(() => 0)) {
      mutationGuard.recordBlockedConfirmation(page.url(), 'visible-dialog');
    }
    if (mutationGuard.blockedMutationAttempts.length) return 'blocked-mutation';
    return error?.name === 'TimeoutError' ? 'timeout' : 'question-interaction-error';
  } finally {
    delete scenarioRef.factoryAnalysisQuestion;
    row.elapsedMs = Date.now() - started;
    row.screenshot = await ctx.screenshot(suffix, { fullPage: true }).catch(() => null);
    page.off('response', onResponse);
    page.off('dialog', onDialog);
  }
}

async function runQuestionSet(questions, ask, shouldStop = () => false) {
  const rows = [];
  for (const [index, question] of questions.entries()) {
    if (shouldStop()) break;
    const row = {
      index: index + 1, factoryAnalysisQuestion: question, factoryAnalysisAnswer: null,
      answerCompleted: false, analysisStatus: null, sourceIdentifiers: [], selectedIds: [],
      cites_blacklake: false, httpStatus: null, elapsedMs: null, screenshot: null,
    };
    const reason = await ask(question, row);
    row.failureReason = reason || (row.screenshot ? null : 'screenshot-failed');
    row.status = row.failureReason ? 'failed' : 'answered';
    rows.push(row);
  }
  return rows;
}

async function run(ctx) {
  ctx.scenarioRef.value = id;
  const questions = validateQuestions(ctx.factoryAnalysisQuestions);
  const result = createScenarioResult(id, `${ctx.baseUrl}${path}`);
  const start = Date.now();
  const blocked = () => ctx.mutationGuard.blockedMutationAttempts.length || ctx.mutationGuard.actualBusinessWrites;
  const rows = await runQuestionSet(questions, (question, row) => askQuestion(ctx, question, row), blocked);
  const guard = ctx.mutationGuard.snapshot();
  const answered = rows.filter(row => row.answerCompleted).length;
  result.result = blocked() ? 'TOOL_ERROR' : rows.every(row => row.status === 'answered') ? 'PASS' : answered ? 'PARTIAL_DEFECT' : 'UNVERIFIED';
  result.rootCauseClass = result.result === 'PASS' ? 'none' : 'tool';
  result.pageEvidence = rows;
  result.screenshots = rows.map(row => row.screenshot).filter(Boolean);
  result.blockedMutationAttempts = guard.blockedMutationAttempts;
  result.actualBusinessWrites = guard.actualBusinessWrites;
  result.durationMs = Date.now() - start;
  result.questionSet = { requested: questions.length, attempted: rows.length, answered };
  return assertScenarioResult(result);
}

module.exports = { id, defaultEnabled: false, validateFactory: assertFactoryAnalysisFactory, validateQuestions, parseQuestionFile, sourceEvidence, runQuestionSet, run };
