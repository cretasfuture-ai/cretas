/**
 * 缺口 07 物料平衡表的「工序」列: 工序名为空时不留白, 用「第 N 道」兜底;
 * 连顺序号也没有就写「未命名工序」(如实标出, 不猜)。
 */
export function balanceProcessLabel(name: string | null | undefined, processOrder: number | null | undefined): string {
  const trimmed = (name ?? '').trim();
  if (trimmed) return trimmed;
  if (processOrder != null && Number.isFinite(processOrder) && processOrder > 0) return `第 ${processOrder} 道`;
  return '未命名工序';
}
