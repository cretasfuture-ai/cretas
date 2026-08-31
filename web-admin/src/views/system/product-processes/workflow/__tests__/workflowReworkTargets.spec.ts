import { describe, expect, it } from 'vitest';
import {
  deriveReworkTargetContext,
  planReworkTargetWrites,
  pruneReworkPolicies,
  reworkNodesMissingTargets,
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
  const proc = (id: string, name: string, extra: Record<string, unknown> = {}): ReworkFlowNodeLike => ({
    id, data: { kind: 'PROCESS', processName: name, ports: [], ...extra },
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
    // 回料由产品甲线路上的熟制工序产出
    { source: 'p-a2', target: 'rework-1' },
  ];
  return { nodes, edges };
}

describe('回料目标：来源与已选', () => {
  it('来源工序 = 产出这块回料的那道工序', () => {
    const { nodes, edges } = buildGraph();
    expect(sourceProcessOfRework(nodes, edges, 'rework-1')).toBe('p-a2');
  });

  it('已选目标从各工序的 reworkPolicy 反查 —— 唯一真值，⛔ 回料 Cell 上不存第二份', () => {
    const { nodes } = buildGraph();
    const withPolicy = nodes.map((node) => (node.id === 'p-b1'
      ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
      : node));
    expect(selectedTargetProcessIds(withPolicy, 'rework-1')).toEqual(['p-b1']);
    // 阴性对照：别的回料 id 不该被算进来。
    expect(selectedTargetProcessIds(withPolicy, 'rework-9')).toEqual([]);
  });
});

describe('目标工序按实际产品线路分组（§8）', () => {
  it('线路条数来自拓扑，⛔ 不硬编码 4，也不硬编码任何产品名', () => {
    const { nodes, edges } = buildGraph();
    const context = deriveReworkTargetContext(nodes, edges, 'rework-1');
    const names = context.groups.map((group) => group.routeName);
    // 3 条具名线路 + 共用工序组 + 未绑成品组
    expect(names).toContain('产品甲');
    expect(names).toContain('产品乙');
    expect(names).toContain('产品丙');
    expect(names).toContain('多线路共用工序');
    expect(names).toContain('尚未绑定成品的线路');
    expect(context.groups).toHaveLength(5);
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
    // 同一张图连算两次必须完全一致（⛔ 不许"先到先得"式的随机归属）。
    const again = deriveReworkTargetContext(nodes, edges, 'rework-1');
    expect(JSON.stringify(again.groups)).toBe(
      JSON.stringify(deriveReworkTargetContext(nodes, edges, 'rework-1').groups));
  });

  it('每组带「已选 n / 总数」所需的数据，顶部有全局已选数', () => {
    const { nodes, edges } = buildGraph();
    const withPolicy = nodes.map((node) => (node.id === 'p-b1'
      ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
      : node));
    const context = deriveReworkTargetContext(withPolicy, edges, 'rework-1');
    expect(context.totalCount).toBe(6);      // 图里 6 道工序
    expect(context.selectedCount).toBe(1);
    const groupB = context.groups.find((group) => group.routeName === '产品乙');
    expect(groupB?.options.filter((option) => option.selected)).toHaveLength(1);
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

describe('保存是更新同一组规则，不是重复追加（§7）', () => {
  it('选中 → 写进目标工序的 reworkPolicy', () => {
    const { nodes } = buildGraph();
    const { updates } = planReworkTargetWrites(nodes, 'rework-1', ['p-b1']);
    expect(updates).toEqual([{
      processNodeId: 'p-b1',
      policy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 },
    }]);
  });

  it('🔴 保存两次结果完全相同 —— 不会把同一个 id 追加第二遍', () => {
    const { nodes } = buildGraph();
    const applied = nodes.map((node) => (node.id === 'p-b1'
      ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
      : node));
    // 已经是目标了，再保存一次应当**没有任何写入**（幂等）。
    expect(planReworkTargetWrites(applied, 'rework-1', ['p-b1']).updates).toEqual([]);
  });

  it('取消最后一个目标 → 整个 policy 清成 null，⛔ 不留空数组空壳', () => {
    const { nodes } = buildGraph();
    const applied = nodes.map((node) => (node.id === 'p-b1'
      ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
      : node));
    expect(planReworkTargetWrites(applied, 'rework-1', []).updates)
      .toEqual([{ processNodeId: 'p-b1', policy: null }]);
  });

  it('取消其中一个不影响别的回料的规则', () => {
    const { nodes } = buildGraph();
    const applied = nodes.map((node) => (node.id === 'p-b1'
      ? {
        ...node,
        data: {
          ...node.data,
          reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1', 'rework-2'], maxInputRatioPercent: 30 },
        },
      }
      : node));
    expect(planReworkTargetWrites(applied, 'rework-1', []).updates).toEqual([{
      processNodeId: 'p-b1',
      policy: { allowedSourceReworkNodeIds: ['rework-2'], maxInputRatioPercent: 30 },
    }]);
  });
});

describe('fail closed 与清理', () => {
  it('没有目标工序的回料会被门禁点名', () => {
    const { nodes } = buildGraph();
    expect(reworkNodesMissingTargets(nodes)).toEqual(['rework-1']);
  });

  it('配了目标就放行（阳性对照：这道闸真的会变绿，不是恒红）', () => {
    const { nodes } = buildGraph();
    const applied = nodes.map((node) => (node.id === 'p-b1'
      ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
      : node));
    expect(reworkNodesMissingTargets(applied)).toEqual([]);
  });

  it('删掉回料 Cell 后，指向它的规则被清掉，不留幽灵 id', () => {
    const { nodes } = buildGraph();
    const applied = nodes
      .map((node) => (node.id === 'p-b1'
        ? { ...node, data: { ...node.data, reworkPolicy: { allowedSourceReworkNodeIds: ['rework-1'], maxInputRatioPercent: 0 } } }
        : node))
      .filter((node) => node.id !== 'rework-1');
    expect(pruneReworkPolicies(applied).updates).toEqual([{ processNodeId: 'p-b1', policy: null }]);
  });

  it('没有幽灵时不产生任何写入（阴性对照：清理不会顺手改别的东西）', () => {
    const { nodes } = buildGraph();
    expect(pruneReworkPolicies(nodes).updates).toEqual([]);
  });
});
