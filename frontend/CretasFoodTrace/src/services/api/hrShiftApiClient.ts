import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

export interface AttendanceShift {
  id: string;
  factoryId: string;
  shiftCode: string;
  shiftName: string;
  startTime: string;
  endTime: string;
  isOvernight?: boolean;
  isActive?: boolean;
  notes?: string | null;
}

export interface ShiftAssignment {
  id: string;
  factoryId: string;
  userId: number;
  workDate: string;
  shiftId: string;
  status?: string;
  notes?: string | null;
}

interface ShiftResponse<T> {
  success: boolean;
  data?: T;
  message?: string;
}

export interface SelfModuleAccess {
  moduleCode: string;
  displayName?: string;
  category?: string;
  permissionModule?: string;
  roleDefaultAllowed: boolean;
  override?: string | null;
  effectiveAllowed: boolean;
}

class HRShiftApiClient {
  private getPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    return `/api/mobile/${currentFactoryId}/hr`;
  }

  private getFactoryPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    return `/api/mobile/${currentFactoryId}`;
  }

  async listAssignments(params: {
    startDate: string;
    endDate: string;
    userId?: number;
    factoryId?: string;
  }): Promise<ShiftAssignment[]> {
    const { factoryId, ...query } = params;
    const response = await apiClient.get<ShiftResponse<ShiftAssignment[]>>(
      `${this.getPath(factoryId)}/shift-assignments`,
      { params: query }
    );
    if (!response.success) throw new Error(response.message || '读取排班失败');
    if (!Array.isArray(response.data)) throw new Error('读取排班返回格式无效');
    return response.data;
  }

  async listActiveShifts(factoryId?: string): Promise<AttendanceShift[]> {
    const response = await apiClient.get<ShiftResponse<AttendanceShift[]>>(
      `${this.getPath(factoryId)}/attendance-shifts`,
      { params: { activeOnly: true } }
    );
    if (!response.success) throw new Error(response.message || '读取班次失败');
    if (!Array.isArray(response.data)) throw new Error('读取班次返回格式无效');
    return response.data;
  }

  async getSelfModuleAccess(userId: number, factoryId?: string): Promise<SelfModuleAccess[]> {
    const response = await apiClient.get<ShiftResponse<SelfModuleAccess[]>>(
      `${this.getFactoryPath(factoryId)}/canvas/user-module-access/${userId}`
    );
    if (!response.success) throw new Error(response.message || '读取当前模块权限失败');
    if (!Array.isArray(response.data)) throw new Error('读取当前模块权限返回格式无效');
    return response.data;
  }

  async assign(
    payload: { userId: number; workDate: string; shiftId: string; notes?: string },
    factoryId?: string
  ): Promise<ShiftResponse<ShiftAssignment>> {
    return apiClient.post(`${this.getPath(factoryId)}/shift-assignments`, payload);
  }

  async remove(id: string, factoryId?: string): Promise<ShiftResponse<unknown>> {
    return apiClient.delete(`${this.getPath(factoryId)}/shift-assignments/${id}`);
  }
}

export const hrShiftApiClient = new HRShiftApiClient();
export default hrShiftApiClient;
