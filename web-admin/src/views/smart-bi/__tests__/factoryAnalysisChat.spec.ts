import { defineComponent, reactive } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({
  auth: null as { factoryId: string; currentRole: string; user: { id: number } } | null,
  post: vi.fn(),
  uploads: vi.fn(),
  route: { query: {} as Record<string, unknown> },
  capture: vi.fn(),
  build: vi.fn(),
  download: vi.fn(),
  readyEmitters: [] as Array<() => void>,
  canvasReady: true,
}));

vi.mock('@/store/modules/auth', async () => {
  const { reactive: makeReactive } = await import('vue');
  h.auth = makeReactive({ factoryId: 'F006', currentRole: 'factory_super_admin', user: { id: 1309 } });
  return { useAuthStore: () => h.auth };
});
vi.mock('@/api/smartbi/factory-analysis', async () => {
  const actual = await vi.importActual<typeof import('@/api/smartbi/factory-analysis')>('@/api/smartbi/factory-analysis');
  return { ...actual, postFactoryAnalysis: h.post };
});
vi.mock('@/api/smartbi/upload', () => ({ getUploadHistory: h.uploads }));
vi.mock('vue-router', () => ({ useRoute: () => h.route }));
vi.mock('../factoryChartExport', () => ({
  captureFactoryChartImages: (...args: unknown[]) => h.capture(...args),
  buildFactoryChartExportPayload: (...args: unknown[]) => h.build(...args),
  downloadFactoryChartExport: (...args: unknown[]) => h.download(...args),
}));
vi.mock('../FactoryChartCanvas.vue', () => ({
  default: defineComponent({
    name: 'FactoryChartCanvasStub',
    emits: ['ready', 'click'],
    props: { option: { type: Object, required: true } },
    mounted() { const emitReady = () => this.$emit('ready'); h.readyEmitters.push(emitReady); if (h.canvasReady) queueMicrotask(emitReady); },
    template: '<div class="factory-chart-canvas" data-testid="rendered-factory-chart">{{ option.title?.text }}</div>',
  }),
}));
vi.mock('../FactoryChartDetails.vue', () => ({
  default: defineComponent({ name: 'FactoryChartDetailsStub', props: ['refreshScope'], template: '<div />' }),
}));
vi.mock('@/components/smartbi/PeriodSelector.vue', () => ({
  default: defineComponent({ name: 'PeriodSelectorStub', template: '<div data-testid="period-selector-stub" />' }),
}));

import FactoryAnalysisChat from '../FactoryAnalysisChat.vue';

const response = (overrides: Record<string, unknown> = {}) => {
  const period = overrides.period === null
    ? null
    : { label: '全部历史', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12, ...(overrides.period as Record<string, unknown> | undefined) };
  return {
    success: true,
    message: '工厂分析完成',
    data: {
    factoryId: 'F006', source: 'business', sourceLabel: '业务系统',
    analysisSubject: null, analysisScopeLabel: '全厂范围', question: '生产表现怎样？', status: 'answered',
    answer: '已根据授权数据完成分析。', selectedIds: ['yield'],
    recommendations: [{
      id: 'yield', title: '产量趋势', metric: '产量', domain: '生产', priority: 'required', reason: '真实数据', source: '业务系统', unit: 'kg',
      findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'line', echartsOption: { title: { text: '产量趋势' }, series: [] } },
      kpis: [{ label: '总产量', value: 12, unit: 'kg' }], tableData: [],
      detailContext: { id: 'detail-yield', expiresAt: '2099-01-01T00:00:00Z' },
    }],
    catalog: [{ id: 'yield', familyId: 'production.yield', familyLabel: '产量趋势', chartType: 'line', domain: '生产', metric: '产量', unit: 'kg' }],
    facts: [{ id: 'fact-1', candidateId: 'yield', label: '总产量', value: 12, unit: 'kg', source: '业务系统' }],
    limitations: ['仅基于当前期间'], sourceErrors: [], followUpQuestions: ['再看质量指标'], contextId: 'ctx-1', subjects: [],
      ...overrides,
      period,
    },
  };
};

function responseWithChartTitle(title: string, overrides: Record<string, unknown> = {}) {
  const result = response(overrides) as { data: { recommendations: Array<{ chart: { echartsOption: Record<string, unknown> } }> } };
  result.data.recommendations[0].chart.echartsOption = { title: { text: title }, series: [] };
  return result;
}

