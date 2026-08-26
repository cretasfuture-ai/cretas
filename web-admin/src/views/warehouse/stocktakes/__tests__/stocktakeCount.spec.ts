import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import * as stocktakeCount from '../stocktakeCount';
import {
  commitRowFromServer,
  countDisplayUnit,
  countUncountedRows,
  isRowLocked,
  nextCountInputIndex,
  revealedDifference,
  revealedDifferenceType,
  revealedSystemQty,
  type StocktakeCountRow,
} from '../stocktakeCount';

/**
 * 盲盘（blind stocktake）契约闸 —— Steve 2026-08-25 拍板。
 *
 * 这份 spec 的上一版守的是**相反**的行为（"按账面数量填充七行空白"）。
 * 那不是恒真式、也不是变异没生效 —— 它好好地守着一个**现在被判定为缺陷**的行为
 * （账面数锚定实盘数）。需求变了就改断言，但改法是把守的东西从「字面」抬到
 * 「性质」，并补阴性对照，⛔ 不是把断言删掉。
 */

const uncommitted = (over: Partial<StocktakeCountRow> = {}): StocktakeCountRow => ({
  id: 'row-1',
  batchId: 'uuid-1',
  batchNo: 'TRF-1',
  materialCode: 'RMT-1',
  materialName: '带鱼段',
  unit: 'box',
  systemQty: 135,
  actualQty: null,
  notes: '',
  committed: false,
  differenceQty: null,
  differenceType: null,
  ...over,
});

describe('盲盘：提交前看不到账面数', () => {
  it('未提交的行不吐账面数量', () => {
    expect(revealedSystemQty(uncommitted())).toBeNull();
  });

  it('已经打了字但还没提交，照样看不到账面数', () => {
    // 这一条守的是「独立性」: 输入本身不该换来任何账面信息,
    // 否则填一个数试探一下就能把账面数问出来。
    expect(revealedSystemQty(uncommitted({ actualQty: 120 }))).toBeNull();
    expect(revealedDifference(uncommitted({ actualQty: 120 }))).toBeNull();
  });

  it('账面数为 0 的行, 未提交时给 null 而不是 0', () => {
    // 0 是账面数量的合法值。拿 0 当"看不到"的哨兵, 下游就分不清
    // 「这行账面就是 0」和「这行还没提交」。
    expect(revealedSystemQty(uncommitted({ systemQty: 0 }))).toBeNull();
  });
});

describe('盲盘：提交后立刻给反馈, 并锁定该行', () => {
  const committed = uncommitted({
    actualQty: 120,
    committed: true,
    differenceQty: -15,
    differenceType: 'SHORTAGE',
  });

  it('提交后账面数/差异/方向都露出来 —— 反馈闭环必须保留', () => {
    // SP7 §7 的用户画像(仓管员年纪偏大、文化素质参差, 客户原话)决定了
    // 「填完立刻知道对不对」有真实依据。盲盘只把它从填之前挪到填之后,
    // ⛔ 不是把它砍掉。这一条就是防止"顺手把反馈也删了"。
    expect(revealedSystemQty(committed)).toBe(135);
    expect(revealedDifference(committed)).toBe(-15);
    expect(revealedDifferenceType(committed)).toBe('SHORTAGE');
  });

  it('提交后锁定 —— 看到差异再回头改, 等于没盲', () => {
    expect(isRowLocked(committed)).toBe(true);
    expect(isRowLocked(uncommitted({ actualQty: 120 }))).toBe(false);
  });

  it('只有服务端回填这一条路能把行置为已提交', () => {
    const row = uncommitted({ actualQty: 120 });
    const after = commitRowFromServer(row, {
      actualQty: 120,
      differenceQty: -15,
      differenceType: 'SHORTAGE',
    });
    expect(after.committed).toBe(true);
    expect(after.differenceQty).toBe(-15);
    // 原对象不被就地改写 —— 提交失败时不能留下"看起来已提交"的行
    expect(row.committed).toBe(false);
  });
});

describe('差异只认服务端算的那一份', () => {
  it('服务端没给差异时返回 null, ⛔ 不用 actualQty - systemQty 兜底', () => {
    // 兜底会把「服务端没算/没返回」翻译成一个**看起来像真的**的数字。
    // 这两件事对审批人完全不同 —— 读不到就让它可见地缺失。
    const noServerDiff = uncommitted({ actualQty: 120, committed: true, differenceQty: null });
    expect(revealedDifference(noServerDiff)).toBeNull();
    expect(revealedDifference(noServerDiff)).not.toBe(-15);
  });

  it('服务端的值与前端自己减出来的不一致时, 以服务端为准', () => {
    // 后端 actualQty 会 setScale(4)、systemQty 是下发时的**快照**,
    // 前端手里的两个数未必还能减出同一个结果。这一条钉死"谁说了算"。
    const drifted = uncommitted({ actualQty: 120, committed: true, differenceQty: -14.5 });
    expect(revealedDifference(drifted)).toBe(-14.5);
    expect(revealedDifference(drifted)).not.toBe(120 - 135);
  });
});

