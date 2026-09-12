import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';
import { patchTableColumnResize, elementPlusTableColumnResize } from '../../../tooling/elementPlusTableColumnResize';

const source = readFileSync(resolve('node_modules/element-plus/es/components/table/src/table-header/event-helper.mjs'), 'utf8');

// Execute the installed vendor handler, including its real guide/commit path.
function drag(code: string, initial: number, minWidth: number, delta: number | number[]) {
  const listeners = new Map<string, (event?: { clientX: number }) => void>();
  const proxy = { style: { left: '' } };
  const column = { id: 'column', width: initial, realWidth: initial, minWidth, resizable: true };
  const rect = { left: 100, get right() { return 100 + column.width; }, get width() { return column.width; } };
  const element = { getBoundingClientRect: () => rect, closest: () => element, style: {}, parentNode: {} };
  const emit = vi.fn(), layout = vi.fn();
  const parent = { vnode: { el: { getBoundingClientRect: () => ({ left: 20 }) } }, refs: { resizeProxy: proxy }, emit };
  const useEvent = runInNewContext(code.replace(/^import .*;\r?\n/gm, '').replace('export { useEvent as default };', '') + '\nuseEvent;', {
    getCurrentInstance: () => ({ vnode: { el: { querySelector: () => element } } }),
    inject: () => parent, TABLE_INJECTION_KEY: {}, ref: (value: unknown) => ({ value }),
    isClient: true, isElement: () => true, hasClass: () => false, addClass: () => {}, removeClass: () => {},
    document: { body: { style: {} }, addEventListener: (type: string, cb: (event?: { clientX: number }) => void) => listeners.set(type, cb), removeEventListener: (type: string) => listeners.delete(type) },
    requestAnimationFrame: (cb: () => void) => cb(), setTimeout: (cb: () => void) => cb(),
  });
  const handlers = useEvent({ border: true, allowDragLastColumn: true, store: { scheduleLayout: layout } }, vi.fn());
  for (const movement of Array.isArray(delta) ? delta : [delta]) {
    const previousWidth = column.width;
    const event = { target: element, clientX: rect.right - 2 };
    handlers.handleMouseMove(event, column);
    handlers.handleMouseDown(event, column);
    listeners.get('mousemove')!({ clientX: event.clientX + movement });
    const guideWidth = Number.parseFloat(proxy.style.left) - (rect.left - 20);
    listeners.get('mouseup')!();
    expect(column.realWidth).toBe(column.width);
    expect(emit).toHaveBeenLastCalledWith('header-dragend', guideWidth, previousWidth, column, event);
    expect(layout).toHaveBeenCalledWith(false, true);
    expect(listeners.size).toBe(0);
  }
  return column.width;
}

describe('Element Plus column drag bounds', () => {
  it('reproduces the installed 30px collapse and respects the declared minimum after patching', () => {
    expect(drag(source, 170, 120, -1000)).toBe(30);
    expect(drag(patchTableColumnResize(source), 170, 120, -1000)).toBe(120);
  });
  it('preserves growth, normal shrinking and intentionally narrow fixed columns', () => {
    const patched = patchTableColumnResize(source);
    expect(drag(patched, 170, 120, 80)).toBe(250);
    expect(drag(patched, 250, 120, -60)).toBe(190);
    expect(drag(patched, 40, 80, -1000)).toBe(40);
    expect(drag(patched, 40, 80, [80, -1000])).toBe(40);
    expect(drag(patched, 190, 150, -1000)).toBe(150);
  });
  it('rejects upstream drift and leaves unrelated event helpers unchanged', () => {
    expect(() => patchTableColumnResize('changed')).toThrow('upstream drag bound changed');
    const transform = elementPlusTableColumnResize().transform;
    if (typeof transform !== 'function') throw new Error('Expected transform hook');
    expect(Reflect.apply(transform, {}, [source, '/other/event-helper.mjs'])).toBeUndefined();
  });
});
