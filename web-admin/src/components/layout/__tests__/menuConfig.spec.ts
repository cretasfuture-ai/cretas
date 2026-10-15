import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import { financeManagerMenu, menuConfig, type MenuItem } from '../menuConfig';
import router from '@/router';

function findGroup(path: string): MenuItem | undefined {
  return menuConfig.find((m) => m.path === path);
}

function collectPaths(items: MenuItem[] = []): string[] {
  return items.flatMap((item) => [
    item.path,
    ...collectPaths(item.children),
  ]);
}

function descendantPaths(groupPath: string): string[] {
  return collectPaths(findGroup(groupPath)?.children);
}

function findDescendant(groupPath: string, path: string): MenuItem | undefined {
  const visit = (items: MenuItem[] = []): MenuItem | undefined => {
    for (const item of items) {
      if (item.path === path) return item;
      const found = visit(item.children);
      if (found) return found;
    }
    return undefined;
  };
  return visit(findGroup(groupPath)?.children);
}

describe('menuConfig — baseline structure (pre-merge)', () => {
  it('exports a non-empty menuConfig array', () => {
    expect(Array.isArray(menuConfig)).toBe(true);
    expect(menuConfig.length).toBeGreaterThan(0);
  });

  it('每个顶级项有 path/title/module', () => {
    for (const item of menuConfig) {
      expect(item.path, `item missing path`).toBeTruthy();
      expect(item.title, `${item.path} missing title`).toBeTruthy();
      expect(item.module, `${item.path} missing module`).toBeTruthy();
    }
  });

  it('当前存在 /restaurant 组 (回归基线)', () => {
    expect(findGroup('/restaurant')).toBeDefined();
  });
});

