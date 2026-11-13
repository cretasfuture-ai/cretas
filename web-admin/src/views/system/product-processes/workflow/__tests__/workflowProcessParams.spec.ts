/**
 * AI 配工艺：用户说出的工艺参数（「油温 150–160℃」）→ 画布工序节点 processParams → 发布时才写进工序标准参数。
 *
 * 考题只跑本地桩：每句话配一份「模型应当吐出的规格 JSON」（桩），不调 Luna / DashScope，不打执行入口。
 * 被测的是确定性的那一半——桩规格进来，哪些落成参数、哪些留作待确认、哪些被拒并说出来。
 * 模型有没有真的把这句话抽成这份规格，要靠真实模型评测，本文件不声称覆盖。
 * 判据与后端 WorkflowPendingConfirmations.processParamBlockers 同形（后端 WorkflowProcessParamMaterializerContractTest 有对应用例）。
 */
import { describe, expect, it } from 'vitest';
import type { ProductProcessWorkflowNode } from '../types';
import {
  buildProcessParamsFromSpec,
  listPendingItems,
  processParamMissing,
} from '../workflowPendingConfirmations';

interface Case {
  sentence: string;
  /** 桩：模型对这句话应吐出的 processParams */
  stub: unknown;
  /** 期望落成的参数（label → 数值/单位）；null = 不应产生任何参数 */
  expect: Array<{ label: string; unit: string | null; min?: number | null; max?: number | null; standardValue?: number | null }> | null;
  /** 期望待确认（true = 说不清，发布前要人补）*/
  pending: boolean;
}

const CASES: Case[] = [
  { sentence: '炒制的时候油温控制在150到160度',
    stub: [{ label: '油温', unit: '℃', min: 150, max: 160 }],
    expect: [{ label: '油温', unit: '℃', min: 150, max: 160 }], pending: false },
  { sentence: '糍粑辣椒炒到155℃左右，上下浮动5度',
    stub: [{ label: '油温', unit: '℃', standardValue: 155, min: 150, max: 160 }],
    expect: [{ label: '油温', unit: '℃', standardValue: 155, min: 150, max: 160 }], pending: false },
  { sentence: '油温别超过160摄氏度',
    stub: [{ label: '油温', unit: '℃', min: null, max: 160 }],
    expect: [{ label: '油温', unit: '℃', min: null, max: 160 }], pending: false },
  { sentence: '炒20分钟',
    stub: [{ label: '炒制时间', unit: 'min', standardValue: 20 }],
    expect: [{ label: '炒制时间', unit: 'min', standardValue: 20 }], pending: false },
  { sentence: '绞碎孔径8mm',
    stub: [{ label: '绞碎孔径', unit: 'mm', standardValue: 8 }],
    expect: [{ label: '绞碎孔径', unit: 'mm', standardValue: 8 }], pending: false },
  { sentence: '豆瓣酱绞碎用6毫米的孔板',
    stub: [{ label: '绞碎孔径', unit: 'mm', standardValue: 6 }],
    expect: [{ label: '绞碎孔径', unit: 'mm', standardValue: 6 }], pending: false },
  { sentence: '水分炒到百分之十二到十五',
    stub: [{ label: '水分', unit: '%', min: 12, max: 15 }],
    expect: [{ label: '水分', unit: '%', min: 12, max: 15 }], pending: false },
  { sentence: '温度一百五到一百六就行（间接表达，没说是油温）',
    stub: [{ label: '温度', unit: '℃', min: 150, max: 160 }],
    expect: [{ label: '温度', unit: '℃', min: 150, max: 160 }], pending: false },
  { sentence: '炒1.5小时',
    stub: [{ label: '炒制时间', unit: '小时', standardValue: 1.5 }],
    expect: [{ label: '炒制时间', unit: '小时', standardValue: 1.5 }], pending: false },
  // ── 说不清：不猜，留作待确认 ──
  { sentence: '油温要热一点',
    stub: [{ label: '油温', unit: null, min: null, max: null, standardValue: null }],
    expect: [{ label: '油温', unit: null, min: null, max: null, standardValue: null }], pending: true },
  { sentence: '火候大概两三分钟',
    stub: [{ label: '炒制时间', unit: null, min: 2, max: 3 }],
    expect: [{ label: '炒制时间', unit: null, min: 2, max: 3 }], pending: true },
  { sentence: '油温160到150（口误，上下限反了）',
    stub: [{ label: '油温', unit: '℃', min: 160, max: 150 }],
    expect: [{ label: '油温', unit: '℃', min: null, max: null, standardValue: null }], pending: true },
  { sentence: '油温很高（模型把形容词当成了数值）',
    stub: [{ label: '油温', unit: '℃', min: '很高', max: null }],
    expect: [{ label: '油温', unit: '℃', min: null, max: null }], pending: true },
  // ── 阴性：这是 CCP / 不是参数，不应产生 processParams ──
  { sentence: '卤制要测中心温度（关键控制点，走 ccps 不走工艺参数）',
    stub: undefined, expect: null, pending: false },
];

