/**
 * 工厂超管必须走得到报表。
 *
 * ## 为什么需要这一条（2026-08-27）
 *
 * `FAReportsStackNavigator`（18 个屏）此前唯一的挂载点是
 * `FactoryAdminTabNavigator` 的 `FAReportsTab`，守卫写着
 * `isScreenEnabled('Reports') && !isRestaurantMode`。
 *
 * 而那棵子树只在 `isRestaurant(user)` 为真时挂载（`AppNavigator:92`
 * `isRestaurant(user) ? <FactoryAdminNavigator /> : <BossNavigator />`）
 * ⇒ `!isRestaurantMode` **恒为假** ⇒ 整套报表**一个屏都走不到**，
 * 而后端 `ReportController` 的 27 个端点一直好好地在返回数据
 * （实测 22 个只读 GET 里 21 个有真数据）。
 *
 * PR #209 删掉了那个恒假注册，本闸守住「接到 BossNavigator 之后别再掉了」。
 *
 * ## ⚠️ 判据用的是分析器，不是文本匹配
 *
 * 只断言 `BossNavigator.tsx` 里出现 `FAReportsStackNavigator` 是不够的 ——
 * 上一次出事恰恰是**注册在，但那一支不渲染**。所以这里沿注册树真的算一遍
 * 「`factory_super_admin` 能走到哪些屏文件」。
 *
 * ⚠️ 已知盲区（继承自 lib）：`parseRoleDispatch` 把三元的两支都算可达，
 *    所以本闸**不能**用来证明「餐饮用户走不到报表」。它只证明正向那一半：
 *    工厂超管这条路上，报表屏在可达集合里。反向那一半由
 *    `navigatorMountContextReachability` 的矛盾检测负责。
 */
import { ALL_ROLES, parseRoleDispatch, reachableScreensFor, relative } from './lib/roleReachability';

const DISPATCH = parseRoleDispatch();

function reachableFiles(role: string): string[] {
  return [...reachableScreensFor(role, DISPATCH).keys()].map(relative);
}

const BOSS = 'factory_super_admin';

/** 落地页 + 三个有代表性的真取数报表。⛔ 只钉一个屏的话，删掉别的 17 个也不会红。 */
const MUST_REACH = [
  'screens/reports/ReportDashboardScreen.tsx',
  'screens/reports/ProductionReportScreen.tsx',
  'screens/reports/QualityReportScreen.tsx',
  'screens/reports/KPIReportScreen.tsx',
];

describe('工厂超管走得到报表', () => {
  it('阳性对照：分析器确实算出了这个角色的可达集合', () => {
    // ⛔ 没有这一条，下面的 toContain 在「集合为空」时会红得莫名其妙，
    //    而在集合恒大时又可能假绿。先证明仪器活着。
    const files = reachableFiles(BOSS);
    expect(ALL_ROLES).toContain(BOSS);
    expect(files.length).toBeGreaterThan(30);
    // 这个角色的落地页 —— 它不在，说明是分流没解析出来，不是报表的问题
    expect(files).toContain('screens/factory-admin/home/BossOverviewScreen.tsx');
  });

  it.each(MUST_REACH)('%s 在 factory_super_admin 的可达集合里', (rel) => {
    expect(reachableFiles(BOSS)).toContain(rel);
  });

  it('阴性对照：不是「什么屏都算可达」', () => {
    // 随便一个不存在的路径必须不在集合里 —— 否则上面每条 toContain 都是恒真式。
    expect(reachableFiles(BOSS)).not.toContain('screens/__NoSuchScreen__.tsx');
    // 平台端的屏不该出现在工厂超管的可达集合里。
    expect(reachableFiles(BOSS).every((f) => !f.includes('screens/platform/'))).toBe(true);
  });
});