describe('menuConfig - Liushanmen department workflow entries', () => {
  function childPaths(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.map((c) => c.path) ?? [];
  }

  function childTitles(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.map((c) => c.title) ?? [];
  }

  function childGroupLabels(groupPath: string): string[] {
    return findGroup(groupPath)?.children?.filter((c) => c.groupLabel).map((c) => c.groupLabel!) ?? [];
  }

  it('keeps sales and purchase approval actions in personal OA, not module navigation', () => {
    expect(childPaths('/sales')).not.toContain('/sales/finance-review');
    expect(childPaths('/finance')).not.toContain('/sales/finance-review');
    // 2026-08-16: 采购那条恒空且按钮打 410, 已整条移除; 阴性对照钉住它别回来。
    expect(childPaths('/procurement')).not.toContain('/procurement/finance-review');
    expect(childPaths('/finance')).not.toContain('/procurement/finance-review');
  });

  it('exposes the manufacturing purchase-requisition flow from the procurement menu', () => {
    expect(childPaths('/procurement')).toEqual(expect.arrayContaining([
      '/procurement/requisitions/my',
      '/procurement/requisitions/pending-approval',
      '/procurement/requisitions',
      '/procurement/orders',
    ]));
    expect(childTitles('/procurement')).toEqual(expect.arrayContaining([
      '我的请购',
      '待审批请购',
      '全部请购单',
      '采购订单',
    ]));
    expect(findDescendant('/procurement', '/procurement/requisitions/pending-approval')?.roles)
      .toContain('procurement_manager');
    expect(findDescendant('/procurement', '/procurement/requisitions/my')?.roles)
      .toContain('procurement_manager');
  });

  it('keeps account and department management under HR, with workflow setup in system', () => {
    expect(childPaths('/hr')).toEqual(expect.arrayContaining([
      '/system/users',
      '/system/roles',
      '/hr/departments',
    ]));
    expect(childPaths('/system')).not.toContain('/system/users');
    expect(childPaths('/system')).not.toContain('/system/roles');
    expect(childPaths('/system')).toEqual(expect.arrayContaining([
      '/system/approval-chains',
      '/system/workflow-designer',
      '/canvas-editor',
    ]));
  });

  // 🔴 缺陷 1 (2026-09-01, LIUSHANMEN 实测): URL 前缀 /system、面包屑「系统管理」、
  // 侧边栏「人事管理」三处各说各话, owner 按「系统管理」找账号管理找不到, 最后从
  // 人事管理点进去, 而进去之后面包屑写着「系统管理 / 账号管理」。
  //
  // owner 拍板: 归属以**侧边栏**为准 (建号/改角色是 HR 的活), 面包屑跟随它, URL 不动
  // (/system/users 是存量深链/书签/E2E 用了很久的地址)。
  // ⇒ 路由用 meta.breadcrumbParent 声明「我在导航里的上一级」, AppHeader 以它为准。
  //
  // ── 2026-09-01 第二轮: 本闸从「账号管理两条」扩到**全表** ──────────────────
  //
  // ⚠️ 判据换了, 口径必须一起说清, 否则两轮的数字没法比:
  //   #432 建闸时量的是「URL 前缀 != 侧边栏组的 path 前缀」, 注释里写「18 条」——
  //   **实测是 20 条**(#432 只给 menuConfig.ts 加了 10 行注释, 没动结构, 所以当时
  //   也是 20; 那个 18 是写错的, 不是后来漂的)。
  //
  //   更要紧的是: 那个判据量的不是缺陷本身。20 条里有 8 条是假阳性 —— 例如
  //   /analytics/overview 挂在「数据与分析」组下, URL 前缀 /analytics 与组 path
  //   /smart-bi 不同, 可它渲染出来的面包屑上一级**正好就是**「数据与分析」,
  //   用户看不到任何矛盾, 给它加 breadcrumbParent 是个 no-op。
  //
  // ⇒ 本闸改成量**用户真看得见的那件事**: 渲染出来的面包屑上一级, 必须是用户在
  //   侧边栏里找得到的名字 —— 组名(如「仓储管理」), 或它所在的 groupLabel 分段名
  //   (如「生产分析」; SidebarMenuNode.vue 把 groupLabel 渲染成分段小标题)。
  //   两者都不是, 才算漂。
  //
  //   换判据的实测: 20 条前缀漂 → 9 条真漂, 且**前缀判据一条真漂都没漏**
  //   (「前缀不漂但面包屑漂」的交集实测 0 条)。9 条里 7 条已按 (a) 修, 2 条登记待定。
  //
  // ⛔ 不再用源码 grep 定位路由。#432 那版靠 routerSource.indexOf(`path: '<叶子>'`),
  //    扩到全表就会读错行: router/index.ts 里 path: 'list' 有 3 处(调拨/校准/…),
  //    而 /production-analytics/* 根本不在 index.ts 里(在 router/modules/ 下)。
  //    改用 router.resolve() 直接问路由表 —— 与 AppHeader 读的是同一个 meta。

  // 侧边栏归属对、只是面包屑没跟上的, 一律加 meta.breadcrumbParent (见 router/index.ts)。
  // 归属**本身**存疑的登记在这里, 每条写明理由, 交 owner 定。
  // ⛔ 登记是留痕, 不是豁免 —— 也不许用通配符或放宽判据把它们盖过去。
  const BREADCRUMB_DRIFT_ACCEPTED: Record<string, string> = {
    // 2026-09-01: 曾登记 2 条, owner 都已裁定, 所以现在空着:
    //   · /rd/samples                  → A2 研发重新独立成组 (menuConfig 的 /rd 组)
    //   · /procurement/requisitions/my → B1 餐饮侧改走 /restaurant/purchase-plan
    //
    // ⚠️ 空表意味着下面「登记项必须确实还在漂」那条阳性对照**一条都跑不到**。
    //    没有别的对照的话, 这道闸在「0 条漂移」的日子里和一个恒真式长得一模一样 ——
    //    所以测试体里另外补了两组构造输入的阳性对照。⛔ 别把它们删了。
  };

  it('全表: 面包屑渲染出来的上一级, 必须是用户在侧边栏里找得到的名字', () => {
    // 仪器: 直接问路由表, 与 AppHeader.vue 读的是同一个 meta。
    const renderedParent = (path: string): string | null => {
      const r = router.resolve(path);
      const titled = r.matched.filter((m) => m.meta?.title);
      const override = r.meta.breadcrumbParent as string | undefined;
      if (override) return override;
      // 只有一级 ⇒ 面包屑不显示上一级。那是「没说」, 不是「说错」, 不构成矛盾。
      if (titled.length < 2) return null;
      return (titled[titled.length - 2].meta.title as string) ?? null;
    };

    // 覆盖面自查: 菜单当前是两级(组 > 叶)。哪天多出第三级, 本闸就只扫到了一部分 ——
    // 那时要先改仪器, ⛔ 不能让它安静地少扫。
    for (const g of menuConfig) {
      for (const c of g.children ?? []) {
        expect((c.children ?? []).length, `${c.path} 有下级菜单 —— 本闸只走两级, 覆盖面不够了`).toBe(0);
      }
    }

    // 阳性对照(一): 判据本身既要能亮红, 也要能正确放行。
    // ⚠️ 只定义一次, 主循环也用它 —— 判据有两份就会漂, 那时对照守的就不是真正在跑的那份。
    const isDrift = (crumb: string, findable: string[]) => !findable.includes(crumb);
    expect(isDrift('系统管理', ['生产管理', '生产配置']), '判据红不起来').toBe(true);
    expect(isDrift('生产管理', ['生产管理', '生产配置']), '判据把组名误判成漂').toBe(false);
    expect(isDrift('生产配置', ['生产管理', '生产配置']), '判据把分段名误判成漂').toBe(false);

    // 阳性对照(二): 仪器确实在读 meta.breadcrumbParent, 不是永远返回路由父级。
    // /system/users 的路由父级是「系统管理」, 而它声明了 breadcrumbParent: '人事管理'。
    expect(renderedParent('/system/users'), 'breadcrumbParent 没被读到 —— 仪器坏了').toBe('人事管理');

    const drifting: { path: string; group: string; crumb: string }[] = [];
    for (const g of menuConfig) {
      let section: string | null = null; // groupLabel 是分段小标题, 向下继承到下一个 label
      for (const c of g.children ?? []) {
        if (c.groupLabel) section = c.groupLabel;
        const r = router.resolve(c.path);
        expect(
          r.matched.length,
          `${c.path} 在路由表里解析不到 —— 要么仪器坏了, 要么这个菜单项指向死链`,
        ).toBeGreaterThan(0);
        const crumb = renderedParent(c.path);
        if (crumb === null) continue;
        const findable = [g.title, section].filter(Boolean) as string[];
        if (isDrift(crumb, findable)) drifting.push({ path: c.path, group: g.title, crumb });
      }
    }

    // 阳性对照: 登记的例外必须**确实还在漂**。
    // 修好了却留在名单里 = 名单开始烂, 而烂掉的名单会把整道闸的可信度一起带走。
    for (const path of Object.keys(BREADCRUMB_DRIFT_ACCEPTED)) {
      expect(
        drifting.some((d) => d.path === path),
        `${path} 已经不漂了 —— 请把它从 BREADCRUMB_DRIFT_ACCEPTED 里删掉`,
      ).toBe(true);
    }

    const unregistered = drifting.filter((d) => !(d.path in BREADCRUMB_DRIFT_ACCEPTED));
    expect(
      unregistered.map((d) => `${d.group}|${d.path}|面包屑写的是「${d.crumb}」`),
      `以下菜单项渲染出来的面包屑上一级, 用户在侧边栏里根本找不到 ——\n` +
        `  ${unregistered.map((d) => `${d.group}|${d.path}|面包屑「${d.crumb}」`).join('\n  ')}\n\n` +
        `用户会像 owner 在 LIUSHANMEN 那样: 照面包屑那个名字去侧边栏找, 找不到。\n` +
        `修法二选一:\n` +
        `  (a) 侧边栏归属是对的 → 给该路由 meta 加 breadcrumbParent: '<它在侧边栏里的组名>'\n` +
        `  (b) 归属本身存疑 → 登记进 BREADCRUMB_DRIFT_ACCEPTED 并写明理由, 交 owner 定`,
    ).toEqual([]);
  });


  // 2026-09-01 收拢：'/warehouse/inventory'（库存批次查询）与 '/warehouse/stocktakes'
  // （盘点与期初库存）不再是各自独立的菜单项，见下方「库存盘点合并」describe 块。
  // 本条改为钉住它们各自所在 hub 的菜单项 + 标题。
  it('exposes the inventory-query and stocktake-query hub entries under warehouse', () => {
    expect(childPaths('/warehouse')).toEqual(expect.arrayContaining([
      '/warehouse/inventory-query',
      '/warehouse/stocktake-query',
    ]));
    expect(childTitles('/warehouse')).toEqual(expect.arrayContaining([
      '库存查询',
      '盘点',
    ]));
  });

  it('groups workdesks by management and execution roles without duplicate quality titles', () => {
    expect(childGroupLabels('/workdesk')).toEqual(['经营管理', '一线执行']);
    expect(childTitles('/workdesk')).toEqual([
      '销售老板工作台',
      '财务主管工作台',
      '质量主管工作台',
      '生产经理工作台',
      '仓管员工作台',
      '采购员工作台',
      '质检主管工作台',
    ]);
  });

  it('uses clear HR menu groups to avoid account/profile/onboarding duplication', () => {
    const hr = findGroup('/hr')!;
    expect(hr.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '账号权限',
      '员工组织',
      '入职考勤',
    ]);
    expect(childTitles('/hr')).toEqual([
      '账号管理',
      '角色权限',
      // ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906: 权限矩阵只读页, 挂在「账号权限」组里
      '权限矩阵',
      '员工档案',
      '部门管理',
      '工种字典',
      '考勤管理',
      '账号邀请',
    ]);
  });

  it('keeps production master data under production instead of system', () => {
    // 2026-09-02 LIUSHANMEN 生产管理菜单收拢: 工序管理/产品-工序配置已合并进
    // /production/process-hub (el-tabs hub, 见 productionHubs.mount.spec.ts)。
    // 旧的 /system/work-processes、/system/product-processes 不再是独立菜单项
    // (仍是路由, 只是改成 redirect —— 见 productionHubRedirects.spec.ts)。
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/system/products',
      '/production/process-hub',
    ]));
    expect(childPaths('/system')).not.toContain('/system/products');
    expect(childPaths('/system')).not.toContain('/system/work-processes');
    expect(childPaths('/system')).not.toContain('/system/product-processes');
    // 阴性对照: 旧的两条独立入口不该再出现在 menuConfig 任何位置 (只应作为 redirect
    // 留在 router 里)。
    expect(descendantPaths('/production')).not.toContain('/system/work-processes');
    expect(descendantPaths('/production')).not.toContain('/system/product-processes');
  });

  it('groups production with inline section labels instead of nested lanes', () => {
    const production = findGroup('/production')!;
    expect(production.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '生产过程',
      '生产配置',
      '生产分析',
    ]);
    expect(production.children!.map((c) => c.path)).not.toContain('/production/process');
    expect(production.children!.map((c) => c.path)).not.toContain('/production/config');
    expect(production.children!.map((c) => c.path)).not.toContain('/production/analysis');
  });

  it('keeps plans, batches, and reporting approval together in production process', () => {
    // 2026-09-02 LIUSHANMEN 生产管理菜单收拢: 报工审批+撤回审批合并为一个 hub,
    // ⛔ 路径仍是 /production/approval (reportApprovalMenuMatchesBackendRoles.spec.ts
    // 按精确路径查找)。/production/reversals 不再是独立菜单项 (仍是路由 redirect)。
    // 生产看板/领料 两个新 hub 也在同一段落里。
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/production/plans',
      '/production/batches',
      '/production/kanban',
      '/production/approval',
      '/production/material-flow',
    ]));
    expect(descendantPaths('/production')).not.toContain('/production/reversals');
    expect(descendantPaths('/production')).not.toContain('/production/pending-queue');
    expect(descendantPaths('/production')).not.toContain('/production/restock-board');
    expect(descendantPaths('/production')).not.toContain('/production/delivery-warnings');
    expect(descendantPaths('/production')).not.toContain('/production/material-requisitions');
    expect(descendantPaths('/production')).not.toContain('/production/material-returns');
  });

  it('moves production analytics out of SmartBI sidebar duplication', () => {
    // 2026-09-02 LIUSHANMEN 生产管理菜单收拢: 6 个生产分析视角合并进
    // /production/analysis-query 这一个 hub (el-tabs)。/production-analytics/cost-summary
    // 不在合并范围内 (owner 拍板的合并表未列它), 仍是独立菜单项。
    expect(descendantPaths('/production')).toEqual(expect.arrayContaining([
      '/production/analysis-query',
      '/production-analytics/cost-summary',
    ]));
    const smartBiPaths = descendantPaths('/smart-bi');
    for (const path of [
      '/production/analysis-query',
      '/production-analytics/cost-summary',
    ]) {
      expect(smartBiPaths).not.toContain(path);
    }
    // 阴性对照: 6 个已合并视角里的另外 5 个不该再是独立菜单项 (仍是 router redirect)。
    for (const path of [
      '/analytics/production-report',
      '/production-analytics/production',
      '/production/bom-achievement',
      '/production/process-io',
      '/production/labor-efficiency',
      '/production-analytics/yield-cost',
    ]) {
      expect(descendantPaths('/production')).not.toContain(path);
      expect(smartBiPaths).not.toContain(path);
    }
  });

  it('groups warehouse with inline section labels instead of nested lanes', () => {
    const warehouse = findGroup('/warehouse')!;
    expect(warehouse.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '仓储作业',
      '库存盘点',
      '仓储配置',
      '仓储分析',
    ]);
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/operations');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/inventory-work');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/config');
    expect(warehouse.children!.map((c) => c.path)).not.toContain('/warehouse/analysis');
  });

  it('keeps warehouse operations together under the first inline section', () => {
    expect(findGroup('/warehouse')!.children!.slice(0, 5).map((c) => c.path)).toEqual([
      '/warehouse/unordered-inbound-applications',
      '/warehouse/materials',
      '/warehouse/shipments',
      '/transfer/list',
      '/warehouse/wastage-reports',
    ]);
  });

  // 2026-09-01 收拢：分仓库存查询/总库存查询/库存批次查询三项不再各自独立，
  // 已合并进 /warehouse/inventory-query hub（见下方「库存盘点合并」describe 块）。
  it('keeps stock query hub, dict config, and price analysis discoverable under warehouse', () => {
    expect(descendantPaths('/warehouse')).toEqual(expect.arrayContaining([
      '/warehouse/inventory-query',
      '/warehouse/material-types',
      '/warehouse/material-segments',
      '/warehouse/material-price-trend',
    ]));
  });

  it('groups procurement into execution and supplier-price sections', () => {
    // 2026-08-16: 「采购审批」这一组的唯一成员是 /procurement/finance-review,
    // 它恒空且按钮打 410, 已整条移除 —— 组随之消失, 不是漏配。
    // 采购的审批入口现在是菜单里的「待我审批」(/workflow/pending)。
    expect(childGroupLabels('/procurement')).toEqual([
      '请购流程',
      '采购执行',
      '供应商与价格',
    ]);
    expect(childGroupLabels('/procurement')).not.toContain('采购审批');
    expect(childPaths('/procurement')).toEqual([
      '/procurement/requisitions/my',
      '/procurement/requisitions/pending-approval',
      '/procurement/requisitions',
      '/procurement/orders',
      '/procurement/suppliers',
      '/procurement/price-lists',
    ]);
  });

  it('does not expose purchase receiving as a procurement write entry', () => {
    expect(childPaths('/procurement')).not.toContain('/procurement/receives');
  });

  it('keeps sales business and configuration sections after approvals move to OA', () => {
    expect(childGroupLabels('/sales')).toEqual([
      '销售业务',
      '销售配置',
    ]);
    expect(childPaths('/sales')).toEqual([
      '/sales/orders',
      '/sales/customers',
      '/sales/shipments',
      '/sales/returns',
      '/sales/payment-requests',
      '/sales/vehicles',
    ]);
  });

  it('groups finance into review, accounting, analysis, and configuration sections', () => {
    // 2026-08-27: 新增「会计凭证」分组 —— 那三个页面此前【菜单零登记、App 也没有】,
    //   而生产库已有 凭证 18 张 / 分录 46 条 / 导出记录 106 条 ⇒ 凭证在生成、有人在导,
    //   却没有任何界面能看。补入口同时把可达性棘轮从 91 调到 88。
    expect(childGroupLabels('/finance')).toEqual([
      '审核队列',
      '财务核算',
      '会计凭证',
      '会计凭证',
      '会计凭证',
      '财务分析',
      '财务配置',
    ]);
    expect(childPaths('/finance')).toEqual([
      '/finance/adjustments',
      '/finance/costs',
      '/finance/three-statements',
      '/finance/reports',
      '/finance/ar-ap',
      '/finance/invoices',
      '/finance/purchase-invoices',
      '/finance/payments',
      '/finance/voucher-list',
      '/finance/voucher-export',
      '/finance/voucher-template',
      '/finance/sku-margin',
      '/finance/gross-margin-redline',
    ]);
  });

  it('groups quality, equipment, scheduling, and system modules with inline labels', () => {
    expect(childGroupLabels('/quality')).toEqual(['质量检验', '处置闭环', '质量配置']);
    expect(childPaths('/quality')).toContain('/quality/label-qc');
    expect(childPaths('/quality')).toContain('/quality/disposition');
    expect(childTitles('/quality')).toContain('包装标签拍检');
    expect(findDescendant('/quality', '/quality/disposition')?.roles).toEqual([
      'quality_manager',
      'factory_super_admin',
      'platform_admin',
      'permission_admin',
    ]);
    expect(childGroupLabels('/equipment')).toEqual(['设备台账', '维护监控']);
    expect(childGroupLabels('/scheduling')).toEqual(['日常调度', '基础资料', '工厂智能调度', '资源与预警', '调度配置']);
    expect(childGroupLabels('/system')).toEqual(['系统运维', '工厂配置', '平台治理', '平台建厂']);
  });

  it('exposes four logistics modules only to LOGISTICS tenants', () => {
    const paths = [
      '/scheduling/logistics/workbench',
      '/scheduling/logistics/records',
      '/scheduling/logistics/orders',
      '/scheduling/logistics/resources',
    ];
    expect(paths.map((path) => findDescendant('/scheduling', path)?.title)).toEqual([
      '排线工作台', '调度记录', '门店与订单', '车辆与司机',
    ]);
    for (const path of paths) {
      expect(findDescendant('/scheduling', path)?.hideForFactoryTypes).toEqual(['FACTORY', 'RESTAURANT']);
    }
    expect(findDescendant('/scheduling', '/scheduling/logistics-demo')).toBeUndefined();

    for (const path of [
      '/scheduling/overview',
      '/scheduling/plans',
      '/scheduling/realtime',
      '/scheduling/workers',
      '/scheduling/alerts',
      '/scheduling/settings',
    ]) {
      expect(findDescendant('/scheduling', path)!.hideForFactoryTypes).toContain('LOGISTICS');
    }
  });

  it('hides the generic dashboard entry for logistics tenants', () => {
    expect(findGroup('/dashboard')?.hideForFactoryTypes).toContain('LOGISTICS');
  });
});

