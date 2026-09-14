import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

/**
 * 源头个体档案 + 拍照核验 API 客户端。
 *
 * 后端:
 *   GET    /api/mobile/{factoryId}/source-individuals
 *   GET    /api/mobile/{factoryId}/source-individuals/{id}
 *   GET    /api/mobile/{factoryId}/source-individuals/by-tag/{tagNumber}
 *   POST   /api/mobile/{factoryId}/source-individuals
 *   PUT    /api/mobile/{factoryId}/source-individuals/{id}
 *   DELETE /api/mobile/{factoryId}/source-individuals/{id}
 *   POST   /api/mobile/{factoryId}/vision/verify
 *
 * ⛔ 本文件**不做**「失败时返回一个空的成功结果」那种兜底(核心原则 1)。
 *    唯一一处对错误做结构化处理的是 422 —— 而 422 不是失败兜底,
 *    它是「模型没看懂这张照片」这个**正常业务结果**, 后端专门给它留了 rawText。
 */

// ========== 类型 ==========

export type DnaVerificationStatus = 'UNSAMPLED' | 'SAMPLED' | 'MATCHED' | 'MISMATCH';

export interface SourceIndividual {
  id: string;
  factoryId: string;
  individualCode: string;
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

/**
 * 按耳标号反查的结果。
 *
 * ⚠️ `matchCount` 的三个取值是三件不同的事, UI ⛔ 不许把它们折叠:
 *   0  —— 查过了, 这个耳标号没有建过档 ⇒ 引导「登记新个体」
 *   1  —— 唯一命中 ⇒ 直接展示
 *   >1 —— 耳标号撞号(脱落重挂/跨场引进) ⇒ 必须让人选
 */
export interface SourceIndividualLookupResult {
  tagNumber: string;
  matchCount: number;
  matched: SourceIndividual | null;
  allMatches: SourceIndividual[];
}

export interface CreateSourceIndividualRequest {
  individualCode: string;
  tagNumber?: string;
  species?: string;
  breed?: string;
  sex?: string;
  birthDate?: string;
  originFarm?: string;
  externalGeneBankId?: string;
  dnaSampleCode?: string;
  dnaVerificationStatus?: DnaVerificationStatus;
  /**
   * 系谱。填了就必须是**同厂已存在**的个体 —— 后端查不到会回 422,
   * ⛔ 前端不许把它吞掉再当成「没填」提交(那就是静默置空)。
   */
  sireIndividualId?: string;
  damIndividualId?: string;
  extAttributes?: Record<string, unknown>;
  notes?: string;
}

export type UpdateSourceIndividualRequest = Omit<CreateSourceIndividualRequest, 'individualCode'>;

export type VisionTemplateCode = 'EAR_TAG' | 'MEAT_CUT';

export interface VisionVerifyRequest {
  templateCode: VisionTemplateCode;
  imageBase64: string;
  contentType?: string;
}

export interface VisionVerifyResult {
  templateCode: string;
  templateName?: string;
  /** 模型真的返回的那些键。⛔ 缺的键就是缺, 不会被补成 null/0。 */
  fields?: Record<string, unknown> | null;
  confidence?: number | null;
  rawText?: string | null;
  parseError?: string | null;
  // EAR_TAG
  normalizedTagNumber?: string | null;
  /** 缺席(undefined) = 模型压根没读出号; 0 = 查过了没有。⛔ 两者不可折叠。 */
  matchCount?: number | null;
  matched?: SourceIndividual | null;
  allMatches?: SourceIndividual[] | null;
  // MEAT_CUT
  assessmentLevel?: string | null;
}

/**
 * 核验的返回：成功 / 模型输出无法解析(422)。
 *
 * ⚠️ 其余错误(400/403/502/网络)一律 **throw** —— 它们不是「识别结果」,
 *    把它们塞进同一个返回值里, 屏幕就分不清「该重拍」和「该找运维」。
 */
export type VisionVerifyOutcome =
  | { ok: true; result: VisionVerifyResult }
  | { ok: false; reason: string; rawText?: string | null; templateCode: string };

interface ApiEnvelope<T> {
  success?: boolean;
  code?: number;
  message?: string;
  data?: T;
}

// ========== 客户端 ==========

class SourceIndividualApiClient {
  private basePath(factoryId?: string): string {
    const id = getCurrentFactoryId(factoryId);
    if (!id) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${id}/source-individuals`;
  }

  private visionPath(factoryId?: string): string {
    const id = getCurrentFactoryId(factoryId);
    if (!id) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${id}/vision`;
  }

