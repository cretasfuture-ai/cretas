/**
 * BP-38: AI 改 Workflow 时「没说的不动」。
 *
 * 事故: 「检查投入与产出单位、数量换算是否完整」生成了 253 项改动, 大部分是把现有配置清空
 * (工序「卤制」投入单位 kg → 未设置, 辅料八角 2 g/kg → 未设置, 端口物料节点 → 未绑定)。
 * 成因: 模型只交回一份残缺的语义规格, 编译器按它**从零重建整张画布**, 规格里没写的字段
 * 就都成了空值。
 *
 * 这里两层, 都是纯函数(不碰画布、不发请求):
 * 1. fillOmittedSpecFields —— 规格层: 模型保留了某道工序、却没写它的额外投入/产出/入口原料时,
 *    用当前画布的值补上, 免得编译器把那些物料节点和端口整个删掉。
 * 2. preserveExistingWorkflowValues —— 定义层: 编译出的新定义里, 凡是能和旧节点/旧端口对上号、
 *    旧值非空而新值为空的字段, 一律保留旧值。只补「空」, 不覆盖模型给出的非空新值。
 *
 * 结构性删除(整道工序/物料节点/连接没了)补不回来 —— 那些在确认卡片里标红, 必须逐项勾选。
 */
import type { ProductProcessWorkflowDefinition, ProductProcessWorkflowNode } from './types';
import { isEmptyWorkflowValue, projectWorkflowAsSpec } from './workflowAiPreview';

type Json = Record<string, unknown>;
const record = (value: unknown): value is Json => !!value && typeof value === 'object' && !Array.isArray(value);
const owns = (value: Json, key: string) => Object.prototype.hasOwnProperty.call(value, key);
const norm = (value: unknown) => String(value ?? '').trim().replace(/\s+/g, '').toLowerCase();
const clone = <T>(value: T): T => JSON.parse(JSON.stringify(value)) as T;

/** 缺省 = 没写这个键或写了 null。显式 [] 视为模型的意思, 交给标红确认去拦。 */
const omitted = (step: Json, key: string) => !owns(step, key) || step[key] === null;

export function fillOmittedSpecFields<T>(before: ProductProcessWorkflowDefinition, spec: T): T {
  if (!record(spec) || !Array.isArray(spec.steps)) return spec;
  const current = projectWorkflowAsSpec(before);
  if (!current.steps.length) return spec;
  const used = new Set<number>();
  const steps = spec.steps.map((step: unknown) => {
    if (!record(step)) return step;
    const index = current.steps.findIndex((old, i) => !used.has(i) && norm(old.process) === norm(step.process));
    if (index < 0) return step;
    used.add(index);
    const old = current.steps[index];
    const merged: Json = { ...step };
    if (omitted(step, 'inputs') && Array.isArray(old.inputs) && old.inputs.length) merged.inputs = clone(old.inputs);
    if ((omitted(step, 'outputs') || (Array.isArray(step.outputs) && step.outputs.length === 0))
      && Array.isArray(old.outputs) && old.outputs.length) {
      merged.outputs = clone(old.outputs);
    } else if (Array.isArray(step.outputs) && Array.isArray(old.outputs)) {
      // 同名产出缺单位/类型时沿用现有值
      merged.outputs = step.outputs.map((output: unknown) => {
        if (!record(output)) return output;
        const match = (old.outputs as unknown[]).find(item => record(item) && norm(item.name) === norm(output.name));
        if (!record(match)) return output;
        const filled: Json = { ...output };
        (['unit', 'kind'] as const).forEach((key) => {
          if (isEmptyWorkflowValue(filled[key]) && !isEmptyWorkflowValue(match[key])) filled[key] = match[key];
        });
        return filled;
      });
    }
    return merged;
  });
  const merged: Json = { ...spec, steps };
  if (omitted(spec, 'rawMaterials') && current.rawMaterials.length) merged.rawMaterials = clone(current.rawMaterials);
  return merged as T;
}

