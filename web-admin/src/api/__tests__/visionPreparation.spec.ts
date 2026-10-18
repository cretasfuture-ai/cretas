import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get, post } from '../request';
import {
  getVisionPreparation, getVisionPreparationImage, reviewVisionPreparationPhoto,
  saveVisionPreparationStandard, submitVisionPreparation, uploadVisionPreparationPhoto,
} from '../visionPreparation';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('vision preparation transport', () => {
  it('uses the fixed absolute preparation prefix and preserves the project path', async () => {
    vi.mocked(get).mockResolvedValue({ success: true, data: { revision: 1 }, message: 'ok' });
    await getVisionPreparation('工厂/A', '项目/1');
    expect(get).toHaveBeenCalledWith('/api/mobile/%E5%B7%A5%E5%8E%82%2FA/vision-training/drafts/%E9%A1%B9%E7%9B%AE%2F1/preparation', { baseURL: '' });
  });

  it('sends the revision on every write and does not add a remote image URL', async () => {
    vi.mocked(post).mockResolvedValue({ success: true, data: { revision: 2 }, message: 'ok' });
    await uploadVisionPreparationPhoto('F1', 'P1', { expectedRevision: 1, fileName: 'a.jpg', contentBase64: 'abc', purpose: 'training', captureGroup: '2026-10-15' });
    expect(post).toHaveBeenLastCalledWith('/api/mobile/F1/vision-training/drafts/P1/preparation/photos', expect.objectContaining({ expectedRevision: 1, purpose: 'training' }), { baseURL: '' });
    await getVisionPreparationImage('F1', 'P1', 'photo-1');
    expect(post).toHaveBeenLastCalledWith('/api/mobile/F1/vision-training/drafts/P1/preparation/image', { photoId: 'photo-1' }, { baseURL: '' });
    await reviewVisionPreparationPhoto('F1', 'P1', { expectedRevision: 2, photoId: 'photo-1', verdict: 'good', boxes: [{ label: '封口', x0: 0, y0: 0, x1: 1, y1: 1 }] });
    expect(post).toHaveBeenLastCalledWith('/api/mobile/F1/vision-training/drafts/P1/preparation/review', expect.objectContaining({ expectedRevision: 2 }), { baseURL: '' });
    await saveVisionPreparationStandard('F1', 'P1', { expectedRevision: 3, passCriteria: '完整', failCriteria: '破损', maxMissRate: 2, maxFalseAlarmRate: 3, confirmed: true });
    await submitVisionPreparation('F1', 'P1', 4);
    expect(post).toHaveBeenLastCalledWith('/api/mobile/F1/vision-training/drafts/P1/preparation/submit', { expectedRevision: 4, confirmed: true }, { baseURL: '' });
  });

  it('keeps the original service message when the envelope fails', async () => {
    vi.mocked(get).mockResolvedValue({ success: false, data: undefined, message: '当前资料已被修改，请刷新' });
    await expect(getVisionPreparation('F1', 'P1')).rejects.toThrow('当前资料已被修改，请刷新');
  });
});
