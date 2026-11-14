// 客户专属价目 (客户档案「价格」页) API client.
// Backend: CustomerPriceListController  /api/mobile/{factoryId}/customers/{customerId}/price-list
import { get, put, del } from './request';

export interface CustomerPriceItem {
  itemId: number;
  priceListId: string;
  priceListName: string;
  productTypeId: string;
  itemName: string | null;
  unit: string | null;
  /** 未税单价; 无查看价格权限时后端置 null */
  standardPrice: number | null;
  effectiveFrom: string;
  effectiveTo: string | null;
  effective: boolean;
}

/** 订单行价格来源 (后端 SalesOrderItem.priceSource), null/undefined = 历史单未记录 */
export type PriceSource = 'CUSTOMER' | 'GLOBAL' | 'LAST_DEAL' | 'MANUAL' | 'QUOTE' | 'PRICING_STRATEGY';

const PRICE_SOURCE_LABELS: Record<string, string> = {
  CUSTOMER: '客户专属价',
  GLOBAL: '统一价',
  LAST_DEAL: '最近成交价',
  MANUAL: '手填',
  QUOTE: '报价单价',
  PRICING_STRATEGY: '价格策略调整',
};

export function priceSourceLabel(source?: string | null): string {
  if (!source) return '未记录';
  return PRICE_SOURCE_LABELS[source] ?? source;
}

const base = (factoryId: string, customerId: string) =>
  `/${factoryId}/customers/${encodeURIComponent(customerId)}/price-list`;

export async function listCustomerPrices(factoryId: string, customerId: string): Promise<CustomerPriceItem[]> {
  const res = await get<CustomerPriceItem[]>(base(factoryId, customerId));
  if (!res.success) throw new Error(res.message || '加载客户价目失败');
  return Array.isArray(res.data) ? res.data : [];
}

export async function saveCustomerPrice(
  factoryId: string,
  customerId: string,
  body: { productTypeId: string; standardPrice: number },
): Promise<CustomerPriceItem> {
  const res = await put<CustomerPriceItem>(`${base(factoryId, customerId)}/items`, body);
  if (!res.success || !res.data) throw new Error(res.message || '保存失败');
  return res.data;
}

export async function deleteCustomerPrice(factoryId: string, customerId: string, itemId: number): Promise<void> {
  const res = await del(`${base(factoryId, customerId)}/items/${itemId}`);
  if (!res.success) throw new Error(res.message || '删除失败');
}
