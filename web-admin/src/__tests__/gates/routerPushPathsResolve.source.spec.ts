import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import router from '@/router/index';

/**
 * 代码里写死的 `router.push('/x/y')` 必须在路由表里解析得出来。
 *
 * <h2>🔴 2026-09-03 客户实拍</h2>
 *
 * 生产计划「生成调拨单」——**单子建出来了，跳转 404**。真因是一行字面量：
 *
 * <pre>
 *   views/production/plans/list.vue   router.push(`/transfer/detail/${id}`)   ← 404
 *   路由表 router/index.ts            { path: 'transfer', children: [{ path: ':id' }] }
 * </pre>
 *
 * 另外 6 处调拨详情跳转全都写的 `/transfer/${id}`，只有这一处多了 `detail/` 一段。
 * 实测 `router.resolve('/transfer/detail/x')` 落到兜底 404 路由
 * (matched=1, name=undefined)，而 `/transfer/x` 解析成 TransferDetail (matched=3)。
 *
 * <h2>为什么要建闸：形态 D「同一个东西有两份」</h2>
 *
 * 路径这件事有两份来源 —— **路由表**里的定义，和**散在各屏**的 push 字面量。
 * 两份之间没有任何机械约束，所以它一定会漂；而漂掉的表现是 404，
 * 既不报编译错、也不报类型错、TS 完全看不见（参数是 string）。
 *
 * ⚠️ 这一类的**症状特别容易被读成别的东西**：用户看到的是「跳转失败」，
 * 而后端其实成功了。本次客户原话就是「生跳转失败，但是生成了」。
 *
 * <h2>棘轮而非硬红</h2>
 *
 * 建闸当天存量见 {@link KNOWN_UNRESOLVED}。⛔ 只许减不许增 ——
 * 一上来硬红就是一道当天被加 skip 的闸（形态 E：宁可窄而可信）。
 *
 * <h2>这道闸看不见什么（代理判据，写明盲区）</h2>
 *
 * 只看**字面量**。`router.push(someVar)`、`router.push({ name })`、
 * 以及路径本身由变量拼出来的，全都扫不到。⇒ 它是兜底，不是免检。
 */

const SRC = resolve(__dirname, '../..');

/** 把 `${...}` 占位段换成一个普通段，好让路由表按 `:param` 匹配。 */
const PLACEHOLDER = 'x1';

/**
 * 建闸当天已存在、暂未处理的不可解析路径（8 个去重路径 / 13 处调用点）。
 *
 * ⛔ 只许从这里删，不许往里加。加之前先问：这个 push 用户点得到吗？
 *
 * ⚠️ **这些和客户报的那个 404 是同一个形态**，只是还没有人点到 ——
 * 每一条都实测 `router.resolve()` 落到兜底路由 (name=undefined, matched=1)。
 * 修法各不相同（有的要改路径、有的要补路由），所以没有在同一个 PR 里一起动：
 * 逐条改需要各自确认「正确的落地页是哪个」，那是另一轮的活。
 *
 * 键用归一化后的**路径**而不是 file:line —— 行号会漂，而「这个路径不存在」
 * 才是缺陷本身；路由补上或调用点改对，它就自动从这张表掉下去。
 */
const KNOWN_UNRESOLVED: readonly string[] = [
  '/warehouse/materials/wip-list',   // components/widgets/endpoint/WipBatchCountWidget.vue
  '/smart-bi/query',                 // analytics/index, analytics/kpi/index (×4), smart-bi/FoodKBFeedback
  '/smart-bi/finance',               // finance/sku-margin/index
  '/smart-bi/excel-upload',          // restaurant/data-completeness
  '/workflow/admin',                 // production/bom/EcnList (?entityType=ECN)
  '/restaurant/x1',                  // restaurant/departments/DepartmentDashboard (`/restaurant/${key}`)
  '/restaurant/suppliers',           // restaurant/supplier-delivery/SupplierDeliveryNoteUploadDialog
  '/system/smartbi-config/x1',       // smartbi-config/SmartBIConfigView (`/system/smartbi-config/${page}`)
];

interface Hit {
  file: string;
  line: number;
  raw: string;
  probe: string;
}

function walk(dir: string, out: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      if (entry === '__tests__' || entry === 'router' || entry === 'node_modules') continue;
      walk(full, out);
    } else if (
      (full.endsWith('.vue') || full.endsWith('.ts'))
      && !full.endsWith('.spec.ts')
      && !full.endsWith('.d.ts')
    ) {
      out.push(full);
    }
  }
  return out;
}

function collectHits(): Hit[] {
  const files = walk(SRC);

  // router.push('/a/b') | router.replace(`/a/${id}`) —— 只取以 / 开头的字面量
  const CALL = /\b(?:router|\$router)\s*\.\s*(?:push|replace)\s*\(\s*(['"`])(\/[^'"`\n]*)\1/g;

  const hits: Hit[] = [];
  for (const file of files) {
    const text = readFileSync(file, 'utf8');
    for (const match of text.matchAll(CALL)) {
      const raw = match[2];
      // `${...}` -> 一个普通段; 带 ? 的先切掉 query
      const probe = raw
        .replace(/\$\{[^}]*\}/g, PLACEHOLDER)
        .split('?')[0];
      // 拼接残留(例如 '/a/' + id)或空段的，跳过——那不是完整字面量
      if (probe.endsWith('/') && probe !== '/') continue;
      const line = text.slice(0, match.index ?? 0).split('\n').length;
      hits.push({ file: relative(SRC, file).replace(/\\/g, '/'), line, raw, probe });
    }
  }
  return hits;
}

describe('router.push 的字面量路径必须解析得出来', () => {
  const hits = collectHits();

  it('阳性对照: 扫描器确实抓到了 push 字面量', () => {
    // 抓不到东西时下面那条断言恒绿 —— 先证明仪器活着。
    expect(hits.length).toBeGreaterThan(20);
  });

  it('阳性对照: 一个已知坏路径确实解析不出来', () => {
    // 证明「解析失败」这件事真的判得出来, 否则主断言是恒真式。
    const bad = router.resolve('/transfer/detail/x1');
    expect(bad.name).toBeUndefined();
  });

  it('没有新增解析不出来的 push 路径', () => {
    const unresolved = hits
      .filter((hit) => router.resolve(hit.probe).name === undefined)
      .filter((hit) => !KNOWN_UNRESOLVED.includes(hit.probe));

    const report = unresolved.map((hit) => `${hit.file}:${hit.line}  ${hit.raw}`);
    expect(report).toEqual([]);
  });
});
