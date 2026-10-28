'use strict';

const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { inspectCatalog } = require('../scenarios/smartbi-catalog-matrix-readonly');

const candidates = Array.from({ length: 8 }, (_, index) => {
  const familyId = `production.family-${index + 1}`;
  return {
    id: `${familyId}@P${index + 1}@kg`,
    title: index === 0 || index === 4 ? '重复标签' : `生产族 ${index + 1}`,
    source: 'business',
    unit: 'kg',
    analysis: { familyId, familyLabel: index === 0 || index === 4 ? '重复标签' : `生产族 ${index + 1}` },
    chart: { chartType: 'bar', echartsOption: { series: [{ data: [500 + index] }] } },
    kpis: [{ label: '产量', value: 500 + index, unit: 'kg' }],
  };
});
const JSON_HEADERS = { 'content-type': 'application/json' };

function recommendationBody(withContext = false, selectedId = null) {
  const listed = selectedId ? candidates.filter((item) => item.id === selectedId) : candidates;
  return {
    success: true,
    data: {
      factoryId: 'F006',
      source: 'business',
      uploadId: null,
      sourceLabel: '业务系统',
      period: { label: '全部历史', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 },
      ranking: { mode: 'rule', reason: 'fixture' },
      defaultSelection: candidates.slice(0, 4).map((item) => item.id),
      recommendations: listed.map((item) => withContext ? { ...item, detailContext: { id: `detail-${item.id}`, expiresAt: '2099-01-01T00:00:00.000Z' } } : item),
      unavailable: [],
      sourceErrors: [],
    },
  };
}

function pageHtml() {
  const familyRows = candidates.map((item, index) => `<div data-testid="factory-chart-family" data-family-id="${item.analysis.familyId}">${item.analysis.familyLabel} <input type="checkbox"${index < 4 ? ' checked' : ''}><div class="el-select">${item.unit}</div></div>`).join('');
  const cards = candidates.map((item) => `<div data-testid="factory-chart-card-${item.id}" data-candidate-id="${item.id}"><div class="factory-chart-canvas"><canvas></canvas></div><button>查看原始明细</button></div>`).join('');
  return `<!doctype html><html><body>
    <main><h2>工厂分析</h2><p>按分类浏览</p>
      <button data-testid="factory-chart-refresh">刷新</button>
      <section data-testid="factory-chart-guide">
        <div data-testid="factory-chart-mode"><button>问答引导</button><button>按分类浏览</button></div>
        <div data-testid="factory-chart-selection" style="display:block">
          ${familyRows}
        </div>
        <button data-testid="factory-chart-guide-generate">查看分析</button>
      </section>
      ${cards}
      <button>导出当前显示图表 Excel</button><button>导出当前显示图表 PDF</button><button>导出当前显示图表 PPT</button>
      <div class="el-drawer" style="display:none"><button>关闭</button><div class="details-table-wrap"></div></div>
    </main>
    <script>
      const allCandidates = ${JSON.stringify(candidates)};
      const jsonHeaders = {'content-type':'application/json'};
      const recPath = '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations';
      const detailPath = '/smartbi-api/api/smartbi/financial-dashboard/factory-details';
      const exportPaths = {'导出当前显示图表 Excel':'/smartbi-api/api/smartbi/financial-dashboard/export-excel','导出当前显示图表 PDF':'/smartbi-api/api/smartbi/financial-dashboard/export-pdf','导出当前显示图表 PPT':'/smartbi-api/api/smartbi/financial-dashboard/export-ppt'};
      document.querySelector('[data-testid="factory-chart-refresh"]').onclick = () => fetch(recPath, {method:'POST',headers:jsonHeaders,body:'{}'});
      for (const card of document.querySelectorAll('[data-testid^="factory-chart-card-"]')) card.querySelector('button').onclick = async () => {
        const candidateId = card.dataset.candidateId;
        const recommendation = await fetch(recPath, {method:'POST',headers:jsonHeaders,body:JSON.stringify({detail_candidate_ids:[candidateId]})});
        const body = await recommendation.json();
        const details = await fetch(detailPath, {method:'POST',headers:jsonHeaders,body:JSON.stringify({context_id:body.data.recommendations[0].detailContext.id})});
        const drawer = document.querySelector('.el-drawer'); drawer.style.display = 'block';
        drawer.querySelector('.details-table-wrap').textContent = (await details.json()).data.rows.length + ' 行';
      };
      document.addEventListener('keydown', event => { if (event.key === 'Escape') document.querySelector('.el-drawer').style.display = 'none'; });
      for (const button of document.querySelectorAll('button')) {
        if (!exportPaths[button.textContent]) continue;
        button.onclick = async () => {
          const familyIds = [...document.querySelectorAll('[data-testid="factory-chart-family"] input:checked')].map(input => input.closest('[data-family-id]').dataset.familyId);
          const ids = allCandidates.filter(item => familyIds.includes(item.analysis.familyId)).map(item => item.id);
          const response = await fetch(exportPaths[button.textContent], {method:'POST',headers:jsonHeaders,body:JSON.stringify({charts:ids.map(id => ({id})), selected_ids:ids})});
          const blob = await response.blob(); const link = document.createElement('a'); link.href = URL.createObjectURL(blob); link.download = button.textContent.includes('Excel') ? 'factory.xlsx' : button.textContent.includes('PDF') ? 'factory.pdf' : 'factory.pptx'; link.click();
        };
      }
    </script>
  </body></html>`;
}

