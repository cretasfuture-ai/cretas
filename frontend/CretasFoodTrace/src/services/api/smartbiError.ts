import { isAxiosError } from 'axios';

export type SmartBIErrorKind = 'unavailable' | 'retryable';

export function classifySmartBIError(error: unknown): SmartBIErrorKind {
  return isAxiosError(error) && error.response?.status === 404
    ? 'unavailable'
    : 'retryable';
}
