// @vitest-environment jsdom
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { flushPromises, shallowMount } from '@vue/test-utils';
import { nextTick } from 'vue';
vi.mock('@/api/smartbi/blacklake-live',()=>({getBlacklakeLiveMonitor:vi.fn(()=>new Promise(()=>{}))}));

vi.mock('@nuxt/ui/components/Card.vue', () => ({ default: { template: '<div><slot /></div>' } }));
vi.mock('@nuxt/ui/components/Button.vue', () => ({ default: { template: '<button><slot /></button>' } }));
vi.mock('@nuxt/ui/components/Badge.vue', () => ({ default: { template: '<span><slot /></span>' } }));
const authMock = vi.hoisted(() => ({ store: { factoryId: 'F006', user: { id: 1 }, currentRole: 'factory_super_admin' } }));
vi.mock('@/store/modules/auth', async () => {
  const { reactive } = await import('vue');
  const store = reactive(authMock.store);
  authMock.store = store;
  return { useAuthStore: () => store };
});
vi.mock('@/components/smartbi/DynamicChartRenderer.vue', () => ({ default: { template: '<div />' } }));
vi.mock('../BlacklakePrototypeCostIntelligence.vue', () => ({ default: { template: '<div />' } }));
vi.mock('@/api/smartbi/blacklake', async () => {
  const actual = await vi.importActual<typeof import('@/api/smartbi/blacklake')>('@/api/smartbi/blacklake');
  return { ...actual, getBlacklakeAnalysis: vi.fn(), createBlacklakePeriodReport: vi.fn(), getBlacklakePeriodReport: vi.fn(), listBlacklakePeriodReports: vi.fn(), getBlacklakePeriodSchedule: vi.fn() };
});

import { createBlacklakePeriodReport, getBlacklakeAnalysis, getBlacklakePeriodReport, getBlacklakePeriodSchedule, listBlacklakePeriodReports } from '@/api/smartbi/blacklake';
import BlacklakePrototypeAnalysis from '../BlacklakePrototypeAnalysis.vue';
import type { BlacklakeAnalysis, BlacklakePeriodJob } from '@/api/smartbi/blacklake';
import {getBlacklakeLiveMonitor,type LiveMonitorReport} from '@/api/smartbi/blacklake-live';

const project = {
  projectId: 2, projectCode: 'P2', product: '黑椒鸡胸', productCode: 'SKU-2', unit: 'kg', status: '20', planned: 10, actual: 10,
  finishedGood: 10, materialCost: 50, laborCost: 20, identifiedDirectCost: 70, unitIdentifiedDirectCost: 7,
  materialLines: 1, materialCostKnownLines: 1, sourceZeroCostLines: 0, taskCount: 1, laborCostKnownTasks: 1,
  materialLinesDetail: [], processes: [], issues: [], unitCostAvailable: true,
};
const analysis = {
  sectionInsights: [], costAlerts: { schema: 'test', ruleVersion: 'test', summary: { projects: 1, costEligible: 1, costBlocked: 0, alerts: 1, byFamily: { business_signal: 1 }, byType: {} }, items: [{ id: 'A1', type: 'missing', family: 'business_signal', severity: 'warning', blocksUnitCost: false, title: '缺少来源说明', message: '请回看来源记录', owner: '仓库', action: '核对领退料单', fields: ['materialCost'], entityRefs: [{ projectId: 2, projectCode: 'P2', productCode: 'SKU-2', unit: 'kg' }], evidence: {}, detectedBy: 'test', ruleVersion: 'test', scope: 'snapshot', runId: 'run-1', status: 'open' }], limitations: [], ai: { status: 'invalid', message: '规则分析' } },
  eligibleCostReview: { eligibleProjects: 1, products: 1, limitations: [], rankings: [], projects: [] },
  alertFollowups: {}, costIntelligence: { engine: 'test', recommendations: [], comparisons: [], answer: '', limitations: [], worklist: [], verifiedMaterialReferences: {}, gapEvidence: {} },
  factoryName: 'F006', snapshotAt: '2026-11-04T00:00:00Z', runId: 'run-1', coverageComplete: true,
  periodMetadata: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai', fingerprint: 'f'.repeat(64), scopeDescription: '测试周期', dataAsOf: '2026-11-04T23:00:00+08:00', unassignedProjects: 0, selectedProjects: 1, coverageStatus: 'complete', inventoryStatus: 'complete' },
  catalog: [], findings: [], limitations: [], production: { projectCount: 1, taskCount: 1, outputCount: 1, completionRate: 1, statuses: { '20': 1 }, planActual: [], quality: [], processes: [] },
  inventory: { records: [], byWarehouseUnit: [], pairsNotReturned: 0, movementOrders: 0 },
  cost: { projects: [project], materials: [], linkedMaterialLines: 1, unlinkedMaterialLines: 0, excludedUnapprovedOrders: 0, knownMaterialCostLines: 1, sourceZeroCostLines: 0, knownLaborTasks: 1, unitCostAvailableProjects: 1, fullManufacturingCost: null, grossProfit: null },
} satisfies BlacklakeAnalysis;

