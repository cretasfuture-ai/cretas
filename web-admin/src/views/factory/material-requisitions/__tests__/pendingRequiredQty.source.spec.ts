import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');

/**
 * 客户 2026-08-03 (Sheet 第 45 行):「没有途径可以配置配方用量」。
 *
 * 后端 generateFromPlan 原本对「BOM 只登记配方资格、没有参考用量」的行抛 409, 提示去
 * BOM 填标准用量 —— 而 BOM 编辑器对 RAW/AUXILIARY 强制 standardQuantity=null、界面上
 * 根本没有那个输入框。现在后端改为留 requiredQty=null 照常出单, 前端必须把 null 和 0
 * 区分开: toNumber(null) 是 0, 一路走下去会印成「0」, 车间照单领 0 就是短料。
 *
 * 断言都落在「null 不会变成 0」这一条不变量上, 不是落在具体文案。
 */
describe('物料需求单「用量待定」行不能退化成 0 (Sheet 第 45 行)', () => {
  it('需求量展示统一走 requiredQtyLabel, 不再直接 qty(requiredQty) / prop 裸绑', () => {
    // 两处展示 (明细表 + 确认领料弹窗) 都必须换成 label
    expect(source.match(/requiredQtyLabel\(row\)/g)).toHaveLength(2);
    expect(source).not.toContain('{{ qty(row.requiredQty) }}');
    expect(source).not.toContain('<el-table-column prop="requiredQty"');
  });

  it('isPendingQty 把 null/undefined/空串 与 0 区分开 —— 0 是真的要 0, null 是还不知道', () => {
    expect(source).toContain('function isPendingQty');
    // 判据必须是「值是不是缺失」, 不能写成 !item.requiredQty (那样 0 也会被当成待定)
    expect(source).toMatch(/requiredQty === null[\s\S]{0,120}requiredQty === undefined/);
    expect(source).not.toContain('!item.requiredQty');
  });

  it('待定行不预填拣货数量 —— 预填 0 会让人一路点确认, 那味料一点没调过去', () => {
    expect(source).toContain('isPendingQty(item) ? null : toNumber(item.requiredQty)');
  });

  it('待定行留空不许提交, 且要指名是哪几味料', () => {
    expect(source).toContain('missingPending');
    // 错误提示按 fool-proof「4 位一体」: sticky + 可关 + 指名 + 说清下一步
    expect(source).toMatch(/missingPending\.map\(\(row\) => row\.materialName\)/);
    expect(source).toMatch(/missingPending[\s\S]{0,400}duration: 0/);
  });

  /**
   * 🔴 2026-09-02 owner 卡点: 上面这条闸原来写成 `!(Number(row.pickedInput) > 0)` ——
   * 把「显式填 0」和「没填(null)」当成了同一件事一起拦下来。0 是「这次一个都不领」的
   * 明确事实, null 才是这道闸原本要防的「不知道」。LIUSHANMEN 现场 7 味辅料库存全是 0,
   * 用户如实填 0 却被拒绝提交。
   *
   * 这条断言原本钉的是 `!(Number(row.pickedInput) > 0)` 这个具体实现字面量 ——
   * 那是**历史实现**, 不是**需求**(需求是「没填才拦, 0 不拦」); 按 C‴ 判据只改断言,
   * 不删这道闸。行为层面的阳性/阴性对照(0 放行 / null 仍拦)见同目录
   * confirmPickingZeroAllowed.mount.spec.ts。
   *
   * 🔴 2026-09-02 缺陷 47 终审 (Q2): 上一版这里用了一条**字面正则**
   * (`/isPendingQty\(row\) && \(row\.pickedInput === null .../`) 死钉住
   * `isPendingQty(row)` 与 `(row.pickedInput === null` 之间必须是单个空格、零换行。
   * 缺陷 47 给这道闸加了自备料豁免 (`!isSelfSupplied(row) && isPendingQty(row)\n
   * && (row.pickedInput === null ...`), 表达式的**性质**(只拦没填、0 不算没填)完全
   * 没变, 只是字面上多了一段前缀 + 换了行 —— 死抠字面的正则因此假红: CI 抓到的不是
   * 缺陷, 是这条断言自己太脆。
   *
   * ⇒ 改成: 先从源码里提取出 missingPending 那个 filter 表达式的**完整文本块**
   * (跨行安全, 不管中间插了什么前缀/怎么换行), 再对这个块做「性质」断言 ——
   * 必须含 null/undefined/空串三种缺失判据, 必须不含把 0 当没填的写法(黑名单)。
   * 检测器本身用一条合成的「旧 bug 写法」样本反向验证 (见下一条 it), 证明它真的
   * 拦得住 #469 那个具体退化, 不是凑巧通过。
   */
  it('🔴 判据只拦"没填"(null/undefined/空串), ⛔ 不许把显式 0 也当成没填', () => {
    const block = extractMissingPendingFilter(source);
    expect(block, 'missingPending 的 filter 表达式在 list.vue 里找不到 —— 提取器本身失灵，先修提取器').not.toBe('');
    expect(pinsOnlyMissingNotZero(block), `missingPending 判据不再只拦"没填" —— 实际文本块:\n${block}`).toBe(true);
  });

  /**
   * 阴性对照 (证明检测器不是巧合通过): 把判断换成 #469 修掉的那个旧 bug 写法
   * (`!(Number(row.pickedInput) > 0)`, 把显式 0 也当成没填) —— pinsOnlyMissingNotZero
   * 必须判它不合格。这条不读真实 list.vue, 只测检测器自身对已知坏样本的反应,
   * 与上面那条「测真实源码」的 it 是互补的两半: 一半证明「真代码是对的」,
   * 一半证明「量它的尺子真的会红」。
   */
  it('🔴 阴性: 检测器必须能拦住 #469 那个具体退化 (旧写法 !(Number(...)>0) 判定不合格)', () => {
    const buggyFixture = [
      'const missingPending = confirmRows.value.filter(',
      '    (row) => isPendingQty(row) && !(Number(row.pickedInput) > 0),',
      ');',
    ].join('\n');
    expect(pinsOnlyMissingNotZero(buggyFixture)).toBe(false);

    // 再钉两种同类退化写法, 防止黑名单本身收窄成只认一种字面量
    expect(pinsOnlyMissingNotZero(
      'const missingPending = confirmRows.value.filter((row) => isPendingQty(row) && !row.pickedInput);',
    )).toBe(false);
    expect(pinsOnlyMissingNotZero(
      'const missingPending = confirmRows.value.filter((row) => isPendingQty(row) && row.pickedInput === 0);',
    )).toBe(false);
  });
});

