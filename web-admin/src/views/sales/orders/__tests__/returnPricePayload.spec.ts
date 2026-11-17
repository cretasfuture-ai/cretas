/**
 * 退货弹窗: 单价缺失/被价格权限遮蔽时不能被当成 0 发给后端。
 * 后端 ReturnOrderServiceImpl 对 unitPrice=null 只落 null 并按 0 计行金额(不回填订单行价),
 * 所以前端的假 0 与「未知」无法区分, 且会让退货金额/冲减被悄悄记成 0。
 * 真实的 0(赠品)是确定值, 照发 0。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { knownUnitPrice } from '../deliveryPricePayload';

const salesDir = resolve(__dirname, '..', '..');
const read = (rel: string) => readFileSync(resolve(salesDir, rel), 'utf-8');

describe('退货行单价口径 (knownUnitPrice)', () => {
  it.each([[undefined], [null], [''], ['***']])('缺价/被遮 %j => undefined, 不是 0', (v) => {
    expect(knownUnitPrice(v)).toBeUndefined();
  });
  it('真 0 => 0, 正常价 => 原值', () => {
    expect(knownUnitPrice(0)).toBe(0);
    expect(knownUnitPrice('0')).toBe(0);
    expect(knownUnitPrice(12.5)).toBe(12.5);
    expect(knownUnitPrice('8.5')).toBe(8.5);
  });
});

describe('接线: 三个退货弹窗入口都走 knownUnitPrice, 不再 || 0', () => {
  const files = ['orders/detail.vue', 'orders/list.vue', 'shipments/LegacyShipments.vue'];
  it.each(files)('%s 没有 Number(x.unitPrice) || 0', (f) => {
    expect(read(f)).not.toMatch(/Number\((it|row)\.unitPrice\)\s*\|\|\s*0/);
  });
  it.each(files)('%s 用 knownUnitPrice 取退货行单价', (f) => {
    const src = read(f);
    expect(src).toMatch(/import \{[^}]*knownUnitPrice[^}]*\} from '[^']*deliveryPricePayload'/);
    expect(src).toMatch(/unitPrice: knownUnitPrice\((it|row)\.unitPrice\)/);
  });
  it('returns/detail.vue 退货明细小计缺价显示 -, 不是 0.00', () => {
    const src = read('returns/detail.vue');
    expect(src).not.toMatch(/\(Number\(row\.unitPrice\)\s*\|\|\s*0\)/);
    expect(src).toMatch(/returnLineSubtotal\(row\)/);
  });
});
