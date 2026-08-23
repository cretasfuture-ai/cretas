'use strict';

const { ROUTES } = require('../config/routes');
const { pathnameOf } = require('../core/url-utils');
const { runReadOnlyPageScenario } = require('./_shared');

const ERROR_MARKERS = [
  '403',
  '无权访问',
  '没有权限',
  '权限不足',
  '页面不存在',
  '加载失败',
  '请求失败',
  '服务器错误',
];

async function visibleMenuTitles(page, selector) {
  return page.locator(selector).evaluateAll((nodes) => nodes
    .filter((node) => {
      const style = window.getComputedStyle(node);
      const rect = node.getBoundingClientRect();
      return style.visibility !== 'hidden' && style.display !== 'none' && rect.width > 0 && rect.height > 0;
    })
    .map((node) => String(node.textContent || '').replace(/\s+/g, ' ').trim())
    .filter(Boolean));
}

async function inspectCurrentPage(page, title) {
  await page.waitForLoadState('networkidle', { timeout: 4_000 }).catch(() => {});
  const body = await page.locator('body').innerText().catch(() => '');
  const markers = ERROR_MARKERS.filter((marker) => body.includes(marker));
  return {
    title,
    path: pathnameOf(page.url()),
    bodyTextLength: body.trim().length,
    errorMarkers: markers,
    failed: body.trim().length < 40 || markers.length > 0,
  };
}

async function crawlVisibleRoleMenu(page, ctx) {
  const visitedTitles = new Set();
  const openedGroupTitles = new Set();
  const pages = [];

  for (let iteration = 0; iteration < 240; iteration += 1) {
    const leafTitles = await visibleMenuTitles(page, '.app-sidebar .el-menu-item');
    const nextLeafTitle = leafTitles.find((title) => !visitedTitles.has(title));
    if (nextLeafTitle) {
      const leaf = page.locator('.app-sidebar .el-menu-item:visible')
        .filter({ hasText: nextLeafTitle })
        .first();
      const beforeUrl = page.url();
      await leaf.click();
      visitedTitles.add(nextLeafTitle);
      await page.waitForFunction(
        ({ beforeUrl, title }) => title === '首页' || window.location.href !== beforeUrl,
        { beforeUrl, title: nextLeafTitle },
        { timeout: 3_000 },
      ).catch(() => {});
      const inspected = await inspectCurrentPage(page, nextLeafTitle);
      if (inspected.failed) {
        inspected.screenshot = await ctx.screenshot(`role-menu-failure-${pages.length + 1}`);
      }
      pages.push(inspected);
      continue;
    }

    const groupTitles = await visibleMenuTitles(page, '.app-sidebar .el-sub-menu__title');
    const nextGroupTitle = groupTitles.find((title) => !openedGroupTitles.has(title));
    if (nextGroupTitle) {
      const group = page.locator('.app-sidebar .el-sub-menu__title:visible')
        .filter({ hasText: nextGroupTitle })
        .first();
      await group.click();
      openedGroupTitles.add(nextGroupTitle);
      continue;
    }
    break;
  }

  return { pages, openedGroupTitles: [...openedGroupTitles] };
}

const scenario = {
  id: 'role-menu-census-readonly',
  path: ROUTES.dashboard,
  errorMarkers: ERROR_MARKERS,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'role-menu-census-readonly',
    path: ROUTES.dashboard,
    landmarks: [],
    timeoutMs: 60_000,
    inspect: async (page) => {
      const crawl = await crawlVisibleRoleMenu(page, ctx);
      const failedPages = crawl.pages.filter((item) => item.failed);
      const uniquePaths = [...new Set(crawl.pages.map((item) => item.path))];
      const pass = crawl.pages.length > 0
        && uniquePaths.length >= Math.min(2, crawl.pages.length)
        && failedPages.length === 0;
      return {
        authenticatedRole: ctx.loginEvidence?.role || null,
        visitedPageCount: crawl.pages.length,
        uniquePathCount: uniquePaths.length,
        openedGroupCount: crawl.openedGroupTitles.length,
        failedPageCount: failedPages.length,
        visitedPathSummary: crawl.pages.map((item) => `${item.title}:${item.path}`).join(' | '),
        failedPathSummary: failedPages.map((item) => `${item.title}:${item.path}:${item.errorMarkers.join(',')}`).join(' | '),
        assessment: pass
          ? { result: 'PASS', rootCauseClass: 'none' }
          : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};

module.exports = scenario;
