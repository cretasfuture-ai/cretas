// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { describe, expect, it } from 'vitest';
import BlacklakePeriodAnalysis from '../BlacklakePeriodAnalysis.vue';
import type { BlacklakePeriodJob, LocalAIUsage } from '@/api/smartbi/blacklake';

function job(usage?: LocalAIUsage, cacheHit = false): BlacklakePeriodJob {
  return { id: 'synthetic', status: 'ready', fingerprint: 'f', revision: 1, createdAt: '2026-11-04T00:00:00Z',
    period: { startDate: '2026-11-03', endDate: '2026-11-03', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai' },
    result: { summary: '合成报告', observations: [], sectionInsights: [], history: { usedReports: [], recurringIssues: [], newIssues: [], resolvedIssues: [] },
      ai: { status: 'complete', message: '已核对', usage, cacheHit } } };
}

describe('period AI token usage', () => {
  it('shows provider counts and distinguishes cached reuse from new consumption', () => {
    const wrapper = mount(BlacklakePeriodAnalysis, { props: { job: job({ inputTokens: 123, outputTokens: 45, totalTokens: 168, source: 'ollama_response', estimated: false }, true) } });
    expect(wrapper.text()).toContain('输入 123 · 输出 45 · 合计 168 token');
    expect(wrapper.text()).toContain('新增消耗 0 token');
  });
  it('does not turn missing provider counts into zero', () => {
    const wrapper = mount(BlacklakePeriodAnalysis, { props: { job: job({ inputTokens: null, outputTokens: null, totalTokens: null, source: 'ollama_response', estimated: false }) } });
    expect(wrapper.text()).toContain('输入 未知 · 输出 未知 · 合计 未知 token');
  });
  it('does not invent retrospective usage for old ready reports', () => {
    expect(mount(BlacklakePeriodAnalysis, { props: { job: job() } }).text()).toContain('本报告未记录 token 用量');
  });
});
