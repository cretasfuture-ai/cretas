// @ts-nocheck
const mockEventSources: MockEventSource[] = [];

class MockEventSource {
  listeners: Record<string, Array<(event: any) => void>> = {};
  closed = false;

  constructor(public url: string, public options: Record<string, unknown>) {
    mockEventSources.push(this);
  }

  addEventListener(type: string, listener: (event: any) => void) {
    (this.listeners[type] ||= []).push(listener);
  }

  removeEventListener(type: string, listener: (event: any) => void) {
    this.listeners[type] = (this.listeners[type] || []).filter((item) => item !== listener);
  }

  removeAllEventListeners() {
    this.listeners = {};
  }

  emit(type: string, event: any) {
    for (const listener of this.listeners[type] || []) listener(event);
  }

  close() {
    this.closed = true;
    this.listeners = {};
  }
}

jest.mock('react-native-sse', () => ({
  __esModule: true,
  default: MockEventSource,
}));

const mockGetSecureItem = jest.fn();
jest.mock('../../../services/storage/storageService', () => ({
  StorageService: {
    getSecureItem: (...args: unknown[]) => mockGetSecureItem(...args),
  },
}));

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: jest.fn(), post: jest.fn() },
}));

jest.mock('../../../constants/config', () => ({ API_BASE_URL: 'https://api.example.test' }));

import { aiApiClient } from '../../../services/api/aiApiClient';

