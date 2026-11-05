// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
vi.mock('@nuxt/ui/components/Button.vue', () => ({ default: { name: 'UButton', template: '<button><slot /></button>' } }));
import StockReconciliation from '../StockReconciliation.vue';
import type { StockReconciliationRow } from '@/api/smartbi/blacklake-live';

const row = (overrides: Partial<StockReconciliationRow> = {}): StockReconciliationRow => ({
  key: 'm-1', productId: 'p-1', productCode: 'SKU-1', product: '番茄酱', warehouseKey: 'w-1', warehouse: '一号仓', unit: 'kg', quantity: null, checkedAt: '2026-11-05T10:00:00Z', status: 'needs_review', conclusion: '期末库存暂无法确认。', possibleReasons: ['盘点记录未回传'], owner: '仓库主管', action: '补录盘点单', afterProvided: '', openingQuantity: 10, openingAt: null, closingQuantity: null, closingAt: '', inbound: 2, outbound: 1, expectedClosing: 11, difference: null, unitIssues: [], movementCoverage: '部分覆盖', movements: [], ...overrides,
});

describe('StockReconciliation', () => {
  it('renders an honest empty state and does not turn missing values into zero', () => {
    const wrapper = mount(StockReconciliation, { props: { rows: [] } });
    expect(wrapper.text()).toContain('暂无可核对的库存记录');
    expect(wrapper.text()).not.toContain('0.00');
  });
  it('shows material evidence, unconfirmed reasons and expandable movements', async () => {
    const wrapper = mount(StockReconciliation, { props: { rows: [row({ status: 'confirmed', conclusion: '收发与盘点一致。', movements: [{ id: 'mv-1', orderNo: 'IN-1', direction: 'in', bizAt: null, approval: '已审核', quantity: 2, unit: 'kg', counted: true, reason: '' }] })] } });
    expect(wrapper.text()).toContain('番茄酱');
    expect(wrapper.text()).toContain('已确认');
    expect(wrapper.text()).not.toContain('收发与盘点一致');
    await wrapper.get('.material-toggle').trigger('click');
    expect(wrapper.text()).toContain('收发与盘点一致');
    expect(wrapper.text()).toContain('当前账面余额 未提供');
    expect(wrapper.text()).toContain('可能原因（尚未确认）');
    expect(wrapper.text()).toContain('IN-1');
  });
  it('opens only the selected material and keeps other explanations out of the page', async () => {
    const wrapper = mount(StockReconciliation, { props: { rows: [row(), row({key:'m-2',product:'牛肉',conclusion:'核对牛肉领料单'})] } });
    expect(wrapper.findAll('.material-detail')).toHaveLength(0);
    const buttons=wrapper.findAll('.material-toggle');
    await buttons[0].trigger('click');
    expect(wrapper.text()).toContain('期末库存暂无法确认');
    await buttons[1].trigger('click');
    expect(wrapper.text()).not.toContain('期末库存暂无法确认');
    expect(wrapper.text()).toContain('核对牛肉领料单');
    expect(buttons[0].attributes('aria-expanded')).toBe('false');
    await buttons[1].trigger('click');
    expect(wrapper.findAll('.material-detail')).toHaveLength(0);
  });
});
