export type Json = null | boolean | number | string | Json[] | { [key: string]: Json }
export interface Plan {
  id: string; planNumber: string; productName: string; productTypeId: string;
  plannedQuantity: number | null; actualQuantity: number | null; plannedUnit: string;
  plannedDate: string; expectedCompletionDate: string; status: string; statusDisplayName: string;
  priority: number | null; isUrgent: boolean; createdByName: string; assignedSupervisorName: string | null;
  processName: string; notes: string; sourceTypeDisplayName: string; nextAction: string;
  sourceKey: string; raw: Record<string, Json>; localDraft?: boolean
}
export interface ProcessDefinition { id: string; processName: string; processCode?: string; category?: string; unit?: string; raw: Record<string, Json> }
export interface WorkflowNode { id: string; label: string; type?: string; x: number; y: number; raw: Record<string, Json> }
export interface WorkflowEdge { id: string; source: string; target: string; label?: string }
export interface Workflow { id: string; name: string; productName: string; version: string; nodes: WorkflowNode[]; edges: WorkflowEdge[]; sourceKey: string; raw: Record<string, Json> }
export interface Tenant { id: string; name: string; type: string; isActive: boolean; aiWeeklyQuota: number | null; contactName: string | null; raw: Record<string, Json> }
export interface MenuItem { path: string; title: string; module?: string }
export interface SampleData {
  fusion?: { definitionCount: number; defaultOwnerId: string; capturedAt: string };
  capturedAt: string; snapshotUpdatedAt: string; account: string; factoryName: string; userName: string;
  plans: Plan[]; planSourceTotal: number; processes: ProcessDefinition[]; workflows: Workflow[];
  factories: Tenant[]; platformStats: Record<string, Json>; products: Record<string, Json>[];
  batches: Record<string, Json>[]; menu: { factory: MenuItem[]; platform: MenuItem[] };
  provenance: { key: string; capturedAt: string; status: number; count?: number }[];
  executive: Record<string, Json>; originalPaths: Record<string, string>;
  analytics: { cost: { rows: Record<string, Json>[]; summary: Record<string, Json> }; yield: { trend: Record<string, Json>[] }; sourceKeys: string[] };
  aiExamples: { prompt: string; response: Record<string, Json>; sourceKey: string }[];
  backups: Record<string, Json>[]
}
export type Role = 'factory' | 'platform'
export type PageId = 'plans' | 'canvas' | 'analytics' | 'ai' | 'platform'
