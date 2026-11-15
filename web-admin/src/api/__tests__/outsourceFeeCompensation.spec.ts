import { describe, expect, it, vi } from 'vitest';

vi.mock('../request', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn() }));

import {
  isFeeCompensation,
  movementReasonText,
  movementShowsQuantity,
  movementTypeText,
  type OutsourceMovementView,
} from '../outsource';

type M = Pick<OutsourceMovementView, 'movementType' | 'reason' | 'parentMovementId'>;

// #1723 低4 规则 3 的补冲减: 后端存成 GOODS_RETURN + parent + reason=UPSTREAM_REJECTED:<调整id>, 没有实物退回
const comp: M = { movementType: 'GOODS_RETURN', parentMovementId: 'MV-RE-RETURN', reason: 'UPSTREAM_REJECTED:ADJ-1' };
const realReturn: M = { movementType: 'GOODS_RETURN', parentMovementId: null, reason: '封口不良' };

describe('委外收发明细: 补冲减不显示成退货件数', () => {
  it('补冲减显示「补冲减（上游驳回）」, 不显示件数, 不露内部前缀', () => {
    expect(isFeeCompensation(comp)).toBe(true);
    expect(movementTypeText(comp)).toBe('补冲减（上游驳回）');
    expect(movementShowsQuantity(comp)).toBe(false);
    expect(movementReasonText(comp)).not.toContain('UPSTREAM_REJECTED');
    expect(movementReasonText(comp)).toContain('驳回');
  });

  it('阳性对照: 真实退货仍显示「退货」、件数和原因原文', () => {
    expect(isFeeCompensation(realReturn)).toBe(false);
    expect(movementTypeText(realReturn)).toBe('退货');
    expect(movementShowsQuantity(realReturn)).toBe(true);
    expect(movementReasonText(realReturn)).toBe('封口不良');
  });

  it('阴性对照: 只有前缀没有 parent / 不是退货, 都不算补冲减', () => {
    expect(isFeeCompensation({ ...comp, parentMovementId: null })).toBe(false);
    expect(isFeeCompensation({ ...comp, movementType: 'LOSS' })).toBe(false);
    expect(movementTypeText({ movementType: 'RECEIVE', parentMovementId: 'X', reason: null })).toBe('收货');
  });
});
