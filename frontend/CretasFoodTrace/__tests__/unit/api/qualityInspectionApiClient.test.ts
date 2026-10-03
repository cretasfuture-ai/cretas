import { qualityInspectionApiClient } from '../../../src/services/api/qualityInspectionApiClient';
import { createApiMock, resetApiMock } from '../../../src/__tests__/utils/mockApiClient';
import MockAdapter from 'axios-mock-adapter';

describe('qualityInspectionApiClient quality summary contract', () => {
  let mock: MockAdapter;
  const factoryId = 'F006';
  const base = `/api/mobile/${factoryId}/processing/quality`;

  beforeEach(() => { mock = createApiMock(); });
  afterEach(() => { resetApiMock(mock); });

  it('requests full-period statistics and preserves qualitySummary status/rate fields', async () => {
    mock.onGet(`${base}/statistics`).reply(200, {
      success: true,
      data: {
        totalInspections: 6,
        qualitySummary: {
          startDate: '2026-09-28',
          endDate: '2026-10-04',
          totalInspections: 6,
          statusCounts: { PASS: 1, FAIL: 1, CONDITIONAL: 1, PENDING: 1, UNKNOWN: 1, EMPTY: 1 },
          passedInspections: 1,
          failedInspections: 1,
          conditionalInspections: 1,
          pendingInspections: 1,
          unknownInspections: 1,
          emptyResultInspections: 1,
          averagePassRate: 37.5,
          reportedPassRateCount: 4,
          missingPassRateCount: 2,
          decisionPassRate: 33.33,
          decidedInspectionCount: 3,
          totalSampleSize: 22,
          totalPassCount: 13,
          totalFailCount: 0,
          missingSampleSizeCount: 1,
          missingPassCount: 0,
          missingFailCount: 0,
        },
      },
    });

    const response = await qualityInspectionApiClient.getStatistics({
      startDate: '2026-09-28',
      endDate: '2026-10-04',
    }, factoryId);

    expect(mock.history.get[0]!.params).toEqual({
      startDate: '2026-09-28',
      endDate: '2026-10-04',
    });
    expect(response.data.qualitySummary?.statusCounts.PENDING).toBe(1);
    expect(response.data.qualitySummary?.averagePassRate).toBe(37.5);
    expect(response.data.qualitySummary?.decisionPassRate).toBe(33.33);
  });

  it('keeps an empty summary distinct from an unsuccessful statistics response', async () => {
    mock.onGet(`${base}/statistics`).replyOnce(200, {
      success: true,
      data: {
        totalInspections: 0,
        qualitySummary: {
          startDate: '2026-10-04',
          endDate: '2026-10-04',
          totalInspections: 0,
          statusCounts: { PASS: 0, FAIL: 0, CONDITIONAL: 0, PENDING: 0, UNKNOWN: 0, EMPTY: 0 },
          passedInspections: 0,
          failedInspections: 0,
          conditionalInspections: 0,
          pendingInspections: 0,
          unknownInspections: 0,
          emptyResultInspections: 0,
          averagePassRate: null,
          reportedPassRateCount: 0,
          missingPassRateCount: 0,
          decisionPassRate: null,
          decidedInspectionCount: 0,
          totalSampleSize: 0,
          totalPassCount: 0,
          totalFailCount: 0,
          missingSampleSizeCount: 0,
          missingPassCount: 0,
          missingFailCount: 0,
        },
      },
    });
    await expect(qualityInspectionApiClient.getStatistics({
      startDate: '2026-10-04', endDate: '2026-10-04',
    }, factoryId)).resolves.toMatchObject({ data: { qualitySummary: { totalInspections: 0, averagePassRate: null } } });

    mock.onGet(`${base}/statistics`).replyOnce(403, { success: false, message: 'forbidden' });
    await expect(qualityInspectionApiClient.getStatistics({
      startDate: '2026-10-04', endDate: '2026-10-04',
    }, factoryId)).rejects.toThrow();
  });
});
