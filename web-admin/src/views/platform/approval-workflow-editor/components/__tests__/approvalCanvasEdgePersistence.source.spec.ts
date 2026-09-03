import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

/**
 * 2026-09-01 生产事故闸 —— LIUSHANMEN 的 PURCHASE_ORDER_APPROVAL 审批链被画布保存清空。
 *
 * 事故链：
 *   1. deserializeGraph 在**同一个 tick** 里先写 nodes.value 再写 edges.value
 *   2. VueFlow 在边模型一变就按内部 node store 校验端点，此时新节点还没注册
 *      ⇒ 三条边被当成悬空连线**静默丢弃**（画布显示"没有连线"）
 *   3. 用户保存 → serializeGraph 原样透传空数组 → 后端 edges_json = "[]"
 *   4. 引擎 walkToFirstHumanNode 沿边找第一个节点 ⇒ 找不到
 *      ⇒ 新单 422「采购 OA 流程没有可处理的当前节点」
 *      ⇒ 在途单 409 WORKFLOW_BOUND_DEFINITION_CHANGED（digest 变了）
 *   5. 产品内**没有恢复入口**（force-cancel 只存在于两处 "W4+ 要加" 的注释）
 *
 * 实测对照：同一租户下从未用画布保存过的 BUDGET_APPROVAL，三条边完好；
 * 两条链的节点结构完全相同（start / admin_approval / approved / rejected）。
 *
 * ⚠️ 同一个坑在 AI 编辑路径里早就被发现并规避了（"register the new cells first;
 * otherwise edges are checked against the previous node set and silently discarded"）。
 * 加载路径漏了同样的规避 —— 形态 D：同一个东西两份，一份修了一份没修。
 * ⛔ 本闸同时钉住两处，防止再漂开。
 */

function source(path: string): string {
  return readFileSync(resolve(process.cwd(), path), 'utf8')
}

/** 截取某个函数体（到下一个顶层 `\n}` 为止），用于做**顺序**断言而不是全文 includes。 */
function functionBody(src: string, signature: string): string {
  const start = src.indexOf(signature)
  expect(start, `找不到函数: ${signature}`).toBeGreaterThan(-1)
  const end = src.indexOf('\n}', start)
  expect(end, `函数 ${signature} 没有闭合`).toBeGreaterThan(start)
  return src.slice(start, end)
}

describe('approval canvas edge persistence contract', () => {
  const editor = source('src/views/platform/approval-workflow-editor/index.vue')

  /**
   * ⚠️ 2026-09-03 载体搬家: 这段「铺画布」的代码从 `deserializeGraph` 抽成了
   * `applyGraphToCanvas`（因为「步骤列表 → 画布」的模式切换要复用同一份, 见
   * index.vue 里那个函数的注释）。**顺序约束一个字没改**, 只是换了个函数名和
   * 参数名(parsedNodes/parsedEdges → graphNodes/graphEdges)。
   * ⛔ 不要因为函数名对不上就把这条闸删掉 —— 它守的是 VueFlow 的挂边时序,
   *    那个约束与代码放在哪个函数里无关。
   */
  it('applyGraphToCanvas 在节点注册之后才挂边（否则 VueFlow 会把边当悬空连线丢弃）', () => {
    const body = functionBody(editor, 'async function applyGraphToCanvas(')

    const clearEdges = body.indexOf('edges.value = []')
    const setNodes = body.indexOf('nodes.value = graphNodes')
    const awaitTick = body.indexOf('await nextTick()')
    const setEdges = body.indexOf('edges.value = graphEdges')

    expect(clearEdges, '必须先清空 edges').toBeGreaterThan(-1)
    expect(setNodes, '必须写 nodes.value').toBeGreaterThan(-1)
    expect(awaitTick, '节点与边之间必须 await nextTick()').toBeGreaterThan(-1)
    expect(setEdges, '必须写 edges.value').toBeGreaterThan(-1)

    // 顺序是这条闸的全部意义：clear → nodes → nextTick → edges
    expect(clearEdges).toBeLessThan(setNodes)
    expect(setNodes).toBeLessThan(awaitTick)
    expect(awaitTick).toBeLessThan(setEdges)
  })

  it('loadWorkflow 必须 await deserializeGraph，否则 nextTick 之后的挂边会被后续逻辑抢跑', () => {
    expect(editor).toContain('await deserializeGraph(res.data)')
  })

  it('AI 编辑路径保留同一条规避（两处不得漂开）', () => {
    // applyAiDraft 侧：同样是先清边、再注册节点。
    const aiClear = editor.indexOf('edges.value = []\n    nodes.value = draft.nodes')
    expect(
      aiClear,
      'AI 编辑路径的 "先清边再注册节点" 规避不见了 —— 两处必须保持同一形态',
    ).toBeGreaterThan(-1)
  })

  it('handleSave 硬拒绝「载入时有边、写回 0 条」的破坏性保存', () => {
    const body = functionBody(editor, 'async function handleSave(')

    expect(body).toContain('payload.edges.length === 0')
    expect(body).toContain('loadedEdgeCount.value > 0')
    // 必须在真正发请求之前拦住
    const guard = body.indexOf('payload.edges.length === 0')
    const update = body.indexOf('updateWorkflow(')
    expect(guard).toBeGreaterThan(-1)
    expect(update).toBeGreaterThan(-1)
    expect(guard, '闸必须在 updateWorkflow 之前').toBeLessThan(update)
  })

  it('loadedEdgeCount 由后端定义填充，而不是由画布当前状态填充', () => {
    const body = functionBody(editor, 'async function deserializeGraph(')
    expect(
      body,
      'loadedEdgeCount 必须取自 parsedEdges（后端定义），取自 edges.value 就成了恒等式',
    ).toContain('loadedEdgeCount.value = parsedEdges.length')
    expect(body).not.toContain('loadedEdgeCount.value = edges.value.length')
  })
})
