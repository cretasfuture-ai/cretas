import { mount, flushPromises } from '@vue/test-utils';
import { defineComponent, h } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import CreatePlan from '../create.vue';

const boundary = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), push: vi.fn(), success: vi.fn(), warning: vi.fn() }));
vi.mock('@/api/request', () => ({ get: boundary.get, post: boundary.post, put: vi.fn(), del: vi.fn() }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F-P05' }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: boundary.push, back: vi.fn() }) }));
vi.mock('element-plus', () => ({ ElMessage: { success: boundary.success, warning: boundary.warning }, ElMessageBox: {} }));

const box = defineComponent({ setup(_, { slots }) { return () => h('div', [slots.header?.(), slots.default?.()]); } });
const button = defineComponent({ props: { loading: Boolean, disabled: Boolean }, setup(props, { slots }) { return () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()); } });
const dateInput = defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup(props, { emit }) { return () => h('input', { type: 'date', value: props.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLInputElement).value) }); } });
const notesInput = defineComponent({ props: { modelValue: String }, emits: ['update:modelValue'], setup(props, { emit }) { return () => h('textarea', { value: props.modelValue, onInput: (e: Event) => emit('update:modelValue', (e.target as HTMLTextAreaElement).value) }); } });

const capabilities = {
  contractVersion: 'scheduling-capabilities/v1',
  aiGeneration: { status: 'UNAVAILABLE', mode: 'UNAVAILABLE', reason: 'AI自动排程暂不可用；请使用手动创建。' },
  workerOptimization: { status: 'UNAVAILABLE', mode: 'UNAVAILABLE', reason: '自动人员优化暂不可用，请手动指派。' },
  reschedule: { status: 'AVAILABLE', mode: 'RECORD_REASON_ONLY', reason: '当前仅记录调整原因，不修改时间或资源。' },
  manualCreation: { status: 'AVAILABLE', mode: 'EXPLICIT_SCHEDULES', reason: '只保存用户明确提供的信息。' },
};

describe('scheduling create capability and manual payload contract', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    boundary.get.mockImplementation((url: string) => Promise.resolve({ success: true, data: url.includes('/processing/batches') ? { content: [{ id: 901, status: 'PLANNED', batchNumber: 'P05-TEST', plannedQuantity: 10, unit: 'kg' }] } : url.includes('/production-lines') ? [{ id: 'LINE-1', name: '一号线' }] : capabilities }));
    boundary.post.mockResolvedValue({ success: true, data: { id: 'PLAN-P05' } });
  });

  async function page(selectBatch = true) {
    const wrapper = mount(CreatePlan, { global: { directives: { loading: () => undefined }, stubs: { ElCard: box, ElForm: box, ElFormItem: box, ElIcon: box, ElButton: button, ElDatePicker: dateInput, ElInput: notesInput, ElCheckbox: true, ElEmpty: true } } });
    await flushPromises();
    await wrapper.get('input[type="date"]').setValue('2026-10-03');
    if (selectBatch) await wrapper.get('.batch-item').trigger('click');
    return wrapper;
  }

  it('renders server-owned AI unavailability and prevents stale AI submit', async () => {
    const wrapper = await page(false);
    expect(wrapper.find('#ai-generation-unavailable-reason').text()).toContain('AI自动排程暂不可用');
    const state = wrapper.vm as unknown as { useAI: boolean; handleSubmit: () => Promise<void> };
    state.useAI = true;
    await state.handleSubmit();
    expect(boundary.warning).toHaveBeenCalledWith('AI自动排程暂不可用；请使用手动创建。');
    expect(boundary.post).not.toHaveBeenCalled();
  });

  it('keeps automation unavailable and explains the recovery path when capabilities fail', async () => {
    boundary.get.mockImplementation((url: string) => {
      if (url.includes('/scheduling/capabilities')) return Promise.reject(new Error('capability unavailable'));
      return Promise.resolve({ success: true, data: url.includes('/processing/batches') ? { content: [] } : [] });
    });
    const output = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const wrapper = await page(false);
      expect(wrapper.find('#ai-generation-unavailable-reason').text()).toContain('能力状态加载失败');
      expect(wrapper.find('.capability-notice').text()).toContain('使用人工确认的排程草稿');
    } finally {
      output.mockRestore();
    }
  });

  it('sends explicit schedules and never sends ignored batchIds or autoAssignWorkers', async () => {
    const wrapper = await page();
    expect(wrapper.find('.qty-label').text()).toContain('kg');
    expect(wrapper.find('.manual-schedule-context').text()).toContain('kg');
    await wrapper.get('select').setValue('LINE-1');
    await wrapper.find('input[type="number"]').setValue('10');
    await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T08:00');
    await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T09:00');
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).toHaveBeenCalledExactlyOnceWith('/F-P05/scheduling/plans', { planDate: '2026-10-03', schedules: [{ productionLineId: 'LINE-1', batchId: 901, sequenceOrder: 1, plannedStartTime: '2026-10-03T08:00:00', plannedEndTime: '2026-10-03T09:00:00', plannedQuantity: 10 }] });
    expect(boundary.post.mock.calls[0][1]).not.toHaveProperty('batchIds');
    expect(boundary.post.mock.calls[0][1]).not.toHaveProperty('autoAssignWorkers');
    expect(boundary.success).toHaveBeenCalledWith('人工确认的排程草稿已创建');
  });

  it('renders the unavailable mode as a disabled button (no DAG entry) when the server says UNAVAILABLE', async () => {
    const wrapper = await page(false);
    expect(wrapper.find('[data-testid="mode-daily-schedule"]').exists()).toBe(false);
    expect(wrapper.find('button.mode-option.unavailable').attributes('disabled')).toBeDefined();
  });

  it.each([
    ['no line', async (wrapper: any) => { await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T08:00'); await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T09:00'); }],
    ['fractional quantity', async (wrapper: any) => { await wrapper.get('select').setValue('LINE-1'); await wrapper.find('input[type="number"]').setValue('1.5'); }],
    ['end before start', async (wrapper: any) => { await wrapper.get('select').setValue('LINE-1'); await wrapper.findAll('input[type="datetime-local"]')[0].setValue('2026-10-03T09:00'); await wrapper.findAll('input[type="datetime-local"]')[1].setValue('2026-10-03T08:00'); }],
  ])('blocks manual submission for %s', async (_label, fill) => {
    const wrapper = await page();
    await fill(wrapper);
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).not.toHaveBeenCalled();
    expect(boundary.warning).toHaveBeenCalledTimes(1);
  });
});

