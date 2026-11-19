import { defineComponent, reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import Sales from '../SalesOwnerWorkdesk.vue';

const api = vi.hoisted(() => ({ post: vi.fn(), get: vi.fn() }));
const auth = reactive({ factoryId: 'FACTORY_A', factoryName: '测试企业', user: { id: 43 } });
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRoute: () => ({ query: {} }), useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }));
const Frame = defineComponent({ name: 'DigitalEmployeeFrame', template: '<div><slot /></div>' });
const Conversation = defineComponent({ name: 'DigitalEmployeeConversation', props: ['response'], emits: ['send', 'retry', 'reset'], template: '<div data-conversation>{{ response }}</div>' });
const mountPage = (extra = {}) => shallowMount(Sales, {
  props: { digitalEmployee: true, employeeNavigationPending: false, ...extra },
  global: { stubs: { DigitalEmployeeFrame: Frame, DigitalEmployeeConversation: Conversation, ElTable: defineComponent({ template: '<div />' }), ElTableColumn: defineComponent({ template: '<div />' }) } },
});
// 自由文本提问 = 走 /ai-intents/execute 且没有指定 intentCode（原生面板查询都带 intentCode）
const askedQuestions = () => api.post.mock.calls
  .filter(([path]) => String(path).endsWith('/ai-intents/execute'))
  .filter(([, body]) => !body.intentCode)
  .map(([, body]) => body.userInput);

describe('销售数字专员: 刷新/挂载不替用户发问', () => {
  beforeEach(() => {
    api.post.mockReset();
    api.get.mockReset();
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', formattedText: 'ok', resultData: {} } });
  });

  it('数字专员挂载时不自动发送自由文本问题(刷新不重放)', async () => {
    const wrapper = mountPage(); await flushPromises();
    expect(askedQuestions()).toEqual([]);
    wrapper.unmount();
  });

  it('用户主动提问仍然发送(阳性对照)', async () => {
    const wrapper = mountPage(); await flushPromises();
    wrapper.findComponent(Conversation).vm.$emit('send', '本月销售额');
    await flushPromises();
    expect(askedQuestions()).toEqual(['本月销售额']);
    wrapper.unmount();
  });

  it('非数字专员的普通工作台仍自动跑跟进清单(未被误伤)', async () => {
    const wrapper = mountPage({ digitalEmployee: false }); await flushPromises();
    expect(api.post.mock.calls.some(([, body]) => body.intentCode === 'DAILY_CUSTOMER_FOLLOWUP')).toBe(true);
    wrapper.unmount();
  });
});
