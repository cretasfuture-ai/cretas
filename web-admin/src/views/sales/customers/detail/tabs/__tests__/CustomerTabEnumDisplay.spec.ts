/**
 * 客户详情页签: 后端枚举原码要显示成中文, 退货备注里的内部锚点不外露。
 * 给一个原码 (NORMAL / REQUESTED / COMPLETED ...), 断言渲染出的是中文且原码不在页面上。
 */
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { ref } from 'vue';
import InvoicesTab from '../InvoicesTab.vue';
import PaymentsTab from '../PaymentsTab.vue';
import ReturnsTab from '../ReturnsTab.vue';
import QuotesTab from '../QuotesTab.vue';
import { cleanRemark } from '../customerTabLabels';
import { customerTabStubs } from './customerTabStubs';

const get = vi.hoisted(() => vi.fn());
vi.mock('@/api/request', () => ({ get }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => ({ factoryId: ref('F1') }) }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canViewPrice: ref(true) }) }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const UUID = '3f2b8c1e-aaaa-4bbb-8ccc-0123456789ab';

async function render(component: object, content: unknown[]) {
  get.mockResolvedValue({ success: true, data: { content, totalElements: content.length } });
  const w = mount(component, { props: { customerId: 'C1' }, global: { stubs: customerTabStubs } });
  await flushPromises();
  return w;
}

describe('customer tabs show Chinese labels, not enum codes', () => {
  beforeEach(() => get.mockReset());

  it('invoices: NORMAL / REQUESTED -> 普通发票 / 待审核', async () => {
    const w = await render(InvoicesTab, [{ id: 'I1', invoiceNumber: 'INV-1', invoiceType: 'NORMAL', status: 'REQUESTED', amount: 1 }]);
    expect(w.text()).toContain('普通发票');
    expect(w.text()).toContain('待审核');
    expect(w.text()).not.toMatch(/NORMAL|REQUESTED/);
  });

  it('payments: PENDING / BANK_TRANSFER -> 待核实 / 银行转账', async () => {
    const w = await render(PaymentsTab, [{ id: 'P1', paymentNumber: 'PAY-1', status: 'PENDING', paymentMethod: 'BANK_TRANSFER', amount: 1 }]);
    expect(w.text()).toContain('待核实');
    expect(w.text()).toContain('银行转账');
    expect(w.text()).not.toMatch(/PENDING|BANK_TRANSFER/);
  });

  it('quotes: PENDING_APPROVAL -> 待审批', async () => {
    const w = await render(QuotesTab, [{ id: 'Q1', quoteNo: 'QT-1', status: 'PENDING_APPROVAL', unitPrice: 1 }]);
    expect(w.text()).toContain('待审批');
    expect(w.text()).not.toContain('PENDING_APPROVAL');
  });

  it('returns: COMPLETED -> 已完成, 备注只留用户写的部分', async () => {
    const w = await render(ReturnsTab, [
      { id: 'R1', returnNumber: 'RT-1', status: 'COMPLETED', totalAmount: 1, remark: `客户要求退货;RECALL_EVENT_ID=2;SHIPMENT_ID=${UUID};` },
    ]);
    expect(w.text()).toContain('已完成');
    expect(w.text()).toContain('客户要求退货');
    expect(w.text()).not.toMatch(/COMPLETED|RECALL_EVENT_ID|SHIPMENT_ID/);
    expect(w.text()).not.toContain(UUID);
  });

  it('returns: 纯锚点备注显示说明而不是 id', async () => {
    const w = await render(ReturnsTab, [
      { id: 'R2', returnNumber: 'RT-2', status: 'COMPLETED', totalAmount: 1, remark: `RECALL_EVENT_ID=2;SHIPMENT_ID=${UUID};` },
    ]);
    expect(w.text()).toContain('由批次召回登记');
    expect(w.text()).not.toContain(UUID);
  });
});

describe('cleanRemark', () => {
  it('keeps plain text, empties to dash, strips anchors', () => {
    expect(cleanRemark('普通备注')).toBe('普通备注');
    expect(cleanRemark(null)).toBe('—');
    expect(cleanRemark(`RECALL_EVENT_ID=2;SHIPMENT_ID=${UUID};`)).toBe('由批次召回登记');
    expect(cleanRemark(`SHIPMENT_ID=${UUID}; 破损`)).toBe('破损');
  });
});
