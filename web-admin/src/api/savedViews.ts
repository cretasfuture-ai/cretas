import { del, get, post, put } from './request';
import type { ApiResponse } from '@/types/api';

export type ViewFieldType = 'string' | 'number' | 'boolean';
export type ViewOp = 'eq' | 'ne' | 'contains' | 'gt' | 'gte' | 'lt' | 'lte';

export interface ViewFilter { field: string; op: ViewOp; value: string }

/** 保存的是查询定义，不是结果行。 */
export interface ViewDefinition {
  entity: string;
  columns: string[];
  filters: ViewFilter[];
  sort?: { field: string; direction: 'asc' | 'desc' } | null;
  limit?: number | null;
}

export interface SavedViewSummary {
  id: string;
  name: string;
  entity: string;
  version: number;
  shared: boolean;
  mine: boolean;
  ownerUserId: number;
  definition: ViewDefinition;
  updatedAt: string;
}

export interface ViewExecuteResult {
  viewId: string;
  name: string;
  version: number;
  entity: string;
  columns: string[];
  hiddenColumns: string[];
  rows: Array<Record<string, string | number | boolean | null>>;
  rowCount: number;
  truncated: boolean;
  executedAt: string;
}

export interface ViewFieldOption { name: string; label: string; type: ViewFieldType; allowed: boolean; ops: ViewOp[] }
export interface ViewEntityOption { key: string; label: string; allowed: boolean; fields: ViewFieldOption[] }

export interface SaveViewRequest {
  name?: string;
  definition?: ViewDefinition;
  shared?: boolean;
  version?: number;
}

const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9._:-]{0,190}$/;

function base(factoryId: string): string {
  if (!SAFE_ID.test(factoryId)) throw new Error('FACTORY_ID_REQUIRED');
  return `/${encodeURIComponent(factoryId)}/saved-views`;
}

export const getViewOptions = (factoryId: string): Promise<ApiResponse<ViewEntityOption[]>> =>
  get(`${base(factoryId)}/options`);

export const listSavedViews = (factoryId: string): Promise<ApiResponse<SavedViewSummary[]>> =>
  get(base(factoryId));

export const createSavedView = (factoryId: string, body: SaveViewRequest): Promise<ApiResponse<SavedViewSummary>> =>
  post(base(factoryId), body);

export const updateSavedView = (
  factoryId: string, id: string, body: SaveViewRequest,
): Promise<ApiResponse<SavedViewSummary>> => put(`${base(factoryId)}/${encodeURIComponent(id)}`, body);

export const deleteSavedView = (factoryId: string, id: string): Promise<ApiResponse<null>> =>
  del(`${base(factoryId)}/${encodeURIComponent(id)}`);

/** 每次打开都由服务端按当前账号权限重算。 */
export const executeSavedView = (factoryId: string, id: string): Promise<ApiResponse<ViewExecuteResult>> =>
  get(`${base(factoryId)}/${encodeURIComponent(id)}/execute`);
