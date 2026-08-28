// Canvas-Thresholds Phase A — 阈值参数中心 API client (2026-05-21).
//
// Backend: CanvasThresholdsController @RequestMapping "/api/mobile/{factoryId}/canvas-thresholds"
//   GET    /                            — list (optional ?category=)
//   GET    /{key}                       — get single by thresholdKey
//   POST   /                            — create new
//   PUT    /{id}                        — update (PATCH semantics, Map body)
//   DELETE /{id}                        — soft delete
//   POST   /reset-to-default?category=  — batch reset to default within a category
//
// axios baseURL = '/api/mobile' so caller-side URLs start at "/{factoryId}/...".
import { get, post, put, del } from './request';

// ==================== Types ====================

/**
 * 阈值分类 — 2026-08-28 由 6 类扩到 15 类, 对应各 service 类的 hard-coded 常量.
 * 后 9 个 (SALES..EFFICIENCY) 与后端 ThresholdCategory 枚举扩容同步 (backend PR 并行进行中)。
 */
export type ThresholdCategory =
  | 'INVENTORY' | 'IOT' | 'CREDIT' | 'BOM' | 'SHORTAGE' | 'AI'
  | 'SALES' | 'PURCHASE' | 'PRICING' | 'PRODUCTION' | 'PROCUREMENT'
  | 'QUALITY' | 'FINANCE' | 'PROCESSING' | 'EFFICIENCY';

/** Display labels for category — used by sub-tabs / breadcrumbs. */
export const ThresholdCategoryLabels: Record<ThresholdCategory, string> = {
  INVENTORY: '库存健康',
  IOT: 'IoT 冷链/环境',
  CREDIT: '客户信用',
  BOM: 'BOM 递归',
  SHORTAGE: '缺料分析',
  AI: 'AI 路由',
  SALES: '销售',
  PURCHASE: '采购',
  PRICING: '定价',
  PRODUCTION: '生产',
  PROCUREMENT: '采购分析',
  QUALITY: '质量',
  FINANCE: '财务',
  PROCESSING: '加工',
  EFFICIENCY: '人效',
};

/** 阈值值类型 — 决定 thresholdValue 字符串如何被运行时解析. */
export type ThresholdValueType = 'INTEGER' | 'DECIMAL' | 'DOUBLE' | 'STRING';

/** FactoryThreshold entity — 后端 entity 字段对齐. */
export interface FactoryThreshold {
  id: string;
  factoryId: string;
  thresholdKey: string;
  category: ThresholdCategory;
  valueType: ThresholdValueType;
  /** 当前值 (字符串形式; 按 valueType 解析). */
  thresholdValue: string;
  /** Service 层 hard-coded baseline (用于 reset). */
  defaultValue: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled: boolean;
  version: number;
  createdAt?: string;
  updatedAt?: string;
  /**
   * 🔴 后端标记：这一行是从全局 `'*'` 默认值继承来的，本厂还没有自己的 override。
   *
   * ⛔ **它的 `id` 属于全局那一行 —— 对它 PUT 会改掉全局默认值，影响所有工厂。**
   * 保存时必须改走 {@link createThreshold} 建一条本厂 override。
   *
   * 背景（2026-08-29 生产实测）：列表接口原来严格按 factoryId 过滤，
   * 全局行在任何工厂页面上都看不见 —— `LIUSHANMEN`(真实客户) 与 `MOCK_REST` 各 0 行，
   * 管理员看到 15 个空分类，而解析器一直在用全局默认值跑。
   */
  inherited?: boolean;
}

/** Create payload — 必填字段. */
export interface CreateThresholdRequest {
  thresholdKey: string;
  category: ThresholdCategory;
  valueType: ThresholdValueType;
  thresholdValue: string;
  defaultValue: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled?: boolean;
}

/** Update payload — PATCH 语义, 任何字段可选 + version 用于乐观锁. */
export interface UpdateThresholdRequest {
  thresholdValue?: string;
  defaultValue?: string;
  description?: string;
  unit?: string;
  minValue?: string | null;
  maxValue?: string | null;
  enabled?: boolean;
  /** 客户端提交时携带的 version 字段, 用于 AUD-4 乐观锁检查. */
  version?: number;
}

// ==================== API methods ====================

const base = (factoryId: string) => `/${factoryId}/canvas-thresholds`;

/** GET / — list (optional category filter). */
export async function listThresholds(
  factoryId: string,
  category?: ThresholdCategory,
): Promise<FactoryThreshold[]> {
  if (!factoryId) return [];
  const url = category
    ? `${base(factoryId)}?category=${encodeURIComponent(category)}`
    : base(factoryId);
  const res = await get<FactoryThreshold[]>(url);
  return res.success && Array.isArray(res.data) ? res.data : [];
}

/** GET /{key} — get single threshold by thresholdKey. */
export async function getThreshold(
  factoryId: string,
  thresholdKey: string,
): Promise<FactoryThreshold> {
  const res = await get<FactoryThreshold>(
    `${base(factoryId)}/${encodeURIComponent(thresholdKey)}`,
  );
  if (!res.success) {
    throw new Error(res.message || '查询阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** POST / — create new threshold. */
export async function createThreshold(
  factoryId: string,
  payload: CreateThresholdRequest,
): Promise<FactoryThreshold> {
  const res = await post<FactoryThreshold>(base(factoryId), payload);
  if (!res.success) {
    throw new Error(res.message || '创建阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** PUT /{id} — update existing (PATCH semantics). */
export async function updateThreshold(
  factoryId: string,
  id: string,
  payload: UpdateThresholdRequest,
): Promise<FactoryThreshold> {
  const res = await put<FactoryThreshold>(`${base(factoryId)}/${id}`, payload);
  if (!res.success) {
    throw new Error(res.message || '更新阈值失败');
  }
  return res.data as FactoryThreshold;
}

/** DELETE /{id} — soft delete. */
export async function deleteThreshold(factoryId: string, id: string): Promise<void> {
  const res = await del<void>(`${base(factoryId)}/${id}`);
  if (!res.success) {
    throw new Error(res.message || '删除阈值失败');
  }
}

/** POST /reset-to-default?category= — batch reset within a category. Returns updated count. */
export async function resetCategoryToDefault(
  factoryId: string,
  category: ThresholdCategory,
): Promise<number> {
  const res = await post<number>(
    `${base(factoryId)}/reset-to-default?category=${encodeURIComponent(category)}`,
    null,
  );
  if (!res.success) {
    throw new Error(res.message || '重置阈值失败');
  }
  return typeof res.data === 'number' ? res.data : 0;
}
