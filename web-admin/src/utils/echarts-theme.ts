/**
 * Shared ECharts theme -- 映射到 Cretas System v1（--cs-*，src/styles/tokens.css；P1）。
 * ECharts 画在 canvas 上读不了 CSS 变量，所以从 TS 镜像 CS_TOKENS 取值（闸钉住与 tokens.css 一致）。
 *
 * Register once in main.ts:  registerEChartsTheme()
 * Use in components:          echarts.init(el, 'cretas')
 *
 * Color palette mirrors CHART_COLORS from @/constants/chart-colors.ts
 * and the CSS custom properties --chart-color-1 through --chart-color-10.
 */
// echarts imported lazily inside registerEChartsTheme() to keep it out of the main chunk
import { CS_TOKENS } from '@/styles/csTokens'

const CHART_COLORS = [
  CS_TOKENS.primary, // brand blue (--chart-color-1 = --cs-primary)；原为 #2D8B57 绿，与界面主色不一致
  '#36B37E', // success green     (--chart-color-2)
  '#FFAB00', // amber warning     (--chart-color-3)
  '#FF5630', // tomato red        (--chart-color-4)
  '#6B778C', // slate gray        (--chart-color-5)
  '#2B7EC1', // primary light     (--chart-color-6)
  '#57D9A3', // mint green        (--chart-color-7)
  '#FFC400', // golden yellow     (--chart-color-8)
  '#FF8B6A', // salmon            (--chart-color-9)
  '#4C9AFF', // sky blue          (--chart-color-10)
]

const theme: Record<string, unknown> = {
  color: CHART_COLORS,
  backgroundColor: 'transparent',
  textStyle: {
    fontFamily: CS_TOKENS.fontFamily,
    color: CS_TOKENS.muted,
  },
  title: {
    textStyle: {
      color: CS_TOKENS.text,
      fontSize: 16,
      fontWeight: 600,
    },
    subtextStyle: {
      color: CS_TOKENS.muted,
      fontSize: 13,
    },
  },
  legend: {
    textStyle: {
      color: CS_TOKENS.muted,
      fontSize: 12,
    },
  },
  tooltip: {
    confine: true,
    backgroundColor: 'rgba(255, 255, 255, 0.96)',
    borderColor: CS_TOKENS.border,
    borderWidth: 1,
    textStyle: {
      color: CS_TOKENS.text,
      fontSize: 13,
    },
    extraCssText:
      'box-shadow: 0 2px 8px rgb(24 45 68 / 8%); border-radius: 10px; backdrop-filter: blur(8px);',
  },
  grid: {
    left: '3%',
    right: '4%',
    bottom: '3%',
    top: '15%',
    containLabel: true,
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: CS_TOKENS.border } },
    axisTick: { lineStyle: { color: CS_TOKENS.border } },
    axisLabel: { color: CS_TOKENS.muted, fontSize: 12 },
    splitLine: { lineStyle: { color: CS_TOKENS.border, type: 'dashed' } },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: CS_TOKENS.muted, fontSize: 12 },
    splitLine: { lineStyle: { color: CS_TOKENS.border, type: 'dashed' } },
  },
  line: {
    smooth: false,
    symbolSize: 6,
    lineStyle: { width: 2 },
  },
  bar: {
    barMaxWidth: 40,
    itemStyle: { borderRadius: [6, 6, 0, 0] },
  },
  pie: {
    itemStyle: { borderColor: CS_TOKENS.surface, borderWidth: 2 },
  },
}

/** Dark variant — same palette, inverted backgrounds */
const darkTheme: Record<string, unknown> = {
  ...theme,
  backgroundColor: '#1a1a2e',
  textStyle: { ...theme.textStyle as Record<string, unknown>, color: '#e0e0e0' },
  title: {
    textStyle: { color: '#f0f0f0', fontSize: 16, fontWeight: 600 },
    subtextStyle: { color: '#a0a0b0', fontSize: 13 },
  },
  legend: { textStyle: { color: '#c0c0d0', fontSize: 12 } },
  tooltip: {
    confine: true,
    backgroundColor: 'rgba(30, 30, 50, 0.95)',
    borderColor: '#3a3a5a',
    borderWidth: 1,
    textStyle: { color: '#e0e0e0', fontSize: 13 },
    extraCssText: 'box-shadow: 0 6px 20px rgba(0,0,0,0.3); border-radius: 10px; backdrop-filter: blur(8px);',
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: '#3a3a5a' } },
    axisTick: { lineStyle: { color: '#3a3a5a' } },
    axisLabel: { color: '#a0a0b0', fontSize: 12 },
    splitLine: { lineStyle: { color: '#2a2a4a', type: 'dashed' } },
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    axisLabel: { color: '#a0a0b0', fontSize: 12 },
    splitLine: { lineStyle: { color: '#2a2a4a', type: 'dashed' } },
  },
  pie: { itemStyle: { borderColor: '#1a1a2e', borderWidth: 2 } },
}

/**
 * Register the 'cretas' and 'cretas-dark' themes with ECharts.
 * Call this once before any chart is initialised (typically in main.ts).
 */
export async function registerEChartsTheme(): Promise<void> {
  const { default: echarts } = await import('@/utils/echarts')
  echarts.registerTheme('cretas', theme)
  echarts.registerTheme('cretas-dark', darkTheme)
}

export { CHART_COLORS }
export { theme as cretasLightTheme }
export default theme
