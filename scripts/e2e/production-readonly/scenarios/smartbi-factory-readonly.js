'use strict';
const { runReadOnlyPageScenario } = require('./_shared');

module.exports = {
  id: 'smartbi-factory-readonly',
  defaultEnabled: false,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'smartbi-factory-readonly',
    path: '/smart-bi/analysis-hub?tab=factory',
    landmarks: ['工厂图表推荐', '数据源'],
    inspect: async (page, _body, context) => {
      if (context.expectedFactoryId !== 'F006') throw new Error('SmartBI acceptance is limited to F006');
      await page.getByTestId('factory-chart-ranking').waitFor({timeout:60_000});
      const pending = page.waitForResponse(r => r.url().split('?')[0].endsWith('/factory-recommendations'), {timeout:60_000});
      await page.getByTestId('factory-chart-refresh').click();
      const response = await pending;
      const body = await response.json();
      const data = body.data;
      await page.getByTestId('factory-chart-ranking').waitFor({timeout:60_000});
      await page.locator('.recommendation-card canvas').first().waitFor({timeout:30_000});
      const cards = await page.locator('.recommendation-card').count();
      const drawingErrors = await page.locator('.recommendation-card [role="alert"]').count();
      const scope = response.status()===200 && body.success===true && data?.factoryId==='F006' && data?.source==='business';
      const factsMatch = await page.locator('.recommendation-card').evaluateAll((elements, recommendations) => elements.every(el => {
        const id=el.getAttribute('data-testid').slice('factory-chart-card-'.length);
        const item=recommendations.find(candidate => candidate.id===id);
        const text=el.innerText.replace(/\s/g,'');
        return item && item.kpis.every(kpi => kpi.value===null || text.includes(String(kpi.value).replace(/\s/g,'')));
      }), data?.recommendations || []);
      return {
        scope, cards, drawingErrors, factsMatch,
        candidateCount:data?.recommendations?.length || 0,
        sourceErrorCount:data?.sourceErrors?.length || 0,
        rankingMode:data?.ranking?.mode,
        screenshots:[await context.screenshot('smartbi-factory-business')],
        assessment:{result:scope && cards>0 && factsMatch && drawingErrors===0 && data.sourceErrors.length===0 ? 'PASS':'CONFIRMED_DEFECT', rootCauseClass:scope && factsMatch && drawingErrors===0 && data.sourceErrors.length===0 ? 'none':'frontend'},
      };
    },
  }),
};