describe('scheduling create: daily-capacity auto-schedule when the server says AVAILABLE', () => {
  const available = {
    ...capabilities,
    aiGeneration: { status: 'AVAILABLE', mode: 'DAILY_CAPACITY', reason: '按产品「一天能做多少」按天排；两样都没有时当场填一个日产能再排；不自动派工。' },
  };
  const pending = [
    { id: 'PP-A', planNumber: 'PLAN-A', productTypeName: '卤猪蹄', plannedQuantity: 12, expectedCompletionDate: '2026-10-20', status: 'PENDING' },
    { id: 'PP-B', planNumber: 'PLAN-B', productTypeName: '酱牛肉', plannedQuantity: 5, expectedCompletionDate: '2026-10-21', status: 'PENDING' },
  ];
  const scheduled = { success: true, data: { id: 'SP-1', outcome: 'SCHEDULED', lineSchedules: [{ id: 'S-1' }], warnings: ['「卤猪蹄」没有填日产能，按历史估算 150 袋/天'] } };
  const capacityRequired = { success: true, data: {
    id: null, outcome: 'CAPACITY_REQUIRED', lineSchedules: [], warnings: [],
    capacityRequired: [
      { productTypeId: 'PT-BEEF', productName: '酱牛肉', unit: '袋', jointPlan: false, question: '「酱牛肉」一天能做多少 袋？', reason: 'MISSING', experienceNote: '近 90 天只有 2 天有成品入库，不足 3 天，算不出历史日产能', planNumbers: ['PLAN-B'] },
      { productTypeId: 'RAW-CHICKEN', productName: '西装鸡', unit: 'kg', jointPlan: true, question: '这个联产计划（西装鸡）一天能做多少 kg？', reason: 'MISSING', planNumbers: ['PLAN-A'] },
    ],
  } };

  function serve(lines: Array<{ id: string; name: string }>) {
    boundary.get.mockImplementation((url: string) => Promise.resolve({ success: true, data:
      url.includes('/scheduling/capabilities') ? available
        : url.includes('/scheduling/pending-batches') ? pending
        : url.includes('/production-lines') ? lines
        : url.includes('/processing/batches') ? { content: [] } : [] }));
  }

  beforeEach(() => {
    vi.clearAllMocks();
    boundary.post.mockResolvedValue(scheduled);
  });

  async function mountPage() {
    const wrapper = mount(CreatePlan, { global: { directives: { loading: () => undefined }, stubs: { ElCard: box, ElForm: box, ElFormItem: box, ElIcon: box, ElButton: button, ElDatePicker: dateInput, ElInput: notesInput, ElCheckbox: true, ElEmpty: true } } });
    await flushPromises();
    await wrapper.get('input[type="date"]').setValue('2026-10-19');
    return wrapper;
  }

  it('lets the user pick plans in order and sends planIds (never batchIds or a start time) then opens the detail with warnings', async () => {
    serve([{ id: 'LINE-1', name: '一号线' }]);
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="mode-daily-schedule"]').trigger('click');
    expect(wrapper.text()).toContain('不自动派工');
    expect(wrapper.text()).toContain('一天能做多少');
    expect(wrapper.find('[data-testid="aps-start-time"]').exists()).toBe(false); // 按天排, 不再问开工时刻
    // 点选顺序就是先到先排的顺序
    await wrapper.get('[data-testid="aps-plan-PP-B"]').trigger('click');
    await wrapper.get('[data-testid="aps-plan-PP-A"]').trigger('click');
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();

    expect(boundary.post).toHaveBeenCalledExactlyOnceWith('/F-P05/scheduling/generate', {
      planDate: '2026-10-19', planIds: ['PP-B', 'PP-A'], productionLineId: 'LINE-1',
    });
    expect(boundary.post.mock.calls[0][1]).not.toHaveProperty('batchIds');
    expect(boundary.push).toHaveBeenCalledWith({ path: '/scheduling/plans/SP-1', state: { apsWarnings: ['「卤猪蹄」没有填日产能，按历史估算 150 袋/天'] } });
  });

  it('asks on the spot for the missing daily capacities, blocks until all are filled, then resubmits with overrides', async () => {
    serve([{ id: 'LINE-1', name: '一号线' }]);
    boundary.post.mockResolvedValueOnce(capacityRequired).mockResolvedValueOnce(scheduled);
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="mode-daily-schedule"]').trigger('click');
    await wrapper.get('[data-testid="aps-plan-PP-B"]').trigger('click');
    await wrapper.get('[data-testid="aps-plan-PP-A"]').trigger('click');
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();

    // 什么都没保存: 不跳详情页, 当场列出要补的产品和该问的话
    expect(boundary.push).not.toHaveBeenCalled();
    const card = wrapper.get('[data-testid="aps-capacity-required"]');
    expect(card.get('[data-testid="aps-capacity-PT-BEEF"]').text()).toContain('「酱牛肉」一天能做多少 袋？');
    expect(card.get('[data-testid="aps-capacity-PT-BEEF"]').text()).toContain('不足 3 天');
    expect(card.get('[data-testid="aps-capacity-RAW-CHICKEN"]').text()).toContain('联产计划');
    expect(card.get('[data-testid="aps-capacity-RAW-CHICKEN"]').text()).toContain('kg/天');
    // 防呆: 没填完不能提交, 并说清差哪个
    expect(card.get('[data-testid="aps-capacity-block-reason"]').text()).toContain('酱牛肉');
    expect(card.get('[data-testid="aps-capacity-submit"]').attributes('disabled')).toBeDefined();

    await card.get('[data-testid="aps-capacity-PT-BEEF"] input').setValue('300');
    expect(wrapper.get('[data-testid="aps-capacity-block-reason"]').text()).toContain('西装鸡');
    await wrapper.get('[data-testid="aps-capacity-RAW-CHICKEN"] input').setValue('0');
    expect(wrapper.get('[data-testid="aps-capacity-block-reason"]').text()).toContain('西装鸡'); // 0 不是产能
    await wrapper.get('[data-testid="aps-capacity-RAW-CHICKEN"] input').setValue('200');
    expect(wrapper.find('[data-testid="aps-capacity-block-reason"]').exists()).toBe(false);
    await wrapper.get('[data-testid="aps-capacity-submit"]').trigger('click');
    await flushPromises();

    expect(boundary.post).toHaveBeenCalledTimes(2);
    expect(boundary.post.mock.calls[1]).toEqual(['/F-P05/scheduling/generate', {
      planDate: '2026-10-19', planIds: ['PP-B', 'PP-A'], productionLineId: 'LINE-1',
      dailyCapacityOverrides: [
        { productTypeId: 'PT-BEEF', quantityPerDay: 300, unit: '袋' },
        { productTypeId: 'RAW-CHICKEN', quantityPerDay: 200, unit: 'kg' },
      ],
    }]);
    expect(boundary.push).toHaveBeenCalledWith({ path: '/scheduling/plans/SP-1', state: { apsWarnings: ['「卤猪蹄」没有填日产能，按历史估算 150 袋/天'] } });
  });

  it('blocks submission up front when no plan is chosen or several lines exist without a choice', async () => {
    serve([{ id: 'LINE-1', name: '一号线' }, { id: 'LINE-2', name: '二号线' }]);
    const wrapper = await mountPage();
    await wrapper.get('[data-testid="mode-daily-schedule"]').trigger('click');
    expect(wrapper.get('[data-testid="aps-block-reason"]').text()).toContain('请至少选择一个生产计划');
    expect(wrapper.findAll('.submit-area button')[1].attributes('disabled')).toBeDefined();

    await wrapper.get('[data-testid="aps-plan-PP-A"]').trigger('click');
    expect(wrapper.get('[data-testid="aps-block-reason"]').text()).toContain('2 条产线');
    const state = wrapper.vm as unknown as { handleSubmit: () => Promise<void> };
    await state.handleSubmit();
    expect(boundary.post).not.toHaveBeenCalled();
    expect(boundary.warning).toHaveBeenCalledWith('工厂有 2 条产线，请选择要排在哪条产线上');

    await wrapper.get('[data-testid="aps-line"]').setValue('LINE-2');
    expect(wrapper.find('[data-testid="aps-block-reason"]').exists()).toBe(false);
    await wrapper.findAll('.submit-area button')[1].trigger('click');
    await flushPromises();
    expect(boundary.post).toHaveBeenCalledExactlyOnceWith('/F-P05/scheduling/generate', {
      planDate: '2026-10-19', planIds: ['PP-A'], productionLineId: 'LINE-2',
    });
  });
});
