import { readFileSync } from 'fs';
import { join } from 'path';

/**
 * 仓储 App 导航收敛（2026-08-21 owner 裁定）的契约。
 *
 * owner: 仓管在 App 上唯一要做的是「确认入库(含拍照)」和「发货」，不再让他查库存。
 * 5 Tab → 3 Tab（入库 / 发货 / 我的）。
 *
 * ⚠️ 本仓 RN 侧用 **jest**（web-admin 才是 vitest）——
 *    `expect(x, '消息')` 的第二参数 jest 不吃, 会整套编译失败 (Tests: 0 total)。
 *    写断言消息请用上一行注释。写这个文件时又栽了一次。
 *
 * 本文件钉三件事，每件都对应一个**已经踩过或差点踩到**的坑：
 *   ① 「我的」不许被一起砍掉 —— 它是登出/切账号的唯一出口。
 *   ② 隐藏 ≠ 删除 —— 两个 Stack 与其下的屏必须还在代码里。
 *   ③ 「库存不足 → 去调拨」的目的地必须跟着从首页栈搬走。
 *      ③ 是本次唯一的「两个各自正确的改动叠加出缺陷」点：
 *      隐藏首页 tab 是对的、那条出路也是对的，但先做完前者不做后者，
 *      跳转就会落到一个不存在的 tab 上，而**两个 diff 单看都没问题**。
 */

const SRC = join(__dirname, '../../../..', 'src');
const read = (p: string) => readFileSync(join(SRC, p), 'utf-8');

/**
 * 剥注释再扫。
 * ⛔ 不能省：本次的说明注释里原样写了 "WHHomeTab"、"TransferList"、"首页" 等词，
 * 不剥的话下面每条断言都会命中我自己写的注释（本仓 2026-08-20 一晚栽过四次）。
 * ⛔ 也不要为了让闸变绿去删注释 —— 该改的是尺子。
 */