  /** 读不到 data 就抛 —— ⛔ 不返回一个假的空列表/空对象(核心原则 1)。 */
  private unwrap<T>(response: ApiEnvelope<T>, what: string): T {
    if (response && response.data !== undefined && response.data !== null) {
      return response.data;
    }
    throw new Error(response?.message || `${what}失败：响应里没有数据`);
  }

  async list(
    params: { page?: number; size?: number; keyword?: string } = {},
    factoryId?: string
  ): Promise<SourceIndividualPage> {
    const response = await apiClient.get<ApiEnvelope<SourceIndividualPage>>(
      this.basePath(factoryId),
      { params: { page: params.page ?? 1, size: params.size ?? 20, keyword: params.keyword } }
    );
    return this.unwrap(response, '获取个体档案列表');
  }

  async getById(id: string, factoryId?: string): Promise<SourceIndividual> {
    const response = await apiClient.get<ApiEnvelope<SourceIndividual>>(
      `${this.basePath(factoryId)}/${encodeURIComponent(id)}`
    );
    return this.unwrap(response, '获取个体档案');
  }

  async findByTag(tagNumber: string, factoryId?: string): Promise<SourceIndividualLookupResult> {
    const response = await apiClient.get<ApiEnvelope<SourceIndividualLookupResult>>(
      `${this.basePath(factoryId)}/by-tag/${encodeURIComponent(tagNumber)}`
    );
    return this.unwrap(response, '按耳标号查询');
  }

  async create(
    request: CreateSourceIndividualRequest,
    factoryId?: string
  ): Promise<SourceIndividual> {
    const response = await apiClient.post<ApiEnvelope<SourceIndividual>>(
      this.basePath(factoryId),
      request
    );
    return this.unwrap(response, '建立个体档案');
  }

  async update(
    id: string,
    request: UpdateSourceIndividualRequest,
    factoryId?: string
  ): Promise<SourceIndividual> {
    const response = await apiClient.put<ApiEnvelope<SourceIndividual>>(
      `${this.basePath(factoryId)}/${encodeURIComponent(id)}`,
      request
    );
    return this.unwrap(response, '更新个体档案');
  }

  async remove(id: string, factoryId?: string): Promise<void> {
    await apiClient.delete<ApiEnvelope<void>>(
      `${this.basePath(factoryId)}/${encodeURIComponent(id)}`
    );
  }

  /**
   * 拍照核验。
   *
   * 422 ⇒ `{ ok: false, reason, rawText }` —— 模型没看懂这张照片, 用户该重拍。
   * 其余错误 ⇒ **原样抛出**, 由屏幕按 `error.message`(拦截器已把后端文案抬上去)提示。
   */
  async verify(request: VisionVerifyRequest, factoryId?: string): Promise<VisionVerifyOutcome> {
    try {
      const response = await apiClient.post<ApiEnvelope<VisionVerifyResult>>(
        `${this.visionPath(factoryId)}/verify`,
        request
      );
      return { ok: true, result: this.unwrap(response, '拍照核验') };
    } catch (error) {
      const err = error as { response?: { status?: number; data?: ApiEnvelope<VisionVerifyResult> } };
      if (err?.response?.status === 422) {
        const body = err.response.data;
        return {
          ok: false,
          templateCode: body?.data?.templateCode ?? request.templateCode,
          reason: body?.data?.parseError ?? body?.message ?? '识别结果无法解析',
          rawText: body?.data?.rawText ?? null,
        };
      }
      throw error;
    }
  }
}

export const sourceIndividualApiClient = new SourceIndividualApiClient();
export default sourceIndividualApiClient;
