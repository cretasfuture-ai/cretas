import type { OutputOptionItem } from '../../services/api/yieldReportApi';

export interface SemiOutputPayload {
  semiCode: string;
  quantity: number;
  isRework: boolean;
}

export interface OutputPayloadResult {
  semiOutputs?: SemiOutputPayload[];
  /** 回料那一项填的数。undefined = 没填(没清点); 0 = 明确填了 0(确认零不良) —— 两者不可合并 */
  defectQuantity?: number;
}

/**
 * 把「每个产出项填了多少」折成提交载荷。
 *
 * ⛔ 不填与填 0 必须分开: 前者是「没清点」, 后者是「确认零不良」。
 *    把前者写成 0 就是编一个读数, 本仓 2026-08-26 因此让 8 个批次全部谎称 100% 良品。
 *
 * ⚠️ 契约(调用方职责, 2026-08-28 评审 Critical 补): `options` **必须已经按当前工序过滤过**
 *    (`getOutputOptions(batchId)` 返回的是整个批次所有工序的产出项, 这个函数不知道"当前工序"
 *    是谁, 也不做过滤 —— 过滤是调用点的职责, 见 `YieldStepReportScreen.tsx` 里的 `taskOptions`)。
 *
 * 🔴 `defectQuantity` = 本次调用里**所有** `isRework` 项之和 (「本道不良品总数」)。
 *    2026-08-28 整分支终审 I-4 前这里是 last-write-wins(后写覆盖前写), 靠一句 Javadoc 声明
 *    「同一次调用里最多应有一个 isRework 项」—— 而**没有任何一层保证这个前提**: 画布的
 *    `addOutputToProcess(processId, { rework: true })` 可以对同一工序点两次, 后端也不校验。
 *    于是「一道工序配两个回料去向(金属异物 / 非金属异物分开回收)」这个合法配置下, 操作员两栏
 *    各填 30 / 20 ⇒ `semiOutputs` 两条都正确入库, 而 `defectQuantity` 只有 20, 质量账少记 60%
 *    且无任何提示。求和才是这个字段的口径。
 *
 * ⛔ 求和不许把「都填了 0」折成「没填」: 一个 isRework 项都没填 → `undefined`;
 *    填了(哪怕都是 0) → 求和结果(可能是 0)。那正是 2026-08-26 事故要分开的两件事。
 */
export function buildSemiOutputsPayload(
  options: OutputOptionItem[],
  quantities: Record<string, string>,
): OutputPayloadResult {
  if (!options || options.length === 0) return {};

  const semiOutputs: SemiOutputPayload[] = [];
  let defectQuantity: number | undefined;

  for (const opt of options) {
    const code = opt.semiCode;
    if (!code) continue;
    const raw = quantities[code];
    if (raw === undefined || raw.trim() === '') continue;   // 没填 → 跳过, 不产生 0
    const n = Number.parseFloat(raw);
    if (Number.isNaN(n) || n < 0) continue;
    // 填了就记, 包括 0。多个回料项求和 —— ⛔ 不是后写覆盖前写(那会静默漏记, 见函数注释 I-4)。
    if (opt.isRework) defectQuantity = (defectQuantity ?? 0) + n;
    if (n > 0) semiOutputs.push({ semiCode: code, quantity: n, isRework: !!opt.isRework });
  }

  return {
    ...(semiOutputs.length > 0 ? { semiOutputs } : {}),
    ...(defectQuantity !== undefined ? { defectQuantity } : {}),
  };
}
