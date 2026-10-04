export type EquipmentOeeMetrics = {
  oee: number;
};

export type EquipmentEfficiencyMetrics = {
  actualRunningHours: number | null;
  plannedHours: number | null;
  utilizationRate: number | null;
  availability: number | null;
};

type JsonObject = Record<string, unknown>;

function isObject(value: unknown): value is JsonObject {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function finiteNonNegative(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 ? value : null;
}

export function normalizePercentagePoint(value: unknown): number | null {
  const number = finiteNonNegative(value);
  return number !== null && number <= 100 ? number : null;
}

export function normalizeOeeScalar(value: unknown): EquipmentOeeMetrics | null {
  const oee = normalizePercentagePoint(value);
  return oee === null ? null : { oee };
}

export function normalizeEfficiencyReport(value: unknown, expectedEquipmentId: number): EquipmentEfficiencyMetrics | null {
  if (!isObject(value) || String(value.equipmentId) !== String(expectedEquipmentId)) return null;

  const actualRunningHours = value.actualRunningHours === null || value.actualRunningHours === undefined
    ? null
    : finiteNonNegative(value.actualRunningHours);
  const plannedHours = value.plannedHours === null || value.plannedHours === undefined
    ? null
    : finiteNonNegative(value.plannedHours);

  return {
    actualRunningHours,
    plannedHours,
    utilizationRate: normalizePercentagePoint(value.utilizationRate),
    availability: normalizePercentagePoint(value.availability),
  };
}
