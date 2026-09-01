import { ROLE_LABELS } from '@/utils/enumDisplay';

/**
 * `FactoryUserRole` 枚举值的**前端副本** —— 账号管理下拉的取值域。
 *
 * ## 为什么这里必须是枚举值集合, 而不是直接用 `ROLE_LABELS` 的键
 *
 * `ROLE_LABELS` 是「角色码 → 中文」的展示表, 它**可以**比枚举多
 * (例: `factory_admin` 是工作流设计器里的历史角色码, Java 枚举里没有)。
 * 而 `CreateUserRequest.roleCode` 的类型是 `FactoryUserRole` 枚举, Jackson 在
 * `@RequestBody` 反序列化时对不认识的字面量**直接 400**。⇒ 下拉里出现一个
 * 枚举外的码, 用户选了就是保存失败, 而报错跟角色八竿子打不着。
 *
 * ## 为什么允许有两份 (以及靠什么钉住)
 *
 * 抽成一份要跨 Java / TypeScript 两个运行时, 抽不动。按本仓「同一个东西有两份,
 * 它一定会漂」的纪律, 这里的处置是**钉一道闸**:
 * `__tests__/factoryUserRoleDictionary.spec.ts` 直接读 `FactoryUserRole.java`,
 * 断言**两边集合必须相等** (不是「各自都对」)。后端加/删角色而这里没跟, CI 就红。
 *
 * ## 这份副本是怎么漂出来的 (2026-09-01 LIUSHANMEN 实测)
 *
 * 账号管理页 `system/users/list.vue` 此前**自带**一份 24 条的 `roleOptions`,
 * 上方注释白纸黑字写着 `Must match FactoryUserRole enum values exactly`,
 * 而它自己少了 6 个: `yield_operator` / `quality_controller` / `cashier` /
 * `restaurant_owner` / `restaurant_chef` / `restaurant_purchaser`。后果不只是
 * 列表里 9 个 `baogong00X` 账号的角色列显示英文原码 —— 这 6 个角色在**添加/编辑**
 * 下拉里根本选不到, 也就无法通过 UI 建这些角色的账号。
 * 注释里还记着上一次同样的漂移 (Apr 20 BR-14), 即同一处至少漂过两次 ⇒ 靠注释不管用。
 *
 * ⚠️ 顺序沿用 Java 枚举的声明顺序 (按 level 分层: 平台 → 工厂总监 → 部门经理 →
 * 车间 → 一线 → 特殊), 下拉据此渲染。闸只钉集合相等, 不钉顺序 —— 顺序是 UX 选择。
 */
export const FACTORY_USER_ROLE_CODES = [
  // Level -1 / 0
  'platform_admin',
  'factory_super_admin',
  // Level 10~15: 职能部门
  'hr_admin',
  'procurement_manager',
  'sales_manager',
  'operations_coordinator',
  'dispatcher',
  'production_manager',
  'production_clerk',
  'warehouse_manager',
  'equipment_admin',
  'quality_manager',
  'quality_controller',
  'finance_manager',
  'cashier',
  'restaurant_manager',
  'restaurant_owner',
  'restaurant_chef',
  'restaurant_purchaser',
  // Level 20~28: 车间管理层
  'workshop_supervisor',
  'team_leader',
  'group_leader',
  // Level 30: 一线员工
  'quality_inspector',
  'operator',
  'yield_operator',
  'warehouse_worker',
  // 特殊
  'permission_admin',
  'department_admin',
  'viewer',
  'unactivated',
] as const;

export type FactoryUserRoleCode = (typeof FACTORY_USER_ROLE_CODES)[number];

export interface FactoryUserRoleOption {
  value: string;
  label: string;
}

/**
 * 账号管理下拉用的 `{ value, label }` 列表。
 *
 * 中文一律走 `ROLE_LABELS` (角色中文的单一来源), ⛔ 不在这里另抄一份 ——
 * 那正是 `enumDisplay.ts` 头注释说的「仓库里曾有 4 份互相矛盾的角色表」。
 */
export function factoryUserRoleOptions(): FactoryUserRoleOption[] {
  return FACTORY_USER_ROLE_CODES.map((value) => ({ value, label: ROLE_LABELS[value] ?? value }));
}
