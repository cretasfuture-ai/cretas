import { describe, expect, it } from 'vitest';
import {
  deriveReworkTargetContext,
  inputRatioFor,
  planReworkTargetWrites,
  pruneReworkPolicies,
  reworkNodesWithoutTargets,
  selectedTargetProcessIds,
  sourceProcessOfRework,
  type ReworkFlowEdgeLike,
  type ReworkFlowNodeLike,
} from '../workflowReworkTargets';

/**
 * 造一张**多线路**图：3 条终端成品线路 + 1 道多线路共用工序 + 1 条未绑成品的线路。
 * ⛔ 刻意不是 4 条 —— 分组必须由拓扑派生，代码里不许出现任何硬编码的线路条数。
 */
function buildGraph(): { nodes: ReworkFlowNodeLike[]; edges: ReworkFlowEdgeLike[] } {
  const proc = (id: string, name: string): ReworkFlowNodeLike => ({
    id, data: { kind: 'PROCESS', processName: name, ports: [] },
  });
  const fin = (id: string, sku: string, name: string): ReworkFlowNodeLike => ({
    id, data: { kind: 'FINISHED_GOOD', skuId: sku, name },
  });
  const nodes: ReworkFlowNodeLike[] = [
    { id: 'raw', data: { kind: 'RAW_MATERIAL', skuId: 'R1', name: '原料' } },
    proc('p-shared', '预处理'),
    proc('p-a1', '腌制A'), proc('p-a2', '熟制A'),
    proc('p-b1', '腌制B'),
    proc('p-c1', '分切C'),
    proc('p-unbound', '试验线工序'),
    fin('f-a', 'SKU-A', '产品甲'),
    fin('f-b', 'SKU-B', '产品乙'),
    fin('f-c', 'SKU-C', '产品丙'),
    { id: 'rework-1', data: { kind: 'SEMI_FINISHED', skuId: '', name: '回料 1', isRework: true } },
  ];
  const edges: ReworkFlowEdgeLike[] = [
    { source: 'raw', target: 'p-shared' },
    { source: 'p-shared', target: 'p-a1' },
    { source: 'p-shared', target: 'p-b1' },
    { source: 'p-a1', target: 'p-a2' },
    { source: 'p-a2', target: 'f-a' },
    { source: 'p-b1', target: 'f-b' },
    { source: 'p-c1', target: 'f-c' },
    { source: 'p-a2', target: 'rework-1' },
  ];
  return { nodes, edges };
}

/** 给某道工序挂上「接收 rework-1，按 ratio% 投」的配方项。 */
function withPolicy(
  nodes: ReworkFlowNodeLike[],
  processNodeId: string,
  sources: Array<{ reworkNodeId: string; inputRatioPercent: number }>,
): ReworkFlowNodeLike[] {
  return nodes.map((node) => (node.id === processNodeId
    ? { ...node, data: { ...node.data, reworkPolicy: { sources } } }
    : node));
}

describe('回料目标：来源与已选', () => {
  it('来源工序 = 产出这块回料的那道工序', () => {
    const { nodes, edges } = buildGraph();
    expect(sourceProcessOfRework(nodes, edges, 'rework-1')).toBe('p-a2');
  });

  it('已选目标从各工序的 reworkPolicy 反查 —— 唯一真值，⛔ 回料 Cell 上不存第二份', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    expect(selectedTargetProcessIds(applied, 'rework-1')).toEqual(['p-b1']);
    // 阴性对照：别的回料 id 不该被算进来。
    expect(selectedTargetProcessIds(applied, 'rework-9')).toEqual([]);
  });
});

