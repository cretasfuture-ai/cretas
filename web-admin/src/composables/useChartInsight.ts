/**
 * U4 — useChartInsight composable
 *
 * Single-chart reactive orchestrator: Tier1 (rules, 0 token) → null? → Tier2 (LLM/template).
 *
 * INTENDED USE: single-chart reactive faces only (gold/dashboard single-chart panels).
 * Array faces (SmartBIAnalysis N-chart loop) must use direct calls:
 *   deriveChartMeta + buildChartInsight + fetchTier2Insight
 * because Vue watchers must be called at the top level of setup(), not inside loops.
 *
 * Design contracts (spec §2 + plan U4):
 * 1. Tier1-first: watch source → buildChartInsight(chart, perms()) → insight set.
 *    Tier1 hit → NO Tier2 call.
 * 2. Tier2-on-null + loading: Tier1 returns null AND autoTier2=true →
 *    loading=true → await fetchTier2Insight → insight set (or null on failure).
 *    Failure/null → honest-null (诚实空), never fabricated data.
 * 3. stale-guard: capture token before await; if source() or factoryId() changed
 *    by the time await resolves → discard result silently.
 * 4. 2-slot module-level semaphore: max 2 concurrent Tier2 in-flight + ~50ms debounce.
 *    Phase A: 0-2 Tier2 calls in practice. 44-chart burst = Phase B (server-side throttle).
 * 5. perms fail-safe: if perms() throws or returns incomplete → canViewFinance=false
 *    (finance_hidden, never leaks finance data).
 */

import { ref, watch, type Ref } from 'vue';
import { fetchChartInsightStream } from '@/api/smartbi/chartInsightStream';
import {
  buildChartInsight,
  fetchTier2Insight,
  type InsightResult,
  type ChartWithMeta,
  type UserPermissions,
} from '@/views/smart-bi/components/chartInsight';

// ============================================================
// Module-level 2-slot semaphore (Phase A cost guard)
// ============================================================

/** Maximum concurrent Tier2 requests allowed across all composable instances. */
const MAX_CONCURRENT_TIER2 = 2;

/** Current number of in-flight Tier2 requests. */
let _inflight = 0;

/** Queue of resolver callbacks waiting for a slot. */
const _queue: Array<() => void> = [];

/**
 * Acquire a semaphore slot. Resolves immediately when slot is available,
 * otherwise queues until an in-flight request releases.
 */
function acquireSlot(): Promise<void> {
  if (_inflight < MAX_CONCURRENT_TIER2) {
    _inflight++;
    return Promise.resolve();
  }
  return new Promise<void>((resolve) => {
    _queue.push(resolve);
  });
}

/**
 * Release a semaphore slot. If callers are queued, wake one immediately.
 */
function releaseSlot(): void {
  const next = _queue.shift();
  if (next) {
    // A queued caller gets the slot; inflight count stays the same.
    next();
  } else {
    _inflight--;
  }
}

// ============================================================
// Debounce helper (per-instance timer)
// ============================================================

