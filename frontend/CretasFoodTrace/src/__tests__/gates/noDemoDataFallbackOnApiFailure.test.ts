import fs from 'fs';
import path from 'path';

/**
 * 接口失败 / 没数据时，**不许**把写死的演示数据顶上去。
 *
 * <h3>本仓第一原则</h3>
 *
 * `CLAUDE.md`：**禁止降级处理 —— 不返回假数据，明确显示错误。**
 *
 * <h3>实测（2026-08-29 逐屏走查 + 真机）</h3>
 *
 * 「财务比率分析」屏上：**92 财务健康分 / 12 良好 / 1 警告 / 13 指标总数**，
 * 配一条小字横幅「当前显示演示数据」。
 *
 * 去问权威（不是猜 404 的含义）：
 * <pre>
 * GET /api/mobile/F006/smart-bi/analysis/finance  ->  404 请求的资源不存在
 * SmartBIAnalysisController 里只有 @PostMapping("/query") 和 ("/drill-down")
 * </pre>
 * ⇒ 这条路由**从来不存在**，两个屏 100% 的时间都在显示演示数据。
 *
 * ▎大号的数 + 小字的免责声明，截出去就是一张假报表。
 *
 * <h3>改成什么</h3>
 *
 * 接口给不出数据 ⇒ 只显示一句「暂未接入，没有可显示的数据」，
 * ⛔ 下面的卡片**整块不渲染** —— 否则空数组会算出「0 财务健康分 / 0 客户」，
 * 那又是一个假读数（0 分看起来是「财务很糟」，而真相是没有数据）。
 *
 * <h3>⚠️ 这道闸的边界</h3>
 *
 * 文本判据，只认 `setXxx(DEMO_*)` / `setXxx(MOCK_*)` 这个形状；
 * 换个变量名或间接引用就看不见。**兜底，不是免检。**
 */
const SCREENS = path.join(__dirname, '..', '..', 'screens');

function walk(dir: string, out: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.tsx')) out.push(p);
  }
  return out;
}

/** ⛔ 先剥注释 —— 否则闸会把说明文字里引用的旧写法当成被测对象（本仓同形四次）。 */
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

/** `setSegments(DEMO_SEGMENTS)` / `setCategories(MOCK_X)` 这类「用假数据填状态」。 */
const DEMO_ASSIGN = /set[A-Z]\w*\(\s*(?:DEMO|MOCK|FAKE|SAMPLE)_[A-Z_]+\s*\)/;

describe('接口没数据时不许拿演示数据顶', () => {
  const files = walk(SCREENS);

  it('阳性对照：真的扫到了屏（扫到 0 个说明路径错了）', () => {
    expect(files.length).toBeGreaterThan(50);
  });

  it('阴性对照：解析器能认出这个形状', () => {
    expect(DEMO_ASSIGN.test('setSegments(DEMO_SEGMENTS);')).toBe(true);
    expect(DEMO_ASSIGN.test('setCategories(mapped ?? []);')).toBe(false);
  });

  it('没有任何一屏在接口失败时把 DEMO_/MOCK_ 常量塞进状态', () => {
    const offenders: string[] = [];
    for (const f of files) {
      const src = stripComments(fs.readFileSync(f, 'utf8'));
      src.split('\n').forEach((line, i) => {
        if (DEMO_ASSIGN.test(line)) {
          offenders.push(`${path.relative(SCREENS, f).replace(/\\/g, '/')}:${i + 1}`);
        }
      });
    }
    expect(offenders).toEqual([]);
  });
});
