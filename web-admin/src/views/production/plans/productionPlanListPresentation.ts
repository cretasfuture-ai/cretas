import type { RouteLocationRaw } from 'vue-router';
import { canonicalUnitCode, displayUnit } from '@/utils/unitPricing';
import { RAW_WORKFLOW_OWNER_ANCHOR_LABEL, isRawWorkflowOwnerAnchorName } from '@/constants/rawWorkflowOwnerAnchor';

export type ProductionPlanListRow = {
  id?: unknown;
  status?: unknown;
  sourceType?: unknown;
  sourceOrderId?: unknown;
  customerOrderNumber?: unknown;
  plannedQuantity?: unknown;
  plannedUnit?: unknown;
  actualQuantity?: unknown;
  cancelReason?: unknown;
  cancelledBy?: unknown;
  cancelledByName?: unknown;
  cancelledAt?: unknown;
};

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function formatQuantity(value: number): string {
  return Number.isInteger(value) ? String(value) : value.toLocaleString(undefined, {
    maximumFractionDigits: 4,
  });
}

export function formatPlanActualQuantity(row: ProductionPlanListRow | null | undefined): string {
  if (!row) return '—';
  const quantity = finiteNumber(row.actualQuantity);
  if (quantity === null) return '—';
  const unit = canonicalUnitCode(row.plannedUnit);
  return unit
    ? `${formatQuantity(quantity)} ${displayUnit(unit)}`
    : `${formatQuantity(quantity)}（单位未配置）`;
}

export function sourceOrderBusinessNumber(row: ProductionPlanListRow | null | undefined): string | null {
  const number = String(row?.customerOrderNumber ?? '').trim();
  return number || null;
}

export function sourceOrderDisplay(row: ProductionPlanListRow | null | undefined): string {
  const number = sourceOrderBusinessNumber(row);
  if (number) return number;
  const sourceOrderId = String(row?.sourceOrderId ?? '').trim();
  if (sourceOrderId) return '业务订单号未同步';
  return '—';
}

export function sourceOrderTarget(row: ProductionPlanListRow | null | undefined): RouteLocationRaw | null {
  const sourceOrderId = String(row?.sourceOrderId ?? '').trim();
  if (!sourceOrderId) return null;
  return { path: `/sales/orders/${encodeURIComponent(sourceOrderId)}` };
}

export function cancellationAudit(
  row: ProductionPlanListRow | null | undefined,
): { reason: string; actor: string; time: string } | null {
  if (String(row?.status ?? '').toUpperCase() !== 'CANCELLED') return null;
  const reason = String(row?.cancelReason ?? '').trim() || '未记录原因';
  const actorName = String(row?.cancelledByName ?? '').trim();
  const actorId = String(row?.cancelledBy ?? '').trim();
  const actor = actorName || (actorId ? `用户 ${actorId}` : '历史记录未标记操作人');
  const rawTime = String(row?.cancelledAt ?? '').trim();
  const time = rawTime ? rawTime.replace('T', ' ').slice(0, 19) : '历史记录未标记时间';
  return { reason, actor, time };
}

/**
 * 逐道录入抽屉标题 /「逐工序报工」后面的产品文字。
 *
 * 2026-10-25 线上实拍(F006【演示L4】多产品计划): 这里显示成
 * `__RAW_WORKFLOW_OWNER__RMT_F006_LSM_…` —— 多产出计划的 productTypeId 是原料锚点,
 * 后端下发的 productTypeName 就是锚点名。列表「生产成品」列走 targetFinishedGoodIds
 * 显示正常, 这里改为吃同一份名字(planFinishedProductNames 的结果)。
 *
 * - 多个成品 → 「多产出计划：A、B、C」
 * - 一个成品 → 成品名
 * - 只剩锚点 → 「多产出计划」(拿不到真名时不编名字, 也不露内部编码)
 */
export function planSheetProductLabel(names: readonly unknown[]): string {
  const cleaned = names.map((n) => String(n ?? '').trim()).filter((n) => n && n !== '-');
  const real = cleaned.filter((n) => !isRawWorkflowOwnerAnchorName(n) && n !== RAW_WORKFLOW_OWNER_ANCHOR_LABEL);
  if (real.length > 1) return `${RAW_WORKFLOW_OWNER_ANCHOR_LABEL}：${real.join('、')}`;
  if (real.length === 1) return real[0];
  return cleaned.length > 0 ? RAW_WORKFLOW_OWNER_ANCHOR_LABEL : '';
}
