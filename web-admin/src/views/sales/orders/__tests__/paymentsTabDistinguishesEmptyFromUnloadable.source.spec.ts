import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detail = readFileSync(
  resolve(process.cwd(), 'src/views/sales/orders/detail.vue'), 'utf8',
);
const list = readFileSync(
  resolve(process.cwd(), 'src/views/sales/orders/list.vue'), 'utf8',
);

/**
 * 测试同学 2026-08-12 报的「进入详情里的收款记录不能与财务管理模块的收款管理同步」。
 *
 * ⚠️ 根因**未坐实**: prod 实测 payment_records.sales_order_id 写得好好的(46/48 有值),
 * 详情页读的也是同一列, 该查得到。所以这里守的**不是**「根因修好了」, 而是:
 *
 * ▎这个标签页此前把「没能查到」渲染成「没有收款」—— 两件完全不同的事长成同一个样子,
 * ▎于是问题在现场无法诊断。本仓硬约束: 「没喊」有两种, 「没能做决定」必须喊。
 *
 * 最可能的失败是 403 —— 该端点要 finance:read_write / sales:read_write(**写**权限),
 * 只读账号打开详情就拿到 403, 而旧代码 `catch { }` 把它吞成空表。
 */
/** 剥掉行注释与块注释 —— 闸要量代码, 不能把自己的说明文字也数进来。 */
function stripComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
}

describe('销售订单详情: 收款记录标签页要分清「没有」和「没查到」', () => {
  const loadPayments = (() => {
    const start = detail.indexOf('async function loadPayments()');
    expect(start, 'loadPayments 不见了 —— 本闸失去被测对象').toBeGreaterThan(-1);
    const end = detail.indexOf('\nasync function ', start + 10);
    expect(end).toBeGreaterThan(start);
    return detail.slice(start, end);
  })();

  it('阳性对照: 它确实还在调那个收款端点', () => {
    expect(loadPayments).toContain('finance/payments/by-sales-order/');
  });

  it('⛔ 不再吞掉加载失败', () => {
    // ⚠️ 必须先剥注释再断言 —— 本文件的修复说明里**引用了**旧写法
    // `catch { /* ignore */ }`, 不剥的话闸会命中自己的注释 (本仓记过这个形状:
    // 「grep 把 docstring 里提到函数名的那行也数上」)。
    const code = stripComments(loadPayments);
    expect(code, '空 catch 让 403/500 与「没有收款」不可区分')
      .not.toMatch(/catch\s*\{\s*\}/);
    // 失败分支必须把原因记下来
    expect(code).toContain('paymentsLoadError.value =');
  });

  it('失败时清空列表并记录原因 —— 而不是留着上一次的数据', () => {
    const catchAt = loadPayments.indexOf('} catch');
    expect(catchAt).toBeGreaterThan(-1);
    const catchBlock = loadPayments.slice(catchAt);
    expect(catchBlock).toContain('payments.value = []');
    expect(catchBlock).toContain('paymentsLoadError.value =');
  });

  it('界面上要能看见这个区别 —— 加载失败有明确提示, 空表文案也不同', () => {
    expect(detail).toContain('v-if="paymentsLoadError"');
    expect(detail).toContain('收款记录未能加载');
    // 空表文案由 paymentsLoadError 决定, 不是写死的「暂无收款记录」
    expect(detail).toMatch(/:empty-text="paymentsLoadError \?/);
  });

  it('阴性对照: 成功路径仍然照常填充列表', () => {
    expect(loadPayments).toContain('payments.value = Array.isArray(res.data) ? res.data : []');
  });
});

/**
 * (a) 类问题: SO 列表的「快速收款」写 ar_ap_transactions, 而详情页标签页读 payment_records。
 * prod 实测该孤儿路径发生次数为 0 ⇒ 本轮不改行为, 但**注释不许再撒谎**
 * (本仓形态 B⁵: 描述过期比没有描述更糟 —— 它让下一个人跳过核实)。
 */
describe('销售订单列表: 快速收款的注释不得声称它修好了详情页标签页', () => {
  it('注释里已订正「两张表互不相干」, 不再声称修好了收款记录 tab', () => {
    // ⚠️ 要定位**真正的调用**, 不是注释里提到的那个路径 —— 本文件的订正说明里
    //    同样写着这个端点名。用带 post( 的完整调用形态锚定。
    const at = list.indexOf('post(`/${factoryId.value}/finance/receivable/payment`');
    expect(at, '快速收款调用不见了 —— 本闸失去被测对象').toBeGreaterThan(-1);

    // 该调用之前的注释块
    const around = list.slice(Math.max(0, at - 2000), at);
    expect(around).toContain('ar_ap_transactions');
    expect(around).toContain('payment_records');
    // 旧注释那句「Was orphan-receipt: ... + SO 收款记录 tab '暂无数据'」不许再原样留着,
    // 因为它把「修好了 tab」当成既成事实。
    expect(around).not.toMatch(/orphan-receipt: SO\.paidAmount stayed null \+ SO 收款记录 tab/);
  });
});
