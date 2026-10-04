import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';
import { formatDate } from '../../utils/formatters';

/**
 * 时间统计管理API客户端
 * 总计17个API - 路径：/api/mobile/{factoryId}/time-stats/*
 */

/**
 * 后端统一响应格式
 */
export interface ApiResponse<T> {
  success: boolean;
  code: number;
  message: string;
  data: T;
}

/**
 * 时间记录
 */
export interface TimeRecord {
  id: number;
  employeeId: number;
  employeeName?: string;
  workTypeId: string;
  workTypeName?: string;
  department?: string;
  startTime: string;
  endTime: string;
  duration: number; // 分钟
  date: string;
  createdAt?: string;
  updatedAt?: string;
}

/**
 * 员工时间统计
 */
export interface EmployeeTimeStats {
  employeeId: number;
  employeeName: string;
  totalHours: number;
  workDays: number;
  overtimeHours: number;
  efficiency: number;
  period: {
    startDate: string;
    endDate: string;
  };
}

/**
 * 部门时间统计
 */
export interface DepartmentTimeStats {
  department: string;
  totalEmployees: number;
  totalHours: number;
  averageHours: number;
  topPerformers: Array<{
    employeeId: number;
    employeeName: string;
    hours: number;
  }>;
}

/**
 * 工作类型时间统计
 */
export interface WorkTypeTimeStats {
  workTypeId: string;
  workTypeName: string;
  totalHours: number;
  recordCount: number;
  averageDuration: number;
}

/**
 * 每日统计
 */
export interface DailyStats {
  date: string;
  totalHours: number;
  employeeCount: number;
  records: TimeRecord[];
}

/**
 * 每周统计
 */
export interface WeeklyStats {
  weekStart: string;
  weekEnd: string;
  totalHours: number;
  dailyBreakdown: Array<{
    date: string;
    hours: number;
  }>;
}

/**
 * 每月统计
 */
export interface MonthlyStats {
  year: number;
  month: number;
  totalHours: number;
  workDays: number;
  averageDailyHours: number;
  weeklyBreakdown: WeeklyStats[];
}

/**
 * 加班时长
 */
export interface OvertimeHours {
  employeeId: number;
  employeeName: string;
  regularHours: number;
  overtimeHours: number;
  totalHours: number;
  period: {
    startDate: string;
    endDate: string;
  };
}

/**
 * 效率报告
 */
export interface EfficiencyReport {
  period: {
    startDate: string;
    endDate: string;
  };
  averageEfficiency: number;
  topPerformers: Array<{
    employeeId: number;
    employeeName: string;
    efficiency: number;
    totalHours: number;
  }>;
  lowPerformers: Array<{
    employeeId: number;
    employeeName: string;
    efficiency: number;
    totalHours: number;
  }>;
}

/**
 * 成本分析（已废弃，使用 processingApiClient）
 */
export interface CostAnalysis {
  totalCost: number;
  laborCost: number;
  averageCostPerHour: number;
  period: {
    startDate: string;
    endDate: string;
  };
}

/**
 * 查询参数
 */
export interface TimeStatsQueryParams {
  startDate?: string;
  endDate?: string;
  department?: string;
  page?: number;
  size?: number;
  sortBy?: string;
  sortDirection?: 'ASC' | 'DESC';
}

