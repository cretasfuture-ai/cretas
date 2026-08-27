/**
 * 退役的屏不许悄悄回来 + 空壳屏不许被接上入口。
 *
 * ## 为什么需要这一条
 *
 * `screenReachability` 是个**棘轮**——它只数「走不到的屏有几个」。
 * 棘轮有一个天生的漏洞：**把预算降下去的办法有两种，而它分不出来。**
 *
 *   ① 真的收敛了（删掉重复注册 / 退役废弃屏）        ✅
 *   ② 给一个**打开就是错误提示**的空壳加一句 navigate  ⛔ 数字一样变小
 *
 * 2026-08-26 那一轮把 35 降到 29 走的是 ①。这道闸钉住的就是「走的是 ①」：
 * 退役的那些名字不许回来，而那 5 个「接口尚未实现」的看板不许被接上。
 *
 * ⚠️ 它和 `screenReachability` 的关系：那道闸问「有几个走不到」，
 *    这道闸问「走得到的那些，是不是**值得**走到」。⛔ 两个数一起看才有意义。
 */
import fs from 'fs';
import path from 'path';

const SRC = path.join(__dirname, '../..');
const NAV_DIR = path.join(SRC, 'navigation');

/** ⛔ 剥注释：注释里提到的注册不是注册，注释里提到的 navigate 不是 navigate。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

function walk(dir: string, acc: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const full = path.join(dir, name);
    if (fs.statSync(full).isDirectory()) {
      if (name !== 'node_modules') walk(full, acc);
    } else if (/\.tsx?$/.test(name)) {
      acc.push(full);
    }
  }
  return acc;
}

/** ⚠️ 用 path.sep 拼，⛔ 不写任何反斜杠转义（同 screenReachability 的那条注释）。 */
const NAV_MARKER = path.sep + 'navigation' + path.sep;
const GATES_MARKER = path.sep + '__tests__' + path.sep + 'gates' + path.sep;

const NAV_SOURCE = walk(NAV_DIR)
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

/**
 * 引用池：非 navigation、非 gates 的全部源码。
 * ⛔ 必须排除 gates —— 否则**本文件**下面写的那些屏名会让它们变「可达」，
 *    这道闸会亲手把它要守的东西破坏掉。
 */
const REFERENCES = walk(SRC)
  .filter((f) => !f.includes(NAV_MARKER) && !f.includes(GATES_MARKER))
  .map((f) => stripComments(fs.readFileSync(f, 'utf8')))
  .join('\n');

const registered = (route: string) =>
  NAV_SOURCE.includes(`name="${route}"`) || NAV_SOURCE.includes(`name='${route}'`);

const referenced = (route: string) =>
  REFERENCES.includes(`'${route}'`) || REFERENCES.includes(`"${route}"`);

/** 2026-08-26 退役的重复注册名 -> 留下来的那个孪生名。 */
const RETIRED_TO_SURVIVOR: Array<[string, string | null]> = [
  ['OutboundDetail', 'WHOutboundDetail'],
  ['InboundDetail', 'WHInboundDetail'],
  ['AlertHandle', 'WHAlertHandle'],
  ['TempMonitor', 'WHTempMonitor'],
  ['TraceDetail', 'TraceabilityDetail'],
  ['Membership', null], // 无孪生：整屏退役（另一个产品的会员页）
];

describe('退役的重复注册名不许回来', () => {
  it('阳性对照：仪器真的读到了 navigator 源码', () => {
    // 没有这一条，「哪个名字都找不到」和「我读了个空字符串」长得一模一样。
    expect(NAV_SOURCE.length).toBeGreaterThan(50_000);
    expect(NAV_SOURCE).toContain('name="WHHome"');
  });

  it.each(RETIRED_TO_SURVIVOR)('%s 已退役，且它的孪生 %s 还在', (retired, survivor) => {
    expect(registered(retired)).toBe(false);
    // 🔴 孪生必须还在 —— 否则这条断言的「通过」可能意味着**两个都被删了**，
    //    那不是收敛，那是丢功能。
    if (survivor) expect(registered(survivor)).toBe(true);
  });

  it('PomeloX 会员页没有回来', () => {
    expect(fs.existsSync(path.join(SRC, 'screens/profile/MembershipScreen.tsx'))).toBe(false);
    // 卡面上写死的那句品牌名是最好的指纹：它属于另一个产品。
    expect(REFERENCES).not.toContain('POMELOX');
  });
});

/**
 * 「打开就是一句『接口尚未实现』」的屏，不许被接上入口。
 *
 * 这 5 个 SmartBI 看板合计 3900+ 行，看起来很有分量，
 * 而它们的 `loadData()` 里唯一那句 API 调用是**被注释掉的**，
 * 紧接着无条件 setError。`smartBIApiClient` 里也根本没有这些方法。
 * ⇒ 接上入口 = 给用户一个必然失败的按钮。
 */
const NOT_IMPLEMENTED_SENTINEL = 'errors.notImplemented';

const SHELL_DASHBOARDS: Array<[string, string]> = [
  ['ProductionDashboard', 'screens/smartbi/ProductionDashboardScreen.tsx'],
  ['QualityDashboard', 'screens/smartbi/QualityDashboardScreen.tsx'],
  ['InventoryDashboard', 'screens/smartbi/InventoryDashboardScreen.tsx'],
  ['ProcurementDashboard', 'screens/smartbi/ProcurementDashboardScreen.tsx'],
  ['SalesFunnel', 'screens/smartbi/SalesFunnelScreen.tsx'],
];

describe('空壳看板不许被接上入口（棘轮的第二种降法要拦住）', () => {
  it('阳性对照：引用池非空，且它确实认得出「有人跳」的屏', () => {
    expect(REFERENCES.length).toBeGreaterThan(1_000_000);
    // DraftReports 是 PR #130 接上入口的那个 —— 它必须被判成「有人引用」，
    // 否则下面每一条 `referenced(...) === false` 都是恒真式。
    expect(referenced('DraftReports')).toBe(true);
  });

  it.each(SHELL_DASHBOARDS)('%s 仍是空壳，因此仍然不许有入口', (route, rel) => {
    const body = stripComments(fs.readFileSync(path.join(SRC, rel), 'utf8'));

    // 🔴 先证明它**还是**空壳。若有人把它修好了（真的接上了 API），
    //    这条会红 —— 那时应该把它从本清单里移走并接上入口，⛔ 不是把断言删掉。
    expect(body).toContain(NOT_IMPLEMENTED_SENTINEL);

    // 既然还是空壳，就不许有人跳它。
    expect(referenced(route)).toBe(false);
  });

  it('这 5 个屏一个都没被真正接上后端（方法在 api client 里根本不存在）', () => {
    const client = fs.readFileSync(path.join(SRC, 'services/api/smartbi.ts'), 'utf8');
    // 阳性对照：这个文件确实是 smartbi 的 api client，不是读错了文件。
    expect(client.length).toBeGreaterThan(1_000);
    expect(client).toContain('smartBIApiClient');

    for (const [route] of SHELL_DASHBOARDS) {
      const method = route === 'SalesFunnel' ? 'getSalesFunnel' : `get${route}`;
      expect(client).not.toContain(method);
    }
  });
});
