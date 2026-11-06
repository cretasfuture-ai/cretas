'use strict';

const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { inspectMaterialRequisitionClosed } = require('../scenarios/material-requisition-closed-readonly');
const { installMutationGuard } = require('../core/mutation-guard');

const OPTION = {
  id: 'mr-owned-closed-1',
  requisitionNo: 'MR-QA-CLOSED-1',
  expectedStatus: 'CLOSED',
  expectedItems: [
    { materialTypeId: 'raw-owned', issuedQty: 1, returnedQty: 1, consumedQty: 0, wastageQty: 0 },
    { materialTypeId: 'pack-owned', issuedQty: 1, returnedQty: 1, consumedQty: 0, wastageQty: 0 },
  ],
};

function detailFor(caseConfig) {
  return {
    id: caseConfig.responseId || OPTION.id,
    factoryId: caseConfig.factoryId || 'F006',
    requisitionNo: OPTION.requisitionNo,
    status: caseConfig.status || 'CLOSED',
    items: (caseConfig.items || OPTION.expectedItems).map((item) => ({ ...item })),
    ...(caseConfig.uiItems ? { uiItems: caseConfig.uiItems.map((item) => ({ ...item })) } : {}),
  };
}

async function withFixture(page, caseConfig, run) {
  let attemptedWrites = 0;
  const server = http.createServer((req, res) => {
    if (req.method !== 'GET') {
      attemptedWrites += 1;
      res.writeHead(405, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify({ success: false, message: 'fixture rejects writes' }));
      return;
    }
    res.setHeader('Content-Type', req.url.startsWith('/api/') ? 'application/json' : 'text/html; charset=utf-8');
    if (req.url.startsWith('/api/mobile/F006/material-requisitions/')) {
      res.end(JSON.stringify({ success: true, data: detailFor(caseConfig) }));
      return;
    }
    const listRows = caseConfig.duplicateRow
      ? `<tr><td>${OPTION.requisitionNo}</td><td>已关单</td><td><button>详情</button></td></tr><tr><td>${OPTION.requisitionNo}</td><td>已关单</td><td><button>详情</button></td></tr>`
      : `<tr><td>${OPTION.requisitionNo}</td><td>已关单</td><td><button>详情</button></td></tr>`;
    res.end(`<!doctype html><html><body>
      <main><h1>领料单</h1><div class="el-table__body-wrapper"><table><tbody>${listRows}</tbody></table></div>
      <div class="el-dialog" hidden></div></main>
      <script>
        document.querySelector('button').onclick = async () => {
          const response = await fetch('/api/mobile/F006/material-requisitions/${OPTION.id}');
          const payload = await response.json();
          const data = payload.data;
          const dialog = document.querySelector('.el-dialog');
          dialog.innerHTML = '<h2>领料单 '+data.requisitionNo+'</h2>'
            + '<div class="status">'+(data.status === 'CLOSED' ? '已关单' : data.status)+'</div>'
            + '<div class="detail-table"><div class="el-table__body-wrapper"><table><thead><tr><th>物料</th><th>发出</th><th>实用</th><th>损耗</th><th>退回</th></tr></thead><tbody>'
            + (data.uiItems || data.items).map(item => '<tr><td>'+item.materialTypeId+'</td><td>'+item.issuedQty+'</td><td>'+item.consumedQty+'</td><td>'+item.wastageQty+'</td><td>'+item.returnedQty+'</td></tr>').join('')
            + '</tbody></table></div></div>';
          dialog.hidden = false;
        };
      </script></body></html>`);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'material-requisition-closed-readonly' } });
  try {
    await page.goto(`http://127.0.0.1:${server.address().port}/production/material-flow?tab=requisitions`);
    return await run(guard, attemptedWrites);
  } finally {
    await guard.dispose();
    await new Promise((resolve) => server.close(resolve));
  }
}

for (const [name, caseConfig, expected] of [
  ['exact closed requisition', {}, 'PASS'],
  ['wrong document response', { responseId: 'mr-other-document' }, 'UNVERIFIED'],
  ['wrong status response', { status: 'PICKING' }, 'UNVERIFIED'],
  ['wrong issued quantity', { items: [{ ...OPTION.expectedItems[0], issuedQty: 0 }, OPTION.expectedItems[1]] }, 'UNVERIFIED'],
  ['wrong returned quantity', { items: [{ ...OPTION.expectedItems[0], returnedQty: 0 }, OPTION.expectedItems[1]] }, 'UNVERIFIED'],
  ['missing quantity', { items: [{ ...OPTION.expectedItems[0], issuedQty: null }, OPTION.expectedItems[1]] }, 'UNVERIFIED'],
  ['blank quantity', { items: [{ ...OPTION.expectedItems[0], wastageQty: ' ' }, OPTION.expectedItems[1]] }, 'UNVERIFIED'],
  ['wrong factory', { factoryId: 'OTHER' }, 'UNVERIFIED'],
  ['duplicate exact row', { duplicateRow: true }, 'UNVERIFIED'],
  ['UI swapped quantity columns', { uiItems: [{ ...OPTION.expectedItems[0], issuedQty: 0 }, OPTION.expectedItems[1]] }, 'UNVERIFIED'],
]) {
  test(`material requisition closed read-only fixture: ${name}`, async ({ page }) => {
    await withFixture(page, caseConfig, async (guard, attemptedWrites) => {
      const evidence = await inspectMaterialRequisitionClosed(page, OPTION);
      expect(evidence.assessment.result).toBe(expected);
      expect(guard.snapshot().actualBusinessWrites).toBe(0);
      expect(guard.snapshot().blockedMutationAttempts).toHaveLength(0);
      expect(attemptedWrites).toBe(0);
    });
  });
}