class TimeStatsApiClient {
  private getPath(factoryId?: string) {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的，请先登录或提供 factoryId 参数');
    }
    return `/api/mobile/${currentFactoryId}/time-stats`;
  }

  async getTimeStats(params?: TimeStatsQueryParams & { factoryId?: string }): Promise<ApiResponse<TimeRecord[]>> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(this.getPath(factoryId), { params: query });
  }

  async createTimeRecord(data: Partial<TimeRecord>, factoryId?: string): Promise<ApiResponse<TimeRecord>> {
    return await apiClient.post(this.getPath(factoryId), data);
  }

  async getTimeRecordById(id: string, factoryId?: string): Promise<ApiResponse<TimeRecord>> {
    return await apiClient.get(`${this.getPath(factoryId)}/${id}`);
  }

  async updateTimeRecord(id: string, data: Partial<TimeRecord>, factoryId?: string): Promise<ApiResponse<TimeRecord>> {
    return await apiClient.put(`${this.getPath(factoryId)}/${id}`, data);
  }

  async deleteTimeRecord(id: string, factoryId?: string): Promise<ApiResponse<{ deleted: boolean }>> {
    return await apiClient.delete(`${this.getPath(factoryId)}/${id}`);
  }

  /**
   * 获取员工个人时间统计
   * 后端路径: GET /api/mobile/{factoryId}/time-stats/workers/{workerId}
   * 必需参数: startDate, endDate
   */
  async getEmployeeTimeStats(employeeId: number, params?: TimeStatsQueryParams, factoryId?: string): Promise<ApiResponse<EmployeeTimeStats>> {
    return await apiClient.get(`${this.getPath(factoryId)}/workers/${employeeId}`, { params });
  }

  /**
   * 按部门统计
   * 后端路径: GET /api/mobile/{factoryId}/time-stats/by-department
   * 必需参数: startDate, endDate
   * 注意: department 参数需要在 params 中传递，不在URL路径中
   */
  async getDepartmentTimeStats(department: string, params?: TimeStatsQueryParams, factoryId?: string): Promise<ApiResponse<DepartmentTimeStats>> {
    // 将 department 添加到查询参数中，而不是作为路径参数
    const queryParams = { ...params, department };
    return await apiClient.get(`${this.getPath(factoryId)}/by-department`, { params: queryParams });
  }

  async getWorkTypeTimeStats(workTypeId: string, params?: TimeStatsQueryParams, factoryId?: string): Promise<ApiResponse<WorkTypeTimeStats>> {
    return await apiClient.get(`${this.getPath(factoryId)}/work-type/${workTypeId}`, { params });
  }

  async getDailyStats(date: string, factoryId?: string): Promise<ApiResponse<DailyStats>> {
    return await apiClient.get(`${this.getPath(factoryId)}/daily`, { params: { date } });
  }

  async getWeeklyStats(weekStart: string, factoryId?: string): Promise<ApiResponse<WeeklyStats>> {
    // 将日期转换为年份和ISO周数
    const date = new Date(weekStart);
    const year = date.getFullYear();
    // 计算ISO周数
    const firstDayOfYear = new Date(year, 0, 1);
    const pastDaysOfYear = (date.getTime() - firstDayOfYear.getTime()) / 86400000;
    const week = Math.ceil((pastDaysOfYear + firstDayOfYear.getDay() + 1) / 7);
    return await apiClient.get(`${this.getPath(factoryId)}/weekly`, { params: { year, week } });
  }

  async getMonthlyStats(year: number, month: number, factoryId?: string): Promise<ApiResponse<MonthlyStats>> {
    return await apiClient.get(`${this.getPath(factoryId)}/monthly`, { params: { year, month } });
  }

  async calculateOvertimeHours(params: TimeStatsQueryParams & { employeeId: number }, factoryId?: string): Promise<ApiResponse<OvertimeHours>> {
    return await apiClient.post(`${this.getPath(factoryId)}/calculate-overtime`, params);
  }

  async getEfficiencyReport(params?: TimeStatsQueryParams, factoryId?: string): Promise<ApiResponse<EfficiencyReport>> {
    return await apiClient.get(`${this.getPath(factoryId)}/efficiency-report`, { params });
  }

  /**
   * @deprecated 此方法已废弃 (废弃日期: 2025-11-19)
   *
   * ⚠️ 请使用 processingApiClient 的成本分析方法替代
   *
   * 替代方案:
   * ```typescript
   * import { processingApiClient } from './processingApiClient';
   *
   * // 单批次成本分析:
   * await processingApiClient.getBatchCostAnalysis(batchId, factoryId);
   *
   * // 时间范围成本分析:
   * await processingApiClient.getTimeRangeCostAnalysis({
   *   startDate: '2025-01-01',
   *   endDate: '2025-11-19',
   *   factoryId
   * });
   * ```
   *
   * 废弃原因:
   * - 成本分析属于生产加工模块，不属于时间统计模块
   * - processingApiClient 提供了更完整的成本分析功能
   * - 避免职责混淆和API重复
   */
  async getCostAnalysis(params?: TimeStatsQueryParams, factoryId?: string): Promise<ApiResponse<CostAnalysis>> {
    console.warn('[timeStatsApiClient.getCostAnalysis] 此方法已废弃，请使用 processingApiClient.getTimeRangeCostAnalysis()');
    return await apiClient.get(`${this.getPath(factoryId)}/cost-analysis`, { params });
  }

  async getTopPerformers(limit?: number, factoryId?: string): Promise<ApiResponse<Array<{
    employeeId: number;
    employeeName: string;
    totalHours: number;
    efficiency: number;
  }>>> {
    return await apiClient.get(`${this.getPath(factoryId)}/top-performers`, { params: { limit } });
  }

  async exportTimeStats(params?: TimeStatsQueryParams & { factoryId?: string }): Promise<Blob> {
    const { factoryId, ...query } = params || {};
    return await apiClient.get(`${this.getPath(factoryId)}/export`, {
      params: query,
      responseType: 'blob'
    });
  }

  async importTimeRecords(file: File, factoryId?: string): Promise<ApiResponse<{
    imported: number;
    failed: number;
    errors?: string[];
  }>> {
    const formData = new FormData();
    formData.append('file', file);
    return await apiClient.post(`${this.getPath(factoryId)}/import`, formData, {
      headers: { 'Content-Type': 'multipart/form-data' }
    });
  }

  /**
   * 获取劳动成本汇总 (HR模块)
   * 后端路径: GET /api/mobile/{factoryId}/time-stats/productivity
   */
  async getLaborCostSummary(params?: {
    period?: string;
    startDate?: string;
    endDate?: string;
    factoryId?: string;
  }): Promise<{
    totalCost: number | null;
    totalWorkMinutes: number | null;
    avgHourlyRate: number | null;
    participatingBatches: number | null;
    participatingEmployees: number;
  }> {
    const { factoryId, period, startDate, endDate } = params || {};

    // 计算日期范围
    const dates = this.calculateDateRange(period, startDate, endDate);

    const response = await apiClient.get<{ success: boolean; data?: Record<string, unknown>; message?: string }>(`${this.getPath(factoryId)}/daily/range`, {
      params: dates
    });

    if (!response || response.success !== true || !response.data || typeof response.data !== 'object' || Array.isArray(response.data)) {
      throw new Error(response?.message || '劳动成本汇总返回格式无效');
    }

    // 映射后端响应到前端期望的格式
    const data = response.data || {};
    const required = ['totalHours', 'activeWorkers', 'startDate', 'endDate'];
    if (required.some((key) => !Object.prototype.hasOwnProperty.call(data, key))) throw new Error('劳动成本汇总返回格式无效');
    for (const key of ['totalHours', 'activeWorkers']) {
      const value = data[key];
      if (typeof value !== 'number' || !Number.isFinite(value) || value < 0) {
        throw new Error('劳动成本汇总返回格式无效');
      }
    }
    if (!Number.isInteger(data.activeWorkers) || (data.activeWorkers as number) < 0) throw new Error('劳动成本汇总返回格式无效');
    if (data.startDate !== dates.startDate || data.endDate !== dates.endDate) throw new Error('劳动成本汇总日期范围不一致');
    const totalHours = Number(data.totalHours);
    const activeWorkers = Number(data.activeWorkers);
    if (!Number.isFinite(totalHours) || !Number.isFinite(activeWorkers)) throw new Error('劳动成本汇总返回格式无效');
    return {
      totalCost: null,
      totalWorkMinutes: totalHours * 60,
      avgHourlyRate: null,
      participatingBatches: null,
      participatingEmployees: activeWorkers,
    };
  }

  /**
   * 获取按部门成本分布 (HR模块)
   * 后端路径: GET /api/mobile/{factoryId}/time-stats/by-department
   */
  async getCostByDepartment(params?: {
    period?: string;
    startDate?: string;
    endDate?: string;
    factoryId?: string;
  }): Promise<{
    departmentId: string;
    departmentName: string;
    totalHours: number;
  }[]> {
    const { factoryId, period, startDate, endDate } = params || {};

    // 计算日期范围
    const dates = this.calculateDateRange(period, startDate, endDate);

    const response = await apiClient.get<{ success: boolean; data?: { departmentStats?: Record<string, { totalHours?: number }> }; message?: string }>(`${this.getPath(factoryId)}/by-department`, {
      params: dates
    });

    // 映射后端响应到前端期望的格式
    if (!response || response.success !== true || !response.data || typeof response.data !== 'object' || Array.isArray(response.data)) {
      throw new Error(response?.message || '部门工时返回格式无效');
    }
    const rawDepts = response.data.departmentStats;
    if (!rawDepts || typeof rawDepts !== 'object' || Array.isArray(rawDepts)) throw new Error('部门工时返回格式无效');
    return Object.entries(rawDepts).map(([department, stats], index) => {
      if (!stats || typeof stats.totalHours !== 'number' || !Number.isFinite(stats.totalHours) || stats.totalHours < 0) throw new Error('部门工时返回格式无效');
      return {
      departmentId: String(index + 1),
      departmentName: department,
      totalHours: stats.totalHours,
      };
    });
  }

  /**
   * 获取员工工时排行 (HR模块)
   * 后端路径: GET /api/mobile/{factoryId}/time-stats/workers
   */
  async getWorkerHoursRank(params?: {
    limit?: number;
    period?: string;
    startDate?: string;
    endDate?: string;
    factoryId?: string;
  }): Promise<{
    rank: number;
    userId: number;
    userName: string;
    department?: string;
    totalMinutes: number;
    totalHours: number;
  }[]> {
    const { factoryId, limit = 10, period, startDate, endDate } = params || {};

    // 计算日期范围
    const dates = this.calculateDateRange(period, startDate, endDate);

    const response = await apiClient.get<{ success: boolean; data?: Array<{ workerId?: number; workerName?: string; department?: string; totalHours?: number }>; message?: string }>(`${this.getPath(factoryId)}/workers`, {
      params: { ...dates, topN: limit }
    });

    // 映射后端响应到前端期望的格式，按工时排序
    if (!response || response.success !== true || !Array.isArray(response.data)) throw new Error(response?.message || '员工工时返回格式无效');
    const workers = response.data;
    workers.forEach((worker) => {
      if (!worker || typeof worker.workerId !== 'number' || !Number.isFinite(worker.workerId)
        || !Number.isInteger(worker.workerId) || worker.workerId <= 0
        || typeof worker.workerName !== 'string' || worker.workerName.trim() === ''
        || typeof worker.totalHours !== 'number' || !Number.isFinite(worker.totalHours) || worker.totalHours < 0) {
        throw new Error('员工工时返回格式无效');
      }
    });
    return workers
      .sort((a: { totalHours?: number }, b: { totalHours?: number }) =>
        (b.totalHours ?? 0) - (a.totalHours ?? 0))
      .slice(0, limit)
      .map((worker: { workerId?: number; workerName?: string; department?: string; totalHours?: number }, index: number) => ({
        rank: index + 1,
        userId: worker.workerId!,
        userName: worker.workerName!,
        department: worker.department,
        totalMinutes: Math.round(worker.totalHours! * 60),
        totalHours: worker.totalHours!,
      }));
  }

  /**
   * 获取考勤统计汇总 (HR模块)
   * 后端路径: GET /api/mobile/{factoryId}/timeclock/admin/statistics
   *
   * 注意: 此接口调用考勤模块的管理员统计端点
   */
  async getAttendanceStats(params?: {
    period?: string;
    startDate?: string;
    endDate?: string;
    factoryId?: string;
  }): Promise<{
    totalEmployees: number;
    avgAttendanceRate: number;
    totalLateCount: number;
    totalAbsentCount: number;
    totalEarlyLeaveCount: number;
    dailyStats?: {
      date: string;
      attendanceRate: number;
      lateCount: number;
    }[];
  }> {
    const { factoryId, period, startDate, endDate } = params || {};

    // 计算日期范围
    const dates = this.calculateDateRange(period, startDate, endDate);

    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      throw new Error('factoryId 是必需的');
    }

    // 调用考勤模块的管理员统计端点
    const response = await apiClient.get<{ success: boolean; data: Record<string, unknown> }>(`/api/mobile/${currentFactoryId}/timeclock/admin/statistics`, {
      params: dates
    });

    // 映射后端响应到前端期望的格式
    const data = response.data || {};
    return {
      totalEmployees: Number(data.totalEmployees) || 0,
      avgAttendanceRate: Number(data.attendanceRate) || 0,
      totalLateCount: Number(data.lateCount) || 0,
      totalAbsentCount: Number(data.absentCount) || 0,
      totalEarlyLeaveCount: Number(data.earlyLeaveCount) || 0,
      dailyStats: Array.isArray(data.dailyStats) ? data.dailyStats as { date: string; attendanceRate: number; lateCount: number }[] : [],
    };
  }

  /**
   * 计算日期范围的辅助方法
   */
  private calculateDateRange(period?: string, startDate?: string, endDate?: string): { startDate: string; endDate: string } {
    if (startDate && endDate) {
      return { startDate, endDate };
    }

    const now = new Date();
    const end = formatDate(now) ?? '';
    let start: string;

    switch (period) {
      case 'week':
        start = formatDate(new Date(now.getTime() - 7 * 24 * 60 * 60 * 1000)) ?? '';
        break;
      case 'month':
        start = formatDate(new Date(now.getFullYear(), now.getMonth(), 1)) ?? '';
        break;
      case 'quarter': {
        const quarter = Math.floor(now.getMonth() / 3);
        start = formatDate(new Date(now.getFullYear(), quarter * 3, 1)) ?? '';
        break;
      }
      case 'year':
        start = formatDate(new Date(now.getFullYear(), 0, 1)) ?? '';
        break;
      default:
        // 默认本月
        start = formatDate(new Date(now.getFullYear(), now.getMonth(), 1)) ?? '';
    }

    return { startDate: start, endDate: end };
  }
}

export const timeStatsApiClient = new TimeStatsApiClient();
export default timeStatsApiClient;
