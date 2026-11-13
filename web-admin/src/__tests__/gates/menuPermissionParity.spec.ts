import { describe, expect, it } from 'vitest';
import baseline from './fixtures/menuPermissionBaseline.json';
import {
  factoryMenuForType,
  groupedMenuConfig,
  menuConfig,
  menuTrail,
  type MenuItem,
} from '@/components/layout/menuConfig';

/**
 * P2（MIGRATION-PLAN 第一块 / 2.8 P2 行）：菜单换组不改权限。
 *
 * 改前快照 fixtures/menuPermissionBaseline.json = origin/main 994f2c0a3a（含 #1679 的
 * requiresDisposalApproval）里每个叶子「祖先链 + 自身」的权限字段。侧栏判可见是一层层过的
 * （父组被 roles/module/disabledMenuPaths 挡住 ⇒ 子项一起消失），所以只比叶子自身不够，
 * 要比整条链。新分组里一条叶子的链 = guardAncestors（搬家前的父组）+ 非展示分组的祖先 + 自身；
 * 纯展示分组（container）不带判据，不进链。
 *
 * ⛔ 不要为了让本闸变绿重新生成快照：快照就是「改前」，重生成等于把闸拆了。
 *    真要改某个菜单项的权限，是一个单独的、要说清理由的改动，在那个 PR 里更新快照并写明哪一条变了。
 */
const FIELDS = baseline.fields as readonly (keyof MenuItem)[];
type Guard = Record<string, unknown>;
interface LeafChain { path: string; chain: Guard[] }

function guardOf(node: MenuItem): Guard {
  const out: Guard = { path: node.path };
  for (const k of FIELDS) if (node[k] !== undefined) out[k] = node[k];
  return out;
}

function leafChains(items: readonly MenuItem[]): LeafChain[] {
  const out: LeafChain[] = [];
  const walk = (nodes: readonly MenuItem[], ancestors: MenuItem[]) => {
    for (const node of nodes) {
      const next = node.container ? ancestors : [...ancestors, node];
      if (node.children?.length) {
        walk(node.children, next);
        continue;
      }
      out.push({
        path: node.path,
        chain: [...(node.guardAncestors ?? []), ...ancestors].map(guardOf).concat(guardOf(node)),
      });
    }
  };
  walk(items, []);
  return out;
}

function byPath(chains: LeafChain[]): Map<string, Guard[]> {
  return new Map(chains.map((c) => [c.path, c.chain]));
}

const snapshot = byPath(baseline.leaves as LeafChain[]);

/**
 * P2b（迁移方案 M3）：快照之后**唯一**允许的权限字段变化 —— 逐条列出，不用通配。
 * 「数据与分析」原组 /smart-bi 去掉 hideForFactoryTypes: ['FACTORY']（对工厂默认显示，按厂可关
 * 改由 feature-config showInSidebar === false 判，见 factoryMenuVisibility.ts）。
 * 受影响的就是祖先链里带 /smart-bi 的这 14 个叶子；它们链上的 /smart-bi 一节去掉该字段，
 * 链上其它节（含叶子自身，如 revenue-report 自己的 FACTORY 隐藏）逐项不变。
 * 白名单外任何叶子有变化 ⇒ 红；白名单内叶子没按预期变（漏改）⇒ 也红。
 */
const EXPECTED_CHANGES: ReadonlyArray<{ leaf: string; ancestor: string; field: keyof MenuItem; from: unknown; to: unknown }> = [
  '/smart-bi/dashboard',
  '/smart-bi/analysis',
  '/smart-bi/analysis-hub',
  '/smart-bi/revenue-report',
  '/analytics/alert-dashboard',
  '/analytics/supply-chain',
  '/smart-bi/upload',
  '/smart-bi/query-templates',
  '/smart-bi/data-completeness',
  '/smart-bi/mapping-review',
  '/analytics/overview',
  '/smart-bi/food-kb-feedback',
  '/smart-bi/fallback-log',
  '/smart-bi/calibration',
].map((leaf) => ({ leaf, ancestor: '/smart-bi', field: 'hideForFactoryTypes' as const, from: ['FACTORY'], to: undefined }));

