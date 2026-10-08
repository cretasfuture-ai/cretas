import type { ProductProcessWorkflowDefinition } from './types';

export interface WorkflowAiChangeRow { path: string; text: string }
export type WorkflowAiPreview =
  | { ok: true; rows: WorkflowAiChangeRow[]; revision: string; warnings?: string[] }
  | { ok: false; message: string };

const labels: Record<string, string> = {
  rawMaterials: '入口原料', process: '名称', processCategory: '类别',
  responsibleWorkerId: '负责人 ID', responsibleWorkerName: '负责人',
  outputs: '产出', kind: '类型', name: '名称', unit: '单位', byproduct: '是否副产',
  packagingBindings: '包材配置', reportingRequired: '是否报工',
  skuCode: 'SKU 编码', skuId: 'SKU', bound: '绑定状态', specification: '规格',
  reportingSelectionMode: '报工模式', allowFinishedGoodsSource: '允许成品投入',
  allowMultipleUpstreamSources: '允许多投入', conversionRule: '单位换算规则',
  workProcessId: '工序目录', key: '节点', processName: '工序名称', inputUnit: '投入单位', outputUnit: '产出单位',
  ports: '端口', direction: '方向', materialNodeId: '物料节点', materialName: '物料名称', materialKind: '物料类型', ordinal: '顺序',
  portGroups: '端口组', label: '标签', mode: '模式', minSelections: '最少选择', maxSelections: '最多选择', materialBindings: '辅料配置',
  materialTypeId: '物料档案', standardQuantity: '标准用量', baseUnit: '基础单位', isByproduct: '是否副产', isRework: '是否回料', isDefective: '是否不良品',
  reworkPolicy: '回料策略', sources: '回料来源', inputRatioPercent: '投入比例', expression: '换算表达式',
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

/**
 * Diff the exact semantic canvas definitions produced before and after the
 * shared Workflow compiler. Runtime ids and layout coordinates are generated
 * values, so they are normalized while every business field, node and edge is
 * retained. This is the confirmation-card source of truth for mounted AI use.
 */
export function previewWorkflowAiDefinitions(
  before: ProductProcessWorkflowDefinition,
  after: ProductProcessWorkflowDefinition,
): WorkflowAiPreview {
  const nodeSortKey = (definition: ProductProcessWorkflowDefinition, id: string): string => {
    const node = definition.nodes.find(item => item.id === id);
    if (!node) return '';
    const data = node.data as Record<string, unknown>;
    return `${node.kind}:${String(data.processName ?? data.name ?? '')}:${String(data.ordinal ?? '')}`;
  };
  const topoOrder = (definition: ProductProcessWorkflowDefinition): string[] => {
    const incoming = new Map(definition.nodes.map(node => [node.id, 0]));
    definition.edges.forEach(edge => incoming.set(edge.target, (incoming.get(edge.target) || 0) + 1));
    const ready = definition.nodes.filter(node => !incoming.get(node.id)).map(node => node.id)
      .sort((a, b) => nodeSortKey(definition, a).localeCompare(nodeSortKey(definition, b)));
    const order: string[] = [];
    while (ready.length) {
      const id = ready.shift()!; order.push(id);
      definition.edges.filter(edge => edge.source === id).sort((a, b) => `${nodeSortKey(definition, a.target)}:${String(a.sourceHandle || '')}`.localeCompare(`${nodeSortKey(definition, b.target)}:${String(b.sourceHandle || '')}`)).forEach(edge => {
        const next = (incoming.get(edge.target) || 0) - 1; incoming.set(edge.target, next); if (!next) ready.push(edge.target);
      });
      ready.sort((a, b) => nodeSortKey(definition, a).localeCompare(nodeSortKey(definition, b)));
    }
    definition.nodes.forEach(node => { if (!order.includes(node.id)) order.push(node.id); });
    return order;
  };
  const normalizeData = (
    value: unknown,
    nodeIndex: Map<string, string>,
    key?: string,
  ): unknown => {
    if (Array.isArray(value)) {
      if (key === 'portIds') return value.map((item, index) => typeof item === 'string' && item.startsWith('port:') ? item : `port-${index}`);
      return value.map(item => normalizeData(item, nodeIndex, key));
    }
    if (!record(value)) return value;
    const result: Record<string, unknown> = {};
    Object.keys(value).sort().forEach(field => {
      // Port and policy ids are compiler generated; node references are mapped
      // to semantic node keys so regenerated UUIDs never become user rows.
      if (field === 'id' && (key === 'ports' || key === 'portGroups')) return;
      if ((field === 'materialNodeId' || field === 'substituteOfNodeId' || field === 'reworkNodeId') && typeof value[field] === 'string') {
        result[field] = nodeIndex.get(value[field] as string) || value[field];
        return;
      }
      result[field] = normalizeData(value[field], nodeIndex, field);
    });
    return result;
  };
  const normalize = (definition: ProductProcessWorkflowDefinition): Record<string, unknown> => {
    const occurrences = new Map<string, number>();
    const keys = new Map<string, string>();
    const rank = new Map(topoOrder(definition).map((id, index) => [id, index]));
    [...definition.nodes].sort((a, b) => (rank.get(a.id) || 0) - (rank.get(b.id) || 0) || a.id.localeCompare(b.id)).forEach(node => {
      const data = node.data as Record<string, unknown>;
      const name = node.kind === 'PROCESS' ? data.processName : data.name;
      const base = `${node.kind}:${String(name ?? '')}`;
      const ordinal = occurrences.get(base) || 0;
      occurrences.set(base, ordinal + 1);
      keys.set(node.id, `${base}#${ordinal}`);
    });
    const nodeIndex = new Map([...keys.entries()]);
    const handles = new Map<string, string>();
    definition.nodes.filter(node => node.kind === 'PROCESS').forEach(node => {
      const ports = Array.isArray((node.data as Record<string, unknown>).ports)
        ? (node.data as Record<string, unknown>).ports as Record<string, unknown>[] : [];
      ports.forEach((port, index) => handles.set(`${node.id}:${String(port.id)}`, `port:${port.direction}:${nodeIndex.get(String(port.materialNodeId)) || ''}:${port.ordinal ?? index}`));
    });
    const nodes = definition.nodes.map(node => ({
      key: keys.get(node.id), kind: node.kind,
      // Preserve stable catalog ids as business data. Only the explicit
      // preview placeholder is normalized; apply maps it to the real id.
      data: normalizeData((() => {
        const data = { ...(node.data as Record<string, unknown>) };
        if (node.kind === 'PROCESS') {
          if (!owns(data, 'reportingRequired')) data.reportingRequired = false;
          // A pending catalog item becomes a real id during apply. For a newly
          // added semantic process, compare the reviewed plan by process name;
          // preserve real ids for existing processes so same-name rebindings remain visible.
          const pendingNames = new Set(before.nodes.filter(item => item.kind === 'PROCESS'
            && String((item.data as Record<string, unknown>).workProcessId || '').startsWith('__pending__:'))
            .map(item => String((item.data as Record<string, unknown>).processName || '')));
          if (String(data.workProcessId || '').startsWith('__pending__:')
            || (definition === after && pendingNames.has(String(data.processName || '')))) {
            data.workProcessId = '__pending__';
          }
          const ports = Array.isArray(data.ports) ? data.ports as Record<string, unknown>[] : [];
          const portKeys = new Map(ports.map((port, index) => [String(port.id), `port:${port.direction}:${nodeIndex.get(String(port.materialNodeId)) || ''}:${port.ordinal ?? index}`]));
          data.ports = ports.map(port => ({ ...port, id: portKeys.get(String(port.id)) || `port:${port.direction}:${port.ordinal ?? 0}` }));
          if (Array.isArray(data.portGroups)) {
            data.portGroups = (data.portGroups as Record<string, unknown>[]).map(group => ({
              ...group,
              portIds: Array.isArray(group.portIds)
                ? (group.portIds as unknown[]).map(id => portKeys.get(String(id)) || String(id))
                : group.portIds,
            }));
          }
        }
        return data;
      })(), nodeIndex),
    })).sort((a, b) => String(a.key).localeCompare(String(b.key)));
    const nodeMap = Object.fromEntries(nodes.map(node => [String(node.key), { kind: node.kind, ...(node.data as Record<string, unknown>) }]));
    return {
      ...Object.fromEntries(Object.entries(definition).filter(([key]) => !['nodes', 'edges', 'viewport'].includes(key))),
      nodes: nodeMap,
      edges: definition.edges.map(edge => ({
        source: keys.get(edge.source),
        sourceHandle: handles.get(`${edge.source}:${edge.sourceHandle}`) || edge.sourceHandle,
        target: keys.get(edge.target),
        targetHandle: handles.get(`${edge.target}:${edge.targetHandle}`) || edge.targetHandle,
      })).sort((a, b) => workflowAiStableJson(a).localeCompare(workflowAiStableJson(b))),
    };
  };
  const beforeValue = normalize(before);
  const afterValue = normalize(after);
  const rows: WorkflowAiChangeRow[] = [];
  const add = (path: string, label: string, from: unknown, to: unknown): void => {
    if (path.endsWith('.key')) return;
    if (workflowAiStableJson(from) === workflowAiStableJson(to)) return;
    if (path.endsWith('.packagingBindings')) {
      const packaging = (value: unknown) => Array.isArray(value) && value.length
        ? value.map(item => record(item) ? `${String(item.materialName || item.name || '包材')} ${item.standardQuantity ?? ''}${item.unit || ''}`.trim() : display(item)).join('、')
        : display(value);
      const bindings = (value: unknown) => Array.isArray(value) ? value.map(item => record(item) ? String(item.materialTypeId || item.skuId || '') : '').join('|') : '';
      const switched = bindings(from) !== bindings(to) && bindings(from) !== '' && bindings(to) !== '';
      rows.push({ path, text: `${label}：${packaging(from)} → ${packaging(to)}${switched ? '（档案绑定已切换）' : ''}` });
      return;
    }
    if (record(from) || record(to)) {
      const a = record(from) ? from : {};
      const b = record(to) ? to : {};
      [...new Set([...Object.keys(a), ...Object.keys(b)])]
        .forEach(key => {
          const nodeLabel = path === 'canvas.nodes'
            ? (() => {
              const parts = key.split(':');
              const kind = parts[0] === 'PROCESS' ? '工序' : parts[0] === 'RAW_MATERIAL' ? '原料' : parts[0] === 'SEMI_FINISHED' ? '半成品' : parts[0] === 'FINISHED_GOOD' ? '成品' : '节点';
              const payload = (record(a[key]) ? a[key] : b[key]) as Record<string, unknown>;
              const name = String(payload?.processName || payload?.name || '');
              return `${kind}${name ? `「${name}」` : ''}`;
            })()
            : `${label} · ${labels[key] || key}`;
          add(`${path}.${key}`, nodeLabel, a[key], b[key]);
        });
      return;
    }
    if (Array.isArray(from) || Array.isArray(to)) {
      const a = Array.isArray(from) ? from : [];
      const b = Array.isArray(to) ? to : [];
      if (path === 'canvas.edges') {
        const keyOf = (edge: unknown) => workflowAiStableJson(edge);
        const beforeEdges = new Set(a.map(keyOf));
        const afterEdges = new Set(b.map(keyOf));
        const edgeText = (edge: unknown, verb: string) => {
          const item = record(edge) ? edge : {};
          const endpoint = (value: unknown) => String(value || '未绑定').replace(/^PROCESS:/, '工序').replace(/^RAW_MATERIAL:/, '原料').replace(/^SEMI_FINISHED:/, '半成品').replace(/^FINISHED_GOOD:/, '成品').replace(/#\d+$/, '');
          const handle = item.sourceHandle || item.targetHandle;
          return `${verb}连接：${endpoint(item.source)} → ${endpoint(item.target)}${handle ? `（端口${String(handle).replace(/^port:[^:]*:/, '')}）` : ''}`;
        };
        a.filter(edge => !afterEdges.has(keyOf(edge))).forEach((edge, index) => rows.push({ path: `canvas.edges.removed[${index}]`, text: edgeText(edge, '删除') }));
        b.filter(edge => !beforeEdges.has(keyOf(edge))).forEach((edge, index) => rows.push({ path: `canvas.edges.added[${index}]`, text: edgeText(edge, '新增') }));
        return;
      }
      for (let i = 0; i < Math.max(a.length, b.length); i += 1) add(`${path}[${i}]`, `${label} ${i + 1}`, a[i], b[i]);
      return;
    }
    const shown = path.endsWith('.workProcessId') || path.endsWith('.skuId') || path.endsWith('.materialTypeId')
      ? (value: unknown) => value === undefined || value === null || value === '' ? '未绑定档案' : String(from) !== String(to) && value === from ? '原档案（档案绑定已切换）' : String(from) !== String(to) && value === to ? '新档案（档案绑定已切换）' : String(value)
      : path.endsWith('.kind')
      ? displayKind
      : path.endsWith('.materialNodeId') || path.endsWith('.substituteOfNodeId') || path.endsWith('.reworkNodeId')
      ? (value: unknown) => displayReference(value)
      : label.includes('是否报工')
      ? (value: unknown) => value === true ? '报工' : value === false ? '免报' : display(value)
      : display;
    rows.push({ path, text: `${label}：${shown(from)} → ${shown(to)}` });
  };
  add('canvas', '画布', beforeValue, afterValue);
  // One revision covers both sides: the current canvas and the semantic plan.
  // This lets the panel detect a changed canvas while the editor can also
  // detect catalog materialization drift without comparing formatted rows.
  return { ok: true, rows, revision: workflowAiStableJson({ before: beforeValue, after: afterValue }) };
}

function display(value: unknown): string {
  if (value === undefined) return '未设置';
  if (value === null) return 'null';
  if (value === '') return '空字符串';
  if (value === true) return '是';
  if (value === false) return '否';
  return typeof value === 'string' ? value : workflowAiStableJson(value);
}

function displayKind(value: unknown): string {
  return ({ RAW_MATERIAL: '原料', PROCESS: '工序', SEMI_FINISHED: '半成品', FINISHED_GOOD: '成品' } as Record<string, string>)[String(value)] || display(value);
}

function displayReference(value: unknown): string {
  if (value === undefined || value === null || value === '') return '未绑定节点';
  const raw = String(value);
  const match = raw.match(/^(RAW_MATERIAL|PROCESS|SEMI_FINISHED|FINISHED_GOOD):(.+?)(?:#\d+)?$/);
  if (!match) return raw.startsWith('uuid') || raw.length > 24 ? '档案绑定已切换' : raw;
  const kind = displayKind(match[1]);
  return `${kind}「${match[2]}」`;
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
    // Packaging is one user-visible binding. Keep it as one confirmation row;
    // expanding its implementation fields would make the card claim several
    // independent edits although the generator either preserves or drops the
    // binding as a single terminal-output property.
    if (path.endsWith('.packagingBindings')) {
      add(path, `${label}：${display(from)} → ${display(to)}`);
      return;
    }
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
  const compilerDefaultsReporting = processes.some(node => owns(node.data as Record<string, unknown>, 'reportingRequired'));
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
        ...(material && owns(material.data as Record<string, unknown>, 'packagingBindings')
          ? { packagingBindings: material.data.packagingBindings }
          : {}),
      };
    });
    const inputs = ports.filter(port => port.direction === 'INPUT')
      .slice(1) // The primary chain input is represented by rawMaterials / the preceding step.
      .map(port => nodes.get(String(port.materialNodeId)))
      .filter(node => node?.kind === 'RAW_MATERIAL')
      .map(node => node!.data.name);
    const step: Record<string, unknown> = { process: data.processName, outputs, inputs };
    if (data.processCategory != null) step.processCategory = data.processCategory;
    // Keep the loaded value in the old projection. The compiler creates every
    // generated process branch with reportingRequired=true; the projected spec
    // below supplies that implicit default when the AI omitted the field.
    if (owns(data, 'reportingRequired')) step.reportingRequired = data.reportingRequired;
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
      const projectedStep = compilerDefaultsReporting
        ? {
          ...step,
          ...(owns(step, 'inputs') ? {} : { inputs: [] }),
          ...(!owns(step, 'reportingRequired')
            ? { reportingRequired: true }
            : {}),
        }
        : step;
      diff(`steps[${index}]`, label, oldIndex < 0 ? undefined : oldSteps[oldIndex], projectedStep);
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
