/**
 * 源码里不再写 Element Plus 默认主色 #409eff（DECISIONS 第二十节：--cs-* 是唯一真值）。
 * 样式用 var(--cs-primary)，拿不到 CSS 变量的 JS（ECharts/canvas）用 CS_TOKENS.primary。
 * 允许名单 = 解释「为什么旧值被盖掉」的注释，共 4 个文件，不是用法。
 */
import { describe, expect, it } from 'vitest'
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'

const ROOT = join(__dirname, '..', '..', '..')
const ALLOWED_COMMENT_ONLY = new Set([
  'src/__tests__/gates/csTokensMirror.spec.ts',
  'src/__tests__/gates/noHardcodedElementPrimary.spec.ts',
  'src/style.css',
  'src/styles/element-plus-theme.css',
  'src/views/platform/factory-onboarding/index.vue',
])

function walk(dir: string, out: string[] = []): string[] {
  for (const n of readdirSync(dir)) {
    const p = join(dir, n)
    if (statSync(p).isDirectory()) walk(p, out)
    else if (/\.(vue|ts|tsx|css|scss|js)$/.test(n)) out.push(p)
  }
  return out
}

describe('no hardcoded #409eff', () => {
  it('only allowlisted comment files mention it', () => {
    const files = walk(join(ROOT, 'src'))
    expect(files.length).toBeGreaterThan(500) // 仪器自检：确实扫到了源码
    const offenders = files
      .filter((f) => /#409eff/i.test(readFileSync(f, 'utf-8')))
      .map((f) => relative(ROOT, f).split(sep).join('/'))
      .filter((f) => !ALLOWED_COMMENT_ONLY.has(f))
    expect(offenders).toEqual([])
  })
})
