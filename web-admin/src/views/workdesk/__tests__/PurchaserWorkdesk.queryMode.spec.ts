import { beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import PurchaserWorkdesk from '../PurchaserWorkdesk.vue';

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post } }));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => ({ factoryId: 'F006' }),
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
    post.mockResolvedValue({ data: { status: 'SUCCESS', resultData: {} } });
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
