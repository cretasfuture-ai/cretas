import { get } from './request';

export interface SchedulingReadiness {
  status: 'INPUTS_VALID_UNCONFIRMED' | 'INCOMPLETE' | 'INFEASIBLE';
  readyForAutomation: boolean;
  reason: string;
  productionLine: { id?: string; selected: boolean; confirmed: boolean; source: string; reason: string };
  batch: { id?: string; selected: boolean; confirmed: boolean; source: string; reason: string };
  timeWindow: { start?: string; end?: string; valid: boolean; reason: string };
  probability: { origin: string; reason: string; optimization: boolean };
  missingInputs: string[];
}

export function getSchedulingReadiness(factoryId: string, params: {
  productionLineId?: string;
  batchId?: number;
  startTime?: string;
  endTime?: string;
  scheduleId?: string;
}) {
  return get<SchedulingReadiness>(`/${factoryId}/scheduling/readiness`, { params });
}
