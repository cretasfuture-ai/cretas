'use strict';

const http = require('node:http');
const crypto = require('node:crypto');
const { test, expect } = require('@playwright/test');
const { inspectScale, DEFAULT_OPTIONS } = require('../scenarios/smartbi-scale-readonly');

const target = DEFAULT_OPTIONS.targetProductId;
const rawProductId = index => `QA-SC28-SKU-${String(index + 1).padStart(3, '0')}`;
const opaqueProductId = raw => `product-${crypto.createHash('sha256').update(`smartbi-subject-v2:id:${raw}`).digest('hex').slice(0, 16)}`;
const subjects = Array.from({ length: 100 }, (_, index) => ({
  id: opaqueProductId(rawProductId(index)),
  label: `产品 ${index + 1}`,
}));
// The production detail store persists this exact internal cost proof before
// exposing a cost-bearing snapshot; it is not a public candidate field.
const contextCostGuards = new Map();

function answer(subjectId) {
  const detailContextId = `detail-${subjectId}`;
  const subjectIndex = subjects.findIndex((item) => item.id === subjectId);
  const unit = subjectId === target ? 'kg' : subjectIndex === 50 ? '盒' : '袋';
  const candidateSource = 'uploaded-production-inspection-fused · 显式上传生产/检验/成本';
  contextCostGuards.set(detailContextId, { productIds: [subjectId], unscoped: false });
  return {
    success: true,
    data: {
      factoryId: 'F006', source: 'upload', uploadId: 5278,
      jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 },
      analysisSubject: subjectId, period: { label: '全部历史', year: 2026, periodType: 'all', startMonth: 1, endMonth: 12 }, status: 'answered',
      recommendations: [{ id: `candidate-${subjectId}`, title: '生产检验联合', source: candidateSource, unit,
        analysis: { familyId: 'production.inspection_fused' }, detailContext: { id: detailContextId, expiresAt: '2099-12-31T00:00:00Z', drilldown: false },
        kpis: [{ value: subjectId === target ? 500 : 1, unit }, { value: subjectId === target ? '96%' : '1%', unit: '%' }, { value: subjectId === target ? '95%' : '1%', unit: '%' }, { value: subjectId === target ? '6,250' : '1', unit: '元' }],
        tableData: { rows: Array.from({ length: 5 }, (_, index) => ({ 产品ID: rawProductId(subjectIndex), 日期: `2026-0${index + 1}-01`, 单位: unit })) } }],
    },
  };
}

