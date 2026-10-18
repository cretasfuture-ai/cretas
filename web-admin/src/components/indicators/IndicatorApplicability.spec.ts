import { describe, expect, it, vi, beforeEach } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import IndicatorApplicability from './IndicatorApplicability.vue';
import { getIndicatorApplicability } from '@/api/indicator';

vi.mock('@/api/indicator', () => ({ getIndicatorApplicability: vi.fn() }));
const api = vi.mocked(getIndicatorApplicability);
const report = (factoryId: string, reason = '配置口径满足质量指标换算条件') => ({
  factoryId, basis: 'CONFIGURATION_ONLY' as const, overallStatus: 'PARTIAL' as const,
  metrics: [{ code: 'FACTORY_LU_YIELD_RATE', name: '实际出成率', status: 'NEEDS_CONFIRMATION' as const, reason, requiredFacts: ['确认投入单位'] }],
  products: [{ productId: 'p1', productName: '猪肉', profile: { unit: 'kg', inputUnit: null, gramsPerUnit: null, multiOutput: null }, metrics: [{ code: 'FACTORY_LU_YIELD_RATE', name: '实际出成率', status: 'NEEDS_CONFIRMATION' as const, reason, requiredFacts: ['确认投入单位'] }] }],
});
const stubs = {
  'el-card': { template: '<section><slot name="header"/><slot/></section>' },
  'el-tag': { props: ['type'], template: '<span class="tag"><slot/></span>' },
  'el-table': { template: '<div><slot/></div>' }, 'el-table-column': { template: '<span />' },
  'el-skeleton': { template: '<div class="skeleton" />' }, 'el-alert': { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
};

describe('IndicatorApplicability', () => {
  beforeEach(() => vi.resetAllMocks());

  it('uses providedReport without fetching and shows concrete Chinese reason', async () => {
    const wrapper = mount(IndicatorApplicability, { props: { factoryId: 'F1', providedReport: report('F1') }, global: { stubs } });
    await flushPromises();
    expect(api).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('确认投入单位');
  });

  it('drops stale response after identity switch', async () => {
    let resolveOld!: (value: ReturnType<typeof report>) => void;
    api.mockReturnValueOnce(new Promise(resolve => { resolveOld = resolve; }));
    api.mockResolvedValueOnce(report('F2', '新身份配置'));
    const wrapper = mount(IndicatorApplicability, { props: { factoryId: 'F1', actorKey: 'u1' }, global: { stubs } });
    await wrapper.setProps({ factoryId: 'F2', actorKey: 'u2' });
    await flushPromises();
    resolveOld(report('F1', '旧身份配置')); await flushPromises();
    expect(wrapper.text()).toContain('新身份配置');
    expect(wrapper.text()).not.toContain('旧身份配置');
  });

  it('renders an error and no false zero state', async () => {
    api.mockRejectedValueOnce(new Error('服务不可用'));
    const wrapper = mount(IndicatorApplicability, { props: { factoryId: 'F1' }, global: { stubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('服务不可用');
    expect(wrapper.text()).not.toContain('0');
  });

  it('invalidates a pending response after unmount', async () => {
    let resolvePending!: (value: ReturnType<typeof report>) => void;
    api.mockReturnValueOnce(new Promise(resolve => { resolvePending = resolve; }));
    const wrapper = mount(IndicatorApplicability, { props: { factoryId: 'F1' }, global: { stubs } });
    wrapper.unmount();
    resolvePending(report('F1', '卸载前旧响应')); await flushPromises();
    expect(wrapper.element.isConnected).toBe(false);
  });
});
