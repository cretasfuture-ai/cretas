import MockAdapter from 'axios-mock-adapter';

import { qualityInspectorApi } from '../../services/api/qualityInspectorApi';
import { createApiMock, resetApiMock } from '../utils/mockApiClient';

describe('qualityInspectorApi exact batch-number lookup', () => {
  let mock: MockAdapter;
  const base = '/api/mobile/F006/processing/batches/lookup';

  beforeEach(() => {
    mock = createApiMock();
    qualityInspectorApi.setFactoryId('F006');
  });

  afterEach(() => resetApiMock(mock));

  it('uses the factory-scoped GET lookup and returns persisted identity', async () => {
    mock.onGet(base, { params: { batchNumber: 'PB-REAL-10806' } }).reply(200, {
      success: true,
      data: { id: '10806', factoryId: 'F006', batchNumber: 'PB-REAL-10806', productName: '真实成品' },
    });

    await expect(qualityInspectorApi.getBatchByQRCode('  PB-REAL-10806 ')).resolves.toMatchObject({
      id: '10806', factoryId: 'F006', batchNumber: 'PB-REAL-10806',
    });
  });

  it('rejects empty manual input without issuing a request', async () => {
    await expect(qualityInspectorApi.getBatchByQRCode('   ')).rejects.toThrow('Batch number is required');
    expect(mock.history.get).toHaveLength(0);
  });

  it('loads the quality task queue from the PENDING-backed endpoint', async () => {
    mock.onGet('/api/mobile/F006/processing/batches/quality-pending', {
      params: { page: 1, size: 20 },
    }).reply(200, {
      success: true,
      data: { content: [{ id: '10806', batchNumber: 'PB-REAL-10806' }], totalElements: 1, totalPages: 1 },
    });

    await expect(qualityInspectorApi.getPendingBatches()).resolves.toMatchObject({
      totalElements: 1,
      content: [{ id: '10806' }],
    });
  });
});
