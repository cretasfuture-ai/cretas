/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — ItemStatsTab spec.
 * 重点: aggregation correctness — verify via `data-rows-json` attr on stub.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import ItemStatsTab from '../ItemStatsTab.vue';
import { customerTabStubs } from './customerTabStubs';

const mockGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: any[]) => mockGet(...args),
}));

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: ref('F999') }),
}));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: ref(true) }),
}));

const globalStubs = {
  ...customerTabStubs,
  // 聚合结果经 data-rows-json 读出来 (UTable 桩只渲染列, 不暴露 data)
  UTable: {
    props: ['data', 'columns'],
    template: '<div class="el-table-stub" :data-rows-json="JSON.stringify(data)" />',
  },
  UAlert: { props: ['title'], template: '<div class="el-alert">{{ title }}</div>' },
  UEmpty: { props: ['description'], template: '<div class="el-empty">{{ description }}</div>' },
};

describe('ItemStatsTab — aggregation', () => {
  beforeEach(() => {
    mockGet.mockReset();
  });

  it('groups items by productTypeId and sums quantity + amount', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: {
        content: [
          {
            id: 'o1',
            orderDate: '2026-05-01',
            items: [
              { productTypeId: 'A', productName: '商品A', quantity: 10, unitPrice: 100 },
              { productTypeId: 'B', productName: '商品B', quantity: 5, unitPrice: 200 },
            ],
          },
          {
            id: 'o2',
            orderDate: '2026-05-02',
            items: [
              { productTypeId: 'A', productName: '商品A', quantity: 3, unitPrice: 100 },
            ],
          },
        ],
        totalElements: 2,
      },
    });

    const w = mount(ItemStatsTab, {
      props: { customerId: 'c1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();

    const tableStub = w.find('.el-table-stub');
    const rows = JSON.parse(tableStub.attributes('data-rows-json') || '[]') as any[];

    // 2 groups: A and B, sorted by salesAmount desc (A=1300, B=1000)
    expect(rows.length).toBe(2);

    const a = rows.find((r) => r.productTypeId === 'A');
    expect(a).toBeDefined();
    expect(a.totalQuantity).toBe(13);
    expect(a.salesAmount).toBe(1300);
    expect(a.avgPrice).toBe(100);

    const b = rows.find((r) => r.productTypeId === 'B');
    expect(b.totalQuantity).toBe(5);
    expect(b.salesAmount).toBe(1000);

    // Sort: A first (higher salesAmount)
    expect(rows[0].productTypeId).toBe('A');
  });

  it('handles empty content array gracefully', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: [], totalElements: 0 },
    });
    const w = mount(ItemStatsTab, {
      props: { customerId: 'c1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-empty').exists()).toBe(true);
  });

  it('shows 500-cap warning when totalElements > 500', async () => {
    const orders = Array.from({ length: 500 }, (_, i) => ({
      id: `o${i}`,
      orderDate: '2026-05-01',
      items: [{ productTypeId: 'X', productName: 'X', quantity: 1, unitPrice: 10 }],
    }));
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: orders, totalElements: 1234 },
    });
    const w = mount(ItemStatsTab, {
      props: { customerId: 'c1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-alert').exists()).toBe(true);
    expect(w.find('.el-alert').text()).toContain('500');
  });
});
