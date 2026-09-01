import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 用户点菜单里的「X」，落地页的面包屑就该说「X」。
 *
 * <h2>🔴 2026-08-28 逐屏走查抓到</h2>
 *
 * f006 →「工作台 / 质检主管工作台」，落地后：
 *
 * <pre>
 *   面包屑      质量主管工作台     ← **另一个菜单项**的名字（/workdesk/quality-manager）
 *   页面标题    质检组长工作台
 *   左侧选中    质检主管工作台
 * </pre>
 *
 * **同一个屏三个名字**，而面包屑指着的是别的屏。真因是 `router/index.ts` 里那个块
 * 整段复制自 quality-manager —— 连上面那行注释都是。
 *
 * <h2>为什么会漂：三份独立来源</h2>
 *
 * <pre>
 *   面包屑    ← router 的 meta.title      (AppHeader: route.matched.map(r => r.meta.title))
 *   菜单标签  ← components/layout/menuConfig.ts
 *   页面标题  ← 各 .vue 自己 template 里写死的
 * </pre>
 *
 * ⚠️ `router/index.ts` 里已有一段注释写着「菜单 module ≠ 路由 module 是一整类漂移，
 * 而漂移扫描器**只读路由不读菜单**（它自己文件头写明的盲区）」——
 * **本闸补的就是那个盲区**，只是量的是 title 而不是 module。
 *
 * <h2>为什么是棘轮而不是硬红</h2>
 *
 * 建闸当天 20 处不一致。其中 4 处是**面包屑指向了别的东西**（已在同 PR 修掉）：
 *
 * <pre>
 *   /workdesk/quality-chief   质检主管工作台  vs  质量主管工作台   ← 另一个菜单项的名字
 *   /system/products          SKU 管理        vs  产品信息管理     ← 该屏横幅正在防这个泛称
 *   /crm                      会员与营销      vs  CRM 商机         ← 另一个概念
 *   /finance/invoices         销售开票        vs  开票管理         ← 丢了「销售」, 同菜单下还有采购发票
 * </pre>
 *
 * 其余 16 处是**措辞漂移**（「Excel 上传」/「Excel上传」、「调拨单」/「调拨单列表」），
 * 各自都指同一个东西。一次性硬红 16 条会让这道闸当天被关掉
 * ——「宁可窄而可信，不要宽而被关掉」⇒ 冻成基线，只禁增长。
 *
 * ⛔ 往 BASELINE 里加新条目 = 承认又漂了一处。加之前先问：能不能直接改成一致？
 */

const ROOT = resolve(__dirname, '../..');

/**
 * 建闸当天已存在的措辞漂移。**只减不增。**
 * key 是完整路由路径，value 是 `[菜单标签, 面包屑]`。
 */
const BASELINE: Record<string, [string, string]> = {
  '/analytics/alert-dashboard': ['异常预警', '生产异常预警'],
  '/analytics/supply-chain': ['进销存总览', '进销存闭环总览'],
  '/calibration/list': ['行为校准', '校准列表'],
  '/dashboard/production-progress': ['生产进度大屏', '生产进度看板'],
  '/finance/voucher-list': ['会计凭证', '凭证列表'],
  // 2026-09-02: '/production/labor-efficiency' 这一条已删除 —— LIUSHANMEN 生产
  // 管理菜单收拢 (owner 拍板) 把它从 menuConfig 里移除、合并进
  // /production/analysis-query 的「人效双口径对比」tab。路径不再在
  // 「菜单 ∩ router」交集里，本闸的棘轮检测会正确判定这条基线已过期，所以删掉，
  // ⛔ 不留着当免检条目 (与 08-28 warehouse/semi-finished 那条同一处理方式)。
  '/rd/samples': ['研发样品', '研发样品管理'],
  '/restaurant/admin/etl-status': ['ETL 状态', '餐饮 ETL 状态'],
  '/smart-bi/analysis': ['AI 问答 / 数据分析', '智能数据分析'],
  '/smart-bi/query-templates': ['查询模板', '查询模板管理'],
  '/smart-bi/upload': ['Excel 上传', 'Excel上传'],
  '/system/skill-tools': ['Skill/Tool治理', 'Skill/Tool 治理'],
  '/transfer/list': ['调拨单', '调拨单列表'],
  // 2026-09-01: '/warehouse/semi-finished' 这一条已删除 —— LIUSHANMEN 仓储
  // 「库存盘点」菜单收拢 (owner 拍板) 把它从 menuConfig 里移除、合并进
  // /warehouse/inventory-query 的「半成品库存」tab；同一 PR 顺手把 route
  // meta.title 也从 '半成品重量库存' 改成了 '半成品库存'，与菜单曾经的说法
  // 收敛 (硬约束 4)。路径不再在「菜单 ∩ router」交集里，本闸的棘轮检测会正确
  // 判定这条基线已过期，所以删掉，⛔ 不留着当免检条目。
  '/warehouse/storage-locations': ['仓储空间与现场盘点', '储存位置与现场盘点'],
};

/** 菜单里的 `{ path, title }`。 */
function menuTitles(): Map<string, string> {
  const src = readFileSync(resolve(ROOT, 'components/layout/menuConfig.ts'), 'utf8');
  const out = new Map<string, string>();
  for (const m of src.matchAll(/path:\s*'([^']+)'\s*,\s*title:\s*'([^']+)'/g)) {
    out.set(m[1].replace(/\/$/, '') || '/', m[2]);
  }
  return out;
}

