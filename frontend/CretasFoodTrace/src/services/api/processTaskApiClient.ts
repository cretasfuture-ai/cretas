import { apiClient } from './apiClient';
import { requireFactoryId } from '../../utils/factoryIdHelper';
import type { PageResponse, WorkProcessTask } from './yieldReportApi';
import type { WorkReportResponse } from '../../types/workReporting';

/**
 * 报工看板汇总 —— 后端 `WorkReportSummaryResponse` 的三个字段。
 *
 * ⚠️ 只有三个是量出来的：legacy 的 Map 出口有 7 个键，
 * 其余四个（progressSummary / hoursSummary / weeklyOutput / todayCount）
 * 在 RN + web-admin 全仓零消费方，随迁移一起砍掉了。
 */
export interface WorkReportSummary {
  /** ⚠️ 口径是 `status = SUBMITTED`，与 `/pending-approval` 列表的 approvalStatus='PENDING' 不是同一个数。 */
  pendingApprovalCount: number;
  todayOutputTotal: number;
  todayYieldRate: number;
}

/** 工序历史均值 —— 后端 `WorkReportHistoricalAverageResponse`。 */
export interface HistoricalAverage {
  avgOutput: number;
  stddevOutput: number;
  avgDefect: number;
  sampleCount: number;
}

// ========== Types ==========

export interface ProcessTaskItem {
  id: string;
  factoryId: string;
  batchNumber?: string;
  productTypeId: string;
  productTypeName?: string;
  workProcessId: string;
  processName?: string;
  processCategory?: string;
  unit: string;
  batchId?: number;
  productionBatchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  inputUnit?: string;
  outputUnit?: string;
  plannedUnit?: string;
  productionRunId?: string;
  sourceDocType?: string;
  sourceDocId?: string;
  /**
   * 计划量。`null` = 【这道工序没有设计划量】, 与 `0` 不是一回事。
   *
   * ⚠️ 不要在这里兜底成 0。上游 `WorkProcessTask.plannedQuantity` 本来就是
   * `number | null`(见 yieldReportApi.ts), 兜底会把「不知道」翻译成「是 0」,
   * 而这两件事对下游完全不同 —— 屏幕会据此画出一个「计划量 0 / 进度 0%」的卡片,
   * 对着一个根本没设过计划量的工序。那不是显示错误, 是【编了一个数】。
   */
  plannedQuantity: number | null;
  completedQuantity: number;
  pendingQuantity: number;
  inputQuantity?: number;
  status: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'CLOSED' | 'SUPPLEMENTING' | string;
  /**
   * 计划起止 —— 车间主管靠这两个答「今天要做什么」。
   *
   * 后端 `@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")`, 所以拿到的是
   * `"2026-08-26 08:00:00"` 这种**本地日历时间字符串**, 不是 ISO。
   * ⛔ 不要 `new Date(s)` 再 `toISOString()` 截断 —— 那会按 UTC 挪一天,
   *    而这个串本来就已经是业务日历日了 (后端全线 GMT+8)。
   *
   * `undefined` = 没设。⛔ 不许兜底成今天/某个默认日期。
   */
  plannedStartAt?: string;
  plannedEndAt?: string;
  /** 责任人姓名 (后端 join 透出)。`undefined` = 未分配, 或用户已删除。 */
  assignedToName?: string;
  /**
   * 这道工序超期没有 —— **三态**: `true` / `false` / `undefined`(判不了)。
   *
   * `undefined` 的唯一含义是「后端没给出判断」——今天只有一个成因:
   * 这道工序没设 `plannedEndAt`。
   *
   * ⛔ 这里【曾经】根本不存在: 屏幕上的「超期」徽章读 `(item as any).overdue`,
   * 而这个字段四层都没有 ⇒ 那个徽章从来没渲染过一次。`as any` 正是它能编译
   * 通过却永不生效的原因 —— 类型系统本来会当场指出「这个字段不存在」。
   *
   * ⛔ 不要 `?? false`: 那会把「没问到」说成「不超期」。
   */
  overdue?: boolean;
  assignedWorkerIds?: string;
  workflowVersionId?: string;
  previousTerminalStatus?: string;
  createdAt?: string;
  updatedAt?: string;
}

