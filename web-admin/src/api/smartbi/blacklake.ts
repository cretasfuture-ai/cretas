import { pythonFetch } from './common';
import type { FactoryRecommendation } from './factory-charts';

export interface CostBridgeMaterial { material: string; materialCode: string; unit: string; baselineUsage: number | null; comparisonUsage: number | null; baselineContribution: number; comparisonContribution: number; delta: number; usageEffect: number | null; priceEffect: number | null; basis: string; }
export interface CostComparison { productCode: string; product: string; unit: string; eligibleProjects: number; visibleProjects: number; weightedUnitCost: number; minimum: number; maximum: number; candidateId: string;
  bridge: { baselineProject: string; comparisonProject: string; unit: string; delta: number; materialDelta: number; laborDelta: number; reconciliationDifference: number; materials: CostBridgeMaterial[]; selection: string; limitations: string[] } | null; }
export interface BlacklakeCostIntelligence {
  engine: string; recommendations: FactoryRecommendation[]; comparisons: CostComparison[]; answer: string; limitations: string[];
  worklist: { issue: string; projects: number }[];
  verifiedMaterialReferences: Record<string, { materialCode: string; sourceUnitQty: number | string | null; feedProcessCode: string; basis: string }[]>;
  gapEvidence: { status?: string; checkedAt?: string; negativeControlVerified?: boolean; summary?: Record<string, number>;
    unresolvedLinks?: { orderCode: string; material: string; materialCode: string; status: string; action: string }[];
    currentPriceClues?: { materialCode: string; material: string; unit: string; price: number; date: string; usage: string }[]; };
}

export interface MaterialLine { material: string; materialCode: string; unit: string; netConsumption: number | null; recognizedCost: number | null; unitPrice: number | null; orderCode: string; date: string; bizType: string; }
export interface ProcessLine { process: string; status: string; good: number | null; hours: number | null; laborCost: number | null; }
export interface CostProject {
  projectId: number; projectCode: string; product: string; productCode: string; unit: string; status: string;
  planned: number | null; actual: number | null; finishedGood: number | null;
  materialCost: number | null; laborCost: number | null; identifiedDirectCost: number | null; unitIdentifiedDirectCost: number | null;
  materialLines: number; materialCostKnownLines: number; sourceZeroCostLines: number; taskCount: number; laborCostKnownTasks: number;
  materialLinesDetail: MaterialLine[]; processes: ProcessLine[]; issues: string[]; unitCostAvailable: boolean;
}
export interface BlacklakeAnalysis {
  costIntelligence: BlacklakeCostIntelligence;
  factoryName: string; snapshotAt: string; runId: string; coverageComplete: boolean;
  catalog: { resource: string; count: number }[]; findings: string[]; limitations: string[];
  production: { projectCount: number; taskCount: number; outputCount: number; completionRate: number | null; statuses: Record<string, number>;
    planActual: { product: string; unit: string; planned: number | null; actual: number | null; projects: number; plannedMissing: number; actualMissing: number }[];
    quality: { product: string; process: string; unit: string; good: number | null; defective: number | null; goodRate: number | null; records: number; missingRecords: number }[];
    processes: { process: string; tasks: number; hours: number | null; inProgress: number; missingDurationTasks: number }[]; };
  inventory: { records: { product: string; warehouse: string; unit: string; quantity: number | null }[];
    byWarehouseUnit: { warehouse: string; unit: string; quantity: number | null; records: number; missing: number }[]; pairsNotReturned: number; movementOrders: number; };
  cost: { projects: CostProject[]; materials: { materialCode: string; material: string; unit: string; netConsumption: number | null; recognizedCost: number | null; lines: number }[];
    linkedMaterialLines: number; unlinkedMaterialLines: number; excludedUnapprovedOrders: number; knownMaterialCostLines: number; sourceZeroCostLines: number; knownLaborTasks: number; unitCostAvailableProjects: number; fullManufacturingCost: null; grossProfit: null; };
}

export async function getBlacklakeAnalysis(): Promise<BlacklakeAnalysis> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakeAnalysis; message: string }>(
    '/api/smartbi/blacklake/analysis', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}
