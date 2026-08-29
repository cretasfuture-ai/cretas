import fs from 'fs';
import path from 'path';

/**
 * 需要参数的详情屏，**不许**有不带参数的导航调用能到达。
 *
 * <h3>它把一个「未完成项」变成了「已判定项」</h3>
 *
 * 2026-08-29 逐角色走查：17 个角色 552 条路由，**89 条**在不带参数打开时抛
 * `Cannot destructure property 'xxxId' of 'route.params'`；去重后是 **40 个屏**。
 *
 * 当时的处理是给每个 navigator 挂屏级错误边界（把爆炸半径从「整棵导航树」
 * 收到「这一屏」）。剩下的问题是：**要不要把这 40 个屏逐个改成
 * `MissingRouteParams`？**
 *
 * 先量了一次「真实用户走得到吗」，三项证据：
 *
 * <table>
 *   <tr><td>不带参数的 `navigate/push/replace('X')`</td><td><b>0</b></td></tr>
 *   <tr><td>对象式 `navigate({ name:'X' })` 且无 params</td><td><b>0</b></td></tr>
 *   <tr><td>阳性对照：<b>带</b>参数导航到这些屏的调用</td><td><b>75</b>（扫描器是活的）</td></tr>
 *   <tr><td>深链</td><td>未配置（`app.json` 无 `scheme` / `intentFilters`，
 *       `NavigationContainer` 无 `linking`）</td></tr>
 * </table>
 *
 * ⇒ **走不到。** 我能打开它们，只因为测试工具直接调 `navigationRef.navigate`。
 * 所以逐屏改 40 个文件是空转，屏级边界才是相称的修法。
 *
 * <h3>⚠️ 这道闸存在的理由</h3>
 *
 * 上面那个结论**有保质期** —— 哪天有人写下一句
 * `navigation.navigate('BatchDetail')`，40 个屏里就有一个变成用户能撞到的崩溃。
 * 这道闸让那句话当场变红。
 *
 * <p>🔴 扫描前必须<b>剥注释</b>：第一版没剥，把 `ProcessingStackNavigator` 注释里
 * 写的 `navigate("StocktakeEntry")` 数成了真实调用（本仓同形第三次）。
 */
const SRC = path.join(__dirname, '..', '..');

/** 走查实测「不带参数打开就抛」的屏（去掉 `Screen` 后缀＝路由名）。 */
const PARAM_REQUIRED_ROUTES = [
  'AIAnalysis', 'AlertDetail', 'BatchDetail', 'BatchWorkers', 'DepartmentDetail',
  'DynamicReport', 'EquipmentDetail', 'LabelScan', 'ProcessRunOverview', 'ProcessTaskDetail',
  'QICamera', 'QIForm', 'QILabelQcReview', 'QILabelQcSubmitted', 'QIRecordDetail',
  'QIResult', 'QIVoice', 'ReturnOrderDetail', 'SalesOrderShortageReview', 'ShortageAlertDetail',
  'StaffAIAnalysis', 'StaffDetail', 'StocktakeEntry', 'SupplierDeliveryDetail', 'TodoDetail',
  'TransferDetail', 'TransferReceive', 'VoiceInspectionResult', 'WHBatchDetail', 'WHBatchTrace',
  'WHInboundDetail', 'WHInspect', 'WHInventoryDetail', 'WHOrderDetail', 'WHOutboundDetail',
  'WHOutboundIssue', 'WHStocktakeDetail', 'WHTracking', 'WHUnorderedInboundReceive', 'YieldStepReport',
];

function walk(dir: string, out: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (name === '__tests__' || name === 'node_modules') continue;
    if (fs.statSync(p).isDirectory()) walk(p, out);
    else if (p.endsWith('.ts') || p.endsWith('.tsx')) out.push(p);
  }
  return out;
}

/** ⛔ 不剥注释的话，闸会把注释里引用的调用当成真实调用。 */
function stripComments(src: string): string {
  const noBlock = src.replace(/\/\*[\s\S]*?\*\//g, ' ');
  return noBlock
    .split('\n')
    .map((line) => {
      const i = line.indexOf('//');
      return i >= 0 ? line.slice(0, i) : line;
    })
    .join('\n');
}

const NO_PARAM = /(?:navigate|push|replace)\s*\(\s*['"]([A-Za-z_]\w*)['"]\s*\)/g;
const WITH_PARAM = /(?:navigate|push)\s*\(\s*['"]([A-Za-z_]\w*)['"]\s*,/g;

describe('需要参数的详情屏不许被无参导航到达', () => {
  const files = walk(SRC);
  const sources = files.map((f) => ({ f, src: stripComments(fs.readFileSync(f, 'utf8')) }));

  it('阳性对照：确实存在**带**参数导航到这些屏的调用（否则下面那条恒真）', () => {
    let n = 0;
    for (const { src } of sources) {
      for (const m of src.matchAll(WITH_PARAM)) {
        if (PARAM_REQUIRED_ROUTES.includes(m[1] as string)) n += 1;
      }
    }
    expect(n).toBeGreaterThan(20);
  });

  it('阴性对照：解析器能认出无参调用', () => {
    const bad = "navigation.navigate('BatchDetail')";
    expect([...bad.matchAll(NO_PARAM)].map((m) => m[1])).toEqual(['BatchDetail']);
  });

  it('没有任何一处不带参数就导航到这些屏', () => {
    const offenders: string[] = [];
    for (const { f, src } of sources) {
      for (const m of src.matchAll(NO_PARAM)) {
        if (PARAM_REQUIRED_ROUTES.includes(m[1] as string)) {
          const line = src.slice(0, m.index ?? 0).split('\n').length;
          offenders.push(`${path.relative(SRC, f).replace(/\\/g, '/')}:${line} -> ${m[1]}`);
        }
      }
    }
    expect(offenders).toEqual([]);
  });
});
