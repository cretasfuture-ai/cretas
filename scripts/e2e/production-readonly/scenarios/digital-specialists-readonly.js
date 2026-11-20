'use strict';

const { resolveUrl } = require('../core/url-utils');
const { runReadOnlyPageScenario } = require('./_shared');

const LIMITED_FACTORY_ID = 'WHOLESALE_0000_001';
const ERROR_MARKERS = ['403', '无权访问', '没有权限', '权限不足', '加载失败', '请求失败', '服务器错误'];

const DIGITAL_SPECIALIST_CASES = Object.freeze([
  { id: 'sales', path: '/workdesk/sales-owner', title: '销售数字专员工作台', links: ['/sales/orders', '/sales/customers'], clickVerify: true },
  { id: 'purchase', path: '/workdesk/purchaser', title: '采购数字专员工作台', links: ['/procurement/orders', '/procurement/suppliers'], clickVerify: true },
  { id: 'inventory', path: '/workdesk/warehouse-keeper', title: '库存数字专员工作台', links: ['/warehouse/materials', '/warehouse/inventory-query', '/warehouse/shipments'], clickVerify: true },
  { id: 'finance', path: '/workdesk/finance-manager', title: '财务数字专员工作台', links: ['/finance/three-statements', '/finance/ar-ap', '/finance/accounting-period'], clickVerify: true, nativeFact: /实际期间：\d{4}-\d{2} 至 \d{4}-\d{2}[\s\S]*(?:营业收入|净利润|利润表|资产负债|现金流)/ },
  { id: 'quality-manager', path: '/workdesk/quality-manager', title: '质量追溯数字专员工作台', links: ['/quality/inspections', '/quality/recalls', '/warehouse/materials'] },
  { id: 'production', path: '/workdesk/production-manager', title: '生产数字专员工作台', links: ['/production/batches', '/production/plans', '/sales/orders'], clickVerify: true },
  { id: 'quality-chief', path: '/workdesk/quality-chief', title: '质检数字专员工作台', links: ['/quality/inspections', '/warehouse/materials'] },
  { id: 'ai-value', path: '/dashboard/ai-value', title: 'AI价值数字专员工作台', links: ['/smart-bi/ai-query', '/dashboard'] },
]);

function casesForFactory(factoryId) {
  return factoryId === LIMITED_FACTORY_ID
    ? DIGITAL_SPECIALIST_CASES.filter(({ id }) => ['sales', 'purchase', 'inventory'].includes(id))
    : [...DIGITAL_SPECIALIST_CASES];
}

async function settleEvidence(ctx) {
  await ctx.network.settle({ quietMs: 1_000, timeoutMs: 15_000 }).catch(() => {});
  await ctx.network.flush();
}

async function waitForSpecialistReady(page, item) {
  await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
  await page.locator('.digital-workdesk h1').filter({ hasText: item.title })
    .waitFor({ state: 'visible', timeout: 15_000 }).catch(() => {});
  await page.waitForFunction(() => {
    const chat = document.querySelector('[aria-label="数字专员对话"]');
    if (chat) {
      const loading = chat.querySelector('[data-testid="ai-chat-loading-skeleton"]');
      const answer = chat.querySelector('[data-testid="ai-chat-assistant-body"]');
      const error = chat.querySelector('[data-testid="ai-chat-error-banner"]');
      return !loading && (!!answer || !!error);
    }
    return !document.querySelector('.employee-status')
      || !document.querySelector('.employee-status')?.textContent?.includes('正在核对');
  }, undefined, { timeout: 30_000 }).catch(() => {});
}

