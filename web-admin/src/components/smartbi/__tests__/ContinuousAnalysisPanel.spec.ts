// @vitest-environment jsdom
import {it,expect,vi,afterEach} from 'vitest';
import {shallowMount,flushPromises} from '@vue/test-utils';
vi.mock('@nuxt/ui/components/Button.vue',()=>({default:{template:'<button><slot/></button>'}}));
vi.mock('@/store/modules/auth',()=>({useAuthStore:()=>({factoryId:'TEST',user:{id:1},currentRole:'viewer'})}));
vi.mock('@/api/smartbi/blacklake-live',()=>({getBlacklakeLiveMonitor:vi.fn()}));
vi.mock('@/api/smartbi/blacklake',()=>({getBlacklakePeriodReport:vi.fn()}));
import {getBlacklakeLiveMonitor} from '@/api/smartbi/blacklake-live';
import {getBlacklakePeriodReport} from '@/api/smartbi/blacklake';
import Panel from '../ContinuousAnalysisPanel.vue';
afterEach(()=>vi.useRealTimers());
it('refreshes latest facts only after a new successful source cycle and cancels polling on unmount',async()=>{
  vi.useFakeTimers();
  const report={lastSuccessAt:'2026-11-05T10:00:00Z',successfulCycles:1};
  vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue(report as Awaited<ReturnType<typeof getBlacklakeLiveMonitor>>);
  const page=shallowMount(Panel);await flushPromises();
  expect(page.text()).toContain('11-05 18:00');expect(page.emitted('data-updated')).toBeUndefined();
  vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue({...report,lastSuccessAt:'2026-11-05T10:01:00Z'} as Awaited<ReturnType<typeof getBlacklakeLiveMonitor>>);
  await vi.advanceTimersByTimeAsync(30000);await flushPromises();
  expect(page.emitted('data-updated')).toHaveLength(1);
  page.unmount();const before=vi.mocked(getBlacklakeLiveMonitor).mock.calls.length;
  await vi.advanceTimersByTimeAsync(60000);expect(vi.mocked(getBlacklakeLiveMonitor).mock.calls.length).toBe(before);
});
it('loads the selected full saved report instead of the compact history row',async()=>{
  vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue({lastSuccessAt:null,successfulCycles:0} as Awaited<ReturnType<typeof getBlacklakeLiveMonitor>>);
  vi.mocked(getBlacklakePeriodReport).mockResolvedValue({id:'saved',period:{startDate:'2026-11-01',endDate:'2026-11-05'},result:{summary:'已保存的六膳门分析',sectionInsights:[]}} as Awaited<ReturnType<typeof getBlacklakePeriodReport>>);
  const page=shallowMount(Panel);await flushPromises();
  page.findComponent({name:'LiveMonitor'}).vm.$emit('open-report','saved');await flushPromises();
  expect(getBlacklakePeriodReport).toHaveBeenCalledWith('saved');page.unmount();
});
