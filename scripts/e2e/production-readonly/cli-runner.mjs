#!/usr/bin/env node

import path from 'node:path';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { describeHarness, resolveScenarios, runSuiteWithPage } = require('./core/run-suite.js');
const questionSet = require('./scenarios/factory-analysis-question-set-readonly');

function parseArgs(argv) {
  const options = { scenarios: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === '--headed') options.headed = true;
    else if (arg === '--production-readonly') options.productionReadonly = true;
    else if (arg === '--dry-run') options.dryRun = true;
    else if (arg === '--scenario') options.scenarios.push(...String(argv[++index] || '').split(',').filter(Boolean));
    else if (arg === '--base-url') options.baseUrl = argv[++index];
    else if (arg === '--evidence-dir') options.evidenceDir = argv[++index];
    else if (arg === '--questions-file') {
      options.questionsFile = argv[++index];
      if (!options.questionsFile || options.questionsFile.startsWith('--')) throw new Error('--questions-file requires a path');
    }
    else throw new Error(`Unknown argument: ${arg}`);
  }
  return options;
}

function isProductionLike(baseUrl) {
  const url = new URL(baseUrl);
  return !['localhost', '127.0.0.1', '::1'].includes(url.hostname);
}

async function loadChromium() {
  for (const name of ['playwright', '@playwright/test']) {
    try {
      const module = await import(name);
      if (module.chromium) return module.chromium;
    } catch {}
  }
  throw new Error('Project Playwright runtime is unavailable. Run npm ci at the repository root; do not install a separate harness dependency tree.');
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const baseUrl = args.baseUrl || process.env.E2E_ADMIN_URL || 'http://localhost:5173';
  const productionReadonly = args.productionReadonly || isProductionLike(baseUrl);
  const scenarios = resolveScenarios(args.scenarios);
  const questionsFile = args.questionsFile || process.env.E2E_FACTORY_ANALYSIS_QUESTIONS_FILE;
  const questionSetSelected = scenarios.includes(questionSet);
  let factoryAnalysisQuestions;
  if (questionSetSelected && (questionsFile || !args.dryRun)) {
    if (!questionsFile) throw new Error('Set --questions-file or E2E_FACTORY_ANALYSIS_QUESTIONS_FILE');
    const content = await readFile(questionsFile, 'utf8');
    factoryAnalysisQuestions = questionSet.parseQuestionFile(content, /\.md$/iu.test(questionsFile));
  }
  if (args.dryRun) {
    console.log(JSON.stringify({ ...describeHarness(), baseUrl, productionReadonly, selectedScenarios: args.scenarios,
      ...(factoryAnalysisQuestions ? { questionCount: factoryAnalysisQuestions.length } : {}),
    }, null, 2));
    return;
  }

  const expectedFactoryId = process.env.E2E_FACTORY_ID || 'F006';
  for (const scenario of scenarios) scenario.validateFactory?.(expectedFactoryId);
  const isLiushanmen = expectedFactoryId === 'LIUSHANMEN';
  const username = isLiushanmen ? process.env.TEST_LIUSHANMEN_ADMIN_USER : process.env.E2E_USERNAME;
  const password = isLiushanmen ? process.env.TEST_LIUSHANMEN_ADMIN_PASS : process.env.E2E_PASSWORD;
  if (!username || !password) {
    throw new Error(isLiushanmen
      ? 'TEST_LIUSHANMEN_ADMIN_USER and TEST_LIUSHANMEN_ADMIN_PASS are required from a gitignored environment source'
      : 'E2E_USERNAME and E2E_PASSWORD are required from a gitignored environment source');
  }
  const runId = new Date().toISOString().replace(/[:.]/g, '-');
  const evidenceDir = path.resolve(args.evidenceDir || path.join('.playwright-mcp', 'production-readonly', runId));
  const chromium = await loadChromium();
  const browser = await chromium.launch({ headless: !(args.headed || productionReadonly) });
  const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
  const page = await context.newPage();
  try {
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: args.scenarios,
      username,
      password,
      expectedUsername: isLiushanmen ? username : process.env.E2E_EXPECTED_USERNAME || 'f006_admin',
      expectedFactoryId,
      factoryAnalysisQuestions,
      expectedRole: process.env.E2E_EXPECTED_ROLE || null,
      factoryAccessFactoryId: process.env.E2E_FACTORY_ACCESS_TARGET || null,
      factoryAccessCases: process.env.E2E_FACTORY_ACCESS_CASES ? JSON.parse(process.env.E2E_FACTORY_ACCESS_CASES) : null,
      materialRequisition: process.env.E2E_MATERIAL_REQUISITION ? JSON.parse(process.env.E2E_MATERIAL_REQUISITION) : null,
      allowMobileOnlyLanding: process.env.E2E_EXPECT_MOBILE_ONLY === '1',
      productionReadonly,
    });
    console.log(JSON.stringify({
      runId: report.runId,
      evidence: report.evidence,
      scenarios: report.scenarios.map(({ scenario, result }) => ({ scenario, result })),
      blockedMutationAttempts: report.blockedMutationAttempts.length,
      actualBusinessWrites: report.actualBusinessWrites,
      exitCode: report.exitCode,
    }, null, 2));
    process.exitCode = report.exitCode;
  } finally {
    await context.close();
    await browser.close();
  }
}

main().catch((error) => {
  console.error(`[production-readonly] ${error.message}`);
  process.exitCode = 1;
});
