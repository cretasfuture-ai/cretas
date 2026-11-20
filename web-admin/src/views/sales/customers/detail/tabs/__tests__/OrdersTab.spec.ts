/**
 * Sprint 4 W1 S-CUSTOMER-TAB-1 (Phase F) — OrdersTab spec.
 * 重点: fetch on mount with correct params + state machine transitions.
 * 金额脱敏: UTable 桩会真实调用 `<id>-cell` 插槽, 末尾用例直接断言无 canViewPrice 时金额不进 DOM。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref, defineComponent, h } from 'vue';
import OrdersTab from '../OrdersTab.vue';

const mockGet = vi.fn();
vi.mock('@/api/request', () => ({
  get: (...args: any[]) => mockGet(...args),
}));

const canViewPriceRef = ref(true);
const factoryIdRef = ref('F999');

vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: factoryIdRef }),
}));

vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canViewPrice: canViewPriceRef }),
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

// UTable 桩: 记下行数, 并真的调用每列的 `<id>-cell` 插槽渲染每行 (金额脱敏断言走真实插槽)
const UTableStub = defineComponent({
  props: ['data', 'columns'],
  setup(props, { slots }) {
    return () =>
      h('div', { class: 'el-table-stub', 'data-count': props.data?.length || 0 }, [
        ...(props.data || []).map((row: any, i: number) =>
          h(
            'div',
            { class: 'row', key: i },
            (props.columns || []).map((c: any) => slots[`${c.id}-cell`]?.({ row: { original: row } })),
          ),
        ),
      ]);
  },
});

const globalStubs = {
  USkeleton: { template: '<div class="el-skeleton" />' },
  UEmpty: { props: ['description'], template: '<div class="el-empty">{{ description }}<slot name="actions" /></div>' },
  UTable: UTableStub,
  UBadge: { template: '<span class="badge"><slot /></span>' },
  UPagination: { template: '<div class="el-pagination" />' },
  UButton: { template: '<button class="el-button"><slot /></button>' },
  UAlert: { props: ['title'], template: '<div class="el-result">{{ title }}</div>' },
};

describe('OrdersTab', () => {
  beforeEach(() => {
    mockGet.mockReset();
    canViewPriceRef.value = true;
  });

  it('fetches /sales/orders/by-customer with customerId param', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: [{ id: '1', orderNumber: 'SO-001' }], totalElements: 1 },
    });
    mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(mockGet).toHaveBeenCalledWith(
      '/F999/sales/orders/by-customer',
      expect.objectContaining({ params: expect.objectContaining({ customerId: 'cust-1' }) }),
    );
  });

  it('transitions to empty state when no orders', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: { content: [], totalElements: 0 },
    });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-empty').exists()).toBe(true);
    expect(w.html()).toContain('该客户暂无销售单');
  });

  it('transitions to ready state with data', async () => {
    mockGet.mockResolvedValueOnce({
      success: true,
      data: {
        content: [{ id: '1', orderNumber: 'SO-001', totalAmount: 100 }],
        totalElements: 1,
      },
    });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    const table = w.find('.el-table-stub');
    expect(table.exists()).toBe(true);
    expect(table.attributes('data-count')).toBe('1');
    expect(w.find('.el-empty').exists()).toBe(false);
  });

  it('transitions to error state on 403', async () => {
    mockGet.mockRejectedValueOnce({ response: { status: 403, data: { message: '无权限' } } });
    const w = mount(OrdersTab, {
      props: { customerId: 'cust-1' },
      global: { stubs: globalStubs },
    });
    await flushPromises();
    expect(w.find('.el-result').exists()).toBe(true);
    expect(w.html()).toContain('无权');
  });

  it('canViewPrice=false 时总额/已收款显示 **** 且金额不进 DOM; true 时显示金额', async () => {
    const payload = {
      success: true,
      data: { content: [{ id: '1', orderNumber: 'SO-001', status: 'NEW', totalAmount: 12345.67, paidAmount: 888.01 }], totalElements: 1 },
    };
    canViewPriceRef.value = false;
    mockGet.mockResolvedValueOnce(payload);
    const masked = mount(OrdersTab, { props: { customerId: 'cust-1' }, global: { stubs: globalStubs } });
    await flushPromises();
    expect(masked.findAll('.masked')).toHaveLength(2);
    expect(masked.html()).not.toContain('12,345.67');
    expect(masked.html()).not.toContain('888.01');

    canViewPriceRef.value = true;
    mockGet.mockResolvedValueOnce(payload);
    const open = mount(OrdersTab, { props: { customerId: 'cust-1' }, global: { stubs: globalStubs } });
    await flushPromises();
    expect(open.findAll('.masked')).toHaveLength(0);
    expect(open.html()).toContain('12,345.67');
    expect(open.html()).toContain('888.01');
  });

  it('状态列显示中文名(已知状态), 未知状态显示「未知状态」且不露英文原码', async () => {
    const known: Record<string, string> = {
      DRAFT: '草稿', CONFIRMED: '已确认', PENDING_FINANCE_REVIEW: '待 OA 审批', FINANCE_APPROVED: '审批已通过',
      FINANCE_REJECTED: '审批已驳回', PROCESSING: '处理中', PARTIAL_DELIVERED: '部分发货', COMPLETED: '已完成',
      CANCELLED: '已取消', SHORT_CLOSED: '短交结案',
    };
    const content = [...Object.keys(known), 'BRAND_NEW_STATUS'].map((status, i) => ({ id: String(i), orderNumber: `SO-${i}`, status }));
    mockGet.mockResolvedValueOnce({ success: true, data: { content, totalElements: content.length } });
    const w = mount(OrdersTab, { props: { customerId: 'cust-1' }, global: { stubs: globalStubs } });
    await flushPromises();
    const texts = w.findAll('.badge').map((b) => b.text());
    expect(texts).toEqual([...Object.values(known), '未知状态']);
    for (const code of [...Object.keys(known), 'BRAND_NEW_STATUS']) expect(w.html()).not.toContain(code);
  });
});
