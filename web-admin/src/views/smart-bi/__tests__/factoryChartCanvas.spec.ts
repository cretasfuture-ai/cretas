import { flushPromises, mount } from '@vue/test-utils';
import { afterEach, expect, it, vi } from 'vitest';
const h = vi.hoisted(() => ({ setOption: vi.fn(), dispose: vi.fn(), resize: vi.fn() }));
vi.mock('@/utils/echarts', () => ({ default: { init: () => h } }));
vi.mock('@/utils/echarts-fmt', () => ({ processEChartsOptions: (value: unknown) => value }));
import FactoryChartCanvas from '../FactoryChartCanvas.vue';
afterEach(() => {
  vi.restoreAllMocks();
  vi.clearAllMocks();
});
it('renders real option contract and disposes on unmount', async () => {
  const option = { series: [{ type: 'bar', data: [95, 90] }] };
  const wrapper = mount(FactoryChartCanvas, { props: { option } });
  await flushPromises();
  expect(h.setOption).toHaveBeenCalledWith(option, { notMerge: true });
  expect(h.resize).toHaveBeenCalled();
  expect(wrapper.emitted('ready')).toHaveLength(1);
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

it.each([260, 480, 640])('wraps all category names in a %ipx ask chart without changing its data', async (width) => {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(width);
  const names = ['示例长名称干式熟成脆皮鸡350g · SKU2026010017', '贴成品盒签', '套袋装箱'];
  const series = [{ type: 'bar', data: [207.125, 107, 50] }];
  const axisLabel = { width: 66, overflow: 'truncate', hideOverlap: true };
  const option = {
    grid: { top: 40, bottom: 64 },
    yAxis: { type: 'category', data: names, axisLabel },
    xAxis: { type: 'value' },
    series,
  };
  const wrapper = mount(FactoryChartCanvas, { props: { option, height: 220, fullCategoryLabels: true } });
  try {
    await flushPromises();
    const rendered = h.setOption.mock.lastCall?.[0];
    expect(rendered.yAxis.data).toEqual(names);
    expect(rendered.series).toEqual(series);
    expect(rendered.yAxis.axisLabel).toMatchObject({
      overflow: 'breakAll', interval: 0, hideOverlap: false, width: Math.min(200, Math.floor(width * 0.4)),
    });
    expect(rendered.xAxis).toEqual(option.xAxis);
    expect(option.yAxis.axisLabel).toEqual({ width: 66, overflow: 'truncate', hideOverlap: true });
  } finally {
    wrapper.unmount();
  }
});

it('grows to fit dense process categories and keeps the catalogue layout by default', async () => {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(260);
  const option = {
    yAxis: { type: 'category', data: Array.from({ length: 18 }, (_, i) => `工序 ${i}`) },
    series: [{ type: 'bar', data: Array.from({ length: 18 }, (_, i) => i) }],
  };
  const chat = mount(FactoryChartCanvas, { props: { option, fullCategoryLabels: true } });
  try {
    await flushPromises();
    expect(parseFloat(chat.get<HTMLElement>('[role="img"]').element.style.height)).toBeGreaterThan(320);
  } finally {
    chat.unmount();
  }
  const catalogue = mount(FactoryChartCanvas, { props: { option } });
  try {
    await flushPromises();
    expect(catalogue.get<HTMLElement>('[role="img"]').element.style.height).toBe('320px');
    expect(h.setOption).toHaveBeenLastCalledWith(option, { notMerge: true });
  } finally {
    catalogue.unmount();
  }
});

it('wraps named x-axis ticks and leaves the date interval unchanged', async () => {
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(260);
  const axisLabel = { interval: 'auto', overflow: 'truncate', width: 40 };
  const option = {
    xAxis: { type: 'category', data: ['套袋装箱 1 号线', '成品入库 2 号线'], axisLabel },
    yAxis: { type: 'value' },
    series: [{ type: 'bar', data: [8, 4] }],
  };
  const wrapper = mount(FactoryChartCanvas, { props: { option, fullCategoryLabels: true } });
  try {
    await flushPromises();
    expect(h.setOption.mock.lastCall?.[0].xAxis.axisLabel).toMatchObject({
      overflow: 'breakAll', interval: 0, hideOverlap: false,
    });
    const dates = { ...option, xAxis: { ...option.xAxis, data: ['2026-11-02', '2026-11-03'] } };
    await wrapper.setProps({ option: dates });
    await flushPromises();
    expect(h.setOption).toHaveBeenLastCalledWith(dates, { notMerge: true });
  } finally {
    wrapper.unmount();
  }
});
