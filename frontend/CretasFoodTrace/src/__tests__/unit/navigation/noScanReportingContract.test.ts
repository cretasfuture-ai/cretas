import { readFileSync, existsSync, readdirSync } from 'fs';
import { join } from 'path';

/**
 * 「不存在扫码报工」契约（2026-08-21 owner 裁定）。
 *
 * owner 原话：报工不存在扫码这回事 —— 工人是「已经被分配到了这道工序」，
 * 或者「手上有这个产品」，**点进去直接输入就好了**。
 *
 * 被删掉的 ScanReportScreen 有三重问题，任何一条单独都够删：
 *   ① 全仓**没有任何 navigate 入口** —— 只在两个 Navigator 里注册着，工人根本进不去。
 *   ② 它提交的 `POST /processing/batches/:id/report` **后端从来没有过**
 *      （不是被删，是从没实现）。失败后 catch 静默存成「离线草稿」，
 *      于是它看起来像网络问题，实际上**一条都没成功过**。
 *   ③ 它的采集模型比真路早一代：它收「产量/合格数/不良数」，
 *      而真路 `YieldReportRequest` 要「本道投入 + 本道产出 + 工时 + 人数」，
 *      且**根本没有 good/defect 这两个概念**（实测 grep 命中 0）。
 *      ⇒ 硬映射过去只会造出假数据，所以不是改个 URL 的事。
 *
 * ⚠️ 本文件用 jest（web-admin 才是 vitest）：`expect(x, '消息')` 的第二参数
 *    jest 不吃，会整套编译失败并显示 `Tests: 0 total`。断言消息写成上一行注释。
 */

const SRC = join(__dirname, '../../../..', 'src');
const read = (p: string) => readFileSync(join(SRC, p), 'utf-8');

/**
 * 剥注释再扫。
 * ⛔ 不能省：本次的说明注释里原样写了 "ScanReport"、"batches/:id/report" 等词，
 * 不剥的话下面的断言会命中我们自己写的说明（本仓 2026-08-20 一晚栽过四次）。
 * ⛔ 也不要为了让闸变绿去删注释 —— 该改的是尺子。
 */
const strip = (s: string) =>
  s
    .replace(/\{\s*\/\*[\s\S]*?\*\/\s*\}/g, '')  // JSX 注释
    .replace(/\/\*[\s\S]*?\*\//g, '')            // 块注释
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1');       // 行注释

/** 递归收集 src 下的 .ts/.tsx（跳过测试自身，避免闸扫到自己）。 */
function allSources(dir = SRC, out: string[] = []): string[] {
  for (const e of readdirSync(dir, { withFileTypes: true })) {
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      if (e.name === '__tests__' || e.name === 'node_modules') continue;
      allSources(p, out);
    } else if (/\.tsx?$/.test(e.name)) {
      out.push(p);
    }
  }
  return out;
}

describe('不存在扫码报工', () => {
  it('前提: 真路的屏还在（否则下面几条在守一个空壳）', () => {
    // 阳性对照。真路没了的话，「删掉扫码报工」就不是收敛而是砍功能。
    expect(existsSync(join(SRC, 'screens/processing/YieldStepReportScreen.tsx'))).toBe(true);
    expect(existsSync(join(SRC, 'screens/processing/OperatorAssignedProcessScreen.tsx'))).toBe(true);
    expect(existsSync(join(SRC, 'screens/processing/ProcessTaskListScreen.tsx'))).toBe(true);
  });

  it('扫码报工的两个屏已删除', () => {
    expect(existsSync(join(SRC, 'screens/processing/ScanReportScreen.tsx'))).toBe(false);
    expect(existsSync(join(SRC, 'screens/processing/ScanReportSuccessScreen.tsx'))).toBe(false);
  });

  it('没有任何导航还注册着 ScanReport', () => {
    for (const p of ['navigation/ProcessingStackNavigator.tsx',
                     'navigation/workshop-supervisor/WSBatchesStackNavigator.tsx']) {
      const s = strip(read(p));
      expect(s).not.toContain('ScanReport');
    }
  });

  it('⛔ 那个后端从来没有过的地址, 全仓不许再出现', () => {
    /*
     * 这条钉的是**地址本身**，不是某个方法名 —— 换个函数名再打同一个地址
     * 是同一个缺陷（本仓形态 B: 机制在、接的是不存在的那一头）。
     */
    const offenders = allSources()
      .filter((p) => /batches\/\$\{[^}]*\}\/report(?!s)/.test(strip(readFileSync(p, 'utf-8'))));
    expect(offenders).toEqual([]);
  });

  it('阴性对照: 真路那个地址(注意结尾是 reports)仍然在用', () => {
    /*
     * ⛔ 没有这一条，上面那条可以靠「把所有报工都删光」满足。
     * 真路: POST /production/batches/:batchId/reports —— YieldReportController。
     */
    const yieldApi = strip(read('services/api/yieldReportApi.ts'));
    expect(yieldApi).toContain('/reports');
  });

  it('草稿屏不再提供「直接提交」—— 那个按钮打的是死路', () => {
    /*
     * 草稿有三个来源（useReportWorkflow / DynamicReportScreen / TeamBatchReportScreen），
     * 而原来的 handleResubmit **对所有草稿一律调那条不存在的地址**（没按 draftType 分发）。
     * ⛔ 修法不是猜一个端点去分发 —— 草稿的字段映射不到真路的模型；
     *    「恢复编辑」已经按 draftType 跳回各自的报工屏，那才是走得通的路。
     */
    const s = strip(read('screens/processing/DraftReportsScreen.tsx'));
    expect(s).not.toContain('handleResubmit');
    expect(s).not.toContain('submitWorkReport');
    // 阳性对照: 恢复编辑这条路必须还在, 否则草稿就成了死数据
    expect(s).toContain('handleRestore');
  });
});
