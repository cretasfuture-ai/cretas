export interface DynamicQualityItem {
  bindingId: string;
  itemId: string;
  itemVersion: number;
  label: string;
  valueType?: string;
  unit?: string | null;
  effectiveStandardValue?: string | null;
  effectiveMinValue?: number | null;
  effectiveMaxValue?: number | null;
  effectiveIsRequired?: boolean;
}

export interface DynamicQualityForm {
  batchId: string;
  productTypeId: string;
  productName: string;
  standardRevision: string;
  items: DynamicQualityItem[];
}

export interface DynamicQualityAnswer {
  bindingId: string;
  itemId: string;
  itemVersion: number;
  value: string;
}

export interface DynamicQualitySubmitResponse {
  inspection?: { id?: string | number; result?: string };
  standardRevision?: string;
}

export function hasDynamicItems(form: DynamicQualityForm | null | undefined): boolean {
  return Array.isArray(form?.items) && form.items.length > 0;
}

export function isDynamicFormForBatch(
  form: DynamicQualityForm | null | undefined,
  batchId: string,
): boolean {
  return Boolean(form && String(form.batchId) === String(batchId) && form.standardRevision);
}

export function requiredDynamicItems(
  form: DynamicQualityForm,
  values: Record<string, string>,
): DynamicQualityItem[] {
  return form.items.filter((item) => item.effectiveIsRequired === true && !values[item.bindingId]?.trim());
}

export function itemIsOutOfRange(item: DynamicQualityItem, rawValue: string | undefined): boolean {
  if (!rawValue?.trim() || item.valueType !== 'RANGE') return false;
  const value = Number(rawValue);
  if (!Number.isFinite(value)) return false;
  return (item.effectiveMinValue != null && value < item.effectiveMinValue)
    || (item.effectiveMaxValue != null && value > item.effectiveMaxValue);
}

export function outOfRangeDynamicItems(
  form: DynamicQualityForm,
  values: Record<string, string>,
): DynamicQualityItem[] {
  return form.items.filter((item) => itemIsOutOfRange(item, values[item.bindingId]));
}

export function buildDynamicAnswers(
  form: DynamicQualityForm,
  values: Record<string, string>,
): DynamicQualityAnswer[] {
  return form.items.map((item) => ({
    bindingId: item.bindingId,
    itemId: item.itemId,
    itemVersion: item.itemVersion,
    value: values[item.bindingId] ?? '',
  }));
}
