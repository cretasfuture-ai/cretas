'use strict';
const { runReadOnlyPageScenario } = require('./_shared');

function resolveCases(ctx) {
  const cases = ctx.factoryAccessCases;
  if (!Array.isArray(cases) || cases.length < 2 || cases.length > 4) throw new Error('Two explicit bounded factory acceptance cases are required');
  for (const item of cases) {
    if (!item || typeof item.factoryId !== 'string' || !/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,49}$/.test(item.factoryId)
        || typeof item.protectedRole !== 'boolean' || typeof item.username !== 'string' || !item.username.trim()) {
      throw new Error('Each acceptance case needs an exact factory and explicit protection expectation');
    }
  }
  if (new Set(cases.map(item => item.factoryId)).size < 2 || !cases.some(item => item.protectedRole) || !cases.some(item => !item.protectedRole)) {
    throw new Error('Acceptance requires two factories and both ordinary and protected employees');
  }
  return cases;
}

function selectEmployee(directory, item) {
  const matched = (directory || []).filter(user => item.username ? user.username === item.username : user.protectedRole === true);
  if (matched.length !== 1 || matched[0].active !== true || matched[0].protectedRole !== item.protectedRole) {
    throw new Error('Acceptance employee is absent, ambiguous, inactive, or has a different protection status');
  }
  return matched[0];
}

async function inspect(page, _body, ctx) {
  if (ctx.expectedFactoryId !== 'PLATFORM' || ctx.loginEvidence.role !== 'super_admin') {
    throw new Error('Factory access acceptance requires a real platform super-admin UI session');
  }
  const screenshots = [], verifiedCases = [];
  for (const item of resolveCases(ctx)) {
  const target = item.factoryId;
  await page.getByPlaceholder('按工厂名称或 ID 搜索').fill(target);
  const factory = page.locator('.factory-row').filter({ has: page.getByText(target, { exact: true }) });
  await factory.waitFor({ state: 'visible' });
  if (await factory.count() !== 1) throw new Error('Target factory selection is ambiguous');
  await factory.click();
  let directory;
  for (const label of ['功能开通', '岗位权限', '员工授权', '菜单显示']) {
    const responsePath = label === '员工授权' ? `/api/platform/factories/${target}/access/users` : `/api/platform/factories/${target}/access`;
    const response = ['菜单显示', '岗位权限'].includes(label) ? null : page.waitForResponse(r => new URL(r.url()).pathname === responsePath && r.request().method() === 'GET');
    await page.getByRole('tab', { name: label, exact: true }).click();
    if (response) {
      const result = await response;
      const body = await result.json();
      if (!result.ok() || body.success !== true) throw new Error(`${label} failed to read real server state`);
      if (label !== '员工授权' && body.data.factoryId !== target) throw new Error('Factory access tenant mismatch');
      if (label === '员工授权') directory = body.data.content;
    }
    if (label === '功能开通') await page.locator('.activation-row').first().waitFor();
    if (label === '岗位权限') await page.locator('.permission-table tbody tr').first().waitFor();
    if (label === '菜单显示') {
      await page.locator('.module-card').first().waitFor();
      if (!await page.locator('.factory-row.active').filter({ hasText: target }).isVisible()
          || await page.locator('.error-alert').isVisible()) throw new Error('Target factory menu settings did not load');
    }
    if (label === '员工授权') {
      if (item.username) {
        await page.getByPlaceholder('搜索用户名或姓名').fill(item.username);
        const searched = page.waitForResponse(r => new URL(r.url()).pathname === responsePath
          && new URL(r.url()).searchParams.get('keyword') === item.username);
        await page.locator('.employee-toolbar').getByRole('button', { name: '搜索', exact: true }).click();
        const result = await searched; const body = await result.json();
        if (!result.ok() || !body.success) throw new Error('Scoped employee search failed');
        directory = body.data.content;
      }
      const protectedUser = selectEmployee(directory, item);
      const employee = page.locator('.employee-row').filter({ hasText: protectedUser.username });
      if (await employee.count() !== 1) throw new Error('Protected employee selection is ambiguous');
      await employee.waitFor();
      const permissions = page.waitForResponse(r => new URL(r.url()).pathname === `/api/platform/factories/${target}/access/users/${protectedUser.id}/permissions`);
      await employee.click();
      const result = await permissions; const body = await result.json();
      if (!result.ok() || body.success !== true) throw new Error('Employee permission read failed');
      if (String(body.data.user.id) !== String(protectedUser.id) || body.data.user.username !== protectedUser.username || body.data.user.active !== true || body.data.user.protectedRole !== item.protectedRole) throw new Error('Employee response does not match the exact active account and protection expectation');
      if (!Array.isArray(body.data.modules) || !body.data.modules.length) throw new Error('Employee permission module inventory is empty');
      await page.locator('.employee-row.active').filter({ hasText: protectedUser.username }).waitFor();
      await page.locator('.employee-detail-heading strong').getByText(body.data.user.displayName || protectedUser.username, { exact: true }).waitFor();
      await page.waitForFunction(({ total, expectedDisabled }) => {
        const controls = [...document.querySelectorAll('.employee-permission-row .el-select input[role="combobox"]')];
        return controls.length === total && controls.filter(control => control.disabled === true).length === expectedDisabled;
      }, { total: body.data.modules.length, expectedDisabled: item.protectedRole ? body.data.modules.length : 0 });
      const controls = await page.locator('.employee-permission-row .el-select input[role="combobox"]').count();
      const disabled = await page.locator('.employee-permission-row .el-select input[role="combobox"]:disabled').count();
      if (!controls || disabled !== (item.protectedRole ? controls : 0)) throw new Error('Employee permission controls disagree with the accepted protection status');
      verifiedCases.push({ factoryId: target, employeeId: protectedUser.id, active: true, protectedRole: item.protectedRole, controls, disabled });
    }
    screenshots.push(await ctx.screenshot(`factory-access-${label}`));
  }
  }
  if (!await page.getByRole('button', { name: '进入只读查看', exact: true }).isVisible()) throw new Error('Existing factory view entry missing');
  const guard = ctx.mutationGuard.snapshot();
  if (guard.actualBusinessWrites || guard.blockedMutationAttempts.length) throw new Error('Factory access read-only acceptance attempted a mutation');
  return { verifiedCases, tabs: ['功能开通', '岗位权限', '员工授权', '菜单显示'], savesAttempted: 0, screenshots,
    assessment: { result: 'PASS', rootCauseClass: 'none' } };
}
module.exports = { id: 'platform-factory-access-readonly', defaultEnabled: false, inspect, resolveCases, selectEmployee,
  run: ctx => runReadOnlyPageScenario(ctx, { id: 'platform-factory-access-readonly', path: '/platform/factories', landmarks: ['工厂管理'], inspect }) };
