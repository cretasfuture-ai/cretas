/**
 * 「这个写操作会让哪些业务域的数据过时」—— 写操作侧的声明表。
 *
 * <h3>为什么单独一个文件</h3>
 * 失效**机制**只有一处 (store 的 `invalidateModules` + AppLayout 的接线)。
 * 但「一次审批通过影响了谁」是业务知识, 只能由写操作那一侧说。放在这里而不是
 * 塞进 pending.vue, 是为了让它可以被直接单测 —— 写在 SFC 里的常量只能靠源码正则去猜。
 *
 * <h3>值域</h3>
 * ⚠️ 值全部是路由 `meta.module` 的取值 (router/index.ts 里的权威分类, 19 种)。
 * ⛔ 不要在这里发明新的域名: 发明一个 router 里不存在的值, 表看起来是对的,
 * 而它匹配不到任何标签 —— 失效**静默**失效, 没有任何东西会红。
 */

/**
 * OA 审批动作 → 受影响的业务域。
 *
 * 🔴 2026-08-20 owner 实测:「我在个人 OA 里点击通过以后, 再点到财务管理里面, 还是待审批;
 * 到仓储管理的入库任务与批次, 点进去没有任何东西。但我刷新了一下网页, 它就更新好了。」
 *
 * ⚠️ 每一条都带 `dashboard`: 个人 OA 自己 (`/workflow/**`) 的 `meta.module` 就是 `dashboard`,
 * 「我发起的 / 已处理 / 抄送我的」这几个标签在批完之后同样已经过时。
 *
 * ⚠️ 键必须与 pending.vue 的 `ACTIONABLE_MODULE_CODES` 对齐 —— 那是「哪些单据点得动通过/驳回」
 * 的权威清单。少一个键的后果不是报错, 而是那一类单据批完之后别的标签**照旧显示旧数据**,
 * 也就是这次要修的 bug 原样复发。`__tests__` 里有一条闸钉着这个对齐。
 */
export const OA_ACTION_INVALIDATES: Record<string, readonly string[]> = {
  // 采购单批完: 采购单状态变了(procurement)、应付/审批流水变了(finance)、
  // 可收货的入库任务出现了(warehouse) —— owner 报的正是后两个。
  PURCHASE_ORDER: ['dashboard', 'procurement', 'finance', 'warehouse'],
  SALES_ORDER: ['dashboard', 'sales', 'finance', 'warehouse'],
  // 调拨批完库存还没过账(要回单据点「确认调拨入库」), 但调拨单/待办列表已经变了。
  INVENTORY_TRANSFER: ['dashboard', 'warehouse'],
  INVENTORY_ADJUSTMENT: ['dashboard', 'warehouse', 'finance'],
};

/**
 * 兜底 `dashboard` 而不是空数组: 任何一次 OA 动作都必然让待办列表本身过时,
 * ⛔ 「没登记的业务码 = 什么都不失效」会让新接入的业务域悄悄退回到本次修的 bug。
 */
export function oaInvalidatedModules(moduleCode: string): readonly string[] {
  return OA_ACTION_INVALIDATES[moduleCode] ?? ['dashboard'];
}
