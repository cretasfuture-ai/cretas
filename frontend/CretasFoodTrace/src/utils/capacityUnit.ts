/**
 * 产品计量单位 → 插单产能核对用的单位代码 (后端只认 PIECE / KG / BATCH)。
 *
 * Owner 2026-10-28 裁定: 订单数量按产品自带的单位算; 认不出来的单位 (盒 / 袋 / 斤 …)
 * 返回 null, 由后端如实判「产能未验证」—— ⛔ 不猜、不换算、不默认成 kg。
 * 与对话建厂解析产线产能时认的写法一致: 件/个 → PIECE, kg/公斤/千克 → KG, 批 → BATCH。
 */
export type CapacityUnitCode = 'PIECE' | 'KG' | 'BATCH';

export function toCapacityUnit(unit: string | null | undefined): CapacityUnitCode | null {
  const u = (unit ?? '').trim().toLowerCase();
  if (u === '件' || u === '个' || u === 'piece') return 'PIECE';
  if (u === 'kg' || u === '公斤' || u === '千克') return 'KG';
  if (u === '批' || u === 'batch') return 'BATCH';
  return null;
}
