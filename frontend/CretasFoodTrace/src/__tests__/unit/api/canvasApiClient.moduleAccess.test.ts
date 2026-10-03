import { canvasApiClient } from '../../../services/api/canvasApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

describe('canvasApiClient.getUserModuleAccess', () => {
  let mock: MockAdapter;
  const factoryId = 'F006';
  const userId = 17;
  const path = `/api/mobile/${factoryId}/canvas/user-module-access/${userId}`;

  beforeEach(() => { mock = createApiMock(); });
  afterEach(() => { resetApiMock(mock); });

  it('reads effective self access including GRANT with no role permission', async () => {
    mock.onGet(path).reply(200, {
      success: true,
      data: [{ moduleCode: 'production_plan', permissionModule: 'production', effectiveAllowed: true }],
    });
    await expect(canvasApiClient.getUserModuleAccess(factoryId, userId)).resolves.toEqual([
      expect.objectContaining({ moduleCode: 'production_plan', effectiveAllowed: true }),
    ]);
  });

  it('preserves role read/write defaults alongside the effective decision', async () => {
    mock.onGet(path).reply(200, {
      success: true,
      data: [
        {
          moduleCode: 'production_plan',
          permissionModule: 'production',
          roleDefaultAllowed: true,
          override: null,
          effectiveAllowed: true,
        },
        {
          moduleCode: 'quality_inspection',
          permissionModule: 'quality',
          roleDefaultAllowed: false,
          override: 'GRANT',
          effectiveAllowed: true,
        },
      ],
    });

    const access = await canvasApiClient.getUserModuleAccess(factoryId, userId);
    expect(access).toEqual([
      expect.objectContaining({
        moduleCode: 'production_plan',
        roleDefaultAllowed: true,
        effectiveAllowed: true,
      }),
      expect.objectContaining({
        moduleCode: 'quality_inspection',
        roleDefaultAllowed: false,
        override: 'GRANT',
        effectiveAllowed: true,
      }),
    ]);
  });

  it('preserves explicit DENY and fail-closes on an unsuccessful response', async () => {
    mock.onGet(path).replyOnce(200, {
      success: true,
      data: [{ moduleCode: 'production_plan', permissionModule: 'production', effectiveAllowed: false }],
    });
    await expect(canvasApiClient.getUserModuleAccess(factoryId, userId)).resolves.toEqual([
      expect.objectContaining({ effectiveAllowed: false }),
    ]);

    mock.onGet(path).replyOnce(403, { success: false, message: 'forbidden' });
    await expect(canvasApiClient.getUserModuleAccess(factoryId, userId)).rejects.toThrow();
  });
});
