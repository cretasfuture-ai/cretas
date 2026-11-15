/**
 * 工序首检页的表单换算 (纯函数, 单测见 __tests__/firstArticleForm.spec.ts)。
 * 只做「表单字符串 ↔ 接口形状」; 是否合格、标准是否合法一律以服务端 preview 的结论为准, 这里不再判一遍。
 */
import type {
  FirstArticleItem,
  FirstArticleTaskStatus,
  ItemType,
  ResultInput,
  TaskRow,
} from '@/api/firstArticle';

export interface ItemDraft {
  key: string | null;
  name: string;
  type: ItemType;
  required: boolean;
  unit: string;
  lower: string;
  upper: string;
  target: string;
  /** 选项, 用逗号 / 顿号 / 空格分隔 */
  options: string;
  passOptions: string;
}

export function emptyDraft(type: ItemType = 'NUMERIC'): ItemDraft {
  return { key: null, name: '', type, required: true, unit: '', lower: '', upper: '', target: '', options: '', passOptions: '' };
}

export function splitList(s: string): string[] {
  return Array.from(new Set(s.split(/[,，、;；\s]+/).map((x) => x.trim()).filter(Boolean)));
}

/** '' → null; 不是数字 → NaN (交给调用方报错, 不悄悄当成没填) */
export function parseNumber(s: string): number | null {
  const t = s.trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : Number.NaN;
}

/** 表单行 → 接口检验项; 有填了但不是数字的格子 → 返回中文错误, 不提交。 */
export function draftsToItems(drafts: ItemDraft[]): { items: FirstArticleItem[]; error: string | null } {
  const items: FirstArticleItem[] = [];
  for (let i = 0; i < drafts.length; i += 1) {
    const d = drafts[i];
    const label = d.name.trim() || `第 ${i + 1} 项`;
    if (d.type === 'NUMERIC') {
      const lower = parseNumber(d.lower);
      const upper = parseNumber(d.upper);
      const target = parseNumber(d.target);
      if ([lower, upper, target].some((v) => v !== null && Number.isNaN(v))) {
        return { items: [], error: `「${label}」的上下限或标准值不是数字` };
      }
      items.push({ key: d.key, name: d.name, type: 'NUMERIC', required: d.required, unit: d.unit.trim() || null,
        lower, upper, target, options: null, passOptions: null });
    } else {
      items.push({ key: d.key, name: d.name, type: 'OPTION', required: d.required, unit: null,
        lower: null, upper: null, target: null, options: splitList(d.options), passOptions: splitList(d.passOptions) });
    }
  }
  return { items, error: null };
}

export function itemToDraft(item: FirstArticleItem): ItemDraft {
  const s = (v: number | null) => (v == null ? '' : String(v));
  return {
    key: item.key, name: item.name, type: item.type, required: item.required !== false, unit: item.unit ?? '',
    lower: s(item.lower), upper: s(item.upper), target: s(item.target),
    options: (item.options ?? []).join('、'), passOptions: (item.passOptions ?? []).join('、'),
  };
}

/** 判定表单 (key → 输入字符串) → 接口实测值; 空着的不提交 (必检项缺值由服务端报出是哪一项)。 */
export function buildResults(items: FirstArticleItem[], values: Record<string, string>): { results: ResultInput[]; error: string | null } {
  const results: ResultInput[] = [];
  for (const it of items) {
    const key = it.key ?? '';
    const raw = (values[key] ?? '').trim();
    if (!raw) continue;
    if (it.type === 'NUMERIC') {
      const n = parseNumber(raw);
      if (n === null || Number.isNaN(n)) return { results: [], error: `「${it.name}」的实测值不是数字` };
      results.push({ key, numericValue: n, optionValue: null });
    } else {
      results.push({ key, numericValue: null, optionValue: raw });
    }
  }
  return { results, error: null };
}

export interface RowActions { request: boolean; judge: boolean }

/** 工序首检表一行能做什么: 送检 = 进行中且 (没送检 / 不合格待复检); 判定 = 有待判定的单且有质量写权限。 */
export function rowActions(row: Pick<TaskRow, 'taskStatus' | 'firstArticleStatus' | 'latestInspectionId'>,
  perms: { canRequest: boolean; canJudge: boolean }): RowActions {
  const requestable: FirstArticleTaskStatus[] = ['NOT_REQUESTED', 'FAILED'];
  return {
    request: perms.canRequest && row.taskStatus === 'IN_PROGRESS' && requestable.includes(row.firstArticleStatus),
    judge: perms.canJudge && row.firstArticleStatus === 'PENDING' && row.latestInspectionId != null,
  };
}