/**
 * 2026-09-01 LIUSHANMEN 仓储「库存盘点」菜单收拢 (owner 拍板)。
 *
 * owner 原话:「这个分仓库存查询跟总库存查询还有库存批次查询我觉得可以放在一起，
 * 然后半成品库存也可以放在一起。另外，半成品盘点跟盘点与期初库存可以放在一起，
 * 要不然这么多库存盘点跟那个仓库查询太乱了。」
 *
 *   库存查询 hub (/warehouse/inventory-query) = 分仓库存查询 + 总库存查询 +
 *     库存批次查询 + 半成品库存 (4 个视角, el-tabs 承载)
 *   盘点 hub (/warehouse/stocktake-query) = 盘点与期初库存 + 半成品盘点
 *     (2 个视角, el-tabs 承载)
 *
 * 6 个旧菜单项从 menuConfig 里移除 (硬约束 1: 合并不是删功能 —— 6 个视角全部
 * 保留, 只是从"各自一个菜单项"变成"tab"; 组件源码原样未改, 见
 * views/warehouse/{inventory-query,stocktake-query}/index.vue)。
 *
 * 旧路径仍可直接访问 (router redirect, 见 warehouseInventoryStocktakeRedirects.spec.ts
 * 与 warehouseHubRedirect.spec.ts 的行为测试; 本文件只钉菜单结构)。
 */
