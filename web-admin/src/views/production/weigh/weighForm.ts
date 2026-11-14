/**
 * 迁移 P5 称重报工表单（纯函数，前端字段旁提示；服务端 WeighingEntryRules 同口径兜底）。
 *
 * 口径（原型 R23/R25/R34/R36）：
 *   良品净重 = 良品毛重 − 皮重；工序出成率 = 良品净重 ÷ 投入；单位 kg，两位小数。
 *   R36：开始 / 结束时间晚于现在 5 分钟以上 ⇒ 字段旁提示「时间晚于现在」，不能保存。
 *   R34：出成率 ≥ 100%、开始 = 结束 ⇒ 只提示「会标为数据疑点」，不拦（不改原值，由车间核对）。
 *   时间按北京时间：报工日期 + HH:mm；结束早于开始视为跨零点（结束算次日）。
 */
import type { WeighingSubmitPayload } from '@/api/weighing';

export const FUTURE_TOLERANCE_MS = 5 * 60 * 1000;
const BJ_OFFSET = '+08:00';

export interface WeighDraft {
  inputKg: string;
  goodGrossKg: string;
  tareKg: string;
  defectKg: string;
  /** YYYY-MM-DD（北京时间） */
  date: string;
  /** HH:mm */
  start: string;
  end: string;
}

export type WeighField = keyof WeighDraft;

export interface WeighCheck {
  errors: Partial<Record<WeighField, string>>;
  warnings: Partial<Record<WeighField, string>>;
  /** 良品净重（kg）；毛重或皮重不合法时为 null */
  netKg: number | null;
  /** 本笔出成率（0~1+）；算不出为 null */
  yieldRate: number | null;
  ok: boolean;
}

const r2 = (n: number) => Math.round(n * 100) / 100;

function num(v: string): number | null | 'bad' {
  const t = (v ?? '').trim();
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : 'bad';
}

/** 北京时间的「今天」YYYY-MM-DD 与「现在」HH:mm。 */
export function beijingNow(now: Date): { date: string; time: string } {
  const bj = new Date(now.getTime() + 8 * 3600 * 1000);
  const p = (n: number) => String(n).padStart(2, '0');
  return {
    date: `${bj.getUTCFullYear()}-${p(bj.getUTCMonth() + 1)}-${p(bj.getUTCDate())}`,
    time: `${p(bj.getUTCHours())}:${p(bj.getUTCMinutes())}`,
  };
}

function at(date: string, hhmm: string): number | null {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || !/^\d{1,2}:\d{2}$/.test(hhmm)) return null;
  const [h, m] = hhmm.split(':');
  const t = Date.parse(`${date}T${h.padStart(2, '0')}:${m}:00${BJ_OFFSET}`);
  return Number.isFinite(t) ? t : null;
}

export function checkWeigh(d: WeighDraft, now: Date): WeighCheck {
  const errors: WeighCheck['errors'] = {};
  const warnings: WeighCheck['warnings'] = {};

  const input = num(d.inputKg);
  if (input === null) errors.inputKg = '请填投入重量';
  else if (input === 'bad') errors.inputKg = '投入重量不是数字';
  else if (input <= 0) errors.inputKg = '投入重量要大于 0';

  const gross = num(d.goodGrossKg);
  if (gross === null) errors.goodGrossKg = '请填良品毛重（含容器）';
  else if (gross === 'bad') errors.goodGrossKg = '良品毛重不是数字';
  else if (gross < 0) errors.goodGrossKg = '良品毛重不能是负数';

  const tare = num(d.tareKg);
  if (tare === 'bad') errors.tareKg = '皮重不是数字';
  else if (tare !== null && tare < 0) errors.tareKg = '皮重不能是负数';
  else if (tare !== null && typeof gross === 'number' && tare > gross) {
    errors.tareKg = `皮重 ${tare} kg 大于良品毛重 ${gross} kg，请核对`;
  }

  const defect = num(d.defectKg);
  if (defect === 'bad') errors.defectKg = '不良重量不是数字';
  else if (defect !== null && defect < 0) errors.defectKg = '不良重量不能是负数';

  let netKg: number | null = null;
  if (typeof gross === 'number' && gross >= 0 && !errors.tareKg) netKg = r2(gross - (typeof tare === 'number' ? tare : 0));
  let yieldRate: number | null = null;
  if (netKg !== null && typeof input === 'number' && input > 0) {
    yieldRate = netKg / input;
    if (yieldRate >= 1) {
      warnings.goodGrossKg = `出成率 ${(yieldRate * 100).toFixed(2)}%，达到或超过 100%，保存后会标为「数据疑点 · 找谁核」`;
    }
  }

  const s = at(d.date, d.start);
  let e = at(d.date, d.end);
  if (!d.date) errors.date = '请选报工日期';
  if (s === null) errors.start = '请填开始时间';
  if (e === null) errors.end = '请填结束时间';
  if (s !== null && e !== null) {
    if (d.start === d.end) warnings.end = '开始和结束时间相同，保存后会标为「数据疑点 · 找谁核」';
    else if (e < s) e += 24 * 3600 * 1000;
  }
  const limit = now.getTime() + FUTURE_TOLERANCE_MS;
  if (s !== null && s > limit) errors.start = '时间晚于现在（超过 5 分钟），请改成已经发生的时间';
  if (e !== null && e > limit) errors.end = '时间晚于现在（超过 5 分钟），请改成已经发生的时间';

  return { errors, warnings, netKg, yieldRate, ok: Object.keys(errors).length === 0 };
}

export function buildPayload(d: WeighDraft, taskId: number, clientRequestId: string, netKg: number): WeighingSubmitPayload {
  const tare = num(d.tareKg);
  const defect = num(d.defectKg);
  return {
    workProcessTaskId: taskId,
    clientRequestId,
    inputQuantity: r2(Number(d.inputKg)),
    inputUnit: 'kg',
    outputQuantity: netKg,
    outputUnit: 'kg',
    tareWeight: typeof tare === 'number' ? r2(tare) : null,
    defectQuantity: typeof defect === 'number' ? r2(defect) : null,
    laborSegments: [{ startTime: d.start, endTime: d.end }],
    businessDate: d.date,
    markComplete: false,
  };
}

/** 服务端 hintTarget（如 laborSegments[0].endTime / tareWeight）映射回表单字段，错误显示在字段旁。 */
export function fieldOfHintTarget(target: string | undefined | null): WeighField | null {
  if (!target) return null;
  if (/startTime$/.test(target)) return 'start';
  if (/endTime$/.test(target)) return 'end';
  if (target === 'tareWeight') return 'tareKg';
  if (target === 'inputQuantity') return 'inputKg';
  if (target === 'outputQuantity') return 'goodGrossKg';
  if (target === 'businessDate') return 'date';
  return null;
}
