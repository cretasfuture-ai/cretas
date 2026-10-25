import { traceabilityApiClient } from '../../../services/api/traceabilityApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import type MockAdapter from 'axios-mock-adapter';

const FACTORY = 'F006';
const IMPACT = `/api/mobile/${FACTORY}/traceability/impact/MATERIAL_BATCH/raw-cmr`;

const impactBody = {
  success: true,
  data: {
    rootType: 'MATERIAL_BATCH',
    rootId: 'raw-cmr',
    productionBatches: [],
    finishedBatches: [],
    shipments: [],
    productionBatchCount: 0,
    finishedBatchCount: 0,
    shipmentCount: 0,
    maxDepth: 50,
  },
  message: 'ok',
};

/**
 * 召回影响面的跳数: 每道工序占 2 跳, 10 道工序的成品在 depth 20。
 * 原来客户端写死 maxDepth=5 ⇒ 召回只覆盖最后两道工序。缺省必须交给后端 (= 上限, 整条链)。
 */
describe('召回影响面 — 跳数缺省交给后端', () => {
  let mock: MockAdapter;

  beforeEach(() => { mock = createApiMock(); });
  afterEach(() => { resetApiMock(mock); });

  it('不传 maxDepth 时请求里不带 maxDepth (不再写死 5)', async () => {
    mock.onGet(IMPACT).reply(200, impactBody);

    await traceabilityApiClient.getImpact('MATERIAL_BATCH', 'raw-cmr', { factoryId: FACTORY });

    const urls = mock.history.get.map((req) => req.url);
    expect(urls).toEqual([IMPACT]);
    expect(urls.join(' ')).not.toContain('maxDepth');
  });

  it('显式传 maxDepth=30 时原样带上', async () => {
    mock.onGet(`${IMPACT}?maxDepth=30`).reply(200, impactBody);

    await traceabilityApiClient.getImpact('MATERIAL_BATCH', 'raw-cmr', { factoryId: FACTORY, maxDepth: 30 });

    expect(mock.history.get.map((req) => req.url)).toEqual([`${IMPACT}?maxDepth=30`]);
  });
});
