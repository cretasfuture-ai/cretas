import { describe, expect, it, vi } from 'vitest';
const post = vi.hoisted(() => vi.fn().mockResolvedValue({ success: true }));
vi.mock('../request', () => ({ default: { post } }));
import { aiApplyDiffs } from '../canvasApi';

describe('Canvas client/server result protocol', () => {
  it('opts into structured results without changing the diff payload', async () => {
    const diffs = [{ tool: 'canvas_test', params: { enabled: true } }];
    await aiApplyDiffs('LOCAL', diffs);
    expect(post).toHaveBeenCalledWith('/LOCAL/config/v2/ai/apply-diffs', diffs,
      { headers: { 'X-Canvas-Result-Protocol': '2' } });
  });
});