describe('「未盘点」= 未提交, 不是输入框空着', () => {
  it('填了数但没提交的行仍然算未盘点', () => {
    const rows = [
      uncommitted({ id: 'a', actualQty: 10 }),
      uncommitted({ id: 'b', actualQty: null }),
      uncommitted({ id: 'c', actualQty: 7, committed: true, differenceQty: 0 }),
    ];
    expect(countUncountedRows(rows)).toBe(2);
  });

  it('键盘推进仍然是确定性的', () => {
    expect(nextCountInputIndex(0, 2)).toBe(1);
    expect(nextCountInputIndex(1, 2)).toBeNull();
  });
});

describe('阴性对照：按账面数一键填充的入口不许回来', () => {
  it('模块不再导出任何"按账面数填充"的 helper', () => {
    const exported = Object.keys(stocktakeCount);
    // 阳性对照: 先证明我确实拿到了一个非空的真模块, 否则下面那条恒真。
    expect(exported).toContain('revealedSystemQty');
    expect(exported.length).toBeGreaterThan(5);
    expect(exported.filter((name) => /^fillSystem/i.test(name))).toEqual([]);
  });
});

// ────────────────────────────────────────────────────────────────────────────
// 源码扫描: 一键填充的按钮活在 .vue 模板里, 模块级断言够不着它。
//
// ⚠️ 闸只扫【录入对话框】那一段, 不扫整个文件。
//    审批侧的「差异预览」和审批弹窗**本来就该**显示账面数、也**本来就有**
//    「账实一致」这个 MATCH 标签 —— 整文件扫描会把它们报成违例。
//    实测: 全文件版本在 stocktakes/index.vue 上误报 3 处, 全是审批视图。
//    一道天天误报的闸最后会被关掉, 那时它的覆盖率归零。宁可窄而可信。
//
// ⚠️ 本仓栽过「闸把自己的注释当成被测对象」(memory 2026-08-20, 一晚四次),
//    所以扫描前必须剥注释 —— 而且要**证明剥注释真的生效了**, 见下面的哨兵。
// ────────────────────────────────────────────────────────────────────────────

const VIEWS = resolve(__dirname, '../../..');