test('catalog matrix fixture uses the real browse, detail, and export endpoint shapes', async ({ page }) => {
  const requests = [];
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture');
    if (request.method === 'GET' && url.pathname === '/fixture') {
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' }); response.end(pageHtml()); return;
    }
    let raw = '';
    request.on('data', chunk => { raw += chunk; });
    request.on('end', () => {
      let body = {}; try { body = JSON.parse(raw || '{}'); } catch {}
      requests.push({ path: url.pathname, body });
      if (url.pathname === '/smartbi-api/api/smartbi/financial-dashboard/factory-recommendations') {
        response.writeHead(200, JSON_HEADERS); response.end(JSON.stringify(recommendationBody(Boolean(body.detail_candidate_ids?.length), body.detail_candidate_ids?.[0] || null))); return;
      }
      if (url.pathname === '/smartbi-api/api/smartbi/financial-dashboard/factory-details') {
        const detailId = String(body.context_id || '').replace(/^detail-/u, '');
        response.writeHead(200, JSON_HEADERS); response.end(JSON.stringify({ success: true, data: { factoryId: 'F006', candidateId: detailId, source: 'business', period: '全部历史', columns: ['batch'], rows: [{ batch: 'B1' }], total: 1, page: 1, pageSize: 50 } })); return;
      }
      if (url.pathname.endsWith('/export-excel') || url.pathname.endsWith('/export-pdf') || url.pathname.endsWith('/export-ppt')) {
        response.writeHead(200, { 'content-type': 'application/octet-stream' }); response.end(Buffer.from('fixture-binary')); return;
      }
      response.writeHead(404, JSON_HEADERS); response.end(JSON.stringify({ success: false }));
    });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}/fixture`, { waitUntil: 'domcontentloaded' });
    const result = await inspectCatalog(page, '', {
      expectedFactoryId: 'F006',
      evidenceDir: '',
    });
    expect(result.assessment.result).toBe('PASS');
    expect(result.familyCount).toBe(8);
    expect(result.coveredFamilyCount).toBe(8);
    expect(result.failedFamilyCount).toBe(0);
    expect(result.remainingFamilyCount).toBe(0);
    expect(result.families.every((item) => item.rowTotal === 1 && item.exportCoverage.includes('excel'))).toBe(true);
    const exports = requests.filter(item => item.path.endsWith('/export-excel'));
    expect(exports).toHaveLength(2);
    expect(exports[0].body.selected_ids).toEqual(candidates.slice(0, 4).map((item) => item.id));
    expect(exports[1].body.selected_ids).toEqual(candidates.slice(4, 8).map((item) => item.id));
  } finally {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
  }
});
