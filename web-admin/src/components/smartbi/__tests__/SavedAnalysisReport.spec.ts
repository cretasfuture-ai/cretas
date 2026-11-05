// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import SavedAnalysisReport from '../SavedAnalysisReport.vue';
import type { BlacklakePeriodJob } from '@/api/smartbi/blacklake';

const report = (overrides: Partial<BlacklakePeriodJob> = {}): BlacklakePeriodJob => ({
  id: 'r-1', status: 'ready', period: { startDate: '2026-10-15', endDate: '2026-11-05', kind: 'custom', basis: 'completed', timezone: 'Asia/Shanghai' }, fingerprint: 'f', revision: 1, createdAt: '2026-11-05T10:00:00Z',
  result: { summary: '2026-10-15 至2026-11-05归入136张工单；37张资料具备，99张仍需核对材料、人工或成品记录。', observations: [], sectionInsights: [{ sectionId: 'coverage', title: '覆盖情况', summary: '原始摘要', observations: ['北京时间含首尾；覆盖 136 张工单'], calculation: ['可核对 37 张资料'], hypotheses: ['可能是资料未上传'], limitations: ['数据版本 v2'], nextSteps: ['仓库补齐资料'], suggestedQuestions: [], scope: { runId: 'x' }, evidenceRefs: [], localAnalysis: { sectionId: 'coverage', title: '覆盖情况', evidenceRefs: [], whatItMeans: '已有资料可核对，但仍有记录缺口。', whyItMatters: '', hypotheses: ['资料未上传'], nextStep: '请仓库补齐缺失资料。', owner: '仓库主管', cannotConclude: '', followupQuestion: '', considerations: [], statistics: [{ id: 's1', text: '已有资料 37 张' }], indicator: { label: '', indicator: '', formula: '', howToRead: '' } } }, { sectionId: 'production', title: '生产进度', summary: '生产摘要', observations: [], calculation: [], hypotheses: [], limitations: [], nextSteps: ['找生产主管核对'], suggestedQuestions: [], scope: { runId: 'x' }, evidenceRefs: [] }], history: { comparisonAvailable: false, usedReports: [], recurringIssues: [], newIssues: [], resolvedIssues: [] }, ai: { status: 'complete', message: 'ok' } },
  ...overrides,
});

describe('SavedAnalysisReport', () => {
  it('renders readable metrics and keeps technical boilerplate out of the normal card', () => {
    const wrapper = mount(SavedAnalysisReport, { props: { report: report() } });
    expect(wrapper.text()).toContain('136');
    expect(wrapper.text()).toContain('可核对材料与人工');
    expect(wrapper.text()).toContain('这 136 张工单里，37 张可核对材料和人工成本');
    expect(wrapper.text()).toContain('找谁处理');
    expect(wrapper.text()).not.toContain('北京时间含首尾');
    expect(wrapper.text()).not.toContain('数据版本 v2');
  });
  it('switches to one other section without rendering all essays at once', async () => {
    const wrapper = mount(SavedAnalysisReport, { props: { report: report() } });
    expect(wrapper.text()).not.toContain('生产摘要');
    await wrapper.findAll('.section-nav button')[1]!.trigger('click');
    expect(wrapper.text()).toContain('生产摘要');
    expect(wrapper.text()).not.toContain('已有资料可核对，但仍有记录缺口');
  });
  it('shows honest fallback when a saved report has no model result', () => {
    const wrapper = mount(SavedAnalysisReport, { props: { report: report({ result: undefined }) } });
    expect(wrapper.text()).toContain('本次报告尚未提供可读的数据概况');
  });
});
