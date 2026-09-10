import type { ApplyDiffResult, ApplyDiffsResult } from '@/api/canvasApi';

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** A malformed/legacy receipt cannot prove which writes happened. */
export function readApplyChangesResult(value: unknown, count: number): ApplyDiffsResult {
  const unknown = (): ApplyDiffsResult => ({
    operationId: '', total: count, applied: 0, reused: 0,
    items: Array.from({ length: count }, (_, index) => ({
      index, status: 'UNKNOWN', message: '执行结果不明，请核对实际记录，勿重复执行',
    })),
  });
  if (!isRecord(value) || typeof value.operationId !== 'string' || !value.operationId.trim()
    || value.total !== count || !Array.isArray(value.items) || value.items.length !== count) return unknown();
  const items: ApplyDiffResult[] = [];
  const seen = new Set<number>();
  for (const item of value.items) {
    if (!isRecord(item) || typeof item.index !== 'number' || !Number.isInteger(item.index)
      || item.index < 0 || item.index >= count || seen.has(item.index)
      || typeof item.message !== 'string' || typeof item.status !== 'string'
      || !['SUCCESS', 'REUSED', 'FAILURE', 'UNKNOWN'].includes(item.status)) return unknown();
    seen.add(item.index);
    items.push({ index: item.index, status: item.status as ApplyDiffResult['status'], message: item.message });
  }
  const applied = items.filter(item => item.status === 'SUCCESS').length;
  const reused = items.filter(item => item.status === 'REUSED').length;
  if (value.applied !== applied || value.reused !== reused) return unknown();
  return { operationId: value.operationId, total: count, applied, reused, items: items.sort((a, b) => a.index - b.index) };
}

export function applyChangesSummary(result: ApplyDiffsResult): string {
  return result.items.map(item => `第 ${item.index + 1} 项：${item.message}`).join('；')
    + (result.operationId ? `（操作编号：${result.operationId}）` : '');
}
