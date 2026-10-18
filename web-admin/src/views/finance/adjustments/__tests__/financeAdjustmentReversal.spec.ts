import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { reactive } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import Adjustments from '../list.vue';

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  post: vi.fn(),
  refundByRequest: vi.fn(),
  confirm: vi.fn(),
  prompt: vi.fn(),
  push: vi.fn(),
}));

const route = reactive({ query: {} as Record<string, unknown> });
const identity = reactive<{ factoryId: string; user: { id: number | undefined } }>({
  factoryId: 'F001', user: { id: 7 },
});

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push: mocks.push, replace: vi.fn() }),
}));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: mocks.refundByRequest }));
vi.mock('@/api/returnFinancialFollowUp', () => ({
  getReturnFinancialFollowUp: vi.fn(),
  validReturnOrderId: (id: unknown) => {
    const value = String(id ?? '').trim();
    return value && value !== 'undefined' && value !== 'null' ? value : null;
  },
}));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({
  usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }),
}));
vi.mock('element-plus', async () => {
  const actual = await vi.importActual<typeof import('element-plus')>('element-plus');
  return {
    ...actual,
    ElMessageBox: { ...actual.ElMessageBox, confirm: mocks.confirm, prompt: mocks.prompt },
  };
});

const reversalRow = {
  id: 'REV-1', factoryId: 'F001', transactionType: 'AR_ADJUSTMENT', counterpartyType: 'CUSTOMER',
  counterpartyId: 'C-1', counterpartyName: '撤销客户', amount: -12, balanceAfter: null,
  approvalStatus: 'PENDING', operatedBy: 7, sourceType: 'RETURN_REFUND_REVERSAL', sourceId: 'RF-1',
  createdAt: '2026-10-16T00:00:00Z',
};
const ordinaryRow = {
  id: 'ADJ-1', factoryId: 'F001', transactionType: 'AR_ADJUSTMENT', counterpartyType: 'CUSTOMER',
  counterpartyId: 'C-2', counterpartyName: '普通客户', amount: 4, balanceAfter: 20,
  approvalStatus: 'PENDING', operatedBy: 8, sourceType: 'OTHER', sourceId: 'ADJ-1',
  createdAt: '2026-10-16T00:00:00Z',
};

let rows: Array<typeof reversalRow | typeof ordinaryRow>;
let wrapper: VueWrapper | undefined;

function render() {
  wrapper = mount(Adjustments, { global: { plugins: [ElementPlus] } });
  return wrapper;
}

beforeEach(() => {
  vi.clearAllMocks();
  route.query = {};
  identity.factoryId = 'F001';
  identity.user.id = 7;
  rows = [{ ...reversalRow }, { ...ordinaryRow }];
  mocks.get.mockImplementation(() => Promise.resolve({ success: true, data: { content: rows, totalElements: rows.length } }));
  mocks.post.mockResolvedValue({ success: true, data: { ...ordinaryRow, approvalStatus: 'APPROVED', balanceAfter: 24 } });
  mocks.refundByRequest.mockResolvedValue({
    returnOrderId: 'return-a',
    request: { id: 'RF-1', reversal: { ledger: { id: 'REV-1' } } },
  });
  mocks.confirm.mockResolvedValue(true);
  mocks.prompt.mockResolvedValue({ value: '核对原因' });
});

afterEach(() => {
  wrapper?.unmount();
  wrapper = undefined;
});

describe('调整审批中的退款撤销专用入口', () => {
  it('keeps the pending reversal visible but disables selection and ordinary actions', async () => {
    const w = render();
    await flushPromises();
    const reversalTableRow = w.findAll('.el-table__body-wrapper tbody tr').find(row => row.text().includes('请从原退款详情审核'));
    expect(reversalTableRow).toBeDefined();
    expect(reversalTableRow!.text()).toContain('待审批');
    expect(reversalTableRow!.find('input[type="checkbox"]').attributes('disabled')).toBeDefined();
    expect(reversalTableRow!.findAll('button').filter(button => ['审批', '驳回'].includes(button.text()))).toHaveLength(0);

    const vm = w.vm as unknown as {
      selectedRows: typeof rows;
      handleBatchReject: () => Promise<void>;
      handleReject: (row: typeof reversalRow) => Promise<void>;
    };
    vm.selectedRows = [{ ...reversalRow }];
    await vm.handleBatchReject();
    await vm.handleReject({ ...reversalRow });
    expect(mocks.prompt).not.toHaveBeenCalled();
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it('navigates by the exact reversal ledger and refund request', async () => {
    rows = [{ ...reversalRow }];
    mocks.get.mockResolvedValue({ success: true, data: { content: rows, totalElements: 1 } });
    const w = render();
    await flushPromises();
    await w.findAll('button').find(button => button.text() === '查看撤销退款')!.trigger('click');
    await flushPromises();

    expect(mocks.refundByRequest).toHaveBeenCalledWith('F001', 'RF-1');
    expect(mocks.push).toHaveBeenCalledWith({
      path: '/finance/return-refunds/return-a',
      query: { refundRequestId: 'RF-1', ledgerId: 'REV-1' },
    });
  });

  it('rejects a mismatched reversal ledger without navigating', async () => {
    rows = [{ ...reversalRow }];
    mocks.get.mockResolvedValue({ success: true, data: { content: rows, totalElements: 1 } });
    mocks.refundByRequest.mockResolvedValueOnce({
      returnOrderId: 'return-a', request: { id: 'RF-1', reversal: { ledger: { id: 'OTHER-REV' } } },
    });
    const w = render();
    await flushPromises();
    await w.findAll('button').find(button => button.text() === '查看撤销退款')!.trigger('click');
    await flushPromises();

    expect(mocks.push).not.toHaveBeenCalled();
  });

  it('does not navigate after the factory changes during an exact source lookup', async () => {
    rows = [{ ...reversalRow }];
    mocks.get.mockResolvedValue({ success: true, data: { content: rows, totalElements: 1 } });
    let resolveLookup!: (value: unknown) => void;
    mocks.refundByRequest.mockReturnValueOnce(new Promise(resolve => { resolveLookup = resolve; }));
    const w = render();
    await flushPromises();
    await w.findAll('button').find(button => button.text() === '查看撤销退款')!.trigger('click');
    identity.factoryId = 'F002';
    await flushPromises();
    resolveLookup({ returnOrderId: 'return-a', request: { id: 'RF-1', reversal: { ledger: { id: 'REV-1' } } } });
    await flushPromises();

    expect(mocks.push).not.toHaveBeenCalled();
  });

  it('keeps ordinary adjustments available for approval', async () => {
    rows = [{ ...ordinaryRow }];
    mocks.get.mockResolvedValue({ success: true, data: { content: rows, totalElements: 1 } });
    const w = render();
    await flushPromises();
    const approvalButtons = w.findAll('button').filter(button => button.text() === '审批');
    await approvalButtons.at(-1)!.trigger('click');
    await flushPromises();

    expect(mocks.confirm).toHaveBeenCalled();
    expect(mocks.post).toHaveBeenCalledWith('/F001/finance/adjustment/ADJ-1/approve');
  });
});
