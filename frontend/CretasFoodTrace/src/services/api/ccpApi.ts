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

  /**
   * 单独补录 CCP 实测值 (质检/管理端用)。⚠️ App 报工不再走这里 —— 实测值随报工请求 (ccpReadings)
   * 一起提交、同一事务落库 (缺口 13 审查中 7), 不存在「产出入账了、CCP 没交上」的半态。
   */
  async record(body: CcpRecordCommand, factoryId?: string): Promise<ApiResponse<CcpRecordResponse>> {
    const fid = requireFactoryId(factoryId);
    return apiClient.post<ApiResponse<CcpRecordResponse>>(`/api/mobile/${fid}/ccp/records`, body);
  }
}

export const ccpApi = new CcpApi();