async function inspectDigitalSpecialistPage(page, item, ctx, shouldNavigate) {
  await settleEvidence(ctx);
  if (shouldNavigate) {
    await page.goto(resolveUrl(item.path, ctx.baseUrl), { waitUntil: 'domcontentloaded', timeout: 45_000 });
  }
  await waitForSpecialistReady(page, item);
  const body = await page.locator('body').innerText().catch(() => '');
  const avatar = await page.locator('.digital-workdesk img[src*="/digital-employees/"]').first()
    .evaluate((image) => ({
      src: image.getAttribute('src') || '',
      complete: image.complete,
      naturalWidth: image.naturalWidth,
      naturalHeight: image.naturalHeight,
    }))
    .catch(() => ({ src: '', complete: false, naturalWidth: 0, naturalHeight: 0 }));
  const linkNodes = page.locator('nav[aria-label="现有业务入口"] a');
  const linkRecords = [];
  const links = [];
  for (let index = 0; index < await linkNodes.count(); index += 1) {
    const rawHref = await linkNodes.nth(index).getAttribute('href') || '';
    const href = rawHref.split('?')[0];
    if (href) {
      links.push(href);
      linkRecords.push({ index, href, rawHref });
    }
  }
  const errorMarkers = ERROR_MARKERS.filter((marker) => body.includes(marker));
  const loadingVisible = await page.locator('[data-testid="ai-chat-loading-skeleton"]').count() > 0;
  const assistantBodies = await page.locator('[data-testid="ai-chat-assistant-body"]').allInnerTexts().catch(() => []);
  const nativeFactVisible = !item.nativeFact || assistantBodies.some((text) => item.nativeFact.test(text));
  const linkRecord = linkRecords.find(({ href, rawHref }) => !rawHref.includes('?') && item.links.includes(href));
  const linkIndex = linkRecord?.index ?? -1;
  let businessLinkNavigation = { attempted: false, passed: !item.clickVerify, path: '' };
  if (item.clickVerify && linkIndex >= 0) {
    const expectedPath = item.links.find((path) => links.includes(path)) || item.links[0];
    businessLinkNavigation = { attempted: true, passed: false, path: expectedPath };
    await page.locator('nav[aria-label="现有业务入口"] a').nth(linkIndex).click();
    await page.waitForURL((url) => new URL(url).pathname === expectedPath, { timeout: 15_000 }).catch(() => {});
    await settleEvidence(ctx);
    const linkedBody = await page.locator('body').innerText().catch(() => '');
    const linkedErrors = ERROR_MARKERS.filter((marker) => linkedBody.includes(marker));
    businessLinkNavigation.passed = new URL(page.url()).pathname === expectedPath
      && await page.locator('main').count() > 0
      && linkedBody.trim().length > 40
      && linkedErrors.length === 0;
    await page.goto(resolveUrl(item.path, ctx.baseUrl), { waitUntil: 'domcontentloaded', timeout: 45_000 });
    await settleEvidence(ctx);
    await waitForSpecialistReady(page, item);
  }
  return {
    id: item.id,
    path: item.path,
    title: item.title,
    titleVisible: body.includes(item.title),
    avatar: { ...avatar, decoded: avatar.complete && avatar.naturalWidth > 0 && avatar.naturalHeight > 0 },
    businessLinks: links,
    hasBusinessLink: links.some((href) => item.links.includes(href)),
    loadingVisible,
    errorMarkers,
    nativeFactVisible,
    assistantBodies,
    businessLinkNavigation,
    bodyTextLength: body.trim().length,
  };
}

async function sendSalesQuestion(page) {
  await page.locator('.digital-workdesk h1').filter({ hasText: '销售数字专员工作台' })
    .waitFor({ state: 'visible', timeout: 15_000 });
  const input = page.getByTestId('ai-chat-input');
  await input.waitFor({ state: 'visible', timeout: 15_000 });
  const assistantBodies = page.locator('[data-testid="ai-chat-assistant-body"]');
  const assistantCountBefore = await assistantBodies.count();
  await input.fill('我是谁');
  await page.getByRole('button', { name: '发送', exact: true }).click();
  const userBubble = page.locator('[data-testid="ai-chat-user-bubble"]').filter({ hasText: '我是谁' }).last();
  await userBubble.waitFor({ state: 'visible', timeout: 15_000 });
  await page.waitForFunction((count) => document.querySelectorAll('[data-testid="ai-chat-assistant-body"]').length > count
    || !!document.querySelector('[data-testid="ai-chat-error-banner"]'), assistantCountBefore, { timeout: 30_000 });
  await page.locator('[data-testid="ai-chat-loading-skeleton"]').waitFor({ state: 'hidden', timeout: 30_000 }).catch(() => {});
  const assistantCountAfter = await assistantBodies.count();
  const assistant = assistantCountAfter > assistantCountBefore
    ? assistantBodies.nth(assistantCountAfter - 1) : null;
  const assistantText = assistant ? await assistant.innerText().catch(() => '') : '';
  const errorVisible = await page.locator('[data-testid="ai-chat-error-banner"]').count() > 0;
  const messageMetrics = await userBubble.evaluate((element) => {
    const style = window.getComputedStyle(element);
    const lineHeight = Number.parseFloat(style.lineHeight) || 24;
    const rect = element.getBoundingClientRect();
    return {
      width: Math.round(rect.width),
      height: Math.round(rect.height),
      clientHeight: element.clientHeight,
      scrollHeight: element.scrollHeight,
      lineCount: Math.max(1, Math.round(element.scrollHeight / lineHeight)),
    };
  });
  return {
    text: '我是谁',
    assistantText,
    assistantCountBefore,
    assistantCountAfter,
    hasNewAssistant: assistantCountAfter > assistantCountBefore,
    errorVisible,
    messageMetrics,
    shortSingleLine: messageMetrics.lineCount === 1 && messageMetrics.height <= 24,
    responseIsNotTerminalNotice: assistantCountAfter > assistantCountBefore
      && assistantText.trim().length > 0
      && !errorVisible
      && !assistantText.includes('对话已完成'),
  };
}

