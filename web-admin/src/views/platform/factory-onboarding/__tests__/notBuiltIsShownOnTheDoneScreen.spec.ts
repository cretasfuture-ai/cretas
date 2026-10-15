import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 「你说了而没配上」的那几条，**建厂完成页也要说**，⛔ 不能只有复核页。
 *
 * <h2>治的那个缺口（owner 2026-08-18 指出）</h2>
 *
 * 上一轮把 `notBuilt` 做进了复核向导（六屏逐屏显示，真实浏览器点过）。
 * 而 owner 追问的是**建厂完成页**那一屏 —— 那是客户刚建完看到的第一屏，
 * 也是他唯一一次「趁还记得就去补」的时机。
 *
 * 此前那一屏只把这几条混在 `aiSummary` 那一大段文字里（靠客户自己读出来）。
 *
 * <h2>⚠️ 为什么这里读源码而不是 mount 整页</h2>
 *
 * 那一屏（`step === 2`）要**走完一次完整访谈**才出得来 —— 40+ 轮真实模型调用。
 * mount 整页需要把访谈/生成/预览/落库四个端点全部打桩，而那样测到的是
 * 「我打的桩」不是「产品」。⇒ 这里钉住**渲染路径存在且接的是同一份数据**：
 *
 *   · 那一屏读的是 `applied.notBuilt`（与复核向导同一份，⛔ 不是前端另算一遍）
 *   · 分组、条目、原因、怎么补四样都渲染了
 *
 * ⛔ 本测试**不保证**它在浏览器里长得好看，也不保证客户读得懂 ——
 *    那一格只有真走完一次流程才知道，如实登记在交接件的「未核实」栏。
 */
describe('建厂完成页要说「你说了而没配上」', () => {
  const HERE = dirname(fileURLToPath(import.meta.url))
  const PAGE = readFileSync(resolve(HERE, '..', 'index.vue'), 'utf8')

  /** ⛔ 先剥注释再数 —— 本仓栽过三次「闸把它自己要求写下的注释数了进去」。 */
  const CODE = PAGE
    .replace(/<!--[\s\S]*?-->/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')

  it('那一屏读的是 applied.notBuilt —— 与复核向导同一份数据', () => {
    expect(CODE).toContain('applied.value?.notBuilt')
  })

  it('四样都渲染: 分组 / 条目 / 为什么 / 怎么补', () => {
    expect(CODE, '没有分组标题').toContain('g.group')
    expect(CODE, '没有条目名').toContain('i.item')
    expect(CODE, '没有原因 —— 「跳过了 N 条」不构成交代').toContain('i.why')
    expect(CODE, '没有「怎么补」—— 客户不知道下一步做什么').toContain('howToFix')
  })

  it('⛔ 前端不许自己再算一遍「哪些没配上」', () => {
    // 生成端才知道客户说过什么; 前端再推一遍就是同一个东西两份, 一定会漂。
    expect(CODE).not.toMatch(/notBuilt\s*=\s*\[/)
    expect(CODE).not.toMatch(/function\s+computeNotBuilt/)
  })

  it('条数要显示出来 —— 只说「有几样没配上」而不给数字等于没说', () => {
    expect(CODE).toContain('notBuiltCount')
  })
})
