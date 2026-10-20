import { webcrypto } from 'node:crypto';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const { get, post } = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../request', () => ({ default: { get, post } }));
import { confirmIntentAction, getPurchaseTaskGrantStatus } from '../smartbi/intent-chat';

const proof = { commandDigest: 'd'.repeat(64), expiresAt: '2026-10-20T04:00:00Z' };
const outcome = {
  grantId: 'preview-123', state: 'DRAFT', purchaseOrderId: 'PO-ONE',
  grossAmount: 22.6, quantity: 2, version: 2, replay: true,
};

describe('controlled purchase confirmation and readonly recovery client', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    vi.stubGlobal('crypto', webcrypto);
  });
  afterEach(() => vi.unstubAllGlobals());

  it('keeps the same attempt after response loss without putting the proof token in the body', async () => {
    post.mockRejectedValueOnce(new Error('response lost')).mockResolvedValueOnce({ data: { status: 'SUCCESS' } });
    await expect(confirmIntentAction('F006', 'private-proof-token', proof)).rejects.toThrow('response lost');
    await confirmIntentAction('F006', 'private-proof-token', proof);
    expect(post).toHaveBeenCalledTimes(2);
    expect(post.mock.calls[0]).toEqual(post.mock.calls[1]);
    const [path, body, config] = post.mock.calls[0];
    expect(path).toBe('/F006/ai-intents/confirm');
    expect(Object.keys(body).sort()).toEqual(['commandDigest', 'expiresAt', 'idempotencyKey', 'requestId']);
    expect(body.idempotencyKey).toMatch(/^confirm-[a-f0-9]{64}$/);
    expect(body.requestId).toBe(body.idempotencyKey);
    expect(JSON.stringify(body)).not.toContain('private-proof-token');
    expect(config.headers).toEqual({ 'X-Cretas-Confirmation-Token': 'private-proof-token' });
  });

  it('separates attempts by tenant, token and the complete approved snapshot', async () => {
    post.mockResolvedValue({ data: { status: 'SUCCESS' } });
    await confirmIntentAction('F006', 'token-1', proof);
    await confirmIntentAction('F007', 'token-1', proof);
    await confirmIntentAction('F006', 'token-2', proof);
    await confirmIntentAction('F006', 'token-1', { ...proof, commandDigest: 'e'.repeat(64) });
    await confirmIntentAction('F006', 'token-1', { ...proof, expiresAt: '2026-10-20T04:01:00Z' });
    expect(new Set(post.mock.calls.map((call) => call[1].idempotencyKey)).size).toBe(5);
  });

  it('fails before sending when secure attempt identity cannot be generated', async () => {
    vi.stubGlobal('crypto', {});
    await expect(confirmIntentAction('F006', 'token-1', proof)).rejects.toThrow('无法安全确认');
    expect(post).not.toHaveBeenCalled();
  });

  it('reads the actual same-reference status envelope through GET only', async () => {
    get.mockResolvedValue({ success: true, data: outcome });
    expect(await getPurchaseTaskGrantStatus('F006', 'preview-123')).toEqual(outcome);
    expect(get).toHaveBeenCalledWith('/F006/ai-purchase-task-grants/preview-123');
    expect(post).not.toHaveBeenCalled();
  });

  it('accepts an already-unwrapped outcome but rejects a different reference or malformed result', async () => {
    get.mockResolvedValueOnce(outcome);
    expect(await getPurchaseTaskGrantStatus('F006', 'preview-123')).toEqual(outcome);
    for (const data of [{ ...outcome, grantId: 'preview-other' }, { grantId: 'preview-123' }, null]) {
      get.mockResolvedValueOnce({ data });
      await expect(getPurchaseTaskGrantStatus('F006', 'preview-123')).rejects.toThrow('无法核对');
    }
    get.mockResolvedValueOnce({ success: false, data: outcome });
    await expect(getPurchaseTaskGrantStatus('F006', 'preview-123')).rejects.toThrow('查询失败');
    expect(post).not.toHaveBeenCalled();
  });

  it('rejects missing scope before GET and encodes identifiers as a single path component', async () => {
    await expect(getPurchaseTaskGrantStatus('', 'preview-123')).rejects.toThrow('缺少');
    await expect(getPurchaseTaskGrantStatus('F006', '')).rejects.toThrow('缺少');
    expect(get).not.toHaveBeenCalled();
    get.mockResolvedValue({ ...outcome, grantId: 'preview/123' });
    await getPurchaseTaskGrantStatus('F006', 'preview/123');
    expect(get).toHaveBeenCalledWith('/F006/ai-purchase-task-grants/preview%2F123');
  });
});
