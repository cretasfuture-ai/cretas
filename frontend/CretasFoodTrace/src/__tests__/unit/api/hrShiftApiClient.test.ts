import { hrShiftApiClient } from '../../../services/api/hrShiftApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
    apiClient: {
      get: jest.fn(),
    post: jest.fn(),
    delete: jest.fn(),
  },
}));
jest.mock('../../../utils/factoryIdHelper', () => ({
  getCurrentFactoryId: (factoryId?: string) => factoryId || 'F006',
}));

describe('hrShiftApiClient', () => {
  beforeEach(() => jest.clearAllMocks());

  it('reads the HR assignment and active shift endpoints with factory-scoped params', async () => {
    (apiClient.get as jest.Mock)
      .mockResolvedValueOnce({ success: true, data: [{ id: 'a1', userId: 7, workDate: '2026-10-04', shiftId: 's1' }] })
      .mockResolvedValueOnce({ success: true, data: [{ id: 's1', factoryId: 'F006', shiftCode: 'DAY', shiftName: '白班', startTime: '08:00', endTime: '17:00' }] });

    await expect(hrShiftApiClient.listAssignments({
      startDate: '2026-10-04', endDate: '2026-10-04', userId: 7, factoryId: 'F006',
    })).resolves.toHaveLength(1);
    await expect(hrShiftApiClient.listActiveShifts('F006')).resolves.toHaveLength(1);

    expect(apiClient.get).toHaveBeenNthCalledWith(1, '/api/mobile/F006/hr/shift-assignments', {
      params: { startDate: '2026-10-04', endDate: '2026-10-04', userId: 7 },
    });
    expect(apiClient.get).toHaveBeenNthCalledWith(2, '/api/mobile/F006/hr/attendance-shifts', {
      params: { activeOnly: true },
    });
  });

  it('keeps single-day write payloads exact and exposes server failures', async () => {
    (apiClient.post as jest.Mock).mockResolvedValue({ success: true, data: { id: 'a1' } });
    (apiClient.delete as jest.Mock).mockResolvedValue({ success: true });

    await hrShiftApiClient.assign({ userId: 7, workDate: '2026-10-04', shiftId: 's1', notes: '临时' }, 'F006');
    await hrShiftApiClient.remove('a1', 'F006');

    expect(apiClient.post).toHaveBeenCalledWith('/api/mobile/F006/hr/shift-assignments', {
      userId: 7, workDate: '2026-10-04', shiftId: 's1', notes: '临时',
    });
    expect(apiClient.delete).toHaveBeenCalledWith('/api/mobile/F006/hr/shift-assignments/a1');
  });

  it('fails closed for false-success and malformed reads', async () => {
    (apiClient.get as jest.Mock).mockResolvedValueOnce({ success: false, message: '403 denied' });
    await expect(hrShiftApiClient.listAssignments({ startDate: '2026-10-04', endDate: '2026-10-04', factoryId: 'F006' }))
      .rejects.toThrow('403 denied');

    (apiClient.get as jest.Mock).mockResolvedValueOnce({ success: true, data: {} });
    await expect(hrShiftApiClient.listActiveShifts('F006')).rejects.toThrow('返回格式无效');
  });

  it('reads the canonical effective self module endpoint', async () => {
    (apiClient.get as jest.Mock).mockResolvedValue({ success: true, data: [
      { moduleCode: 'hr_employee', permissionModule: 'hr', effectiveAllowed: false, roleDefaultAllowed: true },
    ] });
    await expect(hrShiftApiClient.getSelfModuleAccess(9, 'F006')).resolves.toHaveLength(1);
    expect(apiClient.get).toHaveBeenCalledWith('/api/mobile/F006/canvas/user-module-access/9');
  });
});