/** 快照 + 白名单 = 这次之后应有的链。白名单里写的「改前」值必须与快照一致，否则白名单本身写错了。 */
function applyExpectedChanges(base: Map<string, Guard[]>): Map<string, Guard[]> {
  const out = new Map([...base.entries()].map(([k, chain]) => [k, chain.map((g) => ({ ...g }))]));
  for (const c of EXPECTED_CHANGES) {
    const chain = out.get(c.leaf);
    if (!chain) throw new Error(`EXPECTED_CHANGES: 快照里没有叶子 ${c.leaf}`);
    const node = chain.find((g) => g.path === c.ancestor);
    if (!node) throw new Error(`EXPECTED_CHANGES: ${c.leaf} 的链上没有 ${c.ancestor}`);
    if (JSON.stringify(node[c.field]) !== JSON.stringify(c.from)) {
      throw new Error(`EXPECTED_CHANGES: ${c.leaf} › ${c.ancestor}.${String(c.field)} 改前应为 ${JSON.stringify(c.from)}，快照是 ${JSON.stringify(node[c.field])}`);
    }
    if (c.to === undefined) delete node[c.field];
    else node[c.field] = c.to;
  }
  return out;
}

const before = applyExpectedChanges(snapshot);

/**
 * P3 预期新增的叶子（改前快照里没有）：顶层「今天」「待办」。权限字段逐字列在这里——
 * 两页只读聚合，入口与个人 OA 同判据（module: 'dashboard'，无 roles / 工厂类型限制）。
 * 其余叶子仍要和快照逐项相等；新增叶子多一个、少一个、字段多一项（比如手滑加了 roles）都会红。
 */
const EXPECTED_ADDITIONS: Record<string, Guard[]> = {
  '/today': [{ path: '/today', module: 'dashboard' }],
  '/todo': [{ path: '/todo', module: 'dashboard' }],
};
const ADDED = new Set(Object.keys(EXPECTED_ADDITIONS));

