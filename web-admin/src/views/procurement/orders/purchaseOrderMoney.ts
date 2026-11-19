/**
 * 采购订单金额口径（与销售订单同一种写法：未税 + 税额 = 含税）。
 *
 * 后端口径（PurchaseOrderItem / PurchaseServiceImpl#createPurchaseOrder）：
 * - unit_price 存【未税】单价，tax_rate 存百分数（9 表示 9%）；
 * - 行未税 = round2(数量 × 换算系数 × 未税单价)                         getLineAmount()
 * - 行含税 = round2(行未税 × (1 + 税率/100))                             getLineAmountWithTax()
 * - 行税额 = 行含税 − 行未税
 * - 单头 totalAmount = Σ 行未税（未税总额），taxAmount = Σ 行税额（税额合计）
 *   ⇒ 含税总额 = totalAmount + taxAmount，三个数按构造对得上。
 *
 * PO-20261118-0001（F006）：详情页把 totalAmount 标成「总金额」、把行未税金额标成「小计」，
 * 用户无从判断 20.18 是未税还是含税。这里只负责把同一套数字按上面的口径算出来并成组返回，
 * 不改任何存储语义。
 */

function finiteNumber(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const numeric = Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}

/** 两位小数 HALF_UP，与后端 BigDecimal.setScale(2, HALF_UP) 一致（先 toPrecision 消掉二进制尾差）。 */
export function roundMoney(value: number): number {
  const sign = value < 0 ? -1 : 1;
  const scaled = Number((Math.abs(value) * 100).toPrecision(12));
  return (sign * Math.round(scaled)) / 100;
}

export interface PurchaseMoney {
  untaxed: number;
  tax: number;
  taxIncluded: number;
}

/**
 * 一行的未税 / 税额 / 含税。
 * @param untaxedAmount 行未税金额（后端 lineAmount，或表单里 数量×未税单价 的预览值）
 * @param taxRate 百分数（9 表示 9%）；缺失时返回 null —— ⛔ 不当 0% 算
 * @param backendTaxIncluded 后端已算好的 lineAmountWithTax，有就用它，避免前后端各算一遍漂移
 */
export function purchaseLineMoney(
  untaxedAmount: unknown,
  taxRate: unknown,
  backendTaxIncluded?: unknown,
): PurchaseMoney | null {
  const rawUntaxed = finiteNumber(untaxedAmount);
  const rate = finiteNumber(taxRate);
  if (rawUntaxed === null || rate === null || rate < 0 || rate > 100) return null;
  const untaxed = roundMoney(rawUntaxed);
  const backend = finiteNumber(backendTaxIncluded);
  const taxIncluded = backend !== null ? roundMoney(backend) : roundMoney(untaxed * (1 + rate / 100));
  return { untaxed, tax: roundMoney(taxIncluded - untaxed), taxIncluded };
}

/** 单头：totalAmount 是未税总额，taxAmount 是税额合计；任一缺失（价格被剥/未算）返回 null。 */
export function purchaseOrderMoneySummary(order: {
  totalAmount?: unknown;
  taxAmount?: unknown;
} | null | undefined): PurchaseMoney | null {
  const untaxed = finiteNumber(order?.totalAmount);
  const tax = finiteNumber(order?.taxAmount);
  if (untaxed === null || tax === null) return null;
  return { untaxed: roundMoney(untaxed), tax: roundMoney(tax), taxIncluded: roundMoney(untaxed + tax) };
}

/** 后端 tax_rate 恒为百分数（0–100）。⛔ 不再把 ≤1 的值当小数乘 100 —— 0.13 就是 0.13%。 */
export function formatPurchaseTaxRate(rate: unknown): string {
  const numeric = finiteNumber(rate);
  if (numeric === null) return '-';
  return `${numeric.toFixed(2).replace(/\.?0+$/, '')}%`;
}
