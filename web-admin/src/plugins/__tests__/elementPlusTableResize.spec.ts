import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { describe, expect, it, vi } from 'vitest';
import { patchTableResize, resizeAdapter, elementPlusTableResize } from '../../../tooling/elementPlusTableResize';

describe('Element Plus table resize adapter', () => {
  it('patches the installed upstream module and rejects import drift', () => {
    const source = readFileSync(resolve('node_modules/element-plus/es/components/table/src/table/style-helper.mjs'), 'utf8');
    expect(patchTableResize(source)).toContain('useResizeObserver as useImmediateResizeObserver');
    expect(() => patchTableResize('unreviewed upstream')).toThrow('upstream import changed');
    const plugin = elementPlusTableResize();
    const transform = plugin.transform;
    if (typeof transform !== 'function') throw new Error('Expected transform hook');
    expect(Reflect.apply(transform, {}, ['unchanged', '/other/style-helper.mjs'])).toBeUndefined();
  });

  it('coalesces delivery, uses latest geometry, preserves exceptions and cancels on disposal', () => {
    let notify: (...args: unknown[]) => void = () => {};
    let dispose: () => void = () => {};
    const frames = new Map<number, () => void>();
    let next = 0;
    const stop = vi.fn();
    const callback = vi.fn();
    const adapter = runInNewContext(`${resizeAdapter}; useResizeObserver`, {
      useImmediateResizeObserver: (_target: unknown, cb: typeof notify) => { notify = cb; return { stop, isSupported: true }; },
      onScopeDispose: (cb: () => void) => { dispose = cb; },
      requestAnimationFrame: (cb: () => void) => { frames.set(++next, cb); return next; },
      cancelAnimationFrame: (id: number) => { frames.delete(id); },
    });
    const result = adapter({}, callback);
    notify('old'); notify('latest');
    expect(callback).not.toHaveBeenCalled();
    expect(frames.size).toBe(1);
    frames.get(1)!(); frames.delete(1);
    expect(callback).toHaveBeenLastCalledWith('latest');
    callback.mockImplementationOnce(() => { throw new Error('real failure'); });
    notify('error');
    expect(() => frames.get(2)!()).toThrow('real failure'); frames.delete(2);
    notify('pending');
    dispose();
    expect(frames.size).toBe(0);
    expect(stop).toHaveBeenCalled();
    notify('after stop');
    expect(frames.size).toBe(0);
    expect(result.isSupported).toBe(true);
  });
});
