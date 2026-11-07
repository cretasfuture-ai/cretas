import { beforeEach, describe, expect, it, vi } from 'vitest';
import { createPageAgentController, endGlobalPageAgent, pageAgentState, startGlobalPageAgent, type AgentControllerPorts, type PageAgentState } from '../pageAgentRuntime';
import type { AgentAction, AgentSnapshot } from '../pageAgentDom';
import type { AgentWireRequest, AgentWireResult } from '../pageAgentTransport';

const scope = { factoryId: 'F006', actorId: '1309', role: 'factory_super_admin', sandboxId: 'own-session' };
const write: AgentWireRequest = { method: 'POST', path: '/own/proxy/api/mobile/F006/purchase/orders', canonicalPath: '/purchase/orders', kind: 'business' };
const success: AgentWireResult = { ...write, status: 200, ok: true, body: { success: true, data: { id: 'po1', factoryId: 'F006', orderNumber: 'PO-1' } } };
it('cannot discard an uncertain round or start again after its stopped journal was restored', async () => {
  const previous = { ...pageAgentState };
  try {
    Object.assign(pageAgentState, { scope, active: false, phase: 'error', uncertain: true, businessPending: false });
    await expect(endGlobalPageAgent(false)).rejects.toThrow('提交结果尚未确认');
    await expect(startGlobalPageAgent('再创建一笔采购', true)).rejects.toThrow('上一轮');
    expect(pageAgentState.scope).toEqual(scope); expect(pageAgentState.uncertain).toBe(true);
  } finally { Object.assign(pageAgentState, previous); }
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(yes => { resolve = yes; });
  return { promise, resolve };
}
function fixture(plans: AgentAction[]) {
  let clock = 0;
  const state: PageAgentState = { active: false, phase: 'idle', goal: '', runKey: '', stage: '', error: '', scope: null, moduleStep: 1, actions: 0, remaining: 0, businessPending: false, uncertain: false, events: [], completed: [] };
  const button = document.createElement('button'); button.textContent = '保存';
  document.body.append(button);
  const snapshot: AgentSnapshot = { id: 'screen1', route: '/procurement/orders', targets: new Map([['c1', button]]), routeTargets: new Map([['r1', { path: '/sales/orders', title: '销售订单' }]]), page: { title: '采购', text: '采购原页面', controls: [{ ref: 'c1', role: 'button', label: '保存' }], routes: [{ ref: 'r1', path: '/sales/orders', title: '销售订单' }] } };
  const ports: AgentControllerPorts = {
    assertScope: vi.fn(), verifyIdentity: vi.fn(async () => {}), observe: vi.fn(() => snapshot),
    plan: vi.fn(async () => plans.shift() || { kind: 'done', message: '本次任务结束' }),
    execute: vi.fn(async (_snapshot, action, before) => {
      if (action.kind === 'navigate') return { label: '销售订单', path: '/sales/orders' };
      await before(button);
      return { label: action.kind === 'fill' ? '数量' : '保存', before: '', after: action.value || '保存' };
    }),
    navigate: vi.fn(async () => {}), settled: vi.fn(async () => {}),
    readback: vi.fn(async () => '已回读原单据：PO-1'),
    sleep: vi.fn(async ms => { clock += ms; }), now: () => clock,
    persist: vi.fn(),
    makeRunKey: () => 'DEMO-F006-FLOW-20261105-test-run',
  };
  const controller = createPageAgentController(state, ports);
  return { state, ports, controller, button, snapshot, clock: () => clock };
}
const save: AgentAction = { kind: 'click', target: 'c1', message: '点击保存采购草稿' };
beforeEach(() => {
  document.body.innerHTML = '';
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false);
});

