import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 源码闸：**业务入口（审批链配置）必须走线性步骤列表，且它的保存不得读画布状态。**
 *
 * 为什么要有这道闸 —— 它守的是 2026-09-01 事故的**根因**，不是那次的症状：
 *
 *   画布的 nodes/edges 是一份**可能丢边**的中间状态。只要保存路径还从那里取数，
 *   「加载丢边 → 保存写回空」这条链就还在。加保存闸只是把它变成「改不动」，
 *   ⛔ 没有消除它。步骤列表的保存走 `stepsToGraph`（从步骤数组确定性生成），
 *   与画布状态**完全无关** ⇒ 从形状上消灭这一类。
 *
 * ⚠️ 本闸用的是**代理判据**（源码文本），它看不见运行时真正走了哪条分支。
 * 行为侧由 `stepListRoundTrip.spec.ts` 覆盖，两者缺一不可。
 */

function source(rel: string): string {
  return readFileSync(resolve(process.cwd(), rel), 'utf8')
}

/** 截取某个函数体（到下一个顶层 `\n}` 为止），做**位置**断言而不是全文 includes。 */
function functionBody(src: string, signature: string): string {
  const start = src.indexOf(signature)
  expect(start, `找不到函数: ${signature}`).toBeGreaterThan(-1)
  const end = src.indexOf('\n}', start)
  expect(end, `函数 ${signature} 没有闭合`).toBeGreaterThan(start)
  return src.slice(start, end)
}

const EDITOR = 'src/views/platform/approval-workflow-editor/index.vue'

