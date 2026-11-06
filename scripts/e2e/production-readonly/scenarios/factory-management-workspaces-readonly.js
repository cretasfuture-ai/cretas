'use strict';

const { waitForAppMount } = require('../core/app-mount');
const { runReadOnlyPageScenario } = require('./_shared');

const id = 'factory-management-workspaces-readonly';
const ERROR_HEADINGS = /无权访问|权限不足|页面不存在|加载失败|请求失败|服务器错误/u;

// These are the first links in the current factory workspace catalog. The
// scenario records the actually rendered links, then follows only this
// already-visible, read-only GET target for each workspace.
const WORKSPACE_TARGETS = Object.freeze([
  { path: '/workspaces/overview', title: '经营总览', targetPath: '/dashboard', targetTitle: '首页驾驶舱', targetLandmark: '今日生产概览' },
  { path: '/workspaces/exceptions', title: '异常中心', targetPath: '/production/shortage-cases', targetTitle: '缺料异常', targetLandmark: '缺料异常' },
  { path: '/workspaces/plans-approvals', title: '方案与审批', targetPath: '/production/plans', targetTitle: '生产计划', targetLandmark: '生产计划管理' },
  { path: '/workspaces/trace-review', title: '追溯与复盘', targetPath: '/production/traceability', targetTitle: '批次追溯', targetLandmark: '溯源查询' },
]);

function visibleState(state) {
  const visible = state && typeof state === 'object' ? String(state.visible || '') : '';
  return visible === 'link' ? 'ready' : visible;
}

async function waitForBusinessLandmark(page, label, timeoutMs = 15000) {
  try {
    await page.locator('main.app-content').getByText(label, { exact: true })
      .filter({ visible: true }).first().waitFor({ state: 'visible', timeout: timeoutMs });
    return true;
  } catch {
    return false;
  }
}

function assertWorkspacePositive(snapshot, expected) {
  if (snapshot?.path !== expected.path) throw new Error(`Workspace path mismatch: ${snapshot?.path}`);
  if (snapshot?.heading !== expected.title) throw new Error(`Workspace heading mismatch: ${snapshot?.heading}`);
  if (visibleState(snapshot.state) !== 'ready') throw new Error(`Workspace did not reach ready state: ${visibleState(snapshot.state)}`);
  if (!Number.isInteger(snapshot.linkCount) || snapshot.linkCount < 1) throw new Error(`Workspace has no visible target: ${expected.title}`);
  if (!snapshot.linkTitles.includes(expected.targetTitle)) throw new Error(`Workspace target is not visible: ${expected.targetTitle}`);
  if (snapshot.targetPath !== expected.targetPath) throw new Error(`Workspace target navigated to ${snapshot.targetPath}, expected ${expected.targetPath}`);
  if (!snapshot.targetLandmarkFound) throw new Error(`Workspace target business landmark is missing: ${expected.targetLandmark}`);
  if (snapshot.targetErrorMarkers.length) throw new Error(`Workspace target rendered an error state: ${snapshot.targetErrorMarkers.join(',')}`);
  return true;
}

function invalidState(snapshot) {
  return ['loading', 'error', 'unavailable', 'denied', 'empty'].includes(visibleState(snapshot?.state))
    || !Number.isInteger(snapshot?.linkCount) || snapshot.linkCount < 1;
}

function hasSemanticTargetError(model) {
  return (model?.errorTestIds || []).some(value => /error|denied|not-found/i.test(String(value)))
    || (model?.headingTexts || []).some(value => ERROR_HEADINGS.test(String(value)));
}

function assertDeniedSnapshot(snapshot) {
  if (!snapshot.role || ['factory_super_admin', 'permission_admin', 'platform_admin'].includes(snapshot.role)) {
    throw new Error('Denied evidence requires a real non-workspace factory role');
  }
  if (snapshot.workspaceMenuVisible) throw new Error('Denied role can see the factory workspace menu');
  if (snapshot.routes.length !== WORKSPACE_TARGETS.length) throw new Error('Denied role did not check every workspace route');
  for (const route of snapshot.routes) {
    if (!route.denied) throw new Error(`Workspace route was not explicitly denied: ${route.path}`);
    if (!route.authenticatedSameFactory) throw new Error(`Denied route lost the authenticated factory session: ${route.path}`);
    if (route.finalPath !== '/403' && !(route.finalPath === route.path && route.explicitDenied)) {
      throw new Error(`Unexpected redirect is not permission proof: ${route.finalPath}`);
    }
    if (route.linkCount !== 0) throw new Error(`Denied route exposed business links: ${route.path}`);
  }
  return true;
}

function menuItem(page, title) {
  return page.locator('.app-sidebar .el-menu-item').filter({
    has: page.locator('.menu-title-copy').filter({ hasText: new RegExp(`^${title.replace(/[.*+?^${}()|[\\]\\]/g, '\\$&')}$`) }),
  }).first();
}

