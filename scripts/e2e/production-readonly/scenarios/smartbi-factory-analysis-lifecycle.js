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
