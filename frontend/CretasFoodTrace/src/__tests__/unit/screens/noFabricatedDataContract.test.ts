/**
 * 「禁止降级处理 —— 不返回假数据, 明确显示错误」(CLAUDE.md 核心原则 1) 的回归闸。
 *
 * 四个缺陷是同一条原则的四次违反, 所以放在同一个文件里:
 *   1. QITrendScreen  请求失败时用 Math.random() 编一整屏合格率/评分/等级分布
 *   2. QIFormScreen   打开就是一份预填好的 93 分合格报告, 唯一提交闸是 `if (!batch) return`
 *   3. StocktakingExecuteScreen  账面数渲染在实盘输入框正上方 (盘点被锚定)
 *   4. QIReportScreen 弹「报告生成成功, 已保存到文件」, 而后端那个端点根本不存在
 *                     ——「不返回假数据」的另一面是**不要声称一个没发生的结果**
 *
 * ⚠️ 源码断言一律**先剥注释**再扫。
 * 这几个文件的修复注释里逐字写着 `Math.random()`、`18/20/16/19/20`、
 * `record.systemQuantity` —— 不剥注释的闸会把**我自己写的说明**当成被测对象,
 * 得出一个与事实相反的读数。(本仓已有先例, 一晚踩过四次。)
 */
import fs from 'fs';
import path from 'path';

import {
  checkQualityInspectionSubmission,
} from '../../../screens/quality-inspector/qualityInspectionSubmission.logic';

const SCREENS = path.resolve(__dirname, '../../../screens');

/** 去掉 // 行注释、块注释和 JSX 注释, 只留会执行的代码。 */
function stripComments(source: string): string {
  return source
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^[ \t]*\/\/.*$/gm, '');
}

function readCode(relativePath: string): string {
  return stripComments(fs.readFileSync(path.join(SCREENS, relativePath), 'utf8'));
}

function readRaw(relativePath: string): string {
  return fs.readFileSync(path.join(SCREENS, relativePath), 'utf8');
}

type Locale = 'zh-CN' | 'en-US';

function rawLocale(locale: Locale): string {
  return fs.readFileSync(
    path.resolve(__dirname, `../../../i18n/locales/${locale}/quality.json`),
    'utf8',
  );
}

function readLocale(locale: Locale): Record<string, any> {
  return JSON.parse(rawLocale(locale));
}

function readApi(): string {
  return fs.readFileSync(
    path.resolve(__dirname, '../../../services/api/qualityInspectorApi.ts'),
    'utf8',
  );
}

/** 按 `a.b.c` 取值; 取不到返回 undefined (不兜底成空串 —— 那会把"缺失"读成"有")。 */
function resolveKey(obj: Record<string, any>, key: string): unknown {
  return key.split('.').reduce<any>((acc, part) => (acc == null ? undefined : acc[part]), obj);
}

// ============================================================
// 仪器自检: 先证明 stripComments 真的在剥, 否则下面每条断言都可能恒真
// ============================================================
describe('gate instrument self-check', () => {
  it('strips comments, so the gate cannot match its own explanatory prose', () => {
    const withComment = 'const a = 1;\n// Math.random()\n/* Math.random() */\nconst b = 2;';
    expect(withComment).toContain('Math.random()'); // 阳性对照: 原文里确实有
    expect(stripComments(withComment)).not.toContain('Math.random()');
    expect(stripComments(withComment)).toContain('const b = 2;');
  });

  it('the trend screen really does mention Math.random() in prose — so the strip matters here, not hypothetically', () => {
    // 这条是上一条的"接上没有": 证明剥注释在**这个具体文件**上是必需的,
    // 而不是一个用不上的通用工具。
    expect(readRaw('quality-inspector/QITrendScreen.tsx')).toContain('Math.random()');
  });
});

