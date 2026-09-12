'use strict';

const { redactText } = require('./sanitizer');

const DEFAULT_NOISE_RULES = [
  { id: 'favicon', pattern: /favicon\.ico/i },
  { id: 'resize-observer', pattern: /ResizeObserver loop/i },
  { id: 'browser-extension', pattern: /chrome-extension:\/\//i },
  { id: 'devtools-prompt', pattern: /(?:Vue|React) Devtools/i },
  {
    id: 'capability-gradual-rollout-503',
    pattern: /Failed to load resource: the server responded with a status of 503/i,
    locationPattern: /\/smartbi-api\/api\/smartbi\/capability\/[^/?#]+(?:[?#]|$)/i,
  },
];

function matchesNoiseRule(rule, text, location) {
  return rule.pattern.test(text)
    && (!rule.locationPattern || rule.locationPattern.test(location));
}

function installConsoleRecorder(context, options = {}) {
  const noiseRules = options.noiseRules || DEFAULT_NOISE_RULES;
  const raw = [];
  const accepted = [];
  const listeners = new Map();

  function record(type, text, location = '') {
    const safeText = redactText(text, 500);
    const safeLocation = redactText(location, 300);
    const matched = noiseRules.find((rule) => matchesNoiseRule(rule, safeText, safeLocation));
    const item = {
      type,
      text: safeText,
      location: safeLocation,
      at: new Date().toISOString(),
      filtered: Boolean(matched),
      filterRule: matched?.id || null,
    };
    raw.push(item);
    if (!matched) accepted.push(item);
  }

  function attach(page) {
    if (listeners.has(page)) return;
    const onConsole = (message) => {
      if (!['error', 'warning'].includes(message.type())) return;
      record(message.type(), message.text(), message.location()?.url || '');
    };
    const onPageError = (error) => record('pageerror', error?.message || String(error));
    page.on('console', onConsole);
    page.on('pageerror', onPageError);
    listeners.set(page, { onConsole, onPageError });
  }

  // Playwright surfaces most unhandled rejections as pageerror. This bridge
  // gives them a stable label without storing the rejected object.
  context.addInitScript(() => {
    // Browser-generated ErrorEvents (for example ResizeObserver delivery errors)
    // may not reach Playwright's pageerror. Classify locally: never emit raw
    // messages, source URLs, stacks or error objects from this bridge.
    window.addEventListener('error', (event) => {
      const message = typeof event.message === 'string' ? event.message : '';
      const kind = message === 'ResizeObserver loop completed with undelivered notifications.'
        || message === 'ResizeObserver loop limit exceeded'
        ? 'resize_observer_delivery'
        : message === 'Script error.' ? 'opaque_script_error' : 'other_window_error';
      console.error(`[window_error_class] ${kind}`);
    });
    window.addEventListener('unhandledrejection', (event) => {
      const reason = event.reason instanceof Error ? event.reason.message : String(event.reason || 'unknown');
      console.error(`[unhandledrejection] ${reason}`);
    });
  });
  for (const page of context.pages()) attach(page);
  context.on('page', attach);

  return {
    snapshot() {
      return {
        rawCount: raw.length,
        filteredCount: raw.length - accepted.length,
        retainedCount: accepted.length,
        consoleErrors: accepted.filter((item) => item.type === 'error'),
        consoleWarnings: accepted.filter((item) => item.type === 'warning'),
        pageErrors: accepted.filter((item) => item.type === 'pageerror'),
        filteredNoise: raw.filter((item) => item.filtered),
      };
    },
    dispose() {
      context.off('page', attach);
      for (const [page, handlers] of listeners) {
        page.off('console', handlers.onConsole);
        page.off('pageerror', handlers.onPageError);
      }
      listeners.clear();
    },
  };
}

module.exports = { DEFAULT_NOISE_RULES, matchesNoiseRule, installConsoleRecorder };
