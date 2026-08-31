import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 销售订单的商品下拉必须走 /product-types/sellable, 不能走 /product-types/active。
 *
 * /active 继续服务生产侧；销售订单专用 /sellable 只返回成品和半成品。
 * 原料/辅料/包材必须登记在 suppliedMaterials，不能混入商品下拉。
 *
 * 这条闸守两件事:
 *   ① 别有人图省事把销售改回 /active(那会让半成品重新出现在销售单里)
 *   ② 列表页与详情页必须用同一个端点 —— 列表能选到而详情编辑时选不到,
 *      是最难查的那种不一致
 */
const FILES = ['list.vue', 'detail.vue'] as const;

describe('sales order product dropdown endpoint', () => {
  for (const file of FILES) {
    const source = readFileSync(resolve(__dirname, '..', file), 'utf8');

    it(`${file} 用 /product-types/sellable 拉商品下拉`, () => {
      expect(source, `${file} 应当调用 sellable 端点`)
        .toContain('/product-types/sellable');
    });

    it(`${file} 不再用生产侧的 /product-types/active 拉商品下拉`, () => {
      expect(source, `${file} 不该再调用 /product-types/active —— 那条口径保留半成品、排除原料`)
        .not.toContain('/product-types/active');
    });
  }
  it('list.vue 的商品选项不再合并物料字典', () => {
    const source = readFileSync(resolve(__dirname, '..', 'list.vue'), 'utf8');
    expect(source).toContain('const sellableOptions = computed<TableRow[]>(() => products.value)');
    expect(source).toContain('const materialIdSet = computed(() => new Set<string>())');
  });

  it('detail.vue 的商品选项只使用 sellable 响应', () => {
    const source = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');
    expect(source).toContain('const list = unwrap(res?.data)');
    expect(source).not.toContain('...mats');
  });

  it('new.vue 将商品和 suppliedMaterials 分别赋值，并按需加载客供物料', () => {
    const source = readFileSync(resolve(__dirname, '..', 'new.vue'), 'utf8');
    expect(source).toContain('products.value = unwrap<Product>(productResponse?.data).filter(isSalesOrderProduct)');
    expect(source).toContain('async function loadSuppliedMaterialOptions(): Promise<void>');
    expect(source).toContain('suppliedMaterialOptions.value = unwrap<TableRow>(response?.data)');
    expect(source).toContain('void loadSuppliedMaterialOptions();');
  });

  it('两个页面用的是同一个端点(不允许一个 sellable 一个 active)', () => {
    const endpoints = FILES.map((file) => {
      const source = readFileSync(resolve(__dirname, '..', file), 'utf8');
      return /\/product-types\/(sellable|active)/.exec(source)?.[1];
    });
    expect(new Set(endpoints).size, `实得: ${endpoints.join(' vs ')}`).toBe(1);
  });
});
