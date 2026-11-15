import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import PurchaserWorkdesk from '../PurchaserWorkdesk.vue';

const { post, get } = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post, get } }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006', user: { id: 1709 } }),
}));

const query = '请比较现有供应商的报价；没有物料报价时说明不能比价';
const endpoint = '/F006/ai-intents/execute';

async function openWorkdesk() {
  // Real Element Plus input/button: their native events must reach the original
  // template bindings. A click stub emitting no MouseEvent hides this defect.
  const wrapper = mount(PurchaserWorkdesk, {
    global: { plugins: [ElementPlus] },
  });
  await flushPromises();
  return wrapper;
}

describe('PurchaserWorkdesk query mode through mounted user events', () => {
  beforeEach(() => {
    post.mockReset();
    get.mockReset();
    get.mockResolvedValue({ data: [{ id: 'RAW_TEST', minStock: 10 }] });
    post.mockResolvedValue({ data: { status: 'SUCCESS', resultData: {} } });
  });

  it('waits for tenant navigation before using the live wholesale low-stock query', async () => {
    const wrapper = mount(PurchaserWorkdesk, {
      props: { employeeNavigationPending: true }, global: { plugins: [ElementPlus] },
    });
    await flushPromises();
    expect(post).not.toHaveBeenCalled();
    await wrapper.setProps({ digitalEmployee: true, employeeNavigationPending: false });
    await flushPromises();
    expect(post.mock.calls).toEqual([[endpoint, { userInput: '查询低库存预警', mode: 'READ', sessionId: expect.any(String) }]]);
    expect(wrapper.findComponent({ name: 'DigitalEmployeeFrame' }).props('enabled')).toBe(true);
    post.mockClear();
    await wrapper.setProps({ employeeNavigationPending: true });
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    expect(post).not.toHaveBeenCalled();
    wrapper.unmount();
  });

  it('keeps mount and explicit reload in automatic weekly-plan mode', async () => {
    const wrapper = await openWorkdesk();
    expect(post.mock.calls).toEqual([
      [endpoint, { userInput: '下周采购什么?', intentCode: 'PURCHASER_WEEKLY_PLAN' }],
      [endpoint, { userInput: '7 天销售预测', intentCode: 'SALES_FORECAST_7DAY' }],
    ]);
    await wrapper.get('.chat-input textarea').setValue(query);
    post.mockClear();
    await wrapper.get('.header-actions button').trigger('click');
    await flushPromises();
    expect(post.mock.calls).toEqual([
      [endpoint, { userInput: '下周采购什么?', intentCode: 'PURCHASER_WEEKLY_PLAN' }],
      [endpoint, { userInput: '7 天销售预测', intentCode: 'SALES_FORECAST_7DAY' }],
    ]);
  });

  it('uses the emitted original question after the new conversation clears its input', async () => {
    const wrapper = mount(PurchaserWorkdesk, { props: { digitalEmployee: true }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    post.mockClear();
    const conversation = wrapper.findComponent({ name: 'DigitalEmployeeConversation' });
    conversation.vm.$emit('update:modelValue', '');
    conversation.vm.$emit('send', query);
    await flushPromises();
    expect(post.mock.calls).toEqual([[endpoint, { userInput: query, mode: 'READ', sessionId: expect.any(String) }]]);
    wrapper.unmount();
  });

  it.each([{ materials: [] }, { materials: [{ id: 'RAW_TEST', minStock: null }] }])('does not claim sufficient stock without catalog or thresholds', async ({ materials }) => {
    get.mockResolvedValue({ data: materials });
    const wrapper = mount(PurchaserWorkdesk, { props: { digitalEmployee: true }, global: { plugins: [ElementPlus] } });
    await flushPromises();
    expect(post).not.toHaveBeenCalled();
    expect(wrapper.findComponent({ name: 'DigitalEmployeeConversation' }).props('responseStatus')).toBe('NEED_MORE_INFO');
    expect(wrapper.findComponent({ name: 'DigitalEmployeeConversation' }).props('response')).not.toContain('库存充足');
    wrapper.unmount();
  });

  it('sends the exact manual query without a forced intent on a real button click', async () => {
    const wrapper = await openWorkdesk();
    await wrapper.get('.chat-input textarea').setValue(query);
    post.mockClear();
    await wrapper.get('.chat-input button').trigger('click');
    await flushPromises();
    expect(post.mock.calls).toEqual([[endpoint, { userInput: query }]]);
  });

  it('sends the exact manual query without a forced intent on Ctrl+Enter', async () => {
    const wrapper = await openWorkdesk();
    const input = wrapper.get('.chat-input textarea');
    await input.setValue(query);
    post.mockClear();
    await input.trigger('keydown', { key: 'Enter', code: 'Enter', ctrlKey: true });
    await flushPromises();
    expect(post.mock.calls).toEqual([[endpoint, { userInput: query }]]);
  });

  it('does not submit the query on Enter without Ctrl', async () => {
    const wrapper = await openWorkdesk();
    const input = wrapper.get('.chat-input textarea');
    await input.setValue(query);
    post.mockClear();
    await input.trigger('keydown', { key: 'Enter', code: 'Enter' });
    await flushPromises();
    expect(post).not.toHaveBeenCalled();
  });
});
