import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * LSMDEMO 演示：财务分析(Excel)一打开弹 4 条红色「请求的接口不存在 (GET /LSMDEMO/smart-bi/analysis/finance?...)」。
 * 锁住：1) 用户看到的文案不含接口路径（路径只进 console）；2) 并发的多个 404 文案相同，去重成一条 toast；
 * 3) 带 _silent 的请求不弹 toast（页面自己在页内说明）。
 */
const elementMocks = vi.hoisted(() => ({ message: vi.fn(), notification: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: elementMocks.message,
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
  ElNotification: elementMocks.notification,
}));

import request from '../request';

function rejecting404(): AxiosAdapter {
  return async (config) => {
    const response: AxiosResponse = {
      status: 404, statusText: 'Not Found', headers: new AxiosHeaders(), config,
      data: { success: false, message: '请求资源不存在', data: null },
    };
    throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}
async function waitFor(predicate: () => boolean, timeoutMs = 2000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((r) => setTimeout(r, 5));
  }
}

describe('404 提示不暴露接口路径且并发去重', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(console, 'warn').mockImplementation(() => undefined);
  });

  it('两个不同路径的并发 404 只弹一条，文案不含路径，路径写进 console', async () => {
    const a = request.get('/LSMDEMO/smart-bi/analysis/finance?analysisType=profit', { adapter: rejecting404() } as never);
    const b = request.get('/LSMDEMO/smart-bi/analysis/finance?analysisType=cost', { adapter: rejecting404() } as never);
    await expect(a).rejects.toBeTruthy();
    await expect(b).rejects.toBeTruthy();
    await waitFor(() => elementMocks.message.mock.calls.length > 0);
    await new Promise((r) => setTimeout(r, 100));

    expect(elementMocks.message).toHaveBeenCalledTimes(1);
    const text = String(elementMocks.message.mock.calls[0][0].message);
    expect(text).not.toContain('/LSMDEMO');
    expect(text).not.toContain('GET');
    expect(text).toContain('暂未开通');
    expect(vi.mocked(console.warn).mock.calls.flat().join(' ')).toContain('/LSMDEMO/smart-bi/analysis/finance?analysisType=profit');
  });

  it('_silent 请求不弹 toast（阴性对照）', async () => {
    const p = request.get('/LSMDEMO/smart-bi/analysis/finance', { adapter: rejecting404(), _silent: true } as never);
    await expect(p).rejects.toBeTruthy();
    await new Promise((r) => setTimeout(r, 300));
    expect(elementMocks.message).not.toHaveBeenCalled();
  });
});
