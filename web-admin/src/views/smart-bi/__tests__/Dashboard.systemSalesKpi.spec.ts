/**
 * LSM-COSTBI-DASH-KPI: 驾驶舱在无上传数据、业务库有销售订单时, KPI 直接显示系统数据
 * (与经营分析页「销售订单」同一接口 getFactorySalesSummary); 两者都没有 → 如实空状态。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';

const getGoldDataRangeMock = vi.fn(async () => ({ factoryId: 'LSMDEMO', minDate: null, maxDate: null, dayCount: 0 }));
vi.mock('@/api/smartbi/dataRange', () => ({
  getGoldDataRange: (...a: unknown[]) => getGoldDataRangeMock(...a),
}));
vi.mock('@/api/smartbi/gold', () => ({ getKpiSummary: vi.fn(), getTrendBundle: vi.fn() }));

// executive 路径: 无上传数据 → 空 kpiCards
const getMock = vi.fn(async (_url: string) => ({
  success: true,
  data: { code: 200, data: { kpiCards: [], charts: {}, rankings: {}, aiInsights: [], alerts: [] } },
}));
vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => getMock(...(a as [string])) }));

const salesSummaryMock = vi.fn();
vi.mock('@/api/smartbi/factoryOverview', () => ({
  getFactorySalesSummary: (...a: unknown[]) => salesSummaryMock(...a),
}));

vi.stubGlobal('fetch', vi.fn(async () => new Response('', { status: 200 })));
vi.mock('@/api/smartbi', () => ({
  getUploadHistory: vi.fn(async () => ({ success: true, data: [] })),
  getDynamicAnalysis: vi.fn(async () => ({ success: true, data: { kpiCards: [], charts: [] } })),
}));

// ── auth store: RESTAURANT tenant (gold default path) ────────
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'LSMDEMO', factoryType: 'FACTORY' }),
}));
// ── permission store: can view price + can upload ────────────
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    canWrite: () => true,
    canViewPrice: true,
  }),
}));
vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

// ── capability composable: report MISSING fields so that, if any KPI card were
//    still wrapped in <CapabilityGate>, it WOULD show the "需上传" placeholder.
//    This is what Task 2 must bypass for gold cards. ──────────
vi.mock('@/composables/useCapability', () => ({
  useCapability: () => ({
    fetchCapability: vi.fn(async () => null),
    hasAll: () => false, // nothing satisfied → placeholder path if gated
    missingOf: (reqs: string[]) => reqs,
    available: { value: new Set<string>() },
    loading: { value: false },
    suggestions: { value: [] },
  }),
}));

// ── echarts + chart composables (no real charts in jsdom) ────
vi.mock('@/utils/echarts', () => ({
  default: {
    init: () => ({ setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }),
    connect: vi.fn(),
    graphic: { LinearGradient: class { constructor() {} } },
  },
}));
vi.mock('@/composables/useChartResize', () => ({
  useChartResize: () => ({ observe: vi.fn(), disconnect: vi.fn() }),
}));
vi.mock('@/composables/useChartEnhancer', () => ({
  enhanceChartDefaults: (o: unknown) => o,
}));

// ── child components → trivial stubs ─────────────────────────
vi.mock('@/components/smartbi/SmartBIEmptyState.vue', () => ({
  default: { props: ['type'], template: '<div class="empty-state-stub" :data-type="type" />' },
}));
vi.mock('@/components/smartbi/ChartSkeleton.vue', () => ({
  default: { template: '<div class="skeleton-stub" />' },
}));
vi.mock('./components/TemplateGrid.vue', () => ({
  default: { template: '<div class="template-grid-stub" />' },
}));
vi.mock('./components/RestaurantGoldGrid.vue', () => ({
  default: { template: '<div class="restaurant-gold-grid-stub" />' },
}));
vi.mock('@/components/UnlockMoreCTA.vue', () => ({
  default: { template: '<div class="unlock-cta-stub" />' },
}));
// CapabilityGate stub mirroring the real component's gold-mode bypass:
//  - `force` (gold mode) → render the default slot only, NO placeholder.
//  - otherwise (capability unsatisfied, see useCapability mock) → render the
//    real "此分析需上传含..." placeholder text so the test can detect a gold
//    card that is (wrongly) still gated.
vi.mock('@/components/CapabilityGate.vue', () => ({
  default: {
    props: ['requires', 'cardId', 'fallback', 'force'],
    template:
      '<div class="capability-gate-stub">' +
      '<template v-if="force"><slot /></template>' +
      '<template v-else>' +
      '<div class="cap-placeholder">此分析需上传含 {{ (requires || []).join(" / ") }} 的数据</div>' +
      '<slot />' +
      '</template>' +
      '</div>',
  },
}));

import Dashboard from '../Dashboard.vue';

// Element Plus stubs — keep slots rendering, expose v-model where it matters.
const globalStubs = {
  'el-card': { template: '<div class="el-card"><slot name="header" /><slot /></div>' },
  'el-row': { template: '<div class="el-row"><slot /></div>' },
  'el-col': { template: '<div class="el-col"><slot /></div>' },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-icon': { template: '<i><slot /></i>' },
  'el-link': { template: '<a><slot /></a>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}<slot /></div>' },
  'el-empty': { props: ['description'], template: '<div class="el-empty">{{ description }}<slot /></div>' },
  'el-skeleton': { template: '<div class="el-skeleton" />' },
  'el-radio-group': { template: '<div class="el-radio-group"><slot /></div>' },
  'el-radio-button': { template: '<button class="el-radio-button"><slot /></button>' },
  // these two are the data-source picker controls; render so text would surface IF present
  'el-select': {
    props: ['modelValue', 'placeholder'],
    template: '<select class="el-select" :data-placeholder="placeholder"><slot /></select>',
  },
  'el-option': { props: ['label', 'value'], template: '<option :value="value">{{ label }}<slot /></option>' },
  'el-date-picker': {
    props: ['modelValue'],
    template: '<div class="el-date-picker" />',
  },
};

async function mountDashboard() {
  const wrapper = mount(Dashboard, {
    global: {
      stubs: globalStubs,
      directives: { loading: () => undefined },
    },
  });
  await flushPromises();
  await flushPromises();
  return wrapper;
}


const summary = {
  orderCount: 40, effectiveOrderCount: 36, cancelledCount: 2, draftCount: 1, rejectedCount: 1,
  inFlightCount: 5, orderAmount: 366100, ordersWithoutAmount: 0,
  firstOrderDate: '2026-01-01', lastOrderDate: '2026-11-17', topCustomers: [], topProducts: [],
};

describe('Dashboard KPI — 无上传数据时回退系统销售订单', () => {
  beforeEach(() => { salesSummaryMock.mockReset(); getMock.mockClear(); });

  it('系统有订单: KPI 显示系统数据且带来源说明', async () => {
    salesSummaryMock.mockResolvedValue(summary);
    const wrapper = await mountDashboard();
    expect(salesSummaryMock).toHaveBeenCalledOnce();
    const kpi = wrapper.find('.kpi-section').text();
    expect(kpi).toContain('订单金额');
    expect(kpi).toContain('36');          // 有效订单数, 与分析页同口径
    expect(kpi).toContain('订单金额36.6万');  // 与分析页 fmtWan 同口径
    expect(kpi).toContain('有效订单数36');
    expect(kpi).toContain('在途订单5');
    expect(kpi).toContain('在途订单');
    expect(wrapper.find('[data-testid="system-sales-source-note"]').exists()).toBe(true);
    expect(wrapper.find('.empty-state-stub[data-type="no-data"]').exists()).toBe(false);
  });

  it('阴性对照: 上传与系统都没有 → 空状态, 不出现 KPI 数值和来源说明', async () => {
    salesSummaryMock.mockResolvedValue({ ...summary, orderCount: 0, effectiveOrderCount: 0, inFlightCount: 0, orderAmount: null });
    const wrapper = await mountDashboard();
    expect(wrapper.find('.empty-state-stub[data-type="no-data"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="system-sales-source-note"]').exists()).toBe(false);
    expect(wrapper.find('.kpi-section').text()).not.toContain('36');
  });

  it('阴性对照: 系统接口失败 → 保持空状态, 不造数', async () => {
    salesSummaryMock.mockRejectedValue(new Error('boom'));
    const wrapper = await mountDashboard();
    expect(wrapper.find('.empty-state-stub[data-type="no-data"]').exists()).toBe(true);
    expect(wrapper.find('[data-testid="system-sales-source-note"]').exists()).toBe(false);
  });
});
