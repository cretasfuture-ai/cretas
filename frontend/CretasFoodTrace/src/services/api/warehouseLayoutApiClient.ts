import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

export interface WarehouseLayoutZone {
  id: string;
  name: string;
  prefix: string;
  color?: string;
}

export interface WarehouseMapRect { x: number; y: number; width: number; height: number }
export interface WarehouseZoneShape extends WarehouseMapRect { zoneId: string }
export interface WarehouseMapAnnotation extends WarehouseMapRect {
  id: string;
  kind: 'rect' | 'ellipse' | 'polygon' | 'freehand' | 'text';
  points?: number[];
  text?: string;
  color?: string;
  fill?: string;
  strokeWidth?: number;
  fontSize?: number;
}
export interface WarehouseMapFloor {
  id: string;
  name: string;
  racks: Array<{ id: string; zoneId: string; name: string; x: number; y: number; width: number; height: number; levels: number }>;
  zoneShapes?: WarehouseZoneShape[];
  annotations?: WarehouseMapAnnotation[];
  background?: string;
}

export interface WarehouseLayoutLocation {
  id: string;
  zoneId: string;
  code: string;
  name: string;
  notes?: string;
  rackId?: string;
  level?: number;
}

export interface WarehouseLayoutConfiguration {
  zones: WarehouseLayoutZone[];
  locations: WarehouseLayoutLocation[];
  floors: WarehouseMapFloor[];
}

export const warehouseLayoutApiClient = {
  async getLayout(factoryId?: string): Promise<WarehouseLayoutConfiguration> {
    const response = await apiClient.get<{ success: boolean; data: Partial<WarehouseLayoutConfiguration> }>(
      `/api/mobile/${requireFactoryId(factoryId)}/warehouse/layout`,
    );
    const data = response.data ?? {};
    return {
      zones: Array.isArray(data.zones) ? data.zones : [],
      locations: Array.isArray(data.locations) ? data.locations : [],
      floors: Array.isArray(data.floors)
        ? data.floors.map((floor) => ({
            ...floor,
            racks: Array.isArray(floor.racks) ? floor.racks : [],
            zoneShapes: Array.isArray(floor.zoneShapes) ? floor.zoneShapes : [],
            annotations: Array.isArray(floor.annotations) ? floor.annotations : [],
          }))
        : [],
    };
  },
};
