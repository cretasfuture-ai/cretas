import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 源码闸：**`deserializeGraph` 的每一个调用点都必须带 `await`。**
 *
 * <h3>守的是什么行为</h3>
 * `deserializeGraph` 是 async：它的同步段先 `edges.value = []`，然后停在内部的
 * `await nextTick()`。⇒ 任何**不 await** 地调用它的地方，控制权会立刻回到调用方，
 * 而调用方紧接着读到的 `edges.value` 是**空数组**。
 *
 * 2026-09-01 实测的后果（nice-lederberg 查出）：`redrawCanvasPreservingSelection`
 * 里那两个 `edges.value.map(...)` 全在空数组上跑，`edges.value.some(...)` 恒 false
 * ⇒ 选中的若是一条**边**，属性面板**必然被关掉** —— 正是该函数要防的事，它自己犯了。
 * ⚠️ 不丢数据（边最终会被写回来），但「保住选中」在边这一侧**从未生效**。
 *
 * <h3>成因是本仓那条「改共享结构前先数，改完再数」</h3>
 * `deserializeGraph` 被改成 async 时**有 2 个调用点，只更新了 1 个**。
 *
 * <h3>⚠️ 这道闸为什么单独成一个 PR</h3>
 * 它原本长在 #443（线性步骤列表替换画布）的 diff 里。那是一次**形状变更**，可能被
 * revert；而本闸守的缺陷与那次变更**无关**，不该跟着一起消失。
 * ⛔ PR comment 不能当载体 —— comment 是**关于**载体的文档，它拦不住 revert 把代码带走，
 * 也不会在 CI 上变红。**判据是「有人把 await 拿掉时，CI 会不会红」**，只有闸本身做得到。
 *
 * <h3>⚠️ 代理判据</h3>
 * 这是**源码文本**判据，看不见运行时真正走了哪条分支。所以下面第一条测试是
 * **阳性对照**：先证明这个探测器在一个已知漏 await 的样本上读得出 1，
 * 它在真源码上读出的 0 才有意义。⛔ 没有阳性对照的 0 是不可判定的。
 */

function source(rel: string): string {
  return readFileSync(resolve(process.cwd(), rel), 'utf8')
}

const EDITOR = 'src/views/platform/approval-workflow-editor/index.vue'

/**
 * 数「没带 await 的 `deserializeGraph` 调用点」。
 *
 * `(?<!function )` 把**声明行本身**排除掉，所以结果里只有调用点，
 * ⛔ 不要再去减声明数 —— 那会变成两处口径，一定会漂。
 */
function unawaitedCallCount(src: string): number {
  return (src.match(/(?<!await )(?<!function )deserializeGraph\(/g) ?? []).length
}

describe('deserializeGraph 的调用点必须带 await', () => {
  it('🔴 阳性对照：这个探测器**确实抓得到**漏掉的 await', () => {
    const withBug = `
      async function deserializeGraph(dto) { edges.value = []; await nextTick() }
      async function redraw(dto) { deserializeGraph(dto) }
    `
    expect(
      unawaitedCallCount(withBug),
      '探测器对一个已知漏 await 的样本都读出 0 —— 那它在真源码上读 0 也没有意义',
    ).toBe(1)

    const fixed = withBug.replace('{ deserializeGraph(dto) }', '{ await deserializeGraph(dto) }')
    expect(unawaitedCallCount(fixed), '阴性对照：补上 await 之后必须归零').toBe(0)
  })

  it('真源码里每个 deserializeGraph 调用点都带 await（2026-09-01: 2 个调用点只改了 1 个）', () => {
    const editor = source(EDITOR)

    expect(editor).toContain('await deserializeGraph(dto)')
    expect(editor).toContain('await deserializeGraph(res.data)')

    expect(
      unawaitedCallCount(editor),
      '有 deserializeGraph 调用点没有 await —— 「改共享结构前先数，改完再数，两个数必须相等」',
    ).toBe(0)
  })

  it('传染到调用方：redrawCanvasPreservingSelection 自身必须 async 且被 await', () => {
    const editor = source(EDITOR)

    expect(
      editor,
      'redrawCanvasPreservingSelection 里要 await deserializeGraph，它自己就必须是 async',
    ).toContain('async function redrawCanvasPreservingSelection(')

    // ⚠️ 只改被调方不够: 它变成 async 之后, 它自己的调用点不 await 同样会读到半成品。
    expect(
      (editor.match(/(?<!await )(?<!function )redrawCanvasPreservingSelection\(/g) ?? []).length,
      'redrawCanvasPreservingSelection 有调用点没有 await —— 同一个形状又长了一层',
    ).toBe(0)
  })
})
