import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'
import { readJavaSource } from '@/utils/__tests__/javaSource'

/**
 * 后端 `interview-defaults` 给的每一段，前端都得**接**或**显式声明不接**。
 *
 * <h2>它防的那个反复出现的形状</h2>
 *
 * 「写完没挂」在本仓出现过太多次：
 * - `getInterviewDefaults` 端点建好之后，前端**一个消费点都没有**，四段现值从没显示过
 * - 「盯哪些生产指标」后端接完（目录/校验/预览/落库全有），界面上**没有任何入口**
 *
 * 两次都不会报错、不会红、CI 全绿 —— 只是那个能力用户永远碰不到。
 *
 * <h2>为什么允许「显式不接」</h2>
 *
 * 有些段本来就不该在这个页面渲染（权限矩阵是另一个页面的事）。
 * 所以判据不是「必须全渲染」，而是**必须在这里做过决定** ——
 * 要么页面里出现这个 key，要么写进下面的豁免表并说明去处。
 * ⛔ 豁免必须写理由：没有理由的豁免会变成下一个人眼里的「已处理」。
 */
describe('interview-defaults 的每一段都要有去处', () => {
  const HERE = dirname(fileURLToPath(import.meta.url))
  const PAGE = readFileSync(resolve(HERE, '..', 'index.vue'), 'utf8')
  const EDITOR = readFileSync(resolve(HERE, '..', 'components', 'FactoryAssemblyDetailEditor.vue'), 'utf8')
  const SURFACE = `${PAGE}\n${EDITOR}`

  /**
   * 取 **forFactory 真正返回**的那些段的 key。
   *
   * ⚠️ 第一版我直接 grep 全文的 `setKey("...")`，变异测试当场证伪:
   * 把 `out.add(permissions())` 从 forFactory 里删掉之后，`permissions()` 方法体
   * 和它的 setKey 还在文件里 —— 闸照样绿。**那是「闸在找错地方」**:
   * 它测的是「这个方法存在」，而契约是「这一段被返回给前端」。
   *
   * 现在两步走: 先从 forFactory 里取 `out.add(xxx(` 的方法名，再去各方法体里读它的 setKey。
   */
  function activeSectionKeys(java: string): string[] {
    const ff = java.slice(java.indexOf('List<Section> forFactory'))
    const body = ff.slice(0, ff.indexOf('return out;'))
    const methods = [...body.matchAll(/out\.add\(([a-zA-Z]+)\(/g)].map((m) => m[1])
    return methods
      .map((name) => {
        const at = java.indexOf(`Section ${name}(`)
        if (at < 0) return null
        const m = /setKey\("([a-zA-Z]+)"\)/.exec(java.slice(at, at + 1200))
        return m ? m[1] : null
      })
      .filter((k): k is string => k !== null)
  }

  /** 不在建厂页渲染的段 —— key → 去处/理由 */
  const RENDERED_ELSEWHERE: Record<string, string> = {
    permissions: '权限矩阵走角色权限页，建厂只用它当推荐来源',
  }

  /** 后端段名与建厂载荷字段不完全同名的唯一映射。 */
  const SURFACE_TOKEN: Record<string, string> = {
    modules: 'suggestedData.modules',
    thresholds: 'suggestedData.thresholds',
    indicators: 'indicatorOptOut',
  }

  it('🔴 后端每个 section key 都要么被页面渲染，要么被显式豁免', () => {
    // ⛔ 先剥注释：Java 注释里会提到别的段的 key（比如解释「与 X 段不是一回事」），
    //    不剥会把它们当成真的 setKey 调用。
    const java = readJavaSource('InterviewDefaults.java')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/\/\/[^\n]*/g, ' ')

    const keys = activeSectionKeys(java)

    expect(keys.length, 'Java 里一段都没解析出来 —— 闸本身跑不了').toBeGreaterThan(3)

    const missing = keys.filter(
      (k) => !SURFACE.includes(SURFACE_TOKEN[k] ?? `'${k}'`) && !(k in RENDERED_ELSEWHERE)
    )

    expect(
      missing,
      `这些段后端给了、页面没接、也没写进豁免表：${missing.join(', ')} —— ` +
        '后端接完但界面没入口，用户永远碰不到，而且 CI 全绿'
    ).toEqual([])
  })

  it('⛔ 阴性对照：豁免表里的 key 必须真的还是后端的段', () => {
    const java = readJavaSource('InterviewDefaults.java')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/\/\/[^\n]*/g, ' ')
    const keys = new Set(activeSectionKeys(java))

    // 段被删了而豁免还留着 = 死配置：它一行都影响不到，却让人以为「这条已经决定过了」
    const stale = Object.keys(RENDERED_ELSEWHERE).filter((k) => !keys.has(k))
    expect(stale, `豁免表里这些 key 后端已经没有了，是死配置：${stale.join(', ')}`).toEqual([])
  })
})
