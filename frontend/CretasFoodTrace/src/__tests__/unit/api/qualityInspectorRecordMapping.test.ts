import MockAdapter from 'axios-mock-adapter';

import { qualityInspectorApi } from '../../../services/api/qualityInspectorApi';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';

describe('qualityInspectorApi record detail mapping', () => {
  let mock: MockAdapter;
  const base = '/api/mobile/F006/processing/quality/inspections';

  beforeEach(() => {
    mock = createApiMock();
    qualityInspectorApi.setFactoryId('F006');
  });

  afterEach(() => resetApiMock(mock));

  it('maps real batch identity, remarks and persisted HACCP scores', async () => {
    mock.onGet(`${base}/QI-1`).reply(200, {
      success: true,
      data: {
        id: 'QI-1',
        productionBatchId: 10806,
        batchNumber: 'PB-REAL-10806',
        productName: '真实成品',
        notes: '检验备注不是产品名',
        sampleSize: 2,
        passRate: 50,
        result: 'FAIL',
        qualityGrade: 'D',
        inspectorId: 1558,
        inspectorName: '六膳门质检员',
        inspectionDate: '2026-08-25',
        customFields: {
          totalScore: 50,
          grade: 'D',
          haccpScores: {
            appearance: { score: 10, notes: ['色泽异常'] },
            smell: { score: 10, notes: [] },
            specification: { score: 10, notes: [] },
            weight: { score: 10, notes: [] },
            packaging: { score: 10, notes: [] },
          },
        },
      },
    });

    const record = await qualityInspectorApi.getRecordDetail('QI-1');

    expect(record).toMatchObject({
      batchNumber: 'PB-REAL-10806',
      productName: '真实成品',
      remarks: '检验备注不是产品名',
      totalScore: 50,
      passRate: 50,
      scoreDetailsRecorded: true,
      appearance: { score: 10, notes: ['色泽异常'] },
    });
  });

  it('does not fabricate zero category scores or use notes as the product name for legacy records', async () => {
    mock.onGet(`${base}/QI-LEGACY`).reply(200, {
      success: true,
      data: {
        id: 'QI-LEGACY',
        productionBatchId: 10814,
        batchNumber: 'PB-REAL-10814',
        productName: '真实成品',
        notes: '历史备注',
        sampleSize: 2,
        passRate: 100,
        result: 'PASS',
        qualityGrade: 'A',
      },
    });

    const record = await qualityInspectorApi.getRecordDetail('QI-LEGACY');

    expect(record.productName).toBe('真实成品');
    expect(record.remarks).toBe('历史备注');
    expect(record.scoreDetailsRecorded).toBe(false);
    expect(record.totalScore).toBe(0);
    expect(record.passRate).toBe(100);
  });
});
