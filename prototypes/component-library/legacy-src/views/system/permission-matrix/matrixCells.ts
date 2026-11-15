/**
 * 权限矩阵页每一格的取值与高亮判定 —— 纯函数, 不碰 DOM。
 *
 * 抽出来是为了让「哪些格该高亮」这条判据能被单独变异掉 (形态 C):
 * 变异 {@link cellOf} 里的 `diffIndex` 命中逻辑, 组件测试里的高亮断言必须变红。
 */
import type {
  MatrixDiff,
  MatrixLevel,
  MatrixRoleOverride,
  PermissionMatrixView,
} from '@/api/permissionMatrixApi';

export interface MatrixCell {
  /**
   * 这一格**生效**的 L1 值。`null` = L1 表里没有这一行 ⇒ 兜底在真的生效。
   * ⛔ 不要在这里把 null 兜成 '-': 「L1 没管」和「L1 说不给」下游后果相反。
   */
  l1: MatrixLevel | null;
  hardcoded: MatrixLevel;
  /** 展示用的文字: L1 有行就显示 L1 值; L1 无行显示兜底值并由 `fallback` 标注出处 */
  display: MatrixLevel;
  /** 有分歧才非 null; 非 null ⇒ 这一格要高亮 */
  diff: MatrixDiff | null;
  /** true = 显示的是兜底值 (L1 无行) */
  fallback: boolean;
  /** L2 把该模块整个关掉了 ⇒ 置灰 */
  l2Disabled: boolean;
  /** L2 对这个 (role, module) 有覆盖值; null = 没有 */
  l2Override: MatrixLevel | null;
}

const SEP = '\u0000';

export function diffKey(role: string, module: string): string {
  return `${role}${SEP}${module}`;
}

export function buildDiffIndex(diffs: MatrixDiff[]): Map<string, MatrixDiff> {
  const index = new Map<string, MatrixDiff>();
  for (const d of diffs) index.set(diffKey(d.role, d.module), d);
  return index;
}

export function buildL2OverrideIndex(rows: MatrixRoleOverride[]): Map<string, MatrixLevel> {
  const index = new Map<string, MatrixLevel>();
  for (const r of rows) index.set(diffKey(r.role, r.module), r.level);
  return index;
}

/** L2 里 `enabled === false` 的模块集合。缺席 ⇒ 未配置 ⇒ 不置灰。 */
export function buildDisabledModules(view: PermissionMatrixView): Set<string> {
  const disabled = new Set<string>();
  for (const t of view.l2Overrides || []) {
    if (!t.enabled) disabled.add(t.module);
  }
  return disabled;
}

export interface CellContext {
  diffIndex: Map<string, MatrixDiff>;
  l2Index: Map<string, MatrixLevel>;
  disabledModules: Set<string>;
}

export function buildCellContext(view: PermissionMatrixView): CellContext {
  return {
    diffIndex: buildDiffIndex(view.diffs || []),
    l2Index: buildL2OverrideIndex(view.l2RoleOverrides || []),
    disabledModules: buildDisabledModules(view),
  };
}

export function cellOf(
  view: PermissionMatrixView,
  ctx: CellContext,
  role: string,
  module: string,
): MatrixCell {
  const l1Row = view.l1?.[role];
  const hcRow = view.hardcoded?.[role];
  const l1 = (l1Row && Object.prototype.hasOwnProperty.call(l1Row, module))
    ? l1Row[module]
    : null;
  const hardcoded: MatrixLevel = (hcRow && hcRow[module]) || '-';
  const key = diffKey(role, module);
  return {
    l1,
    hardcoded,
    display: l1 === null ? hardcoded : l1,
    diff: ctx.diffIndex.get(key) ?? null,
    fallback: l1 === null,
    l2Disabled: ctx.disabledModules.has(module),
    l2Override: ctx.l2Index.get(key) ?? null,
  };
}

/** 悬浮提示文案。⛔ 措辞里必须出现兜底值本身, 否则读的人不知道分歧在哪。 */
export function diffTooltip(cell: MatrixCell): string {
  if (!cell.diff) return '';
  if (cell.diff.kind === 'override') {
    return `硬编码矩阵: ${cell.hardcoded} (L1 表有行=${cell.l1}, L1 赢, 兜底那格永远到不了)`;
  }
  return `硬编码矩阵: ${cell.hardcoded} (L1 表无此行, 兜底正在真的生效)`;
}

/** 只统计落在给定模块轴上的分歧 —— 轴是投影, 报数必须报口径。 */
export function diffsOnAxis(diffs: MatrixDiff[], axis: string[]): MatrixDiff[] {
  const onAxis = new Set(axis);
  return (diffs || []).filter((d) => onAxis.has(d.module));
}
