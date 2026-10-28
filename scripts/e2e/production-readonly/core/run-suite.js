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
