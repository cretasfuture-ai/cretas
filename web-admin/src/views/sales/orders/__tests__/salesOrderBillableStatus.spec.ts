/**
 * 「开票 / 登记收款 / 税率分组开票」这三个行内操作的**状态可达性**。
 *
 * 🔴 为什么要这道闸 (2026-08-22 实测):
 * 三处条件各自硬写数组, 都含一个在 statusMap 里**不存在的订单状态值**
 * (那个值真正的归属是「出库状态」域), 并且三处都**漏了 FINANCE_APPROVED**
 * —— 审批通过后最常见的状态。
 *
 * 实测 F006 全部 34 张单的状态分布:
 *   CANCELLED 9 / FINANCE_APPROVED 9 / COMPLETED 8 / DRAFT 6 /
 *   PENDING_FINANCE_REVIEW 1 / PARTIAL_DELIVERED 1
 * ⇒ 条件里唯二能命中的 CONFIRMED / PROCESSING **一张都没有**
 * ⇒ 这三个操作在生产环境里**一张单都点不出来**, 而没人发现。
 *
 * ▎「一个选项永远出不来」和「这个选项被正确地隐藏了」在界面上长得一模一样。
 *   所以闸不能只钉「该出现时出现」, 必须同时钉**真实状态分布里至少有一个能命中**。
 */
import { describe, it, expect } from 'vitest';
import { BILLABLE_STATUSES, PAYABLE_STATUSES } from '../salesOrderBillableStatus';

/** statusMap 里真实存在的订单状态(与 list.vue 的 statusMap 同源, 改一处必须改两处 → 见下面那条闸)。 */
const REAL_ORDER_STATUSES = [
  'DRAFT',
  'CONFIRMED',
  'PENDING_FINANCE_REVIEW',
  'FINANCE_APPROVED',
  'FINANCE_REJECTED',
  'PROCESSING',
  'PARTIAL_DELIVERED',
  'COMPLETED',
  'CANCELLED',
  // T-SHORT-SHIP (2026-09-04): 新终态, 与 list.vue/detail.vue 的 statusMap 同步登记。
  'SHORT_CLOSED',
];

/** 2026-08-22 F006 prod 实测分布 —— 用来钉「这些操作在真实数据上够得着」。 */
const PROD_STATUS_SAMPLE = [
  'CANCELLED', 'FINANCE_APPROVED', 'COMPLETED', 'DRAFT',
  'PENDING_FINANCE_REVIEW', 'PARTIAL_DELIVERED',
];

describe('可开票/可收款状态集合', () => {
  it('⛔ 集合里不许出现 statusMap 之外的状态值(死值检测)', () => {
    for (const s of [...BILLABLE_STATUSES, ...PAYABLE_STATUSES]) {
      expect(REAL_ORDER_STATUSES, `「${s}」不是真实的订单状态 —— 它永远匹配不上`).toContain(s);
    }
  });

  it('🔴 在生产环境的真实状态分布上, 至少有一张单够得着(可达性)', () => {
    // ⚠️ 这条才是上一版缺的: 集合「写得对」不等于「有单子能落进来」。
    const reachable = PROD_STATUS_SAMPLE.filter((s) => BILLABLE_STATUSES.includes(s));
    expect(reachable.length, '开票操作在真实数据上一张单都点不出来').toBeGreaterThan(0);
    const payable = PROD_STATUS_SAMPLE.filter((s) => PAYABLE_STATUSES.includes(s));
    expect(payable.length, '收款操作在真实数据上一张单都点不出来').toBeGreaterThan(0);
  });

  it('⛔ 没过审/已取消的单不许开票收款(阴性对照)', () => {
    for (const s of ['DRAFT', 'PENDING_FINANCE_REVIEW', 'FINANCE_REJECTED', 'CANCELLED']) {
      expect(BILLABLE_STATUSES, `${s} 不该能开票`).not.toContain(s);
      expect(PAYABLE_STATUSES, `${s} 不该能收款`).not.toContain(s);
    }
  });

  it('收款集合是开票集合的超集(收款可以晚于交付完成)', () => {
    for (const s of BILLABLE_STATUSES) expect(PAYABLE_STATUSES).toContain(s);
    expect(PAYABLE_STATUSES).toContain('COMPLETED');
    expect(BILLABLE_STATUSES).not.toContain('COMPLETED');
  });

  it('T-SHORT-SHIP: 短交结案不冻结已发货部分的开票/收款能力', () => {
    expect(BILLABLE_STATUSES).toContain('SHORT_CLOSED');
    expect(PAYABLE_STATUSES).toContain('SHORT_CLOSED');
  });
});