describe('menuConfig — 库存盘点合并 (LIUSHANMEN IA 收拢, 2026-09-01)', () => {
  const OLD_MENU_PATHS = [
    '/inventory/by-warehouse',
    '/warehouse/inventory-total',
    '/warehouse/inventory',
    '/warehouse/stocktakes',
    '/warehouse/semi-finished',
    '/warehouse/semi-finished-stocktakes',
  ];

  it('6 个旧菜单项已从 menuConfig 里移除 (合并入 2 个 hub, 由 router redirect 桥接)', () => {
    const paths = descendantPaths('/warehouse');
    for (const p of OLD_MENU_PATHS) {
      expect(paths, `${p} 应已从菜单移除 (合并入 hub)`).not.toContain(p);
    }
  });

  it('2 个 hub 入口存在，标题正确，且落在原「库存盘点」分段', () => {
    const warehouse = findGroup('/warehouse')!;
    const hub1 = warehouse.children!.find((c) => c.path === '/warehouse/inventory-query');
    const hub2 = warehouse.children!.find((c) => c.path === '/warehouse/stocktake-query');
    expect(hub1, '缺库存查询 hub 项').toBeDefined();
    expect(hub1!.title).toBe('库存查询');
    expect(hub1!.groupLabel).toBe('库存盘点');
    expect(hub2, '缺盘点 hub 项').toBeDefined();
    expect(hub2!.title).toBe('盘点');
  });

  it('阴性对照: hub 标题不是随手写的裸字符串巧合 —— 换个不存在的标题就会红', () => {
    const warehouse = findGroup('/warehouse')!;
    const hub1 = warehouse.children!.find((c) => c.path === '/warehouse/inventory-query');
    expect(hub1!.title).not.toBe('库存批次查询');
  });

  it('warehouse 组的 groupLabel 分段结构不变 (合并只影响该分段里的条目数)', () => {
    const warehouse = findGroup('/warehouse')!;
    expect(warehouse.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel)).toEqual([
      '仓储作业', '库存盘点', '仓储配置', '仓储分析',
    ]);
  });

  it('6 个旧路径在真实路由表里仍然存在且能被解析 (还没接 redirect 断言, 只确认死链没有产生)', () => {
    for (const p of OLD_MENU_PATHS) {
      const r = router.resolve(p);
      expect(r.matched.length, `${p} 在路由表里解析不到 —— 变成了死链`).toBeGreaterThan(0);
    }
  });

  it('2 个 hub 路径在真实路由表里能解析到真实组件 (不是又一层 redirect stub)', () => {
    for (const p of ['/warehouse/inventory-query', '/warehouse/stocktake-query']) {
      const r = router.resolve(p);
      expect(r.matched.length, `${p} 解析不到`).toBeGreaterThan(0);
      const leaf = r.matched[r.matched.length - 1];
      expect(leaf.redirect, `${p} 不该是 redirect stub —— 它应该是真实页面`).toBeUndefined();
      expect(typeof leaf.components?.default, `${p} 没有挂真实 component`).toBe('function');
    }
  });

  it('半成品库存名称已收敛 (形态 D 修复): route title 与 menu title 现在一致', () => {
    // 合并前 route.meta.title = '半成品重量库存', menuConfig title = '半成品库存'
    // —— 两个名字描述同一个页面。合并进 hub 后统一成菜单那个说法, tab label
    // 与 route stub 的 title 都改成 '半成品库存'。
    const r = router.resolve('/warehouse/semi-finished');
    expect(r.matched[r.matched.length - 1].meta?.title).toBe('半成品库存');
    expect(r.matched[r.matched.length - 1].meta?.title).not.toBe('半成品重量库存');
  });
});

