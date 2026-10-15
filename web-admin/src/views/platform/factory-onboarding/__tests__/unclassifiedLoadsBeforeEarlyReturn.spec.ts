import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

/**
 * 「没有分类的物料」必须在 alerts 段的 early-return **之前**读出来。
 *
 * <h2>它防的那个缺陷</h2>
 *
 * `loadAlertCatalog()` 里有一句:
 * ```
 * const sec = data.find(x => x.key === 'alerts')
 * if (!sec) { alertRows.value = []; return }
 * ```
 * 我第一版把「未分类物料」的读取写在了这个 return **后面** —— 后端没给 alerts 段时
 * (旧版本后端 / 该段抛错 / 权限裁剪), 未分类物料就永远不显示。
 *
 * ⛔ 而未分类物料恰恰是**静默缺口本身**: 那批物料在按类别筛选的库存台账里一条都不出现,
 * 没人会收到报错。唯一的发现途径就是这个面板。把它挂在另一段的成功路径后面,
 * 等于「兜底装在它该开的那扇门后面」。
 *
 * 这类顺序缺陷只在「后端少给一段」时才现形, 平时跑不出来 —— 所以钉源码顺序。
 */
describe('未分类物料的读取顺序', () => {
  const HERE = dirname(fileURLToPath(import.meta.url))
  const SRC = readFileSync(resolve(HERE, '..', 'index.vue'), 'utf8')

  it('🔴 materialClassification 必须在 alerts 的 early-return 之前读', () => {
    const mcIdx = SRC.indexOf("key === 'materialClassification'")
    const alertsIdx = SRC.indexOf("key === 'alerts'")

    expect(mcIdx, '找不到 materialClassification 的读取 —— 闸本身跑不了').toBeGreaterThan(0)
    expect(alertsIdx, "找不到 alerts 的读取 —— 闸本身跑不了").toBeGreaterThan(0)

    // early-return 就在 alerts 查找之后; 只要 mc 在 alerts 之前, 就不可能落到 return 后面
    expect(mcIdx).toBeLessThan(alertsIdx)
  })

  it('⛔ 阴性对照: 那个 early-return 确实还在(否则上一条测的是不存在的风险)', () => {
    const alertsIdx = SRC.indexOf("key === 'alerts'")
    const after = SRC.slice(alertsIdx, alertsIdx + 400)
    expect(after, 'alerts 段缺失时提前返回的分支必须还在').toContain('return')
  })

  it('⛔ 不许提供「一键补全分类」', () => {
    // 按名字猜分类会把猜错的值一路写进台账筛选和 BOM 替代族, 而那两处都是静默失败。
    // 实测有真实物料的两家工厂根本没建过 L1 分类树 —— 猜也没有地方可写。
    //
    // ⚠️ 必须先剥注释: 第一版没剥, 当场红在**页面里我自己写的那句说明**上
    //    (「⛔ 只显示, 不提供「一键补全」」)。这是今天第三次踩同一个坑 ——
    //    boundaries 闸把 javadoc 里的 @Entity 当真注解 / 编码测试红在自己引用的旧代码。
    //    ⛔ 判据: 包含/正则判断跑在源码上, 一律先剥注释。
    const code = SRC.replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/\/\/.*/g, ' ')
    expect(code).not.toContain('一键补全')
    expect(code).not.toContain('autoClassify')
  })
})
