import { nextTick, reactive } from 'vue';
import type { Router } from 'vue-router';
import request from '@/api/request';
import { parseFormInput } from '@/api/formAssistant';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore, type ModuleName } from '@/store/modules/permission';
import { nativePlayback } from '@/demo/f006NativeRuntime';
import { DEMO_RUN_KEY } from '@/demo/f006Flow';
import { beginSandbox, clearEndedSandbox, currentTokenOwnsSandbox, endSandbox, refreshSandbox, sandboxRequestUrl, sandboxSession } from '@/demo/f006Sandbox';
import { serializeSandboxLifecycle } from '@/demo/f006SandboxLifecycle';
import { executeAgentAction, observeAgentPage, validateAgentAction, type AgentAction, type AgentSnapshot } from './pageAgentDom';
import { installAgentTransport, type AgentWireRequest, type AgentWireResult, type AgentWireScope } from './pageAgentTransport';
import { createAgentIdentityBinding } from './pageAgentIdentity';
import { assertVerifiablePageMutation, verifyPageMutation } from './pageAgentReadback';
import { createAgentWorkflowGrant, deriveAgentWorkflowContext, type VerifiedAgentOrder, type WorkflowPageRow } from './pageAgentApproval';

export type AgentPhase = 'idle' | 'preparing' | 'planning' | 'preview' | 'acting' | 'paused' | 'submitting' | 'awaiting-next' | 'needs-input' | 'complete' | 'error' | 'ending';
export interface AgentEvent {
  at: string;
  kind: 'goal' | 'action' | 'submission' | 'readback' | 'pause' | 'question' | 'error';
  text: string;
  route?: string;
  before?: string;
  after?: string;
}
export interface AgentCompletion extends VerifiedAgentOrder {
  moduleStep: number;
  fingerprint: string;
  summary: string;
  finishedAt: string;
}
export interface PageAgentState {
  active: boolean;
  phase: AgentPhase;
  goal: string;
  runKey: string;
  stage: string;
  error: string;
  scope: AgentWireScope | null;
  moduleStep: number;
  actions: number;
  remaining: number;
  businessPending: boolean;
  uncertain: boolean;
  events: AgentEvent[];
  completed: AgentCompletion[];
}
function emptyState(): PageAgentState {
  return { active: false, phase: 'idle', goal: '', runKey: '', stage: '', error: '', scope: null, moduleStep: 1, actions: 0, remaining: 0, businessPending: false, uncertain: false, events: [], completed: [] };
}
export const pageAgentState = reactive<PageAgentState>(emptyState());
export interface AgentActionOutcome { label: string; before?: string; after?: string; path?: string }
export interface AgentControllerPorts {
  assertScope(scope: AgentWireScope): void;
  verifyIdentity(): Promise<void>;
  observe(): AgentSnapshot;
  plan(goal: string, snapshot: AgentSnapshot, events: readonly AgentEvent[], completed: readonly AgentCompletion[]): Promise<AgentAction>;
  execute(snapshot: AgentSnapshot, action: AgentAction, before: (element: HTMLElement) => Promise<void>): Promise<AgentActionOutcome>;
  navigate(path: string): Promise<void>;
  settled(): Promise<void>;
  readback(result: AgentWireResult): Promise<string | { summary: string; entity?: VerifiedAgentOrder }>;
  makeRunKey(): string;
  sleep(ms: number): Promise<void>;
  now(): number;
  persist(): void;
}
class Interrupted extends Error {}
function mutationFingerprint(wire: AgentWireRequest): string {
  const stable = (value: unknown): unknown => {
    if (Array.isArray(value)) return value.map(stable);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([key]) => !['idempotencyKey', 'clientRequestId'].includes(key)).sort(([a], [b]) => a.localeCompare(b)).map(([key, item]) => [key, stable(item)]));
    return value;
  };
  const text = JSON.stringify([wire.method, wire.canonicalPath, stable(wire.payload ?? {}), stable(wire.query ?? {})]);
  let a = 2166136261, b = 2246822519;
  for (const char of text) { a = Math.imul(a ^ char.charCodeAt(0), 16777619); b = Math.imul(b ^ char.charCodeAt(0), 3266489917); }
  // Local duplicate prevention only, never a grant or a server idempotency key.
  return `${(a >>> 0).toString(16)}-${(b >>> 0).toString(16)}`;
}

/** State machine shared by the real browser driver and deterministic tests.
 * A response from a business submission latches the controller until next(). */