describe('投入比例是 per-(回料, 工序) 的配方值', () => {
  it('读得回这一对各自的比例', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [
      { reworkNodeId: 'rework-1', inputRatioPercent: 15 },
      { reworkNodeId: 'rework-2', inputRatioPercent: 40 },
    ]);
    expect(inputRatioFor(applied, 'rework-1', 'p-b1')).toBe(15);
    expect(inputRatioFor(applied, 'rework-2', 'p-b1')).toBe(40);
  });

  it('🔴 同一道工序接两块回料时，两个比例互不干扰 —— 这是换掉 #417 单值模型的理由', () => {
    // #417 把比例挂在**工序**上（一个工序一个数）。同一道工序接两块回料时，
    // 那个模型只能存一个数 —— 表达不了「回料甲投 15%、回料乙投 40%」。
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [
      { reworkNodeId: 'rework-1', inputRatioPercent: 15 },
      { reworkNodeId: 'rework-2', inputRatioPercent: 40 },
    ]);
    const { updates } = planReworkTargetWrites(applied, 'rework-1', [
      { processNodeId: 'p-b1', inputRatioPercent: 22 },
    ]);
    const sources = updates[0].policy?.sources ?? [];
    expect(sources.find((s) => s.reworkNodeId === 'rework-1')?.inputRatioPercent).toBe(22);
    // 另一块回料的比例**一个字没动**
    expect(sources.find((s) => s.reworkNodeId === 'rework-2')?.inputRatioPercent).toBe(40);
  });

  it('没配这一对时返回 null，⛔ 不返回 0 —— 0 是合法比例', () => {
    // 形态 A¹⁰：兜底的 0 会把「还没配」翻译成「配了，是 0%」，对下游是两回事。
    const { nodes } = buildGraph();
    expect(inputRatioFor(nodes, 'rework-1', 'p-b1')).toBeNull();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 0 }]);
    expect(inputRatioFor(applied, 'rework-1', 'p-b1')).toBe(0);
  });

  it('比例被夹在 0–100', () => {
    const { nodes } = buildGraph();
    const high = planReworkTargetWrites(nodes, 'rework-1', [{ processNodeId: 'p-b1', inputRatioPercent: 250 }]);
    expect(high.updates[0].policy?.sources[0].inputRatioPercent).toBe(100);
    const low = planReworkTargetWrites(nodes, 'rework-1', [{ processNodeId: 'p-b1', inputRatioPercent: -5 }]);
    expect(low.updates[0].policy?.sources[0].inputRatioPercent).toBe(0);
  });

  it('派生上下文把比例带给抽屉', () => {
    const { nodes, edges } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    const option = deriveReworkTargetContext(applied, edges, 'rework-1')
      .groups.flatMap((group) => group.options)
      .find((candidate) => candidate.processNodeId === 'p-b1');
    expect(option?.selected).toBe(true);
    expect(option?.inputRatioPercent).toBe(15);
  });
});

describe('目标工序按实际产品线路分组（§8）', () => {
  it('线路条数来自拓扑，⛔ 不硬编码 4，也不硬编码任何产品名', () => {
    const { nodes, edges } = buildGraph();
    const names = deriveReworkTargetContext(nodes, edges, 'rework-1').groups.map((g) => g.routeName);
    expect(names).toContain('产品甲');
    expect(names).toContain('产品乙');
    expect(names).toContain('产品丙');
    expect(names).toContain('多线路共用工序');
    expect(names).toContain('尚未绑定成品的线路');
    expect(names).toHaveLength(5);
  });

  it('来源线路排最前', () => {
    const { nodes, edges } = buildGraph();
    const context = deriveReworkTargetContext(nodes, edges, 'rework-1');
    expect(context.groups[0].routeName).toBe('产品甲');
    expect(context.groups[0].isSourceRoute).toBe(true);
  });

  it('多线路共用工序进「共用工序」组，规则确定不随机', () => {
    const { nodes, edges } = buildGraph();
    const shared = deriveReworkTargetContext(nodes, edges, 'rework-1')
      .groups.find((group) => group.isSharedGroup);
    expect(shared?.options.map((option) => option.processNodeId)).toEqual(['p-shared']);
    expect(JSON.stringify(deriveReworkTargetContext(nodes, edges, 'rework-1').groups))
      .toBe(JSON.stringify(deriveReworkTargetContext(nodes, edges, 'rework-1').groups));
  });

  it('每组带「已选 n / 总数」所需的数据，顶部有全局已选数', () => {
    const { nodes, edges } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    const context = deriveReworkTargetContext(applied, edges, 'rework-1');
    expect(context.totalCount).toBe(6);
    expect(context.selectedCount).toBe(1);
    expect(context.groups.find((g) => g.routeName === '产品乙')?.options.filter((o) => o.selected))
      .toHaveLength(1);
  });

  it('短名进卡片、完整名进 tooltip/aria-label', () => {
    const { nodes, edges } = buildGraph();
    const option = deriveReworkTargetContext(nodes, edges, 'rework-1')
      .groups.flatMap((group) => group.options)
      .find((candidate) => candidate.processNodeId === 'p-a2');
    expect(option?.processName).toBe('熟制A');
    expect(option?.fullLabel).toBe('熟制A · 产品甲');
  });
});

