import type { WarehouseLayout, WarehouseLocation, WarehouseZone } from '@/api/warehouseLayout';

export const EMPTY_WAREHOUSE_LAYOUT: WarehouseLayout = { zones: [], locations: [], floors: [] };

export function normalizeWarehouseLayout(value: unknown): WarehouseLayout {
  if (!value || typeof value !== 'object') return structuredClone(EMPTY_WAREHOUSE_LAYOUT);
  const layout = value as Partial<WarehouseLayout>;
  return {
    zones: Array.isArray(layout.zones) ? layout.zones : [],
    locations: Array.isArray(layout.locations) ? layout.locations : [],
    floors: Array.isArray(layout.floors)
      ? layout.floors.map((floor) => ({ ...floor, racks: Array.isArray(floor.racks) ? floor.racks : [] }))
      : [],
  };
}

export function nextLocationCode(zone: WarehouseZone, locations: WarehouseLocation[]): string {
  const used = new Set(locations.filter((location) => location.zoneId === zone.id).map((item) => item.code));
  let index = 1;
  while (used.has(`${zone.prefix}${String(index).padStart(2, '0')}`)) index += 1;
  return `${zone.prefix}${String(index).padStart(2, '0')}`;
}

export function daysInStorage(date: string | undefined, now = Date.now()): number | null {
  if (!date) return null;
  const parsed = new Date(date).getTime();
  if (Number.isNaN(parsed)) return null;
  return Math.max(0, Math.floor((now - parsed) / 86_400_000));
}

export function normalizedLocationPrefix(code: string): string {
  return code.match(/^[A-Za-z]+/)?.[0].toUpperCase() ?? '';
}

/**
 * 库存数量展示格式化。
 *
 * 2026-08-25 生产实测: 现场盘点页把 `168.96999999999997`、`29.990000000000002`
 * 直接打给仓管 —— 那是 IEEE754 浮点误差, 不是真实精度, 而且撑破了卡片宽度
 * (scrollWidth 229 > clientWidth 227)。
 *
 * 这里只做「展示层」收敛: 最多 3 位小数并去掉尾随 0。⛔ 不改任何参与计算的值。
 */
export function formatQuantity(value: number): string {
  if (!Number.isFinite(value)) return '—';
  const rounded = Math.round(value * 1000) / 1000;
  return String(rounded);
}

/**
 * 一组批次里「最长在库天数」。
 *
 * ⛔ 全部批次都缺入库日期时返回 null(展示为 —), 不能回落成 0 ——
 * 「0 天」等于向仓管断言这批货今天入库, 那是编造的读数 (核心原则#1 禁止假数据)。
 * 只要有一条批次有日期, 就按有日期的那些算最大值。
 */
export function maxDaysInStorage(dates: Array<string | undefined>, now = Date.now()): number | null {
  const known = dates.map((date) => daysInStorage(date, now)).filter((age): age is number => age !== null);
  return known.length ? Math.max(...known) : null;
}
