/**
 * 拼音/首字母模糊搜索工具。
 *
 * 背景 (HANDOFF R-4，2026-08-20 Steve)：新建采购订单选供应商的下拉框原来只能按中文
 * 名精确/模糊匹配，工厂里录单的人手速要求高，中文输入法切换+打全名比敲几个字母慢得多。
 * 诉求是「北京」「beijing」「bjfx」三种输入都要能命中「北京飞熊」。
 *
 * ⚠️ 这一版是**前端方案**：用 pinyin-pro 在浏览器里把候选文本实时转换成拼音/首字母
 * 再做子串匹配，不需要后端改动、不需要 DB migration。
 * 供应商、客户、物料、产品这类主数据下拉未来都会有同样诉求 —— 如果要推广到那几个
 * 下拉，出于性能/一致性考虑应该考虑挪到后端在建表时存一列拼音码、改名时重算
 * (即 HANDOFF R-4 里 Steve 提到的第二种方案)，避免每个下拉各自转换一遍拼音
 * (N 处各自维护 = 会漂，参见 .claude/rules/measurement-and-wiring.md 形态 D)。
 * 这一轮只在采购单供应商下拉上验证效果，先不做后端改动。
 *
 * ⚠️ **已知的形态 D 风险（如实登记，不是消灭它）**：本仓库
 * `web-admin/src/views/system/product-processes/workflow/pinyinInitials.ts`
 * 已经有一套独立的拼音搜索实现（手写汉字→首字母表 + `Intl.Collator` 兜底，
 * 故意不引入依赖），并导出了同名思路的 `usePinyinFilter` composable，
 * 用在工序/原料选择器上。**这里没有复用它**，原因是它只做「首字母」匹配，
 * 不支持「全拼」——按它的实现，"beijing" 匹配不到「北京」，而这正是 R-4 的
 * 硬性判据之一。引入 pinyin-pro 是本任务 brief 里已确认可行的方案。
 * ⇒ 现在这个仓库里有**两份**拼音匹配实现，会漂。如果之后推广到客户/物料/
 * 产品下拉，或者做后端存拼音码，应该把两份合并成一份（优先看 pinyin-pro
 * 能不能完全取代那张手写表），而不是继续在第三个地方再抄一遍。
 */
import { computed, ref, type ComputedRef, type Ref } from 'vue';
import { pinyin } from 'pinyin-pro';

/**
 * 判断单个候选文本是否匹配用户输入的查询词。
 * 依次尝试三种方式，任意一种命中即算匹配：
 *   1. 原始文本包含查询词（不区分大小写）—— 原有的中文模糊匹配，不能丢；
 *   2. 候选文本的全拼（无声调、无空格）包含查询词 —— 例如 "beijing" 命中 "北京";
 *   3. 候选文本的拼音首字母包含查询词 —— 例如 "bjfx" 命中 "北京飞熊"。
 *
 * @param text  候选文本（例如供应商全称/简称），可能为空
 * @param query 用户输入的查询词，可能为空（空查询视为全部匹配，与下拉不过滤语义一致）
 */
export function matchesTextOrPinyin(text: string | null | undefined, query: string | null | undefined): boolean {
  const q = (query || '').trim().toLowerCase();
  if (!q) return true;
  if (!text) return false;

  if (text.toLowerCase().includes(q)) return true;

  // 只有查询词里出现拉丁字母时才值得去算拼音 —— 纯中文/数字查询不可能匹配拼音结果，
  // 提前跳过能省掉大部分候选项的转换开销。
  if (!/[a-z]/i.test(q)) return false;

  const fullPinyin = pinyin(text, { toneType: 'none', type: 'string' }).replace(/\s+/g, '').toLowerCase();
  if (fullPinyin.includes(q)) return true;

  const initials = pinyin(text, { pattern: 'first', toneType: 'none', type: 'string' }).replace(/\s+/g, '').toLowerCase();
  if (initials.includes(q)) return true;

  return false;
}

/**
 * 多字段版本：候选项常常有多个可展示/可搜索的文本（例如供应商的简称 + 全称），
 * 任意一个字段命中即算这个候选项匹配。
 *
 * @param texts 候选项的多个文本字段
 * @param query 用户输入的查询词
 */
export function matchesAnyTextOrPinyin(texts: Array<string | null | undefined>, query: string | null | undefined): boolean {
  return texts.some((text) => matchesTextOrPinyin(text, query));
}

// ─────────────────────────────────────────────────────────────────────────
// 可复用 composable：把 el-select `filter-method` 场景下反复要写的
// "query ref + filter-method handler + visible-change 清空 + filtered computed"
// 打包成一份实现，方便未来推广到客户/物料/产品下拉时直接复用，不用每处重抄一遍
// 过滤逻辑（brief 第 3 点：这段转换/匹配逻辑要对未来推广友好）。
// 与 pinyinInitials.ts 的同名 composable 保持相同的调用形状 (query / handleFilter /
// handleVisibleChange / filtered)，方便两处调用方读起来是同一套心智模型。
// ─────────────────────────────────────────────────────────────────────────

export interface PinyinFilterHandle<T> {
  /** 当前过滤关键字，一般不需要调用方直接读 */
  query: Ref<string>;
  /** 挂给 el-select 的 `:filter-method` */
  handleFilter: (value: string) => void;
  /** 挂给 el-select 的 `@visible-change`：收起下拉时清空过滤关键字，避免下次打开还带着上次的过滤结果 */
  handleVisibleChange: (visible: boolean) => void;
  /** 按当前 query 过滤后的候选项列表，喂给 `v-for` */
  filtered: ComputedRef<T[]>;
}

/**
 * @param source 候选项来源，用 getter（`() => T[]`）而不是直接传 `Ref<T[]>`，
 *   这样调用方可以传一个 computed 表达式而不需要先物化成新 ref。
 * @param textOf 从候选项里取出参与匹配的一个或多个文本字段（例如供应商的简称 + 全称）。
 *   任意一个字段命中原文/全拼/首字母即算这个候选项匹配。
 */
export function usePinyinFilter<T>(
  source: (() => T[]) | Ref<T[]>,
  textOf: (item: T) => Array<string | null | undefined>,
): PinyinFilterHandle<T> {
  const query = ref('');
  const readSource = (): T[] => (typeof source === 'function' ? source() : source.value);

  function handleFilter(value: string): void {
    query.value = value || '';
  }

  function handleVisibleChange(visible: boolean): void {
    if (!visible) query.value = '';
  }

  const filtered = computed(() => {
    const items = readSource();
    const trimmed = query.value.trim();
    if (!trimmed) return items;
    return items.filter((item) => matchesAnyTextOrPinyin(textOf(item), trimmed));
  });

  return { query, handleFilter, handleVisibleChange, filtered };
}
