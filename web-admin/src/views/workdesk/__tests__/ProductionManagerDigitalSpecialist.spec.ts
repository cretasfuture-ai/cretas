import { reactive, defineComponent } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const { api } = vi.hoisted(() => ({
  api: { post: vi.fn(), get: vi.fn() },
}));
const auth = reactive({ factoryId: 'F003', user: { id: 43 }, factoryName: '生产测试企业' });
let ProductionManagerWorkdesk: typeof import('../ProductionManagerWorkdesk.vue')['default'];
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }));

const FrameStub = defineComponent({ template: '<div data-frame><slot /></div>' });
const ConversationStub = defineComponent({ props: { response: String }, template: '<div data-conversation>{{ response }}<slot name="action" /></div>' });

describe('ProductionManager digital specialist', () => {
  beforeAll(async () => {
    ProductionManagerWorkdesk = (await import('../ProductionManagerWorkdesk.vue')).default;
  });
  beforeEach(() => {
    api.post.mockReset();
    api.get.mockReset();
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', formattedText: '生产需求', resultData: {} } });
    auth.factoryId = 'F003';
    auth.user = { id: 43 };
  });

  it('waits for tenant navigation, then auto-queries with READ and no F006 fallback', async () => {
    const wrapper = mount(ProductionManagerWorkdesk, {
      props: { digitalEmployee: true, employeeNavigationPending: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F003/ai-intents/execute', expect.objectContaining({
      mode: 'READ', intentCode: 'PRODUCTION_DEMAND_QUERY',
    }));
    expect(api.post.mock.calls.some(([path]) => String(path).includes('F006'))).toBe(false);
  });

  it('moves a changed identity to a fresh read scope instead of keeping the prior demand list', async () => {
    const wrapper = mount(ProductionManagerWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    api.post.mockClear();
    auth.factoryId = 'F004';
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F004/ai-intents/execute', expect.any(Object));
  });

  it('does not apply a delayed response from the previous identity', async () => {
    let resolveOld!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = mount(ProductionManagerWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    api.post.mockResolvedValueOnce({ data: { status: 'SUCCESS', formattedText: '新生产结果', resultData: {} } });
    auth.factoryId = 'F009';
    await flushPromises();
    resolveOld({ data: { status: 'SUCCESS', formattedText: '旧生产结果', resultData: {} } });
    await flushPromises();
    expect(wrapper.find('[data-conversation]').text()).toContain('新生产结果');
    expect(wrapper.find('[data-conversation]').text()).not.toContain('旧生产结果');
  });

  it('keeps native batch creation behind its existing preview and confirm functions', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../ProductionManagerWorkdesk.vue'), 'utf8');
    expect(source).toContain('function previewBatchCreate');
    expect(source).toContain('function executeBatchCreate');
    expect(source).toContain('body.preview = true');
  });

  it('native preview keeps the original WRITE intent payload', async () => {
    api.post.mockImplementation((_path, body) => {
      if (body.intentCode === 'PRODUCTION_DEMAND_QUERY') {
        return Promise.resolve({ data: {
          status: 'SUCCESS',
          resultData: { data: { products: [{
            productTypeId: 'P-1', productName: '测试产品', unit: 'kg', totalDemand: 10,
            inProgressQuantity: 0, netShortage: 10, recommendedQuantity: 5,
            recommendedLine: 'DEDICATED_LINE_A', orderCount: 1,
          }] } },
        } });
      }
      return Promise.resolve({ data: { status: 'SUCCESS', resultData: { status: 'PREVIEW', canDo: true } } });
    });
    const wrapper = mount(ProductionManagerWorkdesk, {
      props: { digitalEmployee: false, employeeCanWrite: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    const start = wrapper.findAll('button').find(button => button.text().includes('一键起产'));
    expect(start).toBeDefined();
    await start!.trigger('click');
    await flushPromises();
    const preview = wrapper.findAll('button').find(button => button.text().trim() === '预览');
    expect(preview).toBeDefined();
    await preview!.trigger('click');
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F003/ai-intents/execute', expect.objectContaining({
      intentCode: 'PRODUCTION_BATCH_CREATE', preview: true, parameters: expect.any(Object),
    }));
  });

  it('blocks native preview while navigation is pending and write access is absent', async () => {
    const wrapper = mount(ProductionManagerWorkdesk, {
      props: { digitalEmployee: true, employeeCanWrite: false, employeeNavigationPending: false },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    const vm = wrapper.vm as unknown as { batchDialog: { form: { quantity: number; scheduledDate: string }; maxAllowed: number }; previewBatchCreate(): Promise<void> };
    vm.batchDialog.form.quantity = 1;
    vm.batchDialog.form.scheduledDate = '2026-11-16';
    vm.batchDialog.maxAllowed = 10;
    await vm.previewBatchCreate();
    await wrapper.setProps({ employeeCanWrite: true, employeeNavigationPending: true });
    await vm.previewBatchCreate();
    expect(api.post.mock.calls.some(([, body]) => body?.intentCode === 'PRODUCTION_BATCH_CREATE')).toBe(false);
  });
});