// ============================================================
// 缺陷 1: 趋势屏
// ============================================================
describe('QITrendScreen does not fabricate trend data', () => {
  const code = readCode('quality-inspector/QITrendScreen.tsx');

  it('has no Math.random() anywhere in executable code', () => {
    expect(code).not.toContain('Math.random');
  });

  it('renders an explicit error state instead of substituting data on failure', () => {
    expect(code).toContain('setError(');
    expect(code).toContain('if (error && !points)');
    // 失败路径必须把数据清空 —— 留着上一次的数据同样是"显示的不是现在的真相"
    expect(code).toContain('setPoints(null)');
  });

  it('calls the quality-inspection trend endpoint, not the production dashboard one', () => {
    const api = stripComments(
      fs.readFileSync(
        path.resolve(__dirname, '../../../services/api/qualityInspectorApi.ts'),
        'utf8',
      ),
    );
    const getTrendData = api.slice(api.indexOf('async getTrendData'));
    expect(getTrendData).toContain('/processing/quality/trends');
    expect(getTrendData).not.toContain('/reports/dashboard/trends');
  });

  it('does not print a hardcoded conclusion about the trend', () => {
    // 原文案:「合格率整体保持稳定, 建议关注波动较大的时段」—— 与数据无关, 恒打印。
    const zh = JSON.parse(
      fs.readFileSync(
        path.resolve(__dirname, '../../../i18n/locales/zh-CN/quality.json'),
        'utf8',
      ),
    );
    expect(zh.trend.passRateInsight).toBeUndefined();
    expect(zh.trend.avgScoreInsight).toBeUndefined();
    expect(zh.trend.countInsight).toBeUndefined();
    // 阴性对照: 取而代之的说明必须存在, 否则这条断言只是"删干净了"而不是"换对了"
    expect(typeof zh.trend.coverageNote).toBe('string');
    expect(zh.trend.coverageNote).toContain('{{total}}');
  });
});

// ============================================================
// 缺陷 2: 质检表单
// ============================================================
describe('QIFormScreen requires a real judgement before submitting', () => {
  const code = readCode('quality-inspector/QIFormScreen.tsx');
  const ITEM_IDS = ['appearance', 'smell', 'specification', 'weight', 'packaging'];

  it('starts with no prefilled scores and no prefilled notes', () => {
    expect(code).toContain('useState<Record<string, DraftScoreItem>>({})');
    // 那 7 条预填备注一条都不许留在可执行代码里
    for (const note of [
      '色泽正常',
      '形态完整',
      '符合标准尺寸',
      '符合标准',
      '包装完整',
      '标签清晰',
    ]) {
      expect(code).not.toContain(`'${note}'`);
    }
  });

  it('blocks submission and says which items are missing (behaviour, not source text)', () => {
    const nothingScored = checkQualityInspectionSubmission(ITEM_IDS, {}, 10);
    expect(nothingScored.canSubmit).toBe(false);
    expect(nothingScored.unscoredItemIds).toEqual(ITEM_IDS);

    const partiallyScored = checkQualityInspectionSubmission(
      ITEM_IDS,
      {
        appearance: { score: 18 },
        smell: { score: 20 },
        specification: { score: 16 },
      },
      10,
    );
    expect(partiallyScored.canSubmit).toBe(false);
    expect(partiallyScored.unscoredItemIds).toEqual(['weight', 'packaging']);
  });

  it('treats 0 as a real score, not as "not yet scored"', () => {
    // 这是本次修复的要点: `?? 0` 式兜底会把"我还没看"翻译成"我看了, 是 0 分"。
    const allZero = checkQualityInspectionSubmission(
      ITEM_IDS,
      Object.fromEntries(ITEM_IDS.map((id) => [id, { score: 0 }])),
      10,
    );
    expect(allZero.unscoredItemIds).toEqual([]);
    expect(allZero.canSubmit).toBe(true);
  });

  it('rejects an unusable sample size', () => {
    const scored = Object.fromEntries(ITEM_IDS.map((id) => [id, { score: 20 }]));
    expect(checkQualityInspectionSubmission(ITEM_IDS, scored, 0).canSubmit).toBe(false);
    expect(checkQualityInspectionSubmission(ITEM_IDS, scored, NaN).canSubmit).toBe(false);
    // 阳性对照: 合法输入必须能过, 否则上面两条可能只是"这个闸永远返回 false"
    expect(checkQualityInspectionSubmission(ITEM_IDS, scored, 10).canSubmit).toBe(true);
  });

  it('wires the screen to that same check — a gate nobody calls guards nothing', () => {
    expect(code).toContain('checkQualityInspectionSubmission(');
    expect(code).toContain('validateBeforeSubmit()');
    // 旧的静默闸不许回来
    expect(code).not.toContain('if (!batch) return;\n\n    try {');
  });

  it('does not show a total score / grade before every item is scored', () => {
    expect(code).toContain('totalScore === null');
    expect(code).toContain('grade === null');
  });
});

