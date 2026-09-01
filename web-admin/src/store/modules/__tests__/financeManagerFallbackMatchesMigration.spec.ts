import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 闸: **前端两份 fallback 权限表, 对 `finance_manager` 不许比 DB 权威更宽。**
 *
 * ## 背景 (缺陷 30, 2026-09-01)
 *
 * 同一个「财务主管能不能进采购」在仓里有**三份**说法, 而且互相矛盾:
 *
 * | 来源 | procurement |
 * |---|---|
 * | `V20261030_14__finance_manager_narrow.sql` (DB 权威) | `'-'` |
 * | `utils/permission.ts` PERMISSION_MATRIX | `'none'` ✅ |
 * | `store/modules/permission.ts` FALLBACK | `'r'` ❌ (Apr 24 写的「对齐 DB」, 8-22 被 migration 作废) |
 *
 * 两份前端表都只是**DB 不可用时的兜底**, 线上真正生效的是后端下发的 permissions
 * (实测 `finance_manager` 拿到的是 `["finance:*"]`)。但兜底比真实权限**宽**是最坏的
 * 方向: DB 一挂, 财务侧边栏会铺开采购整组, 而线上他本来看不到。
 *
 * ⇒ 本闸把两份兜底钉在 migration 上。⛔ 钉的是「两份一致」不是「各自都对」。
 *
 * ## ⚠️ 只管 finance_manager, 且带显式登记的已知漂移
 *
 * 建闸时量过: 全表其它角色也有类似漂移, 开到全表会一上来一片红然后被关掉
 * (本仓形态 E: 宁可窄而可信)。⇒ 只钉 migration 真正 UPDATE 过的那 5 个模块,
 * 且本轮没修的那几个**逐条登记在 {@link KNOWN_DRIFT}**, ⛔ 不是漏了。
 * 新长出来的漂移会红。
 */

/**
 * ⚠️ **本闸的已知盲区, 写明而不是靠加启发式逼近**: 它只读 `V20261030_14` 这一份。
 * Flyway 迁移一旦跑过就不可改, 所以「有人编辑这一份」不是现实风险; 但如果将来有
 * **新的一版** migration 再把 procurement 开回给 finance, 本闸看不见那一份, 会继续
 * 按旧权威判定。⇒ 那种情况要手动同步这里的路径, 或把权威改成「查实际 DB」。
 * (⛔ 不用 `V*_finance*.sql` 之类的 glob 去猜 —— 猜错权威比没有闸更糟。)
 */
const MIGRATION = resolve(
  __dirname,
  '../../../../../backend/java/cretas-api/src/main/resources/db/flyway/V20261030_14__finance_manager_narrow.sql',
);
const UTILS = resolve(__dirname, '../../../utils/permission.ts');
const STORE = resolve(__dirname, '../permission.ts');

/** migration 把 finance_manager 的哪些模块 UPDATE 成了 '-'。 */
function modulesNarrowedByMigration(): string[] {
  expect(existsSync(MIGRATION), 'V20261030_14 找不到 —— 迁移被改名或移动了, 请同步本测试').toBe(true);
  const sql = readFileSync(MIGRATION, 'utf8').replace(/^\s*--.*$/gm, '');
  return [...sql.matchAll(/module_code\s*=\s*'([a-zA-Z_]+)'/g)].map((m) => m[1]).sort();
}

/**
 * 本轮**刻意未修**的漂移 —— 每条写清为什么。
 * ⛔ 登记是留痕不是豁免: 加一条之前先确认它是「改动范围之外」, 不是「我漏了」。
 */
const KNOWN_DRIFT: Record<string, string> = {
  analytics: '两份兜底都给 finance rw(SmartBI 完整权限), 与 migration 的 - 不符。'
    + '改它会改变 SmartBI 在兜底态下的可见性, 与缺陷 30 无关 ⇒ 留待单独确认。',
  production: 'store 兜底给 r, 与 migration 的 - 不符。同上, 超出缺陷 30 范围。',
  sales: 'store 兜底给 r, 与 migration 的 - 不符。销售订单详情与采购同形, '
    + '应与「数据半」那个后续 PR 一起处置, ⛔ 不在本 PR 顺手改。',
  rd: 'migration 砍了 rd, 而两份兜底表里 finance_manager 根本没有 rd 键(= 默认无权限), '
    + '方向上不冲突 ⇒ 无需处置, 登记备查。',
};