describe('menuConfig — merged 数据与分析 group (WS4 经营分析合并)', () => {
  it('顶级 /analytics 组已删除 (合并入 /smart-bi)', () => {
    expect(menuConfig.find((m) => m.path === '/analytics')).toBeUndefined();
  });

  it('/smart-bi 组改名「数据与分析」', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi');
    expect(g).toBeDefined();
    expect(g!.title).toBe('数据与分析');
  });

  it('经营驾驶舱是第一个 child (主入口置顶)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children![0].path).toBe('/smart-bi/dashboard');
  });

  it('含 4 个 groupLabel 子组: AI 探索 / 经营分析 / 数据管理 / AI 运维', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const labels = g.children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['AI 探索', '经营分析', '数据管理', 'AI 运维']);
  });

  it('WS4: 经营分析 hub 项存在 (/smart-bi/analysis-hub, 经营分析 段首)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const hub = g.children!.find((c) => c.path === '/smart-bi/analysis-hub');
    expect(hub, '缺经营分析 hub 项').toBeDefined();
    expect(hub!.groupLabel).toBe('经营分析');
    expect(hub!.title).toBe('经营分析');
  });

  it('WS4: 6 页独立菜单项已删除 (合并入 hub, 由 router redirect 桥接)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/smart-bi/financial-dashboard',   // 财务看板 → hub?tab=finance
      '/smart-bi/sales',                 // 销售分析 → hub?tab=sales
      '/analytics/trends',               // 趋势分析 → hub?tab=trend
      '/analytics/kpi',                  // KPI看板 → hub?tab=kpi
      '/indicator-center',               // 指标中心 → hub?tab=kpi&sub=indicator
    ]) {
      expect(paths, `${p} 应已移除 (WS4 合并入 hub)`).not.toContain(p);
    }
  });

  it('WS4 #9: AI 分析报告菜单项已删除', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).not.toContain('/analytics/ai-reports');
  });

  it('经营分析仍保留跨域项，生产专题统一迁入生产分析', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    for (const p of [
      '/analytics/alert-dashboard', '/analytics/supply-chain',
    ]) {
      expect(paths, `missing migrated child ${p}`).toContain(p);
    }
    for (const p of [
      '/analytics/production-report',
      '/production-analytics/production',
      '/production-analytics/yield-cost',
      '/production-analytics/cost-summary',
    ]) {
      expect(paths, `${p} should live under /production/analysis, not /smart-bi`).not.toContain(p);
    }
  });

  it('原 /analytics/overview 仍保留 (D-6 保守, 不删不 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    expect(g.children!.map((c) => c.path)).toContain('/analytics/overview');
  });

  it('原 /smart-bi 其余 children 未掉项 (P3 query / P4 finance / WS4 6页 已 redirect)', () => {
    const g = menuConfig.find((m) => m.path === '/smart-bi')!;
    const paths = g.children!.map((c) => c.path);
    // P3: /smart-bi/query 已合并入 /smart-bi/analysis?tab=query (菜单项移除, 由 redirect 桥接)
    expect(paths, '/smart-bi/query 应已移除 (P3 合并)').not.toContain('/smart-bi/query');
    // P4: /smart-bi/finance 已合并入 hub (菜单项移除, 由 redirect 桥接)
    expect(paths, '/smart-bi/finance 应已移除 (P4 合并)').not.toContain('/smart-bi/finance');
    for (const p of [
      '/smart-bi/dashboard', '/smart-bi/analysis', '/smart-bi/analysis-hub',
      '/smart-bi/revenue-report', '/smart-bi/upload', '/smart-bi/query-templates',
      '/smart-bi/data-completeness', '/smart-bi/food-kb-feedback',
      '/smart-bi/fallback-log', '/smart-bi/calibration',
    ]) {
      expect(paths, `dropped original /smart-bi child ${p}`).toContain(p);
    }
  });
});

