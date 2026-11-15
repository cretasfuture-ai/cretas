/**
 * 产品级利润详情: 价格敏感列与 permissionStore.canViewPrice 同一判断控制。
 * UTable 桩会列出传入的列 id 并真实调用 cell 插槽, 无权限时列不传入、金额不进 DOM、出现隐藏提示。
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref, defineComponent, h } from 'vue';
import ProfitDetail from '../profit-detail.vue';

const mockGet = vi.fn();
vi.mock('@/api/request', () => ({ get: (...a: unknown[]) => mockGet(...a) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));
const canViewPriceRef = ref(true);
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'F999' }) }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({
    get canViewPrice() {
      return canViewPriceRef.value;
    },
  }),
}));
vi.mock('vue-router', () => ({
  useRoute: () => ({ params: { id: '42' } }),
  useRouter: () => ({ push: vi.fn() }),
}));

const UTableStub = defineComponent({
  props: ['data', 'columns'],
  setup(props, { slots }) {
    return () =>
      h('div', { class: 'tbl' }, [
        h('div', { class: 'col-ids' }, (props.columns || []).map((c: any) => c.id || c.accessorKey).join(',')),
        ...(props.data || []).map((row: any, i: number) =>
          h('div', { class: 'row', key: i }, (props.columns || []).map((c: any) => slots[`${c.id || c.accessorKey}-cell`]?.({ row: { original: row } }))),
        ),
      ]);
  },
});
const stubs = {
  UTable: UTableStub,
  UButton: { template: '<button><slot /></button>' },
  USelect: { template: '<div />' },
  UBadge: { template: '<span><slot /></span>' },
  UAlert: { props: ['title'], template: '<div class="alert">{{ title }}</div>' },
};
const PRICE_COLS = ['unitPrice', 'costUnitPrice', 'grossProfit', 'grossMarginPct', 'discountAmount', 'taxAmount', 'netProfit', 'historicalAvgPrice', 'trend'];

describe('profit-detail 价格列权限', () => {
  beforeEach(() => {
    mockGet.mockReset();
    mockGet.mockImplementation((url: string) =>
      url.includes('profit-detail')
        ? Promise.resolve({ success: true, data: [{ productTypeId: 'P1', productName: '牛肉丸', unit: 'kg', quantity: 3, unitPrice: 77777.77, costUnitPrice: 5, grossProfit: 1, grossMarginPct: 40, discountAmount: 0, taxAmount: 0, netProfit: 1, historicalAvgPrice: 9, priceTrend: 'UP' }] })
        : Promise.resolve({ data: { orderNumber: 'SO-1' } }),
    );
  });

  it('无权限: 只剩 产品/数量, 价格不进 DOM, 显示隐藏提示', async () => {
    canViewPriceRef.value = false;
    const w = mount(ProfitDetail, { global: { stubs } });
    await flushPromises();
    const ids = w.find('.col-ids').text().split(',');
    expect(ids).toEqual(['productName', 'quantity']);
    expect(w.html()).not.toContain('77,777.77');
    expect(w.html()).not.toContain('77777.77');
    expect(w.find('[data-testid="profit-price-hidden"]').exists()).toBe(true);
    expect(w.text()).toContain('牛肉丸');
  });

  it('有权限: 价格列全部出现, 金额渲染, 无隐藏提示', async () => {
    canViewPriceRef.value = true;
    const w = mount(ProfitDetail, { global: { stubs } });
    await flushPromises();
    const ids = w.find('.col-ids').text().split(',');
    for (const c of PRICE_COLS) expect(ids).toContain(c);
    expect(w.html()).toContain('77,777.77');
    expect(w.find('[data-testid="profit-price-hidden"]').exists()).toBe(false);
  });

  it('空值统一显示「—」, 不出现单个「-」', async () => {
    canViewPriceRef.value = true;
    mockGet.mockImplementation((url: string) =>
      url.includes('profit-detail')
        ? Promise.resolve({ success: true, data: [{ productTypeId: 'P1', productName: '牛肉丸', unit: 'kg', quantity: null, unitPrice: 1, costUnitPrice: null, grossProfit: null, grossMarginPct: null, discountAmount: null, taxAmount: null, netProfit: null, historicalAvgPrice: null, priceTrend: null }] })
        : Promise.resolve({ data: { orderNumber: 'SO-1' } }),
    );
    const w = mount(ProfitDetail, { global: { stubs } });
    await flushPromises();
    const cells = w.findAll('.row span, .row strong').map((e) => e.text());
    expect(cells.filter((t) => t === '—').length).toBeGreaterThanOrEqual(7);
    expect(cells.filter((t) => t === '-')).toEqual([]);
  });
});