export function createPageAgentController(state: PageAgentState, ports: AgentControllerPorts) {
  let generation = 0;
  let interaction = 0;
  let paused = false;
  let manualInvalidated = false;
  let submitNavigationHeld = false;
  let runPromise: Promise<void> | null = null;
  let action: AgentAction | null = null;
  let actionEpoch = 0;
  let submission: AgentWireResult | null = null;
  let wroteThisModule = false;
  let pendingFingerprint = '';
  let currentRoute = '';
  const log = (kind: AgentEvent['kind'], text: string, details: Partial<AgentEvent> = {}) => {
    state.events.push({ at: new Date(ports.now()).toISOString(), kind, text: text.slice(0, 600), route: currentRoute, ...details });
    if (state.events.length > 200) state.events.shift();
    ports.persist();
  };
  const assertCurrent = (owner: number) => {
    if (owner !== generation || !state.active || !state.scope) throw new Interrupted();
    ports.assertScope(state.scope);
  };
  const playPoint = async (owner: number) => {
    assertCurrent(owner);
    while (paused || document.hidden) { await ports.sleep(100); assertCurrent(owner); }
  };
  async function preview(owner: number, element: HTMLElement, seconds: number): Promise<void> {
    element.scrollIntoView?.({ block: 'center', inline: 'nearest' });
    element.classList.add('page-agent-current-control');
    try {
      for (let remaining = seconds; remaining > 0;) {
        await playPoint(owner);
        if (actionEpoch !== interaction) throw new Interrupted('页面由用户操作过，请继续后重新观察');
        state.remaining = remaining;
        await ports.sleep(1000);
        assertCurrent(owner);
        if (!paused && !document.hidden) remaining--;
      }
    } finally { state.remaining = 0; element.classList.remove('page-agent-current-control'); }
  }
  function pause(reason = '已暂停，点击“继续”后重新查看当前页面', manual = false): void {
    const wasPaused = paused;
    const firstManual = manual && !manualInvalidated;
    paused = true;
    if (firstManual) { interaction++; manualInvalidated = true; }
    if (state.phase !== 'awaiting-next' && !state.businessPending) state.phase = 'paused';
    state.stage = state.businessPending ? '当前提交正在完成，后续操作已暂停' : reason;
    if (!wasPaused || firstManual) log('pause', state.stage);
  }
  function beforeBusiness(wire: AgentWireRequest): void {
    if (!state.scope) throw new Error('AI 操作尚未绑定当前工厂');
    ports.assertScope(state.scope);
    if (!state.active || paused || state.phase !== 'acting' || action?.kind !== 'click' || actionEpoch !== interaction || wroteThisModule) {
      throw new Error('当前没有获准的原页面提交动作；请使用顶部控制继续操作');
    }
    pendingFingerprint = mutationFingerprint(wire);
    if (state.completed.some(item => item.fingerprint === pendingFingerprint)) throw new Error('本轮已完成相同提交，请核对原单据；不会自动重复创建');
    wroteThisModule = true;
    state.businessPending = true;
    state.phase = 'submitting';
    state.stage = '原页面正在提交；完成后将停留，等待“下一模块”';
    log('submission', `原页面已发起提交：${action.message}`);
  }
  function completeBusiness(result: AgentWireResult): void {
    if (!state.businessPending) return;
    const latched = state.uncertain || state.phase === 'error';
    state.businessPending = false;
    submission = result;
    if (latched) {
      state.uncertain = true; state.phase = 'error';
      log('submission', '原页面稍后返回了提交响应；先前的异常仍需核对，不会自动继续或重复提交');
      return;
    }
    // Freeze navigation immediately, before a page handler processes the response.
    state.phase = result.ok ? 'submitting' : 'error';
    state.uncertain = !result.ok;
    state.stage = result.ok ? '提交响应已收到，正在核对结果' : '提交结果未确认，已停止；请核对原单据，勿重复提交';
    if (!result.ok) state.error = state.stage;
    ports.persist();
  }
  async function loop(owner: number): Promise<void> {
    try {
      await ports.verifyIdentity();
      assertCurrent(owner);
      while (state.active && state.phase !== 'awaiting-next' && state.phase !== 'complete' && state.phase !== 'needs-input' && state.phase !== 'error') {
        await playPoint(owner);
        await ports.settled();
        assertCurrent(owner);
        if (state.actions >= 500) throw new Error('本轮已操作500步，请核对已有单据后结束本轮；不会自动重复提交');
        const epoch = interaction;
        const snapshot = ports.observe();
        currentRoute = snapshot.route;
        state.phase = 'planning';
        state.stage = 'AI 正在查看当前页面，选择下一步操作';
        const planned = await ports.plan(state.goal, snapshot, state.events.slice(-24), state.completed);
        await playPoint(owner);
        if (epoch !== interaction) continue; // A manual edit invalidates the old observation.
        ports.assertScope(state.scope!);
        if (planned.kind === 'ask') {
          state.phase = 'needs-input'; state.stage = planned.message;
          log('question', planned.message); return;
        }
        if (planned.kind === 'done') {
          state.phase = 'complete';
          state.stage = state.completed.length ? `自动操作已停止，已核对${state.completed.length}次提交；请查看原单据和操作记录。` : '自动操作已停止，本次未提交业务单据；请查看原页面和操作记录。';
          log('action', state.stage); return;
        }
        if (planned.kind === 'wait') {
          state.actions++;
          state.stage = '等待原页面加载完成';
          await ports.sleep(1000); continue;
        }
        action = planned; actionEpoch = interaction;
        state.phase = 'preview'; state.stage = planned.message;
        const control = snapshot.page.controls.find(item => item.ref === planned.target);
        const isSubmit = planned.kind === 'click' && /保存|提交|确定|确认|批准|同意|通过|结算|入库|领料|出库|发货|完成/.test(control?.label || '');
        submitNavigationHeld = isSubmit;
        const outcome = await ports.execute(snapshot, planned, async element => {
          await preview(owner, element, isSubmit ? 10 : planned.kind === 'fill' ? 2 : 1);
          await playPoint(owner);
          if (actionEpoch !== interaction) throw new Interrupted('页面已由用户修改，请继续后重新观察');
          if (planned.kind === 'click') {
            await ports.verifyIdentity();
            await playPoint(owner);
            if (actionEpoch !== interaction) throw new Interrupted('核对身份期间页面已改变，请重新查看');
          }
          state.phase = 'acting';
        });
        assertCurrent(owner);
        state.actions++;
        const actionLabel = planned.kind === 'fill' ? `填写“${outcome.label}”` : planned.kind === 'click' ? `点击“${outcome.label}”` : `打开页面：${outcome.label}`;
        log('action', actionLabel, planned.kind === 'fill' || outcome.before !== outcome.after ? { before: outcome.before, after: outcome.after } : {});
        if (outcome.path) {
          state.phase = 'acting';
          await ports.navigate(outcome.path);
          assertCurrent(owner);
        }
        // Form validation may be asynchronous; wait for the actual page and network.
        await ports.settled();
        assertCurrent(owner);
        if (wroteThisModule) {
          const deadline = ports.now() + 120000;
          while (state.businessPending) {
            if (ports.now() > deadline) throw new Error('提交仍未返回，结果待核对。不会自动重试');
            await ports.sleep(100); assertCurrent(owner);
          }
          const result = submission;
          if (state.uncertain) throw new Error(state.error || '发现额外提交或操作变化，请核对本页面结果');
          if (!result || !result.ok) throw new Error('提交结果未确认，请先核对原单据');
          const verified = await ports.readback(result);
          const readback = typeof verified === 'string' ? verified : verified.summary;
          assertCurrent(owner);
          log('readback', readback);
          state.completed.push({ moduleStep: state.moduleStep, fingerprint: pendingFingerprint, summary: readback, finishedAt: new Date(ports.now()).toISOString(), ...(typeof verified === 'string' ? {} : verified.entity) });
          state.phase = 'awaiting-next';
          state.stage = '本页面已提交并核对响应；停留展示结果，点击“下一模块”才继续';
          action = null; ports.persist(); return;
        }
        action = null; submitNavigationHeld = false;
      }
    } catch (error) {
      if (owner !== generation || !state.active) return;
      if (error instanceof Interrupted && !wroteThisModule) {
        if (!paused) { state.phase = 'planning'; return await loop(owner); }
        paused = true; state.phase = 'paused'; state.stage = error.message || '已暂停，继续后重新查看当前页面';
        return;
      }
      state.uncertain = state.uncertain || wroteThisModule;
      state.phase = 'error'; state.error = error instanceof Error ? error.message : '页面操作失败，请核对后再继续';
      state.stage = state.error;
      log('error', state.error);
    } finally { action = null; submitNavigationHeld = false; }
  }
  function run(): Promise<void> {
    if (runPromise) return runPromise.then(() => {
      if (state.active && state.phase === 'planning' && !paused) return run();
    });
    const work = loop(generation);
    runPromise = work;
    void work.finally(() => { if (runPromise === work) runPromise = null; });
    return work;
  }
  return {
    start(goal: string, scope: AgentWireScope): Promise<void> {
      if (state.active || state.businessPending) throw new Error('请先结束当前 AI 操作');
      if (!goal.trim() || goal.length > 3000) throw new Error('请填写本次任务，最多3000字');
      generation++; interaction++; paused = false; manualInvalidated = false; submission = null; wroteThisModule = false;
      Object.assign(state, emptyState(), { active: true, phase: 'planning', goal: goal.trim(), runKey: ports.makeRunKey(), scope: { ...scope } });
      log('goal', goal.trim());
      return run();
    },
    pause,
    continue(answer = ''): Promise<void> {
      if (!state.active || state.phase === 'awaiting-next' || state.phase === 'complete' || state.uncertain || state.businessPending) return Promise.resolve();
      if (state.goal.length + answer.length > 12000) { state.error = '本次任务补充较长，请先核对已有结果，再开始新任务'; return Promise.resolve(); }
      if (answer.trim()) { state.goal += `\n用户补充：${answer.trim().slice(0, 3000)}`; log('goal', `用户补充：${answer.trim()}`); interaction++; }
      paused = false; manualInvalidated = false; state.error = ''; state.phase = 'planning';
      return run();
    },
    async next(): Promise<void> {
      if (state.phase !== 'awaiting-next' || state.businessPending || state.uncertain) return Promise.resolve();
      if (runPromise) await runPromise;
      if (state.phase !== 'awaiting-next' || !state.active) return;
      state.moduleStep++; submission = null; wroteThisModule = false; paused = false; manualInvalidated = false;
      state.phase = 'planning'; state.stage = '用户已点击下一模块，继续查看原页面';
      return run();
    },
    halt(): void {
      if (state.businessPending) throw new Error('原页面提交尚未结束，请等待结果后再退出');
      generation++; paused = true; action = null; state.active = false; ports.persist();
    },
    cancelPlanning(): void {
      generation++; paused = true; action = null;
      state.uncertain = state.uncertain || state.businessPending;
      state.active = false;
      if (state.scope) { state.phase = 'error'; state.error = '页面已离开，自动操作已停止；未确认的提交请到原单据核对'; ports.persist(); }
    },
    beforeBusiness, completeBusiness,
    block(reason: string): void {
      paused = true;
      state.uncertain = state.uncertain || wroteThisModule || state.businessPending;
      state.phase = 'error'; state.error = reason; state.stage = reason;
      log('error', reason);
    },
    get action() { return action; },
    get blocksNavigation() { return state.active && (wroteThisModule || state.businessPending || state.phase === 'awaiting-next' || submitNavigationHeld); },
    get promise() { return runPromise; },
  };
}

