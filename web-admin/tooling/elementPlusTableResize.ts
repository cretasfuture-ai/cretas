import { readFile } from 'node:fs/promises';
import type { Plugin } from 'vite';
import type { Plugin as EsbuildPlugin } from 'esbuild';

const target = /[/\\]element-plus[/\\]es[/\\]components[/\\]table[/\\]src[/\\]table[/\\]style-helper\.mjs$/;
const originalImport = "import { useEventListener, useResizeObserver } from '@vueuse/core';";

// Only Element Plus's two table layout observers use this adapter. Native
// ResizeObserver, other components and error reporting remain untouched.
export const resizeAdapter = `
function useResizeObserver(target, callback, options) {
  let frame = null;
  let disposed = false;
  let latest;
  const result = useImmediateResizeObserver(target, (...args) => {
    latest = args;
    if (frame !== null || disposed) return;
    frame = requestAnimationFrame(() => {
      frame = null;
      if (!disposed) callback(...latest);
    });
  }, options);
  const stop = () => {
    disposed = true;
    if (frame !== null) cancelAnimationFrame(frame);
    frame = null;
    latest = undefined;
    result.stop();
  };
  onScopeDispose(stop);
  return { ...result, stop };
}
`;

export function patchTableResize(source: string): string {
  if (source.split(originalImport).length !== 2) {
    throw new Error('Element Plus table resize patch: upstream import changed; review adapter before upgrading');
  }
  return source.replace(originalImport,
    "import { useEventListener, useResizeObserver as useImmediateResizeObserver } from '@vueuse/core';\nimport { onScopeDispose } from 'vue';") + resizeAdapter;
}

export function elementPlusTableResize(): Plugin {
  return { name: 'cretas-element-plus-table-resize', enforce: 'pre',
    transform(source, id) { if (target.test(id)) return { code: patchTableResize(source), map: null }; },
  };
}

// Vite development prebundling must use the identical patch as release builds.
export function elementPlusTableResizePrebundle(): EsbuildPlugin {
  return { name: 'cretas-element-plus-table-resize', setup(build) {
    build.onLoad({ filter: /style-helper\.mjs$/ }, async ({ path }) => {
      if (!target.test(path)) return;
      return { contents: patchTableResize(await readFile(path, 'utf8')), loader: 'js' };
    });
  } };
}
