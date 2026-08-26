/**
 * 「我的工资」API 客户端 —— 只读, 只读**自己**的。
 *
 * 端点 (后端 MyWageController):
 *   GET /api/mobile/{factoryId}/wage/me/monthly?month=yyyy-MM
 *   GET /api/mobile/{factoryId}/wage/me/payrolls?limit=N
 *
 * 🔴 <b>刻意与 {@link ./wageApiClient} 分开的两个理由</b>:
 *
 * 1. `wageApiClient` 里有 `getTopEarners()` (工资排行榜) 和 `getEfficiencyRanking()`
 *    (人效排名)。owner 裁定<b>不做个人排行榜</b> —— 质量责任归工厂不归个人,
 *    且奖励产出会诱导少报不良品。工人屏一个都不许碰, 分开文件让「顺手 import 一个」
 *    这件事不会发生。
 * 2. `wageApiClient` 打的是 `/wage/*`, 那些端点吃 hr/finance 权限闸,
 *    车间工人调过去<b>一定 403</b>。这里打的是 `/wage/me/*`, 走 `hr:self_wage:view`。
 *
 * ⛔ 本文件里的任何函数都不许接受 workerId 参数 —— 后端也不接受, 身份只从 token 推导。
 *
 * @since 2026-08-25
 */

import { apiClient } from './apiClient';
import { getCurrentFactoryId } from '../../utils/factoryIdHelper';

// ──────────────────────────────────────────────────────────────────────────────
// 类型 (mirror 后端 MyWageResponse.java —— 加字段时两边一起改)
// ──────────────────────────────────────────────────────────────────────────────

/** 工资模式。 */
export type WageMode = 'PIECE_RATE' | 'HOURLY' | 'MIXED';

/** 工资单状态全集 —— 与后端 WageCalculationService.PAYROLL_STATUS_* 一致, 没有草稿态。 */
export type PayrollStatus = 'PENDING' | 'APPROVED' | 'PAID';

/**
 * 「拿不到」的原因码。
 *
 * ⚠️ 后端拿不到金额时返回 `null` + 这里的一个原因码, ⛔ 从不返回 0。
 * 前端必须把 null 显示成「—」并把原因说出来 —— 把「我还没查到」显示成「0 元」
 * 在工资这件事上是最糟的错误。
 */
export type UnavailableReason =
  | 'NO_PAYROLL_FOR_PERIOD'    // 这个月还没生成工资单
  | 'NOT_CALCULATED_YET'       // 系统还没试算过这个月
  | 'NO_PIECE_RATE_RULE'       // 这道工序没配计件单价
  | 'NO_HOURLY_RATE_RULE'      // 没配时薪
  | 'NOT_APPLICABLE_FOR_MODE'; // 按件算的人本来就没有时薪

export interface MyWageWorkTotals {
  recordCount: number;
  workedDays: number;
  totalPieceCount: number;
  qualifiedCount: number;
  defectCount: number;
  totalWorkMinutes: number;
  /** >0 说明有报工没记件数, 汇总会偏低 —— 屏上要说出来, ⛔ 不许当没看见。 */
  rowsMissingPieceCount: number;
}

/**
 * 一条报工记录 = (工人, 日期, 工序)。
 *
 * ⚠️ <b>这不是「一次报工」</b>: 同一天同一道工序报 5 次会累加成同一行
 * (后端 WageRecordTriggerService 是 upsert 累加)。所以「26 笔报工逐笔对账」
 * 在当前数据模型下做不到, 最细只到「天 × 工序」。
 *
 * ⚠️ <b>没有金额字段, 是后端故意不给的</b>: 计件单价是阶梯的, 「这一行值多少钱」
 * 取决于它在整月累计里的位置 —— 逐行金额加起来<b>不等于</b>月度金额。
 * 编一个出来会让工人一加发现跟工资单对不上, 反而制造扯皮。
 */
export interface MyWageRecord {
  id: number | null;
  workDate: string | null;          // ISO date "2026-08-03"
  processStageType: string | null;
  productTypeId: string | null;
  workstationName: string | null;
  /** null = 这条没记件数, ⛔ 不是 0。 */
  totalPieceCount: number | null;
  qualifiedCount: number | null;
  defectCount: number | null;
  effectiveWorkMinutes: number | null;
  sourceBatchId: number | null;
  updatedAt: string | null;
}

export interface MyWagePieceRateInfo {
  processStageType: string | null;
  pieceCountInMonth: number;
  ruleId: number | null;
  ruleName: string | null;
  tier1Threshold: number | null;
  tier1Rate: number | null;
  tier2Threshold: number | null;
  tier2Rate: number | null;
  tier3Threshold: number | null;
  tier3Rate: number | null;
  unavailableReason: UnavailableReason | null;
}

