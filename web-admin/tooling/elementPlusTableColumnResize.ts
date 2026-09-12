import { readFile } from 'node:fs/promises';
import type { Plugin } from 'vite';
import type { Plugin as EsbuildPlugin } from 'esbuild';

const target = /[/\\]element-plus[/\\]es[/\\]components[/\\]table[/\\]src[/\\]table-header[/\\]event-helper\.mjs$/;
const original = 'const minLeft = columnRect.left - tableLeft + 30;';

export function patchTableColumnResize(source: string): string {
  if (source.split(original).length !== 2) {
    throw new Error('Element Plus column resize patch: upstream drag bound changed; review before upgrading');
  }
  // Honor declared min-width while retaining intentionally smaller fixed columns.
  // Both the drag guide and the committed width use this same upstream bound.
  return source.replace(original,
    'const minLeft = columnRect.left - tableLeft + cretasColumnDragMinimum(column, columnRect.width);') + `
const cretasColumnInitialWidths = new WeakMap();
function cretasColumnDragMinimum(column, renderedWidth) {
  if (!cretasColumnInitialWidths.has(column)) cretasColumnInitialWidths.set(column, renderedWidth);
  return Math.max(30, Math.min(cretasColumnInitialWidths.get(column), Number(column.minWidth) || 80));
}
`;
}

export function elementPlusTableColumnResize(): Plugin {
  return { name: 'cretas-element-plus-column-resize', enforce: 'pre',
    transform(source, id) { if (target.test(id)) return { code: patchTableColumnResize(source), map: null }; },
  };
}

export function elementPlusTableColumnResizePrebundle(): EsbuildPlugin {
  return { name: 'cretas-element-plus-column-resize', setup(build) {
    build.onLoad({ filter: /event-helper\.mjs$/ }, async ({ path }) => {
      if (!target.test(path)) return;
      return { contents: patchTableColumnResize(await readFile(path, 'utf8')), loader: 'js' };
    });
  } };
}
