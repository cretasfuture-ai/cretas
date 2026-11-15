import { defineComponent, reactive } from 'vue';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import AiValue from '../AiValueSummary.vue';

const api = vi.hoisted(() => ({ fetch: vi.fn() }));
const auth = reactive({ factoryId: '', factoryName: '', user: { id: 43 } });
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('@/api/aiValueSummary', () => ({ fetchAiValueSummary: api.fetch }));
const Frame = defineComponent({ name: 'DigitalEmployeeFrame', props: ['role', 'enabled'], template: '<div><slot /></div>' });
const summary = (calls: number) => ({ data: { windowDays: 30, aiCalls: calls, alertsTotal: 0, alertsByStatus: {}, alertDetails: [], totalTokens: 0, costInYuan: null, costUnavailableReason: '未配置' } });
function mountPage() { return shallowMount(AiValue, { global: { stubs: { DigitalEmployeeFrame: Frame, ElTable: defineComponent({ template: '<div />' }), ElTableColumn: defineComponent({ template: '<div />' }) } } }); }

describe('AI value digital specialist', () => {
  beforeEach(() => { auth.factoryId = ''; auth.user = { id: 43 }; api.fetch.mockReset().mockResolvedValue(summary(2)); });
  it('shows the unified specialist and requests no fabricated factory for a platform account', async () => {
    const wrapper = mountPage(); await flushPromises();
    expect(wrapper.findComponent(Frame).props()).toMatchObject({ role: 'ai-value', enabled: true });
    expect(api.fetch).not.toHaveBeenCalled();
    expect((wrapper.vm as unknown as { loadError: string }).loadError).toContain('没有工厂上下文');
    wrapper.unmount();
  });
  it('does not query while actor or navigation identity is unavailable', async () => {
    auth.factoryId = 'A'; auth.user = { id: 0 };
    const wrapper = mountPage(); await flushPromises();
    expect(api.fetch).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: true });
    auth.user = { id: 43 }; await flushPromises();
    expect(api.fetch).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false }); await flushPromises();
    expect(api.fetch).toHaveBeenLastCalledWith('A', 30);
    wrapper.unmount();
  });
  it('clears the previous summary and ignores delayed success and error after identity changes', async () => {
    auth.factoryId = 'A';
    let finish!: (value: unknown) => void;
    api.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const wrapper = mountPage(); await flushPromises();
    auth.factoryId = 'B'; auth.user = { id: 44 }; await flushPromises();
    expect(api.fetch).toHaveBeenLastCalledWith('B', 30);
    finish(summary(999)); await flushPromises();
    expect((wrapper.vm as unknown as { summary: { aiCalls: number } }).summary.aiCalls).toBe(2);
    auth.factoryId = ''; await flushPromises();
    expect((wrapper.vm as unknown as { summary: unknown }).summary).toBeNull();
    wrapper.unmount();
  });
});
