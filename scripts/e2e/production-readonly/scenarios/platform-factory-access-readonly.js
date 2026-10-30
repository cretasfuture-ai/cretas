'use strict';
const { runReadOnlyPageScenario } = require('./_shared');

async function inspect(page, _body, ctx) {
  if (ctx.expectedFactoryId !== 'PLATFORM' || ctx.loginEvidence.role !== 'super_admin') {
    throw new Error('Factory access acceptance requires a real platform super-admin UI session');
  }
  const target = ctx.factoryAccessFactoryId;
  if (typeof target !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,49}$/.test(target)) throw new Error('An exact target factory is required');
  await page.getByPlaceholder('按工厂名称或 ID 搜索').fill(target);
  const factory = page.locator('.factory-row').filter({ hasText: target });
  await factory.waitFor({ state: 'visible' });
  if (await factory.count() !== 1) throw new Error('Target factory selection is ambiguous');
  await factory.click();
  const screenshots = [];
  for (const label of ['功能开通', '岗位权限', '员工授权', '菜单显示']) {
    const responsePath = label === '员工授权' ? `/api/platform/factories/${target}/access/users` : `/api/platform/factories/${target}/access`;
    const response = ['菜单显示', '岗位权限'].includes(label) ? null : page.waitForResponse(r => new URL(r.url()).pathname === responsePath && r.request().method() === 'GET');
    await page.getByRole('tab', { name: label, exact: true }).click();
    if (response) {
      const result = await response;
      const body = await result.json();
      if (!result.ok() || body.success !== true) throw new Error(`${label} failed to read real server state`);
      if (label !== '员工授权' && body.data.factoryId !== target) throw new Error('Factory access tenant mismatch');
    }
    if (label === '功能开通') await page.locator('.activation-row').first().waitFor();
    if (label === '岗位权限') await page.locator('.permission-table tbody tr').first().waitFor();
    if (label === '员工授权') {
      const employee = page.locator('.employee-row').first();
      await employee.waitFor();
      const permissions = page.waitForResponse(r => new RegExp(`/api/platform/factories/${target}/access/users/[0-9]+/permissions$`).test(new URL(r.url()).pathname));
      await employee.click();
      const result = await permissions; const body = await result.json();
      if (!result.ok() || body.success !== true) throw new Error('Employee permission read failed');
      await page.locator('.employee-permission-row').first().waitFor();
      // This factory's full administrator must remain protected.
      if (body.data.user.protectedRole && await page.locator('.employee-permission-row .el-select:not(.is-disabled)').count()) {
        throw new Error('Protected employee permission control is editable');
      }
    }
    screenshots.push(await ctx.screenshot(`factory-access-${label}`));
  }
  if (!await page.getByRole('button', { name: '进入只读查看', exact: true }).isVisible()) throw new Error('Existing factory view entry missing');
  const guard = ctx.mutationGuard.snapshot();
  if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) throw new Error('Factory access read-only acceptance attempted a mutation');
  return { factoryId: target, tabs: ['功能开通', '岗位权限', '员工授权', '菜单显示'], savesAttempted: 0, screenshots,
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'platform-factory-access-readonly', defaultEnabled: false, inspect,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'platform-factory-access-readonly', path: '/platform/factories', landmarks: ['工厂管理'], inspect }) };