describe('menuConfig — 业态门控方向 (WS4)', () => {
  const group = () => menuConfig.find((m) => m.path === '/smart-bi')!;
  const child = (p: string) => group().children!.find((c) => c.path === p)!;

  it.each([
    '/analytics/supply-chain',
  ])('制造专属项 %s 对餐饮隐藏 (hideForFactoryTypes 含 RESTAURANT)', (p) => {
    expect(child(p).hideForFactoryTypes).toContain('RESTAURANT');
  });

  // 2026-09-02 LIUSHANMEN 生产管理菜单收拢: 车间实时生产报表/生产数据分析/
  // 成品出厂核算已合并进 /production/analysis-query hub, 该菜单项自己不再单独
  // 携带 hideForFactoryTypes (RESTAURANT 靠父级 /production 组的 hideForFactoryTypes
  // 挡住, 见下一条; FACTORY 那部分门控挪到组件内部按 tab 各自 v-if, 见
  // productionAnalysisTab.ts)。/production-analytics/cost-summary 不在合并范围内,
  // 仍保留原样的菜单级 hideForFactoryTypes。
  it.each([
    '/production-analytics/cost-summary',
  ])('生产分析项 %s 对餐饮隐藏 (hideForFactoryTypes 含 RESTAURANT)', (p) => {
    expect(findDescendant('/production', p)!.hideForFactoryTypes).toContain('RESTAURANT');
  });

  it('生产分析 hub 本身不单独门控业态 —— RESTAURANT 靠父级 /production 组挡住', () => {
    expect(findDescendant('/production', '/production/analysis-query')!.hideForFactoryTypes).toBeUndefined();
    expect(findGroup('/production')!.hideForFactoryTypes).toContain('RESTAURANT');
  });

  it('经营分析 hub 不门控 (双业态自适应 — 财务/销售/趋势/KPI 各 tab 内部自适应)', () => {
    expect(child('/smart-bi/analysis-hub').hideForFactoryTypes).toBeUndefined();
  });

  it('生产异常预警仅服务工厂，餐饮租户隐藏', () => {
    expect(child('/analytics/alert-dashboard').hideForFactoryTypes).toContain('RESTAURANT');
  });

  it('收入管理报表对制造隐藏 (FACTORY)', () => {
    expect(child('/smart-bi/revenue-report').hideForFactoryTypes).toContain('FACTORY');
  });

  it('行为校准监控保留 platform_admin 门控', () => {
    expect(child('/smart-bi/calibration').roles).toContain('platform_admin');
  });

  it('WS4: AI 运维 (知识库反馈/AI追问日志) 收 admin 门控', () => {
    expect(child('/smart-bi/food-kb-feedback').roles).toContain('platform_admin');
    expect(child('/smart-bi/fallback-log').roles).toContain('platform_admin');
  });
});

