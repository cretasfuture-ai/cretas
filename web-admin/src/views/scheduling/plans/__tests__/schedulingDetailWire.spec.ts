import { mount, flushPromises } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Detail from '../detail.vue';
import { ElTable, ElTableColumn, ElButton, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { useRoute } from 'vue-router';

const { get, post, access } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), access: { state: { writable: true } } }));
vi.mock('@/api/request', () => ({ get, post, put: vi.fn(), del: vi.fn() }));
vi.mock('@/store/modules/permission', async () => {
  const { reactive } = await import('vue');
  access.state = reactive(access.state);
  return { usePermissionStore: () => ({ canWrite: () => access.state.writable }) };
});
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue');
  const auth = reactive({ factoryId: 'F-REAL' });
  return { useAuthStore: () => auth };
});
vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  const route = reactive({ params: { id: 'PLAN-1' } });
  return { useRoute: () => route, useRouter: () => ({ push: vi.fn() }) };
});
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn(() => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn(), on: vi.fn() })) } }));

describe('scheduling detail real wire mount', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    access.state.writable = true;
    useAuthStore().factoryId = 'F-REAL';
    useRoute().params.id = 'PLAN-1';
    get.mockImplementation((url: string) => url.includes('/scheduling/readiness')
      ? Promise.resolve({ success: true, data: {
        status: 'INPUTS_VALID_UNCONFIRMED', readyForAutomation: false, reason: '等待现场确认',
        productionLine: { id: 'LINE-1', selected: true, confirmed: false, source: 'USER_REFERENCE', reason: '待确认' },
        batch: { id: '9', selected: true, confirmed: false, source: 'USER_REFERENCE', reason: '待确认' },
        timeWindow: { valid: true, reason: '明确输入' },
        probability: { origin: 'UNAVAILABLE', reason: 'VERIFIED_MODEL_FEATURES_UNAVAILABLE', optimization: false }, missingInputs: [],
      } })
      : url.includes('/probabilities')
      ? Promise.resolve({ success: true, data: [] })
      : Promise.resolve({ success: true, data: {
        id: 'PLAN-1', planDate: '2026-10-19', status: 'DRAFT', lineSchedules: [{
          id: 'S-1', planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: 9,
          batchNumber: 'B-1', sequenceOrder: 1, plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:00:00',
          plannedQuantity: 120, assignedWorkers: 3, completedQuantity: 20, status: 'pending', workerAssignments: []
        }]
      } }));
  });

  function mountReasonPage() {
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    return mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElButton, ElProgress: box, ElTag: box,
        ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box,
        SchedulingDiagnosticsPanel: true },
    } });
  }

  it('records only a reason through the real API mapper and displays the persisted GET note', async () => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '  设备检修待确认  ', action: 'confirm' });
    const wrapper = mountReasonPage();
    try {
      await flushPromises();
      const priorGet = get.getMockImplementation()!;
      post.mockImplementation(async () => {
        get.mockImplementation(async (url: string) => {
          const result = await priorGet(url);
          return url.endsWith('/PLAN-1') ? { ...result, data: { ...result.data, notes: '原备注\n重新调度原因: 设备检修待确认 @ 2026-10-21T23:00:00' } } : result;
        });
        return { success: true, data: { id: 'PLAN-1', status: 'DRAFT', lineSchedules: [] } };
      });
      await wrapper.get('[data-testid="record-adjustment-reason"]').trigger('click');
      await flushPromises();
      expect(prompt).toHaveBeenCalledWith(expect.stringContaining('不修改时间或资源'), '记录调整原因', expect.any(Object));
      expect(post).toHaveBeenCalledExactlyOnceWith('/F-REAL/scheduling/reschedule', { planId: 'PLAN-1', reason: '设备检修待确认' });
      expect(get.mock.calls.filter(([url]) => url === '/F-REAL/scheduling/plans/PLAN-1').length).toBe(2);
      expect(wrapper.get('[data-testid="plan-notes"]').text()).toContain('重新调度原因: 设备检修待确认');
      expect(wrapper.text()).toContain('排程时间和资源未改变');
      expect(wrapper.text()).toContain('20/120');
    } finally { wrapper.unmount(); prompt.mockRestore(); }
  });

  it.each(['cancel', 'blank', 'server refusal'] as const)('never reports a saved reason on %s', async mode => {
    const prompt = vi.spyOn(ElMessageBox, 'prompt');
    if (mode === 'cancel') prompt.mockRejectedValue('cancel');
    else prompt.mockResolvedValue({ value: mode === 'blank' ? '  ' : '设备检修', action: 'confirm' });
    post.mockResolvedValue({ success: false, data: null, message: '当前权限拒绝' });
    const wrapper = mountReasonPage();
    try {
      await flushPromises();
      await wrapper.get('[data-testid="record-adjustment-reason"]').trigger('click');
      await flushPromises();
      expect(post).toHaveBeenCalledTimes(mode === 'server refusal' ? 1 : 0);
      expect(wrapper.text()).not.toContain('[调整] 设备检修');
      expect(get.mock.calls.filter(([url]) => url === '/F-REAL/scheduling/plans/PLAN-1').length).toBe(1);
    } finally { wrapper.unmount(); prompt.mockRestore(); }
  });

  it('does not expose the reason action without current module write permission', async () => {
    access.state.writable = false;
    const wrapper = mountReasonPage();
    try {
      await flushPromises();
      expect(wrapper.find('[data-testid="record-adjustment-reason"]').exists()).toBe(false);
      expect(post).not.toHaveBeenCalled();
    } finally { wrapper.unmount(); }
  });

  it.each(['factory', 'plan', 'permission'] as const)('rejects stale confirmation after %s changes', async field => {
    let confirm!: (result: { value: string; action: 'confirm' }) => void;
    const prompt = vi.spyOn(ElMessageBox, 'prompt').mockImplementation(() => new Promise(resolve => { confirm = resolve; }));
    const wrapper = mountReasonPage();
    try {
      await flushPromises();
      await wrapper.get('[data-testid="record-adjustment-reason"]').trigger('click');
      if (field === 'factory') useAuthStore().factoryId = 'OTHER';
      if (field === 'plan') useRoute().params.id = 'OTHER-PLAN';
      if (field === 'permission') access.state.writable = false;
      confirm({ value: '设备检修', action: 'confirm' });
      await flushPromises();
      expect(post).not.toHaveBeenCalled();
    } finally { wrapper.unmount(); prompt.mockRestore(); }
  });

  it('mounts the real API mapper and displays DTO target quantity and worker count', async () => {
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box, ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box }
    } });
    await flushPromises();
    const tableText = wrapper.find('.el-table').text();
    expect(tableText).toContain('20/120');
    expect(tableText).toContain('3');
    wrapper.unmount();
  });

  it('shows the auto-schedule task timeline per schedule and the generation warnings carried in history state', async () => {
    const baseGet = get.getMockImplementation()!;
    get.mockImplementation(async (url: string) => {
      const result = await baseGet(url);
      if (!url.endsWith('/PLAN-1')) return result;
      return { ...result, data: { ...result.data, lineSchedules: [{
        id: 'S-APS', planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: null,
        batchNumber: null, productionPlanId: 'PP-A', planNumber: 'PLAN-A', sequenceOrder: 0,
        plannedStartTime: '2026-10-19T08:00:00', plannedEndTime: '2026-10-19T09:25:00', plannedQuantity: 12,
        assignedWorkers: 0, completedQuantity: 0, status: 'pending', workerAssignments: [],
        taskTimeline: [
          { taskName: '分切', processOrder: 1, workProcessId: 'WP-CUT', start: '2026-10-19T08:00', end: '2026-10-19T08:30', status: 'SCHEDULED' },
          { taskName: '包装', processOrder: 4, workProcessId: 'WP-PACK', start: '2026-10-19T09:15', end: '2026-10-20T09:25', status: 'SCHEDULED' },
          { taskName: '解冻', processOrder: 0, workProcessId: 'WP-THAW', start: null, end: null, status: 'DONE' },
        ],
      }] } };
    });
    window.history.replaceState({ apsWarnings: [
      '计划「PLAN-B」有工序缺预计工时：「蒸煮」，这个计划没有排，也不占工位。请到「工序管理」给这些工序填预计分钟后再排',
    ] }, '');
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box,
        ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box,
        ElAlert: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
        SchedulingDiagnosticsPanel: true },
    } });
    try {
      await flushPromises();
      const warnings = wrapper.get('[data-testid="aps-warnings"]');
      expect(warnings.text()).toContain('蒸煮');
      expect(warnings.text()).toContain('去工序管理填预计工时');
      const timeline = wrapper.get('[data-testid="task-timeline"]');
      expect(timeline.text()).toContain('计划 PLAN-A');
      expect(timeline.text()).toContain('1. 分切');
      expect(timeline.text()).toContain('08:00');
      expect(timeline.text()).toContain('08:30');
      expect(timeline.text()).toContain('10-20 09:25'); // 跨天带日期
      expect(timeline.text()).toContain('已完成（不占时间）');
      expect(wrapper.find('.el-table').text()).toContain('计划 PLAN-A'); // 没有批次时排程列表显示计划号
    } finally {
      wrapper.unmount();
      window.history.replaceState(null, '');
    }
  });

  it('shows which plan is made on which day and how much (daily-capacity schedules)', async () => {
    const baseGet = get.getMockImplementation()!;
    const daily = (unit: string, cap: number, label: string, source: string, days: Array<[string, number, number]>) => ({
      unit, capacityPerDay: cap, capacitySource: source, capacitySourceLabel: label,
      plannedQuantity: 500, reportedQuantity: 200, remainingQuantity: 300,
      days: days.map(([date, quantity, shareOfDay]) => ({ date, quantity, shareOfDay })),
    });
    get.mockImplementation(async (url: string) => {
      const result = await baseGet(url);
      if (!url.endsWith('/PLAN-1')) return result;
      const base = { planId: 'PLAN-1', productionLineId: 'LINE-1', productionLineName: '一号线', batchId: null,
        batchNumber: null, assignedWorkers: 0, completedQuantity: 0, status: 'pending', workerAssignments: [] };
      return { ...result, data: { ...result.data, lineSchedules: [
        { ...base, id: 'S-A', productionPlanId: 'PP-A', planNumber: 'PLAN-A', sequenceOrder: 0, plannedQuantity: 40,
          plannedStartTime: '2026-10-19T00:00:00', plannedEndTime: '2026-10-19T23:59:00',
          dailyAllocations: daily('袋', 100, '人工填写', 'MANUAL', [['2026-10-19', 40, 0.4]]) },
        { ...base, id: 'S-B', productionPlanId: 'PP-B', planNumber: 'PLAN-B', sequenceOrder: 1, plannedQuantity: 300,
          plannedStartTime: '2026-10-19T00:00:00', plannedEndTime: '2026-10-20T23:59:00',
          dailyAllocations: daily('kg', 200, '按历史估算', 'EXPERIENCE', [['2026-10-19', 120, 0.6], ['2026-10-20', 180, 0.9]]) },
      ] } };
    });
    window.history.replaceState(null, '');
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box,
        ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box,
        SchedulingDiagnosticsPanel: true },
    } });
    try {
      await flushPromises();
      const card = wrapper.get('[data-testid="daily-allocations"]');
      expect(card.get('[data-testid="daily-capacity-S-A"]').text()).toContain('100 袋/天');
      expect(card.get('[data-testid="daily-capacity-S-A"]').text()).toContain('人工填写');
      expect(card.get('[data-testid="daily-capacity-S-B"]').text()).toContain('按历史估算');
      expect(card.get('[data-testid="daily-capacity-S-B"]').text()).toContain('已入库 200');
      const rows = card.findAll('.el-table__body tr').map(r => r.text().replace(/\s+/g, ' '));
      // 按日期 → 排程顺序: 10-19 先 A 后 B, 10-20 只有 B
      expect(rows).toHaveLength(3);
      expect(rows[0]).toContain('2026-10-19');
      expect(rows[0]).toContain('计划 PLAN-A');
      expect(rows[0]).toContain('40 袋');
      expect(rows[0]).toContain('40%');
      expect(rows[1]).toContain('计划 PLAN-B');
      expect(rows[1]).toContain('120 kg');
      expect(rows[1]).toContain('60%');
      expect(rows[2]).toContain('2026-10-20');
      expect(rows[2]).toContain('180 kg');
      expect(wrapper.find('[data-testid="task-timeline"]').exists()).toBe(false); // v2 没有工序时间线
      // 2026-10-28 线上验收: 排程列表「时间」原来只显示 00:00 - 23:59, 跨天看不出是哪几天
      const text = wrapper.text().replace(/\s+/g, ' ');
      expect(text).toContain('10-19（1 天）');
      expect(text).toContain('10-19 至 10-20（2 天）');
      expect(text).not.toContain('00:00 - 23:59');
      // 计划级排程没有批次: 不挂按批次算的输入检查 (原来误报「产线或批次不属于当前工厂」), 风险不是「极高风险」
      expect(wrapper.findAllComponents({ name: 'SchedulingDiagnosticsPanel' })).toHaveLength(0);
      expect(text).toContain('未计算');
      expect(text).not.toContain('极高风险');
    } finally {
      wrapper.unmount();
    }
  });

  it('shows no warning block when the page is opened directly (warnings are not persisted)', async () => {
    window.history.replaceState(null, '');
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box,
        ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box,
        ElAlert: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
        SchedulingDiagnosticsPanel: true },
    } });
    try {
      await flushPromises();
      expect(wrapper.find('[data-testid="aps-warnings"]').exists()).toBe(false);
      expect(wrapper.find('[data-testid="task-timeline"]').exists()).toBe(false); // 手工排程没有时间线
    } finally {
      wrapper.unmount();
    }
  });

  it('passes an existing line schedule context through the real diagnostics Panel and API', async () => {
    const box = { template: '<div><slot name="header"/><slot/></div>' };
    const wrapper = mount(Detail, { global: {
      directives: { loading: () => undefined },
      stubs: { ElCard: box, ElTable, ElTableColumn, ElProgress: box, ElTag: box, ElButton: box,
        ElDialog: box, ElForm: box, ElFormItem: box, ElInputNumber: box, ElEmpty: box, ElIcon: box,
        ElAlert: { props: ['title'], template: '<div>{{ title }}</div>' } },
    } });
    try {
      await flushPromises();
      expect(get).toHaveBeenCalledWith('/F-REAL/scheduling/readiness', { params: {
        scheduleId: 'S-1', productionLineId: undefined, batchId: undefined,
        startTime: undefined, endTime: undefined,
      } });
      expect(wrapper.text()).toContain('尚未生成概率');
      expect(wrapper.text()).toContain('缺少已确认的人员、设备或产品特征');
    } finally {
      wrapper.unmount();
    }
  });
});