const NODE_REF_KEYS = new Set(['materialNodeId', 'substituteOfNodeId', 'reworkNodeId']);
const MATERIAL_BINDING_KEYS = ['skuId', 'skuCode', 'bound', 'specification', 'baseUnit'] as const;

export interface PreserveResult {
  definition: ProductProcessWorkflowDefinition;
  /** 被保留下来的现有字段数(新定义里本会变成空值的那些)。 */
  preserved: number;
}

/**
 * 把旧定义里非空、新定义里变空的字段补回去。新定义不被修改, 返回补过的副本。
 */
export function preserveExistingWorkflowValues(
  before: ProductProcessWorkflowDefinition,
  after: ProductProcessWorkflowDefinition,
): PreserveResult {
  const next = clone(after);
  const nodeMap = pairNodes(before.nodes, next.nodes);
  const portMap = new Map<string, string>();
  let preserved = 0;

  /** 引用旧节点/旧端口的值换成新 id; 有一处换不了就返回 undefined (不留悬空引用)。 */
  const remap = (value: unknown, key?: string): unknown => {
    if (key && NODE_REF_KEYS.has(key) && typeof value === 'string') return nodeMap.get(value);
    if (key === 'portIds' && Array.isArray(value)) {
      const mapped = value.map(id => portMap.get(String(id)));
      return mapped.every(Boolean) ? mapped : undefined;
    }
    if (Array.isArray(value)) {
      const mapped = value.map(item => remap(item));
      return mapped.some(item => item === undefined) ? undefined : mapped;
    }
    if (record(value)) {
      const out: Json = {};
      for (const [field, inner] of Object.entries(value)) {
        const mapped = remap(inner, field);
        if (mapped === undefined && inner !== undefined) return undefined;
        out[field] = mapped;
      }
      return out;
    }
    return value;
  };

  const fill = (oldData: Json, newData: Json, skip: ReadonlySet<string> = new Set()): void => {
    for (const key of Object.keys(oldData)) {
      if (skip.has(key) || key === 'id') continue;
      const oldValue = oldData[key];
      const newValue = newData[key];
      if (isEmptyWorkflowValue(oldValue)) continue;
      if (isEmptyWorkflowValue(newValue)) {
        const mapped = remap(clone(oldValue), key);
        if (mapped !== undefined) {
          newData[key] = mapped;
          preserved += 1;
        }
      } else if (record(oldValue) && record(newValue) && !NODE_REF_KEYS.has(key)) {
        fill(oldValue, newValue);
      }
    }
  };

  const byNewId = new Map(next.nodes.map(node => [node.id, node]));
  // 先补物料节点(档案绑定成组补, 免得 skuId 回来了而 bound 仍是 false)
  for (const old of before.nodes) {
    const target = byNewId.get(nodeMap.get(old.id) || '');
    if (!target || old.kind === 'PROCESS') continue;
    const oldData = old.data as Json;
    const newData = target.data as Json;
    if (!isEmptyWorkflowValue(oldData.skuId) && isEmptyWorkflowValue(newData.skuId)) {
      MATERIAL_BINDING_KEYS.forEach((key) => {
        if (owns(oldData, key)) newData[key] = clone(oldData[key]);
      });
      preserved += 1;
    }
    fill(oldData, newData);
  }
  // 再补工序: 端口逐个对上号后补字段, 然后补端口组 / 其余字段
  for (const old of before.nodes) {
    const target = byNewId.get(nodeMap.get(old.id) || '');
    if (!target || old.kind !== 'PROCESS') continue;
    const oldData = old.data as Json;
    const newData = target.data as Json;
    const oldPorts = Array.isArray(oldData.ports) ? oldData.ports as Json[] : [];
    const newPorts = Array.isArray(newData.ports) ? newData.ports as Json[] : [];
    const usedNew = new Set<Json>();
    const pairs: Array<[Json, Json]> = [];
    oldPorts.forEach((port) => {
      const mappedMaterial = nodeMap.get(String(port.materialNodeId || ''));
      const match = newPorts.find(candidate => !usedNew.has(candidate)
        && candidate.direction === port.direction
        && !!mappedMaterial && candidate.materialNodeId === mappedMaterial);
      if (match) { usedNew.add(match); pairs.push([port, match]); }
    });
    oldPorts.forEach((port) => {
      if (pairs.some(([oldPort]) => oldPort === port)) return;
      const sameDirectionOld = oldPorts.filter(item => item.direction === port.direction);
      const ordinal = sameDirectionOld.indexOf(port);
      const match = newPorts.filter(item => item.direction === port.direction)[ordinal];
      if (match && !usedNew.has(match)) { usedNew.add(match); pairs.push([port, match]); }
    });
    pairs.forEach(([oldPort, newPort]) => {
      portMap.set(String(oldPort.id), String(newPort.id));
      fill(oldPort, newPort);
    });
    // 端口数组顺序沿用旧画布(只是存储顺序, 业务顺序看 ordinal), 免得确认卡片里冒出整排「端口 2 ↔ 端口 3」的假改动。
    if (pairs.length) {
      const pairedNew = oldPorts.map(port => pairs.find(([oldPort]) => oldPort === port)?.[1]).filter((port): port is Json => !!port);
      newData.ports = [...pairedNew, ...newPorts.filter(port => !pairedNew.includes(port))];
    }
    fill(oldData, newData, new Set(['ports']));
  }
  return { definition: next, preserved };
}

