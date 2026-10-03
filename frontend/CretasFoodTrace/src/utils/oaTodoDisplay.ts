import type { CreateSalesOrderRequest } from '../services/api/salesApiClient';
import { formatCurrency, formatDateTime } from './formatters';

type ProcessingMode = CreateSalesOrderRequest['processingMode'];
type MaterialSupplyMode = CreateSalesOrderRequest['materialSupplyMode'];
export type CustomerStockFulfillmentMode = 'ORDER_DRIVEN' | 'PRESTOCKED';

const PROCESSING_MODE_LABELS: Record<ProcessingMode, string> = {
  STANDARD_SALE: '普通销售',
  TOLL_PROCESSING: '代加工',
};

const MATERIAL_SUPPLY_MODE_LABELS: Record<MaterialSupplyMode, string> = {
  FACTORY_SUPPLIED: '工厂供料',
  CUSTOMER_SUPPLIED: '客户供料',
};

const CUSTOMER_STOCK_FULFILLMENT_MODE_LABELS: Record<CustomerStockFulfillmentMode, string> = {
  ORDER_DRIVEN: '订单驱动',
  PRESTOCKED: '预存货物',
};

function formatMoney(value: unknown): string {
  if (value == null || value === '') return '—';
  return formatCurrency(typeof value === 'number' ? value : String(value));
}

/** Values whose API names are implementation details should have one display contract. */
export function formatOATodoValue(key: string, value: unknown): string | undefined {
  if (key === 'shippingIncluded') {
    if (value == null || value === '') return '—';
    if (value === true || value === 'true' || value === 1 || value === '1') return '是';
    if (value === false || value === 'false' || value === 0 || value === '0') return '否';
    return String(value);
  }
  if (key === 'shippingFee') return formatMoney(value);
  if (key === 'processingMode' && typeof value === 'string') {
    return PROCESSING_MODE_LABELS[value as ProcessingMode] ?? value;
  }
  if (key === 'materialSupplyMode' && typeof value === 'string') {
    return MATERIAL_SUPPLY_MODE_LABELS[value as MaterialSupplyMode] ?? value;
  }
  if (key === 'customerStockFulfillmentMode' && typeof value === 'string') {
    return CUSTOMER_STOCK_FULFILLMENT_MODE_LABELS[value as CustomerStockFulfillmentMode] ?? value;
  }
  if (key === 'orderDate' || key === 'requiredDeliveryDate' || key === 'expectedDeliveryDate') {
    if (typeof value === 'string' || typeof value === 'number') {
      const formatted = typeof value === 'string' && /^\d{4}-\d{2}-\d{2}$/.test(value)
        ? value
        : formatDateTime(value);
      return formatted || value.toString();
    }
  }
  return undefined;
}
