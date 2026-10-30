/**
 * ECharts 按需引入 — 减少首屏约 500KB
 *
 * 所有组件统一从此文件导入:
 *   import echarts from '@/utils/echarts'
 *
 * 替代全量引入:
 *   import * as echarts from 'echarts'  // ← 不要再用
 *
 * 类型补丁 (2026-05-09): echarts/core 不导出 EChartsOption / SeriesOption 等
 * 高级类型,但运行时 use() 注册后这些类型实际可用. 通过 namespace merge
 * 把 echarts top-level type 合并进 @/utils/echarts 的默认导出 namespace.
 */
import * as echarts from 'echarts/core'
import type {
  EChartsOption as _EChartsOption,
  SeriesOption as _SeriesOption,
  LineSeriesOption as _LineSeriesOption,
  BarSeriesOption as _BarSeriesOption,
  ScatterSeriesOption as _ScatterSeriesOption,
  GraphicComponentOption as _GraphicComponentOption,
  MarkAreaComponentOption as _MarkAreaComponentOption,
  MarkLineComponentOption as _MarkLineComponentOption,
  GaugeSeriesOption as _GaugeSeriesOption,
  RadarComponentOption as _RadarComponentOption,
  CustomSeriesRenderItemReturn as _CustomSeriesRenderItemReturn,
  DefaultLabelFormatterCallbackParams as _DefaultLabelFormatterCallbackParams,
} from 'echarts'

// Augment echarts/core namespace so `echarts.EChartsOption` resolves.
declare module 'echarts/core' {
  type EChartsOption = _EChartsOption
  type SeriesOption = _SeriesOption
  type LineSeriesOption = _LineSeriesOption
  type BarSeriesOption = _BarSeriesOption
  type ScatterSeriesOption = _ScatterSeriesOption
  type GraphicComponentOption = _GraphicComponentOption
  type MarkAreaComponentOption = _MarkAreaComponentOption
  type MarkLineComponentOption = _MarkLineComponentOption
  type GaugeSeriesOption = _GaugeSeriesOption
  type RadarComponentOption = _RadarComponentOption
  type CustomSeriesRenderItemReturn = _CustomSeriesRenderItemReturn
  type DefaultLabelFormatterCallbackParams = _DefaultLabelFormatterCallbackParams
}


// ---- Charts ----
import {
  BarChart,
  LineChart,
  PieChart,
  ScatterChart,
  RadarChart,
  GaugeChart,
  HeatmapChart,
  MapChart,
  CustomChart,
  BoxplotChart,
  SunburstChart,
  FunnelChart,
  TreemapChart,
  SankeyChart,
  TreeChart,
  ThemeRiverChart,
  PictorialBarChart,
  CandlestickChart,
} from 'echarts/charts'

// ---- Components ----
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  TitleComponent,
  DataZoomComponent,
  ToolboxComponent,
  MarkLineComponent,
  MarkPointComponent,
  MarkAreaComponent,
  VisualMapComponent,
  GeoComponent,
  GraphicComponent,
  DatasetComponent,
  TransformComponent,
  ParallelComponent,
  AxisPointerComponent,
  PolarComponent,
} from 'echarts/components'
import { LabelLayout } from 'echarts/features'

// ---- Renderers ----
import { CanvasRenderer, SVGRenderer } from 'echarts/renderers'

// ---- Word Cloud Plugin (auto-registers wordCloud series type) ----
//
// 🔴 2026-09-02: 这个包在没有 canvas 2d context 的环境(jsdom 单测)下会**同步 throw**
// (`Sorry your browser not support wordCloud`, 见 echarts-wordcloud/src/wordCloud.js
// 的 isSupported 探测)。实测: M67YieldCost.vue(成品出厂核算)因此**从未被任何 spec
// mount 过**——生产管理菜单收拢 (LIUSHANMEN) 把它并进 /production/analysis-query
// hub 后, 想按硬约束 9 证明这个 tab 真的渲染, 第一次踩中这颗雷。
//
// ⚠️ 曾经改成过 fire-and-forget 动态 import + catch —— coordinator 复核 #487 时
// 指出这样会引入竞态: 真实浏览器里注册挪到了下一个 microtask，若某个组件在**首次
// 渲染 wordCloud 系列时注册还没完成**，echarts 会报「series type wordCloud not
// exists」。⇒ **保留静态 import**(注册一定先于任何渲染完成)，测试侧改用
// `vi.mock('echarts-wordcloud', () => ({}))` 规避 jsdom 无 canvas 的问题——
// ⛔ 不为了测试能跑而改生产行为。消费方 (git grep -n wordCloud web-admin/src)：
// components/smartbi/DynamicChartRenderer.vue (唯一真正渲染 type:'wordCloud' 系列
// 的组件) + views/smart-bi/composables/useSmartBIDashboardLayout.ts (只是图表类型
// 选择器里的一个目录项，不触发渲染)。需要挂载会触达词云渲染路径的组件的 spec，
// 各自在文件顶部 `vi.mock('echarts-wordcloud', () => ({}))`——见
// productionAnalysisHub.mount.spec.ts 与 productionHubRedirects.spec.ts
// (后者的「预热」静态 import 会把 analysis/index.vue → M67YieldCost.vue → 本文件
// 一起转译)。
import 'echarts-wordcloud'

