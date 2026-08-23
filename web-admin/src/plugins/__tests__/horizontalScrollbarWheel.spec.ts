import { beforeEach, describe, expect, it } from 'vitest';
import {
  canScrollHorizontally,
  handleHorizontalScrollbarWheel,
  horizontalWheelDelta,
  installHorizontalScrollbarWheel,
  isPointerOverNativeHorizontalScrollbar,
  resolveHorizontalScrollbarTarget,
} from '../horizontalScrollbarWheel';

/**
 * 客户 2026-07-21 复测:「鼠标停在横向滚动条上时滑动滚轮能横向滚动」。
 *
 * ⚠️ jsdom 不实现滚动, 也不实现「指针在原生滚动条上」这件事 —— 本文件只能钉住
 * **判据的形状**。真正证明它在浏览器里成立的是下面这组实测(headed Chromium 1400x900,
 * 真实 element-plus 2.13.0 + 真实 src/style.css, 用 page.mouse.wheel 派发真滚轮):
 *
 *   Q1  el-table 的横向滚动条是 EP 自绘的 `.el-scrollbar__bar.is-horizontal`,
 *       高 6px; 它的 wrap `offsetHeight - clientHeight === 0`(原生条被藏了),
 *       横向溢出 1000px  ← 阳性对照, 为 0 则后面所有读数无意义
 *   Q2  [阳性] 指针停在那条 bar 上滚轮 300 → scrollLeft +300, window.scrollY +0
 *   Q4  [阴性] 指针停在表格**正文**上滚轮 300 → scrollLeft 仍为 0
 *   Q3  [阳性] 原生 `overflow-x:auto` 的 6px 滚动条带上滚轮 300 → scrollLeft +300,
 *       且元素确实收到了 wheel 事件(wheelSeen=1) ← 「原生滚动条上 wheel 会派发」不是
 *       推的, 是量的
 *   Q3b [阴性] 同一个元素的**内容区**滚轮 300 → scrollLeft 仍为 0
 */

interface ScrollerOptions {
  scrollWidth: number;
  clientWidth: number;
  /** 有没有原生横向滚动条(px)。EP 假滚动条场景下是 0。 */
  nativeBand?: number;
  borderTop?: number;
  borderBottom?: number;
  rectTop?: number;
  height?: number;
}

/** 造一个「可横滚」的真 DOM 元素 —— jsdom 里这些量全是 0, 必须自己钉。 */
function makeScroller(el: HTMLElement, o: ScrollerOptions): HTMLElement {
  const band = o.nativeBand ?? 0;
  const bt = o.borderTop ?? 0;
  const bb = o.borderBottom ?? 0;
  const height = o.height ?? 100;
  const top = o.rectTop ?? 0;
  Object.defineProperty(el, 'scrollWidth', { value: o.scrollWidth, configurable: true });
  Object.defineProperty(el, 'clientWidth', { value: o.clientWidth, configurable: true });
  Object.defineProperty(el, 'offsetHeight', { value: height, configurable: true });
  Object.defineProperty(el, 'clientHeight', { value: height - band - bt - bb, configurable: true });
  el.style.borderTopWidth = `${bt}px`;
  el.style.borderBottomWidth = `${bb}px`;
  el.getBoundingClientRect = () =>
    ({ top, bottom: top + height, left: 0, right: o.clientWidth, width: o.clientWidth, height, x: 0, y: top }) as DOMRect;
  // 带夹逼的 scrollLeft —— 真浏览器会夹, 「滚到头就别 preventDefault」这条判据靠它
  let left = 0;
  const max = Math.max(0, o.scrollWidth - o.clientWidth);
  Object.defineProperty(el, 'scrollLeft', {
    configurable: true,
    get: () => left,
    set: (v: number) => { left = Math.min(max, Math.max(0, v)); },
  });
  return el;
}

function wheel(init: Partial<WheelEventInit> = {}): WheelEvent {
  return new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaX: 0, deltaY: 0, deltaMode: 0, ...init });
}