const STORAGE = 'cretas:global-page-agent:v1';
const delay = (ms: number) => new Promise<void>(resolve => setTimeout(resolve, ms));
const BUSINESS_ROUTES = /^\/(?:procurement|sales|warehouse|production|workflow|production-analytics)(?:\/|$)/;
let router: Router | undefined;
let pendingReads = 0;
let lastRead = 0;
let controlMutation = false;
let closing = false;
let expectedNavigation: string | null = null;
let clickNavigation: string | null = null;
let savedScope: AgentWireScope | null = null;
let installed = false;
let dispose: (() => void) | undefined;
const workflowGrant = createAgentWorkflowGrant();
function row(value: unknown): Record<string, unknown> { return value !== null && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : {}; }
function unwrap(value: unknown): unknown {
  const envelope = row(value);
  if (envelope.success === false) throw new Error(String(envelope.message || '服务未确认操作结果'));
  return envelope.data ?? value;
}
function authScope(): AgentWireScope {
  const auth = useAuthStore();
  if (!auth.factoryId || !auth.user?.id || !auth.currentRole) throw new Error('请先登录工厂账号');
  return { factoryId: auth.factoryId, actorId: String(auth.user.id), role: String(auth.currentRole), sandboxId: sandboxSession.value?.sessionId };
}
function assertScope(expected: AgentWireScope): void {
  const current = authScope();
  if (current.factoryId !== expected.factoryId || current.actorId !== expected.actorId || current.role !== expected.role || current.sandboxId !== expected.sandboxId) throw new Error('账号、工厂、角色或演示轮次已改变，AI 已停止');
  if (expected.factoryId !== 'F006' || expected.actorId !== '1309' || expected.role !== 'factory_super_admin') throw new Error('当前试用仅向F006演示账号开放');
  if (expected.sandboxId && (sandboxSession.value?.status !== 'READY' || currentTokenOwnsSandbox() !== true)) throw new Error('演示沙盒未就绪或不属于当前账号');
  if (nativePlayback.enabled) throw new Error('请先结束预设流程演示，再开始全局 AI 操作');
  const permissions = usePermissionStore();
  if (!['procurement', 'sales', 'warehouse', 'inventory', 'production'].some(module => permissions.canWrite(module as ModuleName))) throw new Error('当前账号没有业务操作权限');
}
const identityBinding = createAgentIdentityBinding(() => localStorage.getItem('cretas_access_token'), authScope);
async function verifyIdentity(): Promise<void> {
  const expected = pageAgentState.scope || authScope();
  await identityBinding.verify(expected, () => request.get('/auth/me'));
  assertScope(expected);
}
function visibleLoading(): boolean {
  return Array.from(document.querySelectorAll<HTMLElement>('.app-content .el-loading-mask,.el-dialog .el-loading-mask,.el-drawer .el-loading-mask,.app-content .el-button.is-loading,.el-dialog .el-button.is-loading')).some(element => element.getBoundingClientRect().width > 0 && getComputedStyle(element).display !== 'none');
}
async function settled(): Promise<void> {
  const deadline = Date.now() + 120000;
  await nextTick(); await delay(350);
  while (pendingReads > 0 || Date.now() - lastRead < 400 || visibleLoading()) {
    if (Date.now() > deadline) throw new Error('页面资料或原生提交尚未加载完成，请核对后继续');
    await delay(100);
  }
}
async function readback(result: AgentWireResult): Promise<{ summary: string; entity?: VerifiedAgentOrder }> {
  const expected = pageAgentState.scope;
  if (!expected) throw new Error('核对结果时工厂上下文丢失');
  assertScope(expected);
  identityBinding.assertCurrent(expected);
  const reads = new Map<string, unknown>();
  const summary = await verifyPageMutation(result, expected.factoryId, async path => {
    if (!path.startsWith('/') || path.startsWith('//') || /\.\.|https?:|\\|%(?:2f|5c|2e)/i.test(path)) throw new Error('回读路径不在当前业务范围');
    assertScope(expected); identityBinding.assertCurrent(expected);
    const value = await request.get(`/${expected.factoryId}${path}`);
    assertScope(expected); identityBinding.assertCurrent(expected);
    reads.set(path, value); return value;
  });
  assertScope(expected); identityBinding.assertCurrent(expected);
  if (result.method === 'POST' && ['/purchase/orders', '/sales/orders'].includes(result.canonicalPath)) {
    const created = row(unwrap(result.body));
    const actual = row(unwrap(reads.get(`${result.canonicalPath}/${String(created.id)}`)));
    const entity: VerifiedAgentOrder = { entityId: String(actual.id), entityType: result.canonicalPath === '/purchase/orders' ? 'PURCHASE_ORDER' : 'SALES_ORDER' };
    if (entity.entityType === 'PURCHASE_ORDER') {
      if (String(actual.createdBy) !== expected.actorId || !String(actual.remark || '').startsWith(pageAgentState.runKey)) throw new Error('采购单的创建人或本轮演示标识未能核对，已停止');
      entity.createdBy = String(actual.createdBy); entity.runKey = pageAgentState.runKey;
    }
    return { summary, entity };
  }
  return { summary };
}
function persist(): void {
  if (!pageAgentState.scope) return;
  try { sessionStorage.setItem(STORAGE, JSON.stringify({ version: 1, scope: pageAgentState.scope, events: pageAgentState.events, completed: pageAgentState.completed, uncertain: pageAgentState.uncertain, phase: pageAgentState.phase })); } catch { /* do not turn quota failure into a business retry */ }
}
const controller = createPageAgentController(pageAgentState, {
  assertScope, verifyIdentity,
  observe: () => {
    if (!router) throw new Error('页面操作入口尚未就绪');
    const permissions = usePermissionStore();
    const routes = router.getRoutes().filter(route => BUSINESS_ROUTES.test(route.path) && !route.redirect && !/[:*]/.test(route.path) && route.meta.module && permissions.canAccess(String(route.meta.module) as ModuleName)).slice(0, 80).map(route => ({ path: route.path, title: String(route.meta.title || route.name || route.path) }));
    return observeAgentPage({ document, route: router.currentRoute.value.fullPath, routes, title: String(router.currentRoute.value.meta.title || document.title) });
  },
  plan: async (goal, snapshot, events, completed) => {
    const scope = pageAgentState.scope;
    if (!scope) throw new Error('缺少工厂上下文');
    const response = await parseFormInput(scope.factoryId, {
      entityType: 'UI_PAGE_ACTION',
      userInput: JSON.stringify({ goal: `${goal}\n本轮隔离演示追踪约束：新建采购订单的备注必须以“${pageAgentState.runKey}”开头，其余用户备注完整保留。该标识也用于本轮同账号演示审批，不代表普通业务的审批权限。`, location: snapshot.route, scope, page: snapshot.page,
        history: [ ...completed.map(item => ({ kind: 'verified-module', text: `第${item.moduleStep}模块已核对完成，不能重复提交：${item.summary}` })), ...events ],
      }),
      formFields: [
        { name: 'kind', title: '下一步动作：click/fill/navigate/wait/ask/done', type: 'string', required: true },
        { name: 'target', title: '当前快照中的控件或路由ref', type: 'string', required: false },
        { name: 'value', title: '填写到原输入框的文本', type: 'string', required: false },
        { name: 'message', title: '具体动作说明或需要用户补充的问题', type: 'string', required: true },
      ],
    });
    assertScope(scope);
    const parsed = response.data;
    if (!response.success || !parsed?.success) throw new Error(parsed?.message || response.message || 'AI 未返回可用的页面动作');
    const fields = { ...row(parsed.fieldValues) };
    for (const key of ['target', 'value']) if (fields[key] === null) delete fields[key];
    if (['ask', 'done', 'wait'].includes(String(fields.kind))) {
      if (fields.target === '') delete fields.target;
      if (fields.value === '') delete fields.value;
    }
    if (['click', 'navigate'].includes(String(fields.kind)) && fields.value === '') delete fields.value;
    if (typeof fields.value === 'number' && Number.isFinite(fields.value)) fields.value = String(fields.value);
    if (!fields.kind && parsed.followUpQuestion) return { kind: 'ask', message: parsed.followUpQuestion };
    return validateAgentAction(fields, snapshot);
  },
  execute: (snapshot, action, beforeAction) => {
    clickNavigation = null;
    return executeAgentAction(snapshot, action, {
    assertCurrent: () => {
      if (!pageAgentState.active || !pageAgentState.scope) throw new Interrupted();
      assertScope(pageAgentState.scope);
      if (router?.currentRoute.value.fullPath !== snapshot.route && router?.currentRoute.value.fullPath !== clickNavigation) throw new Interrupted('页面已改变，请重新观察');
    },
    beforeAction,
    });
  },
  navigate: async path => {
    if (!router || !BUSINESS_ROUTES.test(path)) throw new Error('目标不在当前业务页面范围');
    const destination = router.resolve(path).fullPath;
    expectedNavigation = destination;
    try {
      await router.push(path);
      if (router.currentRoute.value.fullPath !== destination) throw new Error('无法进入目标页面，请核对当前账号权限');
    } finally { expectedNavigation = null; }
    await nextTick();
  },
  settled, readback, sleep: delay, now: Date.now, persist,
  makeRunKey: () => `${DEMO_RUN_KEY}-${crypto.randomUUID()}`,
});

