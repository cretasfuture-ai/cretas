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
