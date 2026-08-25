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
