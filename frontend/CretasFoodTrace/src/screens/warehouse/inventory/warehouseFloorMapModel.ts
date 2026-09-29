import type { WarehouseMapAnnotation, WarehouseMapFloor } from '../../../services/api/warehouseLayoutApiClient';

export function warehouseMapExtent(floor: WarehouseMapFloor): { width: number; height: number } {
  const rectangles = [...floor.racks, ...(floor.zoneShapes ?? []), ...(floor.annotations ?? [])];
  const right = rectangles.reduce((max, item) => Math.max(max, item.x + item.width), 0);
  const bottom = rectangles.reduce((max, item) => Math.max(max, item.y + item.height), 0);
  return { width: Math.max(900, Math.ceil(right + 40)), height: Math.max(420, Math.ceil(bottom + 40)) };
}

/** Web stores polygon/freehand points relative to the annotation's top-left corner. */
export function warehouseAnnotationPoints(annotation: WarehouseMapAnnotation): string {
  const points = annotation.points ?? [];
  const pairs: string[] = [];
  for (let index = 0; index + 1 < points.length; index += 2) {
    const x = points[index];
    const y = points[index + 1];
    if (x !== undefined && y !== undefined && Number.isFinite(x) && Number.isFinite(y)) {
      pairs.push(`${annotation.x + x},${annotation.y + y}`);
    }
  }
  return pairs.join(' ');
}
