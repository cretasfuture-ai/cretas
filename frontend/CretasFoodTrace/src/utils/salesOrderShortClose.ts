/**
 * 销售订单「短交结案」的纯逻辑 —— RN 侧。
 *
 * 后端契约 (backend/java/cretas-api/src/main/java/com/cretas/aims):
 *   - controller/inventory/SalesController.java:671 `POST /orders/{orderId}/close-short-shipment`
 *     `@RequirePermission("sales:read_write")`, body = CloseShortShipmentRequest (required=false)。
 *   - dto/inventory/CloseShortShipmentRequest.java: `{ reasonCode, notes }`,
 *     reasonCode ∈ CUSTOMER_WAIVED_REMAINDER / SOURCE_SHORTAGE / ORDER_BALANCE_CANCELLED / OTHER,
 *     notes ≤ 500 字。
 *   - service/inventory/impl/SalesServiceImpl.java:2490 closeShortShipment(...):
 *     只接受 PARTIAL_DELIVERED (SHORT_CLOSED 幂等原样返回); 缺口 = Σ max(0, quantity - deliveredQuantity),
 *     缺口 ≤ 0 → 409 SALES_SHORT_CLOSE_NO_SHORTFALL; reasonCode 缺省 → 400
 *     SALES_SHORT_CLOSE_REASON_REQUIRED; OTHER 且 notes 空 → 400 SALES_SHORT_CLOSE_NOTES_REQUIRED。
 *
 * ⚠️ 形态 D (同一个东西有两份): web-admin 侧已有同一套逻辑
 * (web-admin/src/views/sales/orders/salesOrderGuards.ts `shortShipmentSummary` +
 *  detail.vue `showShortShipmentActions` / `SHORT_CLOSE_REASON_OPTIONS`)。
 * 两端跨技术栈无法共享模块, 所以口径**逐条对齐**并在这里写明出处:
 *   - 缺口只认「实发」deliveredQuantity, ⛔ 不用「安排量」(母发货单一建出来就把安排量占满,
 *     而实发可以远小于它 —— 实拍样本 SO-20260902-0001: 订 10kg / 实发 9.8kg)。
 *   - 每行先 clamp `min(ordered, delivered)` 再求和, 与后端 `max(0, ordered - delivered)` 等价
 *     (超发行不会把别的行的缺口冲掉)。
 */

import { hasModulePermission } from './permissionHelper';
import { isMobileBusinessObserver } from './mobileRoleBoundaries';
import type { User } from '../types/auth';

/** 后端 CloseShortShipmentRequest.ReasonCode 的四个值。⛔ 不要在这里造第五个。 */
export type ShortCloseReasonCode =
  | 'CUSTOMER_WAIVED_REMAINDER'
  | 'SOURCE_SHORTAGE'
  | 'ORDER_BALANCE_CANCELLED'
  | 'OTHER';

/**
 * 原因下拉选项。文案与 web-admin detail.vue 的 SHORT_CLOSE_REASON_OPTIONS 逐字相同 ——
 * 同一个业务动作在两端不该有两种说法。
 */
export const SHORT_CLOSE_REASON_OPTIONS: ReadonlyArray<{ value: ShortCloseReasonCode; label: string }> = [
  { value: 'CUSTOMER_WAIVED_REMAINDER', label: '客户同意放弃剩余数量' },
  { value: 'SOURCE_SHORTAGE', label: '原料/成品已耗尽，短期无法补齐' },
  { value: 'ORDER_BALANCE_CANCELLED', label: '客户取消订单剩余部分' },
  { value: 'OTHER', label: '其他原因' },
];

/**
 * 后果说明。取自后端枚举 SalesOrderStatus.SHORT_CLOSED 的 description 字段
 * (entity/enums/SalesOrderStatus.java:31) —— ⛔ 不自己编一句新的。
 */
export const SHORT_CLOSE_CONSEQUENCE_TEXT = '部分发货后确认剩余数量不再发货，按短交结案';

/** 「这项归谁」——权限不足时的解释文案, 口径与后端 sales:read_write 逐字对应。 */
export const SHORT_CLOSE_PERMISSION_REASON = '短交结案归销售（sales:read_write），你的角色只能查看。';

/**
 * 浮点容差。数量是 BigDecimal 经 JSON 转成 double, 0.1+0.2 这类尾差不该被当成缺口。
 * 与 web-admin detail.vue `shortShipment.value.outstanding > 0.000001` 同一个阈值。
 */
export const SHORT_CLOSE_EPSILON = 0.000001;

export interface ShortCloseLine {
  /** 展示名。与详情表格同源: productName → productTypeName → productTypeId。 */
  productLabel: string;
  ordered: number;
  delivered: number;
  /** 缺口 = max(0, ordered - delivered)。 */
  shortfall: number;
  unit: string;
}

export interface ShortCloseSummary {
  lines: ShortCloseLine[];
  /** 只含 shortfall > 0 的行 —— 确认弹窗列的就是这些。 */
  shortLines: ShortCloseLine[];
  ordered: number;
  delivered: number;
  /** 订单级缺口 = Σ 每行 max(0, ordered - delivered)，与后端 shortfall 同式。 */
  outstanding: number;
  /** 各行单位一致时给出单位；混单位时为空串 (⛔ 不拿一个单位去标另一种的数)。 */
  unit: string;
}

