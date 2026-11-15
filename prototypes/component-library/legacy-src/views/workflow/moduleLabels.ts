/**
 * OA 四个页面共用的 moduleCode 中文兜底表 —— **只有一份**。
 *
 * <h2>⚠️ 这不是事实来源</h2>
 *
 * 权威表在后端 `DecisionTypeMetadataRegistry`（30+ 个 moduleCode，各带 chineseName），
 * 并通过 `WorkflowInstancePendingDTO.moduleLabel` 下发 —— `/pending`、`/my-created`、
 * `/acted`、`/copied` **四个端点返回的是同一个 DTO**，都带这个字段。
 *
 * ⇒ 渲染一律写成 `row.moduleLabel || enumLabel(row.moduleCode, MODULE_LABELS)`：
 * **优先用后端下发的**，本表只在后端没下发时兜底（离线 / 老版本后端）。
 *
 * ⛔ **不要往这张表里加新码。** 加了只会让它和后端权威表再漂一次 ——
 * 这份表历史上只覆盖了权威表的一小部分，另外 20 多个码全部渲染成「未知状态（X）」，
 * 客户截图里的「未知状态（BUDGET）」就是这么来的。要加中文名请加到后端权威表。
 *
 * <h2>🔴 2026-08-28 走查抓到的：四个页面各写各的，三种不同内容</h2>
 *
 * <pre>
 *   pending.vue     4 条  独有 INVENTORY_ADJUSTMENT，缺 DISPOSAL/TRANSFER/VOUCHER/ECN
 *   my-created.vue  7 条  缺 INVENTORY_ADJUSTMENT
 *   acted.vue       6 条  缺 INVENTORY_ADJUSTMENT 和 INVENTORY_TRANSFER
 *   copied.vue      6 条  同上
 * </pre>
 *
 * 而且**只有 `pending.vue` 读了 `row.moduleLabel`**，另外三个只查自己那张表
 * ⇒ 生产实测（f006_admin →「我发起的」）：三行盘点审批显示成大写英文
 * <code>INVENTORY_ADJUSTMENT</code>，同一列其他行是中文。
 *
 * ⚠️ `pending.vue` 那段注释早就预言了「加在这里只会再漂一次」—— 它在隔壁文件漂了。
 * ⇒ 收敛成这一份，并由 `__tests__/moduleLabelPrefersBackend.source.spec.ts` 钉住。
 */
export const MODULE_LABELS: Record<string, string> = {
  PURCHASE_ORDER: '采购订单',
  SALES_ORDER: '销售订单',
  INVENTORY_TRANSFER: '库存调拨',
  INVENTORY_ADJUSTMENT: '库存盘点',
  DISPOSAL: '废弃处置',
  TRANSFER: '调拨单',
  VOUCHER: '凭证',
  ECN: '工程变更',
};
