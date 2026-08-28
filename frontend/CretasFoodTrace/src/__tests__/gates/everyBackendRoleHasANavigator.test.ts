/**
 * 🔒 后端真的会发出来的每一个角色，都必须在 AppNavigator 里有一条分支 —— 或者被**显式登记**为
 * 「有意落到锁定页」。⛔ 不许靠「反正有个兜底」把角色悄悄丢进 RestrictedRoleNavigator。
 *
 * <h3>为什么需要这道闸（2026-08-28 逐屏走查 G1）</h3>
 *
 * `navigation/restaurant/` 下四棵 stack（菜谱/领用/盘点/损耗，共 11 屏）建好了，
 * 但**没有任何地方 import 过**；而 AppNavigator 的分派里也没有任何一条命中餐饮角色。
 * 生产库实测：`restaurant_owner` / `restaurant_manager` / `restaurant_purchaser`
 * 各 1 个账号，三个人登录后一律落到锁定页 —— 11 屏一屏都进不去，而且**不报任何错**。
 *
 * 「建好了没接上」这一类的特征就是：编译过、测试绿、屏也不崩，只是没人走得到。
 * 单测对它是盲的（它直接调被测组件，绕过了「谁把用户送进来」）。
 *
 * <h3>这道闸量的是什么</h3>
 *
 * 角色清单是**从生产库抄下来的快照**（见 PRODUCTION_ROLES）。闸只回答一句：
 * 清单里每个角色，在 AppNavigator 源码里是否出现在一条 `userRole === "..."` 判断中。
 *
 * ⚠️ 这是**代理判据**，它看不见「那条分支是不是指向对的树」。
 * 它只能拦住「一个真实存在的角色连分支都没有」这一类 —— 也正是 G1 那一类。
 */
import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * 2026-08-28 从生产库抄下来的角色清单：
 *   select role_code, count(*) from users group by role_code;
 *
 * ⚠️ 新增角色时要同步这里。清单过期会让闸变成恒真式 —— 那正是它要防的东西。
 */
const PRODUCTION_ROLES = [
  'sales_manager', 'factory_super_admin', 'yield_operator', 'operator',
  'quality_inspector', 'quality_manager', 'warehouse_manager', 'finance_manager',
  'hr_admin', 'procurement_manager', 'workshop_supervisor', 'equipment_admin',
  'warehouse_worker', 'dispatcher', 'viewer', 'production_manager',
  'restaurant_purchaser', 'cashier', 'platform_admin', 'department_admin',
  'permission_admin', 'restaurant_owner', 'restaurant_manager',
] as const;

/** 有意不给独立导航、就该落到锁定页的角色。⛔ 往这里加要写理由。 */
const DELIBERATELY_RESTRICTED: string[] = [];

describe('每个后端角色都有导航分支', () => {
  const src = readFileSync(
    join(__dirname, '../../navigation/AppNavigator.tsx'),
    'utf8',
  );

  it('自检：读到的确实是 AppNavigator，且它真的在按角色分派', () => {
    expect(src.length).toBeGreaterThan(2000);
    const branches = src.match(/userRole === "[a-z_]+"/g) ?? [];
    expect(branches.length).toBeGreaterThan(15);
  });

  it('生产库里存在的角色，一个都不许没有分支', () => {
    const missing = PRODUCTION_ROLES.filter(
      (r) => !DELIBERATELY_RESTRICTED.includes(r) && !src.includes(`userRole === "${r}"`),
    );
    expect(missing).toEqual([]);
  });

  it('阴性对照：一个不存在的角色**应当**被判为缺分支（否则这道闸在守空气）', () => {
    expect(src.includes('userRole === "definitely_not_a_role"')).toBe(false);
  });
});