function stripComments(source: string): string {
  return source
    .replace(/<!--[\s\S]*?-->/g, ' ')       // HTML/模板注释
    .replace(/\/\*[\s\S]*?\*\//g, ' ')      // JS 块注释
    .replace(/(^|[^:])\/\/[^\n]*/g, '$1 '); // JS 行注释 (放过 https:// 里的 //)
}

/** 抽出 `v-model="countDialogVisible"` 那个 el-dialog 的整段模板。 */
function extractCountDialog(source: string): string {
  const start = source.indexOf('v-model="countDialogVisible"');
  if (start < 0) throw new Error('找不到录入对话框 —— 闸失去了被测对象, 不要当成通过');
  const end = source.indexOf('</el-dialog>', start);
  if (end < 0) throw new Error('录入对话框没有闭合标签 —— 抽取失败');
  return source.slice(start, end);
}

/**
 * 抽出**实盘数量输入框那一个元素**的属性段。
 *
 * ⚠️ 为什么要精确到元素: 第一版这条断言写的是「对话框里有没有 isRowLocked(row)」——
 *    变异实测把实盘输入框的 `:disabled` 摘掉之后闸**没红**, 因为同一个对话框里
 *    备注输入框还挂着同一个字符串。判据太宽 = 它回答的是「在不在里面」,
 *    而我想问的是「是不是它在管」。
 */
function extractActualQtyInput(dialog: string): string {
  const start = dialog.indexOf('v-model="row.actualQty"');
  if (start < 0) throw new Error('录入对话框里找不到实盘数量输入框 —— 闸失去了被测对象');
  const end = dialog.indexOf('/>', start);
  if (end < 0) throw new Error('实盘数量输入框没有闭合 —— 抽取失败');
  return dialog.slice(start, end);
}

const COUNT_DIALOGS = [
  { name: 'warehouse/stocktakes', file: resolve(VIEWS, 'warehouse/stocktakes/index.vue') },
  { name: 'warehouse/semi-finished-stocktakes', file: resolve(VIEWS, 'warehouse/semi-finished-stocktakes/index.vue') },
];

/** 只出现在录入对话框的注释里 —— 剥注释之后必须消失 */
const COMMENT_SENTINEL = '不要改回直接读';

describe.each(COUNT_DIALOGS)('盲盘源码闸 · $name', ({ file }) => {
  const rawDialog = extractCountDialog(readFileSync(file, 'utf8'));
  const dialog = stripComments(rawDialog);

  it('仪器自检：抽到的是录入对话框, 且注释确实被剥掉了', () => {
    // 阳性对照 ①: 抽出来的是那段对话框, 不是空串
    expect(rawDialog.length).toBeGreaterThan(500);
    expect(dialog).toContain('实盘数量');
    expect(dialog).toContain('v-model="row.actualQty"');
    // 阳性对照 ②: 哨兵在原文里有、剥完没有 ⇒ stripComments 不是空操作。
    //             少了这条, 下面每一条 not.toContain 都可能只是"扫了个寂寞"。
    expect(rawDialog).toContain(COMMENT_SENTINEL);
    expect(dialog).not.toContain(COMMENT_SENTINEL);
  });

  it('录入对话框里没有按账面数量填充的入口', () => {
    expect(dialog).not.toContain('按账面数量填入');
    expect(dialog).not.toContain('账实一致'); // 每行那个 link button 的文案
    expect(dialog).not.toMatch(/fillSystemQuantit/);
  });

  it('录入对话框不在前端做 actualQty - systemQty', () => {
    // 差异必须来自服务端 differenceQty。前端再减一遍就是本仓「同一件事两套实现」。
    expect(dialog).not.toMatch(/actualQty\s*-\s*row\.systemQty/);
  });

  it('账面数量不直接插值 row.systemQty, 必须经 revealedSystemQty 出口', () => {
    expect(dialog).not.toMatch(/\{\{\s*row\.systemQty\s*\}\}/);
    expect(dialog).toContain('revealedSystemQty(row)');
  });

  it('提交后锁定：**实盘输入框本身**绑了 isRowLocked', () => {
    const input = extractActualQtyInput(dialog);
    // 阳性对照: 确实抽到了那个元素(否则 toContain 在空串上必然失败, 但错因会看不出来)
    expect(input).toContain('v-model="row.actualQty"');
    expect(input).toContain(':disabled="isRowLocked(row)"');
  });
});

// ────────────────────────────────────────────────────────────────────────────
// 餐饮盘点「完成盘点」弹窗 —— 单记录录入, 形状不同但纪律相同。
// 它不走 stocktakeCount 模块(字段名是 systemQuantity/actualQuantity), 所以
// 单独扫源码。⚠️ 只扫这几个**精确**的字面量, 不做宽泛匹配 ——
// 详情抽屉里 `detailData.systemQuantity` 是审批/查看视图, 本来就该显示。
// ────────────────────────────────────────────────────────────────────────────

describe('盲盘源码闸 · restaurant/stocktaking 完成盘点弹窗', () => {
  const file = resolve(VIEWS, 'restaurant/stocktaking/list.vue');
  const raw = readFileSync(file, 'utf8');
  const code = stripComments(raw);
  const RESTAURANT_SENTINEL = '不要把 row.systemQuantity 带进 completeForm';

  it('仪器自检：文件读到了, 且注释确实被剥掉了', () => {
    expect(raw.length).toBeGreaterThan(1000);
    expect(code).toContain('submitComplete');
    expect(raw).toContain(RESTAURANT_SENTINEL);
    expect(code).not.toContain(RESTAURANT_SENTINEL);
  });

  it('实盘数量不拿账面数预填 —— 预填等于把答案先写进输入框', () => {
    expect(code).not.toMatch(/actualQuantity:\s*row\.systemQuantity/);
  });

  it('录入表单里不带账面库存字段', () => {
    // completeForm 一旦带上 systemQuantity, 它就会被渲染出来
    expect(code).not.toMatch(/completeForm\.systemQuantity/);
  });

  it('提交后才揭示, 且揭示走服务端返回的记录', () => {
    // 阳性对照 + 行为: 录入表单只在未提交时渲染, 揭示块读 completeResult
    expect(code).toContain('v-if="!completeResult"');
    expect(code).toContain('completeResult.differenceQuantity');
  });
});

describe('单位展示未受影响', () => {
  it.each([
    ['box', '盒'],
    ['case', '箱'],
    ['slice', '片'],
    ['kg', 'kg'],
    ['g', 'g'],
  ])('renders canonical %s as %s', (canonical, expected) => {
    expect(countDisplayUnit(canonical)).toBe(expected);
  });
});
