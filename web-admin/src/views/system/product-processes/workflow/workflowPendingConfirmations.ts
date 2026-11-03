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