export interface MyWageHourlyRateInfo {
  ruleId: number | null;
  baseHourlyRate: number | null;
  overtimeMultiplier: number | null;
  effectiveFrom: string | null;
  effectiveTo: string | null;
}

/** 系统自动试算 —— ⚠️ <b>没有审批状态</b>, 不代表财务认可, 更不代表已经发。 */
export interface MyWageCalculation {
  mode: WageMode | null;
  hourlyAmount: number | null;
  overtimeAmount: number | null;
  pieceRateAmount: number | null;
  totalAmount: number | null;
  totalHours: number | null;
  overtimeHours: number | null;
  totalPieceCount: number | null;
  /** 有值说明这个数是不全的, 例「缺 PieceRateRule(CUTTING)」—— 必须显示给工人。 */
  notes: string | null;
  calculatedAt: string | null;
}

/** 工资单 —— <b>发到手的就是这个数</b>。 */
export interface MyWagePayroll {
  id: number | null;
  periodStart: string | null;
  periodEnd: string | null;
  periodType: string | null;
  status: PayrollStatus | string | null;
  statusLabel: string | null;
  paid: boolean;
  baseSalary: number | null;
  pieceRateWage: number | null;
  overtimeWage: number | null;
  overtimeHours: number | null;
  bonusAmount: number | null;
  deductionAmount: number | null;
  totalWage: number | null;
  totalPieceCount: number | null;
  totalWorkHours: number | null;
  approvedAt: string | null;
  paidAt: string | null;
}

/**
 * ⛔ <b>没有「总计」字段, 是后端刻意不给的</b>。
 * 「已发放 ¥3200 + 待审核 ¥800」不是「¥4000」—— 后面那 800 财务还没点头,
 * 甚至可能被驳回。三档各自成立, 屏上也必须分三行。
 */
export interface MyWagePayrollTotals {
  /** null = 这一档一张单都没有 (⛔ 与「合计 0 元」是两回事)。 */
  paidTotal: number | null;
  paidCount: number;
  approvedTotal: number | null;
  approvedCount: number;
  pendingTotal: number | null;
  pendingCount: number;
}

export interface MyWageMonthly {
  factoryId: string;
  workerId: number;
  workerName: string | null;
  month: string;                 // "2026-08"
  periodStart: string | null;
  periodEnd: string | null;
  wageMode: WageMode;
  wageModeLabel: string;
  mixedFormulaHint: string | null;
  work: MyWageWorkTotals;
  records: MyWageRecord[];
  pieceRates: MyWagePieceRateInfo[];
  hourlyRate: MyWageHourlyRateInfo | null;
  hourlyRateUnavailableReason: UnavailableReason | null;
  calculation: MyWageCalculation | null;
  calculationUnavailableReason: UnavailableReason | null;
  payrolls: MyWagePayroll[];
  payrollTotals: MyWagePayrollTotals | null;
  payrollUnavailableReason: UnavailableReason | null;
}

/** 后端统一响应信封 (apiClient 的拦截器已经把 axios 那层剥掉了)。 */
export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
  success: boolean;
}

// ──────────────────────────────────────────────────────────────────────────────
// 客户端
// ──────────────────────────────────────────────────────────────────────────────

class MyWageApiClient {
  private getPath(factoryId?: string): string {
    const currentFactoryId = getCurrentFactoryId(factoryId);
    if (!currentFactoryId) {
      // ⛔ 不猜 factoryId, 也不用 DEFAULT_FACTORY_ID 兜底 —— 兜底会去查别的厂。
      throw new Error('还没拿到你所属的工厂, 请重新登录后再看工资');
    }
    return `/api/mobile/${currentFactoryId}/wage/me`;
  }

  /**
   * 我的某个月工资。
   *
   * @param month 月份 `yyyy-MM`; 不传 = 后端取本月
   * @param factoryId 可选; 不传时从登录态取
   */
  async getMonthly(month?: string, factoryId?: string): Promise<ApiEnvelope<MyWageMonthly>> {
    return apiClient.get<ApiEnvelope<MyWageMonthly>>(
      `${this.getPath(factoryId)}/monthly`,
      { params: month ? { month } : {} },
    );
  }

  /** 我的工资单历史 (跨月, 按周期倒序)。 */
  async getPayrollHistory(limit = 12, factoryId?: string): Promise<ApiEnvelope<MyWagePayroll[]>> {
    return apiClient.get<ApiEnvelope<MyWagePayroll[]>>(
      `${this.getPath(factoryId)}/payrolls`,
      { params: { limit } },
    );
  }
}

export const myWageApiClient = new MyWageApiClient();
export default myWageApiClient;
