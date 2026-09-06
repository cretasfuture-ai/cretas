/**
 * BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906
 *
 * RN 以前自带一份手写 PERMISSION_MATRIX, 与 prod L1 量出 29 格漂移。现在:
 *   1. `hasModulePermission` 先读登录响应 `factoryUser.modulePermissions` (后端 L2→L1→硬编码 逐格解析)
 *   2. 没带 (OTA 之前登录的旧会话) 才退到本地 L1 快照 (生成物)
 *
 * 每条断言都配一条「能让它红」的对照 —— 特别是「登录响应赢过快照」这一条, 用的格必须是
 * 两份**不同**的格, 否则恒真 (形态 B⁴)。
 */
import {
  hasModulePermission,
  canAccessModule,
  parseModulePermissions,
  levelAllows,
  MODULE_LEVEL_WILDCARD,
  L1_SNAPSHOT_EXPORTED_AT,
} from '../../../utils/permissionHelper';
import { canShortCloseSalesOrder } from '../../../utils/salesOrderShortClose';
import { transformBackendUser } from '../../../utils/roleMapping';
import type { FactoryUser, ModulePermissions, User } from '../../../types/auth';
import l1Snapshot from '../../../utils/permissionMatrix.l1Snapshot.json';

function factoryUser(role: string, modulePermissions?: ModulePermissions): FactoryUser {
  return {
    id: 1,
    username: `${role}-probe`,
    email: '',
    createdAt: '2026-09-06T00:00:00Z',
    updatedAt: '2026-09-06T00:00:00Z',
    isActive: true,
    userType: 'factory',
    roleCode: role as FactoryUser['roleCode'],
    factoryUser: {
      role: role as FactoryUser['factoryUser']['role'],
      factoryId: 'F006',
      factoryType: 'FACTORY',
      permissions: [],
      ...(modulePermissions === undefined ? {} : { modulePermissions }),
    },
  };
}

const snapshot = l1Snapshot.levels as Record<string, Record<string, string> | undefined>;
const cell = (role: string, module: string): string | undefined => snapshot[role]?.[module];

describe('hasModulePermission — 登录响应 modulePermissions 是真相', () => {
  it('登录响应存在时只看它: 与快照不同的格以登录响应为准 (阳性对照: 两份在这一格确实不同)', () => {
    // viewer.sales: prod L1 快照 r —— 登录响应给 rw 就该能写
    expect(cell('viewer', 'sales')).toBe('r');
    const viewerWithRw = factoryUser('viewer', { sales: 'rw' });
    const viewerOld = factoryUser('viewer');
    const observed = {
      fromLogin: hasModulePermission(viewerWithRw, 'sales', 'write'),
      fromSnapshot: hasModulePermission(viewerOld, 'sales', 'write'),
    };
    console.warn('[modulePermissions] viewer sales:write', observed);
    expect(observed.fromLogin).toBe(true);
    expect(observed.fromSnapshot).toBe(false);

    // 反方向: permission_admin.sales 快照 - (就是 #640 那一格), 登录响应给 - 也必须是 -
    expect(cell('permission_admin', 'sales')).toBe('-');
    expect(hasModulePermission(factoryUser('permission_admin', { sales: '-' }), 'sales', 'read')).toBe(false);
    expect(hasModulePermission(factoryUser('permission_admin', { sales: 'rw' }), 'sales', 'write')).toBe(true);
  });

  it('登录响应存在但没有这个模块 ⇒ 无权限, ⛔ 不漏到快照 (对照: 同角色没带登录响应时快照会放行)', () => {
    expect(cell('viewer', 'dashboard')).toBe('r');
    const withMap = factoryUser('viewer', { sales: 'rw' });   // 带了 map, 但 map 里没有 dashboard
    const withoutMap = factoryUser('viewer');
    const observed = {
      withMap: hasModulePermission(withMap, 'dashboard', 'read'),
      withoutMap: hasModulePermission(withoutMap, 'dashboard', 'read'),
    };
    console.warn('[modulePermissions] viewer dashboard:read', observed);
    expect(observed.withMap).toBe(false);
    expect(observed.withoutMap).toBe(true);
  });

  it('空对象 {} = 带了但一格都没有 ⇒ 全 deny; undefined = 没带 ⇒ 退到快照 (两者语义不同)', () => {
    const empty = factoryUser('factory_super_admin', {});
    const absent = factoryUser('factory_super_admin');
    expect(hasModulePermission(empty, 'production', 'read')).toBe(false);
    expect(hasModulePermission(absent, 'production', 'read')).toBe(true);   // 快照 factory_super_admin.production rw
    expect(cell('factory_super_admin', 'production')).toBe('rw');
  });

  it('超管通配 "*": 任何模块读写都放行, 与后端超管分支同口径', () => {
    const admin = factoryUser('factory_super_admin', { [MODULE_LEVEL_WILDCARD]: 'rw' });
    for (const module of ['production', 'operations', 'module_nobody_registered']) {
      expect(hasModulePermission(admin, module, 'read')).toBe(true);
      expect(hasModulePermission(admin, module, 'write')).toBe(true);
    }
    // 显式格压过通配
    const adminButNoHr = factoryUser('factory_super_admin', { [MODULE_LEVEL_WILDCARD]: 'rw', hr: '-' });
    expect(hasModulePermission(adminButNoHr, 'hr', 'read')).toBe(false);
    expect(hasModulePermission(adminButNoHr, 'production', 'read')).toBe(true);
  });

  it('canAccessModule 走同一条路', () => {
    expect(canAccessModule(factoryUser('viewer', { sales: 'w' }), 'sales')).toBe(true);
    expect(canAccessModule(factoryUser('viewer', { sales: '-' }), 'sales')).toBe(false);
    expect(canAccessModule(factoryUser('viewer', {}), 'sales')).toBe(false);
  });
});

