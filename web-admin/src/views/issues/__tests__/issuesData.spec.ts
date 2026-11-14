import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  CATEGORY_DEFS, RENDERED_CATEGORIES, READ_TIMEOUT_MS, categoryBadge, createIssuesLoader, issueStatus,
  type IssuesContext,
} from '../issuesData';

/**
 * P4「发现的问题」数据层：按来源分类、处置标记、G-1 超时、G-2 读不到不画数。
 * 假 get 按 URL 回数据；「挂起」= 永不 resolve 且不理 signal。
 */
type Handler = (url: string, config?: Record<string, unknown>) => Promise<unknown>;
const ok = (data: unknown) => Promise.resolve({ success: true, data });
const NEVER = () => new Promise<never>(() => undefined);

function makeGet(overrides: Record<string, Handler> = {}) {
  const calls: string[] = [];
  const get = (url: string, config?: Record<string, unknown>) => {
    calls.push(url);
    for (const [frag, h] of Object.entries(overrides)) if (url.includes(frag)) return h(url, config) as never;
    if (url.endsWith('/issues/dispositions')) return ok([
      { sourceType: 'SHORTAGE_CASE', sourceId: 's2', status: 'CLOSED', conclusion: 'FALSE_ALARM', conclusionLabel: '误报', reason: '订单已取消', title: 't' },
      { sourceType: 'DELIVERY_RISK', sourceId: 'r1', status: 'CONVERTED', conclusion: null, conclusionLabel: null, reason: null, title: 't' },
    ]) as never;
    if (url.includes('/shortage-cases')) return ok([
      { id: 's1', salesOrderNumber: 'SO-1', stageLabel: '待处理', gapLineCount: 2 },
      { id: 's2', salesOrderNumber: 'SO-2', stageLabel: '待处理', gapLineCount: 1 },
    ]) as never;
    if (url.includes('/delivery-risks')) return ok([
      { id: 'r1', orderNumber: 'SO-7', riskLevel: 'RISK' },
      { id: 'r2', orderNumber: 'SO-8', riskLevel: 'UNKNOWN' },
      { id: 'r3', orderNumber: 'SO-9', riskLevel: 'OK' },
    ]) as never;
    if (url.includes('/process-param-deviation/cases')) return ok([{ id: 'p1', processName: '蒸煮', paramLabel: '温度' }]) as never;
    if (url.includes('/cost-deviation-cases/daily-runs')) return ok([
      { id: 'run2', status: 'NOT_MEASURED', notMeasuredReason: '阈值没配' },
      { id: 'run1', status: 'MEASURED' },
    ]) as never;
    if (url.includes('/cost-deviation-cases')) return ok([{ id: 'c1', sourceLabel: '成本差异', planNumber: 'PL-1' }]) as never;
    if (url.includes('/cold-chain/alerts')) return ok([
      { id: 11, equipmentName: '冷库1', status: 'OPEN' },
      { id: 12, equipmentName: '冷库2', status: 'RESOLVED' },
    ]) as never;
    return Promise.reject(new Error(`unexpected ${url}`));
  };
  return { get, calls };
}

const ctx = (patch: Partial<IssuesContext> = {}): IssuesContext => ({ factoryId: 'F1', canRead: () => true, ...patch });
const keys = (L: ReturnType<typeof createIssuesLoader>, cat: string) =>
  L.S.issues.filter((i) => i.category === cat).map((i) => i.key).sort();

beforeEach(() => { vi.useFakeTimers(); });
afterEach(() => { vi.useRealTimers(); });

describe('发现的问题：分类', () => {
  it('规则预警 = 缺料 + 交付风险(可能延期) + 工艺参数偏离 + 成本/损耗 + 未结束冷链；数据缺口 = 交付数据不够判断 + 每日核对没量到', async () => {
    const a = makeGet();
    const L = createIssuesLoader({ get: a.get as never });
    await L.load(ctx());
    expect(keys(L, 'rule')).toEqual([
      'COLD_CHAIN_ALERT:11', 'COST_DEVIATION:c1', 'DELIVERY_RISK:r1', 'PROCESS_PARAM_DEVIATION:p1',
      'SHORTAGE_CASE:s1', 'SHORTAGE_CASE:s2',
    ]);
    expect(keys(L, 'gap')).toEqual(['DAILY_RECONCILE_RUN:run2', 'DELIVERY_RISK:r2']);
    // 已解决的冷链、OK 的交付风险不进
    expect(L.S.issues.some((i) => i.sourceId === '12' || i.sourceId === 'r3')).toBe(false);
  });

  it('每日差异核对最近一次量到了 ⇒ 没有数据缺口条目', async () => {
    const a = makeGet({ '/cost-deviation-cases/daily-runs': () => ok([{ id: 'run3', status: 'MEASURED' }, { id: 'run2', status: 'NOT_MEASURED' }]) });
    const L = createIssuesLoader({ get: a.get as never });
    await L.load(ctx());
    expect(L.S.issues.some((i) => i.sourceType === 'DAILY_RECONCILE_RUN')).toBe(false);
  });

  it('对账差异没有外部授权判定（P10）⇒ 整类不渲染；数字员工汇报没有来源 ⇒ 写原因、不画数', async () => {
    expect(RENDERED_CATEGORIES.map((c) => c.id)).toEqual(['report', 'rule', 'gap']);
    expect(CATEGORY_DEFS.map((c) => c.label)).toEqual(['数字员工汇报', '规则预警', '对账差异', '数据缺口']);
    expect(CATEGORY_DEFS.find((c) => c.id === 'report')?.reason).toContain('P8');
    const L = createIssuesLoader({ get: makeGet().get as never });
    await L.load(ctx());
    expect(categoryBadge('report', L.S.src, L.S.issues, L.S.dispositions, true)).toBeNull();
  });
});

