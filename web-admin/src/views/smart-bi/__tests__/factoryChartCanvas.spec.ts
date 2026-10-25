import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
const h = vi.hoisted(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }));
vi.mock('@/utils/echarts', () => ({ default: { init: () => h } }));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (value: unknown) => value }));
import FactoryChartCanvas from '../FactoryChartCanvas.vue';
afterEach(() => vi.clearAllMocks());
it('renders real option contract and disposes on unmount', async () => {
  const option = { series: [{ type: 'bar', data: [95, 90] }] };
  const wrapper = mount(FactoryChartCanvas, { props: { option } });
  await flushPromises();
  expect(h.setOption).toHaveBeenCalledWith(option, { notMerge: true });
  expect(wrapper.find('[role="alert"]').exists()).toBe(false);
  wrapper.unmount();
  expect(h.dispose).toHaveBeenCalled();
});
it('shows a drawing failure instead of leaving a blank chart', async () => {
  h.setOption.mockImplementationOnce(() => { throw new Error('invalid chart'); });
  const wrapper = mount(FactoryChartCanvas, { props: { option: { series: [] } } });
  await flushPromises();
  expect(wrapper.find('[role="alert"]').exists()).toBe(true);
  expect(wrapper.get('[role="alert"]').text()).toContain('图表绘制失败');
  expect(h.dispose).toHaveBeenCalled();
  wrapper.unmount();
});
