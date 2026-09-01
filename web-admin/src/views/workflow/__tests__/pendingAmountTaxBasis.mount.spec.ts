/**
 * 缺陷 30 续 (2026-09-01, LIUSHANMEN 生产, PO-20260901-0004):
 *
 *   OA 审批中心列表的「金额」列, 与「通过」前的确认框, 都只显示
 *   `formatAmount(amountOf(row))` —— 那是**未税**额, 且没有任何标注。
 *   审批人在这两处都看不到含税总额, 而「通过」按钮就在确认框里, 点下去就是批钱。
 *
 * 🔒 owner 裁定(2026-09-01):「首先肯定是计算含税总额, 然后告诉这个税率是多少,
 * 然后再计算未税总额」—— 含税领先、税率说明、未税殿后, 且**两处必须同一条口径**
 * (approveConfirmMessage 复用 businessCodeOf/amountOf, ⛔ 不许另拼一份, 见其自身注释)。
 *
 * ⚠️ 与源码闸(personalOaWorkbench.source.spec.ts / inventoryAdjustmentApprovalIsReadable
 * .source.spec.ts)分工不同: 那两份钉的是「特定字符串还在源码里」, 本文件钉的是
 * 「渲染/弹框里出现的是不是真实数字」—— 走真实 pinia store + 渲染出来的按钮,
 * 只桩掉 HTTP / ElMessageBox / 路由。
 */
import { flushPromises, mount } from '@vue/test-utils';
import { computed, defineComponent, h, inject, provide } from 'vue';
import type { ComputedRef } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPinia, setActivePinia } from 'pinia';

const harness = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  confirm: vi.fn(),
}));

vi.mock('vue-router', async () => {
  const { reactive } = await import('vue');
  const route = reactive({ query: {} as Record<string, string | undefined> });
  return { useRoute: () => route, useRouter: () => ({ push: vi.fn() }) };
});

vi.mock('@/api/request', () => ({ get: harness.get, post: harness.post }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: 'LIUSHANMEN', currentRole: 'finance_manager' }) }));
vi.mock('@/utils/errorToast', () => ({ handleCatchError: vi.fn() }));
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), info: vi.fn(), error: vi.fn() },
  ElMessageBox: { confirm: harness.confirm, prompt: vi.fn() },
}));

import PendingApprovals from '../pending.vue';

const ROWS_KEY = 'stub-table-rows';

const ElTableStub = defineComponent({
  name: 'ElTable',
  props: { data: { type: Array, default: () => [] } },
  setup(props, { slots }) {
    provide(ROWS_KEY, computed(() => props.data));
    return () => h('div', { class: 'stub-table' }, slots.default?.());
  },
});

const ElTableColumnStub = defineComponent({
  name: 'ElTableColumn',
  props: { label: { type: String, default: '' } },
  setup(props, { slots }) {
    const rows = inject<ComputedRef<Array<Record<string, unknown>>>>(ROWS_KEY, computed(() => []));
    return () => h(
      'div',
      { class: 'stub-column', 'data-col-label': props.label },
      rows.value.map((row) => h('div', { class: 'stub-cell', 'data-instance-id': row.instanceId as string }, slots.default?.({ row }))),
    );
  },
});

const ElButtonStub = defineComponent({
  name: 'ElButton',
  emits: ['click'],
  setup(_props, { slots, emit }) {
    return () => h('button', { onClick: () => emit('click') }, slots.default?.());
  },
});

const PassThrough = (name: string) => defineComponent({
  name,
  setup(_props, { slots }) {
    return () => h('div', slots.default?.());
  },
});

function mountPending() {
  return mount(PendingApprovals, {
    global: {
      directives: { loading: {} },
      stubs: {
        ElTable: ElTableStub,
        ElTableColumn: ElTableColumnStub,
        ElButton: ElButtonStub,
        ElCard: PassThrough('ElCard'),
        ElTag: PassThrough('ElTag'),
        ElTooltip: PassThrough('ElTooltip'),
        ElSelect: PassThrough('ElSelect'),
        ElOption: PassThrough('ElOption'),
        ElPagination: PassThrough('ElPagination'),
      },
    },
  });
}