/**
 * 从源码里提取 `const missingPending = confirmRows.value.filter( ... )` 这一整段
 * (含跨行), 用括号计数找到与 `filter(` 配对的右括号 —— 不依赖具体缩进/换行位置。
 * 找不到返回空串 (调用方必须先断言非空, 空串会让下面的性质检查恒为 false, 容易被
 * 误读成"判据不合格"而不是"提取失败", 两者必须分得开)。
 */
function extractMissingPendingFilter(src: string): string {
  // 🔴 只搜 'confirmRows.value.filter(' 会命中**第一个**同名调用 —— list.vue 里更早
  // 还有一处 `const invalid = confirmRows.value.filter((row) => Number(row.pickedInput) < 0)`
  // (负数校验, 与本条判据无关)。必须钉住变量名前缀, 定位到 missingPending 那一处。
  const marker = 'const missingPending = confirmRows.value.filter(';
  const markerIndex = src.indexOf(marker);
  if (markerIndex === -1) return '';
  const openParenIndex = markerIndex + marker.length - 1; // 指向 filter 后那个 '('
  let depth = 0;
  for (let i = openParenIndex; i < src.length; i++) {
    if (src[i] === '(') depth++;
    else if (src[i] === ')') {
      depth--;
      if (depth === 0) {
        return src.slice(markerIndex, i + 1);
      }
    }
  }
  return ''; // 括号不配对 (源码本身语法错) —— 同样返回空串, 交给调用方的非空断言去拦
}

/**
 * 判据本身 (性质, 不是字面量): 这段 filter 表达式必须只拦"没填"
 * (null / undefined / 空串), 不许把显式 0 也当成没填。
 */
function pinsOnlyMissingNotZero(filterBlock: string): boolean {
  if (!filterBlock.includes('isPendingQty(row)')) return false;
  if (!filterBlock.includes('row.pickedInput === null')) return false;
  if (!filterBlock.includes('row.pickedInput === undefined')) return false;
  if (!/===\s*['"]{2}/.test(filterBlock)) return false; // 空串判据 (''  或 "")
  // 黑名单: 任一出现即判定"把 0(或假值)当没填" —— #469 那个具体退化 + 常见同类写法
  const bannedPatterns = [
    /pickedInput\s*===\s*0\b/,
    /!\s*row\.pickedInput\b/,
    /!\(\s*Number\(row\.pickedInput\)\s*>\s*0\s*\)/,
  ];
  return !bannedPatterns.some((re) => re.test(filterBlock));
}
