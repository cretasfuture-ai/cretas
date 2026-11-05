import {mount,flushPromises} from '@vue/test-utils';
import {beforeEach,describe,it,expect,vi} from 'vitest';
const engine=vi.hoisted(()=>({setOption:vi.fn(),on:vi.fn(),resize:vi.fn(),dispose:vi.fn()}));
vi.mock('@/utils/echarts',()=>({default:{init:()=>engine}}));
vi.mock('@/store/modules/app',()=>({useAppStore:()=>({theme:'light'})}));
import DynamicChartRenderer from '../DynamicChartRenderer.vue';
const config={chartType:'bar' as const,xAxis:{type:'category' as const,data:['A','B','C']},
  yAxis:[{type:'value' as const,name:'来源金额 / kg'}],series:[{name:'材料',type:'bar',data:[10,null,100]}]};
beforeEach(()=>vi.clearAllMocks());
describe('plain prototype chart presentation',()=>{
  it('rounds opt-in tooltips and numeric axes without rounding chart data or order labels',async()=>{
    const source={...config,xAxis:{...config.xAxis,data:['GD202610290011']},series:[{name:'材料',type:'bar',data:[65.4856123]}]};
    const wrapper=mount(DynamicChartRenderer,{props:{config:source,presentation:'plain',numberPrecision:2}});await flushPromises();
    const option=engine.setOption.mock.calls.at(-1)?.[0];
    expect(option.series[0].data).toEqual([65.4856123]);
    expect(option.tooltip.valueFormatter(65.4856123)).toBe('65.49');
    expect(option.tooltip.valueFormatter(null)).toBe('未提供');
    expect(option.yAxis[0].axisLabel.formatter(65.4856123)).toBe('65.49');
    expect(option.xAxis.data).toEqual(['GD202610290011']);
    await wrapper.setProps({numberPrecision:undefined});await flushPromises();
    expect(engine.setOption.mock.calls.at(-1)?.[1].notMerge).toBe(true);
    wrapper.unmount();
  });
  it('preserves source values and omits generated toolbar/annotations in plain mode',async()=>{
    const wrapper=mount(DynamicChartRenderer,{props:{config,presentation:'plain'}});await flushPromises();
    const option=engine.setOption.mock.calls.at(-1)?.[0];
    expect(option.series[0].data).toEqual([10,null,100]);
    expect(option.toolbox).toBeUndefined();expect(option.series[0].markPoint).toBeUndefined();
    wrapper.unmount();
  });
  it('keeps existing standard tools and replaces stale options when changing presentation',async()=>{
    const wrapper=mount(DynamicChartRenderer,{props:{config}});await flushPromises();
    expect(engine.setOption.mock.calls.at(-1)?.[0].toolbox.feature.saveAsImage).toBeTruthy();
    await wrapper.setProps({presentation:'plain'});await flushPromises();
    expect(engine.setOption.mock.calls.at(-1)?.[1].notMerge).toBe(true);
    expect(engine.setOption.mock.calls.at(-1)?.[0].series[0].data).toEqual([10,null,100]);wrapper.unmount();
  });
});