describe('快照兜底 — 2026-09-06 量出的漂移格现在与 prod L1 一致', () => {
  // 每一格都是 diff 表里的一行: (role, module, RN 旧矩阵, prod L1)
  const formerlyDrifted: Array<[string, string, string, 'read' | 'write', boolean]> = [
    ['permission_admin', 'sales', 'RN 旧 rw / L1 -', 'write', false],
    ['permission_admin', 'system', 'RN 旧 rw / L1 -', 'read', false],
    ['operator', 'production', 'RN 旧 w / L1 rw', 'read', true],
    ['quality_inspector', 'quality', 'RN 旧 w / L1 rw', 'read', true],
    ['warehouse_worker', 'warehouse', 'RN 旧 w / L1 rw', 'read', true],
    ['finance_manager', 'procurement', 'RN 旧 r / L1 -', 'read', false],
    ['sales_manager', 'warehouse', 'RN 旧 r / L1 -', 'read', false],
    ['production_manager', 'system', 'RN 旧 r / L1 -', 'read', false],
    ['viewer', 'finance', 'RN 旧 - / L1 r', 'read', true],
    ['factory_super_admin', 'operations', 'RN 旧 - / L1 rw', 'write', true],
  ];

  it.each(formerlyDrifted)('%s.%s (%s) %s ⇒ %s', (role, module, _why, action, expected) => {
    const user = factoryUser(role);   // 没带 modulePermissions ⇒ 走快照
    expect(hasModulePermission(user, module, action)).toBe(expected);
  });

  it('快照标着导出日期, 且 dispatcher / yield_operator 这类 RN 旧矩阵根本没有的角色现在有格', () => {
    expect(L1_SNAPSHOT_EXPORTED_AT).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(hasModulePermission(factoryUser('dispatcher'), 'production', 'read')).toBe(true);
    expect(hasModulePermission(factoryUser('yield_operator'), 'production', 'write')).toBe(true);
    expect(hasModulePermission(factoryUser('unactivated'), 'dashboard', 'read')).toBe(false);
    expect(hasModulePermission(factoryUser('role_that_does_not_exist'), 'dashboard', 'read')).toBe(false);
  });
});

describe('parseModulePermissions / levelAllows', () => {
  it('null/undefined/非对象 ⇒ undefined (没带); 对象 ⇒ 只收合法格', () => {
    expect(parseModulePermissions(null)).toBeUndefined();
    expect(parseModulePermissions(undefined)).toBeUndefined();
    expect(parseModulePermissions('rw')).toBeUndefined();
    expect(parseModulePermissions(['sales'])).toBeUndefined();
    expect(parseModulePermissions({})).toEqual({});
    expect(parseModulePermissions({ sales: 'rw', hr: 'bogus', finance: 7, '*': 'r' })).toEqual({ sales: 'rw', '*': 'r' });
  });

  it('级别 → 动作 与后端 checkAction 的 read/write 分支同一张表', () => {
    const table: Array<[string | undefined, boolean, boolean]> = [
      ['rw', true, true],
      ['r', true, false],
      ['w', false, true],
      ['-', false, false],
      [undefined, false, false],
      ['read_write', false, false],   // legacy 串不在合法集里 —— 后端已 normalize, 这里不猜
    ];
    for (const [level, read, write] of table) {
      expect(levelAllows(level, 'read')).toBe(read);
      expect(levelAllows(level, 'write')).toBe(write);
    }
  });
});

describe('产品真实入口', () => {
  const shortOrder = {
    status: 'PARTIAL_DELIVERED',
    items: [{ productName: '美式汉堡饼', quantity: 10, deliveredQuantity: 4, unit: 'kg' }],
  };

  it('短交结案按钮: 同一个 sales_manager, 登录响应 sales:- ⇒ 不出现; sales:rw ⇒ 出现', () => {
    const denied = canShortCloseSalesOrder(shortOrder, factoryUser('sales_manager', { sales: '-' }));
    const allowed = canShortCloseSalesOrder(shortOrder, factoryUser('sales_manager', { sales: 'rw' }));
    console.warn('[short-close × modulePermissions]', { denied, allowed });
    expect(denied).toBe(false);
    expect(allowed).toBe(true);
  });

  it('transformBackendUser (后端格式) 把 modulePermissions 带进 factoryUser, 非法格丢弃', () => {
    const user = transformBackendUser({
      id: 6001,
      username: 'f006_sales_mgr',
      role: 'sales_manager',
      factoryId: 'F006',
      permissions: ['sales:*'],
      modulePermissions: { sales: 'rw', warehouse: '-', hr: 'bogus' },
    }) as FactoryUser;
    expect(user.factoryUser.modulePermissions).toEqual({ sales: 'rw', warehouse: '-' });
    expect(hasModulePermission(user as User, 'warehouse', 'read')).toBe(false);
    expect(hasModulePermission(user as User, 'sales', 'write')).toBe(true);

    const old = transformBackendUser({ id: 1, username: 'x', role: 'viewer', factoryId: 'F006', permissions: [] }) as FactoryUser;
    expect(old.factoryUser.modulePermissions).toBeUndefined();
  });

  it('transformBackendUser (已是前端格式) 原样保留 modulePermissions', () => {
    const input = factoryUser('viewer', { sales: 'rw' });
    const out = transformBackendUser(input) as FactoryUser;
    expect(out.factoryUser.modulePermissions).toEqual({ sales: 'rw' });
  });
});