describe('Blacklake prototype page', () => {
  const asyncPageStubs = {
  CostDataTable: {props:['rows'],template:'<div><span v-for="(row,i) in rows" :key="i">{{ row.product }}</span><slot/></div>'},
    BlacklakePeriodControls: { name: 'BlacklakePeriodControls', emits: ['change'], template: '<button data-testid="period-change" @click="$emit(\'change\', { start_date: \'2026-11-02\', end_date: \'2026-11-02\', basis: \'completed\', kind: \'day\' })">change</button>' },
    BlacklakePeriodAnalysis: { name: 'BlacklakePeriodAnalysis', template: '<div><slot /></div>' },
    BlacklakePrototypeCostIntelligence: true,
    DynamicChartRenderer: true,
    PrototypeSectionInsight: true,
    'el-table': { props: ['data'], template: '<div><div v-for="row in data" :key="row.projectId">{{ row.product }}</div><slot /></div>' },
    'el-table-column': { template: '<span />' },
    'el-tag': { template: '<span><slot /></span>' },
    'el-button': { template: '<button><slot /></button>' },
  };

  beforeEach(() => {
    vi.useFakeTimers({toFake:['Date']});vi.setSystemTime(new Date('2026-11-05T04:00:00Z'));
    authMock.store.factoryId = 'F006'; authMock.store.user = { id: 1 }; authMock.store.currentRole = 'factory_super_admin';
    vi.mocked(getBlacklakeAnalysis).mockReset();
    vi.mocked(getBlacklakeLiveMonitor).mockReset().mockImplementation(()=>new Promise(()=>{}));
    vi.mocked(createBlacklakePeriodReport).mockReset().mockResolvedValue({ id: 'job-1', status: 'queued', period: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai' }, fingerprint: 'f'.repeat(64), revision: 1, createdAt: '2026-11-04T23:00:00+08:00' });
    vi.mocked(getBlacklakePeriodReport).mockReset().mockResolvedValue({ id: 'job-1', status: 'queued', period: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai' }, fingerprint: 'f'.repeat(64), revision: 1, createdAt: '2026-11-04T23:00:00+08:00' });
    vi.mocked(listBlacklakePeriodReports).mockReset().mockResolvedValue([]);
    vi.mocked(getBlacklakePeriodSchedule).mockReset().mockResolvedValue({ enabled: true, timezone: 'Asia/Shanghai', rules: [] });
  });
  afterEach(() => vi.useRealTimers());

  it('keeps project-linked alerts visible in the cost table path', async () => {
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: { CostDataTable:{props:['rows'],template:'<div><span v-for="(row,i) in rows" :key="i">{{ row.product }}</span><slot/></div>'},
      DynamicChartRenderer: true,
      PrototypeSectionInsight: true,
      BlacklakePrototypeCostAlerts: { template: '<div />' },
      BlacklakePrototypeCostIntelligence: true,
      'el-table': { props: ['data'], template: '<div><div v-for="row in data" :key="row.projectId">{{ row.product }}</div><slot /></div>' },
      'el-table-column': { template: '<span />' },
      'el-tag': { template: '<span><slot /></span>' },
    } } });
    await new Promise(resolve => setTimeout(resolve, 0));
    expect(wrapper.text()).toContain('黑椒鸡胸');
    expect(wrapper.find('[data-testid="cost-projects"]').exists()).toBe(true);
  });

  it('unblocks facts and period selection while report submission is still pending', async () => {
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    vi.mocked(createBlacklakePeriodReport).mockImplementation(() => new Promise(() => {}));
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});
    await flushPromises();
    const vm=wrapper.vm as unknown as {loading:boolean;periodLoading:boolean;data:BlacklakeAnalysis|null};
    expect(vm.data?.runId).toBe('run-1');
    expect(vm.loading).toBe(false);
    expect(vm.periodLoading).toBe(true);
    wrapper.unmount();
  });

  it('drops an older GET response after switching periods', async () => {
    let resolveFirst!: (value: BlacklakeAnalysis) => void;
    let resolveSecond!: (value: BlacklakeAnalysis) => void;
    vi.mocked(getBlacklakeAnalysis).mockImplementationOnce(() => new Promise(resolve => { resolveFirst = resolve; })).mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve; }));
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await nextTick();
    (wrapper.vm as unknown as { onPeriodChange: (query: { start_date: string; end_date: string; basis: 'completed'; kind: 'day' }) => void }).onPeriodChange({ start_date: '2026-11-02', end_date: '2026-11-02', basis: 'completed', kind: 'day' });
    await flushPromises();
    resolveFirst({ ...analysis, runId: 'old' });
    resolveSecond({ ...analysis, runId: 'new' });
    await flushPromises();
    expect(wrapper.text()).not.toContain('old');
  });

  it('automatically refreshes conflicted facts and submits the new fingerprint without blocking data',async()=>{
    vi.useFakeTimers();
    const updated={...analysis,runId:'refreshed',periodMetadata:{...analysis.periodMetadata!,fingerprint:'a'.repeat(64)}};
    vi.mocked(getBlacklakeAnalysis).mockResolvedValueOnce({...analysis}).mockResolvedValue(updated);
    vi.mocked(createBlacklakePeriodReport).mockRejectedValueOnce(Object.assign(new Error('conflict'),{status:409}));
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});
    await flushPromises();
    expect(wrapper.text()).toContain('正在自动更新分析');
    expect((wrapper.vm as unknown as {loading:boolean}).loading).toBe(false);
    await vi.advanceTimersByTimeAsync(1000);await flushPromises();
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(2);
    const calls=vi.mocked(createBlacklakePeriodReport).mock.calls;
    expect(calls[1]?.[0]).toEqual(calls[0]?.[0]);
    expect(calls[1]?.[2]).toBe('a'.repeat(64));
    expect(wrapper.text()).not.toContain('正在自动更新分析');
    expect((wrapper.vm as unknown as {data:BlacklakeAnalysis}).data.runId).toBe('refreshed');
    wrapper.unmount();
  });

  it('backs off repeated conflicts without a user retry or an attempt limit',async()=>{
    vi.useFakeTimers();
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis});
    vi.mocked(createBlacklakePeriodReport).mockRejectedValue(Object.assign(new Error('conflict'),{status:409}));
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    for(const delay of [1000,2000,4000]){await vi.advanceTimersByTimeAsync(delay);await flushPromises();}
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(4);
    expect((wrapper.vm as unknown as {periodError:string}).periodError).toBe('');
    wrapper.unmount();await vi.advanceTimersByTimeAsync(60000);
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(4);
  });

  it('cancels automatic conflict retry when the user changes dates',async()=>{
    vi.useFakeTimers();
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis});
    vi.mocked(createBlacklakePeriodReport).mockRejectedValueOnce(Object.assign(new Error('conflict'),{status:409}));
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    await wrapper.get('[data-testid="period-change"]').trigger('click');await flushPromises();
    await vi.advanceTimersByTimeAsync(2000);await flushPromises();
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(2);
    expect(vi.mocked(createBlacklakePeriodReport).mock.calls[1]?.[0].start_date).toBe('2026-11-02');
    wrapper.unmount();
  });

  it('does not retry an authorization rejection as a data refresh',async()=>{
    vi.useFakeTimers();vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis});
    vi.mocked(createBlacklakePeriodReport).mockRejectedValue(Object.assign(new Error('forbidden'),{status:403}));
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    await vi.advanceTimersByTimeAsync(60000);await flushPromises();
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(1);
    expect((wrapper.vm as unknown as {periodError:string;periodLoading:boolean}).periodLoading).toBe(false);
    wrapper.unmount();
  });

  it('keeps the queued report when only the collection clock changes',async()=>{
    const monitor={lastSuccessAt:'2026-11-05T10:00:00Z',changedCycles:2} as LiveMonitorReport;
    vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue(monitor);
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis});
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue({...monitor,lastSuccessAt:'2026-11-05T10:01:00Z'});
    await (wrapper.vm as unknown as {refreshLive:()=>Promise<void>}).refreshLive();
    expect(vi.mocked(getBlacklakeAnalysis)).toHaveBeenCalledTimes(1);
    expect((wrapper.vm as unknown as {periodJob:BlacklakePeriodJob}).periodJob.id).toBe('job-1');
    wrapper.unmount();
  });

  it('automatically requeues the selected period when source data changes',async()=>{
    const monitor={lastSuccessAt:'2026-11-05T10:00:00Z',changedCycles:2} as LiveMonitorReport;
    vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue(monitor);
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis});
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    vi.mocked(getBlacklakeLiveMonitor).mockResolvedValue({...monitor,lastSuccessAt:'2026-11-05T10:01:00Z',changedCycles:3});
    await (wrapper.vm as unknown as {refreshLive:()=>Promise<void>}).refreshLive();
    expect(vi.mocked(createBlacklakePeriodReport)).toHaveBeenCalledTimes(2);
    const calls=vi.mocked(createBlacklakePeriodReport).mock.calls;expect(calls[1]?.[0]).toEqual(calls[0]?.[0]);
    wrapper.unmount();
  });

  it('passes the GET fingerprint and force=true on failed retry', async () => {
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    vi.mocked(createBlacklakePeriodReport).mockResolvedValueOnce({ id: 'failed', status: 'failed', period: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai' }, fingerprint: 'f'.repeat(64), revision: 1, createdAt: '2026-11-04T23:00:00+08:00', error: '失败' });
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: { CostDataTable:{props:['rows'],template:'<div><span v-for="(row,i) in rows" :key="i">{{ row.product }}</span><slot/></div>'},
      ...asyncPageStubs,
      BlacklakePeriodAnalysis: { name: 'BlacklakePeriodAnalysis', emits: ['retry'], template: '<button data-testid="retry-period" @click="$emit(\'retry\')">retry</button>' },
    } } });
    await flushPromises();
    await wrapper.get('[data-testid="retry-period"]').trigger('click');
    await flushPromises();
    expect(vi.mocked(createBlacklakePeriodReport).mock.calls.at(-1)?.[1]).toBe(true);
    expect(vi.mocked(createBlacklakePeriodReport).mock.calls.at(-1)?.[2]).toBe('f'.repeat(64));
  });

  it('drops a late POST result after switching periods', async () => {
    let resolveOldPost!: (value: BlacklakePeriodJob) => void;
    const newJob = { id: 'new-job', status: 'queued' as const, period: { startDate: '2026-11-02', endDate: '2026-11-02', kind: 'day' as const, basis: 'completed' as const, timezone: 'Asia/Shanghai' }, fingerprint: 'f'.repeat(64), revision: 2, createdAt: '2026-11-04T23:00:00+08:00' };
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    let postCount = 0;
    vi.mocked(createBlacklakePeriodReport).mockImplementation(() => {
      postCount += 1;
      return postCount === 1 ? new Promise(resolve => { resolveOldPost = resolve; }) : Promise.resolve(newJob);
    });
    vi.mocked(getBlacklakePeriodReport).mockImplementation(() => new Promise(() => {}));
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    (wrapper.vm as unknown as { onPeriodChange: (query: { start_date: string; end_date: string; basis: 'completed'; kind: 'day' }) => void }).onPeriodChange({ start_date: '2026-11-02', end_date: '2026-11-02', basis: 'completed', kind: 'day' });
    await flushPromises();
    expect((wrapper.vm as unknown as { periodJob: BlacklakePeriodJob | null }).periodJob?.id).toBe('new-job');
    resolveOldPost({ ...newJob, id: 'old-job' });
    await flushPromises();
    expect((wrapper.vm as unknown as { periodJob: BlacklakePeriodJob | null }).periodJob?.id).toBe('new-job');
    wrapper.unmount();
  });

  it('loads a queued rule draft once while local AI waits', async () => {
    const queuedResult = { generatedAt: '2026-11-04T10:00:00+08:00', sectionInsights: [{ sectionId: 'product_cost', title: '规则草稿', summary: '规则已保存', observations: [], calculation: [], hypotheses: [], limitations: [], nextSteps: [], suggestedQuestions: [], scope: { runId: 'draft' }, evidenceRefs: [] }], summary: '规则草稿', observations: [], history: { usedReports: [], recurringIssues: [], newIssues: [], resolvedIssues: [] }, ai: { status: 'not_run', message: '本地 AI 等待补充' } };
    const queuedJob = { id: 'draft-job', status: 'queued' as const, period: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day' as const, basis: 'completed' as const, timezone: 'Asia/Shanghai' }, fingerprint: 'f'.repeat(64), revision: 1, createdAt: '2026-11-04T10:00:00+08:00', nextAttemptAt: '2026-11-04T10:01:00+08:00', result: queuedResult };
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    vi.mocked(createBlacklakePeriodReport).mockResolvedValue(queuedJob);
    vi.mocked(getBlacklakePeriodReport).mockResolvedValue(queuedJob);
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    const vm = wrapper.vm as unknown as { data: BlacklakeAnalysis | null; refreshEpoch: number };
    expect(vm.data?.sectionInsights?.[0]?.title).toBe('规则草稿');
    expect(vm.refreshEpoch).toBe(2);
    await flushPromises();
    expect(vm.refreshEpoch).toBe(2);
    wrapper.unmount();
  });

  it('does not install a mismatched ready report', async () => {
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    vi.mocked(createBlacklakePeriodReport).mockResolvedValue({ id: 'ready', status: 'ready', period: { startDate: '2026-11-04', endDate: '2026-11-04', kind: 'day', basis: 'completed', timezone: 'Asia/Shanghai' }, fingerprint: 'wrong'.repeat(16), revision: 1, createdAt: '2026-11-04T23:00:00+08:00', result: { sectionInsights: [{ sectionId: 'product_cost', title: 'wrong', summary: '', observations: [], calculation: [], hypotheses: [], limitations: [], nextSteps: [], suggestedQuestions: [], scope: { runId: 'wrong' }, evidenceRefs: [] }], summary: 'wrong', observations: [], history: { usedReports: [], recurringIssues: [], newIssues: [], resolvedIssues: [] }, ai: { status: 'complete', message: 'wrong AI' } } });
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    expect(wrapper.text()).not.toContain('wrong');
    wrapper.unmount();
  });

  it('stops period polling after unmount', async () => {
    vi.useFakeTimers();
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    expect(vi.mocked(getBlacklakePeriodReport)).toHaveBeenCalledTimes(1);
    wrapper.unmount();
    await vi.advanceTimersByTimeAsync(5000);
    expect(vi.mocked(getBlacklakePeriodReport)).toHaveBeenCalledTimes(1);
  });

  it('opens inventory on the latest full snapshot and displays the supplement outcome',async()=>{
    const supplemented:BlacklakeAnalysis={...analysis,periodMetadata:undefined,operationsSupplement:{checkedAt:'2026-11-05T07:38:37Z',reportingUnitsRecovered:1,outputRecords:1,unresolvedOutputs:[],inventoryRecords:2,stockPairsQueried:3,stockPairsNotReturned:1,requests:[{code:'stock_history',status:'requires_historical_records',missing:'期间起止库存',owner:'仓库 / 财务',action:'补库存表',afterProvided:'核对收发存'}]}};
    vi.mocked(getBlacklakeAnalysis).mockResolvedValueOnce({...analysis}).mockResolvedValue(supplemented);
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{props:{activeView:'inventory'},global:{stubs:asyncPageStubs}});await flushPromises();await flushPromises();
    expect(vi.mocked(getBlacklakeAnalysis).mock.calls.at(-1)?.[0]).toBeNull();
    expect(wrapper.get('[data-testid="operations-supplement"]').text()).toContain('报工单位已补齐');
    expect(wrapper.get('[data-testid="operations-supplement"]').text()).toContain('最新库存 2 行');wrapper.unmount();
  });

  it('publishes a follow-up status update without mutating the loaded snapshot',async()=>{
    const loaded={...analysis,alertFollowups:{}};vi.mocked(getBlacklakeAnalysis).mockResolvedValue(loaded);
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    const vm=wrapper.vm as unknown as {data:BlacklakeAnalysis;updateProgress:(value:import('@/api/smartbi/blacklake').AlertFollowup)=>void};
    vm.updateProgress({alertId:'A1',status:'awaiting_data',note:'待仓库核对',revision:1,updatedBy:1,updatedAt:'2026-11-05T00:00:00Z'});await nextTick();
    expect(vm.data).not.toBe(loaded);expect(vm.data.alertFollowups?.A1?.status).toBe('awaiting_data');expect(loaded.alertFollowups).toEqual({});wrapper.unmount();
  });

  it('locates a stock problem outside the selected unit without retaining an incompatible filter',async()=>{
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({...analysis,inventory:{...analysis.inventory,records:[{product:'甲',warehouse:'A',unit:'箱',quantity:1},{product:'乙',warehouse:'A',unit:'kg',quantity:-1}]}});
    const wrapper=shallowMount(BlacklakePrototypeAnalysis,{global:{stubs:asyncPageStubs}});await flushPromises();
    const vm=wrapper.vm as unknown as {inventoryUnit:string;inventoryFocus:string[];locateOperational:(finding:import('../operationsGuidance').OperationsFinding,section:'inventory')=>Promise<void>};
    vm.inventoryUnit='箱';await nextTick();
    await vm.locateOperational({code:'stock_negative',kind:'check',title:'负余额',summary:'待核',possibleCauses:'先出后入',owner:'仓库',action:'核原单',keys:['1']},'inventory');await nextTick();
    expect(vm.inventoryUnit).toBe('__all__');expect(vm.inventoryFocus).toEqual(['1']);wrapper.unmount();
  });

  it('clears the old factory snapshot when auth identity changes', async () => {
    let resolveSecond!: (value: BlacklakeAnalysis) => void;
    vi.mocked(getBlacklakeAnalysis).mockImplementationOnce(() => Promise.resolve({ ...analysis }))
      .mockImplementationOnce(() => new Promise(resolve => { resolveSecond = resolve; }));
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    expect(wrapper.text()).toContain('黑椒鸡胸');
    authMock.store.factoryId = 'F007';
    await nextTick();
    await flushPromises();
    expect(wrapper.text()).not.toContain('黑椒鸡胸');
    resolveSecond({ ...analysis });
    wrapper.unmount();
  });

  it('drops old history and schedule responses after auth changes', async () => {
    let resolveOldHistory!: (value: BlacklakePeriodJob[]) => void;
    let resolveOldSchedule!: (value: { enabled: boolean; timezone: string; rules: never[] }) => void;
    vi.mocked(getBlacklakeAnalysis).mockResolvedValue({ ...analysis });
    vi.mocked(listBlacklakePeriodReports).mockImplementationOnce(() => new Promise(resolve => { resolveOldHistory = resolve; })).mockResolvedValueOnce([]);
    vi.mocked(getBlacklakePeriodSchedule).mockImplementationOnce(() => new Promise(resolve => { resolveOldSchedule = resolve; })).mockResolvedValueOnce({ enabled: true, timezone: 'Asia/Shanghai', rules: [] });
    const wrapper = shallowMount(BlacklakePrototypeAnalysis, { global: { stubs: asyncPageStubs } });
    await flushPromises();
    authMock.store.factoryId = 'F007';
    await nextTick();
    await flushPromises();
    const vm = wrapper.vm as unknown as { periodHistory: BlacklakePeriodJob[]; periodSchedule: { enabled: boolean } | null };
    expect(vm.periodHistory).toEqual([]);
    expect(vm.periodSchedule?.enabled).toBe(true);
    resolveOldHistory([{ id: 'old-history' } as BlacklakePeriodJob]);
    resolveOldSchedule({ enabled: false, timezone: 'Asia/Shanghai', rules: [] });
    await flushPromises();
    expect(vm.periodHistory).toEqual([]);
    expect(vm.periodSchedule?.enabled).toBe(true);
    wrapper.unmount();
  });
});

