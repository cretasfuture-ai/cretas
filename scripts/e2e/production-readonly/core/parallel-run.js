'use strict';
const { redactText } = require('./sanitizer');
function validateParallelRuns(runs, parent = {}) {
  if (!Array.isArray(runs) || runs.length < 2 || runs.length > 3) throw new Error('parallelRuns must contain 2 or 3 independent runs');
  if (parent.expectedFactoryId && parent.expectedFactoryId !== 'F006') throw new Error('Parallel acceptance is limited to F006');
  const parentId = parent.runId || `parallel-${Date.now()}`;
  const root = parent.evidenceDir || `.playwright-mcp/production-readonly/${parentId}`;
  const names = new Set(); const dirs = new Set(); const actors = new Set();
  return runs.map((run, index) => {
    if (!run || typeof run !== 'object') throw new Error(`parallelRuns[${index}] must be an object`);
    if ((run.expectedFactoryId || 'F006') !== 'F006') throw new Error(`parallelRuns[${index}] is limited to F006`);
    const username = run.username || parent.username; const expectedUsername = run.expectedUsername || username;
    if (!username || username !== expectedUsername) throw new Error('Each parallel run needs a matching login identity');
    const actorId = String(run.expectedActorId ?? '');
    if (!/^[1-9]\d*$/u.test(actorId) || actors.has(actorId)) throw new Error('parallelRuns require distinct positive expectedActorId values');
    actors.add(actorId);
    const runId = run.runId || `${parentId}-lane-${index + 1}`;
    if (!/^[A-Za-z0-9_-]{1,120}$/u.test(runId) || names.has(runId)) throw new Error('parallelRuns require unique safe runId values');
    names.add(runId);
    const evidenceDir = String(run.evidenceDir || `${root}/${runId}`).replace(/\\/gu, '/').replace(/\/+$/u, '');
    if (evidenceDir.split('/').includes('..') || !/(?:^|\/)(?:\.playwright-mcp|test-output|test-results|tmp)(?:\/|$)/u.test(evidenceDir)
      || dirs.has(evidenceDir.toLowerCase())) throw new Error('parallelRuns require unique safe evidenceDir values');
    dirs.add(evidenceDir.toLowerCase());
    return { ...run, runId, evidenceDir, username, expectedUsername, expectedActorId: actorId, expectedFactoryId: 'F006' };
  });
}
function runPassed(result) {
  return result?.safetyPassed === true && result.actorProof?.matchesExpectedUsername === true
    && result.actorProof?.matchesExpectedActorId === true && result.actorProof?.factoryId === 'F006'
    && Array.isArray(result.scenarios) && result.scenarios.length > 0 && result.scenarios.every(s => s.result === 'PASS')
    && !['httpErrors', 'failedRequests', 'bodyReadErrors'].some(key => result.network?.[key]?.length)
    && !result.console?.consoleErrors?.length && !result.console?.pageErrors?.length;
}
async function runParallelSuites(parentPage, options, runSuiteWithPage) {
  const runId = options.runId || `parallel-${Date.now()}`;
  const runs = validateParallelRuns(options.parallelRuns, { ...options, runId });
  const browser = parentPage.context().browser();
  if (!browser) throw new Error('MCP page browser is unavailable for parallel contexts');
  const startedAt = new Date().toISOString();
  const settled = await Promise.allSettled(runs.map(async (run, index) => {
    const context = await browser.newContext(); const cleanup = { attempted: false, closed: false };
    try {
      const page = await context.newPage(); const child = { ...options, ...run }; delete child.parallelRuns;
      const result = await runSuiteWithPage(page, child);
      return { index, runId: run.runId, evidenceDir: run.evidenceDir, actorId: result.actorProof?.actorId || null, result, cleanup };
    } finally { cleanup.attempted = true; await context.close(); cleanup.closed = true; }
  }));
  const output = settled.map((item, index) => {
    if (item.status === 'fulfilled') return item.value;
    let error = String(item.reason?.message || item.reason);
    for (const value of [runs[index].password, runs[index].username, options.password, options.username]) {
      if (value) error = error.split(String(value)).join('[REDACTED]');
    }
    return { index, runId: runs[index].runId, evidenceDir: runs[index].evidenceDir, result: { result: 'TOOL_ERROR', error: redactText(error, 500) } };
  });
  const allPassed = output.every((item, index) => item.cleanup?.closed === true
    && String(item.actorId) === runs[index].expectedActorId && runPassed(item.result));
  const actorIds = output.map(item => item.actorId).filter(Boolean);
  const distinctActors = actorIds.length === runs.length && new Set(actorIds).size === runs.length;
  const intervals = output.flatMap(item => (item.result?.network?.apiEvidence || [])
    .filter(r => r.method === 'POST' && /\/api\/mobile\/F006\/smart-bi\/factory-analysis\/?$/u.test(r.url) && r.status === 200 && Number.isFinite(r.durationMs))
    .map(r => ({ actorId: item.actorId, start: Date.parse(r.at), end: Date.parse(r.at) + r.durationMs })));
  const concurrentRequestsObserved = intervals.some((a, i) => intervals.slice(i + 1).some(b => a.actorId !== b.actorId && Math.min(a.end, b.end) > Math.max(a.start, b.start)));
  return { runId, productionReadonly: true, parallel: true, maxParallelRuns: 3, startedAt, finishedAt: new Date().toISOString(), runs: output, allSettled: true, distinctActors, concurrentRequestsObserved,
    allBusinessScenariosPassed: allPassed && distinctActors, result: allPassed && distinctActors && concurrentRequestsObserved ? 'PASS' : 'UNVERIFIED',
    safetyPassed: output.every(item => item.cleanup?.closed === true && item.result?.safetyPassed === true) };
}
module.exports = { validateParallelRuns, runParallelSuites, runPassed };
