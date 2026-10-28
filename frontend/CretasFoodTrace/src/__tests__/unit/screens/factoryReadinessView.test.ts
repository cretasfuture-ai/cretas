import {
  FIRST_ACTION_SCREEN,
  firstActionTarget,
  missingChecks,
} from '../../../screens/factory-admin/home/readinessView';
import type { ReadinessDomain } from '../../../services/api/factoryReadinessApiClient';

function domain(overrides: Partial<ReadinessDomain>): ReadinessDomain {
  return {
    domain: 'PURCHASE',
    label: '采购',
    applicability: 'APPLICABLE',
    applicabilityBasis: '蓝图已选择 materials',
    state: 'READY',
    checks: [],
    notChecked: [],
    firstRecord: { state: 'NONE', detail: '尚无采购单' },
    firstAction: { label: '新建第一张采购单', path: '/procurement/orders', query: {}, detail: '' },
    ...overrides,
  };
}

describe('建厂就绪（RN）', () => {
  it('适用且已就绪、服务端给了首笔入口 → 跳到对应业务页', () => {
    expect(firstActionTarget(domain({}))).toEqual({ screen: 'PurchaseOrderList', label: '新建第一张采购单' });
    expect(FIRST_ACTION_SCREEN).toEqual({
      PURCHASE: 'PurchaseOrderList',
      PRODUCTION: 'ProductionPlanManagement',
      SHIPPING: 'SalesOrderList',
    });
  });

  it('阴性对照: 缺项 / 无法判断 / 不适用 / 服务端没给入口时都不给首笔入口', () => {
    expect(firstActionTarget(domain({ state: 'MISSING' }))).toBeNull();
    expect(firstActionTarget(domain({ state: 'UNKNOWN' }))).toBeNull();
    expect(firstActionTarget(domain({ applicability: 'NOT_APPLICABLE', state: 'NOT_APPLICABLE' }))).toBeNull();
    expect(firstActionTarget(domain({ firstAction: null }))).toBeNull();
  });

  it('缺项列表只含未满足的检查（包材换算、岗位账号也在内）', () => {
    const checks = [
      { code: 'A', label: 'a', state: 'MET', detail: '', remedyKey: 'X', remedyLabel: 'x' },
      { code: 'PACKAGING_PURCHASE_CONVERSION', label: '包装换算', state: 'MISSING', detail: '缺', remedyKey: 'MATERIAL_PACKAGING', remedyLabel: '原料类型' },
      { code: 'PURCHASE_POST_HOLDER', label: '岗位', state: 'UNKNOWN', detail: '', remedyKey: 'USERS', remedyLabel: '账号' },
    ] as ReadinessDomain['checks'];
    expect(missingChecks(domain({ checks })).map((check) => check.code)).toEqual([
      'PACKAGING_PURCHASE_CONVERSION',
      'PURCHASE_POST_HOLDER',
    ]);
  });
});