describe('P2 菜单改组：每个菜单 path 的权限字段改前改后逐项相等（P2b 只多出 EXPECTED_CHANGES 列的那几条；P3 只多出 EXPECTED_ADDITIONS 的 今天/待办）', () => {
  it('阳性对照：快照真的读到了（否则下面是空集上的恒真式）', () => {
    expect(baseline.leafCount).toBeGreaterThan(150);
    expect(snapshot.size).toBe(baseline.leafCount);
    expect(before.size).toBe(baseline.leafCount);
    // #1679 的字段在快照里
    expect(snapshot.get('/finance/disposal-approvals')?.at(-1)).toMatchObject({ requiresDisposalApproval: true });
    // 被搬家的叶子在快照里带着原父组
    expect(snapshot.get('/system/products')?.map((g) => g.path)).toEqual(['/production', '/system/products']);
  });

  it('P2b 白名单恰好覆盖快照里所有「祖先链带 /smart-bi FACTORY 隐藏」的叶子，且只动这一个字段', () => {
    const affected = [...snapshot.entries()]
      .filter(([, chain]) => chain.some((g) => g.path === '/smart-bi' && JSON.stringify(g.hideForFactoryTypes) === '["FACTORY"]'))
      .map(([path]) => path).sort();
    expect(affected).toHaveLength(14);
    expect(EXPECTED_CHANGES.map((c) => c.leaf).sort()).toEqual(affected);
    // 白名单只把这 14 条链上 /smart-bi 一节的 hideForFactoryTypes 去掉，别的都与快照一样
    const changed = [...snapshot.entries()]
      .filter(([path, chain]) => JSON.stringify(before.get(path)) !== JSON.stringify(chain))
      .map(([path]) => path).sort();
    expect(changed).toEqual(affected);
    // 叶子自身的工厂隐藏不在白名单里：收入管理报表仍对工厂隐藏
    expect(before.get('/smart-bi/revenue-report')?.at(-1)).toMatchObject({ hideForFactoryTypes: ['FACTORY'] });
    // 物流调度的 FACTORY 隐藏不受影响
    expect(before.get('/scheduling/logistics/workbench')?.at(-1)).toMatchObject({ hideForFactoryTypes: ['FACTORY'] });
  });

  it('原型分组侧栏（groupedMenuConfig）：除预期新增外，叶子数量相等、path 集合相等、每条链逐项相等', () => {
    const all = leafChains(groupedMenuConfig);
    const after = all.filter((c) => !ADDED.has(c.path));
    expect(all.length - after.length, '预期新增的叶子都在（缺一个也算红）').toBe(ADDED.size);
    expect(after.length, '叶子数量（同一 path 出现两次也会被这里抓到）').toBe(baseline.leafCount);
    const afterMap = byPath(after);
    expect([...afterMap.keys()].sort()).toEqual([...before.keys()].sort());
    const diffs = [...before.entries()]
      .filter(([path, chain]) => JSON.stringify(afterMap.get(path)) !== JSON.stringify(chain))
      .map(([path, chain]) => `${path}\n  改前 ${JSON.stringify(chain)}\n  改后 ${JSON.stringify(afterMap.get(path))}`);
    expect(diffs, diffs.join('\n')).toEqual([]);
  });

  it('P3 预期新增项：权限字段与登记的完全一致，且不与快照里已有的 path 重名', () => {
    const added = byPath(leafChains(groupedMenuConfig).filter((c) => ADDED.has(c.path)));
    for (const [path, chain] of Object.entries(EXPECTED_ADDITIONS)) {
      expect(before.has(path), `${path} 不应在改前快照里`).toBe(false);
      expect(added.get(path), path).toEqual(chain);
    }
  });

  it('原分组（餐饮/物流仍在用的 menuConfig）与快照完全相同', () => {
    const now = byPath(leafChains(menuConfig));
    expect(now.size).toBe(before.size);
    expect(JSON.stringify([...now.entries()].sort())).toBe(JSON.stringify([...before.entries()].sort()));
  });

  it('展示分组（container）本身不带任何权限判据', () => {
    const containers: MenuItem[] = [];
    const walk = (nodes: readonly MenuItem[]) => nodes.forEach((n) => { if (n.container) containers.push(n); if (n.children) walk(n.children); });
    walk(groupedMenuConfig);
    expect(containers.map((c) => c.title)).toEqual(['概览', '数据与分析', '主数据']);
    for (const c of containers) {
      expect(c.roles, c.title).toBeUndefined();
      expect(c.hideForFactoryTypes, c.title).toBeUndefined();
      expect(c.allowedFactoryTypes, c.title).toBeUndefined();
      expect(c.requiresFactoryContext, c.title).toBeUndefined();
      expect(c.requiresDisposalApproval, c.title).toBeUndefined();
      expect(c.path.startsWith('#group/'), c.title).toBe(true);
    }
  });

  it('分区与组顺序按原型：今天/待办 → 全部功能（概览→个人 OA→采购→销售→生产→仓储→质量→数据与分析→保留模块）→ 设置', () => {
    expect(groupedMenuConfig.map((m) => `${m.section}:${m.title}`)).toEqual([
      'top:今天', 'top:待办',
      'all:概览', 'all:个人 OA', 'all:采购管理', 'all:销售管理', 'all:生产管理', 'all:仓储管理', 'all:质量管理', 'all:数据与分析',
      'all:研发管理', 'all:人事管理', 'all:设备管理', 'all:财务管理', 'all:智能调度',
      'settings:主数据', 'settings:视觉质检训练', 'settings:系统管理',
    ]);
  });

  it('本批不出现的入口：发现的问题（P4）、数字班组（M4）、M7 四页', () => {
    const paths = leafChains(groupedMenuConfig).map((c) => c.path);
    for (const p of ['/issues', '/crew', '/crew/ledger', '/crew/onboard',
      '/lsm/outsource', '/lsm/customer-prices', '/warehouse/inventory-adjustments', '/collab/tasks']) {
      expect(paths, p).not.toContain(p);
    }
  });

  it('餐饮/物流用原分组，工厂/中央厨房/未知类型用原型分组', () => {
    expect(factoryMenuForType('RESTAURANT')).toBe(menuConfig);
    expect(factoryMenuForType('LOGISTICS')).toBe(menuConfig);
    expect(factoryMenuForType('FACTORY')).toBe(groupedMenuConfig);
    expect(factoryMenuForType('CENTRAL_KITCHEN')).toBe(groupedMenuConfig);
    expect(factoryMenuForType(undefined)).toBe(groupedMenuConfig);
  });

  it('面包屑按菜单树取「组 › 项」，最长匹配', () => {
    expect(menuTrail(groupedMenuConfig, '/system/products')?.map((n) => n.title)).toEqual(['主数据', 'SKU 管理']);
    expect(menuTrail(groupedMenuConfig, '/production/plans?x=1')?.map((n) => n.title)).toEqual(['生产管理', '生产计划']);
    expect(menuTrail(groupedMenuConfig, '/workdesk/purchaser')?.map((n) => n.title)).toEqual(['概览', '工作台', '采购员工作台']);
    expect(menuTrail(groupedMenuConfig, '/no/such/page')).toBeNull();
  });
});
