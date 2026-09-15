import { del, get, post, put } from './request';
import type { LineageNode } from './traceability';

/**
 * 源头个体档案 —— 溯源链最前端有生命周期的个体(牛/鸭/猪/…)。
 *
 * 后端: `/api/mobile/{factoryId}/source-individuals`
 * (axios baseURL 已是 `/api/mobile`, 所以这里的路径从 `/{factoryId}/...` 开始)
 *   GET /{factoryId}/source-individuals/{id}/detail   (系谱 + 去向, #807)
 *
 * ⚠️ 物种是**字段值** `species`, 不是模型名 —— ⛔ 不要在这一层长出物种专属字段。
 */

export type DnaVerificationStatus = 'UNSAMPLED' | 'SAMPLED' | 'MATCHED' | 'MISMATCH';

export const DNA_STATUS_LABELS: Record<DnaVerificationStatus, string> = {
  UNSAMPLED: '未采样',
  SAMPLED: '已采样',
  MATCHED: '已比中',
  MISMATCH: '不符',
};

export interface SourceIndividual {
  id: string;
  factoryId: string;
  individualCode: string;
  /** 物理标识(耳标/脚环/芯片号)。⚠️ **不唯一**: 脱落重挂、跨场引进都会撞号。 */
  tagNumber?: string | null;
  species?: string | null;
  breed?: string | null;
  sex?: string | null;
  birthDate?: string | null;
  originFarm?: string | null;
  externalGeneBankId?: string | null;
  dnaSampleCode?: string | null;
  dnaVerificationStatus?: DnaVerificationStatus | null;
  dnaVerificationStatusDisplayName?: string | null;
  /** 系谱: 父本 / 母本个体 ID (同表自引用, 可空)。 */
  sireIndividualId?: string | null;
  damIndividualId?: string | null;
  extAttributes?: Record<string, unknown> | null;
  notes?: string | null;
  createdAt?: string;
  updatedAt?: string;
  version?: number;
}

export interface SourceIndividualPage {
  content: SourceIndividual[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

// ── 详情 (系谱 + 去向) ──────────────────────────────────────────────────────
// 形状逐字对着手机端 `services/api/sourceIndividualApiClient.ts` 抄, ⛔ 不自己发明字段名。

/** 系谱行上另一头个体的摘要 (父本 / 母本 / 后代 / 同父兄弟)。 */
export interface SourceIndividualSummary {
  id: string;
  individualCode: string;
  tagNumber?: string | null;
  species?: string | null;
  breed?: string | null;
  sex?: string | null;
  dnaVerificationStatus?: DnaVerificationStatus | null;
}

/** 挂了该个体的原料批次 —— 「它去了哪」的第一跳。 */
export interface SourceIndividualBatchSummary {
  id: string;
  batchNumber: string;
  /** 物料名; 后端查不到物料类型时为 null (⛔ 前端不编「未知物料」)。 */
  materialName?: string | null;
  quantity?: number | null;
  unit?: string | null;
  receiptDate?: string | null;
}

/**
 * 下游三组, 元素与溯源血缘节点同一个形状 (`LineageNode`)。
 * 三个数组**永远是数组**, 没有就是空的。
 */
export interface SourceIndividualDownstream {
  productionBatches: LineageNode[];
  finishedBatches: LineageNode[];
  shipments: LineageNode[];
}

/**
 * 个体详情: 本体 + 系谱 + 去向。
 *
 * ⚠️ 三种「没有」分开读:
 *   · `sire`/`dam` 为 null 且 `individual.sireIndividualId`/`damIndividualId` 也为 null ⇒ 未登记
 *   · `sire`/`dam` 为 null 而对应 id 非空 ⇒ 登记过、档案已不可查(软删)
 *   · `offspring` / `siblingsBySire` / `batches` / `downstream.*` 空数组 ⇒ 确实没有
 */
export interface SourceIndividualDetail {
  individual: SourceIndividual;
  sire: SourceIndividualSummary | null;
  dam: SourceIndividualSummary | null;
  offspring: SourceIndividualSummary[];
  siblingsBySire: SourceIndividualSummary[];
  batches: SourceIndividualBatchSummary[];
  downstream: SourceIndividualDownstream;
}

export interface SourceIndividualPayload {
  individualCode?: string;
  tagNumber?: string | null;
  species?: string | null;
  breed?: string | null;
  sex?: string | null;
  birthDate?: string | null;
  originFarm?: string | null;
  externalGeneBankId?: string | null;
  dnaSampleCode?: string | null;
  dnaVerificationStatus?: DnaVerificationStatus;
  /**
   * 系谱。填了就必须是**同厂已存在**的个体 —— 后端查不到会回 422 `INVALID_PEDIGREE`,
   * ⛔ 前端不许把这个错误吞掉再当成「没填」提交(那是静默置空)。
   */
  sireIndividualId?: string | null;
  damIndividualId?: string | null;
  notes?: string | null;
}

export interface SourceIndividualLookupResult {
  tagNumber: string;
  /** 0 = 查过了没有 / 1 = 唯一 / >1 = 标牌撞号需人工选。⛔ 三者不可折叠。 */
  matchCount: number;
  matched: SourceIndividual | null;
  allMatches: SourceIndividual[];
}

export function listSourceIndividuals(
  factoryId: string,
  params: { page?: number; size?: number; keyword?: string } = {}
) {
  return get<SourceIndividualPage>(`/${factoryId}/source-individuals`, {
    params: {
      page: params.page ?? 1,
      size: params.size ?? 20,
      keyword: params.keyword || undefined,
    },
  });
}

export function getSourceIndividual(factoryId: string, id: string) {
  return get<SourceIndividual>(`/${factoryId}/source-individuals/${id}`);
}

/**
 * 详情: 本体 + 系谱 + 去向 (#807)。
 * `_silent: true`: 抽屉自己把失败原话显示出来 (不留一张空档案让人以为「这头没有系谱」),
 * 拦截器再弹一个 toast 是重复的。
 */
export function getSourceIndividualDetail(factoryId: string, id: string) {
  return get<SourceIndividualDetail>(`/${factoryId}/source-individuals/${id}/detail`, {
    _silent: true,
  });
}

export function findSourceIndividualByTag(factoryId: string, tagNumber: string) {
  return get<SourceIndividualLookupResult>(
    `/${factoryId}/source-individuals/by-tag/${encodeURIComponent(tagNumber)}`
  );
}

export function createSourceIndividual(factoryId: string, payload: SourceIndividualPayload) {
  return post<SourceIndividual>(`/${factoryId}/source-individuals`, payload);
}

export function updateSourceIndividual(
  factoryId: string,
  id: string,
  payload: SourceIndividualPayload
) {
  return put<SourceIndividual>(`/${factoryId}/source-individuals/${id}`, payload);
}

export function deleteSourceIndividual(factoryId: string, id: string) {
  return del<void>(`/${factoryId}/source-individuals/${id}`);
}
