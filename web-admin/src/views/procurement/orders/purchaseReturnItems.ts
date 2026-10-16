import { canonicalUnitCode } from '@/utils/unitPricing';

export interface PurchaseReturnSourceLine {
  id?: string | number;
  materialTypeId?: unknown;
  materialName?: unknown;
  itemName?: unknown;
  unitPrice?: unknown;
  quantity?: unknown;
  receivedQuantity?: unknown;
  unit?: unknown;
  taxRate?: unknown;
  priceUnit?: unknown;
  quantityToPriceFactor?: unknown;
  purchasePackageUnitSnapshot?: unknown;
  inventoryBaseUnitSnapshot?: unknown;
  packageToBaseFactorSnapshot?: unknown;
}

function text(value: unknown): string {
  return typeof value === 'string' ? value.trim() : '';
}
function numeric(value: unknown): number | null {
  if (value === null || value === undefined || value === '') return null;
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}
function unitFactor(from: string, to: string): number | null {
  const a = canonicalUnitCode(from), b = canonicalUnitCode(to);
  if (a === b) return 1;
  const mass: Record<string, number> = { t: 1000, kg: 1, g: 0.001, mg: 0.000001 };
  const volume: Record<string, number> = { l: 1, ml: 0.001 };
  for (const units of [mass, volume]) if (units[a] && units[b]) return units[a] / units[b];
  return null;
}

/** Generic returns consume inventory units and gross base-unit prices. */
export function buildPurchaseReturnItems(items: PurchaseReturnSourceLine[], baseUnits: ReadonlyMap<string, string> = new Map()) {
  return items.map(it => {
    const materialTypeId = text(it.materialTypeId);
    const name = text(it.materialName) || text(it.itemName) || '采购物料';
    const reject = (message: string): never => { throw new Error(`${name}：${message}，不能发起退货`); };
    if (!materialTypeId) return reject('源行缺少物料');
    const baseUnit = text(it.inventoryBaseUnitSnapshot) || baseUnits.get(materialTypeId) || '';
    const purchaseUnit = text(it.purchasePackageUnitSnapshot) || text(it.unit);
    if (!baseUnit) return reject('无法确定库存基本单位');
    const declaredFactor = numeric(it.packageToBaseFactorSnapshot);
    const factor = declaredFactor ?? unitFactor(purchaseUnit, baseUnit);
    if (factor === null || factor <= 0) return reject('缺少有效的包装换算');
    const priceUnit = text(it.priceUnit) || purchaseUnit;
    const pricingFactor = numeric(it.quantityToPriceFactor) ?? (canonicalUnitCode(priceUnit) === canonicalUnitCode(purchaseUnit) ? 1 : null);
    if (pricingFactor === null || pricingFactor <= 0) return reject('缺少有效的计价换算');
    const price = numeric(it.unitPrice);
    const tax = it.taxRate == null ? 0 : numeric(it.taxRate);
    if (price === null || price <= 0) return reject('源行缺少有效单价');
    if (tax === null || tax < 0 || tax > 100) return reject('源行税率无效');
    const planned = numeric(it.quantity), received = numeric(it.receivedQuantity);
    if (planned === null || planned < 0 || received === null || received < 0) return reject('无法确定源单已收数量');
    const maxQuantity = Number((Math.min(planned, received) * factor).toFixed(8));
    if (Math.abs(maxQuantity * 100 - Math.round(maxQuantity * 100)) > 0.000001) return reject('源单数量不能按库存两位小数精确表达');
    const unitPrice = Number((price * pricingFactor / factor * (1 + tax / 100)).toFixed(4));
    if (unitPrice <= 0) return reject('基本单位单价低于计价精度');
    return { id: it.id, materialTypeId, productTypeId: null as string | null, itemName: name, unitPrice, maxQuantity, unit: baseUnit };
  });
}
