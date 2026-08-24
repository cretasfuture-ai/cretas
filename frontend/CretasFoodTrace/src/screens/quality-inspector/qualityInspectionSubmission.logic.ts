import type { QualityInspectionResult } from '../../types/qualityInspector';

const PASS_THRESHOLD = 95;
const CONDITIONAL_THRESHOLD = 70;

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
