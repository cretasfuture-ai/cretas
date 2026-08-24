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

interface ApiErrorShape {
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
): string {
  if (typeof error !== 'object' || error === null) return fallback;

  const apiError = error as ApiErrorShape;
  const backendMessage = apiError.response?.data?.message;
  if (typeof backendMessage === 'string' && backendMessage.trim()) {
    return backendMessage;
  }
  if (typeof apiError.message === 'string' && apiError.message.trim()) {
    return apiError.message;
  }
  return fallback;
}
