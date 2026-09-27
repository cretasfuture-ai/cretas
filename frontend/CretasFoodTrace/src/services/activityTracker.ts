import { AppState } from 'react-native';
import { API_BASE_URL } from '../constants/config';
import { useAuthStore } from '../store/authStore';
import { StorageService } from './storage/storageService';

type Source = 'screen' | 'click' | 'input' | 'block';
type Detail = Record<string, string | number | boolean>;
type Event = { source: Source; action: string; route: string; detail?: Detail;
  eventId: string; clientActionId?: string; occurredAt: number };
type Touch = { pageX?: number; pageY?: number };
type Fiber = { memoizedProps?: Record<string, unknown>; return?: Fiber | null;
  type?: { displayName?: string; name?: string } | string | null };

const SAFE = /^[A-Za-z0-9_.:-]{1,128}$/;
const DETAIL_KEYS = new Set(['field', 'result', 'reason', 'component', 'disabled',
  'count', 'length', 'status', 'entityType', 'entityId', 'unit', 'quantity', 'role']);
const queue: Event[] = [];
let screen = 'Main';
let factory = '';
let actionId = '';
let actionAt = 0;
let touchStart: { x: number; y: number; at: number } | null = null;
let flushing = false;
let started = false;
let batchLimit = 20;
let disabled = false;
let retryAfter = 0;
const restored = new Set<string>();
const MAX_AGE_MS = 86_400_000;

function fresh(event: Event): boolean {
  return typeof event.eventId === 'string' && SAFE.test(event.eventId)
    && typeof event.occurredAt === 'number'
    && event.occurredAt >= Date.now() - MAX_AGE_MS && event.occurredAt <= Date.now() + MAX_AGE_MS;
}

async function restore(key: string): Promise<void> {
  try {
    const saved = await StorageService.getItem(`activity_events:${key}`);
    if (!saved || factory !== key) return;
    const items: unknown = JSON.parse(saved);
    if (Array.isArray(items)) {
      for (const item of items.slice(-300)) {
        if (item && typeof item === 'object' && 'source' in item && 'action' in item && 'route' in item
            && fresh(item as Event)) {
          queue.push(item as Event);
        }
      }
    }
    await StorageService.removeItem(`activity_events:${key}`);
  } catch {
    // Telemetry cannot interrupt the app.
  }
}

async function persist(): Promise<void> {
  if (!factory) return;
  try {
    const valid = queue.filter(fresh);
    queue.splice(0, queue.length, ...valid);
    if (queue.length) await StorageService.setItem(`activity_events:${factory}`, JSON.stringify(queue.slice(-300)));
    else await StorageService.removeItem(`activity_events:${factory}`);
  }
  catch { /* Telemetry cannot interrupt the app. */ }
}

