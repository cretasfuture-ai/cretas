import { describe, expect, it } from 'vitest';
import officialRouter from '@/router';

/**
 * 纪律排查 B14: 不良品处置 / 批次召回的路由按「质检模块权限」放行, 不再叠岗位白名单。
 * 后端这两组接口的门槛是 quality:read / quality:read_write, 前端多一层 meta.roles 只会把
 * 有质检权限的岗位 (质检员、班组长……) 挡在外面。
 */
describe('质检处置 / 召回路由按模块权限放行', () => {
  const meta = (name: string) => {
    const route = officialRouter.getRoutes().find((r) => r.name === name);
    if (!route) throw new Error(`路由 ${name} 不存在`);
    return route.meta;
  };

  it.each(['QualityDisposition', 'QualityRecalls'])('%s: 只认 quality 模块, 没有 meta.roles', (name) => {
    expect(meta(name).module).toBe('quality');
    expect(meta(name).roles).toBeUndefined();
  });

  it('阴性对照: 同组里仍然是岗位白名单的路由没被这次改动误伤 (视觉质检训练仍限定岗位)', () => {
    const route = officialRouter.getRoutes().find((r) => Array.isArray(r.meta?.roles) && r.path.includes('vision-training'));
    expect(route?.meta?.roles).toEqual(['factory_super_admin', 'quality_manager']);
  });
});
