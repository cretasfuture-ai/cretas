/**
 * 原料类型表单「包装换算」行的保存前分拣 (D-S2-12)。
 *
 * 新建表单默认带一条空白规则。用户什么都没填就点保存, 以前整张表单被
 * 「请完整填写每条包装规则」拦住, 而那条提示是 3 秒自动消失的 toast ——
 * 验收里读到的是「点保存没有任何反应、也没有请求」, 只能先点「删除」才能存。
 *
 * 现在的口径:
 * - 新加的、包装单位和换算数都没填的行 = 空行, 保存时忽略 (不配包装换算是合法状态);
 * - 只填了一半的行 = 不完整, 必须拦下, 并在那一行下面写清缺什么;
 * - 已保存过的规则 (有 id) 被清空 ≠ 空行: 静默丢掉等于偷偷删配置, 按不完整处理,
 *   提示用户真要删就点「删除」。
 */

export interface PackagingRuleDraft {
  id?: string;
  packageUnit?: string | null;
  conversionFactor?: number | string | null;
}

export type PackagingRuleState = 'EMPTY' | 'COMPLETE' | 'PARTIAL';

function hasUnit(rule: PackagingRuleDraft): boolean {
  return Boolean(rule.packageUnit?.trim());
}

function factorEntered(rule: PackagingRuleDraft): boolean {
  const raw = rule.conversionFactor;
  return raw !== null && raw !== undefined && String(raw).trim() !== '';
}

function factorValid(rule: PackagingRuleDraft): boolean {
  const factor = Number(rule.conversionFactor);
  return factorEntered(rule) && Number.isFinite(factor) && factor > 0;
}

export function packagingRuleState(rule: PackagingRuleDraft): PackagingRuleState {
  if (hasUnit(rule) && factorValid(rule)) return 'COMPLETE';
  if (!hasUnit(rule) && !factorEntered(rule) && !rule.id) return 'EMPTY';
  return 'PARTIAL';
}

/** 不完整那一行要显示的具体原因。 */
export function packagingRuleProblem(rule: PackagingRuleDraft): string | null {
  if (packagingRuleState(rule) !== 'PARTIAL') return null;
  if (!hasUnit(rule) && !factorEntered(rule)) {
    return '这条已保存的包装规则被清空了；如要删除请点右侧「删除」';
  }
  if (!hasUnit(rule)) return '请选择包装单位';
  if (!factorEntered(rule)) return '请填写换算数';
  return '换算数必须大于 0';
}

export interface PackagingRuleSplit<T extends PackagingRuleDraft> {
  /** 要提交的规则 (保持原顺序, 已去掉空行)。 */
  complete: T[];
  /** 原列表下标 → 原因; 非空时必须拦下保存。 */
  problems: Record<number, string>;
}

export function splitPackagingRules<T extends PackagingRuleDraft>(rules: T[]): PackagingRuleSplit<T> {
  const complete: T[] = [];
  const problems: Record<number, string> = {};
  rules.forEach((rule, index) => {
    const state = packagingRuleState(rule);
    if (state === 'COMPLETE') complete.push(rule);
    if (state === 'PARTIAL') problems[index] = packagingRuleProblem(rule) ?? '请完整填写这条包装规则';
  });
  return { complete, problems };
}

/** 保存被拦时的提示 (sticky toast 用): 点名是第几条、缺什么。 */
export function packagingProblemSummary(problems: Record<number, string>): string {
  const parts = Object.entries(problems)
    .sort(([a], [b]) => Number(a) - Number(b))
    .map(([index, reason]) => `第 ${Number(index) + 1} 条：${reason}`);
  return `包装换算未保存 —— ${parts.join('；')}。只想不配包装换算的话，把这一行删掉或两项都留空即可`;
}
