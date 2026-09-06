/**
 * 闸: RN 不再自带一份会漂的模块权限矩阵 (BUG-RN-PERMISSION-MATRIX-DRIFTS-FROM-L1-20260906)。
 *
 * 守两件事:
 *   1. `permissionHelper.ts` 里没有手写矩阵字面量 —— 唯一的本地来源是生成的 L1 快照 JSON,
 *      而快照本身格式合法、覆盖 RN 认识的每个工厂角色、行数与自报一致。
 *   2. 登录链把 `modulePermissions` 一路带到 `factoryUser` (源码钉子, 与 backendPermissionContract 同形)。
 *
 * ⚠️ 快照是「prod L1 在 exportedAt 那天的样子」, 只给旧会话兜底; 它会老, 老了不算这道闸红 ——
 *    真相在登录响应。要刷新跑 scripts/gen-permission-l1-snapshot.js。
 */
import fs from 'fs';
import path from 'path';
import { FACTORY_ROLES } from '../../types/auth';
import l1Snapshot from '../../utils/permissionMatrix.l1Snapshot.json';

const SRC = path.resolve(__dirname, '..', '..');
const read = (rel: string) => fs.readFileSync(path.join(SRC, rel), 'utf8');

const VALID = new Set(['rw', 'r', 'w', '-']);

describe('permission matrix L1 snapshot gate', () => {
  it('permissionHelper 没有手写矩阵字面量, 只 import 生成的快照', () => {
    const helper = read('utils/permissionHelper.ts');
    expect(helper).not.toMatch(/const PERMISSION_MATRIX\s*[:=]/);
    expect(helper).not.toMatch(/['"]read_write['"]/);   // 旧矩阵的值域; 新值域是 rw/r/w/-
    expect(helper).toContain("import l1Snapshot from './permissionMatrix.l1Snapshot.json'");
    expect(helper).toContain('factoryUser?.modulePermissions');
  });

  it('快照格式合法: 每格 rw/r/w/-, rows 自报一致, exportedAt 是日期', () => {
    const levels = l1Snapshot.levels as Record<string, Record<string, string>>;
    let cells = 0;
    const bad: string[] = [];
    for (const [role, mods] of Object.entries(levels)) {
      for (const [module, level] of Object.entries(mods)) {
        cells += 1;
        if (!VALID.has(level)) bad.push(`${role}.${module}=${level}`);
      }
    }
    console.warn(`[l1 snapshot] roles=${Object.keys(levels).length} cells=${cells} exportedAt=${l1Snapshot.exportedAt}`);
    expect(bad).toEqual([]);
    expect(cells).toBe(l1Snapshot.rows);
    expect(cells).toBeGreaterThan(0);
    expect(l1Snapshot.exportedAt).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(l1Snapshot.source).toContain('platform_role_permissions');
  });

  it('快照覆盖 RN 认识的每一个工厂角色 (漏一个 = 那个角色离线时全 deny 而没人知道)', () => {
    const levels = l1Snapshot.levels as Record<string, Record<string, string>>;
    const missing = Object.values(FACTORY_ROLES).filter((role) => !levels[role]);
    expect(missing).toEqual([]);
  });

  it('登录链把 modulePermissions 带到 factoryUser (authService / roleMapping / 类型)', () => {
    const authService = read('services/auth/authService.ts');
    const roleMapping = read('utils/roleMapping.ts');
    const authTypes = read('types/auth.ts');
    const apiTypes = read('types/apiResponses.ts');

    expect(authService).toContain('modulePermissions: parseModulePermissions(data.modulePermissions)');
    expect(roleMapping).toContain('modulePermissions: parseModulePermissions(backendUser.modulePermissions)');
    expect(authTypes).toContain('modulePermissions?: ModulePermissions;');
    expect(apiTypes).toMatch(/modulePermissions\?: Record<string, string> \| null;/);
    // 既有契约不动: permissions 仍是平铺数组
    expect(apiTypes).toContain('permissions: string[];');
  });
});
