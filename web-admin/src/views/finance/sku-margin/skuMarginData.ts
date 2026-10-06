/**
 * SKU 毛利率页的数据读取 (纯函数, 便于单测)。
 *
 * 数据来源: POST /{factoryId}/ai-intents/execute → 后端 report_sku_gross_margin 工具。
 * 一路上的包装 (ToolDispatchService.parseToolResultToResponse + AbstractTool.buildSuccessResult):
 *
 *   ApiResponse.data = IntentExecuteResponse
 *     .status      "SUCCESS" | "FAILED" | "NEED_CLARIFICATION" | ...
 *     .message     给人看的文案
 *     .resultData  = 工具 buildSimpleResult(message, data) 的 { message, data }
 *         .data    = { items[], totalProducts, truncated, realizedPeriod, rateUnit }
 *
 * 所以 items 的真实位置是 `data.resultData.data.items`。旧页面读的是
 * `data.data || data.result || data` —— IntentExecuteResponse 上没有 data / result 这两个键,
 * 于是 payload 落回响应本身, 找不到 items, 每次都掉进「数据待接入」。
 *
 * 两种毛利口径分开给, 绝不互相顶替; 算不出来的数一律是 null, 页面显示「—」并带原因, 不当成 0。
 */

export interface RealizedPart {
  status: string;
  reason: string | null;
  note: string | null;
  /** 已回填成本的明细收入 (毛利率分母) */
  revenue: number | null;
  cost: number | null;
  grossProfit: number | null;
  /** 百分数: 42.5 = 42.5% */
  marginRate: number | null;
  /** 百分数: 已回填成本的收入 / 全部收入 */
  costCoverage: number | null;
  missingPriceLineCount: number | null;
}

export interface CatalogPart {
  status: string;
  reason: string | null;
  price: number | null;
  unitCost: number | null;
  /** 百分数 */
  marginRate: number | null;
  costBatchNumber: string | null;
}

export interface SkuMarginRow {
  productName: string;
  quantity: number | null;
  realized: RealizedPart;
  catalog: CatalogPart;
}

export interface SkuMarginPeriod {
  startDate: string | null;
  endDate: string | null;
  defaultPeriod: boolean;
}

export interface SkuMarginReadResult {
  rows: SkuMarginRow[];
  period: SkuMarginPeriod | null;
  truncated: boolean;
  totalProducts: number | null;
  /** 没有行的时候, 后端给的原因 (或「返回里没有可展示的明细」); 有行时为 null */
  emptyMessage: string | null;
}

type Obj = Record<string, unknown>;

function isObj(v: unknown): v is Obj {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** null / undefined / 非有限数 → null。⛔ 不兜成 0: 0 是合法读数, 「算不出」不是。 */
export function toNum(v: unknown): number | null {
  if (v === null || v === undefined || v === '') return null;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

function toStr(v: unknown): string | null {
  return typeof v === 'string' && v.length > 0 ? v : null;
}

function readRealized(raw: unknown): RealizedPart {
  const r: Obj = isObj(raw) ? raw : {};
  return {
    status: toStr(r.status) ?? 'UNKNOWN',
    reason: toStr(r.reason),
    note: toStr(r.note),
    revenue: toNum(r.revenue),
    cost: toNum(r.cost),
    grossProfit: toNum(r.grossProfit),
    marginRate: toNum(r.marginRate),
    costCoverage: toNum(r.costCoverage),
    missingPriceLineCount: toNum(r.missingPriceLineCount),
  };
}

function readCatalog(raw: unknown): CatalogPart {
  const c: Obj = isObj(raw) ? raw : {};
  return {
    status: toStr(c.status) ?? 'UNKNOWN',
    reason: toStr(c.reason),
    price: toNum(c.price),
    unitCost: toNum(c.unitCost),
    marginRate: toNum(c.marginRate),
    costBatchNumber: toStr(c.costBatchNumber),
  };
}

/**
 * 读 `ai-intents/execute` 的 ApiResponse.data (即 IntentExecuteResponse)。
 * 只认 `resultData.data.items` 这一个位置, 不再猜多种形状。
 */
export function readSkuMarginResponse(res: unknown): SkuMarginReadResult {
  const empty = (message: string | null): SkuMarginReadResult => ({
    rows: [], period: null, truncated: false, totalProducts: null, emptyMessage: message,
  });
  if (!isObj(res)) return empty('后端没有返回结果');
  const outerMessage = toStr(res.message);

  const resultData = res.resultData;
  const inner = isObj(resultData) ? resultData.data : null;
  if (!isObj(inner) || !Array.isArray(inner.items)) {
    return empty(outerMessage ?? '返回内容里没有可展示的 SKU 明细');
  }

  const rows: SkuMarginRow[] = (inner.items as unknown[]).filter(isObj).map((item) => ({
    productName: toStr(item.productName) ?? '(未命名产品)',
    quantity: toNum(item.quantity),
    realized: readRealized(item.realized),
    catalog: readCatalog(item.catalog),
  }));
  if (rows.length === 0) {
    const toolMessage = isObj(resultData) ? toStr(resultData.message) : null;
    return empty(toolMessage ?? outerMessage ?? '没有可计算毛利率的产品');
  }

  const p = inner.realizedPeriod;
  const period: SkuMarginPeriod | null = isObj(p)
    ? { startDate: toStr(p.startDate), endDate: toStr(p.endDate), defaultPeriod: p.defaultPeriod === true }
    : null;
  return {
    rows,
    period,
    truncated: inner.truncated === true,
    totalProducts: toNum(inner.totalProducts),
    emptyMessage: null,
  };
}

export const NA = '—';

export function fmtPct(v: number | null | undefined): string {
  return v === null || v === undefined ? NA : `${(Math.round(v * 10) / 10).toFixed(1)}%`;
}

export function fmtMoney(v: number | null | undefined): string {
  return v === null || v === undefined
    ? NA
    : '¥' + v.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}
