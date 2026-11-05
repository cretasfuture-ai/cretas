import { flushPromises, mount } from '@vue/test-utils';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { defineComponent, h, nextTick, reactive } from 'vue';
import SectionInsight from '../PrototypeSectionInsight.vue';
import type { CostDataAlert, SectionInsightReport } from '@/api/smartbi/blacklake';
import { askBlacklakeSection } from '@/api/smartbi/blacklake';

vi.mock('@nuxt/ui/components/Button.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    props: { disabled: Boolean },
    setup(props, { attrs, slots }) {
      return () => h('button', { ...attrs, disabled: props.disabled }, slots.default?.());
    },
  }),
}));
vi.mock('@nuxt/ui/components/Badge.vue', () => ({
  default: defineComponent({
    inheritAttrs: false,
    setup(_, { attrs, slots }) {
      return () => h('span', attrs, slots.default?.());
    },
  }),
}));

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
    observations: ['首要观察：有值金额与零值来源需要区分。', '第二条观察：仍需按来源单据核对。', '第三条观察：单位不能混加。'],
    calculation: ['材料净额按领料减退料计算。'],
    hypotheses: ['零值可能需要回到来源单据核对。'],
    limitations: ['不跨单位混加。'],
    nextSteps: ['先核对来源单据。', '再确认单位口径。'],
    suggestedQuestions: ['哪些原料最值得优先核对？'],
    scope: { runId: 'run-1', projectId: 16 },
    evidenceRefs: ['materials:run-1'],
    ...overrides,
  };
}

function makeAlert(id = 'alert-1'): CostDataAlert {
  return {
    id, type: 'source_zero_cost', family: 'data_quality', severity: 'warning', blocksUnitCost: true,
    title: '来源金额为零', message: '来源金额需要核对。', owner: '财务', action: '回到来源单据核验', fields: ['recognizedCost'],
    entityRefs: [], evidence: {}, detectedBy: 'test', ruleVersion: 'v1', scope: 'F001', runId: 'run-1', status: 'open',
  };
}