interface ApiEnvelope<T> {
  success: boolean;
  data?: T;
  message?: string;
}

interface ProcessCheckinResponseData {
  employeeName?: string;
}

interface ProcessCheckinRecord {
  id: number;
  processTaskId?: string | number;
  employeeId: number;
  employeeName?: string;
  workerName?: string;
  checkInTime?: string;
  checkOutTime?: string;
  status?: string;
  processName?: string;
}

interface TaskWorkerRecord {
  id?: number;
  employeeId?: number;
  name?: string;
  fullName?: string;
}

export interface ProcessTaskSummary {
  task?: ProcessTaskItem;
  // Backend returns flat shape — task fields at top level
  taskId?: string;
  id?: string;
  processName?: string;
  productName?: string;
  productTypeName?: string;
  processCategory?: string;
  // 同 ProcessTaskItem: null 表示【没设计划量】, 与 0 不同, 不在传递途中抹掉
  plannedQuantity?: number | null;
  completedQuantity?: number;
  pendingQuantity?: number;
  inputQuantity?: number;
  unit?: string;
  status?: string;
  productionRunId?: string;
  totalReported?: number;
  approvedTotal?: number;
  pendingTotal?: number;
  rejectedTotal?: number;
  workerCount?: number;
  totalWorkers?: number;
  totalReports?: number;
}

export interface WorkerSummary {
  workerId: number;
  workerName: string;
  totalQuantity: number;
  approvedQuantity: number;
  pendingQuantity: number;
  reportCount: number;
}

export interface RunOverview {
  productionRunId: string;
  tasks: ProcessTaskItem[];
  overallProgress: number;
  completedTasks: number;
  totalTasks: number;
}

export interface ProcessReportItem {
  id: number;
  processTaskId: string;
  reportDate: string;
  processCategory?: string;
  productName?: string;
  batchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  productTypeId?: string;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputQuantity: number;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  productionStartTime?: string;
  productionEndTime?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  photos?: string[];
  notes?: string;
  customFields?: Record<string, unknown>;
  reporterName?: string;
  isSupplemental: boolean;
  approvalStatus: string;
  approvedBy?: number;
  approvedAt?: string;
  rejectedReason?: string;
  reversalOfId?: number;
  createdAt?: string;
}

export interface ApprovalItem {
  id: number;
  reporterName: string;
  reportDate: string;
  processCategory: string;
  productName?: string;
  batchId?: number;
  workProcessTaskId?: number;
  processOrder?: number;
  productTypeId?: string;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputQuantity: number;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  productionStartTime?: string;
  productionEndTime?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  photos?: string[];
  notes?: string;
  customFields?: Record<string, unknown>;
  isSupplemental: boolean;
  processTaskId: string;
}

export interface SubmitProcessReportPayload {
  processTaskId: string;
  outputQuantity: number;
  reporterName?: string;
  targetWorkerId?: number;
  processCategory?: string;
  notes?: string;
  reportMode?: 'MODE_1' | 'MODE_2' | 'MODE_3';
  batchNumber?: string;
  batchId?: number;
  workProcessTaskId?: number;
  inputQuantity?: number;
  inputUnit?: string;
  sourceWipNo?: string;
  outputUnit?: string;
  totalWorkers?: number;
  totalWorkMinutes?: number;
  reportDate?: string;
  productionStartTime?: string;
  productionEndTime?: string;
  photos?: string[];
  workerIds?: number[];
  customFields?: Record<string, unknown>;
}

// ========== API Client ==========

class ProcessTaskApiClient {
  private getBase(factoryId?: string) {
    const fid = factoryId || requireFactoryId();
    return `/api/mobile/${fid}`;
  }

