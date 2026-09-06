/**
 * 权限矩阵视图 API (ARCH-PERMISSION-ADMIN-L1-EDITOR-20260906)。
 *
 * 后端 `PermissionMatrixController`:
 * - step 1: `GET /api/mobile/{factoryId}/permissions/matrix`
 *   (门禁 `@RequirePermission({"system:read","system:read_write"})`)
 * - step 2 (owner 2026-09-06 裁定): `PUT /api/mobile/{factoryId}/permissions/matrix/{role}/{module}`
 *   (门禁 `@RequirePermission("system:read_write")`), 只写 L1 一格, 同事务审计, 写后缓存失效。
 *   ⚠️ L1 是全平台表, 一次写入影响所有工厂。餐饮角色/模块的格后端 400 (Steve: 餐饮不管)。
 *   ⛔ 不走 `permissionApi.updatePlatformPermission` (`/api/platform/**` 只放平台管理员, permission_admin 走不到)。
 */
import request from './request';
import type { ApiResponse } from '@/types/api';

/** rw / r / w / -; 后端已把 legacy 的 read_write|read|write|none 归一化过 */
export type MatrixLevel = 'rw' | 'r' | 'w' | '-';

export interface MatrixRoleRef {
  code: string;
  displayName: string;
  /** platform / factory / restaurant / unknown */
  group: string;
  level: number;
}

/**
 * 一格分歧。
 *
 * - `kind === 'override'`: L1 表里**有行**且与硬编码兜底不同 ⇒ L1 赢, 兜底那格永远到不了。
 * - `kind === 'fallback'`: L1 表里**没有行** (`l1 === null`) 而兜底给了权限 ⇒ 兜底正在真的生效。
 *
 * ⚠️ `l1 === null` 与 `l1 === '-'` 是两件事: 前者「L1 没管」, 后者「L1 明确说不给」。
 * 下游后果相反, 所以渲染时也必须分开显示。
 */
export interface MatrixDiff {
  role: string;
  module: string;
  l1: MatrixLevel | null;
  hardcoded: MatrixLevel;
  kind: 'override' | 'fallback';
}

export interface MatrixModuleToggle {
  module: string;
  enabled: boolean;
}

export interface MatrixRoleOverride {
  role: string;
  module: string;
  level: MatrixLevel;
}

export interface PermissionMatrixView {
  factoryId: string;
  /** 后端派生的模块全集 (L1 ∪ 兜底 ∪ L2)。页面轴是它的**子集**, 见 moduleAxis.ts */
  modules: string[];
  roles: MatrixRoleRef[];
  /** role -> module -> level; **只含 L1 表里真实存在的行** (缺键 = L1 没管) */
  l1: Record<string, Record<string, MatrixLevel>>;
  /** role -> module -> level; 硬编码兜底矩阵 */
  hardcoded: Record<string, Record<string, MatrixLevel>>;
  diffs: MatrixDiff[];
  l2Overrides: MatrixModuleToggle[];
  l2RoleOverrides: MatrixRoleOverride[];
  modulesWithoutL1: string[];
}

export async function getPermissionMatrix(factoryId: string): Promise<PermissionMatrixView> {
  const res = await request.get<ApiResponse<PermissionMatrixView>>(
    `/${factoryId}/permissions/matrix`,
  );
  const data = (res as unknown as ApiResponse<PermissionMatrixView>).data;
  if (!data) {
    // ⛔ 不返回空壳: 空矩阵与「这个工厂真的没有任何权限行」长得一模一样,
    //    而后者在本系统里不可能发生 (兜底矩阵恒非空)。让它炸, 页面显示错误。
    throw new Error('权限矩阵接口返回了空 data');
  }
  return data;
}

/** step-2 写一格的响应: 后端**回读** L1 之后的值 (不是请求体) + 审计行 id。 */
export interface MatrixWriteResult {
  role: string;
  module: string;
  oldLevel: MatrixLevel | null;
  level: MatrixLevel;
  auditId: number;
}

/**
 * step-2: 写 L1 一格 (全平台生效)。
 * 调用方写完必须**重新 GET 矩阵**再渲染 (N9): 页面显示的是回读的 L1, 不是本地改的格。
 */
export async function writePermissionMatrixCell(
  factoryId: string,
  role: string,
  module: string,
  level: MatrixLevel,
  reason?: string,
): Promise<MatrixWriteResult> {
  const res = await request.put<ApiResponse<MatrixWriteResult>>(
    `/${factoryId}/permissions/matrix/${encodeURIComponent(role)}/${encodeURIComponent(module)}`,
    { level, reason: reason || null },
  );
  const data = (res as unknown as ApiResponse<MatrixWriteResult>).data;
  if (!data) throw new Error('写入权限格后接口返回了空 data');
  return data;
}