let wrapper: VueWrapper | null = null;
beforeEach(() => {
  h.auth!.factoryId = 'F006'; h.auth!.currentRole = 'factory_super_admin'; h.auth!.user = { id: 1309 };
  h.route.query = {};
  h.post.mockReset().mockResolvedValue(response());
  h.uploads.mockReset().mockResolvedValue({ success: true, data: [] });
  h.capture.mockReset().mockResolvedValue({ yield: 'data:image/png;base64,chart' });
  h.build.mockReset().mockImplementation((_items: unknown, _images: unknown, context: unknown) => ({ context }));
  h.download.mockReset().mockResolvedValue(undefined);
  h.readyEmitters = [];
  h.canvasReady = true;
});
afterEach(() => { wrapper?.unmount(); wrapper = null; });

async function mountChat() {
  wrapper = mount(FactoryAnalysisChat, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('FactoryAnalysisChat mounted workflow', () => {
  it('keeps the explicitly selected three reports in first questions and original detail refresh scopes', async () => {
    h.route.query = { source: 'upload', upload_id: '101', inspection_upload_id: '102', cost_upload_id: '103' };
    h.uploads.mockResolvedValue({ success: true, data: [101, 102, 103].map((id, index) => ({ id, fileName: '验收.xlsx', sheetName: ['生产', '质检', '成本'][index] })) });
    h.post.mockResolvedValue(response({ source: 'upload', uploadId: 101, jointUploads: { inspectionUploadId: 102, costUploadId: 103 } }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('联合分析');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(h.post).toHaveBeenNthCalledWith(1, 'F006', expect.objectContaining({ source: 'upload', uploadId: 101, inspectionUploadId: 102, costUploadId: 103 }));
    expect(page!.get('[data-testid="factory-analysis-turn-scope"]').text()).toContain('生产：验收.xlsx · 生产；质检：验收.xlsx · 质检；成本：验收.xlsx · 成本');
    const follow = page!.findAll('button').find(button => button.text() === '再看质量指标');
    await follow!.trigger('click');
    await flushPromises();
    expect(h.post).toHaveBeenNthCalledWith(2, 'F006', { question: '再看质量指标', contextId: 'ctx-1' });
  });

  it('does not send a partially selected three-table question or accept mismatching response IDs', async () => {
    h.route.query = { source: 'upload', upload_id: '101', inspection_upload_id: '102' };
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('联合分析');
    await page!.get('form.composer').trigger('submit');
    expect(h.post).not.toHaveBeenCalled();
    expect(page!.text()).toContain('三份不同的报表');
    page!.findComponent('[data-testid="factory-analysis-cost-upload"]').vm.$emit('update:modelValue', 103);
    await flushPromises();
    h.post.mockResolvedValue(response({ source: 'upload', uploadId: 101, jointUploads: { inspectionUploadId: 102, costUploadId: 104 } }));
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(page!.text()).toContain('返回的数据范围与所选报表不一致');
    expect(page!.find('[data-testid="factory-analysis-facts"]').exists()).toBe(false);
  });

  it('drops an in-flight uploaded answer if either related source is changed', async () => {
    h.route.query = { source: 'upload', upload_id: '101', inspection_upload_id: '102', cost_upload_id: '103' };
    let resolveAnswer: ((value: ReturnType<typeof response>) => void) | undefined;
    h.post.mockImplementationOnce(() => new Promise(resolve => { resolveAnswer = resolve; }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('联合分析');
    await page!.get('form.composer').trigger('submit');
    page!.findComponent('[data-testid="factory-analysis-inspection-upload"]').vm.$emit('update:modelValue', 104);
    await flushPromises();
    resolveAnswer!(response({ source: 'upload', uploadId: 101, jointUploads: { inspectionUploadId: 102, costUploadId: 103 } }));
    await flushPromises();
    expect(page!.find('[data-testid="factory-analysis-facts"]').exists()).toBe(false);
    expect(page!.text()).not.toContain('已根据授权数据完成分析。');
  });

  it('sends the factory question and renders the server-selected chart and facts', async () => {
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('生产表现怎样？');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(h.post).toHaveBeenCalledWith('F006', expect.objectContaining({ question: '生产表现怎样？', source: 'business', periodType: 'all' }));
    expect(page!.find('[data-testid="factory-analysis-answer"]').text()).toContain('已根据授权数据完成分析');
    expect(page!.find('[data-testid="factory-analysis-turn-scope"]').text()).toContain('工厂F006 · 业务系统 · 全部历史 · 全厂范围');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(1);
    expect(page!.find('[data-testid="factory-analysis-facts"]').text()).toContain('12 kg');
  });

  it('renders safe family labels, localized chart kinds and units without technical ids', async () => {
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('生产表现怎样？');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.get('.catalog-header .el-button').trigger('click');
    const catalogue = page!.find('.catalog-card');
    expect(catalogue.text()).toContain('产量趋势');
    expect(catalogue.text()).toContain('折线');
    expect(catalogue.text()).toContain('kg');
    expect(catalogue.text()).not.toContain('production.yield');
  });

  it('restores only validated route scope and sends the exact upload/month-range/product payload', async () => {
    h.route.query = {
      source: 'upload', upload_id: '42', year: '2026', period_type: 'month_range',
      start_month: '3', end_month: '5', analysis_subject: 'sku-butter',
    };
    h.uploads.mockResolvedValue({ success: true, data: [{ id: 42, fileName: '生产报表.xlsx' }] });
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('看这个产品的产量');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(h.post.mock.calls[0][1]).toEqual({
      question: '看这个产品的产量', source: 'upload', uploadId: 42, year: 2026,
      periodType: 'month_range', startMonth: 3, endMonth: 5, analysisSubject: 'sku-butter',
    });
  });

  it('uses the latest response context token for a follow-up', async () => {
    const page = await mountChat();
    h.post.mockResolvedValueOnce(response()).mockResolvedValueOnce(response({ contextId: 'ctx-2', answer: '追问完成。' }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('生产表现怎样？');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.get('.followups .el-button').trigger('click');
    await flushPromises();
    expect(h.post.mock.calls[1][1]).toEqual({ question: '再看质量指标', contextId: 'ctx-1' });
    expect(page!.text()).toContain('追问完成');
  });

  it('retries an expired context with the last accepted answer period and subject', async () => {
    h.route.query = { analysis_subject: 'sku-a' };
    h.post.mockResolvedValueOnce(response({
      period: { label: '2025年', year: 2025, periodType: 'year', startMonth: 1, endMonth: 12 },
      analysisSubject: 'sku-a', contextId: 'ctx-2025',
    }));
    h.post.mockRejectedValueOnce({ status: 410 });
    h.post.mockResolvedValueOnce(response({
      period: { label: '2025年', year: 2025, periodType: 'year', startMonth: 1, endMonth: 12 },
      analysisSubject: 'sku-a', contextId: 'ctx-2025-retry', answer: '已按原期间重新读取。',
    }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('去年产量');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.get('.followups .el-button').trigger('click');
    await flushPromises();
    const retry = page!.findAll('.turn--assistant').at(-1)!.findAll('.el-button')
      .find(button => button.text().includes('重新发送'));
    expect(retry).toBeTruthy();
    await retry!.trigger('click');
    await flushPromises();
    expect(h.post.mock.calls[2][1]).toEqual({
      question: '再看质量指标', source: 'business', year: 2025,
      periodType: 'year', startMonth: 1, endMonth: 12, analysisSubject: 'sku-a',
    });
    expect(page!.text()).toContain('已按原期间重新读取');
  });

  it('does not paint a delayed response after an auth identity change', async () => {
    const page = await mountChat();
    let resolve!: (value: unknown) => void;
    h.post.mockImplementationOnce(() => new Promise(value => { resolve = value; }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('旧身份问题');
    await page!.get('form.composer').trigger('submit');
    await Promise.resolve();
    h.auth!.factoryId = 'F007';
    resolve(response({ factoryId: 'F006', answer: '不应显示的旧答案' }));
    await flushPromises();
    expect(page!.text()).not.toContain('不应显示的旧答案');
  });

  it.each([
    ['a period', 'period'] as const,
    ['a product', 'product'] as const,
  ])('invalidates a delayed first request after changing %s', async (_label, scope) => {
    let resolveOld!: (value: unknown) => void;
    h.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('旧范围问题');
    await page!.get('form.composer').trigger('submit');
    await Promise.resolve();

    const vm = page!.vm as unknown as {
      periodChanged: (value: { type: 'month'; year: number; value: string; compareEnabled: boolean }) => void;
      onSubjectChange: (value: string | null) => void;
      contextId: string | null;
    };
    if (scope === 'period') {
      vm.periodChanged({ type: 'month', year: 2026, value: '2026-04', compareEnabled: false });
    } else {
      vm.onSubjectChange('sku-butter');
    }
    resolveOld(response({ answer: '不应显示的旧范围答案' }));
    await flushPromises();

    expect(page!.text()).not.toContain('不应显示的旧范围答案');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
    expect(vm.contextId).toBeNull();
    expect(h.capture).not.toHaveBeenCalled();
    expect(h.build).not.toHaveBeenCalled();
    expect(h.download).not.toHaveBeenCalled();
  });

  it('renders truthful unavailable and source-error states without fallback charts', async () => {
    h.post.mockResolvedValue(response({ status: 'unavailable', answer: 'AI图表规划暂不可用', recommendations: [], sourceErrors: [{ source: '业务系统', message: '读取失败' }] }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('看一下所有指标');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(page!.text()).toContain('AI图表规划暂不可用');
    expect(page!.text()).toContain('业务系统');
    expect(page!.text()).toContain('读取失败');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
  });

  it('uses the server clarification answer for period clarification without forcing product retry', async () => {
    h.post.mockResolvedValue(response({
      status: 'clarification',
      answer: '请明确有效的年份或月份区间。',
      recommendations: [],
      subjects: [],
      contextId: 'ctx-period-clarification',
    }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('看一下期间表现');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(page!.text()).toContain('请明确有效的年份或月份区间。');
    expect(page!.text()).toContain('输入有效期间后重新提问');
    expect(page!.text()).not.toContain('产品目录中明确分析对象');
    expect(page!.findAll('.el-button').filter(button => button.text().includes('按当前产品重试'))).toHaveLength(0);
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
  });

  it('keeps the pending product question and resolved period through the real picker change', async () => {
    const originalQuestion = '生产、质检和成本如何关联？';
    h.post.mockResolvedValueOnce(response({
      status: 'clarification', answer: '请选择需要分析的产品。', recommendations: [], facts: [],
      period: { label: '2025年9–10月', year: 2025, periodType: 'month_range', startMonth: 9, endMonth: 10 },
      contextId: 'ctx-choose-product', subjects: [{ id: 'sku-1', label: '产品甲' }],
    })).mockResolvedValueOnce(response({ analysisSubject: 'sku-1', answer: '已核对产品甲的生产与质检来源。' }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue(originalQuestion);
    await page!.get('form.composer').trigger('submit');
    await flushPromises();

    page!.findComponent({ name: 'ElSelectV2' }).vm.$emit('update:modelValue', 'sku-1');
    await flushPromises();
    expect(h.post).toHaveBeenCalledTimes(1);
    expect(page!.text()).toContain('请选择需要分析的产品。');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
    const retry = page!.findAll('button').find(button => button.text() === '按当前产品重试');
    expect(retry?.exists()).toBe(true);
    await retry!.trigger('click');
    await flushPromises();
    expect(h.post.mock.calls[1]).toEqual(['F006', {
      question: originalQuestion, source: 'business', analysisSubject: 'sku-1',
      year: 2025, periodType: 'month_range', startMonth: 9, endMonth: 10,
    }]);
    expect(page!.text()).toContain('已核对产品甲的生产与质检来源。');

    page!.findComponent({ name: 'ElSelectV2' }).vm.$emit('update:modelValue', 'sku-2');
    await flushPromises();
    expect(page!.text()).not.toContain('已核对产品甲的生产与质检来源。');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
    expect(page!.findAll('button').some(button => button.text() === '按当前产品重试')).toBe(false);
  });

  it('keeps product-guided mode in the original detail refresh request', async () => {
    const page = await mountChat();
    h.post.mockResolvedValueOnce(response({ analysisSubject: 'sku-1' }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('关联生产检验成本');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.find('.answer-chart .el-button').trigger('click');
    const scope = page!.findComponent({ name: 'FactoryChartDetailsStub' }).props('refreshScope');
    expect(scope.request).toMatchObject({ guided: true, use_ai: false, factory_id: 'F006', analysis_subject: 'sku-1' });
  });

  it('drops stale upload results after an identity epoch switch and unmounts raw details', async () => {
    let resolveOld!: (value: unknown) => void;
    h.uploads.mockReset()
      .mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }))
      .mockResolvedValueOnce({ success: true, data: [{ id: 7, fileName: '新工厂报表.xlsx' }] });
    const page = await mountChat();
    h.auth!.factoryId = 'F007';
    h.auth!.user = { id: 1400 };
    h.auth!.currentRole = 'viewer';
    await flushPromises();
    await new Promise(resolve => setTimeout(resolve, 10));
    await flushPromises();
    expect((page!.vm as unknown as { uploads: Array<{ fileName: string }> }).uploads.map(item => item.fileName)).toEqual(['新工厂报表.xlsx']);
    resolveOld({ success: true, data: [{ id: 99, fileName: '旧高权限报表.xlsx' }] });
    await flushPromises();
    expect((page!.vm as unknown as { uploads: Array<{ fileName: string }> }).uploads.map(item => item.fileName)).toEqual(['新工厂报表.xlsx']);

    h.auth!.factoryId = 'F006'; h.auth!.user = { id: 1309 }; h.auth!.currentRole = 'factory_super_admin';
    h.post.mockResolvedValueOnce(response());
    await page!.get('[data-testid="factory-analysis-input"]').setValue('打开明细');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.find('.answer-chart .el-button').trigger('click');
    expect(page!.findComponent({ name: 'FactoryChartDetailsStub' }).exists()).toBe(true);
    (page!.vm as unknown as { onSourceChange: (value: 'upload' | 'business') => void }).onSourceChange('upload');
    await flushPromises();
    expect(page!.findComponent({ name: 'FactoryChartDetailsStub' }).exists()).toBe(false);
  });

  it('exports each turn from its own snapshot and cancels download after scope changes', async () => {
    const page = await mountChat();
    h.post.mockResolvedValueOnce(response({ period: { label: '2025年', year: 2025, periodType: 'year', startMonth: 1, endMonth: 12 }, contextId: 'ctx-1' }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('三月产量');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.download).toHaveBeenCalledTimes(1);
    expect(h.build.mock.calls[0][2]).toEqual(expect.objectContaining({
      periodLabel: '2025年', year: 2025, periodType: 'year', startMonth: 1, endMonth: 12,
    }));

    h.post.mockResolvedValueOnce(response({ period: { label: '2024年', year: 2024, periodType: 'year', startMonth: 1, endMonth: 12 }, contextId: 'ctx-2', answer: '四月完成。' }));
    await page!.get('.followups .el-button').trigger('click');
    await flushPromises();
    expect(page!.findAll('[data-testid^="factory-chart-card-"]')).toHaveLength(1);
    // The earlier answer remains exportable after a later response rotates
    // the server period; it must retain its own 2025 snapshot.
    await page!.findAll('.turn--assistant').at(0)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.build.mock.calls[1][2]).toEqual(expect.objectContaining({
      periodLabel: '2025年', year: 2025, periodType: 'year', startMonth: 1, endMonth: 12,
    }));
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.build.mock.calls[2][2]).toEqual(expect.objectContaining({
      periodLabel: '2024年', year: 2024, periodType: 'year', startMonth: 1, endMonth: 12,
    }));

    let resolveCapture!: (value: unknown) => void;
    h.capture.mockImplementationOnce(() => new Promise(resolve => { resolveCapture = resolve; }));
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    (page!.vm as unknown as { onSourceChange: (value: 'upload' | 'business') => void }).onSourceChange('upload');
    resolveCapture({ yield: 'data:image/png;base64,late' });
    await flushPromises();
    expect(h.download).toHaveBeenCalledTimes(3);
  });

  it('waits for the active turn chart ready event and ignores a stale same-id chart event', async () => {
    h.canvasReady = false;
    h.post.mockResolvedValueOnce(responseWithChartTitle('旧图', { contextId: 'ctx-old' }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('旧问题');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    const oldReady = h.readyEmitters[h.readyEmitters.length - 1];
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.capture).not.toHaveBeenCalled();
    oldReady();
    await flushPromises();
    expect(h.capture).toHaveBeenCalledTimes(1);
    expect((h.capture.mock.calls[0][0] as Array<{ chart: { echartsOption: { title: { text: string } } } }>)[0].chart.echartsOption.title.text).toBe('旧图');

    h.post.mockResolvedValueOnce(responseWithChartTitle('新图', { contextId: 'ctx-new', answer: '新图回答' }));
    await page!.get('.followups .el-button').trigger('click');
    await flushPromises();
    const newReady = h.readyEmitters[h.readyEmitters.length - 1];
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.capture).toHaveBeenCalledTimes(1);
    oldReady();
    await flushPromises();
    expect(h.capture).toHaveBeenCalledTimes(1);
    newReady();
    await flushPromises();
    expect(h.capture).toHaveBeenCalledTimes(2);
    expect((h.capture.mock.calls[1][0] as Array<{ chart: { echartsOption: { title: { text: string } } } }>)[0].chart.echartsOption.title.text).toBe('新图');
  });

  it('rejects missing or invalid answer periods instead of exporting or opening details', async () => {
    h.post.mockResolvedValueOnce(response({ period: null }));
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('缺少期间');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(page!.text()).toContain('工厂分析响应缺少有效期间');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(0);
    expect(h.capture).not.toHaveBeenCalled();
    expect(h.build).not.toHaveBeenCalled();
    expect(h.download).not.toHaveBeenCalled();

    h.post.mockResolvedValueOnce(response({ period: { label: '2025年', year: 2025, periodType: 'year', startMonth: 0, endMonth: 12 } }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('非法期间');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(page!.text()).toContain('工厂分析响应缺少有效期间');
    expect(h.download).not.toHaveBeenCalled();
  });
});
