import { afterEach, describe, expect, it, vi } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import CreateReturnOrderDialog from '../CreateReturnOrderDialog.vue';
import { createReturnOrder } from '@/api/returnOrder';
import { buildPurchaseReturnItems } from '@/views/procurement/orders/purchaseReturnItems';

vi.mock('@/api/returnOrder', () => ({ createReturnOrder: vi.fn() }));
const source = [
  { materialTypeId: 'flour', materialName: '面粉', unit: '箱', quantity: 10, receivedQuantity: 12, unitPrice: 44.2478, taxRate: 13, priceUnit: '箱', quantityToPriceFactor: 1, inventoryBaseUnitSnapshot: 'kg', packageToBaseFactorSnapshot: 10 },
  { materialTypeId: 'bags', materialName: '食品袋', unit: '包', quantity: 10, receivedQuantity: 13, unitPrice: 2.6549, taxRate: 13, priceUnit: '包', quantityToPriceFactor: 1, inventoryBaseUnitSnapshot: '个', packageToBaseFactorSnapshot: 10 },
];
afterEach(() => { document.body.innerHTML = ''; vi.clearAllMocks(); });

describe('actual purchase quality return dialog', () => {
  it('submits quality reason with inventory quantities and tax-inclusive prices', async () => {
    vi.mocked(createReturnOrder).mockResolvedValue({ id: 'return', factoryId: 'factory', returnNumber: 'RT-QUALITY', returnType: 'PURCHASE_RETURN', status: 'DRAFT', counterpartyId: 'supplier', returnDate: '2026-10-16', withGoods: true, createdBy: 1 });
    const wrapper = mount(CreateReturnOrderDialog, { attachTo: document.body, props: { modelValue: true, factoryId: 'factory', returnType: 'PURCHASE_RETURN', sourceOrderId: 'po', counterpartyId: 'supplier', items: buildPurchaseReturnItems(source) }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    const dialog = document.querySelector('.el-dialog')!;
    expect(dialog.textContent).toContain('kg');
    expect(dialog.textContent).toContain('个');
    const quantities = dialog.querySelectorAll<HTMLInputElement>('.el-input-number input');
    for (const [index, value] of ['10', '20'].entries()) {
      quantities[index].value = value;
      quantities[index].dispatchEvent(new Event('input', { bubbles: true }));
      quantities[index].dispatchEvent(new Event('change', { bubbles: true }));
      quantities[index].dispatchEvent(new Event('blur'));
    }
    dialog.querySelector<HTMLElement>('.el-select__wrapper')!.click();
    await flushPromises();
    const option = [...document.querySelectorAll<HTMLElement>('.el-select-dropdown__item')].find(x => x.textContent === '质量问题');
    expect(option).toBeDefined(); option!.click(); await flushPromises();
    [...dialog.querySelectorAll<HTMLButtonElement>('button')].find(x => x.textContent?.trim() === '提交退货申请')!.click();
    await flushPromises();
    expect(createReturnOrder).toHaveBeenCalledWith('factory', expect.objectContaining({ sourceOrderId: 'po', withGoods: true, reason: '质量问题', items: [expect.objectContaining({ materialTypeId: 'flour', quantity: 10, unitPrice: 5 }), expect.objectContaining({ materialTypeId: 'bags', quantity: 20, unitPrice: 0.3 })] }));
    expect(wrapper.emitted('success')).toHaveLength(1);
    wrapper.unmount();
  });
});
