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
    props: { option: { type: Object, required: true } },
    template: '<div class="factory-chart-canvas" data-testid="rendered-factory-chart">{{ option.title?.text }}</div>',
  }),
}));
vi.mock('../FactoryChartDetails.vue', () => ({
  default: defineComponent({ name: 'FactoryChartDetailsStub', template: '<div />' }),
}));
vi.mock('@/components/smartbi/PeriodSelector.vue', () => ({
  default: defineComponent({ name: 'PeriodSelectorStub', template: '<div data-testid="period-selector-stub" />' }),
}));

import FactoryAnalysisChat from '../FactoryAnalysisChat.vue';

const response = (overrides: Record<string, unknown> = {}) => ({
  success: true,
  message: '工厂分析完成',
  data: {
    factoryId: 'F006', source: 'business', sourceLabel: '业务系统', period: { label: '全部历史' },
    analysisSubject: null, analysisScopeLabel: '全厂范围', question: '生产表现怎样？', status: 'answered',
    answer: '已根据授权数据完成分析。', selectedIds: ['yield'],
    recommendations: [{
      id: 'yield', title: '产量趋势', metric: '产量', domain: '生产', priority: 'required', reason: '真实数据', source: '业务系统', unit: 'kg',
      findings: { good: [], attention: [], suggestions: [] }, chart: { chartType: 'line', echartsOption: { title: { text: '产量趋势' }, series: [] } },
      kpis: [{ label: '总产量', value: 12, unit: 'kg' }], tableData: [],
      detailContext: { id: 'detail-yield', expiresAt: '2099-01-01T00:00:00Z' },
    }],
    catalog: [{ id: 'yield', familyId: 'production.yield', chartType: 'line', domain: '生产', metric: '产量', unit: 'kg' }],
    facts: [{ id: 'fact-1', candidateId: 'yield', label: '总产量', value: 12, unit: 'kg', source: '业务系统' }],
    limitations: ['仅基于当前期间'], sourceErrors: [], followUpQuestions: ['再看质量指标'], contextId: 'ctx-1', subjects: [],
    ...overrides,
  },
});

let wrapper: VueWrapper | null = null;
beforeEach(() => {
  h.auth!.factoryId = 'F006'; h.auth!.currentRole = 'factory_super_admin'; h.auth!.user = { id: 1309 };
  h.route.query = {};
  h.post.mockReset().mockResolvedValue(response());
  h.uploads.mockReset().mockResolvedValue({ success: true, data: [] });
  h.capture.mockReset().mockResolvedValue({ yield: 'data:image/png;base64,chart' });
  h.build.mockReset().mockImplementation((_items: unknown, _images: unknown, context: unknown) => ({ context }));
  h.download.mockReset().mockResolvedValue(undefined);
});
afterEach(() => { wrapper?.unmount(); wrapper = null; });

async function mountChat() {
  wrapper = mount(FactoryAnalysisChat, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

describe('FactoryAnalysisChat mounted workflow', () => {
  it('sends the factory question and renders the server-selected chart and facts', async () => {
    const page = await mountChat();
    await page!.get('[data-testid="factory-analysis-input"]').setValue('生产表现怎样？');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    expect(h.post).toHaveBeenCalledWith('F006', expect.objectContaining({ question: '生产表现怎样？', source: 'business', periodType: 'all' }));
    expect(page!.find('[data-testid="factory-analysis-answer"]').text()).toContain('已根据授权数据完成分析');
    expect(page!.findAll('[data-testid="rendered-factory-chart"]')).toHaveLength(1);
    expect(page!.find('[data-testid="factory-analysis-facts"]').text()).toContain('12 kg');
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
    h.post.mockResolvedValueOnce(response({ period: { label: '2026年3月' }, contextId: 'ctx-1' }));
    await page!.get('[data-testid="factory-analysis-input"]').setValue('三月产量');
    await page!.get('form.composer').trigger('submit');
    await flushPromises();
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect(h.download).toHaveBeenCalledTimes(1);
    expect((h.build.mock.calls[0][2] as { periodLabel: string }).periodLabel).toBe('2026年3月');

    h.post.mockResolvedValueOnce(response({ period: { label: '2026年4月' }, contextId: 'ctx-2', answer: '四月完成。' }));
    await page!.get('.followups .el-button').trigger('click');
    await flushPromises();
    expect(page!.findAll('[data-testid^="factory-chart-card-"]')).toHaveLength(1);
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    expect((h.build.mock.calls[1][2] as { periodLabel: string }).periodLabel).toBe('2026年4月');

    let resolveCapture!: (value: unknown) => void;
    h.capture.mockImplementationOnce(() => new Promise(resolve => { resolveCapture = resolve; }));
    await page!.findAll('.turn--assistant').at(-1)!.find('.answer-tools .el-button').trigger('click');
    await flushPromises();
    (page!.vm as unknown as { onSourceChange: (value: 'upload' | 'business') => void }).onSourceChange('upload');
    resolveCapture({ yield: 'data:image/png;base64,late' });
    await flushPromises();
    expect(h.download).toHaveBeenCalledTimes(2);
  });
});
