/**
 * 缺口 20: AI 说不清的项标「待确认」, 由人在画布上补齐, 补齐前不能发布。
 * 判据与后端 WorkflowPendingConfirmations.blockers 同形(两边各有测试)。
 * 每条阳性配阴性: 只测「会拦」的闸, 对「拦错了人」完全沉默。
 */
import { describe, expect, it } from 'vitest';
import type { ProductProcessWorkflowNode } from '../types';
import {
  buildCcpPointsFromSpec,
  ccpMissingFields,
  listPendingItems,
  pendingCountOfProcess,
} from '../workflowPendingConfirmations';

function processNode(extra: Record<string, unknown>): ProductProcessWorkflowNode {
  return {
    id: 'process:1',
    kind: 'PROCESS',
    position: { x: 0, y: 0 },
    data: { processName: '卤制', ...extra } as never,
  };
}

let seq = 0;
const nextId = () => `ccp:${++seq}`;

describe('buildCcpPointsFromSpec', () => {
  it('AI 只说了「卤制要测中心温度」: 名字保留, 限值/单位/危害类型全空 = 待确认, ⛔ 不补默认', () => {
    const rejections: string[] = [];
    const points = buildCcpPointsFromSpec('卤制', [{ name: '中心温度' }], nextId, rejections);
    expect(points).toHaveLength(1);
    expect(points[0]).toMatchObject({
      name: '中心温度', hazardType: null, criticalLimitMin: null, criticalLimitMax: null, unit: null,
    });
    expect(ccpMissingFields(points[0])).toEqual(['min', 'max', 'unit', 'hazard']);
    expect(rejections).toEqual([]);
  });

  it('AI 给全了就是齐全的(阴性对照: 不会硬标待确认)', () => {
    const points = buildCcpPointsFromSpec('卤制', [{
      name: '中心温度', hazardType: 'BIOLOGICAL', criticalLimitMin: 75, criticalLimitMax: 95, unit: '℃',
    }], nextId, []);
    expect(ccpMissingFields(points[0])).toEqual([]);
  });

  it('非数字限值 / 不认识的危害类型 / 下限>上限: 不静默采纳, 留作待确认并说出来', () => {
    const rejections: string[] = [];
    const [p] = buildCcpPointsFromSpec('卤制', [{
      name: 'x', hazardType: 'MAGIC', criticalLimitMin: '很高', criticalLimitMax: 10, unit: '℃',
    }], nextId, rejections);
    expect(p.hazardType).toBeNull();
    expect(p.criticalLimitMin).toBeNull();
    expect(rejections.length).toBeGreaterThanOrEqual(2);

    const swapped: string[] = [];
    const [q] = buildCcpPointsFromSpec('卤制', [{
      name: 'y', hazardType: 'PHYSICAL', criticalLimitMin: 95, criticalLimitMax: 75, unit: '℃',
    }], nextId, swapped);
    expect(q.criticalLimitMin).toBeNull();
    expect(q.criticalLimitMax).toBeNull();
    expect(swapped.join()).toContain('下限大于上限');
  });
});

describe('listPendingItems / 补齐前不能发布', () => {
  it('有待确认 CCP ⇒ 逐项列出缺什么', () => {
    const items = listPendingItems([processNode({ ccpPoints: [{ id: 'c', name: '中心温度' }] })]);
    expect(items.map((i) => i.text)).toEqual([
      '工序「卤制」控制点「中心温度」的下限待确认',
      '工序「卤制」控制点「中心温度」的上限待确认',
      '工序「卤制」控制点「中心温度」的单位待确认',
      '工序「卤制」控制点「中心温度」的危害类型待确认',
    ]);
  });

  it('人把限值/单位/危害类型填上 ⇒ 待确认自然消失(同一份数据, 没有第二个开关)', () => {
    const filled = { id: 'c', name: '中心温度', hazardType: 'BIOLOGICAL', criticalLimitMin: 75, criticalLimitMax: 95, unit: '℃' };
    expect(listPendingItems([processNode({ ccpPoints: [filled] })])).toEqual([]);
  });

  it('阴性对照: 0 是真实限值, 不被当成「没填」', () => {
    const zero = { id: 'c', name: '冷冻温度', hazardType: 'BIOLOGICAL', criticalLimitMin: -18, criticalLimitMax: 0, unit: '℃' };
    expect(listPendingItems([processNode({ ccpPoints: [zero] })])).toEqual([]);
  });

  it('说不清的份额 / 回料比例项: 人点「已核对」移除后才放行', () => {
    const pending = [{ code: 'SHARE:p', label: '原料「猪肉」的主料份额待确认（AI 没有给出有效数值）' }];
    expect(listPendingItems([processNode({ pendingConfirmations: pending })])).toHaveLength(1);
    expect(listPendingItems([processNode({ pendingConfirmations: [] })])).toEqual([]);
  });

  it('只统计工序节点: 物料节点上同名字段不会让图变成发不出', () => {
    const material: ProductProcessWorkflowNode = {
      id: 'm', kind: 'RAW_MATERIAL', position: { x: 0, y: 0 },
      data: { name: '牛肉', pendingConfirmations: [{ code: 'x', label: 'y' }] } as never,
    };
    expect(listPendingItems([material])).toEqual([]);
  });

  it('pendingCountOfProcess 与 listPendingItems 同口径', () => {
    const data = { processName: '卤制', ccpPoints: [{ id: 'c', name: 't' }] };
    expect(pendingCountOfProcess(data)).toBe(4);
  });
});

describe('编译器: 一开始就混投的入口原料不丢 (rawMaterials[1..] → 第一道工序额外投入)', async () => {
  const { readFileSync } = await import('node:fs');
  const { resolve } = await import('node:path');
  const EDITOR = readFileSync(resolve(__dirname, '../ProductProcessWorkflowEditor.vue'), 'utf-8');

  it('只认 rawMaterials[0] 会把「牛肉猪肉 3:7」里的猪肉连同份额一起丢掉', () => {
    expect(EDITOR).toMatch(/const entryExtras = \(Array\.isArray\(spec\.rawMaterials\)/);
    expect(EDITOR).toMatch(/steps\[0\] = \{\s*\.\.\.first,\s*inputs: \[/);
  });

  it('份额按投入原料名落到 INPUT 端口 inputShareRatio, 不认识的名字说出来而不是静默', () => {
    expect(EDITOR).toMatch(/\{ \.\.\.p, inputShareRatio: shareByPort\.get\(p\.id\)! \}/);
    expect(EDITOR).toMatch(/不是本工序的投入原料，已忽略/);
  });
});
