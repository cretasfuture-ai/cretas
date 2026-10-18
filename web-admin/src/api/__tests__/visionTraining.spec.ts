import { beforeEach, describe, expect, it, vi } from 'vitest';
import { get, post } from '../request';
import { enqueueVisionStage, getVisionDraft, getVisionTemplates } from '../visionTraining';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn() }));
beforeEach(() => vi.resetAllMocks());

describe('factory daily vision training transport', () => {
  it('uses the absolute factory mobile namespace', async () => {
    vi.mocked(get).mockResolvedValue({ success: true, data: { templates: [] }, message: 'ok' });
    await getVisionTemplates('FACTORY-A');
    expect(get).toHaveBeenCalledWith('/api/mobile/FACTORY-A/vision-training/templates', { baseURL: '' });
    await getVisionDraft('FACTORY-A', 'project/with?characters');
    expect(get).toHaveBeenLastCalledWith('/api/mobile/FACTORY-A/vision-training/drafts/project%2Fwith%3Fcharacters', { baseURL: '' });
  });

  it('submits only a registered stage and recipe ID', async () => {
    vi.mocked(post).mockResolvedValue({ success: true, data: { job_id: 'job-a' }, message: 'queued' });
    await enqueueVisionStage('FACTORY-A', 'project-a', 'train', 'recipe-a');
    expect(post).toHaveBeenCalledWith('/api/mobile/FACTORY-A/vision-training/drafts/project-a/jobs',
      { stage: 'train', recipe_id: 'recipe-a' }, { baseURL: '' });
  });

  it('fails explicitly when the service reports failure or no data', async () => {
    vi.mocked(get).mockResolvedValueOnce({ success: false, message: 'service unavailable' });
    await expect(getVisionTemplates('FACTORY-A')).rejects.toThrow('service unavailable');
    vi.mocked(get).mockResolvedValueOnce({ success: true, message: 'missing payload' });
    await expect(getVisionTemplates('FACTORY-A')).rejects.toThrow('missing payload');
  });
});
