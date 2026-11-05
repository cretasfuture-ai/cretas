import { pythonFetch } from './common';
import type { FactoryRecommendation } from './factory-charts';

export interface CostDataRequest {
  id: string; code: string; workOrderId: number | string; workOrderCode: string;
  missingData: string; responsibleRole: string; afterProvided: string; sourceStatus: string;
  impactType: string; priority: string; sourceDocuments?: string[];
}

export interface CompleteCostFact {
  factId: string; cohort: 'original44' | 'eligible26' | 'eligible_live'; sectionId: string; kind: string;
  historical?:boolean;engine?:string;sourceRunId?:string;sourceCheckedAt?:string;
  whatItMeans: string; whyItMatters: string; nextStep: string; owner: string; cannotConclude: string; hypotheses: string[];
  display: { text: string; entityRefs?: { projectId: number; projectCode?: string }[] };
}
export interface CompleteCostReport {
  schema: string; sourceRunId: string; sourceCheckedAt: string; analysis: BlacklakeAnalysis & { costTraceability: CostTraceabilityReport };
  contract: { originalRemainingFacts: number; eligibleOrders: number; inventoryBoundary: string;live?:boolean };
  batchAnalysis: { status: string; expectedFacts: number; analyzedFacts: number; remainingFacts: number; results: CompleteCostFact[];
    cohorts: Record<string, { expected: number; analyzed: number }>; usage: LocalAIUsage | null; usageUnknownCalls?: number; jobs: Record<string, number> };
}
export async function getBlacklakeCompleteCost(): Promise<CompleteCostReport> {
  const response = await pythonFetch<{ success: boolean; data: CompleteCostReport; message: string }>(
    '/api/smartbi/blacklake/complete-cost-analysis', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}
export async function followupBlacklakeCompleteCost(runId: string, factId: string, question: string): Promise<string> {
  const response = await pythonFetch<{ success: boolean; data: { answer: string }; message: string }>(
    '/api/smartbi/blacklake/complete-cost-analysis/followup', { method: 'POST', preserveResponseKeys: true,
      body: JSON.stringify({ run_id: runId, fact_id: factId, question }) });
  if (!response.success) throw new Error(response.message);
  return response.data.answer;
}
export interface TraceableWorkOrder {
  workOrderId: number | string; workOrderCode: string; product: string; productCode: string;
  unit: string | null; identifiedUnitCost: number | null; directCostAvailable: boolean;
  directCostBlockingReasons: string[]; needs: CostDataRequest[];
  businessLinks: { sourceDocumentCode?: string; materialName?: string; feedProcessName?: string | null; processId: number | string | null }[];
  providedClues: { label: string; value: string | number; usage: string }[];
  baselineComparison?: { directCostAvailable: boolean };
}
export interface CostTraceabilityReport {
  schema: string; checkedAt: string; workOrders: TraceableWorkOrder[];
  summary: { workOrders: number; ordersWithDirectCost: number; materialOriginLinks: number; materialProcessLinks: number };
  baselineComparison?: { newlyFieldsEligibleOriginallyBlocked: number; previouslyEligibleNowBlocked: number; stillBlockedOriginalCohort: number };
  sourceProbe?: { allRequiredQueriesComplete: boolean; newOrdersOutsideOriginalCohort: number };
}
export async function getBlacklakeCostTraceability(): Promise<CostTraceabilityReport> {
  const response = await pythonFetch<{ success: boolean; data: CostTraceabilityReport; message: string }>(
    '/api/smartbi/blacklake/cost-traceability?mode=latest', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

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
  operationsSupplement?:{checkedAt:string;reportingUnitsRecovered:number;outputRecords:number;unresolvedOutputs:{outputId:number|string;projectId:number|string;reason:string}[];inventoryRecords:number;stockPairsQueried:number;stockPairsNotReturned:number;requests:{code:string;status:string;missing:string;owner:string;action:string;afterProvided:string}[]};
  latestCompletedDate?:string|null;
  operationalSource?:{workOrdersCheckedAt:string;inventoryCheckedAt:string|null;materialReferencesCheckedAt?:string;bomReferencesCheckedAt?:string;referenceExplanation?:string;baselineAnchor:string;observationId:string};
  alertFollowups?: Record<string, AlertFollowup>;
  periodMetadata?: BlacklakePeriodMetadata;
  sectionInsights?: SectionInsightReport[];
  costAlerts?: CostAlertReport;
  eligibleCostReview?: EligibleCostReview;
  costIntelligence: BlacklakeCostIntelligence;
  factoryName: string; snapshotAt: string; runId: string; coverageComplete: boolean;
  catalog: { resource: string; count: number }[]; findings: string[]; limitations: string[];
  production: { projectCount: number; taskCount: number; outputCount: number; completionRate: number | null; statuses: Record<string, number>;approvedOutputCount?:number;unapprovedOutputCount?:number;completionRateApplicable?:boolean;
    planActual: { productCode?:string|null;product: string; unit: string; planned: number | null; actual: number | null; projects: number; plannedMissing: number; actualMissing: number }[];
    quality: { productCode?:string|null;processId?:number|string|null;product: string; process: string; unit: string; good: number | null; defective: number | null; goodRate: number | null; records: number; missingRecords: number }[];
    processes: { processId?:number|string|null;projectIds?:(number|string)[];process: string; tasks: number; hours: number | null; inProgress: number; missingDurationTasks: number }[]; };
  inventory: { reconciliations?:import('./blacklake-live').StockReconciliationRow[]; records: { productCode?:string|null;warehouseKey?:string;warehouseCode?:string;product: string; warehouse: string; unit: string; quantity: number | null }[];
    byWarehouseUnit: { warehouseKey?:string;warehouse: string; unit: string; quantity: number | null; records: number; missing: number }[]; pairsNotReturned: number | null; movementOrders: number;balanceAsOf?:string|null;lastAvailableBalanceAt?:string|null; };
  cost: { projects: CostProject[]; projectDates?: Record<string, { completedAt: string | null; createdAt: string | null }>; materials: { materialCode: string; material: string; unit: string; netConsumption: number | null; recognizedCost: number | null; lines: number;missingCostLines?:number }[];
    linkedMaterialLines: number; unlinkedMaterialLines: number; excludedUnapprovedOrders: number; knownMaterialCostLines: number; sourceZeroCostLines: number; knownLaborTasks: number; unitCostAvailableProjects: number; fullManufacturingCost: null; grossProfit: null; };
}

export type BlacklakePeriodBasis = 'completed' | 'created';
export type BlacklakePeriodKind = 'day' | 'week' | 'month' | 'quarter' | 'year' | 'custom';
export interface BlacklakePeriodQuery { start_date: string; end_date: string; basis: BlacklakePeriodBasis; kind: BlacklakePeriodKind; }
export interface BlacklakePeriodMetadata {
  startDate: string; endDate: string; kind: BlacklakePeriodKind; basis: BlacklakePeriodBasis; timezone: string;
  fingerprint: string; scopeDescription: string; dataAsOf: string; unassignedProjects: number; selectedProjects: number;
  coverageStatus: string; inventoryStatus: string; unassignedMovementOrders?: number;
  unassignedCompletedProjects?:number;inventoryAsOf?:string|null;lastAvailableInventoryAsOf?:string|null;latestCompletedDate?:string|null;
  unassignedCompletedProjectIds?:(number|string)[];
  unassignedProjectIds?:(number|string)[];
}
export interface BlacklakePeriodJob {
  id: string; status: 'queued' | 'running' | 'ready' | 'failed'; period: Omit<BlacklakePeriodMetadata, 'fingerprint' | 'scopeDescription' | 'dataAsOf' | 'unassignedProjects' | 'selectedProjects' | 'coverageStatus' | 'inventoryStatus'>;
  fingerprint: string; revision: number; definitionVersion?: string | null; fromCache?: boolean; nextAttemptAt?: string | null; createdAt: string; finishedAt?: string | null;
  result?: { generatedAt?: string | null; sectionInsights: SectionInsightReport[]; summary: string; observations: string[]; history: { comparisonAvailable?: boolean; usedReports: { id: string; startDate: string; endDate: string; relation: string; sameSourceSnapshot?: boolean; dataAsOf?: string }[]; recurringIssues: string[]; newIssues: string[]; resolvedIssues: string[] }; ai: CostAlertReport['ai'] };
  error?: string;
}
export interface BlacklakePeriodSchedule { enabled: boolean; timezone: string; rules: { label: string; description: string }[]; lastTick?: string; message?: string; }

export interface SectionInsightReport {
  localAnalysis?: import('./local-analysis').LocalAnalysisSection;
  ruleSummary?: string;
  sectionId: string; title: string; summary: string; observations: string[]; calculation: string[];
  hypotheses: string[]; limitations: string[]; nextSteps: string[]; suggestedQuestions: string[];
  scope: { runId: string; projectId?: number }; evidenceRefs: string[];
  scopeDescription?: string;
  evidenceSources?: { label: string; description: string }[];
  relatedOrders?: { projectId: number; projectCode: string; product: string; reasons: string[] }[];
}
export async function askBlacklakeSection(payload: {
  section_id: string; question: string; run_id: string;
  alert_id?: string;
  period?: BlacklakePeriodQuery;
  history?: { role: 'user' | 'assistant'; content: string }[];
}): Promise<{ answer: string; sectionId: string; runId: string; alertId?: string | null; engine: string; ai?: { status: string; model?: string; message?: string } }> {
  const response = await pythonFetch<{ success: boolean; data: { answer: string; sectionId: string; runId: string; alertId?: string | null; engine: string; ai?: { status: string; model?: string; message?: string } }; message: string }>(
    '/api/smartbi/blacklake/sections/followup', { method: 'POST', body: JSON.stringify(payload), preserveResponseKeys: true, timeoutMs: 170000 });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export interface AlertFollowup {
  alertId: string; status: 'open' | 'awaiting_data' | 'verified'; note: string;
  revision: number; updatedBy: number; updatedAt: string;
}
export async function saveBlacklakeAlertFollowup(alertId: string, payload: {
  run_id: string; status: AlertFollowup['status']; note: string; expected_revision: number;
  period?: BlacklakePeriodQuery;
}): Promise<AlertFollowup> {
  const response = await pythonFetch<{success:boolean;data:AlertFollowup;message:string}>(
    `/api/smartbi/blacklake/alerts/${encodeURIComponent(alertId)}/followup`,
    {method:'POST',body:JSON.stringify(payload),preserveResponseKeys:true});
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export interface CostDataAlert {
  id: string; type: string; family: 'data_quality' | 'business_signal' | 'readiness'; severity: 'blocking' | 'warning' | 'info';
  blocksUnitCost: boolean; title: string; message: string; owner: string; action: string; fields: string[];
  entityRefs: { projectId: number; projectCode: string; productCode: string | null; unit: string | null }[];
  evidence: Record<string, unknown>; detectedBy: string; ruleVersion: string; scope: string; runId: string; status: string;
}
export interface LocalAIUsage {
  inputTokens: number | null; outputTokens: number | null; totalTokens: number | null;
  durationSeconds?: number | null; source: string; estimated: boolean; requestId?: string;
}
export interface CostAlertReport {
  schema: string; ruleVersion: string; summary: { projects: number; costEligible: number; costBlocked: number; alerts: number; byFamily: Record<string, number>; byType: Record<string, number> };
  items: CostDataAlert[]; limitations: string[];
  ai: { status: string; message: string; model?: string; host?: string; analysisProfileVersion?: string; analysisSections?: import('./local-analysis').LocalAnalysisSection[]; usage?: LocalAIUsage; currentRequestUsage?: LocalAIUsage; cacheHit?: boolean; sourceRecordsSent?: number; externalProviderCalls?: number; sectionRepresentativeFacts?: number; reviewedSectionFacts?: number; representativeOnly?: boolean; reviews?: { alertId: string; check: string; priority: string; suggestion: string }[] };
}
export interface EligibleCostReview {
  eligibleProjects: number; products: number; limitations: string[];
  rankings: { productCode: string; product: string; unit: string; projects: number; weightedUnitCost: number; minimum: number; maximum: number }[];
  projects: (CostProject & { materialPerFinished: number; laborPerFinished: number; laborShare: number | null; components: (MaterialLine & { costPerFinished: number; usagePerFinished: number })[] })[];
}

function periodQueryString(query?: BlacklakePeriodQuery | null): string {
  if (!query) return '';
  const params = new URLSearchParams({ start_date: query.start_date, end_date: query.end_date, basis: query.basis, kind: query.kind });
  return `?${params.toString()}`;
}

export async function getBlacklakeAnalysis(query?: BlacklakePeriodQuery | null): Promise<BlacklakeAnalysis> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakeAnalysis; message: string }>(
    `/api/smartbi/blacklake/analysis${periodQueryString(query)}`, { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export async function createBlacklakePeriodReport(query: BlacklakePeriodQuery, force = false, expectedFingerprint?: string): Promise<BlacklakePeriodJob> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakePeriodJob; message: string }>(
    '/api/smartbi/blacklake/period-reports', { method: 'POST', body: JSON.stringify({ ...query, force, ...(expectedFingerprint ? { expected_fingerprint: expectedFingerprint } : {}) }), preserveResponseKeys: true, timeoutMs: 30000 });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export async function getBlacklakePeriodReport(id: string): Promise<BlacklakePeriodJob> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakePeriodJob; message: string }>(
    `/api/smartbi/blacklake/period-reports/${encodeURIComponent(id)}`, { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export async function listBlacklakePeriodReports(): Promise<BlacklakePeriodJob[]> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakePeriodJob[]; message: string }>(
    '/api/smartbi/blacklake/period-reports?summaries_only=true', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}

export async function getBlacklakePeriodSchedule(): Promise<BlacklakePeriodSchedule> {
  const response = await pythonFetch<{ success: boolean; data: BlacklakePeriodSchedule; message: string }>(
    '/api/smartbi/blacklake/period-schedule', { preserveResponseKeys: true });
  if (!response.success) throw new Error(response.message);
  return response.data;
}
