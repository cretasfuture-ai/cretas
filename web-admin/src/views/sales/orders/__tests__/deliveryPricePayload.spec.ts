/**
 * 快速发货 / 发货单提交: 被遮或缺失的单价不能被发成 0 (后端按「发货行与订单行单价都为 0」判赠品跳过,
 * 前端的假 0 会污染发货行)。真实有价(含真实的 0)原样传。
 */
import { describe, it, expect } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { buildQuickDeliveryItems, knownUnitPrice, withKnownUnitPrice } from '../deliveryPricePayload';

const line = (over: Record<string, unknown>) => ({
  id: 1, productTypeId: 'PT-1', productName: '鸡胸', quantity: 10, unit: 'kg', ...over,
});

describe('quick delivery item payload', () => {
  it.each([
    ['字段缺失', {}],
    ['null', { unitPrice: null }],
    ['空串', { unitPrice: '' }],
    ['非数字(遮罩串)', { unitPrice: '***' }],
  ])('单价%s: 序列化后的 payload 里没有 unitPrice, 更不是 0', (_n, over) => {
    const [item] = buildQuickDeliveryItems([line(over)]);
    const wire = JSON.parse(JSON.stringify(item));
    expect('unitPrice' in wire).toBe(false);
    expect(Object.values(wire)).not.toContain(0 as never); // 防止任何字段被塞 0 价
    expect(wire.deliveredQuantity).toBe(10); // 数量不受影响
  });

  it('有价时原样传, 含税/小数不被改写', () => {
    const [item] = buildQuickDeliveryItems([line({ unitPrice: 12.3456 }), line({ id: 2, unitPrice: '8.5' })]);
    expect(item.unitPrice).toBe(12.3456);
    expect(buildQuickDeliveryItems([line({ unitPrice: '8.5' })])[0].unitPrice).toBe(8.5);
  });

  it('真实的 0 价(赠品)保持 0: 它是确定值, 不是缺失', () => {
    const [item] = buildQuickDeliveryItems([line({ unitPrice: 0 })]);
    expect(item.unitPrice).toBe(0);
    expect(knownUnitPrice('0')).toBe(0);
  });

  it('混合行: 只有缺价那行去掉 unitPrice', () => {
    const items = buildQuickDeliveryItems([line({ unitPrice: 5 }), line({ id: 2 })]);
    expect(items.map((i) => 'unitPrice' in i)).toEqual([true, false]);
  });

  it('withKnownUnitPrice 同样用于 detail 提交: 缺价去字段, 有价规范成 number', () => {
    expect('unitPrice' in withKnownUnitPrice({ productTypeId: 'a', unitPrice: undefined })).toBe(false);
    expect(withKnownUnitPrice({ productTypeId: 'a', unitPrice: '3' }).unitPrice).toBe(3);
  });
});

describe('接线: 两个真实提交出口都走这条口径', () => {
  const read = (f: string) => readFileSync(resolve(__dirname, '..', f), 'utf-8');
  it('list.vue 快速发货与 detail.vue 创建发货单都用 helper 处理 items', () => {
    const list = read('list.vue');
    const detail = read('detail.vue');
    expect(list).toMatch(/buildQuickDeliveryItems\(/);
    expect(list).toMatch(/items:\s*\(items as TableRow\[\]\)\.map\(\(it\) => withKnownUnitPrice\(it\)\)/);
    expect(detail).toMatch(/items:\s*filteredItems\.map\(\(it\) => withKnownUnitPrice\(it\)\)/);
  });
});