describe('menuConfig — 餐饮运营组 3 层重组 (Task 1)', () => {
  const group = () => menuConfig.find((m) => m.path === '/restaurant')!;
  const paths = () => group().children!.map((c) => c.path);

  it('餐饮组仍存在且仅 RESTAURANT 可见', () => {
    const g = group();
    expect(g).toBeDefined();
    expect(g.hideForFactoryTypes).toEqual(['FACTORY']);
  });

  // ⚠️ 2026-07-31 **改过期望值**。原来断言的是按数据对象分的三段
  //    ['深度分析', '日常录入', '数据与系统']。
  //    餐饮板块已改为**按部门**组织（产品定位是中台分析, 使用者是四类后台数据分析
  //    人员）, 功能页跟着归到运营/市场/财务名下, 旧分段不复存在。
  //    旧期望没有在编码缺陷, 只是钉住了当时的 IA —— 这次是 IA 本身变了。
  //    人事没有出现在这里: 它暂时只有驾驶舱一页, 没有独立功能页。
  it('groupLabel 段按部门组织: 部门驾驶舱 / 运营 / 市场 / 财务 / 数据与系统', () => {
    const labels = group().children!.filter((c) => c.groupLabel).map((c) => c.groupLabel);
    expect(labels).toEqual(['部门驾驶舱', '运营', '市场', '财务', '数据与系统']);
  });

  it('运营总览菜单项已移除 (病症: Excel 浏览器, 驾驶舱在数据与分析组)', () => {
    expect(paths()).not.toContain('/restaurant/analytics');
  });

  it('菜品四象限+毛利合并为单一 菜品分析 (无独立菜品两项)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/dishes');
    expect(p).not.toContain('/restaurant/analytics/menu');
    expect(p).not.toContain('/restaurant/analytics/gross-margin');
  });

  it('点评口碑显性入口 → /analytics/platform (无旧 dianping)', () => {
    const p = paths();
    expect(p).toContain('/restaurant/analytics/platform');
    expect(p).not.toContain('/restaurant/analytics/dianping');
    const platform = group().children!.find((c) => c.path === '/restaurant/analytics/platform')!;
    expect(platform.title).toBe('平台分析');
    expect(platform.icon).toBeTruthy();
  });

  it('深度分析段含 菜品分析/门店对比/平台分析; 日常录入段含 配方/领料/损耗/盘点; 数据与系统段含 数据完整度/ETL', () => {
    const p = paths();
    for (const x of [
      '/restaurant/analytics/dishes', '/restaurant/analytics/stores', '/restaurant/analytics/platform',
      '/restaurant/recipes', '/restaurant/supplier-delivery', '/restaurant/requisitions', '/restaurant/wastage', '/restaurant/stocktaking',
      '/restaurant/data-completeness', '/restaurant/admin/etl-status',
    ]) {
      expect(p, `缺餐饮项 ${x}`).toContain(x);
    }
  });

  it('admin 段 (ETL状态) 保留 roles 门控', () => {
    const etl = group().children!.find((c) => c.path === '/restaurant/admin/etl-status')!;
    expect(etl.roles).toContain('factory_super_admin');
  });
});


