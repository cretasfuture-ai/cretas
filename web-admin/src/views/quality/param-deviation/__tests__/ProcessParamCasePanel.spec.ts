import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError } from '@/types/api';

const api = vi.hoisted(() => ({
  list: vi.fn(),
  assignees: vi.fn(),
  detail: vi.fn(),
  assign: vi.fn(),
  start: vi.fn(),
  resolve: vi.fn(),
}));

vi.mock('@/api/processParamDeviationCase', () => ({
  listProcessParamCases: api.list,
  listProcessParamCaseAssignees: api.assignees,
  getProcessParamCase: api.detail,
  assignProcessParamCase: api.assign,
  startProcessParamCaseVerify: api.start,
  resolveProcessParamCase: api.resolve,
}));

import Panel from '../ProcessParamCasePanel.vue';

const ok = <T>(data: T) => ({ success: true, data, message: 'ok' });
const row = (stage = 'DETECTED', ownerUserId: number | null = null, version?: number) => ({
  id: 'case-1', sourceKey: 'yield-report:1:oilTemp', sourceKind: 'YIELD_REPORT',
  processName: '炒制', batchNumber: 'B-1', paramKey: 'oilTemp', paramLabel: '油温', unit: '℃',
  status: 'HIGH', stage, stageLabel: stage === 'CLOSED' ? '已关闭' : stage === 'EXECUTING' ? '核查中' : '待处理',
  ownerRole: 'quality_manager', ownerUserId, ownerName: ownerUserId ? '李质检' : null,
  version: version ?? (ownerUserId && stage === 'EXECUTING' ? 2 : 1), observedAt: '2026-11-06T09:00:00',
  closedAt: stage === 'CLOSED' ? '2026-11-06T10:00:00' : null, createdAt: '2026-11-06T09:00:00', lastRefreshedAt: null,
});
const detail = (stage = 'DETECTED', ownerUserId: number | null = null, resolutionNote: string | null = null, version?: number) => ({
  caseView: row(stage, ownerUserId, version), sourceReportId: 1, productionBatchId: 2, productionPlanId: 'plan-1',
  workProcessTaskId: 3, processEntryStepKey: null, productTypeId: 'PT-1', measuredValue: 165,
  standardValue: 155, rangeMin: 150, rangeMax: 160, resolutionCode: resolutionNote ? 'CORRECTED' : null,
  resolutionNote, resolvedBy: resolutionNote ? 7 : null, resolvedAt: resolutionNote ? '2026-11-06T10:00:00' : null,
});

function mountPanel(canWrite = true) {
  return mount(Panel, {
    props: { factoryId: 'F006', canWrite },
    global: { plugins: [ElementPlus] },
    attachTo: document.body,
  });
}

beforeEach(() => {
  vi.clearAllMocks();
  document.body.innerHTML = '';
  api.list.mockResolvedValue(ok([row()]));
  api.assignees.mockResolvedValue(ok([{ id: 7, name: '李质检', role: 'quality_manager' }]));
  api.detail.mockResolvedValue(ok(detail()));
  api.assign.mockResolvedValue(ok(null));
  api.start.mockResolvedValue(ok(null));
  api.resolve.mockResolvedValue(ok(null));
});

