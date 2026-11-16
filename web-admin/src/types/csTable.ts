/**
 * Nuxt UI 表格/页签的最小类型（只含本项目用到的字段）。
 * 不直接 `import type … from '@nuxt/ui'`：主入口类型会把整套组件类型带进 vue-tsc，
 * 与 @vue-flow 的 Node 类型撞出 30 处 TS2322（approval-workflow-editor 等 3 个未改动文件），
 * CI vue-tsc 红。这里的形状与 TanStack ColumnDef / Nuxt UI TabsItem 兼容。
 */
interface CsSortableRow {
  getValue: (columnId: string) => unknown;
}
export interface CsTableColumn<T> {
  accessorKey?: Extract<keyof T, string>;
  /** 派生列: 排序/取值用的值 (单元格显示仍走 #列id-cell 插槽)。 */
  accessorFn?: (row: T) => unknown;
  id?: string;
  header?: string;
  /** false = 该列不可点表头排序。 */
  enableSorting?: boolean;
  /** 自定义排序 (如按中文拼音), 参数是 TanStack Row 的最小子集。 */
  sortingFn?: (a: CsSortableRow, b: CsSortableRow, columnId: string) => number;
  meta?: { class?: { th?: string; td?: string } };
}
export interface CsTabItem {
  label: string;
  value: string;
  slot?: string;
}
