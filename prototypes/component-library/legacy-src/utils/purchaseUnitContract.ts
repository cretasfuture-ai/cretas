import {
  canonicalSystemUnitCode,
  findDuplicateUnit,
  normalizeUnitIdentity,
  type SystemUnit,
} from '@/api/systemUnits';

const INTRINSIC_DIMENSIONS = new Set(['MASS', 'VOLUME', 'LENGTH']);

export interface PurchaseUnitResolution {
  compatibleUnits: SystemUnit[];
  defaultUnit: string;
  reason: string;
}

function dimension(unit: SystemUnit): string {
  const value = String(unit.category || '').trim().toUpperCase();
  return value === 'WEIGHT' ? 'MASS' : value;
}

function baseIdentity(unit: SystemUnit): string {
  return normalizeUnitIdentity(unit.baseUnit || unit.unitCode);
}

export function isDirectPurchaseUnitCompatible(base: SystemUnit, candidate: SystemUnit): boolean {
  if (normalizeUnitIdentity(base.unitCode) === normalizeUnitIdentity(candidate.unitCode)) return true;
  const baseDimension = dimension(base);
  return baseDimension === dimension(candidate)
    && INTRINSIC_DIMENSIONS.has(baseDimension)
    && Boolean(baseIdentity(base))
    && baseIdentity(base) === baseIdentity(candidate);
}

export function resolvePurchaseUnits(
  materialUnit: string | null | undefined,
  allUnits: SystemUnit[],
  purchaseUnits: SystemUnit[],
): PurchaseUnitResolution {
  const display = String(materialUnit || '').trim() || '未配置';
  const base = findDuplicateUnit(allUnits, [materialUnit]);
  if (!base) {
    return {
      compatibleUnits: [],
      defaultUnit: '',
      reason: `物料库存基本单位「${display}」未登记，无法判断采购量纲。请先修正物料基本单位，再选择合法采购规格。`,
    };
  }

  const compatibleUnits = purchaseUnits.filter((candidate) => isDirectPurchaseUnitCompatible(base, candidate));
  const exact = findDuplicateUnit(compatibleUnits, [base.unitCode, materialUnit]);
  if (compatibleUnits.length === 0) {
    return {
      compatibleUnits,
      defaultUnit: '',
      reason: `物料库存基本单位「${base.unitName || display}」不允许用于采购数量。请选择与库存基本单位同量纲的合法采购单位；若按箱、卷等包装采购，请新增采购规格并配置到库存基本单位的明确换算。`,
    };
  }
  if (!exact) {
    return {
      compatibleUnits,
      defaultUnit: '',
      reason: `物料库存基本单位「${base.unitName || display}」不能直接作为采购单位。请从同量纲单位中选择；若按包装采购，请新增采购规格并配置明确换算。`,
    };
  }
  return {
    compatibleUnits,
    defaultUnit: canonicalSystemUnitCode(exact.unitCode),
    reason: '',
  };
}

export function purchaseUnitSelectionReason(
  selectedUnit: string | null | undefined,
  resolution: PurchaseUnitResolution,
): string {
  if (!selectedUnit) return resolution.reason || '请选择合法采购单位。';
  return findDuplicateUnit(resolution.compatibleUnits, [selectedUnit])
    ? ''
    : '所选采购单位与物料库存基本单位量纲不兼容；请改选同量纲单位，包装采购必须配置明确换算。';
}
