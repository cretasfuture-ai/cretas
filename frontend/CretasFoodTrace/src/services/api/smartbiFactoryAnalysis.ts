import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import { useAuthStore } from '../../store/authStore';
import type { FactoryAnalysisData, FactoryAnalysisRequest, FactoryAnalysisResponse } from '../../types/factoryAnalysis';

export class FactoryAnalysisApiError extends Error {
  readonly status?: number;

  constructor(message: string, status?: number) {
    super(message);
    this.name = 'FactoryAnalysisApiError';
    this.status = status;
  }
}

interface Identity {
  factoryId: string;
  userId: string;
  role: string;
}

function assertJointSelection(request: FactoryAnalysisRequest, data: FactoryAnalysisData): void {
  const selected = request.inspectionUploadId !== undefined || request.costUploadId !== undefined;
  if (!selected) return;
  const joint = data.jointUploads;
  if (!joint || joint.inspectionUploadId !== request.inspectionUploadId || joint.costUploadId !== request.costUploadId) {
    throw new FactoryAnalysisApiError('联合上传范围与当前选择不一致，已拒绝本次分析');
  }
}

function captureIdentity(): Identity {
  const factoryId = requireFactoryId();
  const auth = useAuthStore.getState();
  const userId = auth.getUserId();
  const role = auth.getUserRole();
  if (userId === null || userId === undefined || !role) {
    throw new FactoryAnalysisApiError('工厂经营分析需要登录身份');
  }
  return { factoryId, userId: String(userId), role };
}

function assertIdentity(identity: Identity): void {
  const auth = useAuthStore.getState();
  if (
    auth.getFactoryId() !== identity.factoryId
    || String(auth.getUserId()) !== identity.userId
    || auth.getUserRole() !== identity.role
  ) {
    throw new FactoryAnalysisApiError('登录状态、工厂或角色已变化，已丢弃本次分析');
  }
}

function errorStatus(error: unknown): number | undefined {
  if (!error || typeof error !== 'object') return undefined;
  const response = (error as { response?: { status?: unknown } }).response;
  return typeof response?.status === 'number' ? response.status : undefined;
}

/** Call the authenticated Java mobile entry; no caller-supplied roles or tenant body fields. */
export async function askFactoryAnalysis(request: FactoryAnalysisRequest): Promise<FactoryAnalysisData> {
  const identity = captureIdentity();
  // A context-bearing turn is intentionally minimal. The server owns the
  // original scope and must not receive stale route/source/period fields on
  // follow-ups or clarification retries.
  const body = request.contextId
    ? {
      question: request.question,
      contextId: request.contextId,
      ...(request.analysisSubject ? { analysisSubject: request.analysisSubject } : {}),
    }
    : {
      question: request.question,
      ...(request.source ? { source: request.source } : {}),
      ...(request.uploadId !== undefined ? { uploadId: request.uploadId } : {}),
      ...(request.inspectionUploadId !== undefined ? { inspectionUploadId: request.inspectionUploadId } : {}),
      ...(request.costUploadId !== undefined ? { costUploadId: request.costUploadId } : {}),
      ...(request.year !== undefined ? { year: request.year } : {}),
      ...(request.periodType ? { periodType: request.periodType } : {}),
      ...(request.startMonth !== undefined ? { startMonth: request.startMonth } : {}),
      ...(request.endMonth !== undefined ? { endMonth: request.endMonth } : {}),
      ...(request.analysisSubject ? { analysisSubject: request.analysisSubject } : {}),
    };
  try {
    const response = await apiClient.post<FactoryAnalysisResponse>(
      `/api/mobile/${encodeURIComponent(identity.factoryId)}/smart-bi/factory-analysis`,
      body,
    );
    assertIdentity(identity);
    if (response.success !== true || !response.data) {
      throw new FactoryAnalysisApiError(response.message || '工厂经营分析暂不可用');
    }
    if (response.data.factoryId !== identity.factoryId) {
      throw new FactoryAnalysisApiError('分析响应与当前工厂不一致');
    }
    assertJointSelection(request, response.data);
    return response.data;
  } catch (error) {
    if (error instanceof FactoryAnalysisApiError) throw error;
    throw new FactoryAnalysisApiError(
      error instanceof Error ? error.message : '工厂经营分析请求失败',
      errorStatus(error),
    );
  }
}
