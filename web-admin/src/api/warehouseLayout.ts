import { get, post, put } from './request';

export interface WarehouseZone {
  id: string;
  name: string;
  prefix: string;
  color?: string;
}

export interface WarehouseLocation {
  id: string;
  zoneId: string;
  code: string;
  name: string;
  notes?: string;
  rackId?: string;
  level?: number;
}

export interface WarehouseRack {
  id: string;
  zoneId: string;
  name: string;
  x: number;
  y: number;
  width: number;
  height: number;
  levels: number;
}

export interface WarehouseFloor {
  id: string;
  name: string;
  racks: WarehouseRack[];
  background?: string;
}

export interface WarehouseLayout {
  zones: WarehouseZone[];
  locations: WarehouseLocation[];
  floors: WarehouseFloor[];
}

export interface RecognizedWarehouseLayout {
  zones?: Array<WarehouseZone & { x?: number; y?: number; width?: number; height?: number }>;
  racks?: WarehouseRack[];
  confidence?: number;
}

export function getWarehouseLayout(factoryId: string) {
  return get<WarehouseLayout>(`/${factoryId}/warehouse/layout`);
}

export function saveWarehouseLayout(factoryId: string, layout: WarehouseLayout) {
  return put<WarehouseLayout>(`/${factoryId}/warehouse/layout`, layout);
}

export function recognizeWarehouseLayout(factoryId: string, imageBase64: string, contentType: string) {
  return post<RecognizedWarehouseLayout>(`/${factoryId}/warehouse/layout/recognize`, {
    imageBase64,
    contentType,
  });
}
