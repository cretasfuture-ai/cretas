import { isProcessSecretHidden } from '@/utils/processSecretField';

export interface CostSummaryRow {
  totalCost?: number | null;
  knownCostSubtotal?: number | null;
}

/**
 * 成本汇总表「总成本」列的合计。
 *
 * 与行内口径一致: 行显示已知合计, 合计也加已知合计; 任一行不完整就标「含待补」。
 * 工艺保密隐藏的行 (保密产品对无授权账号) ⛔ 不按 0 加进去, 也不能悄悄少算:
 * 合计标出「不含 N 单已保密」, 否则一个比真实小的数会被当成区间总成本。
 */
export function costSummaryTotal(rows: CostSummaryRow[], money: (v?: number | null) => string): string {
  let total = 0;
  let partial = false;
  let secret = 0;
  for (const row of rows) {
    if (isProcessSecretHidden(row, 'totalCost')) {
      secret += 1;
      continue;
    }
    total += row.totalCost ?? row.knownCostSubtotal ?? 0;
    if (row.totalCost == null && row.knownCostSubtotal != null) partial = true;
  }
  const notes: string[] = [];
  if (partial) notes.push('含待补');
  if (secret > 0) notes.push(`不含 ${secret} 单已保密`);
  return notes.length ? `${money(total)}（${notes.join('，')}）` : money(total);
}