describe('global page agent submission latch', () => {
  it('explains a covered original control without exposing internal DOM-driver errors', async () => {
    const f = fixture([save]);
    f.ports.execute = vi.fn(async () => { throw new Error('Agent action rejected: target is offscreen or covered'); });
    await f.controller.start('填写采购单', scope);
    expect(f.state.phase).toBe('error'); expect(f.state.uncertain).toBe(false);
    expect(f.state.error).toContain('弹出层遮挡'); expect(f.state.error).not.toContain('Agent action');
  });
  it('holds the original 创建 submit for ten visible seconds before sending', async () => {
    const f = fixture([save]); f.button.textContent = '创建'; f.snapshot.page.controls[0]!.label = '创建';
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); expect(f.clock()).toBe(10000);
      f.controller.beforeBusiness(write); f.controller.completeBusiness(success); return { label: '创建' };
    });
    await f.controller.start('创建采购草稿', scope);
    expect(f.state.phase).toBe('awaiting-next');
  });

  it('sends the real manual Next fact to the next plan and never invents it for Continue', async () => {
    const f = fixture([save]);
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); f.controller.completeBusiness(success); return { label: '保存' };
    });
    await f.controller.start('先采购再销售', scope);
    await f.controller.continue();
    expect(f.ports.plan).toHaveBeenCalledTimes(1);
    expect(f.state.events.some(event => event.text.includes('用户已点击“下一模块”'))).toBe(false);
    await f.controller.next();
    const plannedEvents = vi.mocked(f.ports.plan).mock.calls.at(-1)![2];
    expect(plannedEvents.some(event => event.text.includes('用户已点击“下一模块”') && event.text.includes('第2模块'))).toBe(true);
  });
  it('fills the original control and stops after its real submission; continue cannot skip Next', async () => {
    const f = fixture([{ kind: 'fill', target: 'c1', value: '100', message: '填写数量100' }, save, { kind: 'navigate', target: 'r1', message: '打开销售订单' }]);
    const original = f.ports.execute;
    f.ports.execute = vi.fn(async (snapshot, action, before) => {
      const result = await original(snapshot, action, before);
      if (action.kind === 'click') { f.controller.beforeBusiness(write); f.controller.completeBusiness(success); }
      return result;
    });
    await f.controller.start('采购100公斤后创建销售单', scope);
    expect(f.state.phase).toBe('awaiting-next');
    expect(f.ports.plan).toHaveBeenCalledTimes(2);
    expect(f.ports.readback).toHaveBeenCalledWith(success);
    expect(f.ports.navigate).not.toHaveBeenCalled();
    expect(f.clock()).toBeGreaterThanOrEqual(12000);
    await f.controller.continue();
    expect(f.ports.plan).toHaveBeenCalledTimes(2);
    expect(f.state.events.some(event => event.before === '' && event.after === '100')).toBe(true);
    await f.controller.next();
    expect(f.ports.navigate).toHaveBeenCalledWith('/sales/orders');
    expect(f.state.moduleStep).toBe(2);
    expect(f.state.phase).toBe('complete');
  });

  it('does not enable Next before readback finishes, even after HTTP200', async () => {
    const f = fixture([save]);
    const probe = deferred<string>();
    f.ports.readback = vi.fn(() => probe.promise);
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); f.controller.completeBusiness(success);
      return { label: '保存' };
    });
    const run = f.controller.start('保存采购单', scope);
    await vi.waitFor(() => expect(f.ports.readback).toHaveBeenCalledOnce());
    expect(f.state.phase).toBe('submitting');
    await f.controller.next();
    expect(f.ports.plan).toHaveBeenCalledTimes(1);
    probe.resolve('实际回读PO-1'); await run;
    expect(f.state.phase).toBe('awaiting-next');
  });

  it('rejects background writes and a second write from the same module before Next', async () => {
    const f = fixture([save]);
    expect(() => f.controller.beforeBusiness(write)).toThrow('尚未绑定');
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); f.controller.completeBusiness(success);
      expect(() => f.controller.beforeBusiness(write)).toThrow('没有获准');
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(() => f.controller.beforeBusiness(write)).toThrow('没有获准');
  });

  it('preserves an uncertain network outcome and never replays on Continue or Next', async () => {
    const f = fixture([save]);
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write);
      f.controller.completeBusiness({ ...success, status: 0, ok: false, body: null });
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(f.state.phase).toBe('error'); expect(f.state.uncertain).toBe(true);
    await f.controller.continue(); await f.controller.next();
    expect(f.ports.execute).toHaveBeenCalledTimes(1);
    expect(f.ports.readback).not.toHaveBeenCalled();
  });

  it('stops if the readback identifies another entity instead of claiming completion', async () => {
    const f = fixture([save]);
    f.ports.readback = vi.fn(async () => { throw new Error('单据工厂不一致'); });
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); f.controller.completeBusiness(success);
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(f.state.phase).toBe('error'); expect(f.state.uncertain).toBe(true);
    expect(f.state.error).toContain('工厂不一致');
  });

  it('does not treat a blocked extra write as a completed module', async () => {
    const f = fixture([save]);
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); f.controller.completeBusiness(success);
      f.controller.block('原页面试图额外提交，已阻止');
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(f.state.phase).toBe('error'); expect(f.state.uncertain).toBe(true);
    expect(f.ports.readback).not.toHaveBeenCalled();
  });

  it('keeps a blocked second request latched when the first request succeeds later', async () => {
    const f = fixture([save]);
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write);
      f.controller.block('第二次提交被阻止，结果需核对');
      f.controller.completeBusiness(success);
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(f.state.uncertain).toBe(true); expect(f.state.phase).toBe('error');
    await f.controller.next(); await f.controller.continue();
    expect(f.ports.execute).toHaveBeenCalledOnce();
    expect(f.ports.readback).not.toHaveBeenCalled();
  });

  it('does not unlock a timed-out submit when its late success arrives', async () => {
    const f = fixture([save]);
    f.ports.settled = vi.fn().mockResolvedValueOnce(undefined).mockRejectedValueOnce(new Error('原页面请求超时'));
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write);
      return { label: '保存' };
    });
    await f.controller.start('保存采购单', scope);
    expect(f.state.uncertain).toBe(true); expect(f.state.businessPending).toBe(true);
    f.controller.completeBusiness(success);
    expect(f.state.businessPending).toBe(false);
    expect(f.state.uncertain).toBe(true); expect(f.state.phase).toBe('error');
    await f.controller.next(); await f.controller.continue();
    expect(f.ports.execute).toHaveBeenCalledOnce();
  });

  it('retains verified IDs after many manual inputs, passes the ledger to AI, and rejects an identical replay before send', async () => {
    const f = fixture([save, save]); let actualSends = 0;
    f.ports.execute = vi.fn(async (_snapshot, _action, before) => {
      await before(f.button); f.controller.beforeBusiness(write); actualSends++;
      f.controller.completeBusiness(success); return { label: '保存' };
    });
    await f.controller.start('创建一张采购单，随后查看结果', scope);
    for (let n = 0; n < 75; n++) f.controller.pause('手动输入', true);
    expect(f.state.events.filter(event => event.kind === 'pause')).toHaveLength(1);
    expect(f.state.completed).toHaveLength(1);
    // Even later, unrelated UI records cannot evict the authoritative completion.
    f.state.events.push(...Array.from({ length: 30 }, () => ({ at: '', kind: 'pause' as const, text: '后来查看页面' })));
    await f.controller.next();
    const completed = vi.mocked(f.ports.plan).mock.calls.at(-1)?.[3];
    expect(completed).toEqual([expect.objectContaining({ summary: '已回读原单据：PO-1', moduleStep: 1 })]);
    expect(actualSends).toBe(1);
    expect(f.state.phase).toBe('error'); expect(f.state.error).toContain('已完成相同提交');
  });
});

