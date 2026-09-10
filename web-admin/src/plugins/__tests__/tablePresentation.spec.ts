import { afterEach, expect, it, vi } from 'vitest';
import { installTableTopScrollbar } from '../horizontalScrollbarWheel';

afterEach(() => { document.body.innerHTML = ''; vi.unstubAllGlobals(); });
it('synchronizes both scroll directions, reacts to resize and cleans up its external bar', () => {
  let resize = () => {};
  const disconnect = vi.fn();
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: () => void) { resize = callback; }
    observe() {}
    disconnect = disconnect;
  });
  document.body.innerHTML = '<div><div class="el-table"><div class="el-table__body-wrapper"><div class="el-scrollbar__wrap"><table class="el-table__body"></table></div></div></div></div>';
  const table = document.querySelector<HTMLElement>('.el-table')!;
  const wrap = table.querySelector<HTMLElement>('.el-scrollbar__wrap')!;
  Object.defineProperty(table, 'clientWidth', { value: 400 });
  Object.defineProperty(wrap, 'clientWidth', { value: 400 });
  Object.defineProperty(wrap, 'scrollWidth', { value: 1000, configurable: true });
  const cleanup = installTableTopScrollbar(table);
  const bar = table.previousElementSibling as HTMLElement;
  expect(bar.hidden).toBe(false);
  expect(table.contains(bar)).toBe(false);
  bar.scrollLeft = 120; bar.dispatchEvent(new Event('scroll'));
  expect(wrap.scrollLeft).toBe(120);
  wrap.scrollLeft = 250; wrap.dispatchEvent(new Event('scroll'));
  expect(bar.scrollLeft).toBe(250);
  Object.defineProperty(wrap, 'scrollWidth', { value: 400 }); resize();
  expect(bar.hidden).toBe(true);
  cleanup();
  expect(disconnect).toHaveBeenCalled();
  expect(document.querySelector('.cretas-table-top-scrollbar')).toBeNull();
});
