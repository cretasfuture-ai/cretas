import MockAdapter from 'axios-mock-adapter';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import { alertApiClient } from '../../../services/api/alertApiClient';

describe('equipment alert controller transport contract', () => {
  let mock: MockAdapter;
  beforeEach(() => { mock = createApiMock(); });
  afterEach(() => { resetApiMock(mock); });

  it('acknowledges through the actual scoped PUT route without a caller-supplied actor', async () => {
    const path = '/api/mobile/F006/equipment-alerts/123/acknowledge';
    mock.onPut(path).reply(200, { success: true, data: { id: 123, status: 'ACKNOWLEDGED' } });
    const result = await alertApiClient.acknowledgeAlert({ factoryId: 'F006', alertId: '123', acknowledgedBy: 999 });
    expect(result.success).toBe(true);
    expect(mock.history.put).toHaveLength(1);
    expect(mock.history.put[0]?.url).toBe(path);
    expect(mock.history.put[0]?.data).toBeUndefined();
    expect(mock.history.post).toHaveLength(0);
  });

  it('resolves through PUT with the resolution key the controller consumes', async () => {
    const path = '/api/mobile/F006/equipment-alerts/123/resolve';
    mock.onPut(path, { resolution: '已检查设备' }).reply(200, { success: true, data: { id: 123, status: 'RESOLVED' } });
    const result = await alertApiClient.resolveAlert({ factoryId: 'F006', alertId: '123', resolvedBy: 999, resolutionNotes: '已检查设备' });
    expect(result.success).toBe(true);
    expect(mock.history.put).toHaveLength(1);
    expect(JSON.parse(mock.history.put[0]?.data ?? '{}')).toEqual({ resolution: '已检查设备' });
    expect(mock.history.post).toHaveLength(0);
  });

  it('sends a valid empty resolution object when no notes were entered', async () => {
    mock.onPut('/api/mobile/F006/equipment-alerts/123/resolve', { resolution: '' }).reply(200, { success: true });
    await alertApiClient.resolveAlert({ factoryId: 'F006', alertId: '123', resolvedBy: 77 });
    expect(JSON.parse(mock.history.put[0]?.data ?? '{}')).toEqual({ resolution: '' });
  });
});
