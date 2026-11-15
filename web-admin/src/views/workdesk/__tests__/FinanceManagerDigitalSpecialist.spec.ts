import { reactive, defineComponent } from 'vue';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { flushPromises, mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { beforeAll, beforeEach, describe, expect, it, vi } from 'vitest';

const { api } = vi.hoisted(() => ({
  api: { post: vi.fn(), get: vi.fn() },
}));
const auth = reactive({ factoryId: 'F001', user: { id: 41 }, factoryName: '测试企业' });
let FinanceManagerWorkdesk: typeof import('../FinanceManagerWorkdesk.vue')['default'];
vi.mock('@/api/request', () => ({ default: api }));
vi.mock('@/store/modules/auth', () => ({ useAuthStore: () => auth }));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: vi.fn() }), useRoute: () => ({ query: {} }) }));

const FrameStub = defineComponent({ template: '<div data-frame><slot /></div>' });
const ConversationStub = defineComponent({ props: { response: String }, template: '<div data-conversation>{{ response }}<slot name="action" /></div>' });

function open() {
  return mount(FinanceManagerWorkdesk, {
    props: { digitalEmployee: true, employeeNavigationPending: true },
    global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
  });
}

describe('FinanceManager digital specialist', () => {
  it('renders the pure income tool facts and warnings rather than a contradictory generated empty-data summary', async () => {
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', intentCode: 'INCOME_STATEMENT_QUERY', formattedText: '本月暂无利润表数据，各指标均为空', resultData: {
      message: '2026-11 利润表: 营业收入 ¥0.00 / 净利润 ¥-5.91',
      data: { startYear: 2026, startMonth: 11, endYear: 2026, endMonth: 11, netProfit: -5.91, metricWarnings: ['期间营业收入为零或缺失，毛利率不可计算'] },
    } } });
    const wrapper = open(); await wrapper.setProps({ employeeNavigationPending: false }); await flushPromises();
    const text = wrapper.findComponent(ConversationStub).text();
    expect(text).toContain('实际期间：2026-11 至 2026-11');
    expect(text).toContain('净利润 ¥-5.91');
    expect(text).toContain('毛利率不可计算');
    expect(text).not.toContain('各指标均为空');
    expect(wrapper.find('.metrics-grid').exists()).toBe(false);
    expect(api.post.mock.calls.every(([, body]) => body.intentCode !== 'MONTHLY_FINANCIAL_CLOSE')).toBe(true);
    wrapper.unmount();
  });
  beforeAll(async () => {
    FinanceManagerWorkdesk = (await import('../FinanceManagerWorkdesk.vue')).default;
  });
  beforeEach(() => {
    api.post.mockReset();
    api.get.mockReset();
    api.post.mockResolvedValue({ data: { status: 'SUCCESS', formattedText: '本月经营摘要', resultData: {} } });
    api.get.mockResolvedValue({ data: { items: [], count: 0 } });
    auth.factoryId = 'F001';
    auth.user = { id: 41 };
  });

  it('mounts in pending navigation without falling back to F006, then uses READ intent after identity is ready', async () => {
    const wrapper = open();
    await flushPromises();
    expect(api.post).not.toHaveBeenCalled();

    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F001/ai-intents/execute', expect.objectContaining({
      userInput: '查询本月利润表', mode: 'READ', intentCode: 'INCOME_STATEMENT_QUERY',
    }));
    expect(wrapper.find('[data-conversation]').exists()).toBe(true);
  });

  it('resets the previous identity and never reuses the old tenant path', async () => {
    const wrapper = open();
    await wrapper.setProps({ employeeNavigationPending: false });
    await flushPromises();
    api.post.mockClear();
    auth.factoryId = 'F002';
    await flushPromises();
    expect(api.post).toHaveBeenCalledWith('/F002/ai-intents/execute', expect.any(Object));
    expect(api.post.mock.calls.some(([path]) => String(path).includes('F006'))).toBe(false);
  });

  it('does not apply a delayed response from the previous identity', async () => {
    let resolveOld!: (value: unknown) => void;
    api.post.mockImplementationOnce(() => new Promise(resolve => { resolveOld = resolve; }));
    const wrapper = mount(FinanceManagerWorkdesk, {
      props: { digitalEmployee: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    auth.factoryId = 'F009';
    api.post.mockResolvedValueOnce({ data: { status: 'SUCCESS', formattedText: '新企业结果', resultData: {} } });
    await flushPromises();
    resolveOld({ data: { status: 'SUCCESS', formattedText: '旧企业结果', resultData: {} } });
    await flushPromises();
    expect(wrapper.find('[data-conversation]').text()).toContain('新企业结果');
    expect(wrapper.find('[data-conversation]').text()).not.toContain('旧企业结果');
  });

  it('keeps the native approval flow separate from the READ specialist query', () => {
    const source = readFileSync(resolve(import.meta.dirname, '../FinanceManagerWorkdesk.vue'), 'utf8');
    expect(source).toContain('function submitApproval');
    expect(source).toContain('callIntentExecuteWithParams');
  });

  it('native approval keeps the original WRITE intent payload', async () => {
    api.post.mockImplementation((_path, body) => {
      if (body.intentCode === 'APPROVAL_PENDING_QUERY') {
        return Promise.resolve({ data: {
          status: 'SUCCESS',
          resultData: { data: { count: 1, items: [{
            instanceId: 'WF-1', moduleCode: 'PURCHASE', businessEntityId: 'PO-1',
            businessSummary: '测试采购单', currentNodeLabel: '财务审批',
          }] } },
        } });
      }
      return Promise.resolve({ data: { status: 'SUCCESS', formattedText: '月度摘要', resultData: {} } });
    });
    const wrapper = mount(FinanceManagerWorkdesk, {
      props: { digitalEmployee: false, employeeCanWrite: true },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    await flushPromises();
    await wrapper.get('[data-testid="approval-approve-btn"]').trigger('click');
    const vm = wrapper.vm as unknown as { approvalForm: { reasonOption: string; action: string }; approvalDialogTarget: Record<string, string> | null; submitApproval(): Promise<void> };
    vm.approvalForm.reasonOption = '审核无异常';
    await vm.submitApproval();
    expect(api.post).toHaveBeenCalledWith('/F001/ai-intents/execute', expect.objectContaining({
      intentCode: 'APPROVAL_ACTION_EXECUTE',
      context: expect.objectContaining({ instanceId: 'WF-1', action: 'APPROVE' }),
    }));
  });

  it('blocks approval handler when navigation is pending even with no write grant', async () => {
    const wrapper = mount(FinanceManagerWorkdesk, {
      props: { digitalEmployee: true, employeeCanWrite: false, employeeNavigationPending: false },
      global: { plugins: [ElementPlus], stubs: { DigitalEmployeeFrame: FrameStub, DigitalEmployeeConversation: ConversationStub } },
    });
    const vm = wrapper.vm as unknown as { approvalForm: { reasonOption: string; action: string }; approvalDialogTarget: Record<string, string> | null; submitApproval(): Promise<void> };
    vm.approvalDialogTarget = { instanceId: 'WF-OLD', moduleCode: 'PURCHASE', businessEntityId: 'PO-OLD', businessSummary: '旧单据' };
    vm.approvalForm.action = 'APPROVE';
    vm.approvalForm.reasonOption = '审核无异常';
    await vm.submitApproval();
    await wrapper.setProps({ employeeCanWrite: true, employeeNavigationPending: true });
    await vm.submitApproval();
    expect(api.post.mock.calls.some(([, body]) => body?.intentCode === 'APPROVAL_ACTION_EXECUTE')).toBe(false);
  });
});
