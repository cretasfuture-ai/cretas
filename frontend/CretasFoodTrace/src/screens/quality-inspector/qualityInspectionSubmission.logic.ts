import type { QualityInspectionResult } from '../../types/qualityInspector';

const PASS_THRESHOLD = 95;
const CONDITIONAL_THRESHOLD = 70;

export interface QualityInspectionCounts {
  passCount: number;
  failCount: number;
  passRate: number;
}

export function deriveQualityInspectionCounts(
  sampleSize: number,
  totalScore: number,
): QualityInspectionCounts {
  const normalizedSampleSize = Math.max(1, Math.trunc(sampleSize));
  const normalizedScore = Math.min(100, Math.max(0, totalScore));
  const passCount = Math.min(
    normalizedSampleSize,
    Math.max(0, Math.round(normalizedSampleSize * normalizedScore / 100)),
  );
  const failCount = normalizedSampleSize - passCount;
  const passRate = passCount / normalizedSampleSize * 100;

  return { passCount, failCount, passRate };
}

export function deriveQualityInspectionResult(passRate: number): QualityInspectionResult {
  if (passRate >= PASS_THRESHOLD) return 'PASS';
  if (passRate >= CONDITIONAL_THRESHOLD) return 'CONDITIONAL';
  return 'FAIL';
}

/**
 * 提交前校验。
 *
 * 为什么放在这里而不是留在屏幕里: 留在屏幕里的 if 只能靠"读源码"来验,
 * 放在这里能拿真实输入直接跑 —— 闸要能被观察到红一次才算在守东西。
 *
 * 背景 (2026-08-25): QIFormScreen 此前预填了一份 93 分的合格报告
 * (5 项分数 + 7 条备注全预填), 而唯一的提交闸是 `if (!batch) return`。
 * 打开屏幕直接点提交 = 落库一份没有任何人做过判断的合格报告。
 */
export interface InspectionScoreDraft {
  /** null = 还没评分。**不要**用 0 表示"未评" —— 0 是一个合法分数。 */
  score: number | null;
}

export interface InspectionSubmissionCheck {
  /** 未评分的检验项 id, 顺序与传入的检验项顺序一致 */
  unscoredItemIds: string[];
  sampleSizeValid: boolean;
  /** 全部评完 + 抽样数合法, 才可以提交 */
  canSubmit: boolean;
}

export function checkQualityInspectionSubmission(
  itemIds: string[],
  scores: Record<string, InspectionScoreDraft | undefined>,
  sampleSize: number,
): InspectionSubmissionCheck {
  const unscoredItemIds = itemIds.filter((id) => scores[id]?.score == null);
  const sampleSizeValid = Number.isFinite(sampleSize) && sampleSize > 0;
  return {
    unscoredItemIds,
    sampleSizeValid,
    canSubmit: unscoredItemIds.length === 0 && sampleSizeValid,
  };
}

interface ApiErrorShape {
  code?: unknown;
  message?: unknown;
  response?: {
    data?: {
      message?: unknown;
    };
  };
}

export function getQualityInspectionSubmitErrorMessage(
  error: unknown,
  fallback: string,
  networkMessage: string = fallback,
): string {
  if (typeof error !== 'object' || error === null) return fallback;

  const apiError = error as ApiErrorShape;
  const backendMessage = apiError.response?.data?.message;
  if (typeof backendMessage === 'string' && backendMessage.trim()) {
    return backendMessage;
  }
  if (!apiError.response && (
    ['ERR_NETWORK', 'ECONNABORTED', 'ETIMEDOUT'].includes(String(apiError.code))
    || (typeof apiError.message === 'string'
      && /^(network error|network request failed|timeout of \d+ms exceeded)$/i.test(apiError.message.trim()))
  )) {
    return networkMessage;
  }
  if (typeof apiError.message === 'string' && apiError.message.trim()) {
    return apiError.message;
  }
  return fallback;
}
