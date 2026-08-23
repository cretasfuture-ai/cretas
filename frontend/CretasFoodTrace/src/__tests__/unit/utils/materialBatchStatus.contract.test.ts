/**
 * 批次状态映射的**跨语言**契约闸 —— 对着 Java 枚举源文件断言。
 *
 * 🔴 建这道闸的原因（2026-08-20）：
 * 后端 `MaterialBatchStatus` 有 **12** 个取值，而 App 里那份映射只认 4 个，
 * 其余全部落到 `default → pending` ⇒ **已入库、已用完、已报废的批次统统被算成「入库任务」**。
 * prod 实测 F006 首页「入库任务」里混着 5 条 `USED_UP`（已用完）。
 *
 * ⚠️ 而且同一份映射当时有**两份**（首页 / 入库列表页），**已经漂开了**：
 * 入库列表页把 `fresh`/`frozen` 判成待办，首页则根本没这两个 case。
 *
 * ▎两份会漂 ⇒ 抽成一份；一份也会跟枚举漂 ⇒ 加这道闸。
 * ▎光抽不钉，下次后端加一个枚举值，它还是会静默变成一条假待办。
 */
import fs from 'fs';
import path from 'path';
import { BATCH_STATUS_WORK_MAP, toBatchWorkStatus, isPendingInboundTask } from '../../../utils/materialBatchStatus';

const ENUM_FILE = path.resolve(
  __dirname,
  '../../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/MaterialBatchStatus.java',
);

function backendEnumValues(): string[] {
  const src = fs.readFileSync(ENUM_FILE, 'utf8');
  // 只取枚举常量行：行首缩进 + 全大写标识符 + 紧跟左括号
  return [...src.matchAll(/^\s{4}([A-Z][A-Z_]*)\(/gm)]
    .map((m) => m[1])
    .filter((v): v is string => Boolean(v));
}

describe('批次状态映射 — 对着 Java 枚举的跨语言契约', () => {
  it('前提：真的读到了枚举文件，且解析出合理数量的取值', () => {
    // 🔴 仪器自检。没有这条，路径写错时 backendEnumValues() 返回 []，
    //    下面那条「每个取值都被登记」会因为「一个都没有」而**恒真**。
    expect(fs.existsSync(ENUM_FILE)).toBe(true);
    const values = backendEnumValues();
    expect(values.length).toBeGreaterThanOrEqual(10);
    // 阳性对照：这几个是肉眼确认过存在的
    expect(values).toEqual(expect.arrayContaining(['AVAILABLE', 'USED_UP', 'INSPECTING', 'DEFECTIVE']));
  });

  it('后端每一个枚举取值，都必须在 App 侧被**显式**登记', () => {
    const missing = backendEnumValues().filter((v) => !(v in BATCH_STATUS_WORK_MAP));
    expect(missing).toEqual([]);
    // ⇒ 后端新增枚举值时这条会红，而不是让它悄悄变成一条假的「入库任务」。
  });

  it('⛔ 反向：App 侧不许登记后端没有的取值（防止照着记忆编）', () => {
    const backend = new Set(backendEnumValues());
    const extra = Object.keys(BATCH_STATUS_WORK_MAP).filter((k) => !backend.has(k));
    expect(extra).toEqual([]);
  });

  it('已入库 / 已终结的状态一律不算待办', () => {
    for (const done of ['IN_STOCK', 'AVAILABLE', 'FRESH', 'FROZEN', 'RESERVED',
      'PRODUCING_RESERVED', 'DEPLETED', 'USED_UP', 'EXPIRED', 'SCRAPPED']) {
      expect(isPendingInboundTask(done)).toBe(false);
    }
  });

  it('真正还要人处理的才算待办', () => {
    expect(toBatchWorkStatus('INSPECTING')).toBe('inspecting');
    expect(isPendingInboundTask('INSPECTING')).toBe(true);
    // 枚举注释写明「需单独处理」
    expect(isPendingInboundTask('DEFECTIVE')).toBe(true);
  });

  it('USED_UP 是本次修的那个 —— 单独钉一条', () => {
    // prod 实测 F006 有 5 条 USED_UP 被算成「入库任务」
    expect(toBatchWorkStatus('USED_UP')).toBe('completed');
    expect(toBatchWorkStatus('used_up')).toBe('completed');  // 大小写不敏感
  });

  it('RESERVED 是「已预留」不是「质检中」', () => {
    // 原来两份映射都把它判成 inspecting —— 枚举注释写的是「批次已被预留，等待使用」
    expect(toBatchWorkStatus('RESERVED')).toBe('completed');
  });

  it('认不出来的值回落 pending（宁可多显示，⛔ 不藏起来）', () => {
    expect(toBatchWorkStatus('SOMETHING_NEW')).toBe('pending');
    expect(toBatchWorkStatus(undefined)).toBe('pending');
    expect(toBatchWorkStatus('')).toBe('pending');
  });
});
