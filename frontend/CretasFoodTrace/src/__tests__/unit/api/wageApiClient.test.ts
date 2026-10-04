import wageApiClient from '../../../services/api/wageApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({ apiClient: { get: jest.fn() } }));
jest.mock('../../../utils/factoryIdHelper', () => ({ getCurrentFactoryId: (id?: string) => id || 'F006' }));

describe('wageApiClient labor cost readback', () => {
  beforeEach(() => jest.clearAllMocks());

  it('keeps masked total wage null and legal zero counts without deriving a rate', async () => {
    (apiClient.get as jest.Mock).mockResolvedValue({ success: true, data: {
      totalWage: null, totalPieceRateWage: null, totalPieceCount: 0, workerCount: 0,
      costPerPiece: null, averageEfficiency: null, avgPiecesPerWorker: null, avgWagePerWorker: null,
      byEfficiencyRating: { GOOD: { count: 1, totalWage: null } }, byProcessStage: {},
    } });
    await expect(wageApiClient.analyzeLaborCost('2026-10-01', '2026-10-04', 'F006'))
      .resolves.toMatchObject({ totalWage: null, totalPieceCount: 0, workerCount: 0, costPerPiece: null, byEfficiencyRating: { GOOD: { count: 1, totalWage: null } } });
    expect(apiClient.get).toHaveBeenCalledWith('/api/mobile/F006/wage/analysis/labor-cost', {
      params: { periodStart: '2026-10-01', periodEnd: '2026-10-04' },
    });
  });

  it('rejects false-success and malformed payloads instead of returning zero data', async () => {
    (apiClient.get as jest.Mock).mockResolvedValueOnce({ success: false, message: '403 denied' });
    await expect(wageApiClient.analyzeLaborCost('2026-10-01', '2026-10-04', 'F006')).rejects.toThrow('403 denied');
    (apiClient.get as jest.Mock).mockResolvedValueOnce({ success: true, data: { totalWage: 0, totalPieceCount: 'bad', workerCount: 0 } });
    await expect(wageApiClient.analyzeLaborCost('2026-10-01', '2026-10-04', 'F006')).rejects.toThrow('格式无效');
  });

  it.each([
    { totalWage: -1, totalPieceCount: 0, workerCount: 0 },
    { totalWage: 0, totalPieceCount: 0.5, workerCount: 0 },
    { totalWage: 0, totalPieceCount: 0, workerCount: -1 },
    { totalWage: 0, totalPieceCount: 0, workerCount: 0.5 },
    { totalWage: " ", totalPieceCount: 0, workerCount: 0 },
  ])('rejects negative wage or non-integer counts', async (data) => {
    (apiClient.get as jest.Mock).mockResolvedValue({ success: true, data: { totalPieceRateWage: 0, costPerPiece: null, averageEfficiency: null, avgPiecesPerWorker: null, avgWagePerWorker: null, byEfficiencyRating: {}, byProcessStage: {}, ...data } });
    await expect(wageApiClient.analyzeLaborCost('2026-10-01', '2026-10-04', 'F006')).rejects.toThrow('格式无效');
  });
});
