import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { menuConfig } from '../menuConfig';

function locateRouterFile(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    for (const candidate of [
      join(dir, 'web-admin/src/router/index.ts'),
      join(dir, 'src/router/index.ts'),
    ]) {
      try {
        readFileSync(candidate, 'utf8');
        return candidate;
      } catch {
        // Continue walking toward the repository root.
      }
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('找不到 router/index.ts');
}

function locateDispositionView(): string {
  let dir = resolve(process.cwd());
  for (let i = 0; i < 6; i += 1) {
    for (const candidate of [
      join(dir, 'web-admin/src/views/quality/disposition/list.vue'),
      join(dir, 'src/views/quality/disposition/list.vue'),
    ]) {
      try {
        readFileSync(candidate, 'utf8');
        return candidate;
      } catch {
        // Continue walking toward the repository root.
      }
    }
    const parent = dirname(dir);
    if (parent === dir) break;
    dir = parent;
  }
  throw new Error('找不到不良品处置页面');
}

describe('不良品处置页面可达性', () => {
  it('同时注册路由和按质检权限出现的菜单入口', () => {
    const routerSource = readFileSync(locateRouterFile(), 'utf8');
    const qualityMenu = menuConfig.find((item) => item.path === '/quality');
    const disposition = qualityMenu?.children?.find((item) => item.path === '/quality/disposition');

    expect(routerSource).toContain("path: 'disposition'");
    expect(routerSource).toContain("component: () => import('@/views/quality/disposition/list.vue')");
    expect(disposition?.title).toBe('不良品处置');
    // B14: 入口按质检模块权限出现, 不再写死审批岗位
    expect(disposition?.roles).toBeUndefined();
    expect(disposition?.module).toBe('quality');
  });

  it('如实说明审批后的库存桥接和返工边界', () => {
    const viewSource = readFileSync(locateDispositionView(), 'utf8');

    expect(viewSource).toContain('批准放行或条件放行后，系统会按本次质检关联批次更新库存状态');
    expect(viewSource).toContain('返工、待定和特批继续保持隔离');
    expect(viewSource).not.toContain('不会自动修改批次库存状态');
  });
});