/** PO-20260901-0004 的形状: 未税 750 / 税额 97.5 / 税率 13%。 */
const TAXED_PO_ROW = {
  instanceId: 'inst-po-1',
  moduleCode: 'PURCHASE_ORDER',
  businessEntityId: 'po-1',
  businessCode: 'PO-20260901-0004',
  businessAmount: 750,
  businessTaxAmount: 97.5,
  counterpartyName: '测试供应商',
  currentNodeId: 'finance-approval',
  currentNodeLabel: '财务审批',
};

/** 后端目前只对 PURCHASE_ORDER 下发税额 —— 其余模块 businessTaxAmount 恒为 null/undefined。 */
const UNTAXED_SO_ROW = {
  instanceId: 'inst-so-1',
  moduleCode: 'SALES_ORDER',
  businessEntityId: 'so-1',
  businessCode: 'SO-20260901-0001',
  businessAmount: 1000,
  counterpartyName: '测试客户',
  currentNodeId: 'sales-approval',
  currentNodeLabel: '销售审批',
};

describe('OA 待办列表 + 通过前确认框的金额口径', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    [harness.get, harness.post, harness.confirm].forEach((fn) => fn.mockReset());
    harness.get.mockResolvedValue({
      success: true,
      data: { items: [TAXED_PO_ROW, UNTAXED_SO_ROW], total: 2 },
    });
    harness.post.mockResolvedValue({ success: true });
    harness.confirm.mockResolvedValue('confirm');
  });

  it('「金额」列: 有税额的行含税领先(主行) + 税率/未税殿后(副行); 无税额的行只显示单一个数', async () => {
    const wrapper = mountPending();
    await flushPromises();

    const amountCol = wrapper.find('[data-col-label="金额"]');
    expect(amountCol.exists(), '「金额」列都没渲染出来 —— 阳性对照失败, 后面读数无意义').toBe(true);

    const taxedCell = amountCol.find('[data-instance-id="inst-po-1"]');
    expect(taxedCell.find('.amount-cell').text(), '含税总额应该是主行, 且是真实数字').toBe('¥847.50');
    expect(taxedCell.find('.amount-sub').text(), '未税/税率应该在副行, 且是真实数字')
      .toBe('税率13% · 未税¥750.00');

    const untaxedCell = amountCol.find('[data-instance-id="inst-so-1"]');
    expect(untaxedCell.find('.amount-cell').text(), '没有税额数据时应显示单一个未税数')
      .toBe('¥1,000.00');
    expect(untaxedCell.find('.amount-sub').exists(), '没有税额数据时不该硬凑一行「税率0%」')
      .toBe(false);
  });

  it('「通过」前确认框: 含税总额领先、税率说清楚、未税殿后, 且是这单的真实数字', async () => {
    const wrapper = mountPending();
    await flushPromises();

    const approveButtons = wrapper.findAll('button').filter((btn) => btn.text() === '通过');
    expect(approveButtons.length, '阳性对照: 至少应该渲染出一个「通过」按钮').toBeGreaterThan(0);
    await approveButtons[0]!.trigger('click');
    await flushPromises();

    expect(harness.confirm, '确认框没有被调用 —— 后面对弹框内容的断言无意义').toHaveBeenCalled();
    const html = harness.confirm.mock.calls[0]![0] as string;

    // 三段都要在, 且顺序是含税 -> 税率 -> 未税(owner 裁定的是顺序, 不只是"都要有")。
    const inclusiveIdx = html.indexOf('金额（含税）');
    const rateIdx = html.indexOf('税率');
    const untaxedIdx = html.indexOf('金额（未税）');
    expect([inclusiveIdx, rateIdx, untaxedIdx].every((i) => i >= 0),
      `三段都应该出现在确认框里: ${html}`).toBe(true);
    expect(inclusiveIdx).toBeLessThan(rateIdx);
    expect(rateIdx).toBeLessThan(untaxedIdx);

    // 且必须是这张单真实的数字, ⛔ 不是占位符/裸未税数。
    expect(html).toContain('¥847.50');
    expect(html).toContain('13%');
    expect(html).toContain('¥750.00');
  });
});
