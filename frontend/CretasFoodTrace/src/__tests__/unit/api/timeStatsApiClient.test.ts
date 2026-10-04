import { timeStatsApiClient } from '../../../services/api/timeStatsApiClient';
import { apiClient } from '../../../services/api/apiClient';
import { periodDates } from '../../../utils/laborCostData';

jest.mock('../../../services/api/apiClient', () => ({ apiClient: { get: jest.fn() } }));
jest.mock('../../../utils/factoryIdHelper', () => ({ getCurrentFactoryId: (id?: string) => id || 'F006' }));

describe('timeStatsApiClient labor cost readback', () => {
  beforeEach(() => jest.clearAllMocks());

  it.each([
    ['2026-09-28', 2026, 40],
    ['2023-01-02', 2023, 2],
    ['2034-01-02', 2034, 2],
    ['2023-01-01', 2023, 1],
    ['2025-12-29', 2025, 53],
    ['2024-01-01', 2024, 1],
  ])('serializes %s using the backend Monday/minimum-one-day week contract', async (date, year, week) => {
    jest.mocked(apiClient.get).mockResolvedValue({ success: true, data: {} });
    await timeStatsApiClient.getWeeklyStats(date, 'F006');
    expect(apiClient.get).toHaveBeenCalledWith('/api/mobile/F006/time-stats/weekly', { params: { year, week } });
  });

  it.each(['2026-02-30', 'invalid', '2026-10-04T00:00:00Z'])('rejects invalid weekly date %s before a request', async (date) => {
    await expect(timeStatsApiClient.getWeeklyStats(date, 'F006')).rejects.toThrow('日期格式无效');
    expect(apiClient.get).not.toHaveBeenCalled();
  });

  it('uses Monday as the concrete week start', () => {
    expect(periodDates('week', new Date(2026, 9, 7))).toEqual({ startDate: '2026-10-05', endDate: '2026-10-07' });
  });

  it('uses the factory, inclusive date window, topN and preserves legal zeroes', async () => {
    (apiClient.get as jest.Mock)
      .mockResolvedValueOnce({ success: true, data: { totalHours: 0, activeWorkers: 0, startDate: '2026-10-01', endDate: '2026-10-04' } })
      .mockResolvedValueOnce({ success: true, data: { departmentStats: { 生产部: { totalHours: 0 } } } })
      .mockResolvedValueOnce({ success: true, data: [{ workerId: 7, workerName: '张三', department: '生产部', totalHours: 0 }] });

    await expect(timeStatsApiClient.getLaborCostSummary({ period: 'month', startDate: '2026-10-01', endDate: '2026-10-04', factoryId: 'F006' }))
      .resolves.toMatchObject({ totalCost: null, totalWorkMinutes: 0, participatingEmployees: 0 });
    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/api/mobile/F006/time-stats/daily/range', {
      params: { startDate: '2026-10-01', endDate: '2026-10-04' },
    });
    await expect(timeStatsApiClient.getCostByDepartment({ startDate: '2026-10-01', endDate: '2026-10-04', factoryId: 'F006' }))
      .resolves.toEqual([{ departmentId: '1', departmentName: '生产部', totalHours: 0 }]);
    await expect(timeStatsApiClient.getWorkerHoursRank({ startDate: '2026-10-01', endDate: '2026-10-04', limit: 1, factoryId: 'F006' }))
      .resolves.toEqual([{ rank: 1, userId: 7, userName: '张三', department: '生产部', totalMinutes: 0, totalHours: 0 }]);

    expect(apiClient.get).toHaveBeenNthCalledWith(3, '/api/mobile/F006/time-stats/workers', {
      params: { startDate: '2026-10-01', endDate: '2026-10-04', topN: 1 },
    });
  });

  it.each([
    ['summary', () => timeStatsApiClient.getLaborCostSummary({ factoryId: 'F006' }), { success: false, message: 'denied' }],
    ['department', () => timeStatsApiClient.getCostByDepartment({ factoryId: 'F006' }), { success: true, data: {} }],
    ['workers', () => timeStatsApiClient.getWorkerHoursRank({ factoryId: 'F006' }), { success: true, data: [{ workerId: 7, workerName: 'x' }] }],
  ])('fails closed on %s false-success or malformed response', async (_name, call, response) => {
    (apiClient.get as jest.Mock).mockResolvedValue(response);
    await expect(call()).rejects.toThrow(/denied|格式无效/);
  });

  it.each([
    { totalHours: -1, activeWorkers: 1 },
    { totalHours: "", activeWorkers: 1 },
    { totalHours: " ", activeWorkers: 1 },
    { totalHours: 1, activeWorkers: 1.5 },
  ])('rejects invalid summary wire values', async (values) => {
    (apiClient.get as jest.Mock).mockResolvedValue({ success: true, data: { ...values, startDate: '2026-10-01', endDate: '2026-10-04' } });
    await expect(timeStatsApiClient.getLaborCostSummary({ startDate: '2026-10-01', endDate: '2026-10-04', factoryId: 'F006' })).rejects.toThrow('格式无效');
  });

  it.each([
    { workerId: 0, workerName: 'x', totalHours: 1 },
    { workerId: 1.5, workerName: 'x', totalHours: 1 },
    { workerId: 1, workerName: ' ', totalHours: 1 },
    { workerId: 1, workerName: 'x', totalHours: -1 },
  ])('rejects invalid worker wire values', async (worker) => {
    (apiClient.get as jest.Mock).mockResolvedValue({ success: true, data: [worker] });
    await expect(timeStatsApiClient.getWorkerHoursRank({ factoryId: 'F006' })).rejects.toThrow('格式无效');
  });
});
