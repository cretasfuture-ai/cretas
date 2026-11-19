/**
 * 成品出厂核算（M67）两张桑基图 → 一图一分析输入（问题 40，FLOW 族）。
 *
 * - 批次溯源图（renderSankey）：连线宽度 = 各道工序的投入量。环节 = 各工序投入，最后再接末道产出；
 *   只和前面最近的同单位环节比（kg 和 袋 不换算），点出降得最多的一步。
 * - 多批混锅图（renderMix）：上游各批投料 → 熟制 → 成品。投料合计只在单位一致时成立；
 *   成品产出没报工就照实说缺，⛔ 不拿投料量顶替。
 * 都是重量 / 数量，不涉及金额。
 */
import type { ChartWithMeta, FlowStage } from '@/views/smart-bi/components/chartInsight';
import { canonicalUnitCodeKeepingCount, displayUnit } from '@/utils/unitPricing';

export interface FlowStepGroup {
  processName: string;
  totalInput: number;
  totalOutput: number;
  inputUnit?: string;
  outputUnit?: string;
}

export interface FlowMixSource {
  name: string;
  quantity?: number | null;
  unit?: string;
}

const unitLabel = (u?: string | null) => (u ? displayUnit(u) || u : undefined);
const unitKey = (u?: string | null) => (u ? canonicalUnitCodeKeepingCount(u) || u.trim().toLowerCase() : '');

function stage(label: string, value: number | null | undefined, unit?: string, missing?: string): FlowStage {
  const ok = value != null && Number.isFinite(Number(value));
  return {
    label,
    value: ok ? Number(value) : null,
    unit: unitLabel(unit),
    compareKey: unitKey(unit),
    missing: ok ? undefined : missing,
  };
}

/** 工序名常带同一个「产品·」前缀（如「演示·藤椒掌中宝·熟制杀菌」），分析里去掉共同前缀，只留工序名。 */
export function shortStepNames(names: string[]): string[] {
  if (names.length < 2) return names;
  let prefix = names[0];
  for (const n of names) while (prefix && !n.startsWith(prefix)) prefix = prefix.slice(0, -1);
  const cut = prefix.lastIndexOf('·') + 1;
  return cut > 0 && names.every((n) => n.length > cut) ? names.map((n) => n.slice(cut)) : names;
}

export function yieldSankeyInsightChart(steps: FlowStepGroup[]): ChartWithMeta | null {
  if (!steps.length) return null;
  const names = shortStepNames(steps.map((s) => s.processName));
  // 环节 = 各道工序的投入量（与图上连线宽度同一个数），最后接末道产出
  const stages: FlowStage[] = steps.map((s, i) => stage(names[i], s.totalInput, s.inputUnit, '没报投入量'));
  const last = steps[steps.length - 1];
  stages.push(stage(`${names[names.length - 1]}产出`, last.totalOutput, last.outputUnit, '末道还没报产出'));
  return {
    chartType: 'sankey',
    title: '批次溯源',
    meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
    flow: { kind: 'chain', stages, note: '各道按投入量计，与图上连线宽度一致' },
  };
}

export function mixSankeyInsightChart(
  sources: FlowMixSource[],
  output: number | null | undefined,
  outputUnit?: string,
): ChartWithMeta | null {
  if (sources.length < 2) return null;
  const withQty = sources.filter((s) => s.quantity != null && Number.isFinite(Number(s.quantity)));
  const keys = new Set(withQty.map((s) => unitKey(s.unit)));
  const notes: string[] = [];
  let input: FlowStage;
  if (withQty.length < sources.length) {
    input = { label: '上游投料合计', value: null, missing: `${sources.length - withQty.length}个上游批次没有投料量` };
  } else if (keys.size !== 1) {
    input = { label: '上游投料合计', value: null, missing: '上游批次单位不一致，没法相加' };
  } else {
    const total = withQty.reduce((s, r) => s + Number(r.quantity), 0);
    input = stage(`${sources.length}个上游批次投料`, total, withQty[0].unit);
    const sorted = [...withQty].sort((a, b) => Number(b.quantity) - Number(a.quantity));
    const [top, second] = sorted;
    if (Number(second.quantity) > 0) {
      const ratio = Number(top.quantity) / Number(second.quantity);
      notes.push(`投料最多的是${top.name}，${Number(top.quantity)}${unitLabel(top.unit) ?? ''}，是第二多的${second.name}的${ratio.toFixed(1)}倍`);
    }
  }
  return {
    chartType: 'sankey',
    title: '多批混锅溯源',
    meta: { xDim: 'category', yMetric: 'quantity', aggregation: 'sum', domain: 'factory' },
    flow: {
      kind: 'chain',
      stages: [input, stage('成品产出', output, outputUnit, '还没报末道产出，图上不画成品这一段')],
      note: notes.length ? notes.join('；') : undefined,
    },
  };
}