// ============================================================
// 缺陷 3: 盘点执行 (盲盘)
// ============================================================
describe('Stocktaking counting screens do not anchor the counter', () => {
  const execute = readCode('restaurant/stocktaking/StocktakingExecuteScreen.tsx');
  const list = readCode('restaurant/stocktaking/StocktakingListScreen.tsx');

  it('does not render the book quantity while the count is being entered', () => {
    expect(execute).toContain('onChangeText={setActualQty}'); // 阳性对照: 输入框还在

    /*
     * 这条断言的第一版写成「取 `result ? (` 之前的那一段, 断言它不含账面数」。
     * 变异对照当场证明它是错的: 揭盲分支在文件里排在**录入表单之前**, 所以那个
     * 切片根本不包含录入区 —— 把账面数放回录入框上方, 闸纹丝不动。
     * (形态 A: 我想知道的是"录入区有没有", 我实际在量的是"文件开头那一段有没有"。)
     *
     * 改成按**变量**判, 不按位置判 —— 这两个名字本身就分开了盘前/盘后:
     *   `record` = 提交**前**加载的记录 → 它的 systemQuantity 一次都不许渲染
     *   `result` = 提交**后**后端返回的记录 → 揭盲用的就是它
     */
    expect(execute).not.toContain('record.systemQuantity');
  });

  it('reveals the variance only after submitting, using the server-computed value', () => {
    expect(execute).toContain('setResult(completed)');
    expect(execute).toContain('result.differenceQuantity');
    expect(execute).toContain('result.systemQuantity');
    // 差异算不出来时显示 "—" —— `?? 0` 会把"没算出来"说成"差异为 0"
    expect(execute).not.toContain('differenceQuantity ?? 0');
  });

  it('does not leak the book quantity on the list card that opens the count screen', () => {
    // 列表卡片是执行屏的入口; 只改执行屏而留着这里, 等于没改。
    expect(list).toContain("r.status === 'COMPLETED'");
    const systemQtyIndex = list.indexOf('r.systemQuantity');
    const guardIndex = list.indexOf("r.status === 'COMPLETED'");
    expect(systemQtyIndex).toBeGreaterThan(guardIndex);
  });
});

