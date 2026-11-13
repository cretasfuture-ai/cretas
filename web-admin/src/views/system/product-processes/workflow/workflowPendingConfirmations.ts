/**
 * AI 配工艺时「说不清、留给人补」的项（缺口 20）。
 *
 * 载体就是画布工序节点自己的 data（和份额 / 回料规则同一份 nodesJson，进 revisionHash）：
 * - `ccpPoints`：工序上的 CCP 控制点。限值 / 单位 / 危害类型为空 = AI 没给 = 待人补。
 *   判据是**结构**（字段是不是空），人把值填上，待确认就自然消失，没有第二个「已确认」开关可以忘记关。
 * - `pendingConfirmations`：其余说不清的项（份额、回料比例…），每项 `{code,label}`，人核对后点「已确认」移除。
 *
 * ⛔ 与后端 `WorkflowPendingConfirmations.blockers` 是同一份规则的两个承载点：后端是真闸，这里只负责
 *    让人**提前看见**并补齐；两边的文案判据改一边必须改另一边（回归测试两边各有）。
 */
import type { ProductProcessWorkflowNode } from './types';

export const CCP_HAZARD_TYPES = ['BIOLOGICAL', 'CHEMICAL', 'PHYSICAL'] as const;
export type CcpHazardType = (typeof CCP_HAZARD_TYPES)[number];

export const CCP_HAZARD_LABELS: Record<CcpHazardType, string> = {
  BIOLOGICAL: '生物性',
  CHEMICAL: '化学性',
  PHYSICAL: '物理性',
};

export interface WorkflowCcpPoint {
  id: string;
  name: string;
  hazardType?: CcpHazardType | null;
  criticalLimitMin?: number | null;
  criticalLimitMax?: number | null;
  unit?: string | null;
  monitoringProcedure?: string | null;
  correctiveAction?: string | null;
}

export interface WorkflowPendingConfirmation {
  code: string;
  label: string;
}

export interface PendingItem {
  nodeId: string;
  processName: string;
  /** 一句人话，和后端 409 的措辞一致。 */
  text: string;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function finite(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value);
}

export function ccpPointsOf(data: Record<string, unknown> | undefined): WorkflowCcpPoint[] {
  const raw = data?.ccpPoints;
  return Array.isArray(raw) ? (raw.filter(isRecord) as unknown as WorkflowCcpPoint[]) : [];
}

export function pendingConfirmationsOf(data: Record<string, unknown> | undefined): WorkflowPendingConfirmation[] {
  const raw = data?.pendingConfirmations;
  if (!Array.isArray(raw)) return [];
  return raw.filter((item): item is WorkflowPendingConfirmation => isRecord(item)
    && typeof item.code === 'string' && typeof item.label === 'string');
}

/** 这个控制点还缺哪几项（空数组 = 齐全）。 */
export function ccpMissingFields(point: WorkflowCcpPoint): Array<'min' | 'max' | 'unit' | 'hazard'> {
  const missing: Array<'min' | 'max' | 'unit' | 'hazard'> = [];
  if (!finite(point.criticalLimitMin)) missing.push('min');
  if (!finite(point.criticalLimitMax)) missing.push('max');
  if (typeof point.unit !== 'string' || !point.unit.trim()) missing.push('unit');
  if (!point.hazardType || !CCP_HAZARD_TYPES.includes(point.hazardType)) missing.push('hazard');
  return missing;
}

export function ccpLimitsContradict(point: WorkflowCcpPoint): boolean {
  return finite(point.criticalLimitMin) && finite(point.criticalLimitMax)
    && point.criticalLimitMin > point.criticalLimitMax;
}

const MISSING_TEXT = { min: '下限', max: '上限', unit: '单位', hazard: '危害类型' } as const;

