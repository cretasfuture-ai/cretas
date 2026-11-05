// @vitest-environment jsdom
import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
vi.mock('@nuxt/ui/components/Button.vue', () => ({ default: { name: 'UButton', template: '<button @click="$emit(\'click\')"><slot /></button>' } }));
import LiveMonitor from '../LiveMonitor.vue';
import type { LiveMonitorReport } from '@/api/smartbi/blacklake-live';

const report: LiveMonitorReport = { enabled: true, status: 'running', startedAt: '2026-11-01T00:00:00Z', endsAt: '2026-11-08T00:00:00Z', intervalSeconds: 300, lastAttemptAt: '2026-11-05T10:00:00Z', lastSuccessAt: null, nextRunAt: '2026-11-05T10:05:00Z', cycles: 3, successfulCycles: 2, changedCycles: 1, error: '本轮请求超时', analysis: { status: 'pending', message: '等待采集完成', reportId: 'r-1' }, history: [{ id: 'c-1', startedAt: '2026-11-05T09:55:00Z', finishedAt: null, status: 'failed', requests: 2, changed: true, counts: { changed: 1 }, error: '本轮请求超时', analysisReportId: 'r-2' }] };

describe('LiveMonitor', () => {
  it('renders empty state without a report', () => expect(mount(LiveMonitor, { props: { report: null } }).text()).toContain('暂无监视数据'));
  it('renders the actual window, failures and recent cycles, and emits refresh', async () => {
    const wrapper = mount(LiveMonitor, { props: { report }, global: { stubs: { UButton: { template: '<button @click="$emit(\'click\')"><slot/></button>' } } } });
    expect(wrapper.text()).toContain('自动更新记录');
    expect(wrapper.text()).toContain('最近失败：本轮请求超时');
    expect(wrapper.text()).toContain('等待采集完成');
    expect(wrapper.text()).toContain('有变化');
    await wrapper.get('button').trigger('click');
    expect(wrapper.emitted('refresh')).toBeTruthy();
    await wrapper.findAll('.report-link')[0]!.trigger('click');
    expect(wrapper.emitted('open-report')?.[0]).toEqual(['r-1']);
  });
  it('emits bounded server history paging and never emits a negative offset', async () => {
    const wrapper = mount(LiveMonitor, { props: { report: { ...report, historyOffset: 100, historyHasMore: true } }, global: { stubs: { UButton: { template: '<button><slot/></button>' } } } });
    await wrapper.get('.server-pager button').trigger('click');
    expect(wrapper.emitted('history-page')?.[0]).toEqual([0]);
    await wrapper.get('.server-pager button:last-child').trigger('click');
    expect(wrapper.emitted('history-page')?.[1]).toEqual([200]);
  });
});
