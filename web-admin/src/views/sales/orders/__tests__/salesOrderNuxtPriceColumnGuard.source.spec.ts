import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 销售订单列表/详情迁 Nuxt UI 后, 价格/金额列的「无看价权限就整列不渲染」判断从
 * `<el-table-column v-if="canViewPrice">` 搬进了 UTable 的列定义 (`price && { ... }`)。
 * 守的是行为: 无权限时表头+单元格都不出现, 不是「值被置空的空列」。
 * 变异验证 (见 PR): 去掉任一列的 `price &&` 前缀, 对应用例变红。
 */
const list = readFileSync(resolve(import.meta.dirname, '../list.vue'), 'utf8');
const detail = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');

/** 取某个列 id 所在的那一行列定义 (一列一行)。 */
function columnLine(source: string, id: string): string {
  const line = source.split('\n').find((l) => l.includes(`{ id: '${id}'`));
  expect(line, `找不到列 ${id} 的定义 —— 本闸失去被测对象`).toBeDefined();
  return line as string;
}

describe('销售订单列表: 价格列受 canViewPrice 控制', () => {
  it('价格 const 取自 canViewPrice', () => {
    expect(list).toContain('const price = canViewPrice.value;');
  });
  it.each(['totalAmount', 'shippingFee', 'discountAmount', 'commissionPreview', 'commissionRatePct'])('%s 列带 price 前缀', (id) => {
    expect(columnLine(list, id)).toMatch(/^\s*price && /);
  });
  it('阴性对照: 开票状态列 (所有角色可见) 不带 price 前缀', () => {
    expect(columnLine(list, 'invoiceStatus')).not.toMatch(/price && /);
  });
});

describe('销售订单详情: 价格/金额列受 canViewPrice 控制', () => {
  it('价格 const 取自 canViewPrice', () => {
    expect(detail).toContain('const price = canViewPrice.value;');
  });
  it.each([
    'unitPrice', 'taxIncludedPrice', 'priceSource', 'lineAmount', 'lineTaxIncluded',
    'amount', 'taxAmount', 'totalAmount', 'taxBreakdown', 'subtotal', 'tax',
  ])('%s 列至少在一处列定义里带 price 前缀', (id) => {
    const lines = detail.split('\n').filter((l) => l.includes(`{ id: '${id}'`));
    expect(lines.length, `找不到列 ${id}`).toBeGreaterThan(0);
    expect(lines.every((l) => /^\s*(price|canViewPrice\.value) && /.test(l))).toBe(true);
  });
  it('阴性对照: 订单数量列 (所有角色可见) 不带 price 前缀', () => {
    expect(columnLine(detail, 'quantity')).not.toMatch(/price && /);
  });
});

describe('销售订单详情头部: 已发货/已开票/已收款统一走 formatAmount (零值也带 ¥)', () => {
  it.each(['actualShippedAmount', 'invoicedAmount', 'paidAmount'])('%s 用 formatAmount(order.x || 0), 不写 0.00 字面量', (f) => {
    expect(detail).toContain(`<dd class="num">{{ formatAmount(order.${f} || 0) }}</dd>`);
  });
  it('阴性对照: 不再有裸 0.00 字面量', () => {
    expect(detail).not.toContain(": '0.00'");
  });
  it('formatAmount(0) 带 ¥', async () => {
    const { formatAmount } = await import('@/utils/tableFormatters');
    expect(formatAmount(0)).toBe('¥0.00');
  });
});