describe('SectionInsight', () => {
  it('labels model interpretation and shows impact without exposing evidence aliases', () => {
    const report = makeReport({ localAnalysis: {
      sectionId: 'material', title: '材料解读', evidenceRefs: ['F017'], whatItMeans: '材料记录还需核对',
      whyItMatters: '只看包装会低估成品成本', hypotheses: ['可能是包装工序'], nextStep: '请仓库核对主料', owner: '仓库',
      cannotConclude: '不能认定成品便宜', followupQuestion: '还缺哪些主料记录？', considerations: ['PACKAGING_ONLY'],
      statistics: [{ id: 'F017', text: '合成产品仅见包装记录' }],
      indicator: { label: '材料', indicator: '单位材料成本', formula: '材料净额除良品', howToRead: '先核对记录完整性' },
    } });
    const wrapper = mount(SectionInsight, { props: { report } });
    expect(wrapper.text()).toContain('原因与判断依据');
    expect(wrapper.text()).toContain('只看包装会低估成品成本');
    expect(wrapper.text()).not.toContain('F017');
  });
  function setAuth(factoryId = 'F001', currentRole = 'viewer', userId = 101) {
    authStoreHolder.current = reactive({ factoryId, currentRole, user: { id: userId } });
  }

  beforeEach(() => {
    setAuth();
    askMock.mockReset();
  });

  it('shows a compact conclusion strip and keeps detail/chat closed by default', async () => {
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    expect(wrapper.text()).toContain('已关联工单的原料净消耗快照。');
    expect(wrapper.text()).toContain('首要观察：有值金额与零值来源需要区分。');
    expect(wrapper.text()).toContain('先核对来源单据。');
    expect(wrapper.text()).toContain('暂未提供可读来源说明。');
    expect(wrapper.find('.insight-strip').exists()).toBe(true);
    expect(wrapper.find('.detail-list details').element.open).toBe(false);
    expect(wrapper.find('.followup-panel').exists()).toBe(false);
    expect(wrapper.get('[aria-expanded="false"]').text()).toContain('继续追问');

    await wrapper.get('[aria-expanded="false"]').trigger('click');
    expect(wrapper.find('.followup-panel').exists()).toBe(true);
    await flushPromises();
    expect(wrapper.get('input').attributes('aria-invalid')).toBe('false');
    await wrapper.get('.insight-details--inline summary').trigger('click');
    expect(wrapper.text()).toContain('第二条观察：仍需按来源单据核对。');
  });

  it('sends scoped history and keeps only the last six messages', async () => {
    askMock.mockResolvedValue({ answer: '基于本段证据的回答。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });
    await wrapper.get('[aria-expanded="false"]').trigger('click');
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

  it('binds an alert context, includes its id, opens on focus, and emits clear-focus', async () => {
    askMock.mockResolvedValue({ answer: '已核对。', sectionId: 'material', runId: 'run-1', engine: 'test', alertId: 'alert-1' });
    const wrapper = mount(SectionInsight, { props: { report: makeReport(), focusAlert: makeAlert() } });

    expect(wrapper.text()).toContain('来源金额为零');
    expect(wrapper.text()).toContain('责任角色：财务');
    expect(wrapper.text()).toContain('动作：回到来源单据核验');
    expect(wrapper.find('.followup-panel').exists()).toBe(true);
    await wrapper.get('input').setValue('围绕这个告警核对');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(askMock.mock.calls[0][0]).toMatchObject({ alert_id: 'alert-1' });
    expect(wrapper.findAll('.message')).toHaveLength(2);

    const clearButton = wrapper.findAll('button').find(button => button.text() === '清除上下文');
    expect(clearButton).toBeDefined();
    await clearButton!.trigger('click');
    expect(wrapper.emitted('clear-focus')).toHaveLength(1);
  });

  it('opens only this section for an explicit open request', async () => {
    const initiallyClosed = mount(SectionInsight, { props: { report: makeReport(), openRequest: 0 } });
    expect(initiallyClosed.find('.followup-panel').exists()).toBe(false);

    const wrapper = mount(SectionInsight, { props: { report: makeReport(), openRequest: 1 } });
    expect(wrapper.find('.followup-panel').exists()).toBe(true);
    await wrapper.setProps({ openRequest: 2 });
    await nextTick();
    expect(wrapper.find('[aria-expanded="true"]').exists()).toBe(true);
  });

  it('drops a late response after the report run or focused alert changes', async () => {
    let resolveRequest: ((value: { answer: string; sectionId: string; runId: string; engine: string; alertId: string }) => void) | undefined;
    askMock.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    const wrapper = mount(SectionInsight, { props: { report: makeReport(), focusAlert: makeAlert() } });

    await wrapper.get('input').setValue('旧运行的问题');
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({ report: makeReport({ scope: { runId: 'run-2', projectId: 16 } }), focusAlert: makeAlert('alert-2') });
    resolveRequest?.({ answer: '不应显示的旧回答。', sectionId: 'material', runId: 'run-1', engine: 'test', alertId: 'alert-1' });
    await flushPromises();

    expect(wrapper.text()).not.toContain('不应显示的旧回答。');
    expect(wrapper.get('input').element.value).toBe('');
  });

  it('resets the conversation and drops a late response after auth factory or role changes', async () => {
    let resolveRequest: ((value: { answer: string; sectionId: string; runId: string; engine: string }) => void) | undefined;
    askMock.mockReturnValueOnce(new Promise(resolve => { resolveRequest = resolve; }));
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });

    await wrapper.get('[aria-expanded="false"]').trigger('click');
    await wrapper.get('input').setValue('原身份的问题');
    await wrapper.get('form').trigger('submit');
    authStoreHolder.current!.factoryId = 'F002';
    authStoreHolder.current!.currentRole = 'finance_manager';
    await nextTick();
    resolveRequest?.({ answer: '不应显示的旧身份回答。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    await flushPromises();

    expect(wrapper.text()).not.toContain('不应显示的旧身份回答。');
    expect(wrapper.findAll('.message')).toHaveLength(0);
    expect(wrapper.get('[aria-expanded="false"]').exists()).toBe(true);
  });

  it('rejects a response whose alert identity does not match the current focus', async () => {
    askMock.mockResolvedValue({ answer: '错告警回答。', sectionId: 'material', runId: 'run-1', engine: 'test', alertId: 'other-alert' });
    const wrapper = mount(SectionInsight, { props: { report: makeReport(), focusAlert: makeAlert() } });
    await wrapper.get('input').setValue('核对');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(wrapper.findAll('.message')).toHaveLength(0);
  });

  it('drops a pending answer when facts or rule definition change within the same run and alert ID',async()=>{
    let finish: ((value:{answer:string;sectionId:string;runId:string;alertId:string;engine:string})=>void)|undefined;
    askMock.mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));
    const wrapper=mount(SectionInsight,{props:{report:makeReport(),focusAlert:makeAlert()}});
    await wrapper.get('input').setValue('旧规则问题');await wrapper.get('form').trigger('submit');
    await wrapper.setProps({report:makeReport({summary:'同一归档中的证据已更新'}),focusAlert:{...makeAlert(),ruleVersion:'new-rule'}});
    finish?.({answer:'旧规则回答',sectionId:'material',runId:'run-1',alertId:'alert-1',engine:'test'});await flushPromises();
    expect(wrapper.text()).not.toContain('旧规则回答');expect(wrapper.findAll('.message')).toHaveLength(0);
  });

  it('uses readable scope and sources, hides opaque evidence IDs, and locates related orders', async () => {
    const wrapper = mount(SectionInsight, { props: { report: makeReport({
      scopeDescription: '本次快照中的已关联工单与来源领退料。',
      evidenceSources: [{ label: '工单用料记录', description: '来自本次快照的领料与退料明细。' }],
      evidenceRefs: ['materials:run-1:opaque-internal-id'],
      relatedOrders: [{ projectId: 16, projectCode: 'MO-0016', product: '香酥鸡', reasons: ['来源金额为零，需要回到原单核对。'] }],
    }) } });

    expect(wrapper.text()).toContain('分析与建议');
    expect(wrapper.text()).toContain('本次快照中的已关联工单与来源领退料');
    expect(wrapper.text()).toContain('工单用料记录');
    expect(wrapper.text()).not.toContain('materials:run-1:opaque-internal-id');
    const locateButton = wrapper.findAll('button').find(button => button.text() === '核对工单');
    expect(locateButton).toBeDefined();
    await locateButton!.trigger('click');
    expect(wrapper.emitted('locate-order')).toEqual([[16]]);
    await wrapper.get('.related-order .insight-details--nested summary').trigger('click');
    expect(wrapper.text()).toContain('来源金额为零，需要回到原单核对。');
  });

  it('formats assistant terminology without mutating the user question history', async () => {
    askMock.mockResolvedValue({ answer: '请核对领退料与工单关联三元组，成本 1.2345 元。', sectionId: 'material', runId: 'run-1', engine: 'test' });
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });
    await wrapper.get('[aria-expanded="false"]').trigger('click');
    await wrapper.get('input').setValue('估值 1.2345');
    await wrapper.get('form').trigger('submit');
    await flushPromises();
    expect(wrapper.text()).toContain('估值 1.2345');
    expect(wrapper.text()).toContain('核对领退料的工单编码、业务单类型和关联编号，成本 1.23 元。');
    expect(wrapper.text()).not.toContain('领退料与工单关联三元组');
  });

  it('prefills the selected diagnostic and discards an answer from the previous diagnostic',async()=>{
    let finish:((value:{answer:string;sectionId:string;runId:string;engine:string})=>void)|undefined;
    askMock.mockReturnValueOnce(new Promise(resolve=>{finish=resolve;}));
    const wrapper=mount(SectionInsight,{props:{report:makeReport(),contextKey:'stock:negative'}});
    await wrapper.setProps({openRequest:1,initialQuestion:'负库存找谁核对？'});
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('负库存找谁核对？');
    await wrapper.get('form').trigger('submit');
    await wrapper.setProps({contextKey:'stock:zero',openRequest:2,initialQuestion:'零余额是否要补货？'});
    finish?.({answer:'旧负库存回答',sectionId:'material',runId:'run-1',engine:'test'});await flushPromises();
    expect(wrapper.text()).not.toContain('旧负库存回答');
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('零余额是否要补货？');
  });

  it('opens and prefills a diagnostic when a lazy section mounts after the request',()=>{
    const wrapper=mount(SectionInsight,{props:{report:makeReport(),contextKey:'period:missing',openRequest:1,initialQuestion:'已完工工单缺什么？'}});
    expect((wrapper.get('input').element as HTMLInputElement).value).toBe('已完工工单缺什么？');
    expect(wrapper.get('[aria-expanded="true"]').exists()).toBe(true);
  });

  it('rejects questions longer than 500 characters', async () => {
    const wrapper = mount(SectionInsight, { props: { report: makeReport() } });
    await wrapper.get('[aria-expanded="false"]').trigger('click');
    await wrapper.get('input').setValue('问'.repeat(501));
    await wrapper.get('form').trigger('submit');

    expect(askMock).not.toHaveBeenCalled();
    expect(wrapper.text()).toContain('问题最多 500 个字符');
  });
});