export function installPageAgent(value: Router): () => void {
  if (installed) return () => {};
  installed = true; router = value;
  // A reload never resumes an old submit. Retain its visible journal for the
  // same actor/slot and require handover before starting another task.
  if (!pageAgentState.scope) {
    try {
      const saved = row(JSON.parse(sessionStorage.getItem(STORAGE) || 'null'));
      const prior = row(saved.scope), current = authScope();
      if (saved.version === 1 && prior.factoryId === current.factoryId && prior.actorId === current.actorId && prior.role === current.role && prior.sandboxId === current.sandboxId && Array.isArray(saved.events)) {
        const kinds = new Set(['goal', 'action', 'submission', 'readback', 'pause', 'question', 'error']);
        const events: AgentEvent[] = saved.events.slice(-200).flatMap(value => {
          const item = row(value);
          if (typeof item.text !== 'string' || typeof item.at !== 'string' || !kinds.has(String(item.kind))) return [];
          return [{ at: item.at, kind: item.kind as AgentEvent['kind'], text: item.text.slice(0, 600),
            ...(typeof item.before === 'string' ? { before: item.before.slice(0, 240) } : {}),
            ...(typeof item.after === 'string' ? { after: item.after.slice(0, 240) } : {}) }];
        });
        if (events.length) {
          pageAgentState.scope = current; pageAgentState.events = events;
          pageAgentState.phase = 'error'; pageAgentState.active = false;
          pageAgentState.uncertain = Boolean(saved.uncertain) || saved.phase === 'submitting';
          pageAgentState.error = '页面已重新打开，上次自动操作已停止。请核对原单据；不会自动重试提交。';
          pageAgentState.goal = events.find(event => event.kind === 'goal')?.text || '';
        }
      }
    } catch { /* malformed or another actor's journal never grants an action */ }
  }
  const removeNetwork = installAgentTransport({
    scope: () => closing ? savedScope : pageAgentState.active ? pageAgentState.scope : null,
    allowControl: (method, path) => Boolean(controlMutation && savedScope?.sandboxId && method === 'DELETE' && path === `/api/mobile/F006/demo-sandbox/sessions/${savedScope.sandboxId}`),
    beforeBusiness: (wire, authorization) => {
      if (closing) throw new Error('AI 操作已结束，旧页面提交已阻止');
      if (!pageAgentState.scope) throw new Error('缺少工厂上下文');
      if (/^\/workflow\/instances\/[^/]+\/(?:actions|demo-actions)$/.test(wire.canonicalPath)) wire.workflow = workflowGrant.consume(wire, authScope());
      identityBinding.assertOutgoing(pageAgentState.scope, authorization);
      if (wire.method === 'POST' && wire.canonicalPath === '/purchase/orders' && !String(row(wire.payload).remark || '').startsWith(pageAgentState.runKey)) throw new Error('请先在原备注框填写本轮演示标识，再保存采购单');
      assertVerifiablePageMutation(wire);
      controller.beforeBusiness(wire);
    },
    beforePlanning: authorization => {
      if (!pageAgentState.scope) throw new Error('缺少工厂上下文');
      identityBinding.assertOutgoing(pageAgentState.scope, authorization);
    },
    completeBusiness: result => controller.completeBusiness(result),
    blocked: reason => { if (pageAgentState.active) controller.block(reason); },
    readPending: delta => { pendingReads = Math.max(0, pendingReads + delta); lastRead = Date.now(); },
    resolveUrl: sandboxRequestUrl,
  });
  const removeGuard = value.beforeEach((to, from) => {
    if (to.fullPath !== from.fullPath) workflowGrant.clear();
    if (to.path === '/login' || to.path.startsWith('/auth/')) { controller.cancelPlanning(); return true; }
    if (!pageAgentState.active || pageAgentState.phase === 'complete' || pageAgentState.phase === 'ending') return true;
    if (expectedNavigation === to.fullPath) { expectedNavigation = null; return true; }
    const allowedClick = pageAgentState.phase === 'acting' && controller.action?.kind === 'click' && !controller.blocksNavigation && BUSINESS_ROUTES.test(to.path);
    if (allowedClick) { clickNavigation = to.fullPath; return true; }
    if (to.fullPath !== from.fullPath) {
      controller.pause('已停留在当前原页面；提交后请使用“下一模块”，或结束AI后手动跳转');
      return false;
    }
    return true;
  });
  const interact = (event: Event) => {
    if (!event.isTrusted || !pageAgentState.active || (event.target instanceof Element && event.target.closest('[data-ai-page-agent]'))) return;
    controller.pause('检测到手动操作，已暂停；点击“继续”后重新查看页面', true);
  };
  document.addEventListener('pointerdown', interact, true);
  document.addEventListener('keydown', interact, true);
  document.addEventListener('input', interact, true);
  dispose = () => {
    controller.cancelPlanning();
    workflowGrant.clear();
    identityBinding.clear();
    removeNetwork(); removeGuard();
    document.removeEventListener('pointerdown', interact, true); document.removeEventListener('keydown', interact, true); document.removeEventListener('input', interact, true);
    installed = false; router = undefined;
  };
  return dispose;
}

