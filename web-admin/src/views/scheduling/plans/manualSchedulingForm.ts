export interface ManualScheduleDraft {
  batchId: number;
  productionLineId: string;
  plannedQuantity: number | string;
  plannedStartTime: string;
  plannedEndTime: string;
  sourceQuantity?: number;
  sourceUnit?: string;
}

export interface ManualSchedulePayload {
  planDate: string;
  notes?: string;
  schedules: Array<{
    productionLineId: string;
    batchId: number;
    sequenceOrder: number;
    plannedStartTime: string;
    plannedEndTime: string;
    plannedQuantity: number;
  }>;
}

/**
 * The API maps this field to Java LocalDateTime: it is a factory-local wall
 * clock value and must not carry a timezone suffix or be converted to UTC.
 */
function toLocalDateTime(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2})?$/.test(value)) {
    throw new Error('开始和结束时间必须是有效的本地时间');
  }
  return value.length === 16 ? `${value}:00` : value;
}

function parseDateTime(value: string): number {
  const timestamp = new Date(value).getTime();
  return Number.isFinite(timestamp) ? timestamp : NaN;
}

export function validateManualSchedules(drafts: ManualScheduleDraft[]): string[] {
  const errors: string[] = [];
  if (drafts.length === 0) return ['请至少选择一个生产批次'];

  drafts.forEach((draft, index) => {
    const label = `第${index + 1}个批次`;
    if (!draft.productionLineId) errors.push(`${label}未选择生产线`);
    if (!draft.sourceUnit?.trim()) errors.push(`${label}缺少基本单位，请先确认批次单位`);
    const quantity = Number(draft.plannedQuantity);
    if (!Number.isInteger(quantity) || quantity <= 0) {
      errors.push(`${label}的基本单位数量必须是大于0的整数`);
    }
    const start = parseDateTime(draft.plannedStartTime);
    const end = parseDateTime(draft.plannedEndTime);
    if (!Number.isFinite(start) || !Number.isFinite(end)) {
      errors.push(`${label}必须填写有效的开始和结束时间`);
    } else if (end <= start) {
      errors.push(`${label}的结束时间必须晚于开始时间`);
    }
  });
  return errors;
}

export function buildManualSchedulePayload(
  planDate: string,
  drafts: ManualScheduleDraft[],
  notes?: string,
): ManualSchedulePayload {
  const errors = validateManualSchedules(drafts);
  if (errors.length > 0) throw new Error(errors.join('；'));
  return {
    planDate,
    ...(notes?.trim() ? { notes: notes.trim() } : {}),
    schedules: drafts.map((draft, index) => ({
      productionLineId: draft.productionLineId,
      batchId: Number(draft.batchId),
      sequenceOrder: index + 1,
      plannedStartTime: toLocalDateTime(draft.plannedStartTime),
      plannedEndTime: toLocalDateTime(draft.plannedEndTime),
      plannedQuantity: Number(draft.plannedQuantity),
    })),
  };
}
