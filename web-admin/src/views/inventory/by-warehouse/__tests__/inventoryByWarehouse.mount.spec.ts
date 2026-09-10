import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';
import InventoryByWarehouse from '../index.vue';

/**
 * 分仓库存查询 —— 行为验收 (硬约束 9), 缺陷 A + B, 2026-09-02。
 *
 * LIUSHANMEN 生产实测: 批次 1399 分仓库存查询「当前数量」5900kg,
 * 报工投料区「生产仓可用」5800kg —— 两个数都对(账面 vs 已扣报工在途预扣的可用量),
 * 旧版只显示一个数且不标口径。真挂载组件、真渲染表格, 断言两个数字 + 两个标签
 * 同时出现在 DOM 里, 而不是只测计算函数。
 */

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F001' }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: false }),
}));

const apiGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: unknown[]) => apiGet(...args),
}));

const NETWORK_RESP = {
  success: true,
  data: [{ factoryId: 'F001', factoryName: '刘山门总部' }],
};

const WAREHOUSE_RESP = {
  success: true,
  data: [
    { id: 'wh-wks-1', factoryId: 'F001', code: 'WH-WKS', name: '生产仓', type: 'WORKSHOP', isActive: true },
  ],
};

function inventoryResp(materials: Array<Record<string, unknown>>, materialCount = materials.length) {
  return {
    success: true,
    data: {
      factoryId: 'F001',
      warehouseId: 'wh-wks-1',
      warehouseCode: 'WH-WKS',
      warehouseName: '生产仓',
      warehouseType: 'WORKSHOP',
      materials,
      products: [],
      materialCount,
      productCount: 0,
    },
  };
}

function mountPage() {
  const wrapper = mount(InventoryByWarehouse, { global: { plugins: [ElementPlus] } });
  // These regressions exercise batch details, now an explicit view choice.
  void wrapper.find('input[value="batches"]').setValue(true);
  return wrapper;
}

/** 挂载后要跑够几轮 factoryId→warehouseId→inventory 的联动 watch 才会到最终态。 */
async function settle() {
  await flushPromises();
  await flushPromises();
  await flushPromises();
}

beforeEach(() => {
  apiGet.mockReset();
});

describe('分仓库存查询 —— 缺陷 A: 账面/可用并排显示，各自标清口径', () => {
  it('批次 1399 场景: 账面 5900 / 已预扣 100 → 表格同时渲染 5900 与 5800，且各自带上标签', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/factories/network')) return Promise.resolve(NETWORK_RESP);
      if (url.includes('/factory/warehouses')) return Promise.resolve(WAREHOUSE_RESP);
      if (url.includes('/inventory/by-warehouse')) {
        return Promise.resolve(
          inventoryResp([
            {
              id: 'batch-1399',
              batchNumber: 'B-1399',
              materialName: '五花肉',
              currentQuantity: 5900,
              unsettledConsumption: 100,
              quantityUnit: 'kg',
            },
          ]),
        );
      }
      return Promise.resolve({ success: true, data: [] });
    });

    const wrapper = mountPage();
    await settle();

    const text = wrapper.text();
    // 正面: 两个数字都要出现 —— ⛔ 不许互相覆盖丢信息。
    expect(text).toContain('5900');
    expect(text).toContain('5800');
    // 正面: 两个标签都要出现 —— 不能只显示数字不说口径。
    expect(text).toContain('账面数量');
    expect(text).toContain('可用数量');

    wrapper.unmount();
  });

  it('阳性对照: 预扣为 0 时账面 == 可用，两个数都显示且相等 (不隐藏可用列)', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/factories/network')) return Promise.resolve(NETWORK_RESP);
      if (url.includes('/factory/warehouses')) return Promise.resolve(WAREHOUSE_RESP);
      if (url.includes('/inventory/by-warehouse')) {
        return Promise.resolve(
          inventoryResp([
            {
              id: 'batch-clean',
              batchNumber: 'B-CLEAN',
              materialName: '食盐',
              currentQuantity: 300,
              unsettledConsumption: 0,
              quantityUnit: 'kg',
            },
          ]),
        );
      }
      return Promise.resolve({ success: true, data: [] });
    });

    const wrapper = mountPage();
    await settle();

    const cells = wrapper.findAll('td .cell').map((c) => c.text());
    // 账面数量列 + 可用数量列都渲染出了 300 (相等), 而不是缺一个或都不显示。
    const matches300 = cells.filter((c) => c === '300');
    expect(matches300.length).toBeGreaterThanOrEqual(2);
    expect(wrapper.text()).toContain('账面数量');
    expect(wrapper.text()).toContain('可用数量');

    wrapper.unmount();
  });
});

describe('分仓库存查询 —— 缺陷 B: 搜不到 ≠ 仓库是空的', () => {
  it('仓库有 56 个原料批次，搜索不存在的批次号 → 显示"没找到匹配"而不是"暂无批次"，统计卡片仍显示真实总数', async () => {
    const materials = Array.from({ length: 56 }, (_, i) => ({
      id: `batch-${i}`,
      batchNumber: `B-${i}`,
      materialName: `原料${i}`,
      currentQuantity: 10,
      unsettledConsumption: 0,
      quantityUnit: 'kg',
    }));

    apiGet.mockImplementation((url: string) => {
      if (url.includes('/factories/network')) return Promise.resolve(NETWORK_RESP);
      if (url.includes('/factory/warehouses')) return Promise.resolve(WAREHOUSE_RESP);
      if (url.includes('/inventory/by-warehouse')) return Promise.resolve(inventoryResp(materials, 56));
      return Promise.resolve({ success: true, data: [] });
    });

    const wrapper = mountPage();
    await settle();

    // 统计卡片: 真实总数不受搜索词影响。
    expect(wrapper.text()).toContain('原料批次');
    expect(wrapper.text()).toContain('56');

    const keywordInput = wrapper.find('input[placeholder="批次号 / 物料编码 / 物料名称"]');
    await keywordInput.setValue('1300');
    await settle();

    // 阴性对照: 旧文案「该仓库暂无原料批次」不该再出现 —— 那会被工人读成仓库是空的。
    expect(wrapper.text()).not.toContain('该仓库暂无原料批次');
    // 正面: 新文案要说清"没搜到"而不是"没有"，并带出真实总数 56。
    expect(wrapper.text()).toContain('没有找到');
    expect(wrapper.text()).toContain('1300');

    wrapper.unmount();
  });

  it('仓库这一类批次总数确实是 0 → 渲染"该仓库暂无原料批次"(真正的空态原样保留)', async () => {
    apiGet.mockImplementation((url: string) => {
      if (url.includes('/factories/network')) return Promise.resolve(NETWORK_RESP);
      if (url.includes('/factory/warehouses')) return Promise.resolve(WAREHOUSE_RESP);
      if (url.includes('/inventory/by-warehouse')) return Promise.resolve(inventoryResp([], 0));
      return Promise.resolve({ success: true, data: [] });
    });

    const wrapper = mountPage();
    await settle();

    expect(wrapper.text()).toContain('该仓库暂无原料批次');
    expect(wrapper.text()).not.toContain('没有找到');

    wrapper.unmount();
  });
});
