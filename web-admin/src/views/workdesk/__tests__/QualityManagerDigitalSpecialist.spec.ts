import { reactive, defineComponent } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const { api } = vi.hoisted(() => ({
  api: { post: vi.fn(), get: vi.fn() },
}));
const auth = reactive({ factoryId: 'F002', user: { id: 42 }, factoryName: '质量测试企业' });
let QualityManagerWorkdesk: typeof import('../QualityManagerWorkdesk.vue')['default'];
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }));

const FrameStub = defineComponent({ template: '<div data-frame><slot /></div>' });
const ConversationStub = defineComponent({ props: { response: String }, template: '<div data-conversation>{{ response }}<slot name="action" /></div>' });

describe('QualityManager digital specialist', () => {
  beforeAll(async () => {
    QualityManagerWorkdesk = (await import('../QualityManagerWorkdesk.vue')).default;
  });
  beforeEach(() => {
    api.post.mockReset();
    api.get.mockReset();
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', formattedText: 'HACCP 查询结果', resultData: {} } });
    auth.factoryId = 'F002';
    auth.user = { id: 42 };
  });

  it('mounts without an automatic query and sends free questions through READ mode', async () => {
    const wrapper = mount(QualityManagerWorkdesk, {
      props: { digitalEmployee: true, employeeNavigationPending: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();

    wrapper.findComponent(ConversationStub).vm.$emit('send', '追溯 B-2026 的影响范围');
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false });
    wrapper.findComponent(ConversationStub).vm.$emit('send', '追溯 B-2026 的影响范围');
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F002/ai-intents/execute', expect.objectContaining({
      userInput: '追溯 B-2026 的影响范围', mode: 'READ', sessionId: expect.any(String),
    }));
  });

  it('clears identity-bound recall state and never falls back to F006', async () => {
    const wrapper = mount(QualityManagerWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    wrapper.findComponent(ConversationStub).vm.$emit('send', '查询批次');
    await flushPromises();
    api.post.mockClear();
    auth.factoryId = 'F003';
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    expect(api.post.mock.calls.some(([path]) => String(path).includes('F006'))).toBe(false);
  });

  it('does not apply a delayed free-question response after identity change', async () => {
    const wrapper = mount(QualityManagerWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    let resolveOld!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    wrapper.findComponent(ConversationStub).vm.$emit('send', '旧企业问题');
    await flushPromises();
    auth.factoryId = 'F009';
    await flushPromises();
    resolveOld({ data: { status: 'SUCCESS', formattedText: '旧企业结果', resultData: {} } });
    await flushPromises();
    expect(wrapper.find('[data-conversation]').text()).not.toContain('旧企业结果');
  });

  it('keeps recall freeze/notify/close on their native preview path', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../QualityManagerWorkdesk.vue'), 'utf8');
    expect(source).toContain('function previewFreeze');
    expect(source).toContain('function previewNotify');
    expect(source).toContain('body.preview = true');
  });

  it('native inventory preview keeps the original WRITE intent payload', async () => {
    const wrapper = mount(QualityManagerWorkdesk, {
      props: { digitalEmployee: false, employeeCanWrite: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    const vm = wrapper.vm as unknown as { recallContext: { batchNumber: string }; freezeDialog: { form: { reason: string } }; previewFreeze(): Promise<void> };
    vm.recallContext.batchNumber = 'B-1';
    vm.freezeDialog.form.reason = '测试冻结';
    api.post.mockResolvedValueOnce({ data: { status: 'SUCCESS', resultData: { status: 'PREVIEW', canDo: true } } });
    await vm.previewFreeze();
    expect(api.post).toHaveBeenCalledWith('/F002/ai-intents/execute', expect.objectContaining({
      intentCode: 'INVENTORY_FREEZE', preview: true, parameters: expect.any(Object),
    }));
  });

  it('blocks native preview while navigation is pending and write access is absent', async () => {
    const wrapper = mount(QualityManagerWorkdesk, {
      props: { digitalEmployee: true, employeeCanWrite: false, employeeNavigationPending: false },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    const vm = wrapper.vm as unknown as { recallContext: { batchNumber: string }; freezeDialog: { form: { reason: string } }; previewFreeze(): Promise<void> };
    vm.recallContext.batchNumber = 'B-OLD';
    vm.freezeDialog.form.reason = '旧租户冻结';
    await vm.previewFreeze();
    await wrapper.setProps({ employeeCanWrite: true, employeeNavigationPending: true });
    await vm.previewFreeze();
    expect(api.post.mock.calls.some(([, body]) => body?.intentCode === 'INVENTORY_FREEZE')).toBe(false);
  });
});
