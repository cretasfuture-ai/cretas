import { schedulingApiClient } from '../../../services/api/schedulingApiClient';
import { createApiMock, resetApiMock } from '../../utils/mockApiClient';
import type MockAdapter from 'axios-mock-adapter';

const FACTORY = 'F006';
const BATCHES = `/api/mobile/${FACTORY}/processing/batches`;

describe('HR batch API readback contract', () => {
  let mock: MockAdapter;

  beforeEach(() => { mock = createApiMock(); });
  afterEach(() => { resetApiMock(mock); });

  it('does not invent a required people count from operator or output quantities', async () => {
    mock.onGet(BATCHES).reply(200, {
      success: true,
      data: {
        content: [{
          id: 1037,
          batchNumber: 'B-1037',
          productName: '冷冻鱼排',
          status: 'PLANNED',
          workerCount: 4,
          plannedQuantity: 800,
          workDurationMinutes: null,
          laborCost: null,
        }],
        totalElements: 1,
        totalPages: 1,
      },
      message: 'ok',
    });

    const result = await schedulingApiClient.getBatchAssignments({ factoryId: FACTORY });
    expect(result).toEqual({
      content: [expect.objectContaining({
        batchId: '1037', requiredCount: null, assignedCount: null,
        totalWorkHours: null, laborCost: null,
      })],
      totalElements: 1,
      totalPages: 1,
      page: 1,
    });
    expect(result.content[0]!.requiredCount).not.toBe(800);
    expect(result.content[0]!.requiredCount).toBeNull();
  });

  it('preserves page, size and status filters for a partial page readback', async () => {
    mock.onGet(BATCHES).replyOnce(200, {
      success: true,
      data: { content: [], totalElements: 21, totalPages: 2, page: 2 },
      message: 'ok',
    });
    const result = await schedulingApiClient.getBatchAssignments({ factoryId: FACTORY, status: 'PLANNED', page: 2, size: 20 });
    expect(mock.history.get[0]!.params).toEqual({ status: 'PLANNED', page: 2, size: 20 });
    expect(result.page).toBe(2);
    expect(result.totalPages).toBe(2);
  });

  it('rejects non-success and malformed list envelopes instead of returning empty', async () => {
    mock.onGet(BATCHES).replyOnce(200, { success: false, data: null, message: 'forbidden' });
    await expect(schedulingApiClient.getBatchAssignments({ factoryId: FACTORY })).rejects.toThrow('forbidden');

    mock.onGet(BATCHES).replyOnce(200, { success: true, data: { content: [], totalElements: 1 }, message: 'bad shape' });
    await expect(schedulingApiClient.getBatchAssignments({ factoryId: FACTORY })).rejects.toThrow('bad shape');

    mock.onGet(BATCHES).replyOnce(200, { success: true, data: { content: [{ id: 1, batchNumber: 'bad', status: 'PLANNED', laborCost: '4' }], totalElements: 1, totalPages: 1 }, message: 'bad field' });
    await expect(schedulingApiClient.getBatchAssignments({ factoryId: FACTORY })).rejects.toThrow('laborCost');
  });

  it('keeps assigned distinct from started and completed, with unknown status for cancelled', async () => {
    mock.onGet(`${BATCHES}/1037/workers`).reply(200, {
      success: true,
      data: [
        { sessionId: 1, workerId: 11, workerName: 'A', status: 'ASSIGNED', workMinutes: null },
        { sessionId: 2, workerId: 12, workerName: 'B', status: 'working', workMinutes: 35 },
        { sessionId: 3, workerId: 13, workerName: 'C', status: 'completed', workMinutes: 60 },
        { sessionId: 4, workerId: 14, workerName: 'D', status: 'CANCELLED', workMinutes: 0 },
      ],
      message: 'ok',
    });

    const result = await schedulingApiClient.getBatchWorkers('1037', FACTORY);
    expect(result.content.map((worker) => worker.status)).toEqual(['assigned', 'working', 'completed', 'unknown']);
    expect(result.content[0]!.workMinutes).toBeNull();
  });

  it('rejects non-success and malformed worker envelopes', async () => {
    const url = `${BATCHES}/1037/workers`;
    mock.onGet(url).replyOnce(200, { success: false, data: [], message: 'workers forbidden' });
    await expect(schedulingApiClient.getBatchWorkers('1037', FACTORY)).rejects.toThrow('workers forbidden');
    mock.onGet(url).replyOnce(200, { success: true, data: {}, message: 'workers bad shape' });
    await expect(schedulingApiClient.getBatchWorkers('1037', FACTORY)).rejects.toThrow('workers bad shape');
  });

  it('keeps the legacy dispatcher contract on the parameterless shared method', async () => {
    mock.onGet(/\/api\/mobile\/[^/]+\/processing\/batches\/1037\/workers/).reply(200, {
      success: true,
      data: [{ sessionId: 1, workerId: 11, workerName: 'A', status: 'ASSIGNED' }],
      message: 'ok',
    });
    const result = await schedulingApiClient.getBatchWorkers('1037');
    expect(result.content[0]).toMatchObject({ id: 1, userId: 11, workMinutes: 0, status: 'ASSIGNED' });
  });

  it('rejects a false-success DELETE envelope', async () => {
    mock.onDelete(`${BATCHES}/1037/workers/11`).reply(200, {
      success: true,
      data: { success: false, message: 'already removed', workerId: 11, sessionId: 1 },
      message: 'ok',
    });
    await expect(schedulingApiClient.removeWorkerFromBatch('1037', 11, FACTORY)).rejects.toThrow('already removed');
  });

  it('accepts only a complete matching successful DELETE envelope', async () => {
    mock.onDelete(`${BATCHES}/1037/workers/11`).reply(200, {
      success: true,
      data: { success: true, workerId: 11, sessionId: 1 },
      message: 'ok',
    });
    await expect(schedulingApiClient.removeWorkerFromBatch('1037', 11, FACTORY)).resolves.toEqual({ success: true });
  });

  it('rejects incomplete or mismatched successful DELETE envelopes', async () => {
    const url = `${BATCHES}/1037/workers/11`;
    for (const data of [null, {}, { success: true, workerId: 12, sessionId: 1 }, { success: true, workerId: 11, sessionId: 0 }]) {
      mock.onDelete(url).replyOnce(200, { success: true, data, message: 'bad response' });
      await expect(schedulingApiClient.removeWorkerFromBatch('1037', 11, FACTORY)).rejects.toThrow('bad response');
    }
  });
});