/**
 * router 的完整路径 → meta.title。
 *
 * ⚠️ **用 router 自己的 `getRoutes()`，不用正则猜嵌套。**
 * 第一版我用正则按「路径末段」匹配，`list` / `orders` 这种末段在几十条路由里重复
 * ⇒ 量出 30 处不一致，其中 `/equipment/list → 调拨单列表`、`/sales/orders → 采购订单`
 * 全是匹配器抓错了对象。换成权威路由表之后是 20 处。
 * **「我想知道的」是 X，「我实际在量的」曾经是 Y。**
 */
async function routerTitles(): Promise<Map<string, string>> {
  const mod: Record<string, unknown> = await import('@/router/index');
  const r = (mod.default ?? mod.router) as { getRoutes(): Array<{ path: string; meta?: { title?: string } }> };
  const out = new Map<string, string>();
  for (const route of r.getRoutes()) {
    const t = route.meta?.title;
    if (typeof t === 'string' && t) out.set(route.path.replace(/\/$/, '') || '/', t);
  }
  return out;
}

describe('面包屑(router meta.title) 与菜单标签一致', () => {
  it('阳性对照: 两份都真的读到了(否则下面的比对是空集上的恒真式)', async () => {
    const menu = menuTitles();
    const router = await routerTitles();
    expect(menu.size, 'menuConfig 一条都没解析出来').toBeGreaterThan(100);
    expect(router.size, 'router 一条带 title 的都没有').toBeGreaterThan(100);
    const both = [...menu.keys()].filter(p => router.has(p));
    expect(both.length, '两份的交集为空 ⇒ 主断言恒绿').toBeGreaterThan(100);
    // 本 PR 修掉的那 4 处, 必须真的在比对范围里 —— 否则「修好了」是不可判定的
    for (const p of ['/workdesk/quality-chief', '/system/products', '/crm', '/finance/invoices']) {
      expect(both, `${p} 应在比对范围内`).toContain(p);
    }
  });

  it('本 PR 修掉的 4 处「面包屑指向别的东西」, 现在一致', async () => {
    const menu = menuTitles();
    const router = await routerTitles();
    for (const [p, why] of [
      ['/workdesk/quality-chief', '原值是另一个菜单项 /workdesk/quality-manager 的名字'],
      ['/system/products', '该屏横幅正在防「SKU vs 采购原料」的混淆, 而面包屑用的就是那个泛称'],
      ['/crm', '「CRM 商机」与「会员与营销」是两个概念'],
      ['/finance/invoices', '丢了「销售」这个限定 —— 同菜单下还有采购发票'],
    ] as const) {
      expect(router.get(p), `${p}: ${why}`).toBe(menu.get(p));
    }
  });

  it('⛔ 不许出现新的名字漂移(存量已冻结, 只减不增 —— ⛔ 别把条数写死在标题里, 它会随棘轮失真)', async () => {
    const menu = menuTitles();
    const router = await routerTitles();
    const fresh: string[] = [];
    const healed: string[] = [];
    for (const [path, mTitle] of menu) {
      const rTitle = router.get(path);
      if (!rTitle) continue;                       // 菜单有而 router 无(动态段/别名), 不是本闸的事
      const known = BASELINE[path];
      if (rTitle === mTitle) {
        if (known) healed.push(`${path}  (${known[0]} / ${known[1]})`);
        continue;
      }
      if (known && known[0] === mTitle && known[1] === rTitle) continue;   // 已登记的措辞漂移
      fresh.push(`${path}\n      菜单  : ${mTitle}\n      面包屑: ${rTitle}`);
    }
    expect(
      fresh,
      '用户点了菜单里的一个名字, 落地页的面包屑该说同一个名字。\n' +
        '面包屑来自 router 的 meta.title, 菜单来自 menuConfig —— 两份独立, 会漂。\n' +
        '2026-08-28 实测有一处漂成了**另一个菜单项的名字**(/workdesk/quality-chief\n' +
        '显示成「质量主管工作台」), 因为那个路由块整段复制自 quality-manager。\n' +
        '⇒ 改一边就要改另一边。⛔ 往 BASELINE 里加条目 = 承认又漂了一处。\n' +
        fresh.join('\n'),
    ).toEqual([]);
    // 棘轮的另一半: 修好了就从基线里删掉, 不许留着当豁免
    expect(
      healed,
      '这些已经一致了, 但还留在 BASELINE 里 —— 删掉它们, 否则基线会慢慢变成一张免检清单。\n' +
        healed.join('\n'),
    ).toEqual([]);

    // 🔴 棘轮的第三个洞: 基线条目指向的路径如果**不再存在**(改路径 / 删菜单项),
    //    上面两条检查都够不着它 —— `router.get(path)` 返回 undefined 就 continue 了。
    //    于是基线只增不减地烂掉, 而「16 处已知漂移」这个数字慢慢变成假的。
    //    ⚠️ 这个洞是变异时发现的: 我拿一条基线项做变异, 改了半天没反应,
    //      因为那条路由根本不在我以为的文件里(router 是拆分的, modules/*.ts)。
    const stale = Object.keys(BASELINE).filter(p => !menu.has(p) || !router.has(p));
    expect(
      stale,
      'BASELINE 里这些路径已经不在「菜单 ∩ router」里了(改了路径? 删了菜单项?)。\n' +
        '留着它们会让「已知漂移只有 N 处」这个数字慢慢失真 ⇒ 删掉。\n' +
        stale.join('\n'),
    ).toEqual([]);
  });
});
