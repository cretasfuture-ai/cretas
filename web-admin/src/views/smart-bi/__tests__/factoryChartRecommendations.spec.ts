import { defineComponent, nextTick } from 'vue';
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const h = vi.hoisted(() => ({
  authStore: null as { factoryId: string; currentRole?: string; user?: { id: number } } | null,
  api: { getFactoryChartRecommendations: vi.fn(), getUploadHistory: vi.fn(), getFactoryChartSelection: vi.fn(), saveFactoryChartSelection: vi.fn() },
  route: { query: {} as Record<string, unknown> },
  replace: vi.fn(),
}));

vi.mock('@/store/modules/auth', async () => {
  const { reactive: makeReactive } = await import('vue');
  h.authStore = makeReactive({ factoryId: 'F006' });
  return { useAuthStore: () => h.authStore };
});
vi.mock('vue-router', () => ({ useRoute: () => h.route, useRouter: () => ({ replace: h.replace }) }));
vi.mock('@/api/smartbi/factory-charts', async () => {
  const actual = await vi.importActual<typeof import('@/api/smartbi/factory-charts')>('@/api/smartbi/factory-charts');
  return { ...actual, getFactoryChartRecommendations: h.api.getFactoryChartRecommendations, getFactoryChartSelection: h.api.getFactoryChartSelection, saveFactoryChartSelection: h.api.saveFactoryChartSelection };
});
vi.mock('@/api/smartbi/upload', () => ({
  getUploadHistory: h.api.getUploadHistory,
}));
vi.mock('../FactoryChartCanvas.vue', () => ({
  default: defineComponent({
    name: 'FactoryChartCanvasStub',
    props: { option: { type: Object, required: true } },
    template: '<div class="chart-stub" :data-title="option.title?.text || \'chart\'">chart</div>',
  }),
}));

import FactoryChartRecommendations from '../FactoryChartRecommendations.vue';
import FactoryChartGuide from '../FactoryChartGuide.vue';
import PeriodSelector from '@/components/smartbi/PeriodSelector.vue';

const response = (overrides: Record<string, unknown> = {}) => ({
  success: true,
  data: {
    factoryId: 'F006', source: 'business', sourceLabel: '业务系统', period: { label: '全部历史' },
    analysisSubject: null,
    ranking: { mode: 'rule', reason: '按规则优先级排序，未经AI排序' },
    recommendations: [
      {
        id: 'yield', title: '产量趋势', metric: '产量', domain: '生产', priority: 'required', reason: '有生产日期和产量', source: 'business', unit: 'kg',
        findings: { good: ['产量连续记录'], attention: ['换产波动'], suggestions: ['按批次复核'] },
        chart: { chartType: 'line', echartsOption: { title: { text: '产量趋势' }, xAxis: {}, yAxis: {}, series: [] } },
        kpis: [{ label: '总产量', value: 120, unit: 'kg' }], tableData: [],
      },
      {
        id: 'margin', title: '毛利趋势', metric: '毛利', domain: '经营', priority: 'optional', reason: '有价格字段', aiReason: 'AI补充排序理由', source: 'business', unit: '元',
        findings: { good: [], attention: ['价格波动'], suggestions: [] },
        chart: { chartType: 'line', echartsOption: { title: { text: '毛利趋势' }, series: [] } },
        kpis: [{ label: '毛利', value: '—', unit: '元' }], tableData: [],
      },
    ],
    unavailable: [{ id: 'inventory', title: '库存周转', missing: ['库存流水'] }],
    sourceErrors: [],
    ...overrides,
  },
});

let wrapper: VueWrapper | null = null;
async function mountPage() {
  wrapper = mount(FactoryChartRecommendations, { global: { plugins: [ElementPlus] } });
  await flushPromises();
  return wrapper;
}

async function openCatalog(page: VueWrapper) {
  const input = page.get('[data-testid="factory-chart-mode"] input[value="browse"]');
  await input.setValue(true);
  await nextTick();
}