describe('ProcessParamCasePanel', () => {
  it('shows an in-range refreshed fact in Chinese without closing its unresolved case', async () => {
    const recovered = { ...row('DETECTED', 7), status: 'OK' };
    api.list.mockResolvedValueOnce(ok([recovered]));
    api.detail.mockResolvedValueOnce(ok({ ...detail('DETECTED', 7), caseView: recovered, measuredValue: 155 }));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-table"]').text()).toContain('已恢复正常');
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-start"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="param-case-closed"]').exists()).toBe(false);
    expect(api.resolve).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('loads a non-empty list and detail with current fact', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-table"]').text()).toContain('炒制');
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    expect(api.detail).toHaveBeenCalledWith('F006', 'case-1');
    expect(wrapper.find('[data-testid="param-case-detail"]').text()).toContain('165 ℃');
    wrapper.unmount();
  });

  it('renders read failure with an explicit retry instead of an empty state', async () => {
    api.list.mockRejectedValueOnce(new Error('服务不可用'));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-load-error"]').text()).toContain('服务不可用');
    expect(wrapper.find('[data-testid="param-case-empty"]').exists()).toBe(false);
    api.list.mockResolvedValueOnce(ok([row()]));
    await wrapper.find('[data-testid="param-case-retry"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-table"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('assigns, starts verification, and re-reads the authoritative detail after each action', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    api.detail
      .mockResolvedValueOnce(ok(detail()))
      .mockResolvedValueOnce(ok(detail('DETECTED', 7)));
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    wrapper.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 7);
    await flushPromises();
    await wrapper.find('[data-testid="param-case-assign"]').trigger('click');
    await flushPromises();
    expect(api.assign).toHaveBeenCalledWith('F006', 'case-1', { ownerUserId: 7, version: 1 });
    expect(api.detail).toHaveBeenCalledTimes(2);

    api.detail.mockResolvedValueOnce(ok(detail('EXECUTING', 7)));
    api.list.mockResolvedValueOnce(ok([row('EXECUTING', 7)]));
    await wrapper.find('[data-testid="param-case-start"]').trigger('click');
    await flushPromises();
    expect(api.start).toHaveBeenCalledWith('F006', 'case-1', { version: 1 });
    expect(api.detail).toHaveBeenCalledTimes(3);

    wrapper.unmount();
  });

  it('keeps the resolution note and selected conclusion on a 409', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    api.detail.mockResolvedValueOnce(ok(detail('EXECUTING', 7)));
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    wrapper.findAllComponents({ name: 'ElSelect' }).at(-1)!.vm.$emit('update:modelValue', 'CORRECTED');
    await flushPromises();
    await wrapper.find('[data-testid="param-case-note"]').setValue('已复核并纠正现场参数');
    api.resolve.mockRejectedValueOnce(new ApiError('异常已被别人更新, 请刷新后重试', 'CONFLICT', 409));
    await wrapper.find('[data-testid="param-case-resolve"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-action-error"]').text()).toContain('请刷新后重新确认');
    expect((wrapper.find('[data-testid="param-case-note"]').element as HTMLTextAreaElement).value).toContain('已复核');
    expect(wrapper.find('[data-testid="param-case-resolution"]').text()).toContain('已纠正现场参数');

    api.detail.mockResolvedValueOnce(ok(detail('EXECUTING', 7, null, 3)));
    await wrapper.find('[data-testid="param-case-detail-refresh"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-detail"]').text()).toContain('3');
    expect((wrapper.find('[data-testid="param-case-note"]').element as HTMLTextAreaElement).value).toContain('已复核');
    api.resolve.mockResolvedValueOnce(ok(null));
    await wrapper.find('[data-testid="param-case-resolve"]').trigger('click');
    await flushPromises();
    expect(api.resolve).toHaveBeenLastCalledWith('F006', 'case-1', {
      resolution: 'CORRECTED', note: '已复核并纠正现场参数', version: 3,
    });
    wrapper.unmount();
  });

  it('retries a failed detail read without showing a false empty detail', async () => {
    api.detail.mockRejectedValueOnce(new Error('详情暂不可用'));
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-detail-error"]').text()).toContain('详情暂不可用');
    api.detail.mockResolvedValueOnce(ok(detail()));
    await wrapper.find('[data-testid="param-case-detail-retry"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-detail"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('keeps probing pagination after a short page and labels an empty current page', async () => {
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-next"]').attributes('disabled')).toBeUndefined();
    api.list.mockResolvedValueOnce(ok([]));
    await wrapper.find('[data-testid="param-case-next"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-empty"]').text()).toContain('第 2 页没有记录');
    expect(wrapper.find('[data-testid="param-case-prev"]').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-testid="param-case-next"]').attributes('disabled')).toBeUndefined();
    wrapper.unmount();
  });

  it('allows probing page 1 after an empty page 0 because visibility filtering can hide a database page', async () => {
    api.list.mockResolvedValueOnce(ok([]));
    const wrapper = mountPanel();
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-empty"]').exists()).toBe(true);
    api.list.mockResolvedValueOnce(ok([row('CLOSED', 7)]));
    await wrapper.find('[data-testid="param-case-next"]').trigger('click');
    await flushPromises();
    expect(api.list).toHaveBeenLastCalledWith('F006', { openOnly: true, page: 1, size: 50 });
    expect(wrapper.find('[data-testid="param-case-table"]').exists()).toBe(true);
    wrapper.unmount();
  });

  it('makes a closed case read-only and never shows write actions', async () => {
    api.list.mockResolvedValue(ok([row('CLOSED', 7)]));
    api.detail.mockResolvedValue(ok(detail('CLOSED', 7, '已纠正')));
    const wrapper = mountPanel();
    await flushPromises();
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-closed"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="param-case-actions"]').exists()).toBe(false);
    expect(wrapper.find('[data-testid="param-case-resolve-form"]').exists()).toBe(false);
    expect(api.assign).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('does not load assignees or expose writes without permission', async () => {
    const wrapper = mountPanel(false);
    await flushPromises();
    await wrapper.find('[data-testid="param-case-open-case-1"]').trigger('click');
    await flushPromises();
    expect(wrapper.find('[data-testid="param-case-readonly"]').exists()).toBe(true);
    expect(api.assignees).not.toHaveBeenCalled();
    expect(wrapper.find('[data-testid="param-case-actions"]').exists()).toBe(false);
    wrapper.unmount();
  });
});