echarts.use([
  // charts
  BarChart, LineChart, PieChart, ScatterChart, RadarChart,
  GaugeChart, HeatmapChart, MapChart, CustomChart, BoxplotChart,
  SunburstChart, FunnelChart, TreemapChart, SankeyChart, TreeChart,
  ThemeRiverChart, PictorialBarChart, CandlestickChart,
  // components
  GridComponent, TooltipComponent, LegendComponent, TitleComponent,
  DataZoomComponent, ToolboxComponent, MarkLineComponent, MarkPointComponent, MarkAreaComponent,
  VisualMapComponent, GeoComponent, GraphicComponent, DatasetComponent,
  TransformComponent, ParallelComponent, AxisPointerComponent, PolarComponent,
  LabelLayout,
  // renderers
  CanvasRenderer, SVGRenderer,
])

// ---- Light theme ----
echarts.registerTheme('cretas', {
  color: ['#2D8B57', '#36B37E', '#FFAB00', '#FF5630', '#6B778C',
          '#4C9AFF', '#57D9A3', '#FFC400', '#FF8B6A', '#A3A0FB'],
  backgroundColor: 'transparent',
  textStyle: { color: '#303133' },
  title: { textStyle: { color: '#303133' }, subtextStyle: { color: '#909399' } },
  legend: { textStyle: { color: '#606266' } },
  tooltip: {
    backgroundColor: 'rgba(255,255,255,0.96)',
    borderColor: '#e4e7ed',
    textStyle: { color: '#303133' },
    extraCssText: 'box-shadow:0 4px 12px rgba(0,0,0,0.08);border-radius:8px;padding:12px 16px;',
  },
  categoryAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#909399', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#909399', fontSize: 11 },
    splitLine: { lineStyle: { color: '#f0f2f5', type: 'dashed' } },
  },
  line: { symbol: 'circle', symbolSize: 6, lineStyle: { width: 3 }, smooth: true },
  bar: { itemStyle: { borderRadius: [4, 4, 0, 0] } },
  pie: { itemStyle: { borderColor: '#fff', borderWidth: 2 } },
})

// ---- B3: Dark mode theme ----
echarts.registerTheme('cretas-dark', {
  color: ['#4C9AFF', '#57D9A3', '#FFC400', '#FF8B6A', '#A3A0FB',
          '#2B7EC1', '#36B37E', '#FFAB00', '#FF5630', '#6B778C'],
  backgroundColor: '#1a1a2e',
  textStyle: { color: '#e0e0e0' },
  title: { textStyle: { color: '#e0e0e0' }, subtextStyle: { color: '#aaa' } },
  legend: { textStyle: { color: '#c0c0c0' } },
  tooltip: {
    backgroundColor: 'rgba(22,33,62,0.96)',
    borderColor: '#2a2a4a',
    textStyle: { color: '#e0e0e0' },
    extraCssText: 'box-shadow:0 4px 20px rgba(0,0,0,0.4);border-radius:8px;padding:12px 16px;',
  },
  axisPointer: { lineStyle: { color: '#4C9AFF' } },
  categoryAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#b0b0b0', fontSize: 11 },
    splitLine: { lineStyle: { color: '#2a2a4a', type: 'dashed' } },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#b0b0b0', fontSize: 11 },
    splitLine: { lineStyle: { color: '#2a2a4a', type: 'dashed' } },
  },
  line: { symbol: 'circle', symbolSize: 6, lineStyle: { width: 3 }, smooth: true },
  bar: { itemStyle: { borderRadius: [4, 4, 0, 0] } },
  pie: { itemStyle: { borderColor: '#1a1a2e', borderWidth: 2 } },
  gauge: { axisLine: { lineStyle: { color: [[1, '#3a3a5a']] } } },
  dataZoom: {
    backgroundColor: 'rgba(76,154,255,0.05)',
    fillerColor: 'rgba(76,154,255,0.15)',
    handleStyle: { color: '#4C9AFF', borderColor: '#4C9AFF' },
    textStyle: { color: '#b0b0b0' },
    borderColor: 'transparent',
  },
  toolbox: {
    iconStyle: { borderColor: '#b0b0b0' },
    emphasis: { iconStyle: { borderColor: '#4C9AFF' } },
  },
  markLine: { lineStyle: { color: '#4a4a6a' } },
})

export default echarts
