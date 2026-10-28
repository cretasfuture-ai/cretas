export interface SupplierPriceForm {
  supplierId: string;
  unitPrice: number;
  /** null / undefined = 税率未知; ⛔ 不当 0% 提交 */
  taxRate: number | null | undefined;
  currency: string;
  quotedUnit: string;
  specification: string;
  minOrderQuantity: number | null | undefined;
  priceEffectiveDate: string;
  validUntil: string;
  deliveryDays: number | null | undefined;
  remark: string;
}

function textOrUndefined(value: string | null | undefined): string | undefined {
  const trimmed = (value ?? '').trim();
  return trimmed ? trimmed : undefined;
}

export function buildSupplierPricePayload(form: SupplierPriceForm) {
  const currency = textOrUndefined(form.currency);
  return {
    supplierId: form.supplierId,
    unitPrice: form.unitPrice,
    // 显式 0% 照传; 没填就不传, 后端存「未知」, 比价时标为不可比
    taxRate: form.taxRate ?? undefined,
    currency: currency ? currency.toUpperCase() : undefined,
    quotedUnit: textOrUndefined(form.quotedUnit),
    specification: textOrUndefined(form.specification),
    minOrderQuantity: form.minOrderQuantity && form.minOrderQuantity > 0 ? form.minOrderQuantity : undefined,
    priceEffectiveDate: textOrUndefined(form.priceEffectiveDate),
    validUntil: textOrUndefined(form.validUntil),
    deliveryDays: form.deliveryDays || undefined,
    remark: textOrUndefined(form.remark),
  };
}