describe('global page agent observation and user control', () => {
  it('discards a plan after the user manually edits the page and rescans on Continue', async () => {
    const f = fixture([]);
    const pending = deferred<AgentAction>();
    f.ports.plan = vi.fn().mockImplementationOnce(() => pending.promise).mockResolvedValue({ kind: 'done', message: '已核对用户修改后的页面' });
    const run = f.controller.start('填写原页面', scope);
    await vi.waitFor(() => expect(f.ports.plan).toHaveBeenCalledOnce());
    f.controller.pause('用户修改', true);
    pending.resolve({ kind: 'fill', target: 'c1', value: '旧值', message: '旧计划' });
    const continued = f.controller.continue();
    await Promise.all([run, continued]);
    expect(f.ports.execute).not.toHaveBeenCalled();
    expect(f.ports.observe).toHaveBeenCalledTimes(2);
  });

  it('invalidates an in-flight plan on the first manual edit even after a programmatic Pause', async () => {
    const f = fixture([]); const pending = deferred<AgentAction>();
    f.ports.plan = vi.fn().mockImplementationOnce(() => pending.promise).mockResolvedValue({ kind: 'done', message: '已重新观察' });
    const run = f.controller.start('保存采购', scope);
    await vi.waitFor(() => expect(f.ports.plan).toHaveBeenCalledOnce());
    f.controller.pause('顶部暂停');
    f.controller.pause('用户改变了数量', true);
    pending.resolve(save);
    await Promise.all([run, f.controller.continue()]);
    expect(f.ports.execute).not.toHaveBeenCalled();
    expect(f.ports.observe).toHaveBeenCalledTimes(2);
  });

  it('asks for missing values without inventing a submission', async () => {
    const f = fixture([{ kind: 'ask', message: '请选择供应商并提供单价' }]);
    await f.controller.start('采购100公斤', scope);
    expect(f.state.phase).toBe('needs-input');
    expect(f.ports.execute).not.toHaveBeenCalled();
    expect(f.state.events.at(-1)?.text).toContain('供应商');
    await f.controller.continue('供应商甲，单价20元');
    expect(f.state.goal).toContain('用户补充：供应商甲，单价20元');
  });

  it('stops before a DOM action when factory or actor scope changes', async () => {
    const f = fixture([save]);
    const pending = deferred<AgentAction>();
    f.ports.plan = vi.fn(() => pending.promise);
    const run = f.controller.start('保存', scope);
    await vi.waitFor(() => expect(f.ports.plan).toHaveBeenCalledOnce());
    f.ports.assertScope = vi.fn(() => { throw new Error('工厂已变化'); });
    pending.resolve(save); await run;
    expect(f.ports.execute).not.toHaveBeenCalled();
    expect(f.state.error).toContain('工厂已变化');
  });

  it('cancels a pending plan when leaving the app; no late DOM action runs', async () => {
    const f = fixture([save]); const pending = deferred<AgentAction>();
    f.ports.plan = vi.fn(() => pending.promise);
    const run = f.controller.start('保存', scope);
    await vi.waitFor(() => expect(f.ports.plan).toHaveBeenCalledOnce());
    f.controller.cancelPlanning(); pending.resolve(save); await run;
    expect(f.ports.execute).not.toHaveBeenCalled(); expect(f.state.active).toBe(false);
  });
});
