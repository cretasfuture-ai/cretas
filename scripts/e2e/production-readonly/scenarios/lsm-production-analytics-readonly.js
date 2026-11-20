'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

const HUB_PATH = '/production/analysis-query';
const COST_PATH = '/production-analytics/cost';
const LIMITATION = /^出成率与生产成本\s*·\s*黑湖数据不含投入量，无法计算$/u;

function validateFactory(factoryId) {
  if (factoryId !== 'LIUSHANMEN') throw new Error('Production analytics acceptance is limited to LIUSHANMEN');
}

async function visibleText(locator) {
  return (await locator.allInnerTexts()).map(text => text.trim()).filter(Boolean);
}

async function readSource(section) {
  const source = section.locator('.blacklake-source:visible');
  const [sourceLabel = ''] = await visibleText(source.locator('.blacklake-source__details strong:visible'));
  const [limitation = ''] = await visibleText(source.locator('.el-alert__title:visible'));
  const time = source.locator('.blacklake-source__details time:visible');
  const [snapshotDisplay = ''] = await visibleText(time);
  const snapshotCollectedAt = snapshotDisplay ? await time.first().getAttribute('datetime') : null;
  return {
    sourceLabel,
    snapshotCollectedAt,
    snapshotDisplay,
    limitation,
    sourceConfirmed: /^数据来源\s*黑湖$/u.test(sourceLabel),
    snapshotVisible: Boolean(snapshotDisplay) && Number.isFinite(Date.parse(snapshotCollectedAt)),
    yieldAndCostUnavailable: LIMITATION.test(limitation),
  };
}

function assessment(passed) {
  return { result: passed ? 'PASS' : 'UNVERIFIED', rootCauseClass: passed ? 'none' : 'data' };
}

async function inspectDataAnalysis(page, _body, ctx) {
  await ctx.network.settle();
  const hub = page.locator('.production-analysis-hub');
  const section = hub.getByRole('region', { name: '黑湖生产数据分析', exact: true });
  const tab = hub.getByRole('tab', { name: '生产数据分析', exact: true, selected: true });
  const selected = await tab.isVisible();
  const finalUrl = new URL(page.url());
  const source = await readSource(section);
  const daily = section.locator('.blacklake-card').filter({ has: page.getByRole('heading', { name: '每日报工趋势', exact: true }) });
  const byProduct = section.locator('.blacklake-card').filter({ has: page.getByRole('heading', { name: /^成品入库良品排行（.+）$/u }) });
  const checks = {
    selectedDataAnalysisTab: selected && finalUrl.pathname === HUB_PATH && finalUrl.searchParams.get('tab') === 'data-analysis',
    dailyGoodAndDefectChart: await daily.getByRole('img', { name: '每日良品与不良数量趋势图', exact: true }).isVisible(),
    processScope: await daily.getByRole('combobox', { name: '工序', exact: true }).isVisible(),
    productScope: await daily.getByRole('combobox', { name: '产品', exact: true }).isVisible(),
    dailyGoodQuantity: await daily.getByRole('columnheader', { name: /^良品（.+）$/u }).isVisible(),
    dailyDefectQuantity: await daily.getByRole('columnheader', { name: /^不良（.+）$/u }).isVisible(),
    dailyDefectRate: await daily.getByRole('columnheader', { name: '不良率', exact: true }).isVisible(),
    productGoodRanking: await byProduct.getByRole('columnheader', { name: /^良品（.+）$/u }).isVisible(),
  };
  const dailyRows = await daily.locator('.el-table__body tr:visible').count();
  const productRows = await byProduct.locator('.el-table__body tr:visible').count();
  return {
    ...source, checks, dailyRows, productRows,
    assessment: assessment(Object.values(checks).every(Boolean) && dailyRows > 0
      && source.sourceConfirmed && source.snapshotVisible && source.yieldAndCostUnavailable),
  };
}

async function inspectCost(page, _body, ctx) {
  await ctx.network.settle();
  const root = page.locator('.fca-root');
  await root.locator('.el-loading-mask').first().waitFor({ state: 'hidden', timeout: 15_000 });
  const titleVisible = await root.getByRole('heading', { name: '生产成本分析', exact: true }).isVisible();
  const source = await readSource(root);
  const errors = await visibleText(root.locator('.fca-error .el-alert__title:visible'));
  const emptyText = await visibleText(root.locator('.fca-empty-title:visible, .fca-empty-desc:visible, .fca-inline-empty .el-empty__description:visible'));
  const chartCount = await root.locator('.fca-chart-body:visible canvas:visible').count();
  const kpiCount = await root.locator('.fca-kpi-card:visible').count();
  const state = errors.length ? 'error'
    : source.sourceLabel ? 'limitation'
      : kpiCount || chartCount ? 'data'
        : emptyText.length ? 'empty' : 'unknown';
  return {
    state, ...source, errors, emptyText, chartCount, kpiCount,
    assessment: assessment(new URL(page.url()).pathname === COST_PATH && titleVisible && (
      state === 'data' || state === 'empty'
      || (state === 'limitation' && source.sourceConfirmed && source.snapshotVisible && source.yieldAndCostUnavailable)
    )),
  };
}

function scenario(id, path, inspect) {
  return {
    id, path, defaultEnabled: false, validateFactory, includeNonApiHttpErrors: true,
    async run(ctx) {
      validateFactory(ctx.expectedFactoryId);
      if (ctx.loginEvidence?.factoryId !== ctx.expectedFactoryId
          || !['factory_admin', 'factory_super_admin'].includes(ctx.loginEvidence.role)) {
        throw new Error('Production analytics acceptance requires a verified LIUSHANMEN administrator');
      }
      const result = await runReadOnlyPageScenario(ctx, { id, path, inspect, screenshot: true, screenshotOptions: { fullPage: true } });
      result.pageEvidence.push({
        consoleErrorCount: result.consoleErrors.length,
        http4xxCount: result.httpErrors.filter(({ status }) => status >= 400 && status < 500).length,
        http5xxCount: result.httpErrors.filter(({ status }) => status >= 500 && status < 600).length,
      });
      return result;
    },
  };
}

module.exports = {
  dataAnalysis: scenario('lsm-production-data-analysis-readonly', `${HUB_PATH}?tab=data-analysis`, inspectDataAnalysis),
  cost: scenario('lsm-production-cost-readonly', COST_PATH, inspectCost),
};
