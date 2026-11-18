import { mount } from '@vue/test-utils';
import { nextTick } from 'vue';
import { describe, expect, it } from 'vitest';
import { useVueFlow } from '@vue-flow/core';
import TraceDagView from '../TraceDagView.vue';
import { buildTraceGraph, chainOf, dagFitZoom, DAG_FONT, DAG_MIN_ZOOM, DAG_MAX_ZOOM } from '../traceGraph';
import { baseTrace, cattleNode, SEASONING_NODE, sixCattleTrace } from './fixtures';
import type { FullTraceResponse } from '@/api/traceability';

/** 多上游(2 原料) + 2 道工序 + 2 张出货(同客户 + 另一客户) + 下游批次 + 深层(depth 2)节点。 */
function richTrace(): FullTraceResponse {
  return baseTrace({
    processingStages: [
      { stageType: 'CUTTING', stageName: '分割', stageOrder: 1, operatorName: '张三' },
      { stageType: 'PACK', stageName: '包装', stageOrder: 2 },
    ],
    shipments: [
      { shipmentNumber: 'SH-1', shipmentDate: null, customerName: '某超市', status: 'delivered', quantity: 60, unit: 'kg' },
      { shipmentNumber: 'SH-2', shipmentDate: null, customerName: '某超市', status: 'shipped', quantity: 40, unit: 'kg' },
      { shipmentNumber: 'SH-3', shipmentDate: null, customerName: '', status: 'pending' },
    ],
    lineage: {
      rootType: 'PRODUCTION_BATCH',
      rootId: 'pb-1',
      ancestors: [
        { depth: 1, nodes: [cattleNode(1), cattleNode(2)] },
        { depth: 2, nodes: [SEASONING_NODE] },
      ],
      descendants: [
        { depth: 1, nodes: [{ nodeType: 'FINISHED_BATCH', nodeId: 'fb-1', batchNumber: 'FB-1', name: '成品A', quantity: 10, unit: 'kg', resolved: true }] },
      ],
      maxDepth: 2,
      nodeCount: 4,
    },
  });
}

describe('buildTraceGraph 边组装', () => {
  it('多上游: 每个一跳原料都连到第一道工序, 工序串成链, 末道工序连本批次', () => {
    const g = buildTraceGraph(richTrace());
    const ids = new Set(g.edges.map((e) => `${e.source}>${e.target}`));
    expect(ids.has('up:1:MATERIAL_BATCH:mb-1>stage:0')).toBe(true);
    expect(ids.has('up:1:MATERIAL_BATCH:mb-2>stage:0')).toBe(true);
    expect(ids.has('stage:0>stage:1')).toBe(true);
    expect(ids.has('stage:1>root')).toBe(true);
  });
  it('多下游: 本批次连质检、下游批次、每张出货; 同名客户合并为一个节点', () => {
    const g = buildTraceGraph(richTrace());
    const ids = new Set(g.edges.map((e) => `${e.source}>${e.target}`));
    expect(ids.has('root>ship:SH-1')).toBe(true);
    expect(ids.has('root>ship:SH-2')).toBe(true);
    expect(ids.has('root>down:1:FINISHED_BATCH:fb-1')).toBe(true);
    expect([...ids].some((i) => i.startsWith('root>qc:'))).toBe(true);
    expect(g.nodes.filter((n) => n.kind === 'customer')).toHaveLength(2);
    expect(ids.has('ship:SH-1>cust:某超市')).toBe(true);
    expect(ids.has('ship:SH-2>cust:某超市')).toBe(true);
  });
  it('深层(depth≥2)节点不连线并标明未记录连线, 不编造边', () => {
    const g = buildTraceGraph(richTrace());
    const deep = g.nodes.find((n) => n.id.startsWith('up:2:'))!;
    expect(deep.linked).toBe(false);
    expect(deep.statusText).toContain('具体连线未记录');
    expect(g.edges.some((e) => e.source === deep.id || e.target === deep.id)).toBe(false);
  });
  it('缺数据节点显示「未记录」而不是空白或 0', () => {
    const g = buildTraceGraph(richTrace());
    const noCust = g.nodes.find((n) => n.id === 'cust:__missing__')!;
    expect(noCust.title).toBe('未记录');
    const stage2 = g.nodes.find((n) => n.id === 'stage:1')!;
    expect(stage2.lines.find((l) => l.label === '操作员')!.value).toBe('未记录');
    expect(stage2.lines.find((l) => l.label === '投入→产出')!.value).toBe('未记录');
    const ship3 = g.nodes.find((n) => n.id === 'ship:SH-3')!;
    expect(ship3.lines.find((l) => l.label === '数量')!.value).toBe('未记录');
  });
  it('chainOf 同时给出上游与下游整条链, 不含旁支', () => {
    const g = buildTraceGraph(richTrace());
    const c = chainOf(g, 'stage:0');
    expect(c.nodes.has('up:1:MATERIAL_BATCH:mb-1')).toBe(true);
    expect(c.nodes.has('root')).toBe(true);
    expect(c.nodes.has('cust:某超市')).toBe(true);
    const c2 = chainOf(g, 'ship:SH-1');
    expect(c2.nodes.has('ship:SH-2')).toBe(false);
    expect(c2.nodes.has('up:1:MATERIAL_BATCH:mb-2')).toBe(true);
  });
});

