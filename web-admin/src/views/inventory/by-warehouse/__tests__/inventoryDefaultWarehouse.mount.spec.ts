import { mount, flushPromises } from '@vue/test-utils';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import ElementPlus from 'element-plus';
import InventoryByWarehouse from '../index.vue';

/**
 * 分仓库存查询默认仓库：默认的总仓(WH-LOG)没有批次、别的仓有批次时，
 * 自动换到第一个有批次的仓库，不再让用户一进来看到空表。
 */

vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F001' }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: false }) }));

const apiGet = vi.fn();
vi.mock('@/api/request', () => ({ get: (...args: unknown[]) => apiGet(...args) }));

const warehouses = [
  { id: 'wh-log', factoryId: 'F001', code: 'WH-LOG', name: '物流仓', type: 'LOGISTICS', isActive: true },
  { id: 'wh-raw', factoryId: 'F001', code: 'WH-RAW', name: '原料仓', type: 'RAW', isActive: true },
];

function inventoryFor(warehouseId: string, materials: Array<Record<string, unknown>>) {
  return {
    success: true,
    data: {
      factoryId: 'F001', warehouseId, warehouseCode: warehouseId, warehouseName: warehouseId, warehouseType: 'RAW',
      materials, products: [], materialCount: materials.length, productCount: 0,
    },
  };
}

function wire(batchesByWarehouse: Record<string, Array<Record<string, unknown>>>) {
  apiGet.mockImplementation((rawUrl: unknown) => {
    const url = String(rawUrl ?? '');
    if (url.includes('/factories/network')) return Promise.resolve({ success: true, data: [{ factoryId: 'F001', factoryName: '测试厂' }] });
    if (url.includes('/factory/warehouses')) return Promise.resolve({ success: true, data: warehouses });
    const m = url.match(/warehouseId=([^&]+)/);
    if (m) {
      const id = decodeURIComponent(m[1]);
      return Promise.resolve(inventoryFor(id, batchesByWarehouse[id] ?? []));
    }
    return Promise.resolve({ success: true, data: [] });
  });
}

async function settle() {
  for (let i = 0; i < 6; i += 1) await flushPromises();
}

const requestedWarehouses = () => apiGet.mock.calls
  .map(([url]) => String(url).match(/warehouseId=([^&]+)/)?.[1])
  .filter(Boolean);

beforeEach(() => apiGet.mockReset());

describe('分仓库存查询 —— 默认仓库自动换到有批次的仓库', () => {
  it('总仓为空、原料仓有批次：最终停在原料仓并加载其批次', async () => {
    wire({ 'wh-raw': [{ id: 'b1', batchNumber: 'RAW-001', materialName: '鲜牛腱', currentQuantity: 100, quantityUnit: 'kg' }] });
    const wrapper = mount(InventoryByWarehouse, { global: { plugins: [ElementPlus] } });
    await settle();
    const calls = requestedWarehouses();
    expect(calls[0]).toBe('wh-log');
    expect(calls[calls.length - 1]).toBe('wh-raw');
    expect(wrapper.text()).toContain('原料仓');
    wrapper.unmount();
  });

  it('阴性对照: 总仓本身有批次时不换仓，从不请求别的仓', async () => {
    wire({ 'wh-log': [{ id: 'b2', batchNumber: 'LOG-001', materialName: '包材', currentQuantity: 5, quantityUnit: 'kg' }] });
    const wrapper = mount(InventoryByWarehouse, { global: { plugins: [ElementPlus] } });
    await settle();
    expect(requestedWarehouses().length).toBeGreaterThan(0);
    expect(requestedWarehouses().every((id) => id === 'wh-log')).toBe(true);
    wrapper.unmount();
  });

  it('阴性对照: 所有仓库都没有批次时回到总仓，不停在别的仓', async () => {
    wire({});
    const wrapper = mount(InventoryByWarehouse, { global: { plugins: [ElementPlus] } });
    await settle();
    const calls = requestedWarehouses();
    expect(calls).toContain('wh-raw');
    expect(calls[calls.length - 1]).toBe('wh-log');
    wrapper.unmount();
  });
});
