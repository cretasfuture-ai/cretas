/**
 * CCP 监控 API (缺口 13) —— App 工序报工时录入关键控制点实测值。
 * Backend: CcpMonitoringController @ /api/mobile/{factoryId}/ccp
 */
import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type { ApiResponse } from './yieldReportApi';

export interface CcpStepCheckpoint {
  checkpointId: number;
  checkpointCode: string;
  name: string;
  hazardType: string;
  criticalLimitMin: number;
  criticalLimitMax: number;
  unit: string;
  monitoringProcedure: string | null;
  correctiveAction: string | null;
}

export interface CcpReading {
  checkpointId: number;
  measuredValue: number;
  notes?: string | null;
}

export interface CcpRecordCommand {
  batchNumber: string | null;
  productionBatchId: number | null;
  planId: string | null;
  workProcessId: string;
  processOrder: number | null;
  source: 'APP_STEP_REPORT';
  readings: CcpReading[];
}

export interface CcpRecordResponse {
  deviationCount: number;
  heldOutputs: number;
  message: string;
}

class CcpApi {
  /** 本工序要录的关键控制点 (含合格范围 + 单位)。没挂 CCP 的工序返回空数组。 */
  async listStepCheckpoints(workProcessId: string, factoryId?: string): Promise<ApiResponse<CcpStepCheckpoint[]>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.get<ApiResponse<CcpStepCheckpoint[]>>(`/api/mobile/${fid}/ccp/step-checkpoints`, {
      params: { workProcessId },
    });
  }

  /** 报工成功后提交本次 CCP 实测值; 超限由后端记偏离并把批次转待检。 */
  async record(body: CcpRecordCommand, factoryId?: string): Promise<ApiResponse<CcpRecordResponse>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<CcpRecordResponse>>(`/api/mobile/${fid}/ccp/records`, body);
  }
}

export const ccpApi = new CcpApi();