async function mountDag(trace: FullTraceResponse) {
  const wrapper = mount(TraceDagView, { props: { trace }, attachTo: document.body });
  await nextTick();
  await new Promise((r) => setTimeout(r, 30));
  await nextTick();
  return wrapper;
}

describe('TraceDagView 挂载', () => {
  it('真渲染节点与连线 (正查+反查在同一张图)', async () => {
    const trace = richTrace();
    const g = buildTraceGraph(trace);
    const w = await mountDag(trace);
    expect(w.findAll('.vue-flow__node')).toHaveLength(g.nodes.length);
    expect(w.findAll('.vue-flow__edge')).toHaveLength(g.edges.length);
    expect(w.text()).toContain('MB-2026-001');
    expect(w.text()).toContain('SH-1');
    expect(w.text()).toContain('某超市');
  });

  it('点击节点: 高亮上下游链路、旁支变淡, 详情里显示链路规模; 再点取消', async () => {
    const w = await mountDag(richTrace());
    await w.get('[data-node-id="ship:SH-1"]').trigger('click');
    expect(w.get('[data-node-id="ship:SH-1"]').attributes('data-state')).toBe('selected');
    expect(w.get('[data-node-id="up:1:MATERIAL_BATCH:mb-1"]').attributes('data-state')).toBe('chain'); // 反查
    expect(w.get('[data-node-id="cust:某超市"]').attributes('data-state')).toBe('chain'); // 正查
    expect(w.get('[data-node-id="ship:SH-2"]').attributes('data-state')).toBe('dim');
    expect(w.get('[data-testid="trace-dag-detail"]').text()).toContain('SH-1');
    expect(w.get('[data-testid="trace-dag-chain-summary"]').text()).toMatch(/上游 \d+ 个节点，下游 1 个节点/);
    await w.get('[data-node-id="ship:SH-1"]').trigger('click');
    expect(w.find('[data-testid="trace-dag-detail"]').exists()).toBe(false);
    expect(w.get('[data-node-id="ship:SH-2"]').attributes('data-state')).toBe('normal');
  });

  it('未连线的深层节点点开说明原因, 且只高亮它自己', async () => {
    const w = await mountDag(richTrace());
    const deep = w.get('[data-node-id^="up:2:"]');
    await deep.trigger('click');
    expect(w.find('[data-testid="trace-dag-unlinked"]').exists()).toBe(true);
    expect(w.get('[data-node-id="root"]').attributes('data-state')).toBe('dim');
  });

  it('带个体的原料可跳个体抽屉; 本批次可跳投入产出; 无目标的节点没有按钮', async () => {
    const w = await mountDag(sixCattleTrace());
    await w.get('[data-node-id="up:1:MATERIAL_BATCH:mb-1"]').trigger('click');
    await w.get('[data-testid="trace-dag-action"]').trigger('click');
    expect(w.emitted('open-individual')![0]).toEqual(['si-1']);
    await w.get('[data-node-id="root"]').trigger('click');
    await w.get('[data-testid="trace-dag-action"]').trigger('click');
    expect(w.emitted('open-evidence')).toHaveLength(1);
    await w.get('[data-node-id="stage:0"]').trigger('click');
    expect(w.find('[data-testid="trace-dag-action"]').exists()).toBe(false);
  });
});

const UUID_QC = '11111111-2222-3333-4444-823d7330abcd';
function qcTrace(): FullTraceResponse {
  return baseTrace({
    qualityInspections: [
      { inspectionId: UUID_QC, inspectionDate: '2026-09-10T08:00:00', inspectorName: '李四', result: 'PASSED', passRate: 98 },
      { inspectionId: '99999999-0000-0000-0000-aaaabbbbcccc', inspectionDate: null, inspectorName: null, result: 'FAILED' },
    ],
  });
}