let seq = 0;
const nextId = () => `param:${++seq}`;

function nodeWith(params: unknown): ProductProcessWorkflowNode {
  return {
    id: 'process:1', kind: 'PROCESS', position: { x: 0, y: 0 },
    data: { processName: '炒制', ...(params ? { processParams: params } : {}) } as never,
  };
}

describe('工艺参数考题（桩规格 → 节点 processParams → 待确认）', () => {
  it('不少于 10 句，含间接表达与单位变体', () => {
    expect(CASES.length).toBeGreaterThanOrEqual(10);
    const units = new Set(CASES.flatMap((c) => (Array.isArray(c.stub) ? c.stub.map((p: { unit?: string | null }) => p.unit) : [])));
    expect(units.size).toBeGreaterThanOrEqual(4); // ℃ / min / mm / % / 小时
  });

  for (const c of CASES) {
    it(c.sentence, () => {
      const rejections: string[] = [];
      const params = buildProcessParamsFromSpec('炒制', c.stub, nextId, rejections);
      if (c.expect === null) {
        expect(params).toEqual([]);
        expect(listPendingItems([nodeWith(null)])).toEqual([]);
        return;
      }
      expect(params).toHaveLength(c.expect.length);
      c.expect.forEach((want, i) => expect(params[i]).toMatchObject(want));
      const pending = listPendingItems([nodeWith(params)]);
      expect(pending.length > 0).toBe(c.pending);
    });
  }

  it('说不清的数值被拒时要说出来，不静默', () => {
    const rejections: string[] = [];
    buildProcessParamsFromSpec('炒制', [{ label: '油温', unit: '℃', min: '很高' }], nextId, rejections);
    expect(rejections.join('；')).toContain('下限不是数字');
    const reversed: string[] = [];
    buildProcessParamsFromSpec('炒制', [{ label: '油温', unit: '℃', min: 160, max: 150 }], nextId, reversed);
    expect(reversed.join('；')).toContain('下限大于上限');
  });

  it('阳性对照：待确认项补齐后自然消失（结构判据，没有第二个开关）', () => {
    const [p] = buildProcessParamsFromSpec('炒制', [{ label: '油温' }], nextId, []);
    expect(processParamMissing(p)).toEqual(['range', 'unit']);
    expect(listPendingItems([nodeWith([p])]).map((i) => i.text)).toEqual([
      '工序「炒制」工艺参数「油温」的标准值或上下限待确认',
      '工序「炒制」工艺参数「油温」的单位待确认',
    ]);
    expect(listPendingItems([nodeWith([{ ...p, min: 150, max: 160, unit: '℃' }])])).toEqual([]);
  });

  it('同一工序重复的参数名只保留第一条并说出来', () => {
    const rejections: string[] = [];
    const params = buildProcessParamsFromSpec('炒制', [
      { label: '油温', unit: '℃', min: 150, max: 160 },
      { label: '油温', unit: '℃', min: 100, max: 110 },
    ], nextId, rejections);
    expect(params).toHaveLength(1);
    expect(params[0].min).toBe(150);
    expect(rejections.join('')).toContain('重复');
  });
});