describe('AIChat server-owned session binding', () => {
  beforeEach(() => {
    mockEventSources.length = 0;
    mockGetSecureItem.mockReset().mockResolvedValue('rn-token');
  });

  it('sends the server session on the next SSE request and forwards server callbacks', async () => {
    const onSessionId = jest.fn();
    const first = aiApiClient.executeIntentStream(
      '查询第一页',
      { onSessionId },
      'F006',
    );

    await new Promise((resolve) => setTimeout(resolve, 0));
    const firstSource = mockEventSources[0];
    expect(JSON.parse(String(firstSource.options.body))).toEqual({ userInput: '查询第一页' });
    firstSource.emit('start', { data: JSON.stringify({ message: '开始', sessionId: 'server-session-1' }) });
    firstSource.emit('complete', {
      data: JSON.stringify({ status: 'COMPLETED', cacheHit: false, sessionId: 'server-session-1' }),
    });
    await first;

    expect(onSessionId).toHaveBeenCalledWith('server-session-1');
    expect(firstSource.closed).toBe(true);

    const second = aiApiClient.executeIntentStream(
      '下一页',
      { onSessionId },
      'F006',
      undefined,
      { sessionId: 'server-session-1' },
    );
    await new Promise((resolve) => setTimeout(resolve, 0));
    const secondSource = mockEventSources[1];
    expect(JSON.parse(String(secondSource.options.body))).toEqual({
      userInput: '下一页',
      sessionId: 'server-session-1',
    });
    secondSource.emit('result', {
      data: JSON.stringify({ status: 'COMPLETED', sessionId: 'server-session-1', message: '第二页' }),
    });
    secondSource.emit('complete', {
      data: JSON.stringify({ status: 'COMPLETED', cacheHit: false, sessionId: 'server-session-1' }),
    });
    await second;

    expect(onSessionId).toHaveBeenCalledWith('server-session-1');
    expect(secondSource.closed).toBe(true);
    expect(JSON.stringify(firstSource.options.body)).not.toMatch(/sessionId/);
  });

  it('a slot-filling result session id is reported after the start session, so the screen keeps the slot one', async () => {
    // 缺参追问 (NEED_MORE_INFO) 的 result.sessionId 是参数收集会话; 它必须在 start 的会话之后报给屏幕,
    // 屏幕「最后一次 onSessionId」就是下一句要带回去的那个 (AIChatScreenDialogContinuity 依赖这条顺序)
    const onSessionId = jest.fn();
    const onResult = jest.fn();
    const stream = aiApiClient.executeIntentStream('做完了，产量0', { onSessionId, onResult }, 'F006');
    await new Promise((resolve) => setTimeout(resolve, 0));
    const source = mockEventSources[0];
    source.emit('start', { data: JSON.stringify({ message: '开始', sessionId: 'mem-1' }) });
    source.emit('result', {
      data: JSON.stringify({ status: 'NEED_MORE_INFO', sessionId: 'slot-1', clarificationQuestions: ['请提供工序任务ID'] }),
    });
    source.emit('complete', { data: JSON.stringify({ status: 'NEED_MORE_INFO', cacheHit: false }) });
    await stream;

    expect(onSessionId.mock.calls.map((c) => c[0])).toEqual(['mem-1', 'slot-1']);
    expect(onResult).toHaveBeenCalledWith(expect.objectContaining({ sessionId: 'slot-1' }));
  });

  it('aborts before opening SSE without reading auth or creating a network source', async () => {
    const controller = new AbortController();
    controller.abort();

    await expect(aiApiClient.executeIntentStream(
      '不会发出',
      {},
      'F006',
      undefined,
      undefined,
      controller.signal,
    )).rejects.toMatchObject({ name: 'AbortError' });

    expect(mockGetSecureItem).not.toHaveBeenCalled();
    expect(mockEventSources).toHaveLength(0);
  });

  it('aborts while token is loading without opening SSE', async () => {
    let resolveToken!: (value: string) => void;
    mockGetSecureItem.mockReturnValueOnce(new Promise((resolve) => { resolveToken = resolve; }));
    const controller = new AbortController();
    const request = aiApiClient.executeIntentStream(
      'token期间取消',
      {},
      'F006',
      undefined,
      undefined,
      controller.signal,
    );

    controller.abort();
    resolveToken('rn-token');
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(mockEventSources).toHaveLength(0);
  });

  it('does not open SSE when request identity expires while token is loading', async () => {
    let resolveToken!: (value: string) => void;
    mockGetSecureItem.mockReturnValueOnce(new Promise((resolve) => { resolveToken = resolve; }));
    let current = true;
    const request = aiApiClient.executeIntentStream(
      '身份竞态',
      { isCurrentRequest: () => current },
      'F006',
      undefined,
      undefined,
      undefined,
    );

    current = false;
    resolveToken('rn-token');
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(mockEventSources).toHaveLength(0);
  });

  it('closes active SSE, removes listeners, and ignores late events after abort', async () => {
    const controller = new AbortController();
    const onResult = jest.fn();
    const request = aiApiClient.executeIntentStream(
      '活动流取消',
      { onResult },
      'F006',
      undefined,
      undefined,
      controller.signal,
    );
    await new Promise((resolve) => setTimeout(resolve, 0));
    const source = mockEventSources[0];
    expect(source).toBeTruthy();
    expect(Object.values(source.listeners).some((items) => items.length > 0)).toBe(true);

    controller.abort();
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(source.closed).toBe(true);
    expect(Object.values(source.listeners).every((items) => items.length === 0)).toBe(true);

    source.emit('result', { data: JSON.stringify({ status: 'COMPLETED', message: '迟到结果' }) });
    expect(onResult).not.toHaveBeenCalled();
  });

  it('finishes and closes an active SSE when the request predicate expires', async () => {
    let current = true;
    const onResult = jest.fn();
    const request = aiApiClient.executeIntentStream(
      '活动身份失效',
      { isCurrentRequest: () => current, onResult },
      'F006',
    );
    await new Promise((resolve) => setTimeout(resolve, 0));
    const source = mockEventSources[0];
    expect(source).toBeTruthy();

    current = false;
    source.emit('result', { data: JSON.stringify({ status: 'SUCCESS', message: '不应到达' }) });
    await expect(request).rejects.toMatchObject({ name: 'AbortError' });
    expect(onResult).not.toHaveBeenCalled();
    expect(source.closed).toBe(true);
    expect(Object.values(source.listeners).every((items) => items.length === 0)).toBe(true);
  });
});
