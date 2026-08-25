import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';

export interface WarehouseLayoutZone {
  id: string;
  name: string;
  prefix: string;
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
  floors: Array<{
    id: string;
    name: string;
    racks: Array<{ id: string; zoneId: string; name: string; x: number; y: number; width: number; height: number; levels: number }>;
  }>;
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
        ? data.floors.map((floor) => ({ ...floor, racks: Array.isArray(floor.racks) ? floor.racks : [] }))
        : [],
    };
  },
};
