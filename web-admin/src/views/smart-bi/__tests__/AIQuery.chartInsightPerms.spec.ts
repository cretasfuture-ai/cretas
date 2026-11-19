/**
 * 问题 51 余项 (LSM-COSTBI-Q51-SHARE-TEXT, 11-19 prod F006 实测): AI 问答出图后, 图下分析写
 * 「无成本查看权限」, 登录的是工厂总监 (factory_super_admin)。
 * 根因: AIQuery.vue 两处 ChartInsightProvider 写死 :perms="{ canViewFinance: false }"。
 *
 * 断言跑在真实入口: 挂载 AIQuery → 选数据源 → 发问 → Python 流式回调送回饼图 → 页面上的图下分析。
 * 正向: 有价格查看权限的角色 (工厂总监) 看得到占比和金额; 阴性: 无权角色仍只说排序, 不出 % 和 ¥。
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';

const h = vi.hoisted(() => ({
  routeMock: { query: {} as Record<string, string> },
  authState: { factoryId: 'F006', businessDomain: 'FACTORY', user: { id: 1 } } as Record<string, unknown>,
}));

/** 与 prod 截图同一组读数: C 43.99% / B 28.45% / A 27.56% */
const SHARE_PIE = {
  type: 'pie',
  title: '各产品销售金额占比',
  option: {
    title: { text: '各产品销售金额占比', left: 'center' },
    series: [{
      name: '销售金额',
      type: 'pie',
      data: [
        { name: 'DEMO产品C', value: 4399 },
        { name: 'DEMO产品B', value: 2845 },
        { name: 'DEMO产品A', value: 2756 },
      ],
    }],
  },
};

type StreamCallbacks = {
  onCharts?: (charts: unknown[]) => Promise<void> | void;
  onDone?: (result: Record<string, unknown>) => Promise<void> | void;
};

const chatAnalysisStreamMock = vi.fn((_params: unknown, cb: StreamCallbacks) => {
  void (async () => {
    await cb.onCharts?.([SHARE_PIE]);
    await cb.onDone?.({ success: true, answer: '按销售金额看，DEMO产品C占 43.99%，排第一。', charts: [SHARE_PIE] });
  })();
  return { abort: vi.fn() };
});

vi.mock('@/api/smartbi/intent-chat', () => ({
  executeIntent: vi.fn(),
  confirmIntentAction: vi.fn(),
  getPurchaseTaskGrantStatus: vi.fn(),
  fetchCachedXlsx: vi.fn(),
  submitIntentFeedback: vi.fn(async () => true),
}));
vi.mock('@/api/smartbi', () => ({
  chatAnalysis: vi.fn(async () => ({ success: false })),
  chatAnalysisStream: (p: unknown, cb: StreamCallbacks) => chatAnalysisStreamMock(p, cb),
  getUploadHistory: vi.fn(async () => []),
  deduplicateUploads: vi.fn(async () => ({})),
  nl2sql: vi.fn(),
  logFeedback: vi.fn(async () => ({})),
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => h.authState }));
vi.mock('vue-router', () => ({
  useRoute: () => h.routeMock,
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
vi.mock('@/api/smartbi/materialized', () => ({
  listFactoryTemplates: vi.fn(async () => ({ success: true, templates: [] })),
}));

import AIQuery from '../AIQuery.vue';
import { usePermissionStore } from '@/store/modules/permission';

const globalStubs = {
  'el-button': { emits: ['click'], template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>' },
  'el-link': { emits: ['click'], template: '<a class="el-link" @click="$emit(\'click\')"><slot /></a>' },
  'el-icon': { template: '<i class="el-icon"><slot /></i>' },
  'el-input': { template: '<input />' },
  'el-autocomplete': { template: '<div class="el-autocomplete"><slot :item="{ value: \'x\' }" /></div>' },
  'el-tag': { template: '<span class="el-tag"><slot /></span>' },
  'el-tooltip': { template: '<div><slot /></div>' },
  'el-table': { template: '<table class="el-table"><slot /></table>' },
  'el-table-column': { template: '<th />' },
  'el-select': { template: '<select><slot /></select>' },
  'el-option': { template: '<option><slot /></option>' },
  'el-switch': { template: '<input type="checkbox" />' },
  'el-empty': { template: '<div><slot /></div>' },
  'el-alert': { props: ['title'], template: '<div class="el-alert">{{ title }}<slot /></div>' },
  'el-segmented': { template: '<div />' },
};

interface Vm {
  inputQuery: string;
  selectedUploadId: number | null;
  handleSendMessage: () => Promise<void>;
}

async function askShareAs(role: string): Promise<VueWrapper> {
  usePermissionStore().setRole(role, 'F006', 'FACTORY', 1, { skipDbLoad: true });
  const w = mount(AIQuery, { global: { stubs: globalStubs }, attachTo: document.body });
  await flushPromises();
  const vm = w.vm as unknown as Vm;
  vm.selectedUploadId = 1;
  await flushPromises();
  vm.inputQuery = '各产品的销售额占比是多少？用图表展示';
  await vm.handleSendMessage();
  await flushPromises();
  await flushPromises();
  return w;
}

function insightText(w: VueWrapper): string {
  const findings = w.findAll('.chart-insight-finding');
  expect(findings.length).toBeGreaterThan(0); // 必然出现: 饼图下一定有一句分析
  return findings.map((f) => f.text()).join('\n');
}

beforeEach(() => {
  setActivePinia(createPinia());
  sessionStorage.clear();
  chatAnalysisStreamMock.mockClear();
});
afterEach(() => { document.body.innerHTML = ''; });

describe('AI 问答图下分析按权限配置判可见 (问题 51 余项)', () => {
  it('工厂总监 (factory_super_admin, 有价格查看权限): 图下分析出占比与金额, 不写「无成本查看权限」', async () => {
    const w = await askShareAs('factory_super_admin');
    expect(chatAnalysisStreamMock).toHaveBeenCalledTimes(1);
    const text = insightText(w);
    expect(text).not.toContain('无成本查看权限');
    expect(text).toContain('DEMO产品C占44.0%');
    expect(text).toContain('¥');
  });

  it('阴性对照: 无价格查看权限的角色 (warehouse_manager) 仍只说排序, 不出 % 和 ¥', async () => {
    expect(usePermissionStore().canViewPrice).toBe(false);
    const w = await askShareAs('warehouse_manager');
    expect(usePermissionStore().canViewPrice).toBe(false);
    const text = insightText(w);
    expect(text).toContain('无成本查看权限');
    expect(text).toContain('DEMO产品C是最大一项');
    expect(text.replace('（无成本查看权限，不显示金额和占比）', '')).not.toMatch(/[%¥]/);
  });
});
