export function formatReportCost(totalCost: unknown): string {
  if ((typeof totalCost !== 'number' && typeof totalCost !== 'string')
      || (typeof totalCost === 'string' && totalCost.trim() === '')) {
    return '成本缺失';
  }

  const cost = Number(totalCost);
  return Number.isFinite(cost) ? `¥${cost.toFixed(2)}` : '成本缺失';
}
