'use strict';

const { parsePostData, redactText, sanitizeUrl, summarizePayload } = require('./sanitizer');
const { normalizeQueryValues, splitUrl } = require('./url-utils');

function normalizeDuplicateKey(method, url) {
  try {
    const parsed = splitUrl(url);
    const normalized = splitUrl(normalizeQueryValues(url));
    return `${method} ${parsed.origin}${parsed.pathname}${normalized.search}`;
  } catch {
    return `${method} ${url}`;
  }
}

function pickResponseSummary(body, extractors, url) {
  const summary = {
    success: typeof body?.success === 'boolean' ? body.success : null,
    message: body?.message == null ? null : redactText(body.message, 160),
    keyFields: {},
  };
  for (const extractor of extractors || []) {
    if (!extractor.match(url)) continue;
    Object.assign(summary.keyFields, extractor.pick(body) || {});
  }
  return summary;
}

function installNetworkRecorder(context, options = {}) {
  const records = [];
  const httpErrors = [];
  const failedRequests = [];
  const started = new WeakMap();
  const pending = new Set();
  const activeRequests = new Set();
  let lastActivityAt = Date.now();
  const bodyReadErrors = [];
  const bodyTimeoutMs = Number.isSafeInteger(options.responseBodyTimeoutMs)
    && options.responseBodyTimeoutMs > 0 && options.responseBodyTimeoutMs <= 60_000
    ? options.responseBodyTimeoutMs : 15_000;
  const extractors = options.responseExtractors || [];

  const observedApi = (url) => {
    try {
      const path = splitUrl(url).pathname;
      return path.startsWith('/api/mobile/') || path.startsWith('/smartbi-api/');
    } catch { return false; }
  };

  const onRequest = (request) => {
    if (!observedApi(request.url())) return;
    activeRequests.add(request);
    lastActivityAt = Date.now();
    started.set(request, {
      at: Date.now(),
      payload: summarizePayload(parsePostData(request)),
    });
  };

  const onResponse = (response) => {
    const request = response.request();
    if (!observedApi(request.url())) return;
    lastActivityAt = Date.now();
    const task = (async () => {
      const start = started.get(request);
      let body = null;
      let bodyReadError = null;
      let bodyTimer;
      try {
        const contentType = response.headers()['content-type'] || '';
        // Authentication is verified independently by performUiLogin. Keep
        // only its transport metadata here: its body contains session secrets,
        // and a full-page login redirect can discard the duplicate body read.
        const authentication = splitUrl(request.url()).pathname === '/api/mobile/auth/unified-login';
        if (/json/i.test(contentType) && !authentication) {
          body = await Promise.race([
            response.json(),
            new Promise((_, reject) => {
              bodyTimer = setTimeout(() => reject(new Error(`JSON response body did not finish within ${bodyTimeoutMs}ms`)), bodyTimeoutMs);
            }),
          ]);
        }
      } catch (error) {
        bodyReadError = {
          method: request.method(), url: sanitizeUrl(request.url()), status: response.status(),
          error: redactText(error?.message || String(error), 200),
        };
        bodyReadErrors.push(bodyReadError);
      } finally {
        if (bodyTimer) clearTimeout(bodyTimer);
      }
      const record = {
        method: request.method(),
        url: sanitizeUrl(request.url()),
        status: response.status(),
        durationMs: start ? Math.max(0, Date.now() - start.at) : null,
        requestPayload: start?.payload || null,
        response: pickResponseSummary(body, extractors, request.url()),
        at: new Date(start?.at || Date.now()).toISOString(),
        ...(bodyReadError ? { bodyReadError: bodyReadError.error } : {}),
      };
      records.push(record);
      if (response.status() >= 400) httpErrors.push(record);
    })();
    pending.add(task);
    task.finally(() => { pending.delete(task); lastActivityAt = Date.now(); });
  };

  const onRequestFinished = (request) => {
    if (activeRequests.delete(request)) lastActivityAt = Date.now();
  };

  const onRequestFailed = (request) => {
    if (!observedApi(request.url())) return;
    onRequestFinished(request);
    const errorText = request.failure()?.errorText || 'request failed';
    // Requests aborted by the mutation guard are represented in the guard's
    // blockedMutationAttempts collection, not duplicated as HTTP failures.
    if (/ERR_BLOCKED_BY_CLIENT|blockedbyclient/i.test(errorText)) return;
    failedRequests.push({
      method: request.method(),
      url: sanitizeUrl(request.url()),
      error: redactText(errorText, 160),
      at: new Date().toISOString(),
    });
  };

  context.on('request', onRequest);
  context.on('response', onResponse);
  context.on('requestfailed', onRequestFailed);
  context.on('requestfinished', onRequestFinished);

  return {
    records,
    httpErrors,
    failedRequests,
    async flush() {
      await Promise.allSettled([...pending]);
    },
    async settle({ quietMs = 1_000, timeoutMs = 15_000 } = {}) {
      const deadline = Date.now() + timeoutMs;
      // SPA login can mount its shell before the landing page starts its API
      // requests. Observe a quiet interval, not the document's old load state.
      lastActivityAt = Date.now();
      while (Date.now() < deadline) {
        if (!activeRequests.size && !pending.size && Date.now() - lastActivityAt >= quietMs) return;
        await new Promise(resolve => setTimeout(resolve, Math.min(50, Math.max(1, deadline - Date.now()))));
      }
      throw new Error(`API evidence did not settle within ${timeoutMs}ms (${activeRequests.size} requests, ${pending.size} bodies pending)`);
    },
    snapshot() {
      const duplicates = new Map();
      for (const record of records) {
        const key = normalizeDuplicateKey(record.method, record.url);
        duplicates.set(key, (duplicates.get(key) || 0) + 1);
      }
      return {
        apiEvidence: records.slice(),
        httpErrors: httpErrors.slice(),
        failedRequests: failedRequests.slice(),
        bodyReadErrors: bodyReadErrors.slice(),
        duplicateRequests: [...duplicates.entries()]
          .filter(([, count]) => count > 1)
          .map(([request, count]) => ({ request, count }))
          .sort((a, b) => b.count - a.count),
      };
    },
    dispose() {
      context.off('request', onRequest);
      context.off('response', onResponse);
      context.off('requestfailed', onRequestFailed);
      context.off('requestfinished', onRequestFinished);
    },
  };
}

module.exports = { installNetworkRecorder, normalizeDuplicateKey, pickResponseSummary };
