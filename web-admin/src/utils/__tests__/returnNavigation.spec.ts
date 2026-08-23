import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { beforeEach, describe, expect, it } from 'vitest';
import {
  APP_SCROLL_SELECTOR,
  RETURN_QUERY_KEY,
  RETURN_SCROLL_QUERY_KEY,
  buildReturnTo,
  currentAppScrollTop,
  queryWithoutReturnScroll,
  readReturnScroll,
  restoreAppScroll,
  returnContextQuery,
} from '../returnNavigation';

/**
 * 客户 2026-07-21 复测:「通过单据追踪进入到其他业务模块后, 最好可以一键返回原来的
 * 打开单据追踪功能的页面、定位上下滑动的位置。」
 *
 * 分成两半看现状:
 *  - 「一键返回」: `ReturnBanner.vue` 早就在, 全局挂在 AppLayout —— 缺的是**没人喂它参数**
 *  - 「定位滑动位置」: 全仓没有 (路由无 scrollBehavior; 唯一先例 sales/customers/list.vue
 *     读的是 window.scrollY, 而这个应用真正在滚的是 .app-content)
 */

function makeScrollable(el: HTMLElement, contentHeight: number, viewHeight = 200): HTMLElement {
  let top = 0;
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: () => contentHeight });
  Object.defineProperty(el, 'clientHeight', { configurable: true, get: () => viewHeight });
  Object.defineProperty(el, 'scrollTop', {
    configurable: true,
    get: () => top,
    // 真浏览器会夹到 [0, scrollHeight - clientHeight] —— 内容没撑开时设了也白设,
    // 这正是「有界重试」要对付的那个情形
    set: (v: number) => { top = Math.min(Math.max(0, contentHeight - viewHeight), Math.max(0, v)); },
  });
  return el;
}

beforeEach(() => { document.body.innerHTML = ''; });

describe('buildReturnTo —— 把「从哪儿来 + 滚到哪儿」编进一个值', () => {
  it('带上滚动位置', () => {
    expect(buildReturnTo('/production/plans', 420)).toBe(`/production/plans?${RETURN_SCROLL_QUERY_KEY}=420`);
  });

  it('保留原路径上已有的 query', () => {
    const out = buildReturnTo('/production/plans?status=UNFINISHED', 100);
    expect(out).toContain('status=UNFINISHED');
    expect(out).toContain(`${RETURN_SCROLL_QUERY_KEY}=100`);
  });

  it('幂等: 来回几次不会把 _returnScroll 叠成两份', () => {
    const once = buildReturnTo('/production/plans', 100);
    const twice = buildReturnTo(once, 250);
    expect(twice.match(new RegExp(RETURN_SCROLL_QUERY_KEY, 'g'))).toHaveLength(1);
    expect(twice).toContain(`${RETURN_SCROLL_QUERY_KEY}=250`);
  });

  it('在页面顶部时不写这个参数 —— 0 是「没什么可恢复的」, 不该占一个 query 位', () => {
    expect(buildReturnTo('/production/plans', 0)).toBe('/production/plans');
  });

  it('returnContextQuery 产出的键就是 ReturnBanner 认的那个', () => {
    const q = returnContextQuery('/production/plans', 88);
    expect(Object.keys(q)).toEqual([RETURN_QUERY_KEY]);
    expect(q[RETURN_QUERY_KEY]).toContain(`${RETURN_SCROLL_QUERY_KEY}=88`);
  });
});

describe('readReturnScroll —— 读不出来就是「不恢复」, 不许兜出一个假位置', () => {
  it('正常读出', () => {
    expect(readReturnScroll({ [RETURN_SCROLL_QUERY_KEY]: '360' })).toBe(360);
  });

  it.each([
    ['缺参数', {}],
    ['空串', { [RETURN_SCROLL_QUERY_KEY]: '' }],
    ['不是数字', { [RETURN_SCROLL_QUERY_KEY]: 'abc' }],
    ['负数', { [RETURN_SCROLL_QUERY_KEY]: '-5' }],
    ['小数', { [RETURN_SCROLL_QUERY_KEY]: '12.5' }],
    ['零', { [RETURN_SCROLL_QUERY_KEY]: '0' }],
  ])('[阴性] %s → 0', (_label, query) => {
    expect(readReturnScroll(query as Record<string, unknown>)).toBe(0);
  });

  it('query 里同名多值时取最后一个', () => {
    expect(readReturnScroll({ [RETURN_SCROLL_QUERY_KEY]: ['10', '200'] })).toBe(200);
  });
});

describe('currentAppScrollTop —— 量的是 .app-content, ⛔ 不是 window', () => {
  it('读的是 .app-content 的 scrollTop', () => {
    const main = document.createElement('main');
    main.className = 'app-content';
    document.body.appendChild(main);
    makeScrollable(main, 2000);
    main.scrollTop = 640;
    expect(currentAppScrollTop(document)).toBe(640);
  });

  it('[阴性] 没有那个容器时返回 0, 不抛', () => {
    expect(currentAppScrollTop(document)).toBe(0);
  });

  it('阳性对照: 选择器就是布局里那个 —— 拼错了这条会红', () => {
    const layout = readFileSync(
      resolve(__dirname, '..', '..', 'components', 'layout', 'AppLayout.vue'), 'utf8');
    expect(layout).toContain(`class="${APP_SCROLL_SELECTOR.slice(1)}"`);
  });
});