/** 全图所有阻止发布的待确认项；空 = 可发布。 */
export function listPendingItems(nodes: ProductProcessWorkflowNode[]): PendingItem[] {
  const result: PendingItem[] = [];
  for (const node of nodes) {
    if (node.kind !== 'PROCESS') continue;
    const data = node.data as Record<string, unknown>;
    const processName = typeof data.processName === 'string' && data.processName ? data.processName : node.id;
    pendingConfirmationsOf(data).forEach((item) => {
      result.push({ nodeId: node.id, processName, text: `工序「${processName}」${item.label}` });
    });
    processParamsOf(data).forEach((param, index) => {
      const text = `工艺参数「${param.label || `#${index + 1}`}」`;
      if (processParamMissing(param).includes('range')) {
        result.push({ nodeId: node.id, processName, text: `工序「${processName}」${text}的标准值或上下限待确认` });
      }
      const contradiction = processParamContradiction(param);
      if (contradiction) result.push({ nodeId: node.id, processName, text: `工序「${processName}」${text}的${contradiction}` });
      if (processParamMissing(param).includes('unit')) {
        result.push({ nodeId: node.id, processName, text: `工序「${processName}」${text}的单位待确认` });
      }
    });
    ccpPointsOf(data).forEach((point, index) => {
      const ccp = `控制点「${point.name || `#${index + 1}`}」`;
      ccpMissingFields(point).forEach((field) => {
        result.push({ nodeId: node.id, processName, text: `工序「${processName}」${ccp}的${MISSING_TEXT[field]}待确认` });
      });
      if (ccpLimitsContradict(point)) {
        result.push({ nodeId: node.id, processName, text: `工序「${processName}」${ccp}的下限大于上限` });
      }
    });
  }
  return result;
}

/** 单个工序节点上的待确认项数（节点角标用）。 */
export function pendingCountOfProcess(data: Record<string, unknown> | undefined): number {
  return listPendingItems([{ id: 'x', kind: 'PROCESS', position: { x: 0, y: 0 }, data: (data ?? {}) as never }]).length;
}

/**
 * 规格 → 节点 data 的 ccpPoints。AI 没给的字段保持 null（⛔ 不补默认、不猜），
 * 非法值（非有限数 / 不认识的危害类型）按「没给」处理并记入 rejections，不静默采纳。
 */
export function buildCcpPointsFromSpec(
  processName: string,
  specCcps: unknown,
  nextId: () => string,
  rejections: string[],
): WorkflowCcpPoint[] {
  if (!Array.isArray(specCcps)) return [];
  const points: WorkflowCcpPoint[] = [];
  for (const raw of specCcps) {
    if (!isRecord(raw)) continue;
    const name = typeof raw.name === 'string' ? raw.name.trim() : '';
    if (!name) continue;
    const hazard = typeof raw.hazardType === 'string' && (CCP_HAZARD_TYPES as readonly string[]).includes(raw.hazardType)
      ? (raw.hazardType as CcpHazardType) : null;
    if (raw.hazardType != null && hazard === null) {
      rejections.push(`工序「${processName}」控制点「${name}」的危害类型不认识，已留作待确认`);
    }
    const num = (value: unknown, label: string): number | null => {
      if (value == null) return null;
      if (finite(value)) return value;
      rejections.push(`工序「${processName}」控制点「${name}」的${label}不是数字，已留作待确认`);
      return null;
    };
    const point: WorkflowCcpPoint = {
      id: nextId(),
      name,
      hazardType: hazard,
      criticalLimitMin: num(raw.criticalLimitMin, '下限'),
      criticalLimitMax: num(raw.criticalLimitMax, '上限'),
      unit: typeof raw.unit === 'string' && raw.unit.trim() ? raw.unit.trim() : null,
    };
    if (ccpLimitsContradict(point)) {
      rejections.push(`工序「${processName}」控制点「${name}」的下限大于上限，已清空限值留作待确认`);
      point.criticalLimitMin = null;
      point.criticalLimitMax = null;
    }
    points.push(point);
  }
  return points;
}

// ── 工序标准参数（油温 150–160℃ 这类）────────────────────────────────────
// AI 听来的参数先住在画布节点 data.processParams；**发布时**后端才写进该工序的 customFieldSchema
// （WorkflowProcessParamMaterializer）。说不清的字段保持 null = 待确认，发布闸拦住。

