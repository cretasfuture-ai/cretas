import { describe, expect, it, vi } from 'vitest';
import { createNativeProductionAdapters, selectNativeTime } from '../nativeProductionSteps';
import type { DemoState } from '../f006Flow';
import type { NativeDemoUi } from '../f006NativeBridge';

const state = (): DemoState => ({
  schemaVersion: 2,
  sandboxSessionId: 's',
  runKey: 'DEMO-F006-FLOW-20261105-test',
  factoryId: 'F006',
  currentStep: 0,
  entities: { salesOrder: { id: 'so-1' } },
  completed: [],
  readbacks: {},
  context: {
    supplier: { id: 'sup' }, customer: { id: 'cus' }, material: { id: 'mat', unit: 'kg' },
    packaging: { id: 'pack', unit: 'box' }, product: { id: 'sku', unit: 'box' },
    rawWarehouse: { id: 'raw' }, workshopWarehouse: { id: 'work' }, rawQuantity: 1.25,
    finishedQuantity: 4, processes: Array.from({ length: 6 }, (_, i) => ({ name: `p${i + 1}`, code: `p${i + 1}`, order: i + 1, input: {}, output: {} })),
  },
});

function ui(): NativeDemoUi & { calls: string[] } {
  const calls: string[] = [];
  return {
    calls,
    fill: async (label) => { calls.push(`fill:${label}`) }, click: async (selector) => { calls.push(`click:${selector}`) },
    select: async () => undefined, input: async (selector, value) => { calls.push(`input:${selector}=${value}`) },
    upload: async () => undefined, waitFor: async () => document.createElement('div'), until: async () => undefined,
    hold: async () => undefined, navigate: async (path) => { calls.push(`navigate:${path}`) }, read: async () => ({ data: [] }),
    capture: async (_match, action) => { await action(); return { data: [{ id: 'plan-1' }] }; }, remember: () => undefined,
    receiptFile: async () => new File([], 'receipt.pdf'),
  };
}

describe('native production adapters', () => {
  it('uses the original page opener even when the plan page is already cached', async () => {
    const preparePlan=vi.fn().mockResolvedValue(undefined);
    const adapter = createNativeProductionAdapters({ canWrite: () => true,preparePlan }, ['production-plan-create'])[0];
    const testUi = ui(); const s = state();
    await adapter.prepare({ state: s, context: s.context!, ui: testUi });
    expect(preparePlan).toHaveBeenCalledWith(expect.objectContaining({state:s}));
    expect(testUi.calls.some(call=>call.startsWith('navigate:'))).toBe(false);
    expect(testUi.calls).toContain(`input:[data-testid="production-plan-notes"]=${s.runKey}`);
  });

  it('keeps the process adapter on a persisted SUBMITTED row and never advances from a batch response', async () => {
    const adapter = createNativeProductionAdapters({ canWrite: () => true,openProcessEntry:async()=>undefined }, ['process-report-1'])[0];
    const s = state(); const testUi = ui();
    const node = document.createElement('div');node.append(document.createElement('input')); node.setAttribute('data-process-code', 'workflow-real-code');
    const panel=document.createElement('div');
    for(const count of [24,60]){const list=document.createElement('ul');for(let n=0;n<count;n++){const choice=document.createElement('li');choice.className='el-time-spinner__item';choice.textContent=String(n).padStart(2,'0');list.append(choice)}list.className='el-time-spinner__list';panel.append(list)}
    const confirmation=document.createElement('div');confirmation.innerHTML='<span class="el-message-box__title">确认正式报工</span>';
    testUi.waitFor = async (selector) => selector==='.el-time-panel'?panel:selector==='.el-message-box'?confirmation:node;
    testUi.read = vi.fn(async (path: string) => path.includes('/rows') ? { data: [{ clientRowId: `${s.runKey}:p1`, submissionStatus: 'SUBMITTED' }] } : { data: [] });
    await adapter.prepare({ state: s, context: s.context!, ui: testUi });
    const result = await adapter.commit({ state: s, context: s.context!, ui: testUi });
    expect(result?.processReport1?.clientRowId).toBe(`${s.runKey}:p1`);
    expect(result?.processReport1?.submissionStatus).toBe('SUBMITTED');
    expect(testUi.calls).toContain('click:.el-message-box__btns .el-button--primary');
    expect(testUi.calls.filter(call=>call==='click:.el-time-panel__btn.confirm')).toHaveLength(2);
    expect(testUi.calls.some(call=>call.startsWith('input:[data-testid="output-start-time"]'))).toBe(false);
  });
});

it('checks the active process clock instead of a hidden cached process input',async()=>{
 const old=document.createElement('span');old.dataset.testid='output-start-time';old.style.display='none';old.innerHTML='<input value="08:03" readonly>'
 const active=document.createElement('span');active.dataset.testid='output-start-time';active.innerHTML='<input readonly>'
 document.body.append(old,active)
 const panel=document.createElement('div')
 for(const count of [24,60]){const list=document.createElement('ul');list.className='el-time-spinner__list';for(let n=0;n<count;n++){const li=document.createElement('li');li.className='el-time-spinner__item';li.textContent=String(n).padStart(2,'0');list.append(li)}panel.append(list)}
 const driver=ui();driver.waitFor=async(selector)=>selector==='.el-time-panel'?panel:active
 driver.click=async(selector)=>{if(selector==='.el-time-panel__btn.confirm')active.querySelector('input')!.value='08:06'}
 driver.until=async(predicate)=>{expect(await predicate()).toBe(true)}
 try{await selectNativeTime(driver,'[data-testid="output-start-time"]','08:06');expect(old.querySelector('input')!.value).toBe('08:03')}finally{old.remove();active.remove()}
})