describe('restoreAppScroll —— 返回「有没有真的到位」, 不是「我设过了」', () => {
  it('[阳性] 内容已经撑开 → 一次到位', async () => {
    const main = document.createElement('main');
    main.className = 'app-content';
    document.body.appendChild(main);
    makeScrollable(main, 2000);
    const ok = await restoreAppScroll(500, { doc: document, waitFrame: async () => {} });
    expect(ok).toBe(true);
    expect(main.scrollTop).toBe(500);
  });

  it('[阳性] 内容晚一点才撑开(标签被关过 → 重新取数) → 重试到位', async () => {
    const main = document.createElement('main');
    main.className = 'app-content';
    document.body.appendChild(main);
    makeScrollable(main, 200); // 一开始压根滚不动
    let frames = 0;
    const waitFrame = async (): Promise<void> => {
      frames += 1;
      if (frames === 3) makeScrollable(main, 2000); // 数据回来了, 内容撑开
    };
    const ok = await restoreAppScroll(500, { doc: document, waitFrame, maxAttempts: 10 });
    expect(ok).toBe(true);
    expect(main.scrollTop).toBe(500);
  });

  it('[阴性] 内容始终撑不开 → 返回 false, ⛔ 不许谎报成功', async () => {
    const main = document.createElement('main');
    main.className = 'app-content';
    document.body.appendChild(main);
    makeScrollable(main, 200);
    const ok = await restoreAppScroll(500, { doc: document, waitFrame: async () => {}, maxAttempts: 3 });
    expect(ok).toBe(false);
  });

  it('[阴性] 目标是 0 / 没有容器 → 不做事', async () => {
    expect(await restoreAppScroll(0, { doc: document, waitFrame: async () => {} })).toBe(false);
    expect(await restoreAppScroll(300, { doc: document, waitFrame: async () => {}, maxAttempts: 2 })).toBe(false);
  });
});

describe('queryWithoutReturnScroll —— 用完就摘, 否则刷新又被拽回去', () => {
  it('只摘这一个, 别的 query 原样留着', () => {
    const out = queryWithoutReturnScroll({ status: 'UNFINISHED', [RETURN_SCROLL_QUERY_KEY]: '10', _task: 'x' });
    expect(out).toEqual({ status: 'UNFINISHED', _task: 'x' });
  });
});

describe('接线 —— 生产上谁保证它被调用', () => {
  const listSource = readFileSync(
    resolve(__dirname, '..', '..', 'views', 'production', 'plans', 'list.vue'), 'utf8');
  const layoutSource = readFileSync(
    resolve(__dirname, '..', '..', 'components', 'layout', 'AppLayout.vue'), 'utf8');

  it('「打开销售订单」这一跳带上了返回上下文 (它就在「查看单据追溯」旁边)', () => {
    const fn = listSource.match(/function openSourceOrder[\s\S]*?\n}/);
    expect(fn, '没找到 openSourceOrder').not.toBeNull();
    expect(fn![0]).toContain('returnContextQuery');
    expect(fn![0]).toContain('currentAppScrollTop');
    // 阴性对照: 不许再退回那句光秃秃的 push
    expect(fn![0]).not.toMatch(/router\.push\(target\)/);
  });

  /**
   * ⚠️ 只断言「源码里出现过 restoreAppScroll」是不够的 —— import 语句里就有它。
   * 实测: 把调用整段短路掉(`if (false) {`)之后, 那种写法**一条都不红**。
   * 所以这里剥掉注释和 import 之后再看, 断言的是「它被**调用**了」。
   */
  const layoutBody = layoutSource
    .replace(/\/\*[\s\S]*?\*\//g, '')
    .replace(/^\s*\/\/.*$/gm, '')
    .replace(/^import[\s\S]*?from\s+'[^']*';$/gm, '');

  it('AppLayout 真的在【调用】恢复逻辑 —— 恢复器必须住在拥有滚动容器的那一层', () => {
    expect(layoutBody).toMatch(/readReturnScroll\s*\(/);
    expect(layoutBody).toMatch(/restoreAppScroll\s*\(/);
    expect(layoutBody).toMatch(/queryWithoutReturnScroll\s*\(/);
    // 阴性对照: 不许把恢复整段短路掉还留着 import 充数
    expect(layoutBody).not.toMatch(/if\s*\(\s*false\s*\)/);
  });

  it('阳性对照: 剥 import 这一步真的在干活 (否则上面那条又变成在数 import)', () => {
    expect(layoutSource).toMatch(/^import\s*\{[\s\S]*?restoreAppScroll/m);
    expect(layoutBody).not.toMatch(/^import/m);
  });

  it('ReturnBanner 仍然是全局挂着的 —— 「一键返回」那半靠它显形', () => {
    expect(layoutSource).toContain('<ReturnBanner');
    const banner = readFileSync(
      resolve(__dirname, '..', '..', 'components', 'layout', 'ReturnBanner.vue'), 'utf8');
    expect(banner).toContain(RETURN_QUERY_KEY);
  });
});
