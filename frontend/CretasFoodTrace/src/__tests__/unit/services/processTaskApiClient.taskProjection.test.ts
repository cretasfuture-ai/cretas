/**
 * 守「后端已经送来的四个字段，映射层不许丢」。
 *
 * ⚠️ 为什么这条不能只靠屏幕测试:
 * `ProcessTaskListScreen` 的用例把 `ProcessTaskItem` 【直接喂给屏幕】,
 * 整条 `toProcessTaskItem` 映射根本没被执行。也就是说, 只要有人把这四个
 * 字段从映射里删掉, 那边的屏幕测试照样全绿 —— 而这正是这次缺陷的长相:
 *
 *   后端 DTO 有 plannedStartAt / plannedEndAt / assignedToName / overdue,
 *   RN 传输类型也有, 而 `toProcessTaskItem` 一个都没投影 ⇒ 屏幕永远看不到。
 *   那不是「缺功能」, 是**投影丢失**: 数据送到了消费端前面一层才被扔掉。
 *
 * ⇒ 这些断言必须跑在【真的 client】上, 只桩掉 HTTP 那一层。
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

/** 后端 `WorkProcessTaskDTO` 的真实长相: 时间是 `@JsonFormat("yyyy-MM-dd HH:mm:ss")`, 不是 ISO。 */
const rawTask = (extra: Record<string, unknown> = {}) => ({
  id: 77,
  factoryId: 'F001',
  productTypeId: 'PT-009',
  productTypeName: '卤猪蹄',
  workProcessId: 'WP-009',
  processName: '卤制',
  processCategory: '热加工',
  plannedUnit: 'kg',
  productionBatchId: 10759,
  plannedQuantity: 200,
  actualQuantity: 0,
  status: 'PENDING',
  ...extra,
});

type Projected = {
  plannedStartAt?: string;
  plannedEndAt?: string;
  assignedToName?: string;
  overdue?: boolean;
};

async function projectOne(extra: Record<string, unknown>): Promise<Projected> {
  mockedApiClient.get.mockResolvedValueOnce({
    success: true,
    data: { content: [rawTask(extra)] },
  } as never);
  const res = await processTaskApiClient.getActiveTasks('F001');
  const tasks = res.data as Projected[];
  expect(tasks).toHaveLength(1);
  return tasks[0]!;
}

describe('processTaskApiClient — 计划日期 / 责任人 / 超期 必须被投影出来', () => {
  beforeEach(() => jest.clearAllMocks());

  it('计划起止时间原样透出 (不是 undefined)', async () => {
    const task = await projectOne({
      plannedStartAt: '2026-08-26 08:00:00',
      plannedEndAt: '2026-08-26 17:30:00',
    });

    // 逐个字段钉死。⛔ 不写 toMatchObject 的宽松版本 —— 少一个字段照样过。
    expect(task.plannedStartAt).toBe('2026-08-26 08:00:00');
    expect(task.plannedEndAt).toBe('2026-08-26 17:30:00');
  });

  it('责任人姓名原样透出', async () => {
    const task = await projectOne({ assignedToName: '徐师傅' });
    expect(task.assignedToName).toBe('徐师傅');
  });

  it('overdue=true 透出 true', async () => {
    const task = await projectOne({ plannedEndAt: '2026-08-20 17:30:00', overdue: true });
    expect(task.overdue).toBe(true);
  });

  it('阴性对照: overdue=false 透出 false —— ⛔ 不许被折叠成 undefined', async () => {
    const task = await projectOne({ plannedEndAt: '2026-09-30 17:30:00', overdue: false });
    // 写成 `toBe(false)` 而不是 `toBeFalsy()`: 后者对 undefined 也成立,
    // 那样「false 和 undefined 分不分得开」这件事就没人守了。
    expect(task.overdue).toBe(false);
    expect(task.overdue).not.toBeUndefined();
  });

  it('🔴 后端没给 overdue(判不了) ⇒ undefined, ⛔ 不是 false', async () => {
    // 真实那侧的长相: 这道工序没设 plannedEndAt, 后端 `WorkProcessTaskOverdue.of`
    // 返回 null, Jackson 送 null(或整个键缺失)。
    const fromNull = await projectOne({ plannedEndAt: null, overdue: null });
    const fromMissing = await projectOne({});

    for (const task of [fromNull, fromMissing]) {
      // 这一条就是「兜底会把『我不知道』翻译成『是 0/false』」那个形态。
      // `?? false` 会让这两条断言双双变红 —— 它们是这次修复的防线。
      expect(task.overdue).toBeUndefined();
      expect(task.overdue).not.toBe(false);
    }
  });

  it('计划日期没设时是 undefined, ⛔ 不许兜底成今天或空串', async () => {
    const task = await projectOne({ plannedStartAt: null, plannedEndAt: null });
    expect(task.plannedStartAt).toBeUndefined();
    expect(task.plannedEndAt).toBeUndefined();
    expect(task.plannedStartAt).not.toBe('');
  });

  it('阳性对照: 映射层确实跑了 —— 已有字段没被这次改动带坏', async () => {
    // 这条不是凑数: 如果 getActiveTasks 因为任何原因没走 toProcessTaskItem
    // (比如被改成直接返回后端原始对象), 上面所有断言都会「碰巧」通过,
    // 因为后端原始对象上这些键也叫同样的名字。这一条钉住【映射确实发生了】:
    // `id` 被转成 string、`productionRunId` 是映射层拼出来的, 后端不送这两个形状。
    const task = (await projectOne({})) as unknown as { id: string; productionRunId: string };
    expect(task.id).toBe('77');
    expect(task.productionRunId).toBe('BATCH-10759');
  });
});
