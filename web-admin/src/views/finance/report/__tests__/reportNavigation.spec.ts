import { describe, expect, it, vi } from 'vitest';
import { mount } from '@vue/test-utils';
import { reactive, nextTick } from 'vue';
const route = reactive({ query: { tab: 'income-statement' } });
vi.mock('vue-router', () => ({ useRoute: () => route }));
vi.mock('../BalanceSheet.vue', () => ({ default: { template: '<div>资产负债内容</div>' } }));
vi.mock('../IncomeStatement.vue', () => ({ default: { template: '<div>利润表内容</div>' } }));
vi.mock('../CashFlow.vue', () => ({ default: { template: '<div>现金流内容</div>' } }));
import Page from '../index.vue';
describe('既有财务报表标签', () => {
  it('同期间链接直接挂载利润表，非法标签回到原默认', async () => {
    const wrapper = mount(Page, { global: { stubs: { 'el-tabs': { template: '<div><slot /></div>' }, 'el-tab-pane': { template: '<div><slot /></div>' } } } });
    expect(wrapper.text()).toContain('利润表内容'); expect(wrapper.text()).not.toContain('资产负债内容');
    route.query.tab = 'unknown'; await nextTick(); expect(wrapper.text()).toContain('资产负债内容'); expect(wrapper.text()).not.toContain('利润表内容'); wrapper.unmount();
  });
});
