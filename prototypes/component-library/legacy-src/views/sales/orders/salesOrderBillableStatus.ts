/**
 * 销售订单「可开票 / 可收款」的状态集合 —— 单一来源。
 *
 * 🔴 2026-08-22: 抽出来之前, 这个口径在 list.vue 里**手写了三份**, 三份都带着
 * 同一个缺陷 —— 含一个在订单 statusMap 里**不存在**的状态值(那个值真正的归属是
 * 「出库状态」域), 并且三份都漏了 FINANCE_APPROVED。
 *
 * 实测 F006 全部 34 张单: CANCELLED 9 / FINANCE_APPROVED 9 / COMPLETED 8 /
 * DRAFT 6 / PENDING_FINANCE_REVIEW 1 / PARTIAL_DELIVERED 1
 * ⇒ 旧条件唯二能命中的 CONFIRMED / PROCESSING **一张都没有**
 * ⇒ 三个行内操作在生产环境里一张单都点不出来, 而界面上和「被正确隐藏」长得一样。
 *
 * ▎同一个口径写三份, 它一定会漂 —— 这次漂的方向是三份一起错。
 */

/**
 * 可开票: 已确认 / 处理中 / 审批已通过 / 部分发货。
 *
 * ⛔ 刻意排除 DRAFT、PENDING_FINANCE_REVIEW、FINANCE_REJECTED、CANCELLED ——
 * 没过审和已取消的单不该开票。
 */
export const BILLABLE_STATUSES: readonly string[] = [
  'CONFIRMED',
  'PROCESSING',
  'FINANCE_APPROVED',
  'PARTIAL_DELIVERED',
  // T-SHORT-SHIP (2026-09-04): 短交结案只终止剩余数量的后续发货, 已发货部分本就按实发
  // 数量开票 —— 镜像后端 OrderUsageWhitelists.SO_INVOICEABLE 同一次改动。
  'SHORT_CLOSED',
];

/**
 * 可收款 = 可开票 + COMPLETED。
 *
 * ⚠️ 比开票多一个 COMPLETED 是有意的: 货交完之后才收到钱是常态,
 * 而开票通常不晚于交付完成。
 */
export const PAYABLE_STATUSES: readonly string[] = [...BILLABLE_STATUSES, 'COMPLETED'];
