import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus, { ElMessageBox } from 'element-plus';
import {
  crossedDeadline,
  formatBackendTime,
  formatDuration,
  hasPartialExpiry,
  parseBackendTime,
  remainingMinutesAt,
  remainingText,
} from '@/utils/processTimeLimit';
import type { ProcessTimeLimitStatus } from '@/api/processTimeLimit';
import type { WorkflowPortDescriptor } from '@/api/processSheet';

/** 缺口 14 工序时限面板: 断言打在发出去的请求和按钮的有无上。 */
const state = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => state.get(...args),
  post: (...args: unknown[]) => state.post(...args),
}));
import Panel from '../ProcessTimeLimitPanel.vue';

/** 2026-11-04 10:00 东八区。 */
const NOW = Date.UTC(2026, 10, 4, 2, 0, 0);
const ok = (data: unknown) => ({ success: true, data });

function status(extra: Partial<ProcessTimeLimitStatus> = {}): ProcessTimeLimitStatus {
  return {
    targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-1', batchNumber: 'RM-1', workflowPortId: 'in-1',
    processName: '解冻', limitMinutes: 120, startEvent: 'RAW_OUTBOUND', state: 'OK',
    startAt: '2026-11-04T09:00:00', deadlineAt: '2026-11-04T11:20:00', remainingMinutes: 80, reason: null,
    balance: 50, expiredQuantity: 0, drawableQuantity: 50, unit: 'kg', paused: false, perLot: false,
    ...extra,
  };
}

const port = (timeLimit: WorkflowPortDescriptor['timeLimit']) => ({ workflowPortId: 'in-1', timeLimit }) as WorkflowPortDescriptor;
const coldPort = port({ maxMinutes: 120, startEvent: 'RAW_OUTBOUND', pauseCodes: ['COLD_STORAGE'] });
const plainPort = port({ maxMinutes: 120, startEvent: 'RAW_OUTBOUND', pauseCodes: [] });

function mountPanel(inputPorts: WorkflowPortDescriptor[] = [plainPort]) {
  return mount(Panel, {
    props: { factoryId: 'F-1', planId: 'P-1', processOrder: 2, inputPorts },
    global: { plugins: [ElementPlus] },
  });
}

describe('时限文字', () => {
  it('后端时间按东八区读; 小数秒和空格分隔都能读', () => {
    expect(parseBackendTime('2026-11-04T10:00:00')).toBe(NOW);
    expect(parseBackendTime('2026-11-04T10:00:00.5')).toBe(NOW);
    expect(parseBackendTime('2026-11-04 10:00:00')).toBe(NOW);
    expect(parseBackendTime('')).toBeNull();
    expect(formatBackendTime('2026-11-04T10:00:05.123')).toBe('2026-11-04 10:00');
  });

  it('剩余时间的说法', () => {
    expect(formatDuration(80)).toBe('1 小时 20 分');
    expect(formatDuration(120)).toBe('2 小时');
    expect(remainingText(80)).toBe('还剩 1 小时 20 分');
    expect(remainingText(-35)).toBe('已超时 35 分钟');
    expect(remainingText(0)).toBe('还剩不到 1 分钟');
    expect(remainingText(null)).toBeNull();
  });

  it('按截止时间算; 暂停中用后端读数; 过了 30 秒算超时 1 分钟', () => {
    expect(remainingMinutesAt(status(), NOW)).toBe(80);
    expect(remainingMinutesAt(status({ deadlineAt: '2026-11-04T09:59:30' }), NOW)).toBe(-1);
    expect(remainingMinutesAt(status({ paused: true, deadlineAt: '2026-11-04T09:00:00', remainingMinutes: 40 }), NOW)).toBe(40);
    expect(remainingMinutesAt(status({ deadlineAt: null, remainingMinutes: null }), NOW)).toBeNull();
  });

  it('到点判定只看还在时限内的行', () => {
    expect(crossedDeadline(status({ deadlineAt: '2026-11-04T09:59:00' }), NOW)).toBe(true);
    expect(crossedDeadline(status(), NOW)).toBe(false);
    expect(crossedDeadline(status({ state: 'EXPIRED', deadlineAt: '2026-11-04T09:59:00' }), NOW)).toBe(false);
  });

  it('部分超时: 按笔算且两边都有量才算', () => {
    expect(hasPartialExpiry(status({ perLot: true, expiredQuantity: 10, drawableQuantity: 40 }))).toBe(true);
    expect(hasPartialExpiry(status({ perLot: true, expiredQuantity: 50, drawableQuantity: 0 }))).toBe(false);
    expect(hasPartialExpiry(status({ perLot: false, expiredQuantity: 10, drawableQuantity: 40 }))).toBe(false);
  });
});

