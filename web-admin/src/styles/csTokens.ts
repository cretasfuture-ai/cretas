/**
 * Cretas System v1 token 的 TS 镜像，给拿不到 CSS 变量的地方用（ECharts 主题在 canvas 里画，读不了 var()）。
 * 唯一真值是 src/styles/tokens.css；这里每个值必须与它逐字相等，
 * 闸 src/__tests__/gates/csTokensMirror.spec.ts 解析 tokens.css 比对，改了一边没改另一边会红。
 */
export const CS_TOKENS = {
  primary: '#1b65a8',
  primaryHover: '#15568f',
  primarySoft: '#edf4fb',
  bg: '#f4f6f9',
  surface: '#fff',
  surfaceSubtle: '#f8fafc',
  text: '#1a2332',
  muted: '#526278',
  border: '#dce3ed',
  success: '#24724f',
  successSoft: '#edf8f1',
  warning: '#885910',
  warningSoft: '#fff8e8',
  danger: '#b42318',
  dangerSoft: '#fff1f0',
  info: '#205c90',
  infoSoft: '#edf4fb',
  fontFamily: '"Microsoft YaHei","PingFang SC",-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif',
} as const

export type CsTokenName = keyof typeof CS_TOKENS

/** camelCase 键 → tokens.css 里的变量名（primaryHover → --cs-primary-hover） */
export function csVarName(key: CsTokenName): string {
  return '--cs-' + key.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())
}
