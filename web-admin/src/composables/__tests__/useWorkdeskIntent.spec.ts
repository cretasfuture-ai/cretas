import { effectScope, reactive } from 'vue';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useWorkdeskIntent } from '../useWorkdeskIntent';

const { post } = vi.hoisted(() => ({ post: vi.fn() }));
vi.mock('@/api/request', () => ({ default: { post } }));

describe('workdesk unified recognition boundary', () => {
  it('re-recognizes the original question once when a previous clarification session is already completed', async () => {
    post.mockResolvedValueOnce({ data: { status: 'CONVERSATION_CONTINUE', formattedText: '对话已完成', metadata: { status: 'COMPLETED', sessionId: 'closed-session' } } })
      .mockResolvedValueOnce({ data: { status: 'SUCCESS', formattedText: '这是本次问题的实际回答' } });
    const { client, scope } = create();
    const result = await client.execute('我是谁');
    expect(result.formattedText).toBe('这是本次问题的实际回答');
    expect(post).toHaveBeenCalledTimes(2);
    expect(post.mock.calls.map(([, body]) => body.userInput)).toEqual(['我是谁', '我是谁']);
    expect(post.mock.calls.every(([, body]) => body.mode === 'READ')).toBe(true);
    expect(post.mock.calls[1][1].sessionId).not.toBe(post.mock.calls[0][1].sessionId);
    scope.stop();
  });
  it('does not loop or present a repeated terminal notice as a successful answer', async () => {
    post.mockResolvedValue({ data: { status: 'CONVERSATION_CONTINUE', message: '对话已完成', metadata: { status: 'COMPLETED' } } });
    const { client, scope } = create();
    await expect(client.execute('我是谁')).rejects.toThrow('未返回本次问题的回答');
    expect(post).toHaveBeenCalledTimes(2);
    expect(client.sessionId.value).toBe('');
    scope.stop();
  });
  beforeEach(() => { post.mockReset(); });
  function create() {
    const identity = reactive({ factoryId: 'WHOLESALE_TEST', actorId: '1709', role: 'sales' });
    const scope = effectScope();
    const client = scope.run(() => useWorkdeskIntent(() => identity))!;
    return { identity, scope, client };
  }

  it('sends natural language and keeps one session across followups without forcing an intent', async () => {
    post.mockResolvedValue({ data: { status: 'SUCCESS' } });
    const { client, scope } = create();
    await client.execute('查询采购订单');
    await client.execute('其中还没收到的呢？');
    const first = post.mock.calls[0];
    expect(first[0]).toBe('/WHOLESALE_TEST/ai-intents/execute');
    expect(first[1]).toEqual({ userInput: '查询采购订单', sessionId: expect.any(String), mode: 'READ' });
    expect(post.mock.calls[1][1]).toEqual({ userInput: '其中还没收到的呢？', sessionId: first[1].sessionId, mode: 'READ' });
    scope.stop();
  });

  it('resets conversations and never sends missing tenant or actor requests', async () => {
    post.mockResolvedValue({ data: { status: 'SUCCESS', sessionId: 'server-session' } });
    const { client, identity, scope } = create();
    await client.execute('查询订单');
    expect(client.sessionId.value).toBe('server-session');
    client.reset();
    expect(client.sessionId.value).toBe('');
    identity.factoryId = '';
    await expect(client.execute('查询订单')).rejects.toThrow('账号未绑定企业');
    identity.factoryId = 'OTHER';
    identity.actorId = '';
    await expect(client.execute('查询订单')).rejects.toThrow('账号未绑定企业');
    expect(post).toHaveBeenCalledTimes(1);
    scope.stop();
  });
  it('starts a new session and drops a delayed result after role changes on the same actor', async () => {
    post.mockResolvedValueOnce({ data: { status: 'SUCCESS', sessionId: 'old-session' } });
    const { client, identity, scope } = create();
    await client.execute('查询订单');
    let finish!: (value: unknown) => void;
    post.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const pending = client.execute('查询旧岗位数据');
    identity.role = 'warehouse';
    expect(client.sessionId.value).toBe('');
    finish({ data: { status: 'SUCCESS', sessionId: 'old-session' } });
    await expect(pending).rejects.toThrow('企业或对话已切换');
    post.mockResolvedValueOnce({ data: { status: 'SUCCESS' } });
    await client.execute('查询库存');
    expect(post.mock.calls.at(-1)?.[1].sessionId).not.toBe('old-session');
    scope.stop();
  });

  it('rejects an old response after identity or conversation changes', async () => {
    let resolve!: (value: unknown) => void;
    post.mockImplementation(() => new Promise((done) => { resolve = done; }));
    const { client, identity, scope } = create();
    const pending = client.execute('查询订单');
    identity.factoryId = 'OTHER';
    resolve({ data: { status: 'SUCCESS', sessionId: 'old' } });
    await expect(pending).rejects.toThrow('企业或对话已切换');
    expect(client.sessionId.value).toBe('');
    scope.stop();
  });

  it('does not treat HTTP 200 gateway denial as a completed answer', async () => {
    post.mockResolvedValue({ data: { status: 'DENIED', message: 'Tool policy rejected' } });
    const { client, scope } = create();
    await expect(client.execute('查询原料')).rejects.toThrow('当前查询未获准执行');
    scope.stop();
  });

  it('carries server clarification session and only exposes the supplied query choices', async () => {
    post.mockResolvedValue({ data: { status: 'NEED_CLARIFICATION', metadata: { sessionId: 'clarify-session' }, suggestedActions: [
      { actionCode: 'SELECT_INTENT', actionName: '今日待收货清单' },
      { actionCode: 'DELETE', actionName: '删除记录' },
    ] } });
    const { client, scope } = create();
    await client.execute('其中还没收到的呢？');
    expect(client.sessionId.value).toBe('clarify-session');
    expect(client.clarificationQuestions.value).toEqual(['今日待收货清单']);
    await client.execute('今日待收货清单');
    expect(post.mock.calls[1][1].sessionId).toBe('clarify-session');
    scope.stop();
  });

  it('describes the receiving tool actual window instead of claiming an unsupported date filter is all-time', async () => {
    post.mockResolvedValue({ data: { status: 'SUCCESS', intentCode: 'MATERIAL_TODAY_RECEIVING_QUERY',
      formattedText: '注意：这个查询不能按时间筛选（你说的是 2026-11-15 至 2026-11-15），下面是不限时间的结果。\n暂无待收货',
      resultData: { data: { today: '2026-11-15', rangeEnd: '2026-11-16', includeOverdue: true } },
    } });
    const { client, scope } = create();
    const result = await client.execute('今天要收什么货');
    expect(result.formattedText).toContain('未按你指定的日期筛选');
    expect(result.formattedText).toContain('截至 2026-11-16，包含逾期待收');
    expect(result.formattedText).toContain('暂无待收货');
    expect(result.formattedText).not.toContain('不限时间');
    scope.stop();
  });
});
