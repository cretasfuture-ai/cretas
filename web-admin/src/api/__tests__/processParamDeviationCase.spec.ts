import { beforeEach, describe, expect, it, vi } from 'vitest';

const request = vi.hoisted(() => ({ get: vi.fn(), post: vi.fn() }));
vi.mock('../request', () => request);

import {
  assignProcessParamCase,
  getProcessParamCase,
  listProcessParamCaseAssignees,
  listProcessParamCases,
  resolveProcessParamCase,
  startProcessParamCaseVerify,
} from '../processParamDeviationCase';

describe('process parameter deviation case API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses 0-based list paging and the source-specific route', async () => {
    request.get.mockResolvedValue({ success: true, data: [] });
    await listProcessParamCases('F006');
    expect(request.get).toHaveBeenCalledWith(
      '/F006/quality/process-param-deviation/cases',
      { params: { openOnly: true, page: 0, size: 50 } },
    );
  });

  it('reads assignees and detail within the factory scope', async () => {
    request.get.mockResolvedValue({ success: true, data: [] });
    await listProcessParamCaseAssignees('F006');
    await getProcessParamCase('F006', 'case/1');
    expect(request.get).toHaveBeenNthCalledWith(1, '/F006/quality/process-param-deviation/cases/assignees');
    expect(request.get).toHaveBeenNthCalledWith(2, '/F006/quality/process-param-deviation/cases/case%2F1');
  });

  it('sends versioned write requests and resolves with the four backend enums', async () => {
    request.post.mockResolvedValue({ success: true, data: null });
    await assignProcessParamCase('F006', 'case-1', { ownerUserId: 7, version: 3 });
    await startProcessParamCaseVerify('F006', 'case-1', { version: 4 });
    await resolveProcessParamCase('F006', 'case-1', {
      resolution: 'STANDARD_UPDATE', note: '更新标准后复核', version: 5,
    });
    expect(request.post).toHaveBeenNthCalledWith(1, '/F006/quality/process-param-deviation/cases/case-1/assign', {
      ownerUserId: 7, version: 3,
    });
    expect(request.post).toHaveBeenNthCalledWith(2, '/F006/quality/process-param-deviation/cases/case-1/start-verify', {
      version: 4,
    });
    expect(request.post).toHaveBeenNthCalledWith(3, '/F006/quality/process-param-deviation/cases/case-1/resolve', {
      resolution: 'STANDARD_UPDATE', note: '更新标准后复核', version: 5,
    });
  });
});
