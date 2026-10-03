import { useCallback, useEffect, useRef, useState } from 'react';
import { listSummaryApiClient } from '../services/api/listSummaryApiClient';
import type {
  ListSummaryRequest,
  ListSummaryResponse,
  SupportedSummaryEntityType,
} from '../types/listSummary';

interface UseListSummaryResult {
  summary: ListSummaryResponse | null;
  loading: boolean;
  error: Error | null;
  refresh: () => Promise<void>;
}

/**
 * Sprint 2 Track I — fetches list-summary stats for the bottom sticky footer.
 *
 * Refetches whenever `request` reference changes; caller should memoize.
 * Optionally pass `factoryId` for platform admins viewing cross-factory data.
 */
export function useListSummary(
  entityType: SupportedSummaryEntityType | string,
  request: ListSummaryRequest = {},
  factoryId?: string,
): UseListSummaryResult {
  const contextRef = useRef({ entityType, factoryId });
  if (contextRef.current.entityType !== entityType || contextRef.current.factoryId !== factoryId) {
    contextRef.current = { entityType, factoryId };
  }
  const context = contextRef.current;
  const [summary, setSummary] = useState<ListSummaryResponse | null>(null);
  const summaryOwner = useRef<{ context: typeof context; request: ListSummaryRequest } | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<Error | null>(null);
  const reqRef = useRef(request);
  reqRef.current = request;
  const requestSequence = useRef(0);
  const active = useRef(true);

  const refresh = useCallback(async () => {
    // Retained callbacks may outlive a factory/entity switch.
    if (!active.current || context !== contextRef.current) return;
    const sequence = ++requestSequence.current;
    const currentRequest = reqRef.current;
    const isCurrent = () => active.current && context === contextRef.current && sequence === requestSequence.current
      && currentRequest === reqRef.current;
    setLoading(true);
    setError(null);
    try {
      const envelope = await listSummaryApiClient.getSummary(entityType, reqRef.current, factoryId);
      if (!envelope.success) {
        throw new Error(envelope.message ?? '获取合计失败');
      }
      if (isCurrent()) {
        summaryOwner.current = { context, request: currentRequest };
        setSummary(envelope.data);
      }
    } catch (e) {
      if (isCurrent()) {
        setError(e instanceof Error ? e : new Error(String(e)));
        setSummary(null);
      }
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [context, entityType, factoryId]);

  useEffect(() => {
    active.current = true;
    setSummary(null);
    void refresh();
    return () => { active.current = false; ++requestSequence.current; };
  }, [refresh, request]);

  const visibleSummary = summaryOwner.current?.context === context
    && summaryOwner.current.request === request ? summary : null;
  return { summary: visibleSummary, loading, error, refresh };
}
