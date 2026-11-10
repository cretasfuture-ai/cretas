import fs from 'fs';
import path from 'path';
import {
  PRODUCTION_SETTLEMENT_REQUIRED,
  WEB_SETTLEMENT_PATH,
  settlementRequiredNotice,
} from '../../../utils/settlementRequired';

/** 纪律排查 A7: 默认要求先结单, 手机直接完工被 409 拦时给出「去网页端结单」的明确提示。 */
const err409 = (errorCode: string) => ({
  response: { status: 409, data: { errorCode, message: '本厂设置为生产完成必须先核对结单' } },
});

describe('settlementRequiredNotice', () => {
  it('409 PRODUCTION_SETTLEMENT_REQUIRED → 说清去网页端哪里结单, 带计划号和网页端路径', () => {
    const n = settlementRequiredNotice(err409(PRODUCTION_SETTLEMENT_REQUIRED), 'PP-001');
    expect(n).not.toBeNull();
    expect(n!.title).toBe('本厂要求先结单');
    expect(n!.message).toContain('请到网页端');
    expect(n!.message).toContain('生产管理 → 生产计划');
    expect(n!.message).toContain(WEB_SETTLEMENT_PATH);
    expect(WEB_SETTLEMENT_PATH).toBe('/production/plans');
    expect(n!.message).toContain('计划 PP-001');
    expect(n!.message).toContain('核对结单');
  });

  it('阴性对照: 别的 409 (超产原因) 和非 409 不走这条提示, 仍弹原始错误', () => {
    expect(settlementRequiredNotice(err409('PRODUCTION_OVER_PLAN_REASON_REQUIRED'), 'PP-001')).toBeNull();
    expect(settlementRequiredNotice({ response: { status: 500, data: { errorCode: PRODUCTION_SETTLEMENT_REQUIRED } } })).toBeNull();
    expect(settlementRequiredNotice(new Error('network'))).toBeNull();
    expect(settlementRequiredNotice(null)).toBeNull();
  });

  it('两个手机完工入口都接了这条提示', () => {
    for (const rel of [
      '../../../screens/processing/ProductionPlanManagementScreen.tsx',
      '../../../screens/dispatcher/plan/PlanDetailScreen.tsx',
    ]) {
      const src = fs.readFileSync(path.resolve(__dirname, rel), 'utf8');
      expect(src).toContain('settlementRequiredNotice(');
    }
  });
});