/**
 * 从真元素上派发, 让 `event.target` 由 DOM 自己填 —— ⛔ 不许 `Object.assign(ev, {target})`,
 * `target` 只有 getter, 赋值当场抛错(这次算走运: 是报错不是静默失效)。
 *
 * ⚠️ 走的是 **installHorizontalScrollbarWheel 这条真接线**, 不是直接调 handler。
 * 原因实测出来了: document 上的 `wheel` 监听**默认是 passive 的**(规范如此, jsdom
 * 也照做), passive 监听里 `preventDefault()` 是空操作 —— 第一版 helper 自己
 * `addEventListener('wheel', fn)` 不带选项, 于是 `defaultPrevented` 恒为 false。
 * 那正是「横向滚了, 但页面也跟着纵向滚」这个缺陷的形状。⇒ 只测真接线。
 */
function dispatchWheelOn(el: Element, init: Partial<WheelEventInit> = {}): WheelEvent {
  const event = wheel(init);
  const uninstall = installHorizontalScrollbarWheel(document);
  el.dispatchEvent(event);
  uninstall();
  return event;
}

/** EP 假滚动条的真实结构: .el-scrollbar > (.el-scrollbar__wrap + .el-scrollbar__bar.is-horizontal) */
function buildEpScrollbar(): { wrap: HTMLElement; bar: HTMLElement; thumb: HTMLElement } {
  const root = document.createElement('div');
  root.className = 'el-scrollbar';
  const wrap = document.createElement('div');
  wrap.className = 'el-scrollbar__wrap';
  const bar = document.createElement('div');
  bar.className = 'el-scrollbar__bar is-horizontal';
  const thumb = document.createElement('div');
  thumb.className = 'el-scrollbar__thumb';
  bar.appendChild(thumb);
  root.appendChild(wrap);
  root.appendChild(bar);
  document.body.appendChild(root);
  makeScroller(wrap, { scrollWidth: 1600, clientWidth: 600 });
  return { wrap, bar, thumb };
}

beforeEach(() => { document.body.innerHTML = ''; });

describe('horizontalWheelDelta —— 普通鼠标只有纵向分量, 要当横向用', () => {
  it('纵向分量占优时用纵向分量 (这就是客户要的那件事)', () => {
    expect(horizontalWheelDelta({ deltaX: 0, deltaY: 240, deltaMode: 0 })).toBe(240);
  });

  it('横向分量占优时用横向分量 (触控板横扫 / shift+滚轮)', () => {
    expect(horizontalWheelDelta({ deltaX: -120, deltaY: 30, deltaMode: 0 })).toBe(-120);
  });

  it('deltaMode 为行/页时换算成像素, 不能原样当像素用', () => {
    expect(horizontalWheelDelta({ deltaX: 0, deltaY: 3, deltaMode: 1 })).toBe(48);
    expect(horizontalWheelDelta({ deltaX: 0, deltaY: 1, deltaMode: 2 })).toBe(400);
  });
});

describe('isPointerOverNativeHorizontalScrollbar —— 带子高度必须减掉边框', () => {
  it('指针落在底部滚动条带里 → true', () => {
    const el = makeScroller(document.createElement('div'), {
      scrollWidth: 2000, clientWidth: 400, nativeBand: 6, borderTop: 1, borderBottom: 1, height: 48, rectTop: 100,
    });
    // rect.bottom=148, 减下边框 1 → 147, 带子 = 141~147
    expect(isPointerOverNativeHorizontalScrollbar(el, 144)).toBe(true);
  });

  it('指针在内容区 → false', () => {
    const el = makeScroller(document.createElement('div'), {
      scrollWidth: 2000, clientWidth: 400, nativeBand: 6, borderTop: 1, borderBottom: 1, height: 48, rectTop: 100,
    });
    expect(isPointerOverNativeHorizontalScrollbar(el, 110)).toBe(false);
  });

  it('阴性对照: 只有上下边框、没有滚动条的元素, 贴着底边也不算', () => {
    const el = makeScroller(document.createElement('div'), {
      scrollWidth: 2000, clientWidth: 400, nativeBand: 0, borderTop: 1, borderBottom: 1, height: 48, rectTop: 100,
    });
    // 不减边框的话这里会算出 2px 假带子, 于是 146 会被误判成 true
    expect(isPointerOverNativeHorizontalScrollbar(el, 146)).toBe(false);
  });
});

