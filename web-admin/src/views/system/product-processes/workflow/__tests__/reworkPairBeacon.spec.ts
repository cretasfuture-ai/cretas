import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  REWORK_PAIR_COLORS,
  assignReworkPairs,
  reworkPairFocusIds,
  reworkPairsForProcess,
} from '../workflowReworkTargets';

const EDITOR = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf8');
const PROCESS_NODE = readFileSync(resolve(__dirname, '../WorkflowProcessNode.vue'), 'utf8');

/**
 * 已确认原型 v8 的口径：**「回料使用同色 R 编号配对，不绘制永久回头线」**。
 *
 * 为什么不是连线：回投是跨工序的远程关系。一张多线路的图上画回头线会出现十几条
 * 横穿画布的线，谁连谁读不出来。原型改成：回料 Cell 上一个 R 编号，允许它回投的
 * 工序上一个同色同号的角标；悬停时两头一起亮、其余压暗。
 *
 * owner 2026-09-01 实测反馈：我原来只做了「压暗」没做「亮起」，
 * 观感是「整张图变灰」而不是「这两个是一对」——所以下面把**亮起**那一半也钉住。
 */
function node(id: string, data: Record<string, unknown>) {
  // ⚠️ kind 挂在 **data** 上, 不是节点上 —— `kindOf` 读的是 `node.data.kind`。
  //    我第一版样例先漏了 kind、再把它写在节点上, 两次都读出「一个目标工序都没有」,
  //    而那两次都是样例的毛病, 不是产品的(形态 A: 我量的不是我想知道的那个数)。
  return { id, data: { kind: id.startsWith('process:') ? 'PROCESS' : 'SEMI_FINISHED', ...data } };
}

describe('回料 R 编号配对', () => {
  const nodes = [
    node('material:raw', { name: '原料' }),
    node('material:rw1', { name: '回料甲', isRework: true }),
    node('process:cure', {
      processName: '腌制',
      reworkPolicy: { sources: [{ reworkNodeId: 'material:rw1', inputRatioPercent: 8.5 }] },
    }),
    node('material:rw2', { name: '回料乙', isRework: true }),
    node('process:cook', {
      processName: '熟制',
      reworkPolicy: {
        sources: [
          { reworkNodeId: 'material:rw1', inputRatioPercent: 10 },
          { reworkNodeId: 'material:rw2', inputRatioPercent: 5 },
        ],
      },
    }),
  ];

  it('按画布顺序发号，配色取自原型色板', () => {
    const pairs = assignReworkPairs(nodes);
    expect([...pairs.keys()]).toEqual(['material:rw1', 'material:rw2']);
    expect(pairs.get('material:rw1')).toEqual({ label: 'R1', color: REWORK_PAIR_COLORS[0] });
    expect(pairs.get('material:rw2')).toEqual({ label: 'R2', color: REWORK_PAIR_COLORS[1] });
  });

  it('🔴 阴性对照：不是回料的节点不发号 —— 证明上面不是「给每个节点都编一个」', () => {
    expect(assignReworkPairs(nodes).has('material:raw')).toBe(false);
    expect(assignReworkPairs(nodes).has('process:cure')).toBe(false);
  });

  it('目标工序上显示的角标 = 允许回投到它的那几块回料', () => {
    expect(reworkPairsForProcess(nodes, 'process:cure').map((p) => p.label)).toEqual(['R1']);
    expect(reworkPairsForProcess(nodes, 'process:cook').map((p) => p.label)).toEqual(['R1', 'R2']);
    // 没配 reworkPolicy 的工序不显示任何角标
    expect(reworkPairsForProcess(nodes, 'material:raw')).toEqual([]);
  });

  it('悬停一块回料时亮起的是「它 + 它的目标工序」，⛔ 不含中间路径', () => {
    expect(reworkPairFocusIds(nodes, 'material:rw1').sort())
      .toEqual(['material:rw1', 'process:cook', 'process:cure']);
    // 阴性对照：另一块回料的目标集合不同 —— 否则上面可能只是「返回了所有工序」
    expect(reworkPairFocusIds(nodes, 'material:rw2').sort())
      .toEqual(['material:rw2', 'process:cook']);
  });

  it('⛔ 编号按节点顺序，不按 id/名字排序', () => {
    // 换个排序规则会让同一张图在两次打开之间编号不同 —— 用户记住的 R1 会变成 R2。
    const reordered = [nodes[3], nodes[1]];   // 乙在前、甲在后
    const pairs = assignReworkPairs(reordered);
    expect(pairs.get('material:rw2')?.label).toBe('R1');
    expect(pairs.get('material:rw1')?.label).toBe('R2');
  });

  it('画布上「亮起」和「压暗」都在，⛔ 不是只压暗', () => {
    // 🔴 这条守的正是 owner 反馈的那个差别。少了 wf-pair-active，
    //    悬停的效果就退回成「整张图变灰」。
    expect(EDITOR).toContain("classes.push('wf-pair-active')");
    expect(EDITOR).toContain("classes.push('wf-focus-dim')");
    expect(EDITOR).toContain('--pair-color');
    // 描边环用配对色，且压暗时连线也让路
    expect(EDITOR).toMatch(/\.wf-pair-active\)\s*\{[\s\S]{0,200}box-shadow/);
    expect(EDITOR).toContain('.workflow-canvas.is-pair-focus :deep(.vue-flow__edge) { opacity: 0.1; }');
  });

  it('工序卡片上真的渲染了同色角标', () => {
    expect(PROCESS_NODE).toContain('data-testid="rework-pair-row"');
    expect(PROCESS_NODE).toContain(':style="{ background: pair.color }"');
    expect(PROCESS_NODE).toContain('{{ pair.label }}');
    // 编辑器把它接上了 —— 否则组件写了也没人传（形态 B：机制在、没接上）
    expect(EDITOR).toContain(':rework-pairs="reworkPairsOnProcess(slotProps.id)"');
  });

  it('⛔ 不绘制永久回头线 —— 附属条的边被隐掉，画布上没有回料→工序的连线', () => {
    // 原型口径写死在这里：关系用配对表达，不用线。
    expect(EDITOR).toContain('edge.hidden = true');
    expect(EDITOR).not.toMatch(/addReturnEdge|createReworkEdge|回头线.*flowEdges\.value\.push/);
  });
});
