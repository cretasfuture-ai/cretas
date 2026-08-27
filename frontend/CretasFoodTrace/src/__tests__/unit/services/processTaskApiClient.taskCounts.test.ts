/**
 * 守筛选档计数的**口径统一**与**三态**。
 *
 * 🔴 背景(这是本改动的核心风险, 不是锦上添花):
 * 屏上三个档原本由两条**口径不同**的路供数 ——
 *   - `进行中` 走 getActiveTasks: page1 **size 1000** 拉回来后在**客户端**筛
 *     PENDING|IN_PROGRESS, 返回裸数组, 既没有 totalElements 也天然被 1000 截断;
 *   - `已完成` / `全部` 走 getTasks: **服务端** status 过滤, 有 totalElements。
 * 直接把两边的长度并排印出来, 会给出一组互相对不上的数。
 * ⛔ 一个和「全部」对不上的计数比没有计数更糟 —— 它让用户以为数据丢了。
 *
 * ⇒ `getTaskCounts` 的契约: 三个档**只用服务端 totalElements 这一把尺子**。
 *
 * ⚠️ 这条必须跑在【真的 client】上, 只桩掉 HTTP 那一层 ——
 * 屏幕测试把 counts 直接喂进去, 整个取数口径根本没被执行。
 */
import { processTaskApiClient } from '../../../services/api/processTaskApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn() },
}));

jest.mock('../../../utils/factoryIdHelper', () => ({
  requireFactoryId: jest.fn(() => 'F001'),
}));

const mockedApiClient = apiClient as jest.Mocked<typeof apiClient>;

type GetCfg = { params?: { status?: string; page?: number; size?: number } };

/**
 * 按 status 参数分派, ⛔ 不按调用顺序分派 ——
 * 顺序分派会在发起顺序变化时静默把响应对调, 而每个响应都是合法形状,
 * 断言照样绿, 量的却是另一件事。
 */
function routeTotals(totals: Record<string, number | 'boom' | 'nofield'>) {
  mockedApiClient.get.mockImplementation((async (_url: string, cfg?: GetCfg) => {
    const key = cfg?.params?.status ?? '__ALL__';
    const v = totals[key];
    if (v === undefined) throw new Error(`未预期的 status: ${key}`);
    if (v === 'boom') throw new Error(`${key} 500`);
    if (v === 'nofield') return { success: true, data: { content: [] } };
    return { success: true, data: { content: [], totalElements: v } };
  }) as never);
}

/** 这一档实际发出的请求(按 status 取), 用来断言「是服务端在筛」。 */
function callFor(status?: string): GetCfg | undefined {
  const call = mockedApiClient.get.mock.calls.find(
    (c) => ((c[1] as GetCfg)?.params?.status ?? undefined) === status
  );
  return call?.[1] as GetCfg | undefined;
}

beforeEach(() => jest.clearAllMocks());

describe('口径统一 —— 三个档都用服务端 totalElements', () => {
  it('active = PENDING 总数 + IN_PROGRESS 总数, 两者都由服务端过滤得出', async () => {
    routeTotals({ PENDING: 12, IN_PROGRESS: 7, COMPLETED: 300, __ALL__: 348 });

    const counts = await processTaskApiClient.getTaskCounts('F001');

    expect(counts.active).toBe(19);
    expect(counts.completed).toBe(300);
    expect(counts.all).toBe(348);
  });

  it('🔴 status 是**发给服务端**的参数, ⛔ 不是拉回来在客户端筛', async () => {
    routeTotals({ PENDING: 1, IN_PROGRESS: 2, COMPLETED: 3, __ALL__: 6 });
    await processTaskApiClient.getTaskCounts('F001');

    // 四路各自带自己的 status(「全部」那路不带) —— 服务端过滤的证据
    expect(callFor('PENDING')?.params?.status).toBe('PENDING');
    expect(callFor('IN_PROGRESS')?.params?.status).toBe('IN_PROGRESS');
    expect(callFor('COMPLETED')?.params?.status).toBe('COMPLETED');
    expect(callFor(undefined)?.params?.status).toBeUndefined();
  });

  it('🔴 只取 totalElements ⇒ size 必须小, ⛔ 不许再来一次 size:1000 的大拉取', async () => {
    routeTotals({ PENDING: 1, IN_PROGRESS: 2, COMPLETED: 3, __ALL__: 6 });
    await processTaskApiClient.getTaskCounts('F001');

    // 计数只需要 totalElements。拉整页回来既慢, 又会诱使人改回「数数组长度」——
    // 而数组长度正是那个和「全部」对不上的旧口径。
    for (const cfg of mockedApiClient.get.mock.calls.map((c) => c[1] as GetCfg)) {
      expect(cfg?.params?.size).toBeLessThanOrEqual(1);
    }
  });

  it('阳性对照: 换一组总数, 读数跟着变 —— 否则上面几条可能只是撞上了常量', async () => {
    routeTotals({ PENDING: 0, IN_PROGRESS: 5, COMPLETED: 1, __ALL__: 6 });
    const counts = await processTaskApiClient.getTaskCounts('F001');

    expect(counts.active).toBe(5);
    expect(counts.completed).toBe(1);
    expect(counts.all).toBe(6);
  });
});

