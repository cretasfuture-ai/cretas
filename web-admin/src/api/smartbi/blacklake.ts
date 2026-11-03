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
  projectId: number; projectCode: string; product: string; productCode: string | null; unit: string | null; status: string;
  planned: number | null; actual: number | null; finishedGood: number | null;
  materialCost: number | null; laborCost: number | null; identifiedDirectCost: number | null; unitIdentifiedDirectCost: number | null;
  materialLines: number; materialCostKnownLines: number; sourceZeroCostLines: number; taskCount: number; laborCostKnownTasks: number;
  materialLinesDetail: MaterialLine[]; processes: ProcessLine[]; issues: string[]; unitCostAvailable: boolean;
}
export interface BlacklakeAnalysis {
  costAlerts?: CostAlertReport;
  eligibleCostReview?: EligibleCostReview;
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

export interface CostDataAlert {
  id: string; type: string; family: 'data_quality' | 'business_signal' | 'readiness'; severity: 'blocking' | 'warning' | 'info';
  blocksUnitCost: boolean; title: string; message: string; owner: string; action: string; fields: string[];
  entityRefs: { projectId: number; projectCode: string; productCode: string | null; unit: string | null }[];
  evidence: Record<string, unknown>; detectedBy: string; ruleVersion: string; scope: string; runId: string; status: string;
}
export interface CostAlertReport {
  schema: string; ruleVersion: string; summary: { projects: number; costEligible: number; costBlocked: number; alerts: number; byFamily: Record<string, number>; byType: Record<string, number> };
  items: CostDataAlert[]; limitations: string[];
  ai: { status: string; message: string; model?: string; host?: string; reviews?: { alertId: string; check: string; priority: string; suggestion: string }[] };
}
export interface EligibleCostReview {
  eligibleProjects: number; products: number; limitations: string[];
  rankings: { productCode: string; product: string; unit: string; projects: number; weightedUnitCost: number; minimum: number; maximum: number }[];
  projects: (CostProject & { materialPerFinished: number; laborPerFinished: number; laborShare: number | null; components: (MaterialLine & { costPerFinished: number; usagePerFinished: number })[] })[];
}

export async function getBlacklakeAnalysis(): Promise<BlacklakeAnalysis> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakeAnalysis; message: string }>(
    '/api/smartbi/blacklake/analysis', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}
