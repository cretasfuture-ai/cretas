import { describe, expect, it } from 'vitest';
import {
  cancellationAudit,
  formatPlanActualQuantity,
  planSheetProductLabel,
  sourceOrderDisplay,
  sourceOrderTarget,
} from '../productionPlanListPresentation';

describe('production plan list presentation', () => {
  it('localizes actual quantity without changing canonical payload units', () => {
    expect(formatPlanActualQuantity({ actualQuantity: 5, plannedUnit: 'box' })).toBe('5 盒');
    expect(formatPlanActualQuantity({ actualQuantity: 2, plannedUnit: 'case' })).toBe('2 箱');
    expect(formatPlanActualQuantity({ actualQuantity: 4.5, plannedUnit: 'kg' })).toBe('4.5 kg');
    expect(formatPlanActualQuantity({ actualQuantity: 1, plannedUnit: null })).toBe('1（单位未配置）');
  });

  it('shows and links the business sales-order number instead of exposing a UUID', () => {
    const row = {
      customerOrderNumber: 'SO-20260720-0001',
      sourceOrderId: 'ecd7f20b-21c2-4ea3-9103-2034d5d6547f',
    };
    expect(sourceOrderDisplay(row)).toBe('SO-20260720-0001');
    expect(sourceOrderTarget(row)).toEqual({
      path: '/sales/orders/ecd7f20b-21c2-4ea3-9103-2034d5d6547f',
    });
    expect(sourceOrderDisplay({ sourceOrderId: row.sourceOrderId })).toBe('业务订单号未同步');
  });

  it('renders the immutable cancellation reason, operator and time', () => {
    expect(cancellationAudit({
      status: 'CANCELLED',
      cancelReason: '重复创建的空白测试计划，无正式报工、结算或库存',
      cancelledBy: 42,
      cancelledByName: '生产主管',
      cancelledAt: '2026-07-24T03:30:12',
    })).toEqual({
      reason: '重复创建的空白测试计划，无正式报工、结算或库存',
      actor: '生产主管',
      time: '2026-07-24 03:30:12',
    });
    expect(cancellationAudit({ status: 'PENDING' })).toBeNull();
  });

  it('labels the process-entry sheet like the 生产成品 column, never with the raw-workflow anchor', () => {
    const anchor = '__RAW_WORKFLOW_OWNER__RMT_F006_LSM_2bce8bda718ed689';
    expect(planSheetProductLabel(['香酥炸鸡腿', '盐酥鸡块', '鸡骨高汤']))
      .toBe('多产出计划：香酥炸鸡腿、盐酥鸡块、鸡骨高汤');
    expect(planSheetProductLabel(['猪蹄'])).toBe('猪蹄');
    expect(planSheetProductLabel([anchor])).toBe('多产出计划');
    expect(planSheetProductLabel(['多产出计划'])).toBe('多产出计划');
    expect(planSheetProductLabel([anchor, '香酥炸鸡腿'])).toBe('香酥炸鸡腿');
    expect(planSheetProductLabel(['-'])).toBe('');
    expect(planSheetProductLabel([])).toBe('');
  });
});
