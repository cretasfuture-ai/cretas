import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { reactive, nextTick } from 'vue';
import SectionInsight from '../SectionInsight.vue';
import type { SectionInsightReport } from '@/api/smartbi/blacklake';
import { askBlacklakeSection } from '@/api/smartbi/blacklake';

const authStoreHolder = vi.hoisted(() => ({
  current: null as { factoryId: string; currentRole: string; user: { id: number } | null } | null,
}));

vi.mock('@/api/smartbi/blacklake', () => ({
  askBlacklakeSection: vi.fn(),
}));
vi.mock('@/store/modules/auth', () => ({
  useAuthStore: () => authStoreHolder.current,
}));

const askMock = vi.mocked(askBlacklakeSection);

function makeReport(overrides: Partial<SectionInsightReport> = {}): SectionInsightReport {
  return {
    sectionId: 'material',
    title: '原料净消耗',
    summary: '已关联工单的原料净消耗快照。',
    observations: ['有值金额与零值来源需要区分。'],
    calculation: ['材料净额按领料减退料计算。'],
    hypotheses: ['零值可能需要回到来源单据核对。'],
    limitations: ['不跨单位混加。'],
    nextSteps: ['先核对来源单据。'],
    suggestedQuestions: ['哪些原料最值得优先核对？'],
    scope: { runId: 'run-1', projectId: 16 },
    evidenceRefs: ['materials:run-1'],
    ...overrides,
  };
}

describe('SectionInsight', () => {
  function setAuth(factoryId = 'F001', currentRole = 'viewer', userId = 101) {
    authStoreHolder.current = reactive({ factoryId, currentRole, user: { id: userId } });
  }

  beforeEach(() => {
    setAuth();
    askMock.mockReset();
  });

  it('shows the summary, evidence boundary and suggested question without inventing data', async () => {
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    expect(wrapper.text()).toContain('已关联工单的原料净消耗快照。');
    expect(wrapper.text()).toContain('有值金额与零值来源需要区分。');
    expect(wrapper.find('.scope-details').element.open).toBe(false);
    await wrapper.get('.suggested-questions button').trigger('click');
    expect(wrapper.get('input').element.value).toBe('哪些原料最值得优先核对？');
    expect(wrapper.text()).not.toContain('完整制造成本');
  });

  it('sends scoped history and keeps only the last six messages', async () => {
    askMock.mockResolvedValue({ answer: '基于本段证据的回答。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });
    const input = wrapper.get('input');

    for (const value of ['问题一', '问题二', '问题三', '问题四']) {
      await input.setValue(value);
      await wrapper.get('form').trigger('submit');
      await flushPromises();
    }

    expect(askMock).toHaveBeenCalledTimes(4);
    expect(askMock.mock.calls[0][0]).toEqual({ section_id: 'material', question: '问题一', run_id: 'run-1', history: [] });
    expect(askMock.mock.calls[3][0].history).toHaveLength(6);
    expect(wrapper.findAll('.message')).toHaveLength(6);
  });

  it('drops a late response after the report run changes', async () => {
    let resolveRequest: ((value: { answer: string; sectionId: string; runId: string; engine: string }) => void) | undefined;
    askMock.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    await wrapper.get('input').setValue('旧运行的问题');
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({ report: makeReport({ scope: { runId: 'run-2', projectId: 16 } }) });
    resolveRequest?.({ answer: '不应显示的旧回答。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    await flushPromises();

    expect(wrapper.text()).not.toContain('不应显示的旧回答。');
    expect(wrapper.get('input').element.value).toBe('');
  });

  it('resets the conversation and drops a late response after auth factory or role changes', async () => {
    let resolveRequest: ((value: { answer: string; sectionId: string; runId: string; engine: string }) => void) | undefined;
    askMock.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    await wrapper.get('input').setValue('原身份的问题');
    await wrapper.get('form').trigger('submit');
    authStoreHolder.current!.factoryId = 'F002';
    authStoreHolder.current!.currentRole = 'finance_manager';
    await nextTick();
    resolveRequest?.({ answer: '不应显示的旧身份回答。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    await flushPromises();

    expect(wrapper.text()).not.toContain('不应显示的旧身份回答。');
    expect(wrapper.findAll('.message')).toHaveLength(0);
    expect(wrapper.get('input').element.value).toBe('');
  });

  it('rejects questions longer than 500 characters', async () => {
    askMock.mockReset();
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    await wrapper.get('input').setValue('问'.repeat(501));
    await wrapper.get('form').trigger('submit');

    expect(askMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('问题最多 500 个字符');
  });
});