describe('发现的问题：处置标记与角标', () => {
  it('已关闭的不算进角标；已转待办的仍算（还没处理完）', async () => {
    const L = createIssuesLoader({ get: makeGet().get as never });
    await L.load(ctx());
    const s2 = L.S.issues.find((i) => i.key === 'SHORTAGE_CASE:s2')!;
    const r1 = L.S.issues.find((i) => i.key === 'DELIVERY_RISK:r1')!;
    expect(issueStatus(s2, L.S.dispositions)).toBe('closed');
    expect(issueStatus(r1, L.S.dispositions)).toBe('converted');
    expect(categoryBadge('rule', L.S.src, L.S.issues, L.S.dispositions, true)).toBe(6 - 1);
    expect(categoryBadge('gap', L.S.src, L.S.issues, L.S.dispositions, true)).toBe(2);
  });

  it('处置记录读不到 ⇒ 不知道哪些已关闭 ⇒ 不画角标并写原因', async () => {
    const L = createIssuesLoader({ get: makeGet({ '/issues/dispositions': () => Promise.reject(new Error('403')) }).get as never });
    await L.load(ctx());
    expect(L.S.dispositionState).toBe('error');
    expect(L.S.dispositionNote).toContain('先不允许关闭');
    expect(categoryBadge('rule', L.S.src, L.S.issues, L.S.dispositions, false)).toBeNull();
  });

  it('一类里有一路读失败 ⇒ 这类不画数，失败那一路写原因；其它类照常', async () => {
    const L = createIssuesLoader({ get: makeGet({ '/cold-chain/alerts': () => Promise.reject(new Error('503')) }).get as never });
    await L.load(ctx());
    expect(L.S.src.coldChain.state).toBe('error');
    expect(L.S.src.coldChain.note).toContain('503');
    expect(categoryBadge('rule', L.S.src, L.S.issues, L.S.dispositions, true)).toBeNull();
    expect(categoryBadge('gap', L.S.src, L.S.issues, L.S.dispositions, true)).toBe(2);
  });

  it('没有某来源的模块权限 ⇒ 不发请求、标不适用（不是「没有问题」）', async () => {
    const a = makeGet();
    const L = createIssuesLoader({ get: a.get as never });
    await L.load(ctx({ canRead: (m) => !m.includes('quality') }));
    expect(L.S.src.paramDeviation.state).toBe('na');
    expect(L.S.src.paramDeviation.note).toContain('不是「没有问题」');
    expect(a.calls.some((u) => u.includes('process-param-deviation'))).toBe(false);
  });

  it('applyDisposition：关闭后就地标记，恢复后去掉', async () => {
    const L = createIssuesLoader({ get: makeGet().get as never });
    await L.load(ctx());
    const s1 = L.S.issues.find((i) => i.key === 'SHORTAGE_CASE:s1')!;
    L.applyDisposition({ sourceType: 'SHORTAGE_CASE', sourceId: 's1', status: 'CLOSED', conclusion: 'HANDLED', conclusionLabel: '已处理', reason: '补货到了', title: s1.title, linkPath: null, assigneeUserId: null, actorType: 'FACTORY_USER', actorId: 1, actedAt: '' });
    expect(issueStatus(s1, L.S.dispositions)).toBe('closed');
    L.applyDisposition({ sourceType: 'SHORTAGE_CASE', sourceId: 's1', status: 'OPEN', conclusion: null, conclusionLabel: null, reason: null, title: s1.title, linkPath: null, assigneeUserId: null, actorType: 'FACTORY_USER', actorId: 1, actedAt: '' });
    expect(issueStatus(s1, L.S.dispositions)).toBe('open');
  });
});

describe('发现的问题：G-1 超时', () => {
  it('一路挂起 ⇒ 时限到了标读取失败、其余照常；inflight 释放，下一次 load 会重新发请求', async () => {
    const a = makeGet({ '/shortage-cases': NEVER });
    const L = createIssuesLoader({ get: a.get as never });
    const p = L.load(ctx());
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS + 10);
    await p;
    expect(L.S.src.shortage.state).toBe('error');
    expect(L.S.src.shortage.note).toContain('秒没有返回');
    expect(L.S.src.risk.state).toBe('ok');
    const n = a.calls.filter((u) => u.includes('/shortage-cases')).length;
    const p2 = L.load(ctx());
    await vi.advanceTimersByTimeAsync(READ_TIMEOUT_MS + 10);
    await p2;
    expect(a.calls.filter((u) => u.includes('/shortage-cases')).length).toBe(n + 1);
  });
});
