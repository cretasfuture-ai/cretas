/**
 * 仓储屏「按钮必须真的能做事」的回归闸。
 *
 * 🔴 2026-08-20 真机走查：仓储工作台上两个按钮**点了都没反应**，成因是两种不同的「空」：
 *
 *   · 「开始打包」 → `onPress` 里**只有一行注释** `// 跳转到打包页面`
 *   · 「确认入库」 → **连 onPress 属性都没有**
 *
 * ⚠️ 这已经是本仓「界面在说一件它不做的事」的第 4、5 例
 *    （前三例见 `warehouseDeadEndsContract.test.ts`：两个按钮跳同一处、
 *      iOS 独有的 Alert.prompt、调拨指向原型屏）。
 *    ⇒ 单独钉一条：**渲染出来的动作按钮，必须挂一个非空的处理函数。**
 *
 * ⚠️ 口径（代理判据，明写）：源码断言，看不见运行时。它能抓住「没有 onPress」和
 *    「onPress 体内只有注释」，⛔ 抓不住「onPress 调了一个自己什么都不做的函数」。
 */
import fs from 'fs';
import path from 'path';

const sourceRoot = path.resolve(__dirname, '../../../');
const read = (rel: string) => fs.readFileSync(path.resolve(sourceRoot, rel), 'utf8');

const home = read('screens/warehouse/home/WHHomeScreen.tsx');

/** 剥注释——⛔ 否则闸会把自己的说明文字当成被测对象（本仓当天栽过四次）。 */
const stripComments = (src: string) =>
  src
    .replace(/\{\/\*[\s\S]*?\*\/\}/g, '')
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/(^|[^:])\/\/.*$/gm, '$1');

describe('仓储工作台 · 动作按钮必须可用', () => {
  const code = stripComments(home);

  it('前提：确实抓到了那两张任务卡（否则下面的断言恒真）', () => {
    expect(code).toContain('renderOutboundCard');
    expect(code).toContain('renderInboundCard');
  });

  it('每个 actionBtn 都挂了 onPress', () => {
    // 找出所有用 styles.actionBtn 的 TouchableOpacity，逐个确认同一标签里有 onPress
    const tags = [...code.matchAll(/<TouchableOpacity[^>]*styles\.actionBtn[^>]*>/g)].map((m) => m[0]);
    expect(tags.length).toBeGreaterThanOrEqual(2);
    const withoutHandler = tags.filter((tag) => !/onPress=/.test(tag));
    expect(withoutHandler).toEqual([]);
  });

  it('onPress 不是空壳（体内只有注释 = 点了没反应）', () => {
    // 空箭头体：onPress={() => {}} 或 onPress={(e) => { e.stopPropagation(); }} 之后没有别的语句
    expect(code).not.toMatch(/onPress=\{\s*\([^)]*\)\s*=>\s*\{\s*\}\s*\}/);
    // 「只 stopPropagation 就完了」也是空壳
    expect(code).not.toMatch(/onPress=\{\s*\([^)]*\)\s*=>\s*\{\s*e\.stopPropagation\(\);\s*\}\s*\}/);
  });

  it('两个按钮各自接到了真实去处', () => {
    expect(code).toMatch(/goToReceiving/);
    expect(code).toMatch(/goToDelivery/);
    // 跨 Stack 要经 getParent，⛔ 不是在首页栈里重复注册那些屏
    expect(code).toMatch(/getParent\(\)/);
  });

  it('⛔ 发货按钮不再说「打包」—— 这套系统的发货流程里没有这一步', () => {
    expect(code).not.toMatch(/outboundTask\.startPacking/);
    expect(code).toMatch(/outboundTask\.startShipping/);
  });
});
