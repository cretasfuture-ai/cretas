/**
 * #1806 复审 L2: 后端按权限配置不下发金额类指标的数值 (valueHidden=true, lastValue/value 为 null)。
 * 前端以前把「没有 lastValue」一律说成「未计算 / 待配置 / 暂无数据」—— 被藏的值被说成「没算」, 不如实。
 * 现在三处 (指标卡片 / 指标中心统计 / 指标树) 读 valueHidden, 显示「无权限查看」。
 */
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount, shallowMount, type VueWrapper } from '@vue/test-utils';
import { reactive } from 'vue';
import ElementPlus from 'element-plus';
import Card from '@/components/indicator/IndicatorValueCard.vue';
import TreeViewer from '../IndicatorTreeViewer.vue';
import Dashboard from '../IndicatorCenterDashboard.vue';
import { getIndicatorTree, getIndicatorValue, listIndicators } from '@/api/indicator';

vi.mock('@/api/indicator', () => ({
  getIndicatorValue: vi.fn(),
  listIndicators: vi.fn(),
  getIndicatorTree: vi.fn(),
}));
const identity = reactive({ factoryId: 'F-TEST', user: { id: 7 }, currentRole: 'dispatcher' });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canAccess: () => true }),
}));
vi.mock('vue-router', () => ({ useRoute: () => reactive({ query: {} }), useRouter: () => ({ push: vi.fn() }) }));
vi.mock('@/utils/echarts', () => ({ default: { init: vi.fn() } }));

const mounted: VueWrapper[] = [];
beforeEach(() => {
  vi.resetAllMocks();
});
afterEach(() => {
  mounted.splice(0).forEach(w => w.unmount());
});

describe('IndicatorValueCard valueHidden', () => {
  const base = { code: 'SALES_MONTHLY_REVENUE', name: '月度销售额', category: 'SALES', unit: '元' };

  it('says 无权限查看 instead of 未计算 / 待配置 when the value was withheld', () => {
    const wrapper = shallowMount(Card, {
      props: { indicator: { ...base, lastValue: null, lastComputedAt: '2026-11-16T08:00:00', valueHidden: true } },
    });
    expect(wrapper.find('.value-num').text()).toBe('无权限查看');
    expect(wrapper.text()).toContain('需要价格查看权限');
    expect(wrapper.text()).not.toContain('未计算');
    expect(wrapper.text()).not.toContain('待配置');
    expect(wrapper.text()).not.toContain('暂无数据');
  });

  it('keeps 待配置 for a genuinely uncomputed indicator (negative control)', () => {
    const wrapper = shallowMount(Card, { props: { indicator: { ...base, lastValue: null, lastComputedAt: null } } });
    expect(wrapper.text()).toContain('待配置');
    expect(wrapper.text()).not.toContain('无权限查看');
  });
});

describe('IndicatorTreeViewer valueHidden', () => {
  it('tags hidden nodes 无权限查看 and does not count them as 待配置', async () => {
    vi.mocked(getIndicatorTree).mockResolvedValue([
      { nodeId: 'n1', indicatorId: 'ind-srm', code: 'SALES_MONTHLY_REVENUE', name: '月度销售额', category: 'SALES', depth: 0, valueHidden: true, children: [] },
      { nodeId: 'n2', indicatorId: 'ind-fyr', code: 'FACTORY_YIELD_RATE', name: '综合良品率', category: 'FACTORY', depth: 0, lastValue: null, children: [] },
    ] as never);
    const wrapper = mount(TreeViewer, { props: { factoryId: 'F-TEST' }, global: { plugins: [ElementPlus] } });
    mounted.push(wrapper);
    await flushPromises();
    expect(wrapper.findAll('.node-hidden-tag')).toHaveLength(1);
    const stats = wrapper.find('.tree-stats').text();
    expect(stats).toContain('待配置 1');
    expect(stats).toContain('无权限查看 1');
  });
});

describe('IndicatorCenterDashboard valueHidden', () => {
  it('does not count withheld values as 缺数 and shows how many are hidden', async () => {
    vi.mocked(listIndicators).mockResolvedValue([
      { id: 'a', code: 'SALES_MONTHLY_REVENUE', name: '月度销售额', category: 'SALES', isActive: true, computeStrategy: 'CACHED', lastValue: null, lastComputedAt: '2026-11-16T08:00:00', valueHidden: true },
      { id: 'b', code: 'OTHER_INDICATOR', name: '其它', category: 'FACTORY', isActive: true, computeStrategy: 'CACHED', lastValue: null, lastComputedAt: null },
    ] as never);
    vi.mocked(getIndicatorValue).mockResolvedValue({ code: 'X', name: 'X', value: null, alertLevel: null, fromCache: false } as never);
    const wrapper = shallowMount(Dashboard, { global: { stubs: {
      IndicatorTreeViewer: { template: '<div />', methods: { loadTree: vi.fn() } },
    } } });
    mounted.push(wrapper);
    await flushPromises();
    expect(wrapper.find('[data-testid="indicator-hidden-count"]').text()).toContain('1 个无权限查看');
    const nums = wrapper.findAll('.summary-num').map(n => n.text());
    // 总指标 2 / 数据可用 0 / 缺数 1 (只有真没算的那个) / 失败 0
    expect(nums).toEqual(['2', '0', '1', '0']);
  });
});
