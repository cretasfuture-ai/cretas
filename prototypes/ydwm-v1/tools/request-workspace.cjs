'use strict';

const fs = require('node:fs');
const { AsyncLocalStorage } = require('node:async_hooks');

const storage = new AsyncLocalStorage();
const persistTracker = Symbol('ydwm-request-workspace-persist-tracker');

function statFingerprint(file) {
  if (!file) return { exists: false };
  try {
    const stat = fs.statSync(file, { bigint: true });
    return {
      exists: true,
      dev: String(stat.dev),
      ino: String(stat.ino),
      mode: String(stat.mode),
      size: String(stat.size),
      mtimeNs: String(stat.mtimeNs),
      ctimeNs: String(stat.ctimeNs),
    };
  } catch (error) {
    if (error && error.code === 'ENOENT') return { exists: false };
    throw error;
  }
}

function fingerprint(workspace) {
  return JSON.stringify({
    state: statFingerprint(workspace.file),
    shared: statFingerprint(workspace.sharedFile),
    source: statFingerprint(workspace.baselineFile),
    sourceOverlay: statFingerprint(workspace.sourceOverlayFile),
  });
}

function trackPersist(workspace) {
  if (workspace[persistTracker] || typeof workspace.persist !== 'function') return;
  const original = workspace.persist;
  const tracker = { revision: 0 };
  workspace[persistTracker] = tracker;
  workspace.persist = function trackedPersist(...args) {
    tracker.revision += 1;
    return original.apply(this, args);
  };
}

function persistRevision(workspace) {
  return workspace[persistTracker]?.revision || 0;
}

function reload(workspace, refreshSource) {
  // Preserve Workspace.refreshSource's existing baseline-mtime rebuild trigger.
  // Fingerprints invalidate this request's memo; they do not change that source
  // contract or promise that an overlay-only edit rebuilds source models.
  if (refreshSource && typeof workspace.refreshSource === 'function') workspace.refreshSource();
  if (fs.existsSync(workspace.file)) {
    workspace.state = JSON.parse(fs.readFileSync(workspace.file, 'utf8'));
  }
  workspace.syncShared();
  return workspace;
}

/** Run work with a request-local workspace hydration context. */
function run(work) {
  if (typeof work !== 'function') throw new TypeError('request workspace run() requires a function');
  return storage.run({ workspaces: new Map() }, work);
}

/**
 * Hydrate a cached Workspace once per request, unless its files or persistence
 * revision changed. Outside a request context this intentionally preserves the
 * old read-and-sync-on-every-call behavior.
 */
function hydrate(workspace) {
  if (!workspace || typeof workspace !== 'object') throw new TypeError('hydrate() requires a Workspace');
  trackPersist(workspace);
  if (workspace.batchDepth) return workspace;

  const context = storage.getStore();
  if (!context) return reload(workspace, true);

  const revision = persistRevision(workspace);
  const before = fingerprint(workspace);
  const previous = context.workspaces.get(workspace);
  if (previous && previous.fingerprint === before && previous.persistRevision === revision) return workspace;

  // Never mark old in-memory state with a newer on-disk fingerprint. A writer
  // can replace a file during hydration; retry a bounded number of times and
  // report contention explicitly rather than caching a mixed snapshot.
  let expected = before;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    reload(workspace, true);
    const after = fingerprint(workspace);
    if (expected === after) {
      context.workspaces.set(workspace, {
        fingerprint: after,
        persistRevision: persistRevision(workspace),
      });
      return workspace;
    }
    expected = after;
  }
  context.workspaces.delete(workspace);
  throw new Error('工作区在读取期间持续变化，请刷新后重试。');
}

module.exports = { run, hydrate, fingerprint };
