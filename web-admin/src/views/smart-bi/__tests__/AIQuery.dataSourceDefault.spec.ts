/**
 * PROTO-YDWM B2/B3 — 问数据 (AI) 的数据源下拉 (2026-11-19 味美实测)。
 *
 * B3: 默认选「全部数据」, 不是最新一份上传 (演示时容易问错表)。
 * B2: 保存/解析失败 (status 非 COMPLETED) 或空的上传不进下拉, 也就不会成为默认数据源。
 *
 * 真实挂载 AIQuery, 只 mock API; 用带 data-testid 的 el-select 桩读出真实的 v-model 与选项。
 * 阴性/对照: 选中某份上传后, 发出去的问题带这份 uploadId (证明「全部数据」时没带是真的没带, 不是桩坏了)。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import { defineComponent, h as vh } from 'vue';

const hoisted = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string> },
}));

const executeIntentMock = vi.fn();
const chatAnalysisStreamMock = vi.fn((..._a: unknown[]) => ({ abort: vi.fn() }));
const getUploadHistoryMock = vi.fn();

vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: (...a: unknown[]) => executeIntentMock(...a),
  confirmIntentAction: vi.fn(),
  getPurchaseTaskGrantStatus: vi.fn(),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));
vi.mock('@/api/smartbi', () => ({
  chatAnalysis: vi.fn(async () => ({ success: false })),
  chatAnalysisStream: (...a: unknown[]) => chatAnalysisStreamMock(...a),
  getUploadHistory: (...a: unknown[]) => getUploadHistoryMock(...a),
  // 同名去重不是本测试要测的东西: 恒等, 让断言只看「谁进了下拉」
  deduplicateUploads: (xs: unknown[]) => xs,
  nl2sql: vi.fn(),
  logFeedback: vi.fn(async () => ({})),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F001', businessDomain: 'FACTORY', user: { id: 1 } }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => hoisted.routeMock,
  useRouter: () => ({ push: vi.fn() }),
}));
vi.mock('@/utils/echarts', () => ({
  default: { init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }) },
}));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (o: unknown) => o }));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('marked', () => ({ marked: (s: string) => s }));
vi.mock('dompurify', () => ({ default: { sanitize: (s: string) => s } }));
vi.mock('@/components/smartbi', () => ({ AIInsightPanel: { template: '<div />' } }));
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/components/smart-bi/MaterializedAnalysisPanel.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../FactoryAnalysisChat.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/materialized', () => ({
  listFactoryTemplates: vi.fn(async () => ({ success: true, templates: [] })),
}));

import AIQuery from '../AIQuery.vue';

// 带 data-testid 的下拉桩: 暴露 modelValue, 选项渲染成可读的 data-value, 点选项会 emit update:modelValue
const SelectStub = defineComponent({
  props: ['modelValue'],
  emits: ['update:modelValue'],
  setup(props, { slots, emit }) {
    return () =>
      vh('div', { class: 'sel', 'data-model': String(props.modelValue) }, [
        vh('div', { class: 'opts', onPick: (v: unknown) => emit('update:modelValue', v) }, slots.default?.()),
      ]);
  },
});
const OptionStub = defineComponent({
  props: ['value', 'label'],
  setup(props) {
    return () => vh('span', { class: 'opt', 'data-value': String(props.value), 'data-label': props.label });
  },
});

const stubs = {
  'el-button': { template: '<button><slot /></button>' },
  'el-link': { template: '<a><slot /></a>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': { template: '<div><slot :item="{ value: \'\' }" /></div>' },
  'el-tag': { template: '<span><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table><slot /></table>' },
  'el-table-column': { template: '<td><slot /></td>' },
  'el-select': SelectStub,
  'el-option': OptionStub,
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { template: '<div><slot /></div>' },
};

const up = (id: number, name: string, over: Record<string, unknown> = {}) => ({
  id, fileName: name, sheetName: 'Sheet1', tableType: 'general',
  rowCount: 100, columnCount: 5, status: 'COMPLETED', createdAt: '2026-11-19T10:00:00', ...over,
});

describe('AIQuery 数据源下拉: 默认全部数据 + 失败上传不进下拉 (PROTO-YDWM B2/B3)', () => {
  let wrappers: VueWrapper[] = [];

  beforeEach(() => {
    setActivePinia(createPinia());
    executeIntentMock.mockReset();
    chatAnalysisStreamMock.mockClear();
    getUploadHistoryMock.mockReset();
  });
  afterEach(() => {
    wrappers.forEach((w) => w.unmount());
    wrappers = [];
  });

  async function mountWith(uploads: unknown[]) {
    getUploadHistoryMock.mockResolvedValue({ success: true, data: uploads });
    const w = mount(AIQuery, { global: { stubs } });
    wrappers.push(w);
    await flushPromises();
    return w;
  }

  const sourceSelect = (w: VueWrapper) => w.find('[data-testid="aiq-data-source-select"]');

  it('B3: 有多份上传时默认值是「全部数据」, 不是最新一份', async () => {
    const w = await mountWith([
      up(11, '04醒发蒸制.xlsx', { createdAt: '2026-11-19T09:00:00' }),
      up(12, '最新计划vs实产.xlsx', { createdAt: '2026-11-19T11:30:00' }),
    ]);
    const sel = sourceSelect(w);
    expect(sel.exists()).toBe(true);
    expect(sel.attributes('data-model')).toBe('__ALL__');
    // 对照: 选项里「全部数据」排第一, 两份上传都在
    const opts = sel.findAll('.opt').map((o) => o.attributes('data-value'));
    expect(opts).toEqual(['__ALL__', '11', '12']);
  });

  it('B3: 默认「全部数据」时提问不带 uploadId; 选定某份后才带 (对照)', async () => {
    const w = await mountWith([up(11, 'a.xlsx'), up(12, 'b.xlsx')]);
    const vm = w.vm as unknown as {
      inputQuery: string;
      handleSendMessage: () => Promise<void>;
    };
    vm.inputQuery = '醒发表平均温度多少';
    await vm.handleSendMessage();
    await flushPromises();
    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(1);
    const first = chatAnalysisStreamMock.mock.calls[0][0] as { uploadId?: string };
    expect(first.uploadId).toBeUndefined();

    // 对照: 用户在下拉里钉住 12, 再问就带 12
    sourceSelect(w).findComponent(SelectStub).vm.$emit('update:modelValue', 12);
    await flushPromises();
    expect(sourceSelect(w).attributes('data-model')).toBe('12');
    vm.inputQuery = '再问一次';
    await vm.handleSendMessage();
    await flushPromises();
    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(2);
    const second = chatAnalysisStreamMock.mock.calls[1][0] as { uploadId?: string };
    expect(second.uploadId).toBe('12');
  });

  it('B2: 失败 / 空的上传不进下拉, 好的上传仍在 (阴性对照)', async () => {
    const w = await mountWith([
      up(21, 'ok.xlsx'),
      up(22, '保存失败的.xlsx', { status: 'FAILED' }),
      up(23, '空表.xlsx', { rowCount: 0 }),
      up(24, '处理中.xlsx', { status: 'PROCESSING' }),
    ]);
    const opts = sourceSelect(w).findAll('.opt').map((o) => o.attributes('data-value'));
    expect(opts).toEqual(['__ALL__', '21']);
  });

  it('B2: 只剩失败上传时没有数据源下拉, 也没有被选成默认', async () => {
    const w = await mountWith([up(31, '失败.xlsx', { status: 'FAILED' })]);
    expect(sourceSelect(w).exists()).toBe(false);
  });
});
