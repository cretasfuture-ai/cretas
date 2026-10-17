export interface OrderQuote {
  id: string;
  quoteNo?: string;
  factoryId: string;
  customerId: string;
  productTypeId: string;
  status: string;
  unitPrice: number;
  unit: string;
  minOrderQty?: number | null;
  validUntil?: string | null;
}

export function canOrderFromQuote(quote: { status?: unknown; validUntil?: unknown }, today: string): boolean {
  return quote.status === 'APPROVED' && (!quote.validUntil || String(quote.validUntil) >= today);
}