describe('ProcessTimeLimitPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ now: NOW, toFake: ['Date', 'setInterval', 'clearInterval'] });
    state.post.mockResolvedValue(ok({ id: 7 }));
  });
  afterEach(() => vi.useRealTimers());

  it('这道工序没有状态: 整块不显示', async () => {
    state.get.mockResolvedValue(ok([]));
    const wrapper = mountPanel();
    await flushPromises();
    expect(state.get).toHaveBeenCalledWith(
      '/F-1/process-time-limits/plans/P-1',
      expect.objectContaining({ params: { processOrder: 2 } }),
    );
    expect(wrapper.find('[data-testid="process-time-limit-panel"]').exists()).toBe(false);
  });

  it('读失败: 就地说明, 不假装没有时限', async () => {
    state.get.mockRejectedValue(new Error('boom'));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="time-limit-load-failed"]').exists()).toBe(true);
  });

  it('显示剩余时间, 每分钟本地重算', async () => {
    state.get.mockResolvedValue(ok([status()]));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.get('[data-testid="time-limit-remaining"]').text()).toBe('还剩 1 小时 20 分');
    vi.advanceTimersByTime(60_000);
    await flushPromises();
    expect(wrapper.get('[data-testid="time-limit-remaining"]').text()).toBe('还剩 1 小时 19 分');
    expect(state.get).toHaveBeenCalledTimes(1);
  });

  it('到点重新问后端一次, 不每分钟都问', async () => {
    state.get.mockResolvedValue(ok([status({ deadlineAt: '2026-11-04T10:00:30' })]));
    mountPanel();
    await flushPromises();
    vi.advanceTimersByTime(60_000);
    await flushPromises();
    expect(state.get).toHaveBeenCalledTimes(2);
    vi.advanceTimersByTime(60_000);
    await flushPromises();
    expect(state.get).toHaveBeenCalledTimes(2);
  });

  it('按笔部分超时: 写清可领和已超时', async () => {
    state.get.mockResolvedValue(ok([status({
      state: 'EXPIRED', perLot: true, expiredQuantity: 15, drawableQuantity: 35,
    })]));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.get('[data-testid="time-limit-partial"]').text()).toBe('可领 35 kg，已超时 15 kg');
  });

  it('要登记解冻: 确认后发 START/THAW, 然后刷新', async () => {
    const confirm = vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never);
    state.get.mockResolvedValue(ok([status({
      state: 'START_MISSING', deadlineAt: null, remainingMinutes: null, reason: '还没有登记开始解冻，不能投料',
    })]));
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.get('[data-testid="time-limit-start-thaw"]').trigger('click');
    await flushPromises();
    expect(confirm.mock.calls[0][1]).toBe('开始解冻 — RM-1');
    expect(state.post).toHaveBeenCalledWith('/F-1/process-time-limits/marks', {
      targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-1', kind: 'START', code: 'THAW',
    });
    expect(state.get).toHaveBeenCalledTimes(2);
  });

  it('解冻确认框点取消: 不发登记', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue('cancel');
    state.get.mockResolvedValue(ok([status({ state: 'START_MISSING', deadlineAt: null, remainingMinutes: null })]));
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.get('[data-testid="time-limit-start-thaw"]').trigger('click');
    await flushPromises();
    expect(state.post).not.toHaveBeenCalled();
  });

  it('端口没配 pauseCodes: 没有冷藏按钮', async () => {
    state.get.mockResolvedValue(ok([status()]));
    const wrapper = mountPanel([plainPort]);
    await flushPromises();
    expect(wrapper.find('[data-testid="time-limit-start-cold"]').exists()).toBe(false);
  });

  it('结束冷藏用后端状态带回的登记 id (刷新页面后也能结束)', async () => {
    state.get.mockResolvedValueOnce(ok([status()]));
    state.get.mockResolvedValue(ok([status({ paused: true, reason: '冷藏暂停中，暂不计时', pauseMarkId: 7 })]));
    const wrapper = mountPanel([coldPort]);
    await flushPromises();
    await wrapper.get('[data-testid="time-limit-start-cold"]').trigger('click');
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-1/process-time-limits/marks', {
      targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-1', kind: 'PAUSE', code: 'COLD_STORAGE',
    });
    await wrapper.get('[data-testid="time-limit-end-cold"]').trigger('click');
    await flushPromises();
    expect(state.post).toHaveBeenLastCalledWith('/F-1/process-time-limits/marks/7/end');
  });

  it('别处开始的冷藏: 打开页面就能结束', async () => {
    state.get.mockResolvedValue(ok([status({ paused: true, pauseMarkId: 9 })]));
    const wrapper = mountPanel([coldPort]);
    await flushPromises();
    await wrapper.get('[data-testid="time-limit-end-cold"]').trigger('click');
    await flushPromises();
    expect(state.post).toHaveBeenLastCalledWith('/F-1/process-time-limits/marks/9/end');
  });
  it('A13 补登实际开始时间: 没填原因不能登记; 填了发 startedAt + backfillReason (不是现在)', async () => {
    state.get.mockResolvedValue(ok([status({
      state: 'START_MISSING', deadlineAt: null, remainingMinutes: null, reason: '还没有登记开始解冻，不能投料',
    })]));
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.get('[data-testid="time-limit-backfill-thaw"]').trigger('click');
    await flushPromises();
    const vm = wrapper.vm as unknown as {
      backfillAt: string; backfillReason: string; backfillDisabledReason: string; submitBackfill: () => Promise<void>;
    };
    vm.backfillAt = '2026-11-04T06:00:00';
    await flushPromises();
    expect(vm.backfillDisabledReason).toBe('请填写补登原因');
    await vm.submitBackfill();
    expect(state.post).not.toHaveBeenCalled();

    vm.backfillReason = '早班 6 点开始解冻，忘了登记';
    await flushPromises();
    expect(vm.backfillDisabledReason).toBe('');
    await vm.submitBackfill();
    await flushPromises();
    expect(state.post).toHaveBeenCalledWith('/F-1/process-time-limits/marks', {
      targetType: 'RAW_MATERIAL_BATCH', targetId: 'mb-1', kind: 'START', code: 'THAW',
      startedAt: '2026-11-04T06:00:00', backfillReason: '早班 6 点开始解冻，忘了登记',
    });
  });

  it('A13 阴性对照: 未来时间不能补登; 不在待登记状态的行没有补登入口', async () => {
    state.get.mockResolvedValue(ok([status({
      state: 'START_MISSING', deadlineAt: null, remainingMinutes: null, reason: '还没有登记开始解冻，不能投料',
    }), status({ targetId: 'mb-2', batchNumber: 'RM-2' })]));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.findAll('[data-testid="time-limit-backfill-thaw"]')).toHaveLength(1);
    await wrapper.get('[data-testid="time-limit-backfill-thaw"]').trigger('click');
    const vm = wrapper.vm as unknown as { backfillAt: string; backfillReason: string; backfillDisabledReason: string };
    vm.backfillAt = '2026-11-04T12:00:00';
    vm.backfillReason = 'x';
    await flushPromises();
    expect(vm.backfillDisabledReason).toBe('开始时间不能晚于现在');
  });
});