describe('BP-53 质检节点不显示内部短码', () => {
  it('图数据: 质检节点标题/要点里没有 inspectionId 片段, 显示「质检 + 结论」与批次号', () => {
    const g = buildTraceGraph(qcTrace());
    const qcs = g.nodes.filter((n) => n.kind === 'quality');
    expect(qcs).toHaveLength(2);
    for (const n of qcs) {
      const all = [n.title, n.statusText, ...n.lines.map((l) => `${l.label}${l.value}`)].join('|');
      expect(all).not.toMatch(/#[0-9a-f]{8}/i);
      expect(all).not.toContain('823d7330');
      expect(all).not.toContain('aaaabbbbcccc');
    }
    expect(qcs[0].title).toBe('质检 已通过');
    expect(qcs[1].title).toBe('质检 未通过');
    expect(qcs[0].lines.find((l) => l.label === '批次')!.value).toBe(buildTraceGraph(qcTrace()).nodes.find((n) => n.kind === 'root')!.title);
  });
  it('真渲染: 质检节点 DOM 文本不含短码', async () => {
    const w = await mountDag(qcTrace());
    const qc = w.findAll('[data-kind="quality"]');
    expect(qc).toHaveLength(2);
    for (const n of qc) expect(n.text()).not.toMatch(/#[0-9a-f]{8}/i);
    expect(qc[0].text()).toContain('质检 已通过');
  });
});

describe('BP-53 默认视图字号与要点', () => {
  it('基准字号 × 最低缩放: 主标题 ≥14px, 次要信息 ≥12px', () => {
    expect(DAG_FONT.title * DAG_MIN_ZOOM).toBeGreaterThanOrEqual(14);
    expect(DAG_FONT.line * DAG_MIN_ZOOM).toBeGreaterThanOrEqual(12);
    expect(DAG_FONT.label * DAG_MIN_ZOOM).toBeGreaterThanOrEqual(12);
  });
  it('dagFitZoom 随容器宽度变化, 且被上下限夹住', () => {
    expect(dagFitZoom(300, 3000)).toBe(DAG_MIN_ZOOM);
    expect(dagFitZoom(10000, 1000)).toBe(DAG_MAX_ZOOM);
    const a = dagFitZoom(1500, 1700);
    const b = dagFitZoom(1700, 1700);
    expect(b).toBeGreaterThan(a);
    expect(dagFitZoom(0, 1000)).toBe(1);
  });
  it('真渲染: 节点上不点开就直接显示要点, 且字号来自基准常量', async () => {
    const w = await mountDag(richTrace());
    const stage = w.get('[data-node-id="stage:0"]');
    expect(stage.text()).toContain('分割');
    expect(stage.text()).toContain('张三');
    expect(stage.text()).toContain('投入→产出');
    const root = w.get('[data-node-id="root"]');
    expect(root.text()).toContain('PB-2026-001');
    expect(root.text()).toContain('数量');
    expect(w.find('[data-testid="trace-dag-detail"]').exists()).toBe(false); // 未点开
    expect(stage.get('[data-testid="dag-node-title"]').attributes('style')).toContain(`font-size: ${DAG_FONT.title}px`);
    for (const l of stage.findAll('[data-testid="dag-node-line"]')) {
      expect(l.attributes('style')).toContain(`font-size: ${DAG_FONT.line}px`);
    }
  });
  it('画布高度随图高自适应 (内联样式, 非写死 460px)', async () => {
    const w = await mountDag(richTrace());
    const h = parseInt((w.get('[data-testid="trace-dag-canvas"]').element as HTMLElement).style.height, 10);
    expect(h).toBeGreaterThanOrEqual(360);
    expect(h).toBeLessThanOrEqual(720);
  });
});

describe('BP-54 默认缩放不低于可读下限', () => {
  it('容器比整图窄很多(整图适应缩放 <0.5): 默认真实视口缩放 ≥ 0.5 且停在起点(左侧)', async () => {
    const proto = HTMLElement.prototype;
    const orig = Object.getOwnPropertyDescriptor(proto, 'clientWidth');
    Object.defineProperty(proto, 'clientWidth', { configurable: true, get: () => 700 });
    try {
      const trace = sixCattleTrace();
      const g = buildTraceGraph(trace);
      const cols = Math.max(...g.nodes.map((n) => n.column)) + 1;
      // 整图适应缩放会远低于 0.5, 才说明这条用例真的在守下限
      expect((700 - 24) / (cols * 240 + (cols - 1) * 40)).toBeLessThan(0.5);
      const w = await mountDag(trace);
      const vp = useVueFlow('trace-dag-flow').viewport.value;
      expect(vp.zoom).toBeGreaterThanOrEqual(0.5);
      expect(vp.zoom * DAG_FONT.line).toBeGreaterThanOrEqual(11);
      expect(vp.x).toBe(12);
      // 手动缩到很小, 再点「适应」: 必须回到 ≥ 下限且在起点, 而不是整图缩到不可读
      const flow = useVueFlow('trace-dag-flow');
      await flow.setViewport({ x: 300, y: 5, zoom: 1.1 });
      await w.get('[data-testid="trace-dag-fit"]').trigger('click');
      await nextTick();
      await new Promise((r) => setTimeout(r, 30));
      const after = flow.viewport.value;
      expect(after.zoom).toBeGreaterThanOrEqual(DAG_MIN_ZOOM);
      expect(after.zoom).toBeGreaterThanOrEqual(0.5);
      expect(after.x).toBe(12);
      w.unmount();
    } finally {
      if (orig) Object.defineProperty(proto, 'clientWidth', orig);
      else delete (proto as unknown as Record<string, unknown>).clientWidth;
    }
  });
});
