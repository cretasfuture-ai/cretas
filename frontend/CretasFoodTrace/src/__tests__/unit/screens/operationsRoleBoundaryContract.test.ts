import fs from 'fs';
import path from 'path';
import { hasModulePermission } from '../../../utils/permissionHelper';

const screenSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/operations/OperationsHomeScreen.tsx'),
  'utf8',
);
const navigatorSource = fs.readFileSync(
  path.resolve(__dirname, '../../../navigation/OperationsNavigator.tsx'),
  'utf8',
);
const permissionSource = fs.readFileSync(
  path.resolve(__dirname, '../../../utils/permissionHelper.ts'),
  'utf8',
);
const reusedOrderSources = [
  'SalesOrderListScreen.tsx',
  'SalesOrderDetailScreen.tsx',
  'PurchaseOrderListScreen.tsx',
  'PurchaseOrderDetailScreen.tsx',
  'FinishedGoodsListScreen.tsx',
].map((file) => fs.readFileSync(
  path.resolve(__dirname, `../../../screens/factory-admin/inventory/${file}`),
  'utf8',
));

describe('operations coordinator mobile boundary', () => {
  it('supports application create, view and pre-approval withdrawal', () => {
    expect(screenSource).toContain('发起申请');
    expect(screenSource).toContain('提交审批');
    expect(screenSource).toContain("item.status === 'PENDING_APPROVAL'");
  });

  it('matches the backend role matrix: operations write, adjacent modules read', () => {
    // 2026-09-06: RN 不再自带手写矩阵 (BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906),
    // 真相来自登录响应 modulePermissions, 旧会话退到 prod L1 快照。这里守的是**性质**
    // (运营协调员: operations 可写, 相邻模块只读), 不再守矩阵字面量。
    expect(permissionSource).not.toContain('operations_coordinator: {');
    const coordinator = {
      id: 1, username: 'ops', email: '', createdAt: '', updatedAt: '', isActive: true,
      userType: 'factory' as const,
      factoryUser: { role: 'operations_coordinator' as const, factoryId: 'F006', permissions: [] },
    };
    expect(hasModulePermission(coordinator, 'operations', 'write')).toBe(true);
    for (const module of ['warehouse', 'procurement', 'sales']) {
      expect(hasModulePermission(coordinator, module, 'read')).toBe(true);
      expect(hasModulePermission(coordinator, module, 'write')).toBe(false);
    }
  });

  it('states that warehouse owns material and quantity receipt facts', () => {
    expect(screenSource).toContain('这里只申请，不处理入库');
    expect(screenSource).toContain('审批通过后再由仓管核对实物');
    expect(screenSource).not.toContain('label="物料');
    expect(screenSource).not.toContain('label="数量');
  });

  it('lists pending applications without reusing the warehouse receivable filter', () => {
    expect(screenSource).toContain('listCustomerMaterialArrivals(false)');
    expect(screenSource).toContain("notice.status === 'PENDING_APPROVAL'");
    expect(screenSource).toContain("{ value: 'pending', label: '待审批' }");
  });

  it('keeps cross-business access read-oriented without registering create screens', () => {
    expect(navigatorSource).toContain('ProductionPlanManagementScreen');
    expect(navigatorSource).toContain('SalesOrderListScreen');
    expect(navigatorSource).toContain('PurchaseOrderListScreen');
    expect(navigatorSource).toContain('FinishedGoodsListScreen');
    expect(navigatorSource).not.toContain('SalesOrderCreateScreen');
    expect(navigatorSource).not.toContain('PurchaseOrderCreateScreen');
    reusedOrderSources.forEach((source) => {
      expect(source).toContain('isMobileBusinessObserver(user)');
      expect(source).toContain('!isOperationsReadOnly');
    });
  });
});
