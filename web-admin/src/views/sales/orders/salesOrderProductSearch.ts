import { matchesAnyTextOrPinyin } from '@/utils/pinyinSearch';

export interface SalesProductOption {
  id: string | number;
  name?: string | null;
  productName?: string | null;
  code?: string | null;
  specification?: string | null;
  packageSpec?: string | null;
  productCategory?: string | null;
  category?: string | null;
  productType?: string | null;
  isSellable?: boolean | null;
  active?: boolean | null;
}

/** Sales order products are finished goods or semi-finished goods only. */
export function isSalesOrderProduct(product: SalesProductOption): boolean {
  const category = String(product.productCategory || product.category || product.productType || '').toUpperCase();
  const categoryAllowed = !category || [
    'FINISHED_PRODUCT', 'FINISHED', 'SEMI_FINISHED', 'SEMI_FINISHED_PRODUCT', 'WIP',
  ].includes(category);
  return product.isSellable !== false && product.active !== false && categoryAllowed;
}

export function salesProductSearchText(product: SalesProductOption): string[] {
  return [
    product.name,
    product.productName,
    product.code,
    product.specification,
    product.packageSpec,
  ];
}

export function matchesSalesProduct(product: SalesProductOption, query: string): boolean {
  return isSalesOrderProduct(product) && matchesAnyTextOrPinyin(salesProductSearchText(product), query);
}

export function filterSalesProducts(products: SalesProductOption[], query: string): SalesProductOption[] {
  return products.filter((product) => matchesSalesProduct(product, query));
}