const strip = (s: string) =>
  s
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '')  // JSX 注释 {/* ... */}
    .replace(/\/\*[\s\S]*?\*\//g, '')            // 块注释
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');       // 行注释

const tabNav = strip(read('navigation/WarehouseManagerTabNavigator.tsx'));

/**
 * 抽出所有 `{!SIMPLIFIED_WAREHOUSE_NAV && ( ... )}` 块里出现的 tab 名（按出现顺序）。
 * 按括号配平切块 —— ⛔ 不用「离得近不近」来判断归属。
 */
function guardedTabs(): string[] {
  const names: string[] = [];
  const marker = '{!SIMPLIFIED_WAREHOUSE_NAV && (';
  let from = 0;
  for (;;) {
    const start = tabNav.indexOf(marker, from);
    if (start < 0) break;
    let depth = 0;
    let end = start;
    for (let i = start; i < tabNav.length; i += 1) {
      if (tabNav[i] === '{') depth += 1;
      else if (tabNav[i] === '}') {
        depth -= 1;
        if (depth === 0) { end = i; break; }
      }
    }
    if (end <= start) throw new Error('守卫块的大括号没有配平，块没抽全');
    for (const m of tabNav.slice(start, end).matchAll(/name="(\w+Tab)"/g)) {
      if (m[1]) names.push(m[1]);
    }
    from = end;
  }
  if (!names.length) {
    throw new Error(
      '前提不成立: 一个守卫块都没抽到 —— 这条判据没有量到任何东西，'
      + '不要把它读成「通过」。收敛开关的写法可能变了，先修这条判据。',
    );
  }
  return names;
}
const outboundStack = strip(read('navigation/warehouse/WHOutboundStackNavigator.tsx'));
const inboundStack = strip(read('navigation/warehouse/WHInboundStackNavigator.tsx'));
const inboundScreen = strip(read('screens/warehouse/inbound/WHInboundListScreen.tsx'));
const confirmScreen = strip(read('screens/warehouse/outbound/WHDeliveryConfirmScreen.tsx'));

describe('仓储导航收敛', () => {
  it('前提: 收敛开关存在且开着（否则下面几条在量未收敛的状态）', () => {
    // 阳性对照。开关被改回 false 时这条先红，提醒读者「下面的断言语义变了」。
    expect(tabNav).toContain('export const SIMPLIFIED_WAREHOUSE_NAV');
    expect(tabNav).toMatch(/SIMPLIFIED_WAREHOUSE_NAV\s*=\s*true/);
  });

  it('首页与库存两个 Tab 被开关挡住', () => {
    for (const tab of ['WHHomeTab', 'WHInventoryTab']) {
      const at = tabNav.indexOf(`name="${tab}"`);
      // 找不到 = 被删了, 而收敛的判据是「隐藏而非删除」
      expect(at).toBeGreaterThan(0);
      // 往前找最近的守卫，确认这个 Screen 在收敛开关的否定分支里。
      const guard = tabNav.lastIndexOf('!SIMPLIFIED_WAREHOUSE_NAV', at);
      // 没找到守卫 = 这个 Screen 是无条件渲染的, 收敛没生效
      expect(guard).toBeGreaterThan(0);
      // 离得太远 = 匹配到的是别处的守卫, 不是管着这个 Screen 的那个
      expect(at - guard).toBeLessThan(400);
    }
  });

  it('入库 / 发货 / 我的 三个 Tab 无条件保留', () => {
    for (const tab of ['WHInboundTab', 'WHOutboundTab', 'WHProfileTab']) {
      expect(tabNav).toContain(`name="${tab}"`);
    }
    // ⛔ 「我的」尤其不许被顺手砍掉：它是登出/切账号的唯一出口，
    //    砍了等于把用户锁死在一个账号里（防呆 Rule 5：dead-end 必须有出路）。
    //
    // 判据用**结构**而不是位置距离：把每个 `{!SIMPLIFIED_WAREHOUSE_NAV && ( ... )}`
    // 块整个抽出来，看里面到底包着哪几个 tab。
    // ⚠️ 第一版写的是「WHProfileTab 之前最近的守卫要早于 WHOutboundTab」——
    //    那条恰好被库存 tab 的守卫顶掉而误红。位置距离答的是「谁离得近」，
    //    而我想问的是「它在不在某个守卫块里面」，是两个问题。
    expect(guardedTabs()).toEqual(['WHHomeTab', 'WHInventoryTab']);
  });

  it('落地页是入库 —— 打开就是要干的活', () => {
    expect(tabNav).toMatch(/initialRouteName=\{[^}]*"WHInboundTab"/);
  });

  it('库存 Tab 隐藏时，生产成品实收仍从入库首页可达', () => {
    expect(inboundStack).toContain('name="WHTransitLedger"');
    expect(inboundScreen).toContain('testID="wh-production-receipt-entry"');
    expect(inboundScreen).toContain('navigation.navigate("WHTransitLedger")');
    expect(inboundScreen).toContain('生产成品入库');
  });

  it('⚠️ 去调拨的目的地已迁进发货栈, 且跳转不再依赖首页 tab', () => {
    // 目的地在发货栈里
    expect(outboundStack).toContain('name="TransferList"');
    expect(outboundStack).toContain('name="TransferDetail"');
    // 跳转改成同栈直跳
    expect(confirmScreen).toContain("navigation.navigate('TransferList')");
    // 阴性对照: ⛔ 不许再有指向首页 tab 的跨栈跳转（首页已经不在导航里了）
    // 还在跳 WHHomeTab = 首页已隐藏, 这个跳转会落空
    expect(confirmScreen).not.toContain('WHHomeTab');
  });

  it('隐藏不是删除: 两个 Stack 的实现文件都还在', () => {
    // 只要能读出来且非空就算在。⛔ 收敛是产品裁定, 不是删功能;
    // owner 原话「先把它隐藏起来, 我们看看效果」。
    expect(read('navigation/warehouse/WHHomeStackNavigator.tsx').length).toBeGreaterThan(200);
    expect(read('navigation/warehouse/WHInventoryStackNavigator.tsx').length).toBeGreaterThan(200);
  });
});

describe('采购单品名字段', () => {
  const client = read('services/api/purchaseApiClient.ts');

  it('读的是后端真的会发的那个名字', () => {
    /*
     * 🔴 后端 `by-status` 直接序列化实体, 字段叫 materialName
     * (PurchaseOrderItem.java:49 / PurchaseReceiveItem.java:59)。
     * RN 原来读 materialTypeName —— 一个后端从不发的名字, 且声明成可选(`?`),
     * 于是类型检查一路绿, 界面恒显示兜底的「未知材料」。
     * ▎可选字段 + 兜底文案 = 字段名写错不报错, 只变成一个看起来合理的显示值。
     */
    expect(strip(client)).toContain('materialName?: string');
    // 两个名字并存就是形态 D —— 下一个人还会挑错那个
    expect(strip(client)).not.toContain('materialTypeName');
  });

  it('扫码收货填单屏读到了品名（仓管唯一要做的那件事）', () => {
    const receipt = strip(read('screens/warehouse/inbound/WHReceiptCreateScreen.tsx'));
    // 这个屏原来有 8 处读错字段, 是全仓最多的一个。
    expect(receipt).toContain('materialName');
    expect(receipt).not.toContain('materialTypeName');
  });
});