/** 从源码里抠出 finance_manager 那一段的某个模块值。 */
function readModuleLevel(file: string, module: string): string | null {
  const src = readFileSync(file, 'utf8').replace(/^\s*\/\/.*$/gm, '');
  const start = src.indexOf('finance_manager: {');
  expect(start, `${file} 里找不到 finance_manager —— 仪器坏了`).toBeGreaterThan(-1);
  const body = src.slice(start, src.indexOf('}', start));
  const hit = new RegExp(`\\b${module}\\s*:\\s*'([^']*)'`).exec(body);
  return hit ? hit[1] : null;
}

/** 两份表用不同的写法表达「没有权限」。 */
const DENIED = new Set(['-', 'none']);

const NARROWED = modulesNarrowedByMigration();

describe('finance_manager 的前端兜底权限不许比 DB 权威更宽', () => {
  it('阳性对照: migration 真的被解析到了, 且解析出来的是模块名', () => {
    expect(NARROWED.length, 'migration 一个 module_code 都没解析出来 —— 仪器坏了').toBeGreaterThan(3);
    expect(NARROWED).toContain('procurement');
    expect(NARROWED).toContain('sales');
    // 注释里也写着这些词, 剥注释失败会混进别的东西。
    expect(NARROWED).not.toContain('finance');
  });

  it('阳性对照: 两份兜底表都读得到 finance_manager 的值', () => {
    // 读空时下面那条会因「到处都是 null」而红得莫名其妙; 这条先炸, 指出是仪器问题。
    expect(readModuleLevel(UTILS, 'finance')).toBeTruthy();
    expect(readModuleLevel(STORE, 'finance')).toBeTruthy();
  });

  it('migration 砍掉的模块, 两份兜底表要么同样拒绝, 要么已登记为已知漂移', () => {
    const unregistered: string[] = [];
    for (const module of NARROWED) {
      for (const [label, file] of [['utils/permission.ts', UTILS], ['store/modules/permission.ts', STORE]] as const) {
        const level = readModuleLevel(file, module);
        if (level === null) continue;           // 没有这个键 = 默认无权限, 不算更宽
        if (DENIED.has(level)) continue;        // 同样拒绝, 一致
        if (module in KNOWN_DRIFT) continue;    // 显式登记过
        unregistered.push(`${label}: ${module} = '${level}'（migration 是 '-'）`);
      }
    }
    expect(
      unregistered,
      `以下兜底值比 DB 权威更宽, 且没有登记理由 ——\n  ${unregistered.join('\n  ')}\n\n`
        + `⇒ DB 一挂, finance_manager 的侧边栏会铺开这些模块, 而线上他看不到。\n`
        + `修法: 跟随 migration 改成 '-' / 'none'; 确实不改的登记进 KNOWN_DRIFT 并写清为什么。`,
    ).toEqual([]);
  });

  it('缺陷 30 那一项已经改好: 两份兜底都拒绝 procurement', () => {
    // 这条是本 PR 真正修掉的那一格, 单独钉住它不许回退。
    expect(DENIED.has(readModuleLevel(UTILS, 'procurement') ?? ''), 'utils 那份放开了 procurement').toBe(true);
    expect(DENIED.has(readModuleLevel(STORE, 'procurement') ?? ''), 'store 那份放开了 procurement').toBe(true);
    // ⛔ 且 procurement 不许被登记进 KNOWN_DRIFT 蒙混过关。
    expect(KNOWN_DRIFT).not.toHaveProperty('procurement');
  });

  it('登记表里的每一条都必须是 migration 真的砍过的模块, 且理由不为空', () => {
    for (const [module, reason] of Object.entries(KNOWN_DRIFT)) {
      expect(NARROWED, `${module} 不在 migration 砍掉的清单里, 登记它没有意义`).toContain(module);
      expect(reason.length, `${module} 的登记理由太短, 说不清为什么不改`).toBeGreaterThan(20);
    }
  });
});