function pageHtml() {
  return `<!doctype html><html><body>
    <main data-testid="factory-analysis-chat"><h1>工厂 AI 问答分析</h1><div>当前授权图表目录</div>
      <form id="form"><input data-testid="factory-analysis-input"><button data-testid="factory-analysis-submit" type="submit">发送问题</button></form>
      <div data-testid="factory-analysis-subject"><input aria-label="产品筛选"></div><div id="options"></div><div id="turns"></div>
    </main><script>
      const subjects=${JSON.stringify(subjects)}; let current=null; let turns=[];
      const subjectInput=document.querySelector('[data-testid="factory-analysis-subject"] input');
      subjectInput.addEventListener('focus',()=>renderOptions(subjects));
      subjectInput.addEventListener('input',()=>renderOptions(subjects.filter(s=>s.label.includes(subjectInput.value))));
      function renderOptions(items){document.getElementById('options').innerHTML=items.map(s=>'<div role="option">'+s.label+'</div>').join('');document.querySelectorAll('[role="option"]').forEach(o=>o.onclick=()=>{current=subjects.find(s=>s.label===o.textContent);subjectInput.value=o.textContent;document.getElementById('options').innerHTML='<button id="retry">按当前产品重试</button>';document.getElementById('retry').onclick=retry;});}
      function renderAnswer(body){const recommendation=body.data.recommendations&&body.data.recommendations[0];if(!recommendation)return;document.getElementById('turns').innerHTML='<div data-testid="factory-analysis-answer">回答</div><div data-testid="factory-chart-card-'+recommendation.id+'"><canvas></canvas><button>查看原始明细</button></div>';document.querySelector('[data-testid^="factory-chart-card-"] button').onclick=details;}
      async function send(){const r=await fetch('/api/mobile/F006/smart-bi/factory-analysis',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:'upload',uploadId:5278,inspectionUploadId:5277,costUploadId:5279,...(current ? {analysisSubject:current.id} : {}),question:'关联生产、质检和成本一起看'})});const body=await r.json();renderAnswer(body);document.getElementById('options').innerHTML='';renderOptions(subjects);return body;}
      async function retry(){const id=current.id;const r=await fetch('/api/mobile/F006/smart-bi/factory-analysis',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({source:'upload',uploadId:5278,inspectionUploadId:5277,costUploadId:5279,analysisSubject:id,question:'关联生产、质检和成本一起看'})});const body=await r.json();renderAnswer(body);document.getElementById('options').innerHTML='';renderOptions(subjects);}
      async function details(){const r=await fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({context_id:'detail-'+current.id,page:1,page_size:50,filters:[]})});const body=await r.json();const drawer=document.createElement('div');drawer.className='el-drawer';drawer.textContent='原始明细 共 '+body.data.total+' 行';document.body.appendChild(drawer);}
      document.getElementById('form').onsubmit=e=>{e.preventDefault();send();};
    </script></body></html>`;
}

function legacyPageHtml() {
  return `<!doctype html><html><body>
    <div class="header-actions"><div class="el-select" id="switcher"><input value="trio5278.xlsx" readonly></div></div>
    <div class="el-select-dropdown" hidden><div class="el-select-dropdown__item">融合分析</div><div class="el-select-dropdown__item">trio5278.xlsx</div></div>
    <div class="sheet-tabs"><button class="el-tabs__item">生产</button></div>
    <button id="preview">查看原始数据</button><div id="dialog" class="el-dialog" hidden></div>
    <script>
      fetch('/api/mobile/F006/smart-bi/uploads?page=0&size=200').then(()=>{});
      document.querySelector('#switcher').onclick=()=>{document.querySelector('.el-select-dropdown').hidden=false;};
      document.querySelectorAll('.el-select-dropdown__item').forEach(item=>item.onclick=()=>{document.querySelector('.el-select-dropdown').hidden=true;});
      async function load(page){const r=await fetch('/api/mobile/F006/smart-bi/uploads/5278/data?page='+page+'&size=50');const body=await r.json();const d=document.querySelector('#dialog');d.hidden=false;d.innerHTML='<div class="el-table__body-wrapper"><table><tbody>'+body.data.data.map(row=>'<tr class="el-table__row">'+Object.values(row).map(value=>'<td>'+value+'</td>').join('')+'</tr>').join('')+'</tbody></table></div><div class="el-pagination"><button class="btn-next">下一页</button><span class="number is-active">'+(page+1)+'</span><span>'+body.data.total+'</span></div>';d.querySelector('.btn-next').onclick=()=>load(1);}
      document.querySelector('#preview').onclick=()=>load(0);
    </script></body></html>`;
}

