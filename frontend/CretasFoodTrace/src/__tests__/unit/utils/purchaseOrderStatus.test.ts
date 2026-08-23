/**
 * 采购单状态文案 —— 测试反馈 2026-08-12「审批后状态直接变成财务已审核」的 App 侧回归。
 *
 * 守两件事:
 *  1. 后端能返回的每一个状态都有中文文案 —— 不许把原始枚举串漏给用户;
 *  2. `FINANCE_APPROVED` 但没有财务审核人时, 不许说「财务已审核」。
 */
import fs from 'fs';
import path from 'path';
import {
  PURCHASE_ORDER_STATUS_MAP,
  NO_FINANCE_REVIEW_LABEL,
  resolvePurchaseOrderStatus,
} from '../../../utils/purchaseOrderStatus';

describe('resolvePurchaseOrderStatus', () => {
  it('财务真批过时说「财务已审核」', () => {
    const view = resolvePurchaseOrderStatus({ status: 'FINANCE_APPROVED', financeReviewedBy: 1553 });
    expect(view.label).toBe('财务已审核');
  });

  it('采购单收货完毕时说「已收货」而不是笼统的「已完成」', () => {
    expect(resolvePurchaseOrderStatus({ status: 'COMPLETED' }).label).toBe('已收货');
  });

  // 🔴 核心断言: 没有财务节点时不许谎称财务审核过。
  it.each([undefined, null, 0])(
    '没有财务审核人 (financeReviewedBy=%p) 时改说「无需财务审核」',
    (financeReviewedBy) => {
      const view = resolvePurchaseOrderStatus({
        status: 'FINANCE_APPROVED',
        financeReviewedBy: financeReviewedBy as number | null | undefined,
      });
      expect(view.label).toBe(NO_FINANCE_REVIEW_LABEL);
      expect(view.label).not.toContain('财务已审核');
    },
  );

  it('未知状态回落到原始串而不是崩', () => {
    expect(resolvePurchaseOrderStatus({ status: 'SOMETHING_NEW' }).label).toBe('SOMETHING_NEW');
  });

  it('空单据不炸', () => {
    expect(resolvePurchaseOrderStatus(null).label).toBe('-');
    expect(resolvePurchaseOrderStatus({}).label).toBe('-');
  });
});

describe('状态表覆盖后端枚举', () => {
  // 从后端枚举源码里取真值, 而不是在测试里再抄一份 —— 抄一份就守不住漂移。
  const enumPath = path.resolve(
    __dirname,
    '../../../../../../backend/java/cretas-api/src/main/java/com/cretas/aims/entity/enums/PurchaseOrderStatus.java',
  );

  it('阳性对照: 找得到后端枚举文件且解析出多个常量', () => {
    expect(fs.existsSync(enumPath)).toBe(true);
    expect(backendStatuses().length).toBeGreaterThan(5);
  });

  function backendStatuses(): string[] {
    const src = fs.readFileSync(enumPath, 'utf8');
    const body = src.slice(src.indexOf('{'), src.indexOf(';', src.indexOf('{')));
    return Array.from(body.matchAll(/^\s*([A-Z][A-Z0-9_]+)\s*\(/gm))
      .map((m) => m[1])
      .filter((s): s is string => Boolean(s));
  }

  it('后端每个状态在 App 侧都有中文文案', () => {
    const missing = backendStatuses().filter((s) => !PURCHASE_ORDER_STATUS_MAP[s]);
    expect(missing).toEqual([]);
  });
});

describe('两块采购屏不许各自再抄一份状态表', () => {
  const screens = [
    'screens/factory-admin/inventory/PurchaseOrderListScreen.tsx',
    'screens/factory-admin/inventory/PurchaseOrderDetailScreen.tsx',
  ];

  it.each(screens)('%s 用共享 helper 而不是本地 STATUS_MAP', (rel) => {
    const src = fs.readFileSync(path.resolve(__dirname, '../../../', rel), 'utf8');
    expect(src).toContain('resolvePurchaseOrderStatus');
    // 本地再定义一张表 = 又一次漂移的起点。
    expect(src).not.toMatch(/const\s+STATUS_MAP\s*[:=]/);
  });

  it('采购列表的 COMPLETED 筛选也显示「已收货」', () => {
    const src = fs.readFileSync(path.resolve(__dirname, '../../../screens/factory-admin/inventory/PurchaseOrderListScreen.tsx'), 'utf8');
    expect(src).toContain("{ value: 'COMPLETED', label: '已收货' }");
    expect(src).not.toContain("{ value: 'COMPLETED', label: '已完成' }");
  });
});
