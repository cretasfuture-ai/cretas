import MockAdapter from 'axios-mock-adapter';

import { qualityInspectorApi } from '../../../services/api/qualityInspectorApi';
import { isPassed } from '../../../types/qualityInspector';
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
      result: 'FAIL',
      passed: false,
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
    expect(record.result).toBe('PASS');
    expect(record.passed).toBe(true);
  });

  it('keeps CONDITIONAL distinct from fully passed and preserves its disposition gate', async () => {
    mock.onGet(`${base}/QI-CONDITIONAL`).reply(200, {
      success: true,
      data: {
        id: 'QI-CONDITIONAL',
        productionBatchId: 10814,
        batchNumber: 'PB-REAL-10814',
        productName: '有条件批次',
        sampleSize: 10,
        passCount: 7,
        failCount: 3,
        passRate: 70,
        result: 'CONDITIONAL',
        qualityGrade: 'C',
      },
    });

    const record = await qualityInspectorApi.getRecordDetail('QI-CONDITIONAL');

    expect(record.result).toBe('CONDITIONAL');
    expect(record.passed).toBe(false);
    expect(record.passRate).toBe(70);
  });

  it('does not turn pending or unknown results into grade D/failed records', async () => {
    mock.onGet(base).reply(200, {
      success: true,
      data: {
        content: [
          { id: 'QI-PENDING', productionBatchId: 10815, result: 'PENDING', passRate: null },
          { id: 'QI-UNKNOWN', productionBatchId: 10816, result: 'REWORK', passRate: null },
        ],
        totalElements: 2,
        totalPages: 1,
      },
    });

    const records = await qualityInspectorApi.getInspectionRecords({ result: 'FAIL' });

    expect(records.content[0]).toMatchObject({ result: 'PENDING', grade: 'UNKNOWN', passed: false });
    expect(records.content[1]).toMatchObject({ result: 'UNKNOWN', grade: 'UNKNOWN', passed: false });
    expect(mock.history.get[0]!.params).toMatchObject({ result: 'FAIL' });
  });

  it('does not treat UNKNOWN as passed when evaluating a grade', () => {
    expect(isPassed('A')).toBe(true);
    expect(isPassed('D')).toBe(false);
    expect(isPassed('UNKNOWN')).toBe(false);
  });
});
