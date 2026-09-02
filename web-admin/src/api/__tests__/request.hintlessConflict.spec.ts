import { AxiosError, AxiosHeaders, type AxiosAdapter, type AxiosResponse } from 'axios';
import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * LIUSHANMEN 走查 (2026-09-02): 一批 `BusinessException(409, …)` 没配
 * `.withHint(...)`，而 request.ts 旧判据把"没有 actionHint"当成"这是乐观锁，
 * 调用方会自己弹框"，于是整条错误提示被静默吞掉 —— 用户点了动作、后端确实
 * 拒绝了 (409)，界面却什么都不显示（既不是原始 message，也不是裸 code）。
 *
 * 本文件锁住修复后的三态：
 *   1. 无 hint 的 409 —— 必须仍然落一条 sticky toast，展示后端原始 message
 *      (never silent, never just the code)。
 *   2. 带 hint 的 409 —— 富通知同时展示 message 与 actionHint（既有行为，回归防线）。
 *   3. 真正的 JPA 乐观锁冲突 (errorCode=OPTIMISTIC_LOCK_CONFLICT) —— 仍然保持
 *      抑制，交给调用方自己的"并发编辑冲突"弹框，不产生双重提示。
 */

const elementMocks = vi.hoisted(() => ({
  message: vi.fn(),
  notification: vi.fn(),
}));

vi.mock('element-plus', () => ({
  ElMessage: elementMocks.message,
  ElMessageBox: { alert: vi.fn(), confirm: vi.fn() },
  ElNotification: elementMocks.notification,
}));

import request from '../request';

describe('409 without actionHint no longer goes silent', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    elementMocks.notification.mockReturnValue({ close: vi.fn() });
  });

  it('surfaces the raw backend message via a sticky toast when the 409 carries no hint', async () => {
    const message = '只有草稿状态的调拨单可以提交审批';
    const promise = request.post('/F006/inventory/transfers/T-1/submit', {}, {
      adapter: rejecting409({ message }),
    } as never);

    await expect(promise).rejects.toBeTruthy();
    await waitForSurface(() => elementMocks.message.mock.calls.length > 0);

    // 阴性对照: 没有 actionHint 就不应该走富通知那条路径 (showRichError 的
    // `if (opts.actionHint)` 分支), 应该落到默认的 sticky toast (ElMessage)。
    expect(elementMocks.notification).not.toHaveBeenCalled();
    expect(elementMocks.message).toHaveBeenCalledWith(expect.objectContaining({
      message,
      type: 'error',
      duration: 0, // sticky — 方案 A, 不是 3s 闪过
    }));
  });

  it('still renders both the message and the hint when the 409 carries an actionHint (regression guard)', async () => {
    const message = '该供应商与物料的供应关系不存在';
    const actionHint = '该供应商尚未维护可采购原料，请先在供应商详情的「供应原料」中建立关系';
    const promise = request.post('/F006/purchase/orders', {}, {
      adapter: rejecting409({ message, actionHint, errorCode: 'SUPPLIER_MATERIAL_RELATION_NOT_FOUND' }),
    } as never);

    await expect(promise).rejects.toBeTruthy();
    await waitForSurface(() => elementMocks.notification.mock.calls.length > 0);

    expect(elementMocks.message).not.toHaveBeenCalled();
    expect(elementMocks.notification).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining(message),
    }));
    expect(elementMocks.notification).toHaveBeenCalledWith(expect.objectContaining({
      message: expect.stringContaining(actionHint),
    }));
  });

  it('keeps the true JPA optimistic-lock conflict suppressed by errorCode, independent of hint presence', async () => {
    // 模拟 legacy/畸形响应: errorCode 是乐观锁的判别信号, 即使这条响应本身没带
    // actionHint, 抑制逻辑也应该只认 errorCode —— 不能因为"没有 hint"就当成本次
    // 要修的那一类而弹出提示 (那会在真乐观锁场景造成与调用方自定义确认框的双重提示)。
    const message = '数据已被其他用户修改，请刷新后重试';
    const promise = request.put('/F006/sales/customers/C-1', {}, {
      adapter: rejecting409({ message, errorCode: 'OPTIMISTIC_LOCK_CONFLICT' }),
    } as never);

    await expect(promise).rejects.toBeTruthy();
    await settleAsyncSurface();

    expect(elementMocks.message).not.toHaveBeenCalled();
    expect(elementMocks.notification).not.toHaveBeenCalled();
  });
});

function rejecting409(opts: {
  message: string;
  actionHint?: string | null;
  hintTarget?: string | null;
  errorCode?: string | null;
}): AxiosAdapter {
  return async (config) => {
    const response: AxiosResponse = {
      status: 409,
      statusText: 'Conflict',
      headers: new AxiosHeaders(),
      config,
      data: {
        success: false,
        code: 409,
        errorCode: opts.errorCode ?? null,
        message: opts.message,
        actionHint: opts.actionHint ?? null,
        severity: 'warning',
        hintTarget: opts.hintTarget ?? null,
        data: null,
      },
    };
    throw new AxiosError('Request failed', 'ERR_BAD_REQUEST', config, undefined, response);
  };
}

// 提示是 interceptor 里 fire-and-forget 的异步分支 (`await import('element-plus')`),
// 断言前必须等它落地 — 与 request.authFailure.spec.ts / request.workflowConflict.spec.ts
// 同一条踩坑记录, 不要用写死的短 setTimeout 替代轮询 (CI 上动态 import 偶尔超过 50ms)。
async function waitForSurface(predicate: () => boolean, timeoutMs = 2000): Promise<void> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (predicate()) return;
    await new Promise((resolve) => setTimeout(resolve, 5));
  }
}

// 反例 (断言"没有弹") 没有可等的正向信号, 只能等满预算 —— 等太短会变成假绿。
async function settleAsyncSurface(timeoutMs = 500): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, timeoutMs));
}
