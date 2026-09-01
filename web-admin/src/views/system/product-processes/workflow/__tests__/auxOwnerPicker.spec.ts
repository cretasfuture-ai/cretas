import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { downstreamFinishedSkuIds } from '../workflowReworkTargets';

const EDITOR = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');

/**
 * owner 2026-09-01 实测：「包材跟辅料都没有办法去添加，知道去哪儿加呀？
 * 不就是在这个[画布上加]吗？」
 *
 * 旧行为：这道工序归属的成品定不出来 ⇒ 弹一句「请先从产出侧的包材 cell 建立 BOM」然后什么都不做。
 * 而「定不出来」的唯一常见原因是**这张图产出多个成品**（联产/多线路）——
 * `pickOwningProductId` 要求「画布上恰好一个成品」。于是任何联产图上辅料都加不了。
 *
 * ⇒ 定不出来不该拒绝，该**问用户算在哪个成品头上**。
 */
function node(id: string, kind: string, data: Record<string, unknown> = {}) {
  return { id, data: { kind, ...data } };
}
function edge(source: string, target: string) {
  return { id: `${source}->${target}`, source, target };
}

describe('联产图上「辅料算在哪个成品头上」', () => {
  //  原料 → 预处理 → 半成品 → { 装盒A → 成品A , 装盒B → 成品B }
  const nodes = [
    node('m:raw', 'RAW_MATERIAL', { skuId: 'RM-1' }),
    node('p:pre', 'PROCESS', { processName: '预处理' }),
    node('m:semi', 'SEMI_FINISHED', { skuId: 'PT-SEMI' }),
    node('p:packA', 'PROCESS', { processName: '装盒A' }),
    node('m:finA', 'FINISHED_GOOD', { skuId: 'PT-A' }),
    node('p:packB', 'PROCESS', { processName: '装盒B' }),
    node('m:finB', 'FINISHED_GOOD', { skuId: 'PT-B' }),
  ];
  const edges = [
    edge('m:raw', 'p:pre'), edge('p:pre', 'm:semi'),
    edge('m:semi', 'p:packA'), edge('p:packA', 'm:finA'),
    edge('m:semi', 'p:packB'), edge('p:packB', 'm:finB'),
  ];

  it('共用工序往下能走到两个成品 —— 这正是要问用户的场景', () => {
    expect(downstreamFinishedSkuIds(nodes, edges, 'p:pre').sort()).toEqual(['PT-A', 'PT-B']);
  });

  it('🔴 候选只取**这道工序下游**的成品，⛔ 不是整张画布的成品', () => {
    // 少了这条，实现写成「把画布上所有成品都列出来」也能让上面那条绿，
    // 而用户会在装盒A 上看到装盒B 的成品并选中它 —— 那是个错的归属，且不会报错。
    expect(downstreamFinishedSkuIds(nodes, edges, 'p:packA')).toEqual(['PT-A']);
    expect(downstreamFinishedSkuIds(nodes, edges, 'p:packB')).toEqual(['PT-B']);
  });

  it('走不到任何成品时返回空 —— 调用方据此说「先接到成品线上」，而不是弹选择器', () => {
    const orphan = [...nodes, node('p:lab', 'PROCESS', { processName: '试验线' })];
    expect(downstreamFinishedSkuIds(orphan, edges, 'p:lab')).toEqual([]);
  });

  it('画布层：定不出归属时**弹选择器**，⛔ 不再是弹一句警告就 return', () => {
    // 判据钉在分支的存在与顺序上：先问，只有真的走不到成品才解释。
    expect(EDITOR).toContain('downstreamFinishedSkuIds(flowNodes.value, flowEdges.value, processNodeId)');
    expect(EDITOR).toContain('auxOwnerPickerVisible.value = true');
    expect(EDITOR).toContain('data-testid="aux-owner-picker"');
    // ⛔ 那句把用户支去另一扇门的旧提示不许留着
    expect(EDITOR).not.toContain('联合生产请先从产出侧的包材 cell 建立 BOM');
    // 选完之后走的是**同一条**打开逻辑，不是第二份
    expect(EDITOR).toMatch(/confirmAuxOwner[\s\S]{0,400}await openAuxiliaryEditor\(/);
  });
});