type ItemLike = {
  productName?: string | null;
  productTypeName?: string | null;
  productTypeId?: string | null;
  quantity?: number | string | null;
  deliveredQuantity?: number | string | null;
  unit?: string | null;
};

function num(value: number | string | null | undefined): number {
  const parsed = typeof value === 'number' ? value : Number(value ?? 0);
  return Number.isFinite(parsed) ? parsed : 0;
}

/** 逐行算订购/已发/短交, 并汇总订单级缺口。 */
export function shortCloseSummary(items: ReadonlyArray<ItemLike> | null | undefined): ShortCloseSummary {
  const units = new Set<string>();
  const lines: ShortCloseLine[] = (items ?? []).map((item) => {
    const ordered = num(item.quantity);
    // ⚠️ clamp: 某一行超发不允许抵消别的行的缺口 (与后端 max(0, ordered-delivered) 一致)。
    const delivered = Math.min(ordered, num(item.deliveredQuantity));
    const unit = String(item.unit ?? '').trim();
    if (unit) units.add(unit);
    return {
      productLabel: String(item.productName || item.productTypeName || item.productTypeId || '未知产品'),
      ordered,
      delivered,
      shortfall: Math.max(0, ordered - delivered),
      unit,
    };
  });
  const ordered = lines.reduce((sum, line) => sum + line.ordered, 0);
  const delivered = lines.reduce((sum, line) => sum + line.delivered, 0);
  return {
    lines,
    shortLines: lines.filter((line) => line.shortfall > SHORT_CLOSE_EPSILON),
    ordered,
    delivered,
    outstanding: Math.max(0, ordered - delivered),
    unit: units.size === 1 ? ([...units][0] ?? '') : '',
  };
}

/** 前端可见性判定所需的最小订单形状 —— ⛔ 不要求整个 SalesOrder, 便于测试造样本。 */
export interface ShortCloseOrderLike {
  status?: string | null;
  items?: ReadonlyArray<ItemLike> | null;
}

/**
 * 「短交结案」按钮是否出现。三个条件缺一不可：
 *   1. 订单状态 PARTIAL_DELIVERED —— 后端唯一接受的入口状态 (SalesServiceImpl:2500)。
 *   2. 当前用户有 sales:read_write —— 与 @RequirePermission("sales:read_write") 同口径,
 *      ⛔ 不硬编码角色串 (permissionHelper 的矩阵是单一真相, 加角色只改那里)。
 *   3. 实发缺口 > 0 —— 没缺口时后端 409 SALES_SHORT_CLOSE_NO_SHORTFALL, 按钮出现就是骗人。
 *
 * ⚠️ 另外排除「移动端业务观察者」(工厂老板 / 运营协调员): 他们在这个复用页上全程只读,
 * 与本屏其它写动作 (`!isOperationsReadOnly`) 同一道闸, 不为短交单独开口子。
 * 前端隐藏 ≠ 放行, 后端那道闸仍是最终真值。
 */
export function canShortCloseSalesOrder(
  order: ShortCloseOrderLike | null | undefined,
  user: User | null | undefined
): boolean {
  if (!order) return false;
  if (order.status !== 'PARTIAL_DELIVERED') return false;
  if (isMobileBusinessObserver(user ?? null)) return false;
  if (!hasModulePermission(user, 'sales', 'write')) return false;
  return shortCloseSummary(order.items).outstanding > SHORT_CLOSE_EPSILON;
}

export interface ShortCloseForm {
  reasonCode: ShortCloseReasonCode | '';
  notes: string;
  acknowledged: boolean;
}

/**
 * 提交前的本地拦截。返回 null 表示可提交, 否则返回给用户看的那句话。
 *
 * ⚠️ 这三条与后端 SalesServiceImpl 的 400 分支一一对应, 目的是**少一次往返**,
 * ⛔ 不是替代后端校验 —— 后端仍会自己再判一遍。
 */
export function shortCloseSubmitBlockReason(form: ShortCloseForm): string | null {
  if (!form.reasonCode) return '请选择短交结案原因';
  if (form.reasonCode === 'OTHER' && !form.notes.trim()) return '选择“其他原因”时必须填写补充说明';
  if (form.notes.trim().length > 500) return '补充说明不能超过500字';
  if (!form.acknowledged) return '请先勾选确认，再提交短交结案';
  return null;
}

/** 数量展示: 去掉浮点尾巴, 但不改变数值 (3.0 → "3", 9.8 → "9.8")。 */
export function formatShortCloseQty(value: number): string {
  return String(Number(value.toFixed(3)));
}

/**
 * 确认弹窗正文。列出每一条短交行的 订购/已发/短交 + 后果说明。
 * ⛔ 数字全部来自订单明细, 不允许出现任何写死的示例数。
 */
export function shortCloseConfirmText(summary: ShortCloseSummary): string {
  const rows = summary.shortLines.map((line) => {
    const unit = line.unit ? ` ${line.unit}` : '';
    return `${line.productLabel}：订购 ${formatShortCloseQty(line.ordered)}${unit} / 已发 ${formatShortCloseQty(line.delivered)}${unit} / 短交 ${formatShortCloseQty(line.shortfall)}${unit}`;
  });
  const totalUnit = summary.unit ? ` ${summary.unit}` : '';
  return [
    ...rows,
    `合计短交 ${formatShortCloseQty(summary.outstanding)}${totalUnit}`,
    SHORT_CLOSE_CONSEQUENCE_TEXT,
  ].join('\n');
}
