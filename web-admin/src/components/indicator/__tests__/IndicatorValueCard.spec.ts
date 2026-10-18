import { describe, expect, it, vi } from 'vitest';
import { shallowMount } from '@vue/test-utils';
import Card from '../IndicatorValueCard.vue';

vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const baseIndicator = {
  code: 'FACTORY_LU_YIELD_RATE',
  name: '流转出成率',
  category: 'FACTORY',
  unit: '%',
  lastValue: 13.93,
  lastComputedAt: '2026-10-16T09:00:00',
};

function mountCard(overrides: Record<string, unknown> = {}) {
  return shallowMount(Card, {
    props: { indicator: { ...baseIndicator, ...overrides } },
  });
}

describe('IndicatorValueCard value states', () => {
  it('renders an available business value', () => {
    const wrapper = mountCard();
    expect(wrapper.find('.value-num').text()).toBe('13.93');
    expect(wrapper.find('.card-footer').exists()).toBe(true);
    expect(wrapper.text()).not.toContain('待配置');
  });

  it('keeps an available zero visible', () => {
    const wrapper = mountCard({ lastValue: 0 });
    expect(wrapper.find('.value-num').text()).toBe('0');
  });

  it('does not fall back to an old value when data is unavailable', () => {
    const wrapper = mountCard({ lastValue: 99, lastComputedAt: '2026-10-15T09:00:00', status: 'NO_DATA' });
    expect(wrapper.find('.value-num').text()).toBe('—');
    expect(wrapper.text()).toContain('暂无数据');
    expect(wrapper.text()).toContain('暂无业务数据');
    expect(wrapper.text()).not.toContain('待配置');
  });

  it('shows calculation failure separately from missing data', () => {
    const wrapper = mountCard({ lastValue: 88, lastComputedAt: '2026-10-15T09:00:00', status: 'FAILED' });
    expect(wrapper.find('.value-num').text()).toBe('—');
    expect(wrapper.text()).toContain('计算失败');
    expect(wrapper.text()).not.toContain('暂无业务数据');
  });

  it('keeps an unconfigured indicator distinct from a configured metric without data', () => {
    const wrapper = mountCard({ lastValue: null, lastComputedAt: null });
    expect(wrapper.text()).toContain('待配置');
    expect(wrapper.text()).toContain('未配置');
    expect(wrapper.text()).not.toContain('暂无数据');
  });
});