  private toProcessTaskItem(task: WorkProcessTask): ProcessTaskItem {
    return {
      id: String(task.id),
      factoryId: task.factoryId,
      batchNumber: task.batchNumber ?? undefined,
      productTypeId: task.productTypeId,
      productTypeName: task.productTypeName ?? undefined,
      workProcessId: task.workProcessId,
      processName: task.processName ?? undefined,
      processCategory: task.processCategory ?? undefined,
      unit: task.plannedUnit ?? task.outputUnit ?? '',
      batchId: task.productionBatchId,
      productionBatchId: task.productionBatchId,
      workProcessTaskId: task.id,
      processOrder: task.processOrder,
      outputUnit: task.outputUnit ?? undefined,
      plannedUnit: task.plannedUnit ?? undefined,
      productionRunId: `BATCH-${task.productionBatchId}`,
      // ⛔ 这里【曾经】是 `?? 0` —— 那一处兜底就是「计划量 0 + 0% 进度条」的源头。
      // 后端 planned_quantity 为 NULL 时保持 null, 让屏幕自己决定怎么说「未设置」。
      plannedQuantity: task.plannedQuantity ?? null,
      completedQuantity: task.actualQuantity ?? 0,
      pendingQuantity: 0,
      status: task.status,
      // ⛔ 这四个【曾经】在这里被整个丢掉 —— 后端 DTO 有、传输类型有,
      //    映射层不投影, 于是屏幕上永远看不到计划日期/责任人/超期。
      //    那不是缺功能, 是投影丢失: 数据一路送到了消费端前面一层才被扔。
      plannedStartAt: task.plannedStartAt ?? undefined,
      plannedEndAt: task.plannedEndAt ?? undefined,
      assignedToName: task.assignedToName ?? undefined,
      // 三态: true / false / undefined。⛔ 不要写 `?? false` —— `undefined` 是
      // 「后端没给出判断」(这道工序没设 plannedEndAt), 与「不超期」不是一回事。
      overdue: task.overdue == null ? undefined : task.overdue,
      createdAt: task.createdAt ?? undefined,
      updatedAt: task.updatedAt ?? undefined,
    };
  }

