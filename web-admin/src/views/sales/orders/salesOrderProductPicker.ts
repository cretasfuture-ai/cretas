import { filterSalesProducts, type SalesProductOption } from './salesOrderProductSearch';

export type SalesProductCategoryFilter = 'ALL' | 'FINISHED_PRODUCT' | 'SEMI_FINISHED';

export interface SalesProductInventorySummary {
  productTypeId: string;
  availableQuantity: number;
  unit?: string | null;
}

export interface SalesProductPickerOption extends SalesProductOption {
  availableQuantity?: number;
  inventoryUnit?: string;
  customerHistoryCount?: number;
}

export function salesProductCategory(product: SalesProductOption): 'FINISHED_PRODUCT' | 'SEMI_FINISHED' {
  const value = String(product.productCategory || product.category || product.productType || '').toUpperCase();
  return value.includes('SEMI') || value === 'WIP' ? 'SEMI_FINISHED' : 'FINISHED_PRODUCT';
}

export function productIdsFromCustomerOrders(orders: Array<{ items?: Array<{ productTypeId?: string | number | null }> }>): Map<string, number> {
  const counts = new Map<string, number>();
  for (const order of orders) {
    for (const item of order.items || []) {
      const id = String(item.productTypeId || '');
      if (id) counts.set(id, (counts.get(id) || 0) + 1);
    }
  }
  return counts;
}

export function buildSalesProductPickerOptions(
  products: SalesProductOption[],
  inventory: SalesProductInventorySummary[],
  historyCounts: Map<string, number>,
  query: string,
  category: SalesProductCategoryFilter,
): SalesProductPickerOption[] {
  const inventoryByProduct = new Map(inventory.map((row) => [String(row.productTypeId), row]));
  return filterSalesProducts(products, query)
    .filter((product) => category === 'ALL' || salesProductCategory(product) === category)
    .map((product) => {
      const stock = inventoryByProduct.get(String(product.id));
      return {
        ...product,
        availableQuantity: Number(stock?.availableQuantity || 0),
        inventoryUnit: String(stock?.unit || ''),
        customerHistoryCount: historyCounts.get(String(product.id)) || 0,
      };
    })
    .sort((left, right) => {
      const history = Number(right.customerHistoryCount || 0) - Number(left.customerHistoryCount || 0);
      if (history) return history;
      const stock = Number(right.availableQuantity || 0) - Number(left.availableQuantity || 0);
      if (stock) return stock;
      return String(left.name || left.productName || left.code || '').localeCompare(
        String(right.name || right.productName || right.code || ''),
        'zh-CN',
      );
    });
}
