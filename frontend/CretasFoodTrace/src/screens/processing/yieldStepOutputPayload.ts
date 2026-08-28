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
 *    并且**同一次调用里最多应有一个 `isRework: true` 的项**(一道工序至多一个回料端口)。
 *    若违反后一条(调用方传入了两个及以上 isRework 项), 本函数的行为是明确但非直觉的:
 *    `semiOutputs` 里两条都会保留, 但 `defectQuantity` 只取**最后一条**(循环内后写覆盖前写)——
 *    不做去重/报错。这不是推荐用法, 只是把行为钉死, 不要在这层加过滤逻辑
 *    (纯函数不该知道"当前工序"是谁)。
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
    if (opt.isRework) defectQuantity = n;                    // 填了就记, 包括 0
    if (n > 0) semiOutputs.push({ semiCode: code, quantity: n, isRework: !!opt.isRework });
  }

  return {
    ...(semiOutputs.length > 0 ? { semiOutputs } : {}),
    ...(defectQuantity !== undefined ? { defectQuantity } : {}),
  };
}
