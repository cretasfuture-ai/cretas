import { beforeEach, expect, it, vi } from 'vitest';
import request from '@/api/request';
import { indicatorsApi } from '@/api/canvasIndicators';

// 批次溯源跳数: 每道工序占 2 跳, 10 道工序的首道原料在 depth 19。
// 原来前端缺省写死 5 ⇒ 只看得到最后两道工序。缺省必须交给后端 (= 上限 50, 整条链)。
vi.mock('@/api/request', () => ({ default: { get: vi.fn() } }));

beforeEach(() => {
  vi.mocked(request.get).mockReset();
  vi.mocked(request.get).mockResolvedValue({ success: true, data: null, message: '' });
});

it('omits maxDepth when the caller did not ask for one (backend default = full chain)', async () => {
  await indicatorsApi.batchLineage('F006', 'PRODUCTION_BATCH', '10898');
  expect(request.get).toHaveBeenCalledWith('/F006/canvas-indicators/batch-lineage/PRODUCTION_BATCH/10898', { params: {} });
});

it('passes an explicit depth above the old cap of 10 through unchanged', async () => {
  await indicatorsApi.batchLineage('F006', 'PRODUCTION_BATCH', '10898', 30);
  expect(request.get).toHaveBeenCalledWith('/F006/canvas-indicators/batch-lineage/PRODUCTION_BATCH/10898', { params: { maxDepth: 30 } });
});
