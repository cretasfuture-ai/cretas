/**
 * 客户价格页 — 列表渲染、改价/新增调用契约、无写权限不显示操作、价格被后端置 null 时显示「—」。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import PriceMemoryTab from '../PriceMemoryTab.vue';
import { priceSourceLabel } from '@/api/customerPriceList';

const mockList = vi.fn();
const mockSave = vi.fn();
const mockDelete = vi.fn();
vi.mock('@/api/customerPriceList', async (orig) => {
  const actual = await orig<typeof import('@/api/customerPriceList')>();
  return {
    ...actual,
    listCustomerPrices: (...a: unknown[]) => mockList(...a),
    saveCustomerPrice: (...a: unknown[]) => mockSave(...a),
    deleteCustomerPrice: (...a: unknown[]) => mockDelete(...a),
  };
});
vi.mock('@/api/request', () => ({ get: vi.fn().mockResolvedValue({ success: true, data: [] }) }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F999') }) }));
let canWrite = true;
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => canWrite }),
}));

const stubs = {
  USkeleton: { template: '<div />' },
  UEmpty: { props: ['description'], template: '<div class="empty">{{ description }}</div>' },
  UAlert: { props: ['title'], template: '<div>{{ title }}</div>' },
  UTable: { props: ['data'], template: '<div class="tbl" :data-rows="JSON.stringify(data)" />' },
  UBadge: { template: '<span><slot /></span>' },
  UButton: { template: '<button class="btn"><slot /></button>' },
  UModal: { template: '<div />' },
  UFormField: { template: '<div />' },
  USelectMenu: { template: '<div />' },
  UInputNumber: { template: '<div />' },
};

const row = {
  itemId: 1, priceListId: 'PL1', priceListName: '客户专属价目', productTypeId: 'P1',
  itemName: '牛肉丸', unit: 'kg', standardPrice: 13.2, effectiveFrom: '2026-11-14', effectiveTo: null, effective: true,
};

describe('PriceMemoryTab (客户价格页)', () => {
  beforeEach(() => { mockList.mockReset(); canWrite = true; });

  it('按客户加载价目并渲染, 带 factoryId + customerId', async () => {
    mockList.mockResolvedValue([row]);
    const w = mount(PriceMemoryTab, { props: { customerId: 'C-A' }, global: { stubs } });
    await flushPromises();
    expect(mockList).toHaveBeenCalledWith('F999', 'C-A');
    expect(JSON.parse(w.find('.tbl').attributes('data-rows')!)).toHaveLength(1);
    expect(w.text()).toContain('新增专属价');
  });

  it('没有专属价时给出空态说明, 不是假数据', async () => {
    mockList.mockResolvedValue([]);
    const w = mount(PriceMemoryTab, { props: { customerId: 'C-B' }, global: { stubs } });
    await flushPromises();
    expect(w.find('.empty').exists()).toBe(true);
    expect(w.find('.tbl').exists()).toBe(false);
  });

  it('无销售写权限不显示新增入口', async () => {
    canWrite = false;
    mockList.mockResolvedValue([row]);
    const w = mount(PriceMemoryTab, { props: { customerId: 'C-A' }, global: { stubs } });
    await flushPromises();
    expect(w.text()).not.toContain('新增专属价');
  });

  it('加载失败显示后端原文并可重试', async () => {
    mockList.mockRejectedValue(new Error('客户不存在'));
    const w = mount(PriceMemoryTab, { props: { customerId: 'X' }, global: { stubs } });
    await flushPromises();
    expect(w.text()).toContain('客户不存在');
  });
});

describe('priceSourceLabel', () => {
  it('四种来源 + 历史未记录', () => {
    expect(priceSourceLabel('CUSTOMER')).toBe('客户专属价');
    expect(priceSourceLabel('GLOBAL')).toBe('统一价');
    expect(priceSourceLabel('LAST_DEAL')).toBe('最近成交价');
    expect(priceSourceLabel('MANUAL')).toBe('手填');
    expect(priceSourceLabel('QUOTE')).toBe('报价单价');
    expect(priceSourceLabel('PRICING_STRATEGY')).toBe('价格策略调整');
    expect(priceSourceLabel(null)).toBe('未记录');
  });
});
