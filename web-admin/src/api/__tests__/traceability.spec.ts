import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

import { get } from '../request';
import { ApiError } from '@/types/api';
import { getFullTrace, isTraceNotFound } from '../traceability';
import { getSourceIndividualDetail } from '../sourceIndividual';

const mockedGet = vi.mocked(get);

beforeEach(() => {
  mockedGet.mockReset();
  mockedGet.mockResolvedValue({ success: true, data: {}, message: '' });
});

describe('traceability API client', () => {
  it('full trace goes to /{factoryId}/traceability/full/{batchNumber}, silent (page renders its own errors)', async () => {
    await getFullTrace('F006', 'PB-2026-001');
    expect(mockedGet).toHaveBeenCalledTimes(1);
    expect(mockedGet).toHaveBeenCalledWith('/F006/traceability/full/PB-2026-001', { _silent: true });
  });

  it('url-encodes the batch number — a / or # in it must not become a path segment', async () => {
    await getFullTrace('F006', 'A/B#1');
    expect(mockedGet).toHaveBeenCalledWith('/F006/traceability/full/A%2FB%231', { _silent: true });
  });

  it('individual detail goes to /{factoryId}/source-individuals/{id}/detail (#807)', async () => {
    await getSourceIndividualDetail('F006', 'si-42');
    expect(mockedGet).toHaveBeenCalledWith('/F006/source-individuals/si-42/detail', { _silent: true });
  });
});

describe('isTraceNotFound — both shapes the interceptor can hand us', () => {
  it('200 + {success:false, code:404} ⇒ ApiError with numeric code, no status', () => {
    // TraceabilityController#getFullTrace 对不存在的批次回 HTTP 200 + code 404;
    // request.ts 拦截器把它变成 ApiError(message, code=404, status=undefined)。
    // ApiError.code 的类型标的是 string, 运行时却是数字 —— 这里按运行时的形状造。
    const e = new ApiError('未找到该批次信息', 404 as unknown as string);
    expect(e.status).toBeUndefined();
    expect(isTraceNotFound(e)).toBe(true);
  });

  it('real HTTP 404 ⇒ ApiError(code=NOT_FOUND, status=404)', () => {
    expect(isTraceNotFound(new ApiError('请求的接口不存在', 'NOT_FOUND', 404))).toBe(true);
  });

  it('阴性对照: 403 / 500 / 网络错误 都不是「未找到」—— 否则权限问题会被说成批次不存在', () => {
    expect(isTraceNotFound(new ApiError('权限不足', 'FORBIDDEN', 403))).toBe(false);
    expect(isTraceNotFound(new ApiError('服务器错误', 'SERVER_ERROR', 500))).toBe(false);
    expect(isTraceNotFound(new Error('Network Error'))).toBe(false);
    expect(isTraceNotFound(null)).toBe(false);
  });
});
