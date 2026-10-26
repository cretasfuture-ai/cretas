import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Panel from '../SchedulingDiagnosticsPanel.vue';

const { get } = vi.hoisted(() => ({ get: vi.fn() }));
vi.mock('@/api/request', () => ({ get }));

describe('SchedulingDiagnosticsPanel', () => {
  beforeEach(() => get.mockReset());
  it('renders actionable inactive-line and unschedulable-batch reasons without claiming confirmed inputs', async () => {
    const base = payload('缺少或无法验证明确输入');
    get.mockResolvedValue({ success: true, data: { ...base.data,
      productionLine: { ...base.data.productionLine, reason: '产线当前不可创建新排程，请选择启用产线' },
      batch: { ...base.data.batch, reason: '批次状态不允许创建新排程，请选择计划中批次' },
      missingInputs: ['productionLineId:not_schedulable', 'batchId:not_schedulable'],
    } });
    const wrapper = mountPanel({ factoryId: 'F-READINESS' });
    try {
      await flushPromises();
      expect(wrapper.text()).toContain('启用状态的产线');
      expect(wrapper.text()).toContain('计划中批次');
      expect(wrapper.text()).toContain('批次状态不允许创建新排程');
      expect(wrapper.text()).not.toContain('输入完整，等待现场确认');
      expect(get.mock.calls.every(([url]) => url === '/F-READINESS/scheduling/readiness')).toBe(true);
    } finally { wrapper.unmount(); }
  });
  it('mounts the read-only readiness contract and shows missing input provenance', async () => {
    get.mockResolvedValue({ success: true, data: {
      status: 'INCOMPLETE', readyForAutomation: false, reason: '缺少或无法验证明确输入',
      productionLine: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '未提供明确产线引用' },
      batch: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '未提供明确批次引用' },
      timeWindow: { valid: false, reason: '必须明确提供开始和结束时间' },
      probability: { origin: 'UNAVAILABLE', reason: '排产引擎不可用', optimization: false },
      missingInputs: ['productionLineId', 'batchId', 'timeWindow'],
    }});
    const wrapper = mount(Panel, {
      props: { factoryId: 'F-READINESS' },
      global: { directives: { loading: () => undefined }, stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot/></div>' },
      } },
    });
    await flushPromises();
    expect(get).toHaveBeenCalledWith('/F-READINESS/scheduling/readiness', { params: {
      productionLineId: undefined, batchId: undefined, startTime: undefined, endTime: undefined, scheduleId: undefined,
    }});
    expect(wrapper.text()).toContain('缺少排程输入');
    expect(wrapper.text()).toContain('产线');
    expect(wrapper.text()).toContain('尚未生成概率');
  });

  it('keeps the newest selection when responses arrive out of order', async () => {
    let resolveFirst!: (value: unknown) => void;
    let resolveSecond!: (value: unknown) => void;
    get.mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve; }));
    const wrapper = mount(Panel, {
      props: { factoryId: 'F-READINESS', productionLineId: 'LINE-1' },
      global: { directives: { loading: () => undefined }, stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot/></div>' },
      } },
    });
    await wrapper.setProps({ productionLineId: 'LINE-2' });
    resolveSecond({ success: true, data: { status: 'INCOMPLETE', readyForAutomation: false, reason: '最新', productionLine: { id: 'LINE-2', selected: true, confirmed: false, source: 'USER_REFERENCE', reason: '最新' }, batch: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '缺少' }, timeWindow: { valid: false, reason: '缺少' }, probability: { origin: 'UNAVAILABLE', reason: '不可用', optimization: false }, missingInputs: [] } });
    await flushPromises();
    resolveFirst({ success: true, data: { status: 'INCOMPLETE', readyForAutomation: false, reason: '旧', productionLine: { id: 'LINE-1', selected: true, confirmed: false, source: 'USER_REFERENCE', reason: '旧' }, batch: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '缺少' }, timeWindow: { valid: false, reason: '缺少' }, probability: { origin: 'UNAVAILABLE', reason: '不可用', optimization: false }, missingInputs: [] } });
    await flushPromises();
    expect(wrapper.text()).toContain('最新');
    expect(wrapper.text()).not.toContain('旧');
  });

  it('renders an actionable inline error when the diagnostic request fails', async () => {
    get.mockRejectedValueOnce(new Error('network'));
    const wrapper = mount(Panel, { props: { factoryId: 'F-READINESS' }, global: {
      directives: { loading: () => undefined }, stubs: {
        ElCard: { template: '<section><slot name="header"/><slot/></section>' },
        ElAlert: { props: ['title'], template: '<div>{{ title }}<slot/></div>' },
      },
    } });
    await flushPromises();
    expect(wrapper.text()).toContain('未创建或调整排程');
  });

  it.each([{}, { status: 'INCOMPLETE' }, { ...payload('invalid').data, productionLine: null },
    { ...payload('invalid').data, missingInputs: null }])('rejects malformed successful readiness data without rendering it', async data => {
    get.mockResolvedValueOnce({ success: true, data });
    const wrapper = mountPanel({ factoryId: 'F-READINESS' });
    await flushPromises();
    expect(wrapper.text()).toContain('输入检查响应格式无效');
    expect(wrapper.text()).not.toContain('invalid');
  });

  it('invalidates the previous tenant response when the factory is cleared', async () => {
    let resolveOld!: (value: unknown) => void;
    get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = mountPanel({ factoryId: 'F-OLD' });
    await wrapper.setProps({ factoryId: '' });
    resolveOld(payload('旧工厂结果'));
    await flushPromises();
    expect(wrapper.text()).toContain('尚未选择工厂');
    expect(wrapper.text()).not.toContain('旧工厂结果');
  });

  it('refreshes and invalidates previous results when either schedule or factory changes', async () => {
    let resolveOld!: (value: unknown) => void;
    get.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce(payload('新排程结果')).mockResolvedValueOnce(payload('新工厂结果'));
    const wrapper = mountPanel({ factoryId: 'F-OLD', scheduleId: 'OLD-SCHEDULE' });
    await wrapper.setProps({ scheduleId: 'NEW-SCHEDULE' });
    await flushPromises();
    expect(get).toHaveBeenLastCalledWith('/F-OLD/scheduling/readiness', { params: expect.objectContaining({ scheduleId: 'NEW-SCHEDULE' }) });
    expect(wrapper.text()).toContain('新排程结果');
    await wrapper.setProps({ factoryId: 'F-NEW' });
    await flushPromises();
    resolveOld(payload('旧工厂结果'));
    await flushPromises();
    expect(wrapper.text()).toContain('新工厂结果');
    expect(wrapper.text()).not.toContain('旧工厂结果');
  });
});

function mountPanel(props: { factoryId: string; scheduleId?: string }) {
  return mount(Panel, { props, global: {
    directives: { loading: () => undefined }, stubs: {
      ElCard: { template: '<section><slot name="header"/><slot/></section>' },
      ElAlert: { props: ['title'], template: '<div>{{ title }}<slot/></div>' },
    },
  } });
}

function payload(reason: string) {
  return { success: true, data: {
    status: 'INCOMPLETE', readyForAutomation: false, reason,
    productionLine: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '未选择产线' },
    batch: { selected: false, confirmed: false, source: 'MISSING_INPUT', reason: '未选择批次' },
    timeWindow: { valid: false, reason: '未提供时间' },
    probability: { origin: 'UNAVAILABLE', reason: '未计算', optimization: false }, missingInputs: [],
  } };
}
