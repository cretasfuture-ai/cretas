import type { Router } from 'vue-router';
import { factoryViewContext } from '@/store/modules/platformFactoryContext';

type Source = 'screen' | 'click' | 'input' | 'block';
type Detail = Record<string, string | number | boolean>;
type ActivityEvent = { source: Source; action: string; route: string; detail?: Detail;
  eventId: string; clientActionId?: string; occurredAt: number };
type Auth = { factoryId: string; userId: number | string | undefined; isAuthenticated: boolean; accessToken?: string | null; readOnly?: boolean };

const SAFE = /^[A-Za-z0-9_.:-]{1,128}$/;
// Exact UI verbs only. Never derive an event name from arbitrary button text or form data.
const CONTROL_VERBS: Record<string, string> = {
  '搜索': 'search', '查询': 'search', '重置': 'reset', '刷新': 'refresh',
  '新建': 'create', '新增': 'create', '添加': 'add', '添加位置': 'add-location',
  '编辑': 'edit', '查看': 'view', '删除': 'delete', '保存': 'save',
  '取消': 'cancel', '确认': 'confirm', '提交': 'submit', '提交申请': 'submit-request',
  '确认拒绝': 'confirm-reject', '审批': 'approve', '通过': 'approve', '驳回': 'reject',
  '导入': 'import', '导出': 'export', '盘点任务': 'stocktake-tasks',
  '发起盘点': 'start-stocktake', '调整位置': 'move-location',
  '添加货架': 'add-rack', '绑定位置': 'bind-location',
  '应用到配置': 'apply-layout', '退出全屏': 'exit-fullscreen',
};
const DETAIL_KEYS = new Set(['field', 'result', 'reason', 'component', 'disabled',
  'count', 'length', 'status', 'entityType', 'entityId', 'unit', 'quantity', 'role']);
const queue: ActivityEvent[] = [];
let authGetter: (() => Auth) | null = null;
let activeActor = '';
let batchLimit = 20;
let disabled = false;
let retryAfter = 0;
let actionId = '';
let actionAt = 0;
let flushing = false;
let installed = false;
let activeRouter: Router | null = null;

// Set by the canonical read-only harness before application scripts execute.
// This controls optional client telemetry only; server business audit stays enabled.
function isReadonlyHarness(): boolean {
  return (window as Window & { __CRETAS_READONLY_HARNESS__?: unknown }).__CRETAS_READONLY_HARNESS__ === true;
}

