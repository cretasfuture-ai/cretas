import fs from 'fs';
import path from 'path';

/**
 * 后端把「算不出来的比率」改成 `null` 之后，**不能把 null 原样喂给图表库**。
 *
 * <h3>实测（2026-08-29，真机）</h3>
 *
 * 后端把「那一天没有订单」的 `otifRate` 从 100 改成 `null`（PR #333）。
 * `OnTimeDeliveryReportScreen` 里这一行没跟着改：
 *
 * ```ts
 * data: otifData.dailyTrend.slice(-7).map((item) => item.otifRate)
 * ```
 *
 * 图表库对 null 调 `.toFixed` 当场抛，整屏打不开：
 *
 * ```
 * OnTimeDeliveryReport: Cannot read property 'toFixed' of null
 * ```
 *
 * ▎**是同一轮加的屏级错误边界兜住的** —— 否则会掀掉整棵导航树。
 *
 * <h3>为什么 tsc 没拦住</h3>
 *
 * `OnTimeDeliveryReport` 的类型把这些字段声明成 `number`（`target` 还带着
 * 注释 `// 95%`）。**类型说谎，编译器就成了瞎子**。已改成 `number | null`。
 *
 * <h3>这道闸守什么</h3>
 *
 * 喂给 `datasets[].data` 的数组必须先过滤掉空值。⛔ 也不许用 `?? 0` 填 ——
 * 那等于在图上画出「那天准时率 0%」这个假读数。
 *
 * ⚠️ 它是**文本判据**，看不见拼接和间接引用；是兜底，不是免检。
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

/** 剥掉注释 —— ⛔ 否则闸会把说明文字里引用的旧写法当成被测对象（本仓记过）。 */
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

describe('图表数据不许直接吃 null', () => {
  const files = walk(SCREENS);

  it('阳性对照：真的扫到了屏（扫到 0 个说明路径错了）', () => {
    expect(files.length).toBeGreaterThan(50);
  });

  it('阴性对照：解析器能认出「没过滤就 map 进 datasets」的写法', () => {
    const bad = 'data: x.dailyTrend.slice(-7).map((item) => item.otifRate),';
    expect(/data:\s*[\w.$]+(?:\.[\w$]+|\([^)]*\))*\.map\(/.test(bad)).toBe(true);
  });

  it('OnTimeDeliveryReportScreen 的趋势数据先过滤掉了空值', () => {
    const f = path.join(SCREENS, 'reports', 'OnTimeDeliveryReportScreen.tsx');
    expect(fs.existsSync(f)).toBe(true);
    const src = stripComments(fs.readFileSync(f, 'utf8'));

    // 阳性对照：确认读到的是那个屏
    expect(src).toContain('dailyTrend');

    expect(src).toContain('.filter((item) => item.otifRate != null)');
    expect(src)
      .not.toContain('.map((item) => item.otifRate)');
  });
});
