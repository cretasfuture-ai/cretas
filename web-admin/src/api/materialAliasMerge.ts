/**
 * 物料别名 + 合并重复物料 (raw-material-types)
 *
 * 背景: 新接入的食品厂反馈「配方名称和系统名称对不上」「同类品牌多, 要整合」。
 * 后端新增别名表与合并动作; 被合并物料有库存 / 在用配方 / 工艺图 / 有效供应商关系 /
 * 未结采购单时合并被拦 (blockers), 前端先查 merge-check 把边界摆出来, 不等提交后报错。
 */
import { get, post, del } from '@/api/request';

export type MaterialAliasSource = 'MANUAL' | 'MERGE';

export interface MaterialAliasItem {
  id: string;
  alias: string;
  source: MaterialAliasSource;
}

/** 列表/详情行里与别名、合并有关的只读字段 (后端 RawMaterialTypeDTO, camelCase)。 */
export interface RawMaterialTypeAliasFields {
  aliases?: string[];
  mergedIntoId?: string | null;
  mergedIntoName?: string | null;
}

/** 别名/合并弹窗用到的物料行最小形状。 */
export interface RawMaterialTypeRow extends RawMaterialTypeAliasFields {
  id: string;
  code?: string;
  name: string;
  unit?: string;
  category?: string;
  isActive?: boolean;
}

export interface MaterialBrief {
  id: string;
  code: string;
  name: string;
  unit: string;
  mainCategory: string;
}

export type MaterialMergeBlockerCode =
  | 'STOCK'
  | 'BOM'
  | 'WORKFLOW'
  | 'DRAFT_WORKFLOW'
  | 'SUPPLIER'
  | 'PURCHASE_ORDER'
  | 'OPEN_PURCHASE_ORDER'
  | 'CATEGORY';

export interface MaterialMergeBlocker {
  code: MaterialMergeBlockerCode;
  message: string;
  items: string[];
  hint: string;
}

export interface MaterialMergeReplacement {
  code: 'WORKFLOW' | 'SUPPLIER' | 'PURCHASE_ORDER';
  message: string;
  items: string[];
}

export interface MaterialMergeCheck {
  canMerge: boolean;
  replacements: MaterialMergeReplacement[];
  duplicate: MaterialBrief;
  master: MaterialBrief;
  blockers: MaterialMergeBlocker[];
  warnings: string[];
  aliasesToAdd: string[];
}

export interface MaterialMergeReplaced {
  draftWorkflows: number;
  suppliers: number;
  purchaseOrderItems: number;
  notes: string[];
}

export interface MaterialMergeResult {
  replaced?: MaterialMergeReplaced;
  duplicateId: string;
  masterId: string;
  aliasesAdded: string[];
}

function base(factoryId: string, id: string): string {
  return `/${factoryId}/raw-material-types/${encodeURIComponent(id)}`;
}

export function listMaterialAliases(factoryId: string, id: string) {
  return get<MaterialAliasItem[]>(`${base(factoryId, id)}/aliases`);
}

export function addMaterialAlias(factoryId: string, id: string, alias: string) {
  return post<MaterialAliasItem>(`${base(factoryId, id)}/aliases`, { alias });
}

export function deleteMaterialAlias(factoryId: string, id: string, aliasId: string) {
  return del(`${base(factoryId, id)}/aliases/${encodeURIComponent(aliasId)}`);
}

export function checkMaterialMerge(factoryId: string, id: string, masterId: string) {
  return get<MaterialMergeCheck>(`${base(factoryId, id)}/merge-check`, { params: { masterId } });
}

export function mergeMaterial(factoryId: string, id: string, masterId: string, reason?: string) {
  return post<MaterialMergeResult>(`${base(factoryId, id)}/merge`, { masterId, reason });
}
