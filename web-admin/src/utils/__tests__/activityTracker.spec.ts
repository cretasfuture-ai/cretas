import { describe, expect, it, vi } from 'vitest';
import type { Router } from 'vue-router';
import { installActivityTracker, logBusinessAction } from '../activityTracker';

describe('activity tracker', () => {
  it('batches authenticated actions with stable route and removes unknown detail keys', async () => {
    const send = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', send);
    const router = {
      currentRoute: { value: { matched: [{ path: '/orders/:id' }] } },
      afterEach: vi.fn(),
    } as unknown as Router;
    installActivityTracker(router, () => ({ factoryId: 'F006', userId: 42, isAuthenticated: true, accessToken: 'test-token' }));

    const details: Record<string, string | number | boolean> =
      { quantity: 3, customerName: 'Alice', field: 'quantity' };
    for (let i = 0; i < 20; i++) logBusinessAction('purchase.submit', details);
    await vi.waitFor(() => expect(send).toHaveBeenCalledOnce());
    const [url, options] = send.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/mobile/F006/activity-events');
    const batch = JSON.parse(String(options.body)) as { events: Array<Record<string, unknown>> };
    expect(batch.events).toHaveLength(20);
    expect(batch.events[0]).toMatchObject({ source: 'click', action: 'purchase.submit', route: '/orders/:id' });
    expect(batch.events[0].detail).toEqual({ quantity: 3, field: 'quantity' });
    expect(String(options.body)).not.toContain('Alice');
  });

  it('captures a disabled control through pointerdown even when click is suppressed', async () => {
    const send = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', send);
    const button = document.createElement('button');
    button.disabled = true;
    button.dataset.testid = 'warehouse.confirm';
    document.body.appendChild(button);
    button.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    for (let i = 0; i < 19; i++) logBusinessAction('other.action');
    await vi.waitFor(() => expect(send).toHaveBeenCalledOnce());
    const batch = JSON.parse(String((send.mock.calls[0] as [string, RequestInit])[1].body)) as { events: Array<{ action: string; detail: Record<string, unknown> }> };
    expect(batch.events[0]).toMatchObject({ action: 'warehouse.confirm', detail: { disabled: true } });
    button.remove();
  });

  it('does not emit client events without an authenticated factory', () => {
    const send = vi.fn();
    vi.stubGlobal('fetch', send);
    const router = {
      currentRoute: { value: { matched: [{ path: '/orders' }] } },
      afterEach: vi.fn(),
    } as unknown as Router;
    installActivityTracker(router, () => ({ factoryId: 'F006', userId: 42, isAuthenticated: false }));
    for (let i = 0; i < 25; i++) logBusinessAction('purchase.submit');
    expect(send).not.toHaveBeenCalled();
  });

  it('discards queued actions when another user logs into the same factory', async () => {
    const send = vi.fn().mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', send);
    let userId = 101;
    const router = {
      currentRoute: { value: { matched: [{ path: '/orders' }] } },
      afterEach: vi.fn(),
    } as unknown as Router;
    installActivityTracker(router, () => ({ factoryId: 'F006', userId, isAuthenticated: true }));
    logBusinessAction('first.user.action');
    userId = 102;
    for (let i = 0; i < 20; i++) logBusinessAction('second.user.action');
    await vi.waitFor(() => expect(send).toHaveBeenCalledOnce());
    const batch = JSON.parse(String((send.mock.calls[0] as [string, RequestInit])[1].body)) as { events: Array<{ action: string }> };
    expect(batch.events).toHaveLength(20);
    expect(batch.events.every(event => event.action === 'second.user.action')).toBe(true);
  });

  it('splits a 413 batch and retries with the same event IDs', async () => {
    const send = vi.fn()
      .mockResolvedValueOnce(new Response('{}', { status: 413 }))
      .mockResolvedValue(new Response('{}', { status: 200 }));
    vi.stubGlobal('fetch', send);
    const router = {
      currentRoute: { value: { matched: [{ path: '/orders' }] } },
      afterEach: vi.fn(),
    } as unknown as Router;
    installActivityTracker(router, () => ({ factoryId: 'F006', userId: 103, isAuthenticated: true }));
    for (let i = 0; i < 20; i++) logBusinessAction('order.open');
    await vi.waitFor(() => expect(send).toHaveBeenCalledOnce());
    logBusinessAction('order.open');
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2));
    const first = JSON.parse(String((send.mock.calls[0] as [string, RequestInit])[1].body)) as { events: Array<{ eventId: string }> };
    const second = JSON.parse(String((send.mock.calls[1] as [string, RequestInit])[1].body)) as { events: Array<{ eventId: string }> };
    expect(second.events).toHaveLength(10);
    expect(second.events.map(event => event.eventId)).toEqual(first.events.slice(0, 10).map(event => event.eventId));
  });

  it('stops collecting when an older backend returns 404', async () => {
    const send = vi.fn().mockResolvedValue(new Response('{}', { status: 404 }));
    vi.stubGlobal('fetch', send);
    const router = {
      currentRoute: { value: { matched: [{ path: '/orders' }] } },
      afterEach: vi.fn(),
    } as unknown as Router;
    installActivityTracker(router, () => ({ factoryId: 'F006', userId: 104, isAuthenticated: true }));
    for (let i = 0; i < 20; i++) logBusinessAction('order.open');
    await vi.waitFor(() => expect(send).toHaveBeenCalledOnce());
    await Promise.resolve();
    for (let i = 0; i < 20; i++) logBusinessAction('order.open');
    expect(send).toHaveBeenCalledOnce();
  });
});