function salesQuestionPasses(result) {
  return !!result?.hasNewAssistant
    && !result.errorVisible
    && typeof result.assistantText === 'string'
    && result.assistantText.trim().length > 0
    && !!result.shortSingleLine
    && !!result.responseIsNotTerminalNotice;
}

const scenario = {
  id: 'digital-specialists-readonly',
  defaultEnabled: false,
  path: DIGITAL_SPECIALIST_CASES[0].path,
  landmarks: [DIGITAL_SPECIALIST_CASES[0].title],
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'digital-specialists-readonly',
    path: DIGITAL_SPECIALIST_CASES[0].path,
    landmarks: [DIGITAL_SPECIALIST_CASES[0].title],
    timeoutMs: 60_000,
    inspect: async (page, _body, scenarioContext) => {
      const cases = casesForFactory(scenarioContext.expectedFactoryId);
      const pages = [];
      for (let index = 0; index < cases.length; index += 1) {
        pages.push(await inspectDigitalSpecialistPage(page, cases[index], scenarioContext, index > 0));
      }
      const sales = cases.find((item) => item.id === 'sales');
      const salesEvidence = pages.find((item) => item.id === 'sales');
      let salesQuestion = null;
      if (sales && salesEvidence?.titleVisible && !salesEvidence.errorMarkers.length && !salesEvidence.loadingVisible) {
        await inspectDigitalSpecialistPage(page, sales, scenarioContext, true);
        salesQuestion = await sendSalesQuestion(page);
      }
      const failedPages = pages.filter((item) => !(item.titleVisible && item.avatar.decoded && item.hasBusinessLink
          && !item.loadingVisible && item.errorMarkers.length === 0 && item.nativeFactVisible
          && item.businessLinkNavigation.passed));
      const pass = pages.length === cases.length && failedPages.length === 0
        && salesQuestionPasses(salesQuestion);
      return {
        factoryId: scenarioContext.expectedFactoryId,
        routeCount: pages.length,
        expectedRouteCount: cases.length,
        pages,
        failedPageIds: failedPages.map((item) => item.id),
        // Keep scalar evidence readable through the shared bounded-depth sanitizer.
        pageChecks: pages.map((item) => JSON.stringify({ id: item.id, title: item.titleVisible,
          avatar: item.avatar.decoded, links: item.businessLinks, loading: item.loadingVisible,
          errors: item.errorMarkers, nativeFact: item.nativeFactVisible,
          navigation: item.businessLinkNavigation })),
        salesQuestion,
        logisticsClaimed: false,
        assessment: { result: pass ? 'PASS' : 'UNVERIFIED', rootCauseClass: pass ? 'none' : 'frontend' },
      };
    },
  }),
};

module.exports = scenario;
module.exports.DIGITAL_SPECIALIST_CASES = DIGITAL_SPECIALIST_CASES;
module.exports.casesForFactory = casesForFactory;
module.exports.inspectDigitalSpecialistPage = inspectDigitalSpecialistPage;
module.exports.salesQuestionPasses = salesQuestionPasses;