// ============================================================
// 缺陷 4: 报告导出屏 —— 声称一个从未发生的结果
//
// 「不返回假数据」的另一面: **不要声称一个没发生的结果**。
// 这个屏原来弹「报告生成成功, 已保存到文件」, 而
//   (a)「查看报告」按钮的实现是 console.log;
//   (b) 它调的 POST /reports/quality/generate 后端全仓零命中 ⇒ 必然 404,
//       也就是这个成功文案**一次都没有真的出现过的前提**;
//   (c) 后端那两个 GET /reports/export/* 端点是桩 —— 返回 200 +
//       filename=report.pdf, 正文是 "PDF export not yet implemented"。
// 所以这个屏既不能报成功, 也不能改指向 (c) —— 那是把一个诚实的 404
// 换成一个无声的假 PDF。
// ============================================================
describe('QIReportScreen does not claim a result that never happened', () => {
  const code = readCode('quality-inspector/QIReportScreen.tsx');
  const api = stripComments(readApi());
  const zh = readLocale('zh-CN');
  const en = readLocale('en-US');

  it('has no fake "view report" action — the old one was a console.log', () => {
    expect(code).not.toContain('console.log');
    expect(code).not.toContain('Open report');
  });

  it('does not call an endpoint the backend does not expose', () => {
    expect(code).not.toContain('generateReport(');
    expect(api).not.toContain('/reports/quality/generate');
    // 阳性对照: 剥注释后 api 里仍有真实端点 —— 证明上一条不是"把文件读空了"
    expect(api).toContain('/processing/quality/trends');
  });

  it('is not wired to the export stubs — they answer 200 with a fake PDF', () => {
    // 桩返回 Content-Disposition: filename=report.pdf 而正文是一句英文,
    // 用户会下载到一个打不开的 PDF 而屏幕报成功。404 是诚实的失败,
    // 200 + 假 PDF 是无声的失败 —— 后者更糟, 所以这条闸钉死不许接。
    expect(code).not.toContain('/reports/export/');
    expect(api).not.toContain('/reports/export/');
  });

  it('no longer promises a saved file', () => {
    for (const [name, dict] of [['zh-CN', zh], ['en-US', en]] as const) {
      expect({ name, generateSuccess: dict.report.generateSuccess }).toEqual({
        name,
        generateSuccess: undefined,
      });
      expect(dict.report.viewReport).toBeUndefined();
      expect(dict.report.generateFailed).toBeUndefined();
    }
    // 承诺的关键词在整份 locale 里都不许再出现 (不止那一个键)
    expect(JSON.stringify(zh)).not.toContain('已保存到文件');
    expect(JSON.stringify(en)).not.toContain('saved to file');
    // 阴性对照: 取而代之的说明必须存在 —— 否则这只是"删干净了"而不是"换对了"
    expect(typeof zh.report.unavailableTitle).toBe('string');
    expect(typeof zh.report.unavailableBody).toBe('string');
    expect(typeof en.report.unavailableTitle).toBe('string');
  });

  it('resolves every i18n key it renders — a missing key renders as the key string itself', () => {
    /*
     * 这个屏原来的 Alert 标题是 t('clock.clockSuccess') / t('clock.clockFailed')
     * / t('clock.alert')。quality.json 当时有**两个** "clock" 块, JSON 重复键
     * 后者胜出, 而这三个键只在**前一个(死的)**块里 ⇒ i18next 回退成打印键名,
     * 用户看到的标题字面就是 `clock.clockSuccess`。
     *
     * 所以这条不守"别用考勤的键"这个字面, 守的是**性质**: 屏幕渲染的每个键
     * 都必须真的解析得出来。
     */
    const keys = [...code.matchAll(/\bt\(\s*'([^']+)'/g)]
      .map((m) => m[1])
      .filter((k): k is string => typeof k === 'string');
    // 仪器自检: 正则必须真的抓到键, 否则下面的循环是空转而这条恒绿
    expect(keys.length).toBeGreaterThanOrEqual(7);

    for (const key of keys) {
      expect({ key, zh: typeof resolveKey(zh, key) }).toEqual({ key, zh: 'string' });
      expect({ key, en: typeof resolveKey(en, key) }).toEqual({ key, en: 'string' });
    }

    // 阳性对照: 同一个解析器面对不存在的键必须给 undefined, 否则上面全是恒真
    expect(resolveKey(zh, 'report.noSuchKeyExists')).toBeUndefined();
    // 当初漏掉的那三个键, 在生效的 clock 块里确实不存在
    expect(resolveKey(zh, 'clock.clockSuccess')).toBeUndefined();
  });
});

// ============================================================
// 载体闸: 重复的顶层键会让**前一个块整块变成死代码**, 且不报错
// ============================================================
describe('quality.json is unambiguous', () => {
  it('defines each top-level key exactly once — a duplicate silently wins', () => {
    for (const locale of ['zh-CN', 'en-US'] as const) {
      const topKeys = [...rawLocale(locale).matchAll(/^ {2}"([A-Za-z0-9_]+)":/gm)]
        .map((m) => m[1])
        .filter((k): k is string => typeof k === 'string');
      // 仪器自检: 抓不到顶层键就说明正则/缩进假设错了, 而不是"没有重复"
      expect({ locale, count: topKeys.length > 15 }).toEqual({ locale, count: true });
      const duplicates = [...new Set(topKeys.filter((k, i) => topKeys.indexOf(k) !== i))];
      expect({ locale, duplicates }).toEqual({ locale, duplicates: [] });
    }
  });
});
