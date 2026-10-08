import type { ProductProcessWorkflowDefinition } from './types';

export interface WorkflowAiChangeRow { path: string; text: string }
export type WorkflowAiPreview =
  | { ok: true; rows: WorkflowAiChangeRow[]; revision: string }
  | { ok: false; message: string };

const labels: Record<string, string> = {
  rawMaterials: '入口原料', process: '名称', processCategory: '类别',
  responsibleWorkerId: '负责人 ID', responsibleWorkerName: '负责人',
  outputs: '产出', kind: '类型 kind', name: '名称', unit: '单位', byproduct: '是否副产',
  inputs: '额外原料投入', seasonings: '辅料 / 调料投入', dosagePerKgG: '每 kg 投入克数',
  subsequentPotRatio: '后续锅比例', injectionAmount: '注射量',
};
const owns = (value: Record<string, unknown>, key: string) => Object.prototype.hasOwnProperty.call(value, key);
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);

/** Stable comparison preserves unknown JSON keys and distinguishes absent, null, false and zero. */
export function workflowAiStableJson(value: unknown): string {
  if (value === undefined) return 'undefined';
  if (Array.isArray(value)) return `[${value.map(workflowAiStableJson).join(',')}]`;
  if (record(value)) return `{${Object.keys(value).sort().map(key => `${JSON.stringify(key)}:${workflowAiStableJson(value[key])}`).join(',')}}`;
  return JSON.stringify(value);
}

function display(value: unknown): string {
  if (value === undefined) return '未设置';
  if (value === null) return 'null';
  if (value === '') return '空字符串';
  if (value === true) return '是';
  if (value === false) return '否';
  return typeof value === 'string' ? value : workflowAiStableJson(value);
}

/** Pure projection: never resolves/creates catalog entries or hydrates the canvas. */
export function previewWorkflowAiSpec(
  before: ProductProcessWorkflowDefinition,
  spec: unknown,
): WorkflowAiPreview {
  if (!record(spec) || !Array.isArray(spec.steps) || !spec.steps.length
    || spec.steps.some(step => !record(step) || typeof step.process !== 'string' || !step.process.trim())) {
    return { ok: false, message: 'AI 规格里没有合法的工序步骤，画布保持原样。' };
  }
  const rows: WorkflowAiChangeRow[] = [];
  const add = (path: string, text: string) => rows.push({ path, text });
  const diff = (path: string, label: string, from: unknown, to: unknown): void => {
    if (workflowAiStableJson(from) === workflowAiStableJson(to)) return;
    if (record(from) || record(to)) {
      const a = record(from) ? from : {};
      const b = record(to) ? to : {};
      const keys = [...new Set([...Object.keys(a), ...Object.keys(b)])];
      if (keys.length && (from === undefined || record(from)) && (to === undefined || record(to))) {
        keys.forEach(key => diff(`${path}.${key}`, `${label} · ${labels[key] || key}`, a[key], b[key]));
        return;
      }
    }
    if ((Array.isArray(from) || from === undefined) && (Array.isArray(to) || to === undefined)) {
      const a = Array.isArray(from) ? from : [];
      const b = Array.isArray(to) ? to : [];
      if (Math.max(a.length, b.length)) {
        for (let i = 0; i < Math.max(a.length, b.length); i += 1) diff(`${path}[${i}]`, `${label} ${i + 1}`, a[i], b[i]);
        return;
      }
    }
    add(path, `${label}：${display(from)} → ${display(to)}`);
  };

  // A spec replaces the whole canvas. Use graph topology, not visual x/y, for current order.
  const nodes = new Map(before.nodes.map(node => [node.id, node]));
  const indegree = new Map(before.nodes.map(node => [node.id, 0]));
  before.edges.forEach(edge => indegree.set(edge.target, (indegree.get(edge.target) || 0) + 1));
  const queue = before.nodes.filter(node => !indegree.get(node.id)).map(node => node.id);
  const ordered: typeof before.nodes = [];
  while (queue.length) {
    const id = queue.shift()!;
    const node = nodes.get(id);
    if (node) ordered.push(node);
    before.edges.filter(edge => edge.source === id).forEach(edge => {
      const degree = (indegree.get(edge.target) || 0) - 1;
      indegree.set(edge.target, degree);
      if (!degree) queue.push(edge.target);
    });
  }
  before.nodes.filter(node => !ordered.includes(node)).forEach(node => ordered.push(node));
  const processes = ordered.filter(node => node.kind === 'PROCESS');
  const oldSteps = processes.map(node => {
    const data = node.data as Record<string, unknown>;
    const ports = Array.isArray(data.ports) ? data.ports as Record<string, unknown>[] : [];
    const outputs = ports.filter(port => port.direction === 'OUTPUT').map(port => {
      const material = nodes.get(String(port.materialNodeId));
      return {
        kind: material?.kind ?? port.materialKind,
        name: material?.data.name ?? port.materialName,
        unit: material?.data.baseUnit ?? port.unit,
        byproduct: material?.data.isByproduct ?? false,
      };
    });
    const inputs = ports.filter(port => port.direction === 'INPUT')
      .slice(1) // The primary chain input is represented by rawMaterials / the preceding step.
      .map(port => nodes.get(String(port.materialNodeId)))
      .filter(node => node?.kind === 'RAW_MATERIAL')
      .map(node => node!.data.name);
    const step: Record<string, unknown> = { process: data.processName, outputs, inputs };
    if (data.processCategory != null) step.processCategory = data.processCategory;
    ['responsibleWorkerId', 'responsibleWorkerName', 'injectionAmount'].forEach(key => {
      if (owns(data, key)) step[key] = data[key];
    });
    if (Array.isArray(data.materialBindings)) {
      step.seasonings = data.materialBindings.map(binding => {
        const row = binding as Record<string, unknown>;
        return { name: row.materialName, dosagePerKgG: row.dosagePerKgG,
          ...(owns(row, 'subsequentPotRatio') ? { subsequentPotRatio: row.subsequentPotRatio } : {}) };
      });
    }
    return step;
  });
  const oldRaw = ordered.filter(node => node.kind === 'RAW_MATERIAL' && !before.edges.some(edge => edge.target === node.id))
    .map(node => node.data.name);
  diff('rawMaterials', '入口原料', oldRaw, spec.rawMaterials);

  const used = new Set<number>();
  spec.steps.forEach((step: Record<string, unknown>, index: number) => {
    const oldIndex = oldSteps.findIndex((old, i) => !used.has(i) && old.process === step.process);
    const label = `工序 ${index + 1}「${String(step.process)}」`;
    if (oldIndex < 0) add(`steps[${index}].add`, `新增${label}`);
    else {
      used.add(oldIndex);
      if (oldIndex !== index) add(`steps[${index}].order`, `${label}顺序：${oldIndex + 1} → ${index + 1}`);
    }
    diff(`steps[${index}]`, label, oldIndex < 0 ? undefined : oldSteps[oldIndex], step);
  });
  oldSteps.forEach((step, index) => {
    if (!used.has(index)) {
      add(`removed[${index}]`, `删除工序 ${index + 1}「${String(step.process)}」`);
      diff(`removed[${index}].fields`, `删除工序「${String(step.process)}」`, step, undefined);
    }
  });
  Object.keys(spec).filter(key => key !== 'steps' && key !== 'rawMaterials')
    .forEach(key => diff(key, labels[key] || key, undefined, spec[key]));
  return { ok: true, rows, revision: workflowAiStableJson(before) };
}