export async function startGlobalPageAgent(goal: string, sandbox: boolean): Promise<void> {
  if (pageAgentState.scope) throw new Error('请先结束上一轮AI操作；未确认的提交必须结束演示并清空后才能重新开始');
  if (pageAgentState.active || pageAgentState.phase === 'preparing' || nativePlayback.enabled) throw new Error('请先结束当前页面操作或预设演示');
  if (!sandbox || authScope().factoryId !== 'F006' || authScope().actorId !== '1309' || authScope().role !== 'factory_super_admin') throw new Error('当前版本仅支持F006隔离演示；普通生产模式尚未开放');
  pageAgentState.phase = 'preparing'; pageAgentState.error = ''; pageAgentState.stage = '正在核对账号与操作范围';
  try {
    await verifyIdentity();
    const actor = authScope();
    if (sandbox) {
      if (actor.factoryId !== 'F006' || actor.actorId !== '1309') throw new Error('当前账号不能准备F006演示沙盒');
      await serializeSandboxLifecycle(async () => {
        let session = await beginSandbox(request);
        const sid = session.sessionId, deadline = Date.now() + 420000;
        pageAgentState.stage = '正在准备可清空的隔离演示数据';
        while (session.status !== 'READY') {
          if (session.status === 'FAILED' || session.status === 'ENDED' || Date.now() > deadline) throw new Error('沙盒尚未准备成功，请核对演示状态后重试');
          await delay(1500);
          if (authScope().actorId !== actor.actorId || authScope().factoryId !== actor.factoryId || authScope().role !== actor.role || sandboxSession.value?.sessionId !== sid) throw new Error('准备时账号或演示轮次已改变');
          session = await refreshSandbox(request);
        }
      });
    }
    const scope = authScope(); assertScope(scope);
    if (!scope.sandboxId) throw new Error('请先准备隔离演示沙盒');
    savedScope = { ...scope }; closing = false;
    // Starting authorizes this goal; every successful submission still latches next().
    void controller.start(goal, scope);
  } catch (error) {
    pageAgentState.phase = 'error'; pageAgentState.error = error instanceof Error ? error.message : '无法开始AI操作';
    throw error;
  }
}
export function pauseGlobalPageAgent(): void { controller.pause(); }
export function continueGlobalPageAgent(answer = ''): void { void controller.continue(answer); }
export function nextGlobalPageModule(): void { void controller.next(); }
export async function withPageAgentWorkflowAction<T>(approval: WorkflowPageRow, action: string, submit: (demoPurchase?: { runKey: string }) => Promise<T>): Promise<T> {
  if (!pageAgentState.active) return submit();
  try {
    const scope = pageAgentState.scope;
    if (!scope) throw new Error('当前没有原审批页面操作范围');
    assertScope(scope); identityBinding.assertCurrent(scope);
    const context = deriveAgentWorkflowContext(pageAgentState, authScope(), sandboxSession.value?.status === 'READY' && currentTokenOwnsSandbox() === true, approval, action);
    return await workflowGrant.run(context, scope, () => submit(context.demoRunKey ? { runKey: context.demoRunKey } : undefined));
  } catch (error) {
    controller.block(error instanceof Error ? error.message : '无法核对当前审批操作');
    throw error;
  }
}
export async function endGlobalPageAgent(clearSandbox = Boolean(pageAgentState.scope?.sandboxId)): Promise<void> {
  if (!clearSandbox && pageAgentState.uncertain) throw new Error('提交结果尚未确认，请先核对单据或结束演示并清空本轮，不能重新开始同一轮操作');
  if (pageAgentState.businessPending || pageAgentState.phase === 'preparing') throw new Error('当前准备或原页面提交尚未结束，请稍候再退出');
  const scope = pageAgentState.scope;
  if (!scope) { Object.assign(pageAgentState, emptyState()); return; }
  if (clearSandbox) {
    const current = authScope();
    if (current.actorId !== scope.actorId || current.factoryId !== scope.factoryId || current.sandboxId !== scope.sandboxId || currentTokenOwnsSandbox() !== true) throw new Error('不能清空其他账号或其他演示轮次');
  }
  savedScope = { ...scope }; closing = true;
  workflowGrant.clear();
  controller.halt(); pageAgentState.phase = 'ending';
  try { if (clearSandbox && scope.sandboxId) {
    await serializeSandboxLifecycle(async () => {
      if (sandboxSession.value?.sessionId !== scope.sandboxId || currentTokenOwnsSandbox() !== true) throw new Error('不能清空其他演示轮次');
      controlMutation = true;
      try {
        let session = await endSandbox(request);
        const deadline = Date.now() + 420000;
        while (session.status !== 'ENDED') {
          if (session.status === 'FAILED' || Date.now() > deadline) throw new Error('本轮清理未完成，隔离仍保留，请重试结束');
          await delay(1500); session = await refreshSandbox(request);
        }
      } finally { controlMutation = false; }
      clearEndedSandbox();
    });
  } } catch (error) {
    pageAgentState.phase = 'error'; pageAgentState.error = error instanceof Error ? error.message : '演示清理未完成，请重试';
    throw error;
  }
  // Destroy the old page's asynchronous handlers before releasing the write fence.
  sessionStorage.removeItem(STORAGE);
  window.location.replace(clearSandbox ? '/procurement/orders' : location.pathname + location.search);
}
