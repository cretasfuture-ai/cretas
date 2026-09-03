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
 * <h2>存量已清零（2026-09-03 第二轮）</h2>
 *
 * 建闸当天报了 13 处，逐条核对后 **9 处是假阳性**（判据写错，见下方阴性对照），
 * 真缺陷 4 处已全部修掉。所以这道闸现在是**硬红**，不是棘轮 ——
 * {@link REVIEWED_NOT_A_DEFECT} 里只放「核对过、确认不是缺陷」的，不是欠账。
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
 * 已逐条核对、确认**不是**缺陷的路径。
 *
 * ⛔ 只许从这里删，不许往里加 —— 往里加之前必须写清「为什么它解析不出来却没坏」。
 *
 * 这里只剩一类：**路径由一个有限值集拼出来**（`/x/${key}`），而落地页是若干
 * 字面路由而不是 `:param` 路由。占位符替换出来的探针路径必然落兜底，
 * 但真实取值每一个都解析得出来 —— 逐个核对过：
 */
const REVIEWED_NOT_A_DEFECT: readonly { probe: string; why: string }[] = [
  {
    probe: '/restaurant/x1',
    // DeptKey = 'ops'|'marketing'|'hr'|'finance'|'procurement'(departmentConfig.ts),
    // /restaurant/{ops,marketing,hr,finance,procurement} 五条路由全部存在。
    why: 'DepartmentDashboard 的 `/restaurant/${key}`，key 是 DeptKey 五选一，五条都在',
  },
  {
    probe: '/system/smartbi-config/x1',
    // goToPage 只有两个调用点: 'data-sources' / 'chart-templates', 两条路由都在。
    why: 'SmartBIConfigView 的 `/system/smartbi-config/${page}`，page 只取两个值，都在',
  },
];

/**
 * 一条路径是不是「死的」。
 *
 * ⛔ 判据不能是 `resolve(p).name === undefined` —— redirect 路由天生无 name
 *   (见下方阴性对照)。唯一可靠的信号是**它落到了兜底路由**。
 */
function isDead(path: string): boolean {
  const matched = router.resolve(path).matched;
  if (matched.length === 0) return true;
  return String(matched[matched.length - 1].path).startsWith('/:pathMatch');
}

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

  it('阳性对照: 一个已知坏路径确实判得出来', () => {
    // 证明「解析失败」这件事真的判得出来, 否则主断言是恒真式。
    expect(isDead('/transfer/detail/x1')).toBe(true);
  });

  /**
   * 🔴 阴性对照 —— 这条是**建闸当天判据写错**的直接产物, 不要删。
   *
   * 第一版判据是 `resolve(p).name === undefined`。而 vue-router 里
   * **redirect 路由既没有 name 也没有 component**:
   *
   *   { path: '/smart-bi/query', redirect: (to) => ({ path: '/smart-bi/analysis', ... }) }
   *
   * 于是它被判成「死路径」—— 实际上它工作得好好的。13 处报警里 **9 处是这一类
   * 假阳性**(6 处 /smart-bi/query + 1 处 /smart-bi/finance + 2 处有限值集拼路径)。
   * 假阳性正是让人把闸关掉的那个东西(形态 E)。
   *
   * ⇒ 判据改成「**落到兜底路由**」: 只有 matched 为空、或最后一条匹配记录是
   *    `/:pathMatch(.*)*` 时才算死。
   */
  it('阴性对照: 无名的 redirect 路由不算死路径', () => {
    const r = router.resolve('/smart-bi/query');
    expect(r.name).toBeUndefined();      // 它确实没有 name ——
    expect(isDead('/smart-bi/query')).toBe(false);  // 但它不是死路径。
  });

  it('没有新增解析不出来的 push 路径', () => {
    const reviewed = new Set(REVIEWED_NOT_A_DEFECT.map((entry) => entry.probe));
    const unresolved = hits
      .filter((hit) => isDead(hit.probe))
      .filter((hit) => !reviewed.has(hit.probe));

    const report = unresolved.map((hit) => `${hit.file}:${hit.line}  ${hit.raw}`);
    expect(report).toEqual([]);
  });
});