test('scale scenario runs against an async online-shaped local fixture without API monkeypatching', async ({ page }) => {
  const server = http.createServer((req, res) => {
    if (req.method === 'GET' && !req.url.startsWith('/api/mobile/F006/smart-bi/uploads')) {
      res.setHeader('content-type', 'text/html; charset=utf-8');
      res.end(req.url.includes('tab=analysis') ? legacyPageHtml() : pageHtml());
      return;
    }
    let raw = ''; req.on('data', (chunk) => { raw += chunk; }); req.on('end', () => {
      res.setHeader('content-type', 'application/json');
      if (req.url.split('?')[0] === '/api/mobile/F006/smart-bi/uploads') {
        res.end(JSON.stringify({ success: true, data: { content: [
          { id: 5278, fileName: 'trio5278.xlsx', sheetName: '生产', rowCount: 500, createdAt: '2026-10-01T00:00:00Z' },
          { id: 5277, fileName: 'trio5278.xlsx', sheetName: '质检', rowCount: 500, createdAt: '2026-10-01T00:00:00Z' },
          { id: 5279, fileName: 'trio5278.xlsx', sheetName: '成本', rowCount: 500, createdAt: '2026-10-01T00:00:00Z' },
        ], totalElements: 3 } })); return;
      }
      const rawMatch = req.url.match(/^\/api\/mobile\/F006\/smart-bi\/uploads\/(\d+)\/data\?page=(\d+)&size=(\d+)$/);
      if (rawMatch) {
        const page = Number(rawMatch[2]); const size = Number(rawMatch[3]);
        res.end(JSON.stringify({ success: true, data: { headers: ['产品', '日期', '产量'], data: Array.from({ length: size }, (_, index) => ({ 产品: `SKU-${page * size + index}`, 日期: '2026-01-01', 产量: 1 })), total: 500, page, totalPages: 10 } })); return;
      }
      if (req.url === '/smartbi-api/api/smartbi/financial-dashboard/factory-details') {
        const request = JSON.parse(raw);
        const costGuard = contextCostGuards.get(request.context_id);
        if (!costGuard || !Array.isArray(costGuard.productIds) || typeof costGuard.unscoped !== 'boolean') {
          res.statusCode = 403;
          res.end(JSON.stringify({ success: false, message: '当前授权不能读取该制造成本详情' })); return;
        }
        res.end(JSON.stringify({ success: true, data: {
          factoryId: 'F006', candidateId: `candidate-${request.context_id.split('detail-')[1]}`,
          title: '生产检验联合', source: 'uploaded-production-inspection-fused · 显式上传生产/检验/成本', period: '全部历史', columns: ['产品ID', '日期', '单位'],
          total: 5, page: 1, pageSize: 50, hasMore: false,
          rows: Array.from({ length: 5 }, (_, index) => { const subjectId = request.context_id.split('detail-')[1]; const subjectIndex = subjects.findIndex((item) => item.id === subjectId); const unit = subjectId === target ? 'kg' : subjectIndex === 50 ? '盒' : '袋'; return { 产品ID: rawProductId(subjectIndex), 日期: `2026-0${index + 1}-01`, 单位: unit }; }),
          appliedFilters: [], availableFilters: [], snapshotAt: 1793404800,
        } })); return;
      }
      const payload = raw ? JSON.parse(raw) : {};
      if (payload.analysisSubject) { res.end(JSON.stringify(answer(payload.analysisSubject))); return; }
      res.end(JSON.stringify({ success: true, data: { factoryId: 'F006', source: 'upload', uploadId: 5278, jointUploads: { inspectionUploadId: 5277, costUploadId: 5279 }, status: 'clarification', subjects } }));
    });
  });
  await new Promise((resolve) => server.listen(0, resolve));
  try {
    const port = server.address().port;
    await page.goto(`http://127.0.0.1:${port}/smart-bi/analysis?tab=query&mode=factory&source=upload&upload_id=5278&inspection_upload_id=5277&cost_upload_id=5279`);
    const result = await inspectScale(page, { expectedFactoryId: 'F006', smartbiScale: { ...DEFAULT_OPTIONS },
      screenshot: async name => { const path = test.info().outputPath(`${name}.png`); await page.screenshot({ path }); return path; } });
    expect(result.subjectCount).toBe(100);
    expect(result.checks.noHundredCharts).toBe(true);
    expect(result.checks.answersMatchSelectedIds).toBe(true);
    expect(result.rawPagination.result).toBe('PASS');
    expect(result.screenshots).toHaveLength(2);
    expect(result.result).toBe('PASS');
  } finally {
    await new Promise((resolve) => server.close(resolve));
  }
});
