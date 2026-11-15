import type { Router } from 'vue-router';
import { isPathAllowedForRole } from '@/router/guards';
import type { ModuleName } from '@/store/modules/permission';

/**
 * 「审批人点开自己在审的那张单」—— 够不着路由时退到只读速览抽屉。
 *
 * ## 为什么不是「把那条路由开给审批人」
 *
 * 缺陷 30 (2026-09-01 LIUSHANMEN 生产实测): `lsm009`(财务主管)审完
 * `PO-20260901-0003` 想看这张单, **整页跳 /403** —— 审批链把采购单派给财务审,
 * 而财务点开自己审的单必被拦。
 *
 * 直觉修法是把 `/procurement/orders/:id` 开给 finance_manager。⛔ **不行**, 两条理由:
 *
 * 1. **撤销 owner 已经拍过的板**。`V20261030_14__finance_manager_narrow.sql` 记着原话:
 *    「采购的话需要看的是采购单的详情……在导航栏里面其实只能看到财务自己本身的内容,
 *    但是我们又要让财务看到这些订单详情的内容, **但是又不能让他直接跨模块去看**。」
 *    ⇒ 「菜单里看得见 / 路由进得去 / 接口读得到」三件事被**刻意拆开**了,
 *    那张 migration 就是执行这个拆分的(砍掉 finance 的 analytics/production/rd/sales/procurement)。
 *    它同批还写明:「那个能力要**新建** —— 财务页内只读抽屉(⛔ 不新增路由, 物理上进不了导航)」。
 *    ⇒ 抽屉建了({@link ../finance/components/OrderPeekDrawer.vue}, 有闸钉它不许注册路由),
 *      但**只接了发票页那一处**; OA 这条路径还在 `router.push` ⇒ 本缺陷就是那半没接上。
 *
 * 2. **路径闸是前缀匹配, 而「新建采购订单」不是独立路由**, 是 `procurement/orders/list.vue`
 *    里的对话框。把 `/procurement/orders` 放进白名单 = 把新建面一起给了财务,
 *    `meta.modulesAny` 拦不住 —— 两道闸**串联**且路径闸在前(见 guards.ts beforeEach)。
 *
 * ⇒ 正确形状: **够得着就跳转, 够不着就开只读抽屉**, 权限模型一动不动。
 */

/** `moduleCode` → 只读抽屉能承载的单据类型。没登记 = 这类单据没有抽屉可退。 */
export const PEEKABLE_BY_MODULE: Record<string, 'sales' | 'purchase'> = {
  PURCHASE_ORDER: 'purchase',
  SALES_ORDER: 'sales',
};

/**
 * 「这个角色点下去会不会 403」—— 必须与 `guards.ts` 的 `beforeEach` **同一份判定**。
 *
 * ⚠️ 两道闸是**串联**的, 缺一道就会给出偏乐观的答案:
 *   1. 角色路径白名单 {@link isPathAllowedForRole} —— ⛔ 只写 `canAccess()` 会漏掉这道。
 *      实例: `finance_manager` 白名单里有 `/warehouse/stocktakes` 但没有 `/warehouse/materials`,
 *      即使模块权限通, 路径闸照样 403。
 *   2. 模块闸 `meta.module` / `meta.modulesAny`。
 *
 * 🔴 模块从**真实路由表**里 resolve 出来, ⛔ 不在这里另抄一份「路径 → 模块」映射 ——
 * 那就是形态 D(同一个东西有两份, 它一定会漂), 而漂的结果是这个判定与守卫给出不同答案,
 * 表现为「按钮显示了但点进去 403」或者反过来「明明进得去却被藏了」。
 */
export function isRouteReachable(
  router: Router,
  role: string,
  target: string,
  canAccess: (module: ModuleName) => boolean,
): boolean {
  // 路径闸只认路径, 深链的 query 要先剥掉 (例: /warehouse/stocktakes?stocktakeId=x)。
  const path = target.split('?')[0];
  if (!isPathAllowedForRole(role, path)) return false;

  const resolved = router.resolve(path);
  // 解析不到任何路由记录 = 这条路径压根不存在, 交给调用方按「没接详情页」处理。
  if (!resolved.matched.length) return false;

  const modulesAny = resolved.meta?.modulesAny as ModuleName[] | undefined;
  if (modulesAny?.length) return modulesAny.some((m) => canAccess(m));

  const module = resolved.meta?.module as ModuleName | undefined;
  if (module) return canAccess(module);

  return true;
}