describe('resolveHorizontalScrollbarTarget', () => {
  it('命中 EP 假滚动条(含它内部的 thumb) → 返回同级的 wrap', () => {
    const { wrap, bar, thumb } = buildEpScrollbar();
    expect(resolveHorizontalScrollbarTarget({ target: bar, clientY: 0 } as unknown as WheelEvent)).toBe(wrap);
    expect(resolveHorizontalScrollbarTarget({ target: thumb, clientY: 0 } as unknown as WheelEvent)).toBe(wrap);
  });

  it('阴性对照: 命中的是【纵向】那条 bar → 不返回任何东西', () => {
    const { bar } = buildEpScrollbar();
    bar.className = 'el-scrollbar__bar is-vertical';
    expect(resolveHorizontalScrollbarTarget({ target: bar, clientY: 0 } as unknown as WheelEvent)).toBeNull();
  });

  it('阴性对照: 假滚动条存在但内容并不比容器宽 → 不返回', () => {
    const { wrap, bar } = buildEpScrollbar();
    Object.defineProperty(wrap, 'scrollWidth', { value: 600, configurable: true });
    expect(resolveHorizontalScrollbarTarget({ target: bar, clientY: 0 } as unknown as WheelEvent)).toBeNull();
  });

  it('原生滚动条: 从事件目标沿祖先链找到那个可横滚的容器', () => {
    const outer = makeScroller(document.createElement('div'), {
      scrollWidth: 2000, clientWidth: 400, nativeBand: 6, height: 48, rectTop: 0,
    });
    const inner = document.createElement('span');
    outer.appendChild(inner);
    document.body.appendChild(outer);
    expect(resolveHorizontalScrollbarTarget({ target: inner, clientY: 45 } as unknown as WheelEvent)).toBe(outer);
    // 阴性对照: 同一个元素, 指针在内容区
    expect(resolveHorizontalScrollbarTarget({ target: inner, clientY: 10 } as unknown as WheelEvent)).toBeNull();
  });
});

describe('handleHorizontalScrollbarWheel —— 守的是「真的横向滚了」这个行为', () => {
  it('[阳性] 指针在 EP 假滚动条上, 纵向滚轮 → scrollLeft 前进, 且吃掉默认行为', () => {
    const { wrap, bar } = buildEpScrollbar();
    const event = dispatchWheelOn(bar, { deltaY: 300 });
    expect(wrap.scrollLeft).toBe(300);
    // 不吃掉默认行为的话, 横向滚了、页面也跟着纵向滚 —— 那不是客户要的
    expect(event.defaultPrevented).toBe(true);
  });

  it('[阴性] 指针在表格正文(不是滚动条)上 → 一点都不滚, 也不吃默认行为', () => {
    const { wrap } = buildEpScrollbar();
    const cell = document.createElement('td');
    wrap.appendChild(cell);
    const event = dispatchWheelOn(cell, { deltaY: 300 });
    expect(wrap.scrollLeft).toBe(0);
    expect(event.defaultPrevented).toBe(false);
  });

  it('[阴性] 已经顶到最右端 → 不吃默认行为, 让页面照常纵向滚(否则用户被卡住)', () => {
    const { wrap, bar } = buildEpScrollbar();
    wrap.scrollLeft = 99999; // 夹到 max = 1600-600
    expect(wrap.scrollLeft).toBe(1000);
    const event = dispatchWheelOn(bar, { deltaY: 300 });
    expect(event.defaultPrevented).toBe(false);
  });

  it('[阴性] ctrl+滚轮是浏览器缩放, 不抢', () => {
    const { wrap, bar } = buildEpScrollbar();
    dispatchWheelOn(bar, { deltaY: 300, ctrlKey: true });
    expect(wrap.scrollLeft).toBe(0);
  });
});

describe('installHorizontalScrollbarWheel —— 接线本身', () => {
  it('装上之后, 真派发到 document 的 wheel 会横向滚; 卸载后不再滚', () => {
    const { wrap, bar } = buildEpScrollbar();
    const uninstall = installHorizontalScrollbarWheel(document);
    bar.dispatchEvent(wheel({ deltaY: 120 }));
    expect(wrap.scrollLeft).toBe(120);
    uninstall();
    bar.dispatchEvent(wheel({ deltaY: 120 }));
    expect(wrap.scrollLeft).toBe(120);
  });
});

describe('canScrollHorizontally', () => {
  it('内容更宽才算', () => {
    const el = makeScroller(document.createElement('div'), { scrollWidth: 800, clientWidth: 400 });
    expect(canScrollHorizontally(el)).toBe(true);
    const flat = makeScroller(document.createElement('div'), { scrollWidth: 400, clientWidth: 400 });
    expect(canScrollHorizontally(flat)).toBe(false);
  });
});