export interface WorkflowProcessParam {
  id: string;
  label: string;
  unit?: string | null;
  standardValue?: number | null;
  min?: number | null;
  max?: number | null;
  /** 人在画布上确认「覆盖工序已有的标准」；没有它，发布会在要改已有值时 409。 */
  overwriteConfirmed?: boolean;
  /** 勾选那一刻的数值快照；之后数值/单位任何改动都让确认作废（后端同样比对）。 */
  /** 服务端存草稿时盖的已发布版本号（前端不写、不读）：确认只在一次发布内有效。 */
  confirmedBase?: number;
  confirmedFor?: { unit?: string | null; standardValue?: number | null; min?: number | null; max?: number | null };
}

export function processParamsOf(data: Record<string, unknown> | undefined): WorkflowProcessParam[] {
  const raw = data?.processParams;
  return Array.isArray(raw) ? (raw.filter(isRecord) as unknown as WorkflowProcessParam[]) : [];
}

export type ProcessParamMissing = 'range' | 'unit';

/** 这个参数还缺什么（空数组 = 齐全）：标准值/上下限一个都没有、或没有单位。 */
export function processParamMissing(param: WorkflowProcessParam): ProcessParamMissing[] {
  const missing: ProcessParamMissing[] = [];
  if (!finite(param.standardValue) && !finite(param.min) && !finite(param.max)) missing.push('range');
  if (typeof param.unit !== 'string' || !param.unit.trim()) missing.push('unit');
  return missing;
}

/** 数值自相矛盾（下限>上限 / 标准值不在范围内）的一句话；没有矛盾返回 null。 */
export function processParamContradiction(param: WorkflowProcessParam): string | null {
  if (finite(param.min) && finite(param.max) && param.min > param.max) return '下限大于上限';
  if (finite(param.standardValue)
    && ((finite(param.min) && param.standardValue < param.min) || (finite(param.max) && param.standardValue > param.max))) {
    return '标准值不在上下限范围内';
  }
  return null;
}

/** 规格 → 节点 data 的 processParams。没说的字段保持 null（⛔ 不补默认、不猜），非法值按「没说」处理并记入 rejections。 */
export function buildProcessParamsFromSpec(
  processName: string,
  specParams: unknown,
  nextId: () => string,
  rejections: string[],
): WorkflowProcessParam[] {
  if (!Array.isArray(specParams)) return [];
  const params: WorkflowProcessParam[] = [];
  const seen = new Set<string>();
  for (const raw of specParams) {
    if (!isRecord(raw)) continue;
    const label = typeof raw.label === 'string' ? raw.label.trim() : '';
    if (!label) continue;
    if (seen.has(label)) {
      rejections.push(`工序「${processName}」工艺参数「${label}」重复，只保留第一条`);
      continue;
    }
    seen.add(label);
    const num = (value: unknown, what: string): number | null => {
      if (value == null) return null;
      if (finite(value)) return value;
      rejections.push(`工序「${processName}」工艺参数「${label}」的${what}不是数字，已留作待确认`);
      return null;
    };
    const unit = typeof raw.unit === 'string' && raw.unit.trim() ? raw.unit.trim() : null;
    const param: WorkflowProcessParam = {
      id: nextId(),
      label,
      unit: unit && unit.length <= 20 ? unit : null,
      standardValue: num(raw.standardValue, '标准值'),
      min: num(raw.min, '下限'),
      max: num(raw.max, '上限'),
    };
    if (unit && unit.length > 20) rejections.push(`工序「${processName}」工艺参数「${label}」的单位过长，已留作待确认`);
    const contradiction = processParamContradiction(param);
    if (contradiction) {
      rejections.push(`工序「${processName}」工艺参数「${label}」${contradiction}，已清空数值留作待确认`);
      param.standardValue = null;
      param.min = null;
      param.max = null;
    }
    params.push(param);
  }
  return params;
}
