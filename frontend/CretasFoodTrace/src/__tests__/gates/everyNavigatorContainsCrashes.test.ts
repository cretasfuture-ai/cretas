import fs from 'fs';
import path from 'path';

/**
 * 每个 navigator 都要把渲染异常**关在一屏里**，⛔ 不许掀掉整棵导航树。
 *
 * <h3>背景（2026-08-29 逐角色走查实测）</h3>
 *
 * `AppErrorBoundary` 包的是 `<AppNavigator />`，粒度是**整个 App**。
 * 一屏抛异常 → 整个导航器被卸载 → `navigationRef` 变成 `null`，
 * 用户看到的不是「这一页打不开」，而是**整个应用只剩一张错误卡**。
 *
 * 走查里 17 个角色走下来，这一类崩溃有几十处，全部是同一个形状：
 * `Cannot destructure property 'xxxId' of 'route.params'`。
 *
 * <p>react-navigation v7 的 `screenLayout` 正是为这件事准备的：
 * 挂在 navigator 上（76 处），而不是逐屏包（272 处）。
 *
 * <h3>这道闸守的是「接上没有」，不是「写了没有」</h3>
 *
 * ⚠️ 本仓形态 B：机制在、测试绿，但生产上没人调它。
 * 所以闸问的是「**每个 Navigator 开标签**上有没有这个 prop」，
 * 而不是「`ScreenErrorBoundary` 这个文件存不存在」。
 */
const NAV_DIR = path.join(__dirname, '..', '..', 'navigation');

/** 建闸当天的存量豁免。⛔ 只许调小，加进来必须写理由。 */
const EXEMPT: Record<string, string> = {};

function walk(dir: string, out: string[] = []): string[] {
  for (const name of fs.readdirSync(dir)) {
    const p = path.join(dir, name);
    if (fs.statSync(p).isDirectory()) walk(p, out);
    else if (name.endsWith('.tsx')) out.push(p);
  }
  return out;
}

/** 找出每个 `<Xxx.Navigator ...>` 开标签的完整文本（到第一个未配对的 `>`）。 */
function navigatorTags(src: string): string[] {
  const tags: string[] = [];
  const re = /<([A-Za-z_$][\w$]*)\.Navigator\b/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(src)) !== null) {
    let depth = 0;
    let i = m.index + m[0].length;
    for (; i < src.length; i += 1) {
      const c = src[i];
      if (c === '{') depth += 1;
      else if (c === '}') depth -= 1;
      else if (c === '>' && depth === 0) break;
    }
    tags.push(src.slice(m.index, i + 1));
  }
  return tags;
}

describe('导航器把崩溃关在一屏里', () => {
  const files = walk(NAV_DIR);

  it('阳性对照：真的扫到了 navigator（扫到 0 个说明路径错了，⛔ 不是「已经全好了」）', () => {
    const total = files.reduce((n, f) => n + navigatorTags(fs.readFileSync(f, 'utf8')).length, 0);
    expect(total).toBeGreaterThan(30);
  });

  it('阴性对照：解析器能看出「没有 screenLayout」的开标签', () => {
    const fake = '<Stack.Navigator screenOptions={{ headerShown: false }}>';
    const parsed = navigatorTags(fake);
    expect(parsed).toHaveLength(1);
    expect(parsed[0]).toBe(fake);
    expect(String(parsed[0]).includes('screenLayout')).toBe(false);
  });

  it('每个 Navigator 都带 screenLayout', () => {
    const missing: string[] = [];
    for (const f of files) {
      const rel = path.relative(NAV_DIR, f).replace(/\\/g, '/');
      if (EXEMPT[rel]) continue;
      navigatorTags(fs.readFileSync(f, 'utf8')).forEach((tag, i) => {
        if (!tag.includes('screenLayout')) {
          missing.push(`${rel} #${i + 1}: ${tag.slice(0, 70).replace(/\s+/g, ' ')}`);
        }
      });
    }
    expect(missing).toEqual([]);
  });
});