  private parseTaskId(taskId: string): number {
    const normalized = taskId.startsWith('WPT-') ? taskId.slice(4) : taskId;
    const parsed = Number(normalized);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new Error(`无效的工序任务ID: ${taskId}`);
    }
    return parsed;
  }

  private parseBatchId(productionRunId: string): number {
    const normalized = productionRunId.startsWith('BATCH-')
      ? productionRunId.slice(6)
      : productionRunId;
    const parsed = Number(normalized);
    if (!Number.isInteger(parsed) || parsed <= 0) {
      throw new Error(`无效的生产批次ID: ${productionRunId}`);
    }
    return parsed;
  }

  // --- Canonical Work Process Tasks ---

  async getActiveTasks(factoryId?: string): Promise<ApiEnvelope<ProcessTaskItem[] | { content: ProcessTaskItem[] }>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<PageResponse<WorkProcessTask>>>(
      `${base}/work-process-tasks`,
      { params: { page: 1, size: 1000 } },
    );
    const tasks = (response.data?.content ?? [])
      .filter(task => task.status === 'PENDING' || task.status === 'IN_PROGRESS')
      .map(task => this.toProcessTaskItem(task));
    return { ...response, data: tasks };
  }

  /**
   * 工序任务列表屏三个筛选档各自的**真实总数**。
   *
   * 🔴 这个方法存在的全部理由是**口径统一**。屏上三个档原本由两条口径不同的路供数:
   *   - `进行中` 走 `getActiveTasks`: page1 **size 1000** 拉回来, 在**客户端**筛
   *     PENDING|IN_PROGRESS, 返回一个裸数组 —— 没有 totalElements, 且天然被 1000 截断。
   *   - `已完成` / `全部` 走 `getTasks`: **服务端** status 过滤, 有 totalElements。
   *   直接拿两边的长度并排显示, 就会给出一组**互相对不上**的数
   *   (「进行中 900 / 全部 348」这种)。⛔ 一个和「全部」对不上的计数比没有计数更糟。
   *
   * ⇒ 三个档一律改由**服务端 totalElements** 供数, 只用一把尺子:
   *   - `active`    = totalElements(PENDING) + totalElements(IN_PROGRESS)
   *                   (一条任务只有一个状态, 两者不重叠, 相加即为并集)
   *   - `completed` = totalElements(COMPLETED)
   *   - `all`       = totalElements(不带 status)
   *
   * ⚠️ 这是「**有多少条**」, 不是「屏幕上显示了多少条」—— 列表本身仍是取前一页。
   *   两者的差额由调用方显式说明, ⛔ 不许让用户以为列表是全的。
   *
   * 任一档问不到就是 `null`, ⛔ 不兜 0 ——「没问到」和「这一档一条都没有」
   * 在筛选器上长得一模一样, 含义却相反。各档独立取数, 一档失败不连坐其它档。
   */
  async getTaskCounts(factoryId?: string): Promise<{
    active: number | null;
    completed: number | null;
    all: number | null;
  }> {
    const base = this.getBase(factoryId);
    /** 只为拿 totalElements ⇒ size=1, 不搬运用不到的行。读不到返回 null。 */
    const totalFor = async (status?: string): Promise<number | null> => {
      try {
        const res = await apiClient.get<ApiEnvelope<PageResponse<WorkProcessTask>>>(
          `${base}/work-process-tasks`,
          { params: { ...(status ? { status } : {}), page: 1, size: 1 } },
        );
        if (res.success === false) return null;
        const total = res.data?.totalElements;
        return typeof total === 'number' ? total : null;
      } catch {
        return null;
      }
    };

    const [pending, inProgress, completed, all] = await Promise.all([
      totalFor('PENDING'),
      totalFor('IN_PROGRESS'),
      totalFor('COMPLETED'),
      totalFor(undefined),
    ]);

    return {
      // 两条腿缺任何一条, 和就没有意义 ⇒ null, ⛔ 不许拿单条腿的数冒充并集
      active: pending === null || inProgress === null ? null : pending + inProgress,
      completed,
      all,
    };
  }

  async getTasks(
    params: { status?: string; productTypeId?: string; page?: number; size?: number },
    factoryId?: string,
  ): Promise<ApiEnvelope<{ content: ProcessTaskItem[]; totalElements?: number }>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<PageResponse<WorkProcessTask>>>(
      `${base}/work-process-tasks`,
      { params: { status: params.status, page: params.page ?? 1, size: params.size ?? 50 } },
    );
    const content = (response.data?.content ?? [])
      .filter(task => !params.productTypeId || task.productTypeId === params.productTypeId)
      .map(task => this.toProcessTaskItem(task));
    return {
      ...response,
      data: { content, totalElements: params.productTypeId ? content.length : response.data?.totalElements },
    };
  }

  async getTaskById(taskId: string, factoryId?: string): Promise<ApiEnvelope<ProcessTaskItem>> {
    const base = this.getBase(factoryId);
    const response = await apiClient.get<ApiEnvelope<WorkProcessTask>>(
      `${base}/work-process-tasks/${this.parseTaskId(taskId)}`,
    );
    return { ...response, data: response.data ? this.toProcessTaskItem(response.data) : undefined };
  }

  async getTaskSummary(taskId: string, factoryId?: string): Promise<ApiEnvelope<ProcessTaskSummary>> {
    const taskResponse = await this.getTaskById(taskId, factoryId);
    const task = taskResponse.data;
    if (!task) return { ...taskResponse, data: undefined };
    return {
      ...taskResponse,
      data: {
        task,
        taskId: task.id,
        processName: task.processName,
        productName: task.productTypeName,
        plannedQuantity: task.plannedQuantity,
        completedQuantity: task.completedQuantity,
        pendingQuantity: 0,
        unit: task.unit,
        status: task.status,
        productionRunId: task.productionRunId,
      },
    };
  }

  async getRunOverview(productionRunId: string, factoryId?: string): Promise<ApiEnvelope<RunOverview>> {
    const base = this.getBase(factoryId);
    const batchId = this.parseBatchId(productionRunId);
    const response = await apiClient.get<ApiEnvelope<WorkProcessTask[]>>(
      `${base}/production/batches/${batchId}/work-process-tasks`,
    );
    const tasks = (response.data ?? []).map(task => this.toProcessTaskItem(task));
    const completedTasks = tasks.filter(task => task.status === 'COMPLETED').length;
    const overallProgress = tasks.length === 0 ? 0 : Math.round((completedTasks / tasks.length) * 100);
    return {
      ...response,
      data: { productionRunId: `BATCH-${batchId}`, tasks, overallProgress, completedTasks, totalTasks: tasks.length },
    };
  }

  // --- Work Reporting (Process Mode) ---

  async getPendingApprovals(params: { page?: number; size?: number } = {}, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-work-reporting/pending-approval`, { params });
  }

  async approveReport(reportId: number, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/${reportId}/approve`);
  }

  async rejectReport(reportId: number, reason: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/${reportId}/reject`, { reason });
  }

  async batchApprove(reportIds: number[], factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.put(`${base}/process-work-reporting/batch-approve`, reportIds);
  }

  /**
   * 报工列表 —— 替代 legacy `workReportingApiClient.getReports`。
   *
   * 后端 `ProcessWorkReportingController.listReports`，返回 Spring `Page<WorkReportResponse>`，
   * 与 legacy 同一个 DTO、同样的四个查询分支、同样的 reportDate 倒序，
   * 所以消费方读 `data.content` 的写法不用改。
   *
   * ⚠️ 与 legacy 的唯一行为差：新 controller 带 `@RequireModule("production_report")`，
   * 未开该模块的工厂会被挡（legacy 那条没有模块闸）。
   */
  async getWorkReports(
    params: { type?: string; startDate?: string; endDate?: string; page?: number; size?: number } = {},
    factoryId?: string,
  ): Promise<ApiEnvelope<PageResponse<WorkReportResponse>>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<PageResponse<WorkReportResponse>>>(
      `${base}/process-work-reporting/reports`,
      { params },
    );
  }

  /**
   * 报工看板汇总 —— 替代 legacy `workReportingApiClient.getSummary`。
   *
   * ⛔ 新端点<b>不接受</b> startDate/endDate：legacy 那两个参数只喂三个零消费方的字段
   * （progressSummary / hoursSummary / weeklyOutput），已随迁移砍掉。
   * RN 侧本来就是无参调用。
   */
  async getWorkReportSummary(factoryId?: string): Promise<ApiEnvelope<WorkReportSummary>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<WorkReportSummary>>(`${base}/process-work-reporting/summary`);
  }

  /**
   * 工序历史均值（异常检测用）—— 替代 legacy `workReportingApiClient.getHistoricalAverage`。
   *
   * ⚠️ 「这个工序近 N 天没报过工」的长相是 `sampleCount: 0`，⛔ 不是 null ——
   * 后端那条聚合是 COUNT(*)，空集上给 0。消费方靠 `sampleCount < 5` 判样本不够。
   */
  async getHistoricalAverage(
    processCategory: string,
    days: number = 30,
    factoryId?: string,
  ): Promise<ApiEnvelope<HistoricalAverage>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<HistoricalAverage>>(
      `${base}/process-work-reporting/reports/historical-average`,
      { params: { processCategory, days } },
    );
  }

  async getReportsByTask(taskId: string, factoryId?: string) {
    const base = this.getBase(factoryId);
    return apiClient.get(`${base}/process-work-reporting/by-task/${taskId}`);
  }

  async getWorkersByTask(taskId: string, factoryId?: string): Promise<ApiEnvelope<TaskWorkerRecord[]>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<TaskWorkerRecord[]>>(`${base}/process-work-reporting/by-task/${taskId}/workers`);
  }

  // --- Process Checkin (工序模式签到) ---

  async processCheckin(data: { employeeId: number; processName?: string; processCategory?: string; checkinMethod?: string; processTaskId?: string }, factoryId?: string): Promise<ApiEnvelope<ProcessCheckinResponseData>> {
    const base = this.getBase(factoryId);
    return apiClient.post<ApiEnvelope<ProcessCheckinResponseData>>(`${base}/process-checkin`, data);
  }

  async processCheckout(checkinRecordId: number, factoryId?: string): Promise<ApiEnvelope<null>> {
    const base = this.getBase(factoryId);
    return apiClient.post<ApiEnvelope<null>>(`${base}/process-checkin/checkout/${checkinRecordId}`);
  }

  async getActiveCheckins(factoryId?: string): Promise<ApiEnvelope<ProcessCheckinRecord[]>> {
    const base = this.getBase(factoryId);
    return apiClient.get<ApiEnvelope<ProcessCheckinRecord[]>>(`${base}/process-checkin/active`);
  }

}

export const processTaskApiClient = new ProcessTaskApiClient();