describe('menuConfig - platform-only governance entries', () => {
  const systemPaths = () => findGroup('/system')?.children ?? [];

  it.each([
    '/system/ai-intents',
    '/system/skill-tools',
    '/system/llm-usage',
    '/system/encoding-rules',
    '/system/ai-quota',
    '/system/pos',
    '/system/smartbi-config',
    '/calibration/list',
    '/system/data-quality-queue',
  ])('keeps %s out of factory-level account permissions', (path) => {
    const item = systemPaths().find((child) => child.path === path);
    expect(item, `missing ${path}`).toBeDefined();
    expect(item!.roles).toEqual(['platform_admin']);
  });
});

describe('menuConfig — personal OA workbench', () => {
  it('exposes one personal OA group with the four user-facing queues', () => {
    const oa = findGroup('/workflow');
    expect(oa?.title).toBe('个人 OA');
    expect(oa?.module).toBe('dashboard');
    expect(oa?.children?.map((item) => item.path)).toEqual([
      '/workflow/pending',
      '/workflow/my-created',
      '/workflow/acted',
      '/workflow/copied',
    ]);
    expect(oa?.children?.every((item) => item.module === 'dashboard')).toBe(true);
  });

  it('keeps the same personal OA entry in the finance-specific menu', () => {
    const oa = financeManagerMenu.find((item) => item.path === '/workflow');
    expect(oa?.children?.map((item) => item.title)).toEqual([
      '待我审批',
      '我发起的',
      '已处理',
      '抄送我的',
    ]);
  });

  it('keeps finance operations in finance and sends order approvals to personal OA', () => {
    expect(financeManagerMenu.map((item) => item.path)).toEqual(expect.arrayContaining([
      '/workflow',
      '/finance/ar-ap',
      '/finance/invoices?status=REQUESTED',
      '/finance/purchase-invoices',
      '/finance/payments',
    ]));
    expect(financeManagerMenu.some((item) => item.path === '/sales/finance-review')).toBe(false);
    for (const path of [
      '/finance/ar-ap',
      '/finance/invoices?status=REQUESTED',
      '/finance/purchase-invoices',
      '/finance/payments',
    ]) {
      expect(financeManagerMenu.find((item) => item.path === path)?.hideForFactoryTypes)
        .toContain('RESTAURANT');
    }
  });

  it('limits the purchase-invoice workbench to finance roles', () => {
    expect(findDescendant('/finance', '/finance/purchase-invoices')?.roles).toEqual([
      'factory_super_admin',
      'platform_admin',
      'permission_admin',
      'finance_manager',
    ]);
  });
});
