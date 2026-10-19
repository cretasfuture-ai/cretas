import { afterEach, describe, expect, it, vi } from 'vitest';
import type { Router } from 'vue-router';

const readonlyWindow = window as Window & { __CRETAS_READONLY_HARNESS__?: unknown };

function router(): Router {
  return {
    currentRoute: { value: { matched: [{ path: '/orders' }] } },
    afterEach: vi.fn(),
  } as unknown as Router;
}

afterEach(() => {
  delete readonlyWindow.__CRETAS_READONLY_HARNESS__;
  vi.useRealTimers();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('activity tracker in a production read-only harness', () => {
  it('installs no listeners or timers and sends no telemetry with the strict pre-navigation marker', async () => {
    vi.resetModules();
    vi.useFakeTimers();
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = true;
    const send = vi.fn();
    vi.stubGlobal('fetch', send);
    const listen = vi.spyOn(document, 'addEventListener');
    const timer = vi.spyOn(window, 'setInterval');
    const instance = router();
    const tracker = await import('../activityTracker');
    tracker.installActivityTracker(instance, () => ({ factoryId: 'F006', userId: 42, isAuthenticated: true }));
    for (let i = 0; i < 25; i++) tracker.logBusinessAction('purchase.open');
    tracker.logActivityBlock('native.validation', 'quantity');
    await vi.advanceTimersByTimeAsync(30_000);
    expect(instance.afterEach).not.toHaveBeenCalled();
    expect(listen).not.toHaveBeenCalled();
    expect(timer).not.toHaveBeenCalled();
    expect(tracker.currentActionId()).toBeUndefined();
    expect(send).not.toHaveBeenCalled();
  });

  it('keeps normal authenticated telemetry enabled when the marker is absent', async () => {
    vi.resetModules();
    vi.useFakeTimers();
    const send = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', send);
    const tracker = await import('../activityTracker');
    tracker.installActivityTracker(router(), () => ({ factoryId: 'F006', userId: 42, isAuthenticated: true }));
    for (let i = 0; i < 20; i++) tracker.logBusinessAction('purchase.open');
    expect(send).toHaveBeenCalledOnce();
    expect(send.mock.calls[0]?.[0]).toBe('/api/mobile/F006/activity-events');
  });

  it('drops queued telemetry if a read-only marker is present before a later flush', async () => {
    vi.resetModules();
    vi.useFakeTimers();
    const send = vi.fn();
    vi.stubGlobal('fetch', send);
    const tracker = await import('../activityTracker');
    tracker.installActivityTracker(router(), () => ({ factoryId: 'F006', userId: 42, isAuthenticated: true }));
    tracker.logBusinessAction('purchase.open');
    readonlyWindow.__CRETAS_READONLY_HARNESS__ = true;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(send).not.toHaveBeenCalled();
    expect(tracker.currentActionId()).toBeUndefined();
    delete readonlyWindow.__CRETAS_READONLY_HARNESS__;
    await vi.advanceTimersByTimeAsync(15_000);
    expect(send).not.toHaveBeenCalled();
  });
});
