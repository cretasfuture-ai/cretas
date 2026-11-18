import { fmtNumber } from '@/utils/format';

/** 成本汇总金额：¥ + 千分位 + 保留 2 位；缺失显示「—」，不改数据。 */
export function costMoney(v?: number | null): string {
  return v == null ? '—' : '¥' + fmtNumber(v, { digits: 2 });
}
