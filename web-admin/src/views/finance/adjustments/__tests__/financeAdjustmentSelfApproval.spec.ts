import { flushPromises, mount, type VueWrapper } from '@vue/test-utils';
import { reactive } from 'vue';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import ElementPlus from 'element-plus';
import Adjustments from '../list.vue';
import { adjustmentStubs, closeConfirmModal, confirmButton } from './adjustmentStubs';

/**
 * 红线 4: 应收/应付调整的本人审批。
 * 开关关 (默认): 提交人看不到自己那条的「审批」按钮 (与改动前一致);
 * 开关开: 按钮出现, 点了先确认再选/写原因, 原因作为 selfApprovalReason 发给后端; 不写原因发不出去。
 */
const mocks = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn(), }));
const identity = reactive<{ factoryId: string; user: { id: number } }>({ factoryId: 'F006', user: { id: 7 } });

vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
vi.mock('@/api/request', () => ({ get: mocks.get, post: mocks.post }));
vi.mock('@/api/inventory', () => ({ getReturnRefundByRequest: vi.fn() }));
vi.mock('@/api/returnFinancialFollowUp', () => ({ getReturnFinancialFollowUp: vi.fn(), validReturnOrderId: () => null }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => identity }));
vi.mock('@/store/modules/permission', () => ({ usePermissionStore: () => ({ canWrite: () => true, canViewPrice: true }) }));

const ownRow = {
  id: 'QA-ADJ-OWN', factoryId: 'F006', transactionType: 'AR_ADJUSTMENT', counterpartyType: 'CUSTOMER',
  counterpartyId: 'C-1', counterpartyName: 'QA-客户', amount: -30, balanceAfter: 100,
  approvalStatus: 'PENDING', operatedBy: 7, createdAt: '2026-11-13T00:00:00Z',
};

let allowSelfApproval = false;
let wrapper: VueWrapper | undefined;

beforeEach(() => {
  vi.clearAllMocks();
  allowSelfApproval = false;
  mocks.get.mockImplementation(async (url: string) => {
    if (url === '/F006/settings/features') return { success: true, data: { allowSelfApproval } };
    if (url === '/F006/finance/adjustments/pending') return { success: true, data: { content: [{ ...ownRow }], totalElements: 1 } };
    throw new Error('unexpected read ' + url);
  });
  mocks.post.mockResolvedValue({ success: true, data: { ...ownRow, approvalStatus: 'APPROVED', balanceAfter: 70 } });
});

afterEach(() => { closeConfirmModal(); wrapper?.unmount(); wrapper = undefined; document.body.innerHTML = ''; });

async function render() {
  wrapper = mount(Adjustments, { attachTo: document.body, global: { plugins: [ElementPlus], stubs: adjustmentStubs } });
  await flushPromises();
  return wrapper;
}
const approveButtons = (w: VueWrapper) => w.findAll('button').filter(b => b.text() === '审批');
// 原因框的按钮在 UModal 的 footer 里 (不在 body 的 testid 容器内), 此刻确认弹窗已收起, 全页按文字找不会撞
const dialogButton = (text: string) => Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
  .find(b => b.textContent?.trim() === text);

describe('应收/应付调整 本人审批 (红线 4)', () => {
  it('开关关: 提交人看不到自己那条的审批按钮, 不发请求', async () => {
    const w = await render();
    expect(approveButtons(w)).toHaveLength(0);
    expect(mocks.post).not.toHaveBeenCalled();
  });

  it('开关开: 选原因后带 selfApprovalReason 审批', async () => {
    allowSelfApproval = true;
    const w = await render();
    expect(approveButtons(w)).toHaveLength(1);
    await approveButtons(w)[0].trigger('click');
    await flushPromises();
    // 先过确认弹窗, 再进本人审批原因框
    expect(mocks.post).not.toHaveBeenCalled();
    confirmButton('确认审批')!.click();
    await flushPromises();

    expect(document.body.textContent).toContain('这是你自己提交的单');
    const confirm = dialogButton('确认本人审批');
    expect(confirm, 'self-approval dialog confirm button').toBeDefined();
    confirm!.click();
    await flushPromises();

    expect(mocks.post).toHaveBeenCalledWith('/F006/finance/adjustment/QA-ADJ-OWN/approve',
      { selfApprovalReason: '本厂只有我一个人负责这项审批' });
  });

  it('开关开: 取消原因对话框不发请求', async () => {
    allowSelfApproval = true;
    const w = await render();
    await approveButtons(w)[0].trigger('click');
    await flushPromises();
    confirmButton('确认审批')!.click();
    await flushPromises();
    dialogButton('取消')!.click();
    await flushPromises();
    expect(mocks.post).not.toHaveBeenCalled();
  });
});
