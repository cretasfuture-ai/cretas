import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 「配出来了、而你没说过」要和「你说了、没配上」**分成两块**显示。
 *
 * <h2>治的那个缺口（2026-08-18 实测）</h2>
 *
 * 同一套答案连跑 8 遍，客户报了 2 个成品的名字，有 2 遍配出 **4 个**。
 * 多出来的那两个在预览里看得见，却**没有一句话点出「这几个是我加的」**——
 * 客户会以为那也是自己说过的。
 *
 * 而 `aiSummary` 那句话**只活在建厂那一屏**，他第二天来复核时早关了；
 * 复核的三份对账里一个字都没有。
 *
 * <h2>⛔ 为什么不合并成一块</h2>
 *
 * 两者的措辞**正好相反**。混在一起，客户会把「没配上」的东西读成「配上了」。
 *
 * <h2>⚠️ 老数据</h2>
 *
 * 快照里的旧条目**没有 kind** ⇒ 一律按 `missing` 处理。
 * ⛔ 不许因为它是 null 就不显示 —— 那会让历史建厂的交代凭空消失。
 */
describe('多配的那几条：单独一块，不和「没配上」混在一起', () => {
  const HERE = dirname(fileURLToPath(import.meta.url))
  const read = (...p: string[]) => readFileSync(resolve(HERE, ...p), 'utf8')
  const strip = (t: string) =>
    t.replace(/<!--[\s\S]*?-->/g, '').replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')

  const DONE = strip(read('..', 'index.vue'))
  const REVIEW = strip(read('..', '..', 'factory-review', 'index.vue'))

  it('建厂完成页：两块都在，且各自的措辞不同', () => {
    expect(DONE, '没有「没配上」那一块').toContain('你说了，但这几样没配上')
    expect(DONE, '没有「我补的」那一块 = 多配又变回没人点出来')
      .toContain('这几样是我补的，你没有专门说过')
  })

  it('复核向导：同样两块都在', () => {
    expect(REVIEW).toContain('你说了，但这几样没配上')
    expect(REVIEW).toContain('这几样是我补的，你没有专门说过')
  })

  it('🔴 两块读的是同一条通道，按 kind 分 —— ⛔ 前端不许自己判哪些是多配的', () => {
    for (const [name, src] of [['done', DONE], ['review', REVIEW]] as const) {
      expect(src, `${name}: 没按 kind 分`).toMatch(/kind === 'extra'/)
      // 生成端才知道客户说过什么；前端再推一遍就是同一个东西两份，一定会漂
      expect(src, `${name}: 前端自己算了一遍`).not.toMatch(/declaredProducts|说过的成品/)
    }
  })

  it('⚠️ 老快照没有 kind 时按「没配上」处理 —— 不许漏显示', () => {
    // 判据：过滤用的是 `kind === 'extra'`（null 落进 !isExtra 那一侧），
    // ⛔ 不许写成 `kind === 'missing'`（那会让老条目两块都进不去）
    for (const [name, src] of [['done', DONE], ['review', REVIEW]] as const) {
      expect(src, `${name}: 用了 kind === 'missing'，老条目会消失`)
        .not.toMatch(/kind === 'missing'/)
    }
  })
})
