import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { createPinia, setActivePinia } from 'pinia';
import { latestOrderSalesperson } from '../latestOrderSalesperson';

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: true }),
}));

import CustomerHeader from '../CustomerHeader.vue';

function mountHeader(props: Record<string, unknown>) {
  setActivePinia(createPinia());
  return mount(CustomerHeader, { props, global: { plugins: [ElementPlus] } });
}

describe('latestOrderSalesperson', () => {
  it('取日期最新且非空的业务员; 全空返回 null', () => {
    expect(latestOrderSalesperson([
      { orderDate: '2026-11-01', salesperson: '老王' },
      { orderDate: '2026-11-10', salesperson: ' 小李 ' },
      { orderDate: '2026-11-12', salesperson: '' },
    ])).toBe('小李');
    expect(latestOrderSalesperson([{ orderDate: '2026-11-01', salesperson: null }])).toBeNull();
    expect(latestOrderSalesperson(undefined)).toBeNull();
  });
});

describe('CustomerHeader 业务员显示', () => {
  const base = { id: 1, name: '客户A', customerCode: 'C1' };

  it('档案未分配且有最近订单业务员: 标明「最近订单业务员」', () => {
    const w = mountHeader({ customer: { ...base }, latestOrderSalesperson: '小李' });
    expect(w.text()).toContain('最近订单业务员');
    expect(w.text()).toContain('小李');
    expect(w.text()).not.toContain('当前业务员');
  });

  it('阴性对照: 档案已分配时仍显示「当前业务员」, 不被订单业务员覆盖', () => {
    const w = mountHeader({
      customer: { ...base, assignedSalesUserId: 5, assignedSalesUserName: '档案业务员' },
      latestOrderSalesperson: '小李',
    });
    expect(w.text()).toContain('当前业务员');
    expect(w.text()).toContain('档案业务员');
    expect(w.text()).not.toContain('最近订单业务员');
  });

  it('阴性对照: 都没有则如实显示未分配', () => {
    const w = mountHeader({ customer: { ...base }, latestOrderSalesperson: null });
    expect(w.text()).toContain('当前业务员');
    expect(w.text()).toContain('未分配');
  });
});
