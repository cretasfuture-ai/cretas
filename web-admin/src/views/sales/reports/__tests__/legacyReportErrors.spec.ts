import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { mount, flushPromises, type VueWrapper } from '@vue/test-utils';
import Daily from '../daily.vue';
import Monthly from '../monthly.vue';
import Yearly from '../yearly.vue';
import CustomerRank from '../customer-rank.vue';
import ProductRank from '../product-rank.vue';

const { pythonFetchMock } = vi.hoisted(() => ({ pythonFetchMock: vi.fn() }));
vi.mock('@/api/smartbi/common', () => ({
  pythonFetch: (...args: unknown[]) => pythonFetchMock(...args),
  getFactoryId: () => 'F006',
}));
vi.mock('element-plus', () => ({ ElMessage: { error: vi.fn() } }));

const stubs = {
  'el-card': { template: '<div class="el-card"><slot /></div>' },
  'el-alert': {
    props: ['title', 'type', 'closable'],
    template: '<div role="alert" :data-type="type" :data-closable="closable">{{ title }}</div>',
  },
  'el-date-picker': { emits: ['change'], template: '<input class="retry" @change="$emit(\'change\')" />' },
  'el-input-number': { emits: ['change'], template: '<input class="retry" @change="$emit(\'change\')" />' },
  'el-button': { emits: ['click'], template: '<button @click="$emit(\'click\')"><slot /></button>' },
  'el-table': {
    props: ['data'],
    template: '<div class="el-table">{{ JSON.stringify(data) }}</div>',
  },
  'el-table-column': { template: '<div />' },
};
const success = {
  success: true, message: 'ok', data: {
    date: '2026-10-01', yearMonth: '2026-10', year: 2026, orderCount: 1,
    totalAmountWithTax: 123, taxableAmount: 100, taxAmount: 23, paid: 0, unpaid: 123,
    daily: [], monthly: [], rank: [{ productName: 'PRODUCT_SENTINEL', customerName: 'CUSTOMER_SENTINEL' }],
  },
};
const cases = [
  { name: 'daily', component: Daily, endpoint: '/daily-report?', hasCards: true },
  { name: 'monthly', component: Monthly, endpoint: '/monthly-report?', hasCards: true },
  { name: 'yearly', component: Yearly, endpoint: '/yearly-report?', hasCards: true },
  { name: 'customer-rank', component: CustomerRank, endpoint: '/customer-rank?', hasCards: false },
  { name: 'product-rank', component: ProductRank, endpoint: '/product-rank?', hasCards: false },
];
let wrapper: VueWrapper | undefined;
beforeEach(() => { pythonFetchMock.mockReset(); });
afterEach(() => { wrapper?.unmount(); wrapper = undefined; });

async function retry(w: VueWrapper): Promise<void> {
  if (w.find('button').exists()) await w.find('button').trigger('click');
  else await w.find('.retry').trigger('change');
  await flushPromises();
}

for (const report of cases) {
  describe(`legacy ${report.name} real component error handling`, () => {
    it.each([
      [400, '查询条件不合法,请检查日期范围后重试'],
      [403, '没有查看该报表的权限,请联系管理员开通'],
    ])('%i stays visible as a non-dismissible error without network wording', async (status, text) => {
      pythonFetchMock.mockRejectedValue(new Error(`Python service error: ${status} ${status === 400 ? 'Bad Request' : 'Forbidden'}`));
      wrapper = mount(report.component, { global: { stubs, directives: { loading: {} } } });
      await flushPromises();
      expect(pythonFetchMock).toHaveBeenCalledTimes(1);
      expect(String(pythonFetchMock.mock.calls[0][0])).toContain(report.endpoint);
      const alert = wrapper.find('[role="alert"]');
      expect(alert.exists()).toBe(true);
      expect(alert.attributes('data-type')).toBe('error');
      expect(alert.attributes('data-closable')).toBe('false');
      expect(alert.text()).toBe(text);
      expect(wrapper.text()).not.toContain('网络');
      await flushPromises();
      expect(wrapper.find('[role="alert"]').text()).toBe(text);
    });

    it('clears stale results on a denied refresh and clears the alert after success', async () => {
      pythonFetchMock.mockResolvedValueOnce(success)
        .mockRejectedValueOnce(new Error('Python service error: 403 Forbidden'))
        .mockResolvedValueOnce(success);
      wrapper = mount(report.component, { global: { stubs, directives: { loading: {} } } });
      await flushPromises();
      if (report.hasCards) expect(wrapper.findAll('.el-card').length).toBeGreaterThan(0);
      else expect(wrapper.find('.el-table').text()).toContain('SENTINEL');
      await retry(wrapper);
      expect(pythonFetchMock).toHaveBeenCalledTimes(2);
      expect(wrapper.find('[role="alert"]').text()).toContain('权限');
      expect(wrapper.findAll('.el-card')).toHaveLength(0);
      expect(wrapper.text()).not.toContain('SENTINEL');
      await retry(wrapper);
      expect(pythonFetchMock).toHaveBeenCalledTimes(3);
      expect(wrapper.find('[role="alert"]').exists()).toBe(false);
      if (report.hasCards) expect(wrapper.findAll('.el-card').length).toBeGreaterThan(0);
      else expect(wrapper.find('.el-table').text()).toContain('SENTINEL');
    });
  });
}