describe('三态 —— 读到就给数(含 0), 读不到给 null', () => {
  it('🔴 全部为 0 时给的是 0, ⛔ 不是 null', async () => {
    routeTotals({ PENDING: 0, IN_PROGRESS: 0, COMPLETED: 0, __ALL__: 0 });
    const counts = await processTaskApiClient.getTaskCounts('F001');

    // 0 是合法读数:「现在没有在做的活」。不敢报 0 是另一种说谎。
    expect(counts.active).toBe(0);
    expect(counts.completed).toBe(0);
    expect(counts.all).toBe(0);
    expect(counts.active).not.toBeNull();
  });

  it('🔴 某一档挂了给 null, ⛔ 不是 0', async () => {
    routeTotals({ PENDING: 3, IN_PROGRESS: 4, COMPLETED: 'boom', __ALL__: 10 });
    const counts = await processTaskApiClient.getTaskCounts('F001');

    // COMPLETED 那一路挂了却给出 0 ⇒ 用户读到「一条已完成的都没有」,
    // 而真相是我们没查到。⛔ 失败不许被翻译成读数。
    expect(counts.completed).toBeNull();
    expect(counts.completed).not.toBe(0);
    // 挂掉的那一档不许连坐其它档
    expect(counts.active).toBe(7);
    expect(counts.all).toBe(10);
  });

  it('🔴 active 的两条腿缺任何一条 ⇒ active 为 null, ⛔ 不许拿单腿冒充并集', async () => {
    routeTotals({ PENDING: 'boom', IN_PROGRESS: 4, COMPLETED: 1, __ALL__: 10 });
    const counts = await processTaskApiClient.getTaskCounts('F001');

    // PENDING 没问到时把 IN_PROGRESS 的 4 当成「进行中」总数 —— 那是个偏小的假数。
    expect(counts.active).toBeNull();
    expect(counts.active).not.toBe(4);
    // 其它档不受影响
    expect(counts.completed).toBe(1);
    expect(counts.all).toBe(10);
  });

  it('响应里没有 totalElements 字段时给 null, ⛔ 不许当成 0', async () => {
    routeTotals({ PENDING: 'nofield', IN_PROGRESS: 4, COMPLETED: 'nofield', __ALL__: 10 });
    const counts = await processTaskApiClient.getTaskCounts('F001');

    expect(counts.completed).toBeNull();
    expect(counts.active).toBeNull();
  });

  it('success:false 与抛错同等对待 —— 都是读不到', async () => {
    mockedApiClient.get.mockResolvedValue({ success: false, message: '权限不足' } as never);
    const counts = await processTaskApiClient.getTaskCounts('F001');

    expect(counts.active).toBeNull();
    expect(counts.completed).toBeNull();
    expect(counts.all).toBeNull();
  });

  it('全挂时不抛异常 —— 计数问不到不该拖垮调用它的屏幕', async () => {
    mockedApiClient.get.mockRejectedValue(new Error('网络断了') as never);
    await expect(processTaskApiClient.getTaskCounts('F001')).resolves.toEqual({
      active: null,
      completed: null,
      all: null,
    });
  });
});