function debouncePromise(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/** Debounce delay in milliseconds (avoids rapid watch re-triggers). */
const DEBOUNCE_MS = 50;

// ============================================================
// Perms fail-safe helper
// ============================================================

/** Finance-hidden fallback when perms cannot be read safely. */
const PERMS_FALLBACK: UserPermissions = { canViewFinance: false };

function safePerms(perms: () => UserPermissions): UserPermissions {
  try {
    const p = perms();
    // If canViewFinance is not a boolean, fall back to safe default
    if (typeof p?.canViewFinance !== 'boolean') return PERMS_FALLBACK;
    return p;
  } catch {
    return PERMS_FALLBACK;
  }
}

// ============================================================
// Options / return types
// ============================================================

export interface UseChartInsightOpts {
  /** Override the factory ID getter (used in Tier2 request body). Defaults to empty string. */
  factoryId?: () => string;
  /**
   * Whether to automatically call Tier2 when Tier1 returns null.
   * Defaults to true.
   */
  autoTier2?: boolean;
  /** Use the authenticated insight service for charts that require server analysis. */
  requireTier2?: boolean;
  /** Use the progressive Blacklake endpoint with the displayed snapshot and filter range. */
  dataVersion?: () => string | undefined;
}

export interface UseChartInsightReturn {
  insight: Ref<InsightResult | null>;
  loading: Ref<boolean>;
  unavailableReason: Ref<string | null>;
}

// ============================================================
// Composable
// ============================================================

/**
 * useChartInsight — single-chart reactive insight orchestrator.
 *
 * @param source    Reactive getter returning `{ chart: ChartWithMeta } | null`.
 *                  Pass as `() => ({ chart: myChartRef.value })` or `() => null` to
 *                  skip. Watch is deep on the getter result.
 * @param perms     Reactive getter returning current UserPermissions.
 *                  If it throws or returns incomplete, defaults to finance_hidden.
 * @param opts      Optional: factoryId getter + autoTier2 flag (default true).
 *
 * @returns `{ insight, loading }` — both reactive Refs.
 */
export function useChartInsight(
  source: () => { chart: ChartWithMeta } | null,
  perms: () => UserPermissions,
  opts?: UseChartInsightOpts,
): UseChartInsightReturn {
  const autoTier2 = opts?.autoTier2 ?? true;
  const getFactoryId = () => {
    try { return opts?.factoryId?.() ?? ''; } catch { return ''; }
  };

  const insight = ref<InsightResult | null>(null);
  const loading = ref(false);
  const unavailableReason = ref<string | null>(null);

  // Stale-guard token: bumped whenever source or factoryId changes.
  // Any async path captures its token at start; on completion checks if still current.
  let _staleToken = 0;

  watch(
    // Watch source deeply so changes inside the chart object trigger re-evaluation.
    () => [source(), getFactoryId(), safePerms(perms).canViewFinance, opts?.dataVersion?.()] as const,
    async ([newSource, factoryId, , dataVersion], _previous, onCleanup) => {
      // Bump stale token — any previous in-flight request will see a mismatch.
      const token = ++_staleToken;
      const controller = dataVersion === undefined ? undefined : new AbortController();
      onCleanup(() => {
        _staleToken++;
        controller?.abort();
      });
      unavailableReason.value = null;

      if (!newSource) {
        insight.value = null;
        loading.value = false;
        return;
      }

      const { chart } = newSource;
      const currentPerms = safePerms(perms);

      // --- Tier 1: synchronous, 0 tokens ---
      const tier1 = opts?.requireTier2 ? null : buildChartInsight(chart, currentPerms);

      if (tier1 !== null) {
        // Tier1 hit — set immediately, no Tier2.
        insight.value = tier1;
        loading.value = false;
        return;
      }

      // --- Tier 1 null: proceed to Tier 2 if enabled ---
      if (!autoTier2) {
        insight.value = null;
        loading.value = false;
        return;
      }

      // Signal loading while we wait for slot + debounce + fetch.
      insight.value = null;
      loading.value = true;

      // Debounce: short delay to coalesce rapid watch triggers.
      await debouncePromise(DEBOUNCE_MS);

      // Stale check after debounce.
      if (_staleToken !== token) return;

      // Acquire a slot from the module-level 2-slot semaphore (may queue).
      await acquireSlot();

      // Stale check after potentially waiting in queue.
      if (_staleToken !== token) {
        releaseSlot();
        return;
      }

      try {
        // Re-read source and perms freshly — may have changed during queue wait.
        const freshSource = source();
        if (!freshSource || _staleToken !== token) {
          insight.value = null;
          loading.value = false;
          return;
        }

        const freshPerms = safePerms(perms);
        const result = controller && dataVersion !== undefined
          ? await fetchChartInsightStream(freshSource.chart, freshPerms, factoryId, dataVersion, preview => {
            if (_staleToken === token) insight.value = preview;
          }, controller.signal)
          : await fetchTier2Insight(freshSource.chart, freshPerms, factoryId);

        // Final stale check after await.
        if (_staleToken !== token) return;

        if (result === null) return;
        if ('reason' in result) {
          unavailableReason.value = result.reason;
          return;
        }
        insight.value = result;
      } catch {
        if (_staleToken === token) {
          unavailableReason.value = 'SERVICE_UNAVAILABLE';
        }
      } finally {
        releaseSlot();
        if (_staleToken === token) {
          loading.value = false;
        }
      }
    },
    { immediate: true, deep: true },
  );

  return { insight, loading, unavailableReason };
}