/** 新旧节点配对: 同 id 同类 → 工序按目录 id / 名称唯一匹配 → 物料按「类型+名称」/ SKU 唯一匹配。 */
function pairNodes(
  beforeNodes: ProductProcessWorkflowNode[],
  afterNodes: ProductProcessWorkflowNode[],
): Map<string, string> {
  const result = new Map<string, string>();
  const takenAfter = new Set<string>();
  const afterById = new Map(afterNodes.map(node => [node.id, node]));
  beforeNodes.forEach((node) => {
    const same = afterById.get(node.id);
    if (same && same.kind === node.kind) { result.set(node.id, same.id); takenAfter.add(same.id); }
  });
  const pairUnique = (signature: (node: ProductProcessWorkflowNode) => string) => {
    const group = (nodes: ProductProcessWorkflowNode[], taken: (id: string) => boolean) => {
      const map = new Map<string, string[]>();
      nodes.forEach((node) => {
        if (taken(node.id)) return;
        const key = signature(node);
        if (key) map.set(key, [...(map.get(key) || []), node.id]);
      });
      return map;
    };
    const oldGroups = group(beforeNodes, id => result.has(id));
    const newGroups = group(afterNodes, id => takenAfter.has(id));
    oldGroups.forEach((ids, key) => {
      const targets = newGroups.get(key);
      if (ids.length === 1 && targets?.length === 1) { result.set(ids[0], targets[0]); takenAfter.add(targets[0]); }
    });
  };
  pairUnique((node) => {
    if (node.kind !== 'PROCESS') return '';
    const id = String((node.data as Json).workProcessId || '');
    return id && !id.startsWith('__pending__:') ? `P:id:${id}` : '';
  });
  pairUnique(node => (node.kind === 'PROCESS' && norm((node.data as Json).processName)
    ? `P:name:${norm((node.data as Json).processName)}` : ''));
  pairUnique(node => (node.kind !== 'PROCESS' && norm((node.data as Json).name)
    ? `${node.kind}:name:${norm((node.data as Json).name)}` : ''));
  pairUnique(node => (node.kind !== 'PROCESS' && norm((node.data as Json).skuId)
    ? `${node.kind}:sku:${norm((node.data as Json).skuId)}` : ''));
  return result;
}