async function openWorkspace(page, expected) {
  const child = menuItem(page, expected.title);
  if (!await child.isVisible()) {
    const group = page.locator('.app-sidebar .el-sub-menu__title').filter({
      has: page.locator('.menu-title-copy').filter({ hasText: /^工厂管理工作区$/ }),
    }).first();
    await group.waitFor({ state: 'visible', timeout: 15000 });
    await group.click();
    await child.waitFor({ state: 'visible', timeout: 15000 });
  }
  await child.click();
  await page.waitForURL(url => url.pathname === expected.path, { timeout: 20000 });
  await waitForAppMount(page);
}

async function inspect(page, _body, ctx) {
  const snapshots = [];
  const screenshots = [];

  try {
    for (const expected of WORKSPACE_TARGETS) {
      if (page.url().split('?')[0].endsWith(expected.path)) {
        // The first workspace was opened by the shared scenario wrapper.
      } else {
        await openWorkspace(page, expected);
      }
      await page.getByRole('heading', { name: expected.title, exact: true }).waitFor({ state: 'visible', timeout: 15000 });
      const settled = await page.waitForFunction(() => {
        const visible = (testId) => {
          const node = document.querySelector(`[data-testid="${testId}"]`);
          if (!node) return false;
          const style = getComputedStyle(node);
          const rect = node.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
        };
        if (visible('workspace-loading')) return false;
        return ['workspace-link', 'workspace-error', 'workspace-unavailable', 'workspace-empty', 'workspace-denied']
          .some(visible);
      }, null, { timeout: 15000 }).then(() => true).catch(() => false);

      const state = await page.evaluate(() => {
        const pick = (testId) => {
          const node = document.querySelector(`[data-testid="${testId}"]`);
          if (!node) return false;
          const style = getComputedStyle(node);
          const rect = node.getBoundingClientRect();
          return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
        };
        const visible = ['loading', 'error', 'unavailable', 'denied', 'empty', 'link'].find(value => pick(`workspace-${value}`)) || 'unknown';
        return { visible: visible === 'link' ? 'ready' : visible };
      });
      const links = page.locator('[data-testid="workspace-link"]');
      const linkCount = await links.count();
      const linkTitles = await links.locator('strong').allTextContents();
      const snapshot = {
        path: new URL(page.url()).pathname,
        heading: await page.getByRole('heading', { name: expected.title, exact: true }).textContent(),
        state,
        linkCount,
        linkTitles: linkTitles.map(value => String(value).trim()),
        settled,
        targetPath: null,
        targetLandmarkFound: false,
        targetErrorMarkers: [],
      };
      screenshots.push(await ctx.screenshot(`${expected.path.slice(1).replaceAll('/', '-')}-workspace`));

      if (invalidState(snapshot)) {
        snapshot.assessment = snapshot.state.visible === 'loading' || !settled
          ? { result: 'UNVERIFIED', rootCauseClass: 'tool' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' };
        snapshots.push(snapshot);
        return { snapshots, screenshots, assessment: snapshot.assessment };
      }
      const firstLink = links.first();
      if ((await firstLink.locator('strong').textContent()).trim() !== expected.targetTitle) {
        throw new Error(`Unexpected first target for ${expected.title}`);
      }
      await firstLink.click();
      await page.waitForURL(url => url.pathname === expected.targetPath, { timeout: 20000 });
      await waitForAppMount(page);
      snapshot.targetPath = new URL(page.url()).pathname;
      snapshot.targetLandmarkFound = await waitForBusinessLandmark(page, expected.targetLandmark);
      const targetSemanticErrors = await page.evaluate(() => ({
        errorTestIds: [...document.querySelectorAll('.el-alert--error,[data-testid*="error" i],[data-testid="permission-denied"],[data-testid="not-found"]')]
          .filter(node => {
            const style = getComputedStyle(node);
            const rect = node.getBoundingClientRect();
            return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
          })
          .map(node => node.getAttribute('data-testid') || 'el-alert--error'),
        headingTexts: [...document.querySelectorAll('h1,h2,h3,[role="heading"]')]
          .filter(node => {
            const style = getComputedStyle(node);
            const rect = node.getBoundingClientRect();
            return style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0;
          })
          .map(node => String(node.textContent || '').trim())
          .filter(text => /无权访问|权限不足|页面不存在|加载失败|请求失败|服务器错误/u.test(text)),
      }));
      snapshot.targetErrorMarkers = hasSemanticTargetError(targetSemanticErrors)
        ? [...targetSemanticErrors.errorTestIds, 'error-heading'].filter((value, index, values) => values.indexOf(value) === index)
        : [];
      if (!snapshot.targetLandmarkFound) {
        snapshot.assessment = snapshot.targetErrorMarkers.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'TOOL_ERROR', rootCauseClass: 'tool' };
        snapshots.push(snapshot);
        screenshots.push(await ctx.screenshot(`${expected.path.slice(1).replaceAll('/', '-')}-target-missing`));
        return { snapshots, screenshots, assessment: snapshot.assessment };
      }
      snapshot.assessment = { result: 'PASS', rootCauseClass: 'none' };
      assertWorkspacePositive(snapshot, expected);
      snapshots.push(snapshot);
    }
    return {
      workspaceCount: snapshots.length,
      workspaceTitles: snapshots.map(snapshot => snapshot.heading),
      visibleTargetCount: snapshots.reduce((count, snapshot) => count + snapshot.linkCount, 0),
      targetPaths: snapshots.map(snapshot => snapshot.targetPath),
      targetLandmarks: WORKSPACE_TARGETS.map(expected => expected.targetLandmark),
      targetLandmarkResults: snapshots.map(snapshot => snapshot.targetLandmarkFound),
      workspaceStates: snapshots.map(snapshot => visibleState(snapshot.state)),
      snapshots,
      screenshots,
      assessment: { result: 'PASS', rootCauseClass: 'none' },
    };
  } catch (error) {
    return {
      snapshots,
      screenshots,
      error: String(error?.message || error),
      assessment: { result: 'TOOL_ERROR', rootCauseClass: 'tool' },
    };
  }
}