describe('业务入口必须走线性步骤列表', () => {
  const editor = source(EDITOR)

  it('serializeGraph 在步骤列表模式下**先于**任何画布读取就返回', () => {
    const body = functionBody(editor, 'function serializeGraph(')

    const stepBranch = body.indexOf('if (useStepList.value) return serializeStepFlow()')
    const canvasRead = body.indexOf('nodes.value.find')

    expect(stepBranch, '步骤列表分支不见了 —— 业务入口会退回画布序列化').toBeGreaterThan(-1)
    expect(canvasRead, '画布序列化本身应当还在（高级模式要用）').toBeGreaterThan(-1)
    expect(
      stepBranch,
      '步骤列表分支必须在读画布之前 —— 否则画布那份可能丢边的状态又回到保存路径上',
    ).toBeLessThan(canvasRead)
  })

  it('serializeStepFlow 只从 stepsToGraph 取图，⛔ 不碰 nodes.value / edges.value', () => {
    const body = functionBody(editor, 'function serializeStepFlow(')

    expect(body).toContain('stepsToGraph(')
    expect(
      body,
      'serializeStepFlow 读了画布节点 —— 那正是会丢边的那份状态',
    ).not.toContain('nodes.value')
    expect(
      body,
      'serializeStepFlow 读了画布连线 —— 那正是会丢边的那份状态',
    ).not.toContain('edges.value')
  })

  it('stepFlow 取自后端原文 parsedEdges，⛔ 不取自画布 edges.value', () => {
    const body = functionBody(editor, 'async function deserializeGraph(')

    const call = body.indexOf('graphToSteps(parsedNodes, parsedEdges, dto.startNodeId)')
    expect(
      call,
      'graphToSteps 必须喂后端原文；喂 edges.value 会把画布丢边的结果带进步骤列表',
    ).toBeGreaterThan(-1)

    // 必须在画布反序列化之前算完 —— 否则中途的 `edges.value = []` 会影响读数
    const clearEdges = body.indexOf('edges.value = []')
    expect(clearEdges).toBeGreaterThan(-1)
    expect(call, 'graphToSteps 必须在清空画布边之前算').toBeLessThan(clearEdges)
  })

  it('表达不了的图必须退回画布并给出原因，⛔ 不静默丢配置', () => {
    const body = functionBody(editor, 'async function deserializeGraph(')
    expect(body).toContain('stepModeFallbackReason.value')

    // useStepList 必须同时要求「业务入口」与「读得出来」——少任一个都是错的：
    //   少前者 → 高级模式也被换成步骤列表，平台管理员配不了分支
    //   少后者 → 复杂图被强行塞进线性列表，用户配置被丢
    const gate = editor.slice(editor.indexOf('const useStepList = computed('))
    const gateBody = gate.slice(0, gate.indexOf('\n)') + 2)
    expect(gateBody).toContain('props.lockDecisionType')
    expect(gateBody).toContain('stepFlow.value !== null')
  })

  /**
   * 🔴 2026-09-02 审查发现：`explainStepListFallback` 在 stepGraph.ts 里定义了
   * （#453 加的，7 个具名原因），但 index.vue 一次都没调用它 —— 横幅文案仍是
   * `deserializeGraph` 里手写的一句「有分支、并行或自定义条件」。
   * 那句话对「终端 id 不匹配」「有环」「缺通过出边」等原因是**假话**，
   * 会让人去找一个不存在的分支（正是 #453 那次事故复盘明确指出要避免的）。
   *
   * ⇒ 横幅文案必须由 `explainStepListFallback` **算出来**，⛔ 不许硬编码字面量。
   * 行为侧的逐原因验证见 `stepListFallbackBanner.spec.ts`（挂载真实组件，
   * 对 branching-node / unknown-node / no-approve-edge 各断言横幅文案
   * 精确等于 `STEP_LIST_FALLBACK_TEXT[reason]`，而不是"包含关键词"这种松判据）。
   */
  it('🔴 stepModeFallbackReason 必须来自 explainStepListFallback，⛔ 不许硬编码字面量', () => {
    const body = functionBody(editor, 'async function deserializeGraph(')

    const call = body.indexOf('stepModeFallbackReason.value = explainStepListFallback(')
    expect(
      call,
      'stepModeFallbackReason 不是由 explainStepListFallback 算出来的 —— '
      + '又变回了一句猜的文案，对「有环」「终端 id 不匹配」等原因是假话',
    ).toBeGreaterThan(-1)

    // 阴性对照：旧的那句硬编码文案不能再出现在源码的任何地方。
    expect(
      editor,
      '旧的硬编码横幅文案还在源码里 —— 它对多数退回原因是假话',
    ).not.toContain('有分支、并行或自定义条件，线性步骤列表表达不了')

    // 顶层 import 必须真的引入了这个符号，⛔ 不能只在注释里提到它。
    // import 可能跨多行（`import {\n  a,\n  b,\n} from '...'`），所以取整块，
    // 不能只看含 `from` 的那一行 —— 那一行常常只有闭括号。
    const fromIdx = editor.indexOf("from './lib/stepGraph'")
    expect(fromIdx, '找不到 stepGraph 的 import').toBeGreaterThan(-1)
    const openBraceIdx = editor.lastIndexOf('import {', fromIdx)
    expect(openBraceIdx, 'stepGraph 的 import 不是花括号具名导入').toBeGreaterThan(-1)
    const importBlock = editor.slice(openBraceIdx, fromIdx)
    expect(importBlock).toContain('explainStepListFallback')
  })

  /**
   * 数「没带 await 的 deserializeGraph 调用点」。
   * `(?<!function )` 把声明行本身排除掉，所以结果里**只有调用点**，⛔ 不要再减声明数。
   */
  function unawaitedCallCount(src: string): number {
    return (src.match(/(?<!await )(?<!function )deserializeGraph\(/g) ?? []).length
  }

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

  it('deserializeGraph 的每个调用点都带 await（2026-09-01: 2 个调用点只改了 1 个）', () => {
    // deserializeGraph 是 async：同步段先清空 edges，await nextTick 之后才填回。
    // 任何同步调用它的地方，紧接着读 edges.value 都会读到空数组。
    expect(editor).toContain('await deserializeGraph(dto)')
    expect(editor).toContain('await deserializeGraph(res.data)')
    expect(editor).toContain('await redrawCanvasPreservingSelection(res.data)')

    expect(
      unawaitedCallCount(editor),
      '有 deserializeGraph 调用点没有 await —— 「改共享结构前先数，改完再数」',
    ).toBe(0)
  })
})
