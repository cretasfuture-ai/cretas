import { describe, it, expect } from 'vitest';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * 闸: HR「生效起始」三个页面的控件必须与**后端的生效判定口径**一致。
 *
 * 后端有**两套**口径, 而它们在界面上长得一模一样:
 *
 * | 页面 | 后端 probe | 含义 | 正确控件 |
 * |---|---|---|---|
 * | city-overrides   | `yearMonth.atDay(1)`      | 只有 1 号才在当月生效 | 月选择器 |
 * | insurance-rates  | `yearMonth.atDay(1)`      | 同上                  | 月选择器 |
 * | special-deductions | `yearMonth.atEndOfMonth()` | 月内任意天当月生效 | 日选择器 |
 *
 * 🔴 前两个原本是 `type="date"`, 用户挑 9-15 会**静默推迟到 10 月** ——
 *    那个月全厂社保按旧基数算, 工资条和申报都要更正。而 label 写的是
 *    「生效起始**月**」, 说的是月、收的是日、判的是「必须等于 1 号」。
 *
 * ⚠️ 这道闸真正要守的是**三者不一样**这件事。下一个人很容易把它们
 *    「统一」成同一个控件 —— 那会把两套后端口径中的一套弄错。
 *
 * ⛔ 判据不止看控件: 提交校验里另有 fail-closed 的 `endsWith('-01')`,
 *    控件被换回去或被绕过时也进不去。两层都钉。
 */

const HERE = path.dirname(fileURLToPath(import.meta.url));
const VIEWS = path.resolve(HERE, '..', '..');

const read = (rel: string) => fs.readFileSync(path.join(VIEWS, rel), 'utf8');

/**
 * 取 v-model 锚点之后的一段。
 *
 * ⚠️ 锚点找不到就**当场炸**, 不做 `slice(-1)` 那种静默降级 —— 第一版写死了
 *    `formData.effectiveFrom`, 而 special-deductions 里那个字段叫 `validFrom`,
 *    `indexOf` 返回 -1 让断言报成 `expected '
' to contain ...`, 真因看不出来。
 */
function blockAfter(code: string, anchor: string, len = 400): string {
  const i = code.indexOf(anchor);
  if (i < 0) throw new Error('锚点未命中, 闸的假设已过期: ' + anchor);
  return code.slice(i, i + len);
}

const MONTH_PAGES = ['hr/config/city-overrides.vue', 'hr/config/insurance-rates.vue'];
const DAY_PAGE = 'hr/special-deductions/list.vue';

describe('闸: HR 生效起始的控件必须匹配后端的生效判定口径', () => {
  it('阳性对照: 三个页面都真的读到了(读错路径会让下面全部假绿)', () => {
    for (const p of [...MONTH_PAGES, DAY_PAGE]) {
      expect(read(p).length).toBeGreaterThan(2000);
    }
  });

  it('🔴 按月生效的两页必须用月选择器, 且写回仍是 YYYY-MM-DD', () => {
    for (const p of MONTH_PAGES) {
      const code = read(p);
      const block = blockAfter(code, 'v-model="formData.effectiveFrom"');
      expect(block).toContain('type="month"');
      // 写回格式不能跟着变: 后端收的是 LocalDate, 页面校验也是 YYYY-MM-DD
      expect(block).toContain('value-format="YYYY-MM-DD"');
      expect(block).not.toContain('type="date"');
    }
  });

  it('🔴 提交校验必须 fail-closed 地要求 1 号(不许只靠控件)', () => {
    for (const p of MONTH_PAGES) {
      expect(read(p)).toContain("endsWith('-01')");
    }
  });

  it('🔴 专项附加扣除是**另一套口径**(月末 probe), 必须保持日选择器', () => {
    // 把它「统一」成月选择器会丢掉一个后端真正支持的能力。
    const code = read(DAY_PAGE);
    // ⚠️ 这一页的字段叫 validFrom, 不是 effectiveFrom。
    const block = blockAfter(code, 'v-model="formData.validFrom"');
    expect(block).toContain('type="date"');
    expect(block).not.toContain('type="month"');
    // 阴性对照: 它**不该**有按月的 fail-closed 校验
    expect(code).not.toContain("endsWith('-01')");
  });

  it('label 要说实话: 按月生效的页面 label 里带「月」', () => {
    for (const p of MONTH_PAGES) {
      const code = read(p);
      const i = code.indexOf('v-model="formData.effectiveFrom"');
      // 往回找最近的 el-form-item label
      const before = code.slice(0, i);
      const label = before.slice(before.lastIndexOf('<el-form-item'));
      expect(label).toContain('生效起始月');
    }
  });
});
