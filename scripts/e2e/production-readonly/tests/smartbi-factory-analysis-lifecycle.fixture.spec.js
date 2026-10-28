'use strict';

const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { runFactoryAnalysisLifecycle } = require('../scenarios/smartbi-factory-analysis-readonly');

const candidate = {
  id: 'production-inspection-fused-P1-kg', title: '生产检验联合', source: 'upload', unit: 'kg',
  analysis: { familyId: 'production.inspection_fused' }, detailSnapshotKey: 'snapshot-fused-1',
  detailContext: { id: 'detail-old', expiresAt: '2000-01-01T00:00:00.000Z' },
  chart: { chartType: 'bar', echartsOption: { series: [{ data: [500] }] } },
  kpis: [{ label: '产量', value: 500 }],
};

function answer(contextId = 'ctx-initial', detail = candidate) {
  return {
    success: true,
    data: {
      factoryId: 'F006', source: 'upload', uploadId: 101,
      jointUploads: { inspectionUploadId: 102, costUploadId: 103 },
      period: { label: '全部历史', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 },
      analysisSubject: 'P1', status: 'answered', contextId,
      facts: [{ id: 'fact-1', label: '产量', value: 500, unit: 'kg', source: '上传报表' }],
      recommendations: [detail], followUpQuestions: ['再看质量'],
    },
  };
}

function pageHtml() {
  return `<!doctype html><html><body>
    <article class="turn turn--assistant"><div class="followups"><button class="el-button">再看质量</button></div>
      <div data-testid="factory-chart-card-${candidate.id}"><button data-testid="factory-analysis-detail-button">查看原始明细</button></div>
    </article>
    <div class="el-drawer old-turn" style="display:none"><button data-testid="factory-analysis-refresh-button">重新获取原始明细</button><div class="details-table-wrap">旧旧行</div></div>
    <div class="el-drawer" style="display:none"><button data-testid="factory-analysis-refresh-button">重新获取原始明细</button><div class="details-table-wrap">旧行</div></div>
    <script>
      let contextId = 'ctx-initial'; let round = 0;
      const button = document.querySelector('.followups button');
      button.onclick = async () => {
        const response = await fetch('/api/mobile/F006/smart-bi/factory-analysis', { method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({ question:'再看质量', contextId }) });
        const body = await response.json(); contextId = body.data.contextId; window.__rounds = (window.__rounds || 0) + 1;
      };
      document.querySelector('[data-testid="factory-chart-card-${candidate.id}"] button').onclick = () => {
        const drawer = document.querySelector('.el-drawer'); drawer.style.display = 'block';
        const refresh = drawer.querySelector('button'); refresh.onclick = async () => {
          const recommendation = await fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations', { method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({ factory_id:'F006', source:'upload', upload_id:101, inspection_upload_id:102, cost_upload_id:103, year:2026, period_type:'all', start_month:1, end_month:12, analysis_subject:'P1', include_optional:true, use_ai:false, detail_candidate_ids:['${candidate.id}'] }) });
          const recommendationBody = await recommendation.json();
          const details = await fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details', { method:'POST', headers:{'content-type':'application/json'}, body: JSON.stringify({ context_id: recommendationBody.data.recommendations[0].detailContext.id, page:1, page_size:50 }) });
          window.__refreshDetail = await details.json(); refresh.remove(); drawer.querySelector('.details-table-wrap').textContent = '恢复行';
        };
      };
    </script>
  </body></html>`;
}

test('lifecycle fixture follows the real recommendation refresh endpoint and response schema', async ({ page }) => {
  const requests = [];
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture');
    if (request.method === 'GET' && url.pathname === '/fixture') {
      response.writeHead(200, { 'content-type': 'text/html' });
      response.end(pageHtml());
      return;
    }
    let raw = '';
    request.on('data', (chunk) => { raw += chunk; });
    request.on('end', () => {
      let body = {}; try { body = JSON.parse(raw || '{}'); } catch {}
      requests.push({ path: url.pathname, body });
      if (url.pathname === '/api/mobile/F006/smart-bi/factory-analysis') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(answer(`ctx-round-${requests.filter((item) => item.path.includes('factory-analysis')).length}`)));
        return;
      }
      if (url.pathname === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(answer('ctx-refresh', { ...candidate, detailContext: { id: 'detail-new', expiresAt: '2099-01-01T00:00:00.000Z' } })));
        return;
      }
      if (url.pathname === '/smartbi-api/api/smartbi/financial-dashboard/factory-details') {
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', candidateId: candidate.id, source: 'upload', period: '全部历史', rows: [{ batch: 'B1', amount: 500 }], columns: ['batch', 'amount'], total: 1, page: 1, pageSize: 50 } }));
        return;
      }
      response.writeHead(404, { 'content-type': 'application/json' }); response.end(JSON.stringify({ success: false }));
    });
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}/fixture`, { waitUntil: 'domcontentloaded' });
    const result = await runFactoryAnalysisLifecycle(
      page,
      { factoryAnalysisLifecycle: { waitForExpiry: true }, screenshot: async () => Buffer.from('fixture'), evidenceDir: 'test-results/sc28-lifecycle-fixture' },
      answer('ctx-initial'),
      { source: 'upload', uploadId: 101, inspectionUploadId: 102, costUploadId: 103, analysisSubject: 'P1' },
      candidate,
      { success: true, data: { rows: [{ batch: 'B1', amount: 500 }] } },
    );
    expect(result.result).toBe('PASS');
    expect(result.lifecycleRoundsPassed).toBe(true);
    expect(result.lifecycleExpiryRecovered).toBe(true);
    expect(result.refreshVisible).toBe(true);
    expect(result.recommendationRefreshObserved).toBe(true);
    expect(result.phaseReason).toBe('expiry-refresh-recovered');
    expect(result.checkFailKeys).toEqual([]);
    expect(result.screenshots).toHaveLength(2);
    expect(result.idsCreatedCount).toBeGreaterThanOrEqual(2);
    const refresh = requests.find((item) => item.path === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations');
    expect(refresh.body.detail_candidate_ids).toEqual([candidate.id]);
    expect(requests.filter((item) => item.path === '/api/mobile/F006/smart-bi/factory-analysis')).toHaveLength(12);
    expect(requests.some((item) => item.path.includes('/factory-analysis') && item.path.includes('recommendations'))).toBe(false);
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  }
});