describe('保存是更新同一组规则，不是重复追加', () => {
  it('选中 → 写进目标工序的 reworkPolicy', () => {
    const { nodes } = buildGraph();
    const { updates } = planReworkTargetWrites(nodes, 'rework-1', [
      { processNodeId: 'p-b1', inputRatioPercent: 15 },
    ]);
    expect(updates).toEqual([{
      processNodeId: 'p-b1',
      policy: { sources: [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }] },
    }]);
  });

  it('🔴 保存两次结果完全相同 —— 不会把同一个 id 追加第二遍', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    expect(planReworkTargetWrites(applied, 'rework-1', [
      { processNodeId: 'p-b1', inputRatioPercent: 15 },
    ]).updates).toEqual([]);
  });

  it('只改比例也算变更（阴性对照：幂等判定不能把改比例吞掉）', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    const { updates } = planReworkTargetWrites(applied, 'rework-1', [
      { processNodeId: 'p-b1', inputRatioPercent: 20 },
    ]);
    expect(updates[0].policy?.sources[0].inputRatioPercent).toBe(20);
  });

  it('取消最后一个目标 → 整个 policy 清成 null，⛔ 不留空数组空壳', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    expect(planReworkTargetWrites(applied, 'rework-1', []).updates)
      .toEqual([{ processNodeId: 'p-b1', policy: null }]);
  });

  it('取消其中一个不影响别的回料的规则', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [
      { reworkNodeId: 'rework-1', inputRatioPercent: 15 },
      { reworkNodeId: 'rework-2', inputRatioPercent: 30 },
    ]);
    expect(planReworkTargetWrites(applied, 'rework-1', []).updates).toEqual([{
      processNodeId: 'p-b1',
      policy: { sources: [{ reworkNodeId: 'rework-2', inputRatioPercent: 30 }] },
    }]);
  });
});

describe('⛔ 回料是可选项：一个目标都不配也不许拦（owner 2026-09-01 裁定）', () => {
  it('目标清空是**合法保存**，不是被拒绝的输入', () => {
    // 判据换代（形态 C‴）：旧断言守的是交接书 §7 的 fail-closed，
    // 而 owner 明确推翻了它 —— 回料本来就是可选能力，让它拦住整张工艺图的发布是本末倒置。
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    const { updates } = planReworkTargetWrites(applied, 'rework-1', []);
    expect(updates).toEqual([{ processNodeId: 'p-b1', policy: null }]);
  });

  it('「还没配目标」只是一个可见状态，函数名里不带门禁语义', () => {
    const { nodes } = buildGraph();
    expect(reworkNodesWithoutTargets(nodes)).toEqual(['rework-1']);
    // 阳性对照：配了目标之后这个状态会消失（否则它是恒真的，什么都没说）。
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }]);
    expect(reworkNodesWithoutTargets(applied)).toEqual([]);
  });

  it('阴性对照：编辑器里没有任何一处拿它拦发布', async () => {
    // 「机制在、没接上」的反面：这里要证明它**故意没接**在发布路径上。
    const { readFileSync } = await import('node:fs');
    const { resolve } = await import('node:path');
    const editor = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');
    expect(editor).not.toMatch(/reworkNodesWithoutTargets[\s\S]{0,200}return\b/);
    expect(editor).not.toContain('reworkTargetsSatisfied');
    expect(editor).not.toContain('无法发布');
  });
});

describe('删除后的清理', () => {
  it('删掉回料 Cell 后，指向它的规则被清掉，不留幽灵 id', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }])
      .filter((node) => node.id !== 'rework-1');
    expect(pruneReworkPolicies(applied).updates)
      .toEqual([{ processNodeId: 'p-b1', policy: null }]);
  });

  it('只删掉失效的那一条，别的回料规则保留', () => {
    const { nodes } = buildGraph();
    const applied = withPolicy(nodes, 'p-b1', [
      { reworkNodeId: 'rework-1', inputRatioPercent: 15 },
      { reworkNodeId: 'ghost', inputRatioPercent: 30 },
    ]);
    expect(pruneReworkPolicies(applied).updates).toEqual([{
      processNodeId: 'p-b1',
      policy: { sources: [{ reworkNodeId: 'rework-1', inputRatioPercent: 15 }] },
    }]);
  });

  it('没有幽灵时不产生任何写入（阴性对照：清理不会顺手改别的东西）', () => {
    const { nodes } = buildGraph();
    expect(pruneReworkPolicies(nodes).updates).toEqual([]);
  });
});
