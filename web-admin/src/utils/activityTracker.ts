import type { Router } from 'vue-router';

type Source = 'screen' | 'click' | 'input' | 'block';
type Detail = Record<string, string | number | boolean>;
type ActivityEvent = { source: Source; action: string; route: string; detail?: Detail;
  eventId: string; clientActionId?: string; occurredAt: number };
type Auth = { factoryId: string; userId: number | string | undefined; isAuthenticated: boolean; accessToken?: string | null };

const SAFE = /^[A-Za-z0-9_.:-]{1,128}$/;
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

function uuid(): string {
  return globalThis.crypto?.randomUUID?.() || `action-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function route(): string {
  const matched = activeRouter?.currentRoute.value.matched;
  const template = matched?.[matched.length - 1]?.path;
  return (template || '/unmatched').slice(0, 255);
}

function auth(): Auth | null {
  const value = authGetter?.();
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
  const action = explicit && SAFE.test(explicit) ? explicit : `control.${control.tagName.toLowerCase()}`;
  actionId = uuid();
  actionAt = Date.now();
  enqueue('click', action, { component: control.tagName.toLowerCase(),
    disabled: control.hasAttribute('disabled') || control.getAttribute('aria-disabled') === 'true' });
}

function onPointerDown(event: PointerEvent): void {
  if (!auth()) return;
  const target = event.target;
  if (!(target instanceof Element)) return;
  const control = target.closest('button,[role="button"]');
  if (!control || (!control.hasAttribute('disabled') && control.getAttribute('aria-disabled') !== 'true')) return;
  const explicit = control.getAttribute('data-activity-action') || control.getAttribute('data-testid');
  const action = explicit && SAFE.test(explicit) ? explicit : `control.${control.tagName.toLowerCase()}`;
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
