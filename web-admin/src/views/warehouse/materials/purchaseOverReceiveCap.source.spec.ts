import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const panel = readFileSync(
  resolve(import.meta.dirname, 'PendingPurchaseReceivingPanel.vue'), 'utf8');
const api = readFileSync(
  resolve(import.meta.dirname, '../../../api/purchaseReceive.ts'), 'utf8');

/**
 * 2026-08-25 —— 采购超收：界面上那个上限必须来自后端执行侧的真值。
 *
 * 缺陷长相：输入框上限绑的是 `remainingReceivableQuantity`（= 还差多少收齐），
 * 于是下单 100 箱、供应商送 105 箱时仓管员**根本输不进 105** —— 界面把货挡在门口，
 * 而后端此刻允许超收。后端已改成默认不限制超收并把上限做成可配置
 * （`PurchaseServiceImpl.resolveOverReceiveLimit` / `OverReceiveLimit`），
 * 展示侧必须读同一个值。
 *
 * ⚠️ 第一版这些断言是**整份文件** `toContain(...)`，把 `remainingLimit` 改回
 * 待收量之后**一条都没红** —— 因为同样的字符串在姊妹函数
 * `remainingLimitForReceiptRow` 里还在。整份文件的字符串计数量的是"文本里有没有"，
 * 而这里要守的是"**哪个函数**在用哪个字段"。所以改成按函数体切片再断言。
 */

/** 切出一个顶层 `function name(...)` 的函数体（到第一个行首 `}` 为止）。 */
function bodyOf(name: string): string {
  const start = panel.indexOf(`function ${name}(`);
  expect(start, `源码里找不到函数 ${name} —— 它被改名或删了, 这些断言就都失去意义了`)
    .toBeGreaterThan(-1);
  const end = panel.indexOf('\n}', start);
  expect(end, `函数 ${name} 没有行首闭括号, 切片失败`).toBeGreaterThan(start);
  return panel.slice(start, end);
}

describe('采购收货输入上限 = 后端超收上限（不是待收量）', () => {
  it('⭐ remainingLimit 只看 maxReceivableQuantity, ⛔ 一个字都不许提待收量', () => {
    const body = bodyOf('remainingLimit');
    expect(body).toContain('maxReceivableQuantity');
    expect(body).not.toContain('remainingReceivableQuantity');
  });

  it('⭐ remainingLimitForReceiptRow(改草稿数量) 同样只看 maxReceivableQuantity', () => {
    const body = bodyOf('remainingLimitForReceiptRow');
    expect(body).toContain('maxReceivableQuantity');
    expect(body).not.toContain('remainingReceivableQuantity');
  });

  it('⭐ 后端说「不限制」(null) 时两个上限函数都不设上限(Infinity)', () => {
    for (const name of ['remainingLimit', 'remainingLimitForReceiptRow']) {
      const body = bodyOf(name);
      expect(body, `${name} 缺少 null → Infinity 分支`).toContain('return Infinity;');
      // ?? 回落 = 把「不限制」翻译成一个具体的上限, 正是缺陷本身
      expect(body, `${name} 用 ?? 把 null 兜成了别的数`).not.toContain('??');
    }
  });

  it('「默认填满」填的是待收量, 不是上限（上限可能是 Infinity）', () => {
    const body = bodyOf('outstandingQuantity');
    expect(body).toContain('remainingReceivableQuantity');
    expect(panel).toContain('outstandingQuantity(row).toFixed(4)');
    // 旧写法会把输入框填成 Infinity
    expect(panel).not.toContain('remainingLimit(row).toFixed(4)');
  });

  it('两处 el-input-number 仍绑这两个函数（换实现不换绑定）', () => {
    expect(panel).toContain(':max="remainingLimit(row)"');
    expect(panel).toContain(':max="remainingLimitForReceiptRow(row)"');
  });

  it('⭐ 超收上限的措辞直接印后端 label, ⛔ 界面不写死百分比', () => {
    expect(panel).toContain('selectedTask.overReceiveLimitLabel');
    expect(panel).not.toMatch(/\d+%\s*超收/);
    expect(panel).not.toContain('OVER_RECEIVE_PCT');
  });

  it('API 类型把「还差多少收齐」和「最多还能收多少」分成两个字段', () => {
    expect(api).toContain('maxReceivableQuantity?: number | null;');
    expect(api).toContain('remainingReceivableQuantity: number;');
    expect(api).toContain('overReceiveLimitRate?: number | null;');
    expect(api).toContain('overReceiveLimitLabel?: string | null;');
  });
});