function uuid(): string {
  return globalThis.crypto?.randomUUID?.() || `action-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

function identity(): { factoryId: string; userId: number } | null {
  const state = useAuthStore.getState();
  const factoryId = state.getFactoryId();
  const userId = state.getUserId();
  if (!state.isAuthenticated || !factoryId || !userId) {
    if (factory) void StorageService.removeItem(`activity_events:${factory}`);
    factory = '';
    queue.length = 0;
    actionId = '';
    actionAt = 0;
    return null;
  }
  if (factory !== `${factoryId}:${userId}`) {
    if (factory) void StorageService.removeItem(`activity_events:${factory}`);
    queue.length = 0;
    actionId = '';
    actionAt = 0;
    factory = `${factoryId}:${userId}`;
    if (!restored.has(factory)) {
      restored.add(factory);
      void restore(factory);
    }
  }
  return { factoryId, userId };
}

function enqueue(source: Source, action: string, detail?: Detail): void {
  if (!identity() || disabled || !SAFE.test(action)) return;
  const safeDetail: Detail = {};
  for (const [key, value] of Object.entries(detail || {})) {
    if (!DETAIL_KEYS.has(key)) continue;
    if (typeof value === 'string' && SAFE.test(value)) safeDetail[key] = value;
    else if (typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) safeDetail[key] = value;
  }
  queue.push({ eventId: uuid(), source, action, route: `/${screen}`, detail: safeDetail,
    clientActionId: currentActionId(), occurredAt: Date.now() });
  if (queue.length > 500) queue.shift();
  if (queue.length >= 20) void flush();
}

export function currentActionId(): string | undefined {
  return identity() && Date.now() - actionAt <= 5000 ? actionId : undefined;
}

export function logScreen(name: string): void {
  if (!SAFE.test(name) || name === screen) return;
  screen = name;
  enqueue('screen', 'enter');
}

export function logBusinessAction(action: string, detail?: Detail): void {
  if (!identity()) return;
  actionId = uuid();
  actionAt = Date.now();
  enqueue('click', action, detail);
}

export function logActivityBlock(rule: string, field?: string): void {
  enqueue('block', rule, field && SAFE.test(field) ? { field } : undefined);
}

export function beginTouch(event: { nativeEvent?: Touch }): void {
  const point = event.nativeEvent;
  if (typeof point?.pageX !== 'number' || typeof point.pageY !== 'number') return;
  touchStart = { x: point.pageX, y: point.pageY, at: Date.now() };
}

/** Read only stable component metadata; typed text and rendered child text are never inspected. */
export function endTouch(event: { nativeEvent?: Touch; _targetInst?: Fiber }): void {
  const first = touchStart;
  touchStart = null;
  const point = event.nativeEvent;
  if (!first || typeof point?.pageX !== 'number' || typeof point.pageY !== 'number'
      || Date.now() - first.at > 600
      || Math.hypot(first.x - point.pageX, first.y - point.pageY) > 12) return;
  let node = event._targetInst;
  for (let depth = 0; node && depth < 25; depth++, node = node.return || undefined) {
    const props = node.memoizedProps;
    if (!props || (typeof props.onPress !== 'function' && props.disabled !== true)) continue;
    const named = props.testID;
    const type = node.type;
    const component = typeof type === 'string' ? type : type?.displayName || type?.name || 'pressable';
    const label = typeof named === 'string' && SAFE.test(named) ? named : `control.${component}`;
    if (!SAFE.test(label)) return;
    logBusinessAction(label, { component: SAFE.test(component) ? component : 'pressable',
      disabled: props.disabled === true });
    return;
  }
}

async function flush(): Promise<void> {
  const actor = identity();
  if (flushing || disabled || Date.now() < retryAfter || queue.length === 0 || !actor) return;
  flushing = true;
  queue.splice(0, queue.length, ...queue.filter(fresh));
  if (queue.length === 0) { flushing = false; await persist(); return; }
  const batch = queue.splice(0, batchLimit);
  try {
    const token = await StorageService.getSecureItem('secure_access_token');
    if (!token) throw new Error('no token');
    const response = await fetch(`${API_BASE_URL}/api/mobile/${actor.factoryId}/activity-events`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}`,
        'X-Activity-Client': 'app' },
      body: JSON.stringify({ events: batch }),
    });
    if (response.status === 413) {
      identity();
      if (batch.length > 1) {
        batchLimit = Math.max(1, Math.floor(batch.length / 2));
        if (factory === `${actor.factoryId}:${actor.userId}`) queue.unshift(...batch);
      }
      await persist();
      return;
    }
    if (response.status === 404 || response.status === 410) {
      disabled = true;
      queue.length = 0;
      await persist();
      return;
    }
    if (response.status === 429) retryAfter = Date.now() + 60_000;
    if (!response.ok) throw new Error('ingest failed');
    await persist();
  } catch {
    identity();
    if (factory === `${actor.factoryId}:${actor.userId}`) queue.unshift(...batch);
    if (queue.length > 500) queue.splice(500);
    await persist();
  } finally {
    flushing = false;
  }
}

export function startActivityTracker(): void {
  if (started) return;
  started = true;
  useAuthStore.subscribe(() => { identity(); });
  identity();
  setInterval(() => void flush(), 15_000);
  AppState.addEventListener('change', state => {
    if (state === 'background' || state === 'inactive') {
      void persist();
      void flush();
    }
  });
}
