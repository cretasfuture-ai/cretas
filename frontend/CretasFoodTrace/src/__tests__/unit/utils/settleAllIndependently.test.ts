/**
 * 缺陷 (2026-09-05, owner 点名): `ProductionPlanManagementScreen.loadOptions()` 原来用
 * `Promise.all([产品类型, 客户, 可用库存])` 一起等 —— 客户下拉那一路 403(或任何失败)会让
 * `Promise.all` 整体 reject，另外两路(产品类型/可用库存)也一并拿不到，整屏选项空白，
 * "一拒全拒"。修法是把三路改成各自独立 settle。
 *
 * 本文件直接测这条修复背后的纯函数 `settleAllIndependently`（不依赖任何 React /
 * react-native-paper / expo-* 原生模块 —— `ProductionPlanManagementScreen.tsx` 本身
 * 经实测无法在当前 jest 环境下渲染/import 级别加载，`expo-document-picker` 在 import
 * 阶段就抛 `Cannot read properties of undefined (reading 'NativeModule')`）。
 */
import { settleAllIndependently } from '../../../utils/settleAllIndependently';

describe('settleAllIndependently', () => {
  it('一路 reject 时，另外两路仍然各自拿到结果 (缺陷本体的阴性对照)', async () => {
    const products = { data: [{ id: 'PT-1', name: '罗非鱼片' }] };
    const stock = { success: true, data: { summary: [{ category: '原料A', totalQuantity: 10, batchCount: 2 }] } };
    const customersError = new Error('您的角色 [procurement_manager] 缺少 销售管理 或 财务管理 模块的 [读取] 权限');

    const result = await settleAllIndependently({
      products: Promise.resolve(products),
      customers: Promise.reject(customersError),
      stock: Promise.resolve(stock),
    });

    // 核心断言: 客户那一路失败, 不影响产品类型 / 可用库存两路 —— 这正是修复要守住的行为。
    expect(result.values.products).toEqual(products);
    expect(result.values.stock).toEqual(stock);
    expect(result.values.customers).toBeUndefined();
    expect(result.failedKeys).toEqual(['customers']);
    expect(result.failureReasons.customers).toBe(customersError);
  });

  it('三路全部 resolve 时，三路都拿到结果，failedKeys 为空', async () => {
    const products = { data: [] };
    const customers = { data: [] };
    const stock = { success: true, data: { summary: [] } };

    const result = await settleAllIndependently({
      products: Promise.resolve(products),
      customers: Promise.resolve(customers),
      stock: Promise.resolve(stock),
    });

    expect(result.values).toEqual({ products, customers, stock });
    expect(result.failedKeys).toEqual([]);
  });

  it('三路全部 reject 时，三路都不在 values 里，failedKeys 含三个 key', async () => {
    const result = await settleAllIndependently({
      products: Promise.reject(new Error('products failed')),
      customers: Promise.reject(new Error('customers failed')),
      stock: Promise.reject(new Error('stock failed')),
    });

    expect(result.values).toEqual({});
    expect(result.failedKeys).toEqual(['products', 'customers', 'stock']);
  });
});