function uuid(): string {
  return globalThis.crypto?.randomUUID?.() || `action-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function route(): string {
  const matched = activeRouter?.currentRoute.value.matched;
  const template = matched?.[matched.length - 1]?.path;
  return (template || '/unmatched').slice(0, 255);
}

function auth(): Auth | null {
  if (isReadonlyHarness()) {
    queue.length = 0;
    actionId = '';
    actionAt = 0;
    activeActor = '';
    return null;
  }
  const value = authGetter?.();
  if (value?.readOnly || factoryViewContext.value) {
    queue.length = 0;
    actionId = '';
    actionAt = 0;
    activeActor = '';
    return null;
  }
  const actor = value?.isAuthenticated && /^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$/.test(value.factoryId)
    && /^\d+$/.test(String(value.userId)) ? `${value.factoryId}:${value.userId}` : '';
  if (actor !== activeActor) {
    queue.length = 0;
    actionId = '';
    actionAt = 0;
    activeActor = actor;
  }
  return actor && value ? value : null;
}

function enqueue(source: Source, action: string, detail?: Detail): void {
  if (!auth() || disabled || !SAFE.test(action)) return;
  const safeDetail: Detail = {};
  for (const [key, value] of Object.entries(detail || {})) {
    if (!DETAIL_KEYS.has(key)) continue;
    if (typeof value === 'string' && SAFE.test(value)) safeDetail[key] = value;
    else if (typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) safeDetail[key] = value;
  }
  queue.push({ eventId: uuid(), source, action, route: route(), detail: safeDetail,
    clientActionId: currentActionId(), occurredAt: Date.now() });
  if (queue.length > 500) queue.shift();
  if (queue.length >= 20) void flush();
}

/** Last human click is attached to the next API request for up to five seconds. */
export function currentActionId(): string | undefined {
  return auth() && Date.now() - actionAt <= 5000 ? actionId : undefined;
}

export function logBusinessAction(action: string, detail?: Detail): void {
  if (!auth()) return;
  actionId = uuid();
  actionAt = Date.now();
  enqueue('click', action, detail);
}

export function logActivityBlock(rule: string, field?: string): void {
  enqueue('block', rule, field && SAFE.test(field) ? { field } : undefined);
}

function onClick(event: MouseEvent): void {
  if (!auth()) return;
  const target = event.target;
  if (!(target instanceof Element)) return;
  const control = target.closest('button,[role="button"],[role="tab"],a,[data-activity-action]');
  if (!control) return;
  const explicit = control.getAttribute('data-activity-action') || control.getAttribute('data-testid')
    || control.getAttribute('aria-label') || control.getAttribute('name');
  const action = resolveControlAction(control, explicit);
  actionId = uuid();
  actionAt = Date.now();
  enqueue('click', action, { component: control.tagName.toLowerCase(),
    disabled: control.hasAttribute('disabled') || control.getAttribute('aria-disabled') === 'true' });
}

export function resolveControlAction(control: Element, explicit?: string | null): string {
  if (explicit && SAFE.test(explicit)) return explicit;
  const label = control.textContent?.trim().replace(/\s+/g, '');
  const verb = label ? CONTROL_VERBS[label] : undefined;
  return verb ? `control.${verb}` : `control.${control.tagName.toLowerCase()}`;
}

function onPointerDown(event: PointerEvent): void {
  if (!auth()) return;
  const target = event.target;
  if (!(target instanceof Element)) return;
  const control = target.closest('button,[role="button"]');
  if (!control || (!control.hasAttribute('disabled') && control.getAttribute('aria-disabled') !== 'true')) return;
  const explicit = control.getAttribute('data-activity-action') || control.getAttribute('data-testid');
  const action = resolveControlAction(control, explicit);
  actionId = uuid();
  actionAt = Date.now();
  enqueue('click', action, { component: control.tagName.toLowerCase(), disabled: true });
}

function onChange(event: Event): void {
  const target = event.target;
  if (!(target instanceof HTMLInputElement || target instanceof HTMLSelectElement
      || target instanceof HTMLTextAreaElement)) return;
  const field = target.getAttribute('data-activity-field') || target.name;
  if (!field || !SAFE.test(field) || target.type === 'password' || target.type === 'hidden') return;
  // Only field identity and length are retained. Values, including business free text, stay in the business record.
  enqueue('input', 'change', { field, length: target.value.length });
}

function onInvalid(event: Event): void {
  const target = event.target;
  if (!(target instanceof HTMLInputElement || target instanceof HTMLSelectElement
      || target instanceof HTMLTextAreaElement)) return;
  const field = target.getAttribute('data-activity-field') || target.name;
  enqueue('block', 'native.validation', field && SAFE.test(field) ? { field } : undefined);
}

async function flush(): Promise<void> {
  const identity = auth();
  if (flushing || disabled || Date.now() < retryAfter || queue.length === 0 || !identity) return;
  flushing = true;
  const actor = activeActor;
  const batch = queue.splice(0, batchLimit);
  try {
    const response = await fetch(`/api/mobile/${identity.factoryId}/activity-events`, {
      method: 'POST', keepalive: true,
      headers: { 'Content-Type': 'application/json', 'X-Activity-Client': 'web-admin',
        ...(identity.accessToken ? { Authorization: `Bearer ${identity.accessToken}` } : {}) },
      body: JSON.stringify({ events: batch }),
    });
    if (response.status === 413) {
      auth();
      if (batch.length > 1) {
        batchLimit = Math.max(1, Math.floor(batch.length / 2));
        if (activeActor === actor) queue.unshift(...batch);
      }
      return;
    }
    if (response.status === 404 || response.status === 410) {
      disabled = true;
      queue.length = 0;
      return;
    }
    if (response.status === 429) retryAfter = Date.now() + 60_000;
    if (!response.ok) throw new Error('activity ingest failed');
  } catch {
    auth();
    if (activeActor === actor) queue.unshift(...batch);
    if (queue.length > 500) queue.splice(500);
  } finally {
    flushing = false;
  }
}

export function installActivityTracker(router: Router, getAuth: () => Auth): void {
  if (isReadonlyHarness()) return;
  authGetter = getAuth;
  activeRouter = router;
  if (installed) return;
  installed = true;
  router.afterEach((to) => {
    if (to.meta.requiresAuth !== false) enqueue('screen', 'enter');
  });
  document.addEventListener('click', onClick, true);
  document.addEventListener('pointerdown', onPointerDown, true);
  document.addEventListener('change', onChange, true);
  document.addEventListener('invalid', onInvalid, true);
  window.setInterval((): void => { void flush(); }, 15_000);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') void flush();
  });
}