beforeEach(() => {
  localStorage.clear();
  h.authStore!.factoryId = 'F006';
  h.authStore!.currentRole = 'factory_super_admin';
  h.authStore!.user = { id: 1309 };
  h.route.query = {};
  h.replace.mockReset();
  h.api.getUploadHistory.mockReset().mockResolvedValue({ success: true, data: [
    { id: 42, fileName: '生产报表.xlsx', sheetName: '产量', rowCount: 8, columnCount: 4, status: 'COMPLETED', createdAt: '2026-10-01' },
  ] });
  h.api.getFactoryChartRecommendations.mockReset().mockResolvedValue(response());
  h.api.getFactoryChartSelection.mockReset().mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: null, updatedAt: null } });
  h.api.saveFactoryChartSelection.mockReset().mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: [], updatedAt: 'now' } });
});
afterEach(() => { wrapper?.unmount(); wrapper = null; document.body.innerHTML = ''; });

describe('FactoryChartRecommendations mounted workflow', () => {
  it('uses distinct server defaults without claiming an unset preference was saved', async () => {
    h.api.getFactoryChartRecommendations.mockResolvedValue(response({ defaultSelection: ['margin'] }));
    const page = await mountPage();
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('毛利趋势');
    expect(page!.text()).toContain('尚未保存选择');
    expect(h.api.saveFactoryChartSelection).not.toHaveBeenCalled();
    await page!.get('[data-testid="factory-chart-save-selection"]').trigger('click');
    await flushPromises();
    expect(h.api.saveFactoryChartSelection).toHaveBeenCalledWith('F006', 'business', ['margin']);
    expect(page!.text()).toContain('选择已同步');
  });
  it('keeps authoritative intent across a late GET and period changes', async () => {
    let resolvePreference!: (value: unknown) => void;
    h.api.getFactoryChartSelection.mockImplementationOnce(() => new Promise(resolve => { resolvePreference = resolve; }));
    const page = await mountPage();
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ recommendations: [response().data.recommendations[1]] }));
    page!.findComponent(PeriodSelector).vm.$emit('change', { type: 'year', year: 2026, value: '2026', allHistory: false, compareEnabled: false });
    await flushPromises();
    resolvePreference({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['yield'], updatedAt: 'now' } });
    await flushPromises();
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('毛利趋势');
    page!.findComponent(PeriodSelector).vm.$emit('change', { type: 'year', year: 2026, value: '2026', allHistory: true, compareEnabled: false });
    await flushPromises();
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('产量趋势');
    expect(h.api.saveFactoryChartSelection).not.toHaveBeenCalled();
  });
  it('serializes explicit saves and captures their source before a source switch', async () => {
    let finishFirst!: (value: unknown) => void;
    h.api.saveFactoryChartSelection.mockImplementationOnce(() => new Promise(resolve => { finishFirst = resolve; }));
    const page = await mountPage();
    await openCatalog(page!);
    const boxes = page!.findAll('.selection-panel input[type="checkbox"]');
    await boxes[0].setValue(false);
    await boxes[1].setValue(false);
    await flushPromises();
    expect(h.api.saveFactoryChartSelection).toHaveBeenCalledTimes(1);
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ source: 'upload' }));
    page!.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'upload');
    await flushPromises();
    finishFirst({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['margin'], updatedAt: 'now' } });
    await flushPromises();
    expect(h.api.saveFactoryChartSelection).toHaveBeenNthCalledWith(2, 'F006', 'business', []);
  });
  it('does not let a stale preference GET overwrite a newer checkbox choice', async () => {
    let resolvePreference!: (value: unknown) => void;
    h.api.getFactoryChartSelection.mockImplementationOnce(() => new Promise(resolve => { resolvePreference = resolve; }));
    const page = await mountPage();
    await openCatalog(page!);
    const boxes = page!.findAll('.selection-panel input[type="checkbox"]');
    await boxes[0].setValue(false);
    await nextTick();
    resolvePreference({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['yield'], updatedAt: null } });
    await flushPromises();
    expect(JSON.parse(localStorage.getItem('smartbi.factory-chart-selection:F006:business')!)).toEqual(['margin']);
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('毛利趋势');
  });
  it('keeps user intent when a period temporarily excludes the selected chart', async () => {
    const page = await mountPage();
    await openCatalog(page!);
    await page!.findAll('.selection-panel input[type="checkbox"]')[1].setValue(false);
    await nextTick();
    const key = 'smartbi.factory-chart-selection:F006:business';
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(['yield']);
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ recommendations: [response().data.recommendations[1]] }));
    page!.findComponent(PeriodSelector).vm.$emit('change', { type: 'year', year: 2026, value: '2026', allHistory: false, compareEnabled: false });
    await flushPromises();
    expect(JSON.parse(localStorage.getItem(key)!)).toEqual(['yield']);
    page!.findComponent(PeriodSelector).vm.$emit('change', { type: 'year', year: 2026, value: '2026', allHistory: true, compareEnabled: false });
    await flushPromises();
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('产量趋势');
  });

  it('keeps the active upload in the URL and clears it when returning to business data', async () => {
    h.route.query = { tab: 'factory' };
    h.replace.mockImplementation(({ query }: { query: Record<string, unknown> }) => { h.route.query = query; return Promise.resolve(); });
    const page = await mountPage();
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ source: 'upload' }));
    page!.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'upload');
    await flushPromises();
    expect(h.replace).toHaveBeenLastCalledWith({ query: { tab: 'factory', upload_id: '42' } });
    page!.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'business');
    await flushPromises();
    expect(h.replace).toHaveBeenLastCalledWith({ query: { tab: 'factory' } });
  });
  it('loads business data, renders server chart/facts, and persists checkbox selection', async () => {
    const page = await mountPage();
    expect(h.api.getUploadHistory).toHaveBeenCalledWith();
    expect(h.api.getFactoryChartRecommendations).toHaveBeenCalled();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ source: 'business', period_type: 'all', factory_id: 'F006', include_optional: true, use_ai: true });
    expect(page!.text()).toContain('规则排序（未经AI排序）');
    expect(page!.text()).toContain('规则依据');
    expect(page!.text()).toContain('做得好的');
    expect(page!.findAll('.chart-stub')).toHaveLength(2);
    expect(page!.find('.chart-stub').attributes('data-title')).toBe('产量趋势');
    const cardText = page!.get('[data-testid="factory-chart-card-margin"]').text();
    expect(cardText.indexOf('AI 补充')).toBeGreaterThan(cardText.indexOf('需要关注'));

    await openCatalog(page!);
    const boxes = page!.findAll('.selection-panel input[type="checkbox"]');
    await boxes[0].setValue(false);
    await nextTick();
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
    expect(localStorage.getItem('smartbi.factory-chart-selection:F006:business')).toContain('margin');
    await page!.get('[data-testid="factory-chart-refresh"]').trigger('click');
    await flushPromises();
    expect(page!.findAll('.chart-stub')).toHaveLength(1);
  });

  it('switches to upload source and sends selected upload id, then maps period changes', async () => {
    const page = await mountPage();
    const sourceSelect = page!.findComponent({ name: 'ElSelect' });
    sourceSelect.vm.$emit('update:modelValue', 'upload');
    await flushPromises();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ source: 'upload', upload_id: 42 });

    const period = page!.findComponent(PeriodSelector);
    period.vm.$emit('update:modelValue', { type: 'month', year: 2026, value: '2026-03', compareEnabled: false });
    await flushPromises();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ period_type: 'month_range', start_month: 3, end_month: 3 });
  });

  it('shows source errors separately and retries a failed request', async () => {
    h.api.getFactoryChartRecommendations.mockReset()
      .mockResolvedValueOnce(response({ sourceErrors: [{ source: 'business', message: '缺少生产表' }], recommendations: [] }))
      .mockResolvedValueOnce(response());
    const page = await mountPage();
    expect(page!.find('[data-testid="factory-chart-source-errors"]').exists()).toBe(true);
    expect(page!.find('[data-testid="factory-chart-empty"]').exists()).toBe(false);
    await page!.find('[data-testid="factory-chart-source-errors"] .el-button').trigger('click');
    await flushPromises();
    expect(h.api.getFactoryChartRecommendations).toHaveBeenCalledTimes(2);
    expect(page!.findAll('.chart-stub')).toHaveLength(2);
  });

  it('loads an upload query automatically and exposes upload-history failure with retry', async () => {
    h.route.query = { upload_id: '42' };
    h.api.getFactoryChartRecommendations.mockResolvedValue(response({ source: 'upload' }));
    h.api.getUploadHistory.mockResolvedValueOnce({ success: false, data: [] });
    const page = await mountPage();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ source: 'upload', upload_id: 42 });
    expect(page!.find('[data-testid="factory-chart-source-load-error"]').exists()).toBe(true);
    h.api.getUploadHistory.mockResolvedValueOnce({ success: true, data: [] });
    await page!.find('[data-testid="factory-chart-source-load-error"] .el-button').trigger('click');
    await flushPromises();
    expect(page!.find('[data-testid="factory-chart-source-load-error"]').exists()).toBe(false);
    expect(page!.findAll('.chart-stub')).toHaveLength(2);
  });

  it.each([{ factoryId: 'F007' }, { source: 'upload' }])('rejects mismatched response scope %j', async (scope) => {
    h.api.getFactoryChartRecommendations.mockResolvedValue(response(scope));
    const page = await mountPage();
    expect(page!.text()).toContain('返回的数据源与当前工厂不一致');
    expect(page!.findAll('.chart-stub')).toHaveLength(0);
  });

  it('drops a recommendation response that belongs to the previous factory', async () => {
    let resolveOld!: (value: ReturnType<typeof response>) => void;
    const oldResponse = new Promise<ReturnType<typeof response>>(resolve => { resolveOld = resolve; });
    h.api.getFactoryChartRecommendations.mockReset()
      .mockImplementationOnce(() => oldResponse)
      .mockResolvedValueOnce(response({ factoryId: 'F007', sourceLabel: 'F007业务系统' }));
    const page = await mountPage();
    h.authStore!.factoryId = 'F007';
    await nextTick();
    await flushPromises();
    resolveOld(response({ factoryId: 'F006', sourceLabel: '旧F006业务系统' }));
    await flushPromises();
    expect(page!.text()).toContain('F007业务系统');
    expect(page!.text()).not.toContain('旧F006业务系统');
  });

  it('generates local analysis without a preference write and defeats a late preference response', async () => {
    let resolvePreference!: (value: unknown) => void;
    h.api.getFactoryChartSelection.mockImplementationOnce(() => new Promise(resolve => { resolvePreference = resolve; }));
    const page = await mountPage();
    page.findComponent(FactoryChartGuide).vm.$emit('analysis', ['margin']);
    await nextTick();
    resolvePreference({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['yield'] } });
    await flushPromises();
    expect(page.findAll('.chart-stub')).toHaveLength(1);
    expect(page.find('.chart-stub').attributes('data-title')).toBe('毛利趋势');
    expect(h.api.saveFactoryChartSelection).not.toHaveBeenCalled();
  });

  it('retains a generated inventory question when the selected product only has production data', async () => {
    const stock = {
      ...response().data.recommendations[0], id: 'inventory', title: '库存周转', metric: '库存', domain: '库存',
      analysis: { familyId: 'inventory', familyLabel: '库存周转' },
    };
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ recommendations: [stock, ...response().data.recommendations] }));
    h.api.getFactoryChartSelection.mockResolvedValue({ success: true, data: { factoryId: 'F006', source: 'business', selectedIds: ['yield'], updatedAt: 'now' } });
    const page = await mountPage();

    await page.get('[data-testid="factory-chart-guide-domain"] input[value="库存"]').setValue(true);
    await nextTick();
    await page.get('[data-testid="factory-chart-guide-generate"]').trigger('click');
    await nextTick();
    expect(page.find('[data-testid="factory-chart-card-inventory"]').exists()).toBe(true);

    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({
      recommendations: [response().data.recommendations[0]],
      subjects: [{ id: 'p1', label: '黄油鸡' }],
      analysisSubject: 'p1',
    }));
    page.findComponent(FactoryChartGuide).vm.$emit('subject', 'p1');
    await flushPromises();

    expect(page.findComponent(FactoryChartGuide).props('context').domain).toBe('库存');
    expect(page.findComponent(FactoryChartGuide).props('subject')).toBe('p1');
    expect(page.findAll('.chart-stub')).toHaveLength(0);
    expect(page.text()).toContain('当前范围没有满足数据条件');
  });

  it('sends the selected product and rejects an unscoped response while keeping recovery controls', async () => {
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ subjects: [{ id: 'p1', label: '黄油鸡' }] }));
    const page = await mountPage();
    // Simulate an older or broken server ignoring the new product selector.
    page.findComponent(FactoryChartGuide).vm.$emit('subject', 'p1');
    await flushPromises();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ guided: true, analysis_subject: 'p1' });
    expect(page.text()).toContain('返回的数据源与当前工厂不一致');
    expect(page.findAll('.chart-stub')).toHaveLength(0);
    expect(page.findComponent(FactoryChartGuide).props('subject')).toBe('p1');
    expect(page.findComponent(FactoryChartGuide).props('subjects')).toEqual([{ id: 'p1', label: '黄油鸡' }]);
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ analysisSubject: null }));
    page.findComponent(FactoryChartGuide).vm.$emit('subject', null);
    await flushPromises();
    expect(page.findAll('.chart-stub')).toHaveLength(2);
    expect(h.api.saveFactoryChartSelection).not.toHaveBeenCalled();
  });

  it('retains business questions across product reloads and clears the product when source changes', async () => {
    const page = await mountPage();
    page.findComponent(FactoryChartGuide).vm.$emit('context', { mode: 'browse', domain: '生产', family: 'all' });
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ analysisSubject: 'p1', analysisScopeLabel: '黄油鸡', subjects: [{ id: 'p1', label: '黄油鸡' }] }));
    page.findComponent(FactoryChartGuide).vm.$emit('subject', 'p1');
    await flushPromises();
    expect(page.findComponent(FactoryChartGuide).props('context').mode).toBe('browse');
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ source: 'upload' }));
    page.findAllComponents({ name: 'ElSelect' })[0].vm.$emit('update:modelValue', 'upload');
    await flushPromises();
    expect(h.api.getFactoryChartRecommendations.mock.calls.at(-1)?.[0]).toMatchObject({ source: 'upload', analysis_subject: null });
  });

  it.each(['user', 'role'])('drops high-role in-flight data after switching %s within the same factory', async mode => {
    let resolveOld!: (value: unknown) => void;
    h.api.getFactoryChartRecommendations.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const page = await mountPage();
    h.api.getFactoryChartRecommendations.mockResolvedValueOnce(response({ recommendations: [], subjects: [], sourceLabel: '新用户受限视图' }));
    if (mode === 'user') h.authStore!.user = { id: 1558 };
    else h.authStore!.currentRole = 'quality_inspector';
    await flushPromises();
    resolveOld(response({ sourceLabel: '旧管理员金额数据', subjects: [{ id: 'private', label: '旧受限产品' }] }));
    await flushPromises();
    expect(page.text()).toContain('新用户受限视图');
    expect(page.text()).not.toContain('旧管理员金额数据');
    expect(page.text()).not.toContain('旧受限产品');
    expect(page.findAll('.chart-stub')).toHaveLength(0);
  });

  it('does not request data without a factory', async () => {
    h.authStore!.factoryId = '';
    const page = await mountPage();
    expect(h.api.getUploadHistory).not.toHaveBeenCalled();
    expect(h.api.getFactoryChartRecommendations).not.toHaveBeenCalled();
    expect(page!.text()).toContain('当前账号没有所属工厂');
  });
});
