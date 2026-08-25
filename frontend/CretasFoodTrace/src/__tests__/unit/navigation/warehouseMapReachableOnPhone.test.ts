import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * 2026-08-25 生产验收发现: Web 端配好的仓储空间, 手机上根本看不到。
 *
 * `WHLocationManageScreen`(库位 + 仓库平面图) 当时**只**挂在
 * `WHInventoryStackNavigator` 下, 而那个 Tab 被
 * `SIMPLIFIED_WAREHOUSE_NAV = true` 按 owner 裁定整栈隐藏了
 * (原话「仓管在 App 上唯一要做的是确认入库和发货, 不再让他查库存」)。
 * 于是: 仓管无入口, `f006_warehouse_worker` 账号 is_active=false,
 * `factory_super_admin` 走 BossNavigator 也没有仓储入口 —— 三条路全断。
 *
 * ⛔ 正确的修法**不是**把 SIMPLIFIED_WAREHOUSE_NAV 翻成 false:
 *    那会把 18 个库存查询屏一起放出来, 正好违背 owner 那条裁定。
 *    地图是「现场找货的参照」, 不是库存查询 —— 单独挂进首页栈。
 */
const SRC = join(__dirname, '../../../..', 'src');
const read = (path: string) => readFileSync(join(SRC, path), 'utf8');

const homeStack = read('navigation/warehouse/WHHomeStackNavigator.tsx');
const home = read('screens/warehouse/home/WHHomeScreen.tsx');
const tabNav = read('navigation/WarehouseManagerTabNavigator.tsx');
const locationScreen = read('screens/warehouse/inventory/WHLocationManageScreen.tsx');

describe('仓库地图在手机上必须够得着', () => {
  it('首页栈里挂了 WHLocationManage', () => {
    expect(homeStack).toContain('name="WHLocationManage"');
    expect(homeStack).toContain('component={WHLocationManageScreen}');
  });

  it('首页有一个能点到它的入口', () => {
    expect(home).toContain('testID="wh-home-warehouse-map"');
    expect(home).toContain("navigate('WHLocationManage')");
    expect(home).toContain('accessibilityLabel="查看库位与仓库地图"');
  });

  it('阳性对照: 这个闸读的是真的那几个文件', () => {
    // 读错文件时「什么都不包含」会让上面两条假绿地红, 这条先炸, 指出是仪器坏了。
    expect(home).toContain('testID="wh-home-inbound"');
    expect(homeStack).toContain('name="WHHome"');
    expect(homeStack.length).toBeGreaterThan(500);
  });

  /**
   * owner 那条裁定必须原样保留 —— 修可达性不等于把库存查询放回来。
   * 这是本次修复的**阴性对照**: 如果谁图省事把开关翻了, 这条会红。
   */
  it('⛔ 不许靠翻 SIMPLIFIED_WAREHOUSE_NAV 来解决 —— 库存 Tab 保持隐藏', () => {
    expect(tabNav).toContain('export const SIMPLIFIED_WAREHOUSE_NAV = true;');
    expect(tabNav).toMatch(/!SIMPLIFIED_WAREHOUSE_NAV[\s\S]{0,200}name="WHInventoryTab"/);
  });

  /**
   * 「两个各自正确的改动叠出一条死路」的钉子。
   *
   * WHLocationManageScreen 里写死 navigate("WHTempMonitor") —— 那是**库存栈**里的
   * 注册名。首页栈里同一个屏原本叫 `TempMonitor`, 名字对不上, 那个按钮在新路径下
   * 就会跳到一个不存在的路由。
   */
  it('它跳出去的每一个路由, 在首页栈里都注册过', () => {
    const targets = [...locationScreen.matchAll(/navigation\.navigate\(\s*["']([A-Za-z0-9_]+)["']/g)]
      .map((m) => m[1]);
    expect(targets.length).toBeGreaterThan(0);   // 阳性对照: 正则真的抓到了东西
    // 收集所有断头路一次性报出来, 而不是第一条就停 —— 失败信息里直接看到是哪几个。
    const deadEnds = targets.filter((target) => !homeStack.includes(`name="${target}"`));
    expect({ deadEnds, checked: targets }).toEqual({ deadEnds: [], checked: targets });
  });
});
