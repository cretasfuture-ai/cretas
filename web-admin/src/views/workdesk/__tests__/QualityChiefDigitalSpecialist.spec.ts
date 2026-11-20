import { reactive, defineComponent } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const { api } = vi.hoisted(() => ({
  api: { post: vi.fn(), get: vi.fn() },
}));
const auth = reactive({ factoryId: 'F004', user: { id: 44 }, factoryName: '质检测试企业' });
let QualityChiefWorkdesk: typeof import('../QualityChiefWorkdesk.vue')['default'];
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }));

const FrameStub = defineComponent({ template: '<div data-frame><slot /></div>' });
const ConversationStub = defineComponent({ props: { response: String }, template: '<div data-conversation>{{ response }}<slot name="action" /></div>' });

describe('QualityChief digital specialist', () => {
  beforeAll(async () => {
    QualityChiefWorkdesk = (await import('../QualityChiefWorkdesk.vue')).default;
  });
  beforeEach(() => {
    api.post.mockReset();
    api.get.mockReset();
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', formattedText: '待放行批次', resultData: {} } });
    auth.factoryId = 'F004';
    auth.user = { id: 44 };
  });

  it('waits for navigation and then performs the pending-batch READ query in the real tenant', async () => {
    const wrapper = mount(QualityChiefWorkdesk, {
      props: { digitalEmployee: true, employeeNavigationPending: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F004/ai-intents/execute', expect.objectContaining({
      mode: 'READ', intentCode: 'QUALITY_CHIEF_WORKDESK',
    }));
  });

  it('uses a new tenant scope after identity change and never reuses F006', async () => {
    const wrapper = mount(QualityChiefWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    api.post.mockClear();
    auth.factoryId = 'F005';
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F005/ai-intents/execute', expect.any(Object));
    expect(api.post.mock.calls.some(([path]) => String(path).includes('F006'))).toBe(false);
  });

  it('does not apply a delayed response from the previous identity', async () => {
    let resolveOld!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = mount(QualityChiefWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    api.post.mockResolvedValueOnce({ data: { status: 'SUCCESS', formattedText: '新质检结果', resultData: {} } });
    auth.factoryId = 'F009';
    await flushPromises();
    resolveOld({ data: { status: 'SUCCESS', formattedText: '旧质检结果', resultData: {} } });
    await flushPromises();
    expect(wrapper.find('[data-conversation]').text()).toContain('新质检结果');
    expect(wrapper.find('[data-conversation]').text()).not.toContain('旧质检结果');
  });

  it('keeps release and return actions on their existing preview/execute flow', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../QualityChiefWorkdesk.vue'), 'utf8');
    expect(source).toContain('function previewRelease');
    expect(source).toContain('function executeRelease');
    expect(source).toContain('body.preview = true');
  });

  it('native release preview keeps the original WRITE intent payload', async () => {
    api.post.mockImplementation((_path, body) => {
      if (body.intentCode === 'QUALITY_CHIEF_WORKDESK') {
        return Promise.resolve({ data: {
          status: 'SUCCESS',
          resultData: { data: { pendingBatches: [{
            batchNumber: 'B-1', materialName: '测试物料', qualityCheck: true,
            haccp: true, additive: true, customerStandard: true,
          }] } },
        } });
      }
      return Promise.resolve({ data: { status: 'SUCCESS', resultData: { status: 'PREVIEW', canDo: true } } });
    });
    const wrapper = mount(QualityChiefWorkdesk, {
      props: { digitalEmployee: false, employeeCanWrite: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    const release = wrapper.findAll('button').find(button => button.text().includes('一键放行'));
    expect(release).toBeDefined();
    await release!.trigger('click');
    await flushPromises();
    const preview = wrapper.findAll('button').find(button => button.text().trim() === '预览');
    expect(preview).toBeDefined();
    await preview!.trigger('click');
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F004/ai-intents/execute', expect.objectContaining({
      intentCode: 'RELEASE_DECISION', preview: true, parameters: expect.any(Object),
    }));
  });

  it('blocks native release confirmation while navigation is pending and write access is absent', async () => {
    const wrapper = mount(QualityChiefWorkdesk, {
      props: { digitalEmployee: true, employeeCanWrite: false, employeeNavigationPending: false },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    const vm = wrapper.vm as unknown as { releaseDialog: { batchNumber: string; canSubmit: boolean }; executeRelease(): Promise<void> };
    vm.releaseDialog.batchNumber = 'B-OLD';
    vm.releaseDialog.canSubmit = true;
    await vm.executeRelease();
    await wrapper.setProps({ employeeCanWrite: true, employeeNavigationPending: true });
    await vm.executeRelease();
    expect(api.post.mock.calls.some(([, body]) => body?.intentCode === 'RELEASE_DECISION')).toBe(false);
  });
});
