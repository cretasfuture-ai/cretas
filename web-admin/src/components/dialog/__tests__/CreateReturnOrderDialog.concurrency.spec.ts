import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus, { ElMessage } from 'element-plus';
import CreateReturnOrderDialog from '../CreateReturnOrderDialog.vue';
import { createReturnOrder, type ReturnOrder } from '@/api/returnOrder';

vi.mock('@/api/returnOrder', () => ({ createReturnOrder: vi.fn() }));
const cleanup: (() => void)[] = [];
afterEach(() => { cleanup.splice(0).forEach(fn => fn()); document.body.innerHTML = ''; vi.clearAllMocks(); vi.restoreAllMocks(); });
const result: ReturnOrder = { id: 'return', factoryId: 'factory', returnNumber: 'RT-ONCE', returnType: 'PURCHASE_RETURN', status: 'DRAFT', counterpartyId: 'supplier', returnDate: '2026-10-16', withGoods: true, createdBy: 1 };
async function dialog(maxQuantity = 10) {
  const wrapper = mount(CreateReturnOrderDialog, { attachTo: document.body, props: { modelValue: true, factoryId: 'factory', returnType: 'PURCHASE_RETURN', sourceOrderId: 'po', counterpartyId: 'supplier', items: [{ materialTypeId: 'flour', itemName: '面粉', unit: 'kg', unitPrice: 5, maxQuantity }] }, global: { plugins: [ElementPlus] } });
  cleanup.push(() => wrapper.unmount()); await flushPromises();
  const element = document.querySelector<HTMLElement>('.el-dialog')!;
  element.querySelector<HTMLElement>('.el-select__wrapper')!.click(); await flushPromises();
  [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(x => x.textContent === '质量问题')!.click(); await flushPromises();
  const submit = () => [...element.querySelectorAll<HTMLButtonElement>('button')].find(x => x.textContent?.trim() === '提交退货申请')!;
  return { element, submit };
}
describe('purchase return duplicate submission', () => {
  it('sends one request when two clicks overlap asynchronous form validation', async () => {
    let resolve!: (order: ReturnOrder) => void;
    vi.mocked(createReturnOrder).mockImplementation(() => new Promise<ReturnOrder>(r => { resolve = r; }));
    const { submit } = await dialog(); submit().click(); submit().click(); await flushPromises();
    expect(createReturnOrder).toHaveBeenCalledTimes(1);
    expect(vi.mocked(createReturnOrder).mock.calls[0][1].requestId).toMatch(/^[0-9a-f-]{36}$/i);
    resolve(result); await flushPromises();
  });
  it('keeps the same request identifier for a retry after an uncertain failure', async () => {
    vi.mocked(createReturnOrder).mockRejectedValueOnce(new Error('退货请求暂未确认')).mockResolvedValueOnce(result);
    const { submit } = await dialog(); submit().click(); await flushPromises(); submit().click(); await flushPromises();
    expect(createReturnOrder).toHaveBeenCalledTimes(2);
    const calls = vi.mocked(createReturnOrder).mock.calls;
    expect(calls[1][1].requestId).toBe(calls[0][1].requestId);
  });
  it('blocks a positive quantity when cumulative remaining capacity is zero', async () => {
    const { element, submit } = await dialog(0);
    element.querySelector<HTMLInputElement>('.el-table__row .el-checkbox input')!.click(); await flushPromises();
    const input = element.querySelector<HTMLInputElement>('.el-input-number input')!;
    input.value = '1'; input.dispatchEvent(new Event('input', { bubbles: true })); input.dispatchEvent(new Event('change', { bubbles: true })); input.dispatchEvent(new Event('blur')); await flushPromises();
    expect(submit().disabled).toBe(true); expect(createReturnOrder).not.toHaveBeenCalled();
  });
  it('does not describe an already completed idempotent result as a new draft', async () => {
    const feedback = vi.spyOn(ElMessage, 'success');
    vi.mocked(createReturnOrder).mockResolvedValue({ ...result, status: 'COMPLETED' });
    const { submit } = await dialog(); submit().click(); await flushPromises();
    expect(feedback).toHaveBeenCalledWith(expect.stringContaining('该退货请求已处理'));
    expect(feedback).not.toHaveBeenCalledWith(expect.stringContaining('DRAFT 草稿'));
  });
});
