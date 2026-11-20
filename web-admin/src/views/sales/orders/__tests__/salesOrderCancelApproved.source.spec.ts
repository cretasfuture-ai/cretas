import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import { computeRowActions } from '@/composables/useRowActions';

/**
 * 财务批准后取消 (工厂开关 sales.order.cancel_after_approval, 2026-11-11):
 * 已批准订单上的「取消订单」只能按后端 cancel-policy 出现, 不能写死在通用菜单里 (开关关着时那是一个必然 409 的按钮)。
 */
const listSource = readFileSync(resolve(__dirname, '../list.vue'), 'utf8');

describe('财务已批准订单的取消入口', () => {
  it('通用菜单配置里 FINANCE_APPROVED 不再带 cancel (阴性)', () => {
    const ids = computeRowActions('salesOrder', { status: 'FINANCE_APPROVED', id: '1' }, { canViewPrice: true })
      .map((a) => a.id);
    expect(ids).not.toContain('cancel');
    // 阳性对照: 别的状态的 cancel 没被一起删掉
    expect(computeRowActions('salesOrder', { status: 'APPROVED', id: '1' }, { canViewPrice: true }).map((a) => a.id))
      .toContain('cancel');
  });

  it('列表页按 cancel-policy 决定已批准订单显不显示取消, 读失败当不能', () => {
    expect(listSource).toContain('/sales/orders/cancel-policy');
    expect(listSource).toMatch(/status === 'FINANCE_APPROVED' && canCancelApproved\.value/);
    expect(listSource).toMatch(/catch \(error\) \{[\s\S]{0,120}canCancelApproved\.value = false/);
  });
});