async function inspectDenied(page, _body, ctx) {
  const routes = [];
  try {
    if (!ctx.loginEvidence?.role || ctx.loginEvidence?.factoryId !== 'F006'
        || ['factory_super_admin', 'permission_admin', 'platform_admin'].includes(ctx.loginEvidence.role)) {
      return { error: 'Denied scenario requires a non-workspace factory role', assessment: { result: 'UNVERIFIED', rootCauseClass: 'tool' } };
    }
    const workspaceMenuVisible = await page.locator('.app-sidebar .menu-title-copy').filter({ hasText: /^工厂管理工作区$/ }).isVisible().catch(() => false);
    for (const expected of WORKSPACE_TARGETS) {
      await page.goto(`${ctx.baseUrl}${expected.path}`, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await waitForAppMount(page);
      await page.waitForLoadState('networkidle', { timeout: 6000 }).catch(() => {});
      const finalPath = new URL(page.url()).pathname;
      const linkCount = await page.locator('[data-testid="workspace-link"]').count();
      const explicitDenied = await page.locator('[data-testid="workspace-denied"]').isVisible().catch(() => false);
      const auth = await page.evaluate(async () => {
        const token = localStorage.getItem('cretas_access_token');
        const response = await fetch('/api/mobile/auth/me', {credentials: 'same-origin', headers: token ? {Authorization: `Bearer ${token}`} : {}});
        const body = await response.json();
        return {status: response.status, success: body.success === true, factoryId: body.data?.factoryUser?.factoryId || body.data?.factoryId || ''};
      });
      routes.push({
        path: expected.path,
        finalPath,
        linkCount,
        denied: finalPath === '/403' || (finalPath === expected.path && explicitDenied),
        explicitDenied,
        authStatus: auth.status,
        authFactoryId: auth.factoryId,
        authenticatedSameFactory: auth.status === 200 && auth.success && auth.factoryId === 'F006',
      });
    }
    const snapshot = { role: ctx.loginEvidence?.role || null, workspaceMenuVisible, routes };
    assertDeniedSnapshot(snapshot);
    return {
      ...snapshot,
      routePaths: routes.map(route => route.path),
      routeFinalPaths: routes.map(route => route.finalPath),
      routeDenials: routes.map(route => route.denied),
      routeAuthStatuses: routes.map(route => route.authStatus),
      routeFactoryIds: routes.map(route => route.authFactoryId),
      routeLinkCounts: routes.map(route => route.linkCount),
      assessment: { result: 'PASS', rootCauseClass: 'none' },
    };
  } catch (error) {
    return { routes, error: String(error?.message || error), assessment: { result: 'TOOL_ERROR', rootCauseClass: 'tool' } };
  }
}

module.exports = {
  id,
  defaultEnabled: false,
  path: WORKSPACE_TARGETS[0].path,
  WORKSPACE_TARGETS,
  visibleState,
  waitForBusinessLandmark,
  assertWorkspacePositive,
  assertDeniedSnapshot,
  hasSemanticTargetError,
  invalidState,
  inspect,
  denied: {
    id: 'factory-management-workspaces-denied-readonly',
    defaultEnabled: false,
    path: '/dashboard',
    inspect: inspectDenied,
    run: ctx => runReadOnlyPageScenario(ctx, {
      id: 'factory-management-workspaces-denied-readonly',
      path: '/dashboard',
      landmarks: [],
      screenshot: false,
      inspect: inspectDenied,
    }),
  },
  run: ctx => runReadOnlyPageScenario(ctx, {
    id,
    path: WORKSPACE_TARGETS[0].path,
    landmarks: [WORKSPACE_TARGETS[0].title],
    screenshot: false,
    inspect,
  }),
};
