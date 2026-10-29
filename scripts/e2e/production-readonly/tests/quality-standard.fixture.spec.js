'use strict';
const http = require('node:http');
const { test, expect } = require('@playwright/test');
const { inspectQualityStandard } = require('../scenarios/quality-standard-readonly');
const { installMutationGuard } = require('../core/mutation-guard');

for (const incorrectBatch of [false, true]) {
 for (const incorrectItem of [false, true]) {
  test(`quality standard opens and cancels; wrong batch=${incorrectBatch}, wrong item=${incorrectItem}`, async ({ page }) => {
    let submitted = 0;
    const server = http.createServer((req, res) => {
      if (req.method !== 'GET') { submitted += 1; res.writeHead(500).end(); return; }
      if (req.url.endsWith('/quality-form')) {
        res.setHeader('Content-Type', 'application/json');
        res.end(JSON.stringify({ success: true, data: { batchId: incorrectBatch ? 11004 : 11003,
          standardRevision: 'INTERNAL:REVISION:DO-NOT-DISPLAY', items: [{
            itemId: incorrectItem ? 'other-item' : 'a562ea27-8436-4944-8a66-c0afc80f103a',
            label: 'QA-S3-1027-成品中心温度', unit: '°C',
            effectiveIsRequired: true, effectiveMinValue: 0, effectiveMaxValue: 4 }] } }));
        return;
      }
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.end(`<button id="open">新建质检</button><div role="dialog" aria-label="新建质检记录" hidden>
        <h3>当前产品质检标准</h3><p>1. QA-S3-1027-成品中心温度（必检）</p><p>0 ~ 4 °C</p><button id="cancel">取消</button>
        <button id="submit">提交质检结果</button></div><script>
        document.querySelector('#open').onclick=async()=>{await fetch('/api/mobile/F006/processing/batches/11003/quality-form');document.querySelector('[role=dialog]').hidden=false;};
        document.querySelector('#cancel').onclick=()=>document.querySelector('[role=dialog]').hidden=true;
        document.querySelector('#submit').onclick=()=>fetch('/api/mobile/F006/processing/batches/11003/quality-inspections',{method:'POST'});
        </script>`);
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'quality-standard-readonly' } });
    try {
      await page.goto(`http://127.0.0.1:${server.address().port}/`);
      const evidence = await inspectQualityStandard(page);
      expect(evidence.assessment.result).toBe(incorrectBatch || incorrectItem ? 'UNVERIFIED' : 'PASS');
      expect(evidence.checks.exactBatch).toBe(!incorrectBatch);
      expect(evidence.checks.requiredTemperature).toBe(!incorrectItem);
      expect(evidence.checks.cancelClosed).toBe(true);
      expect(guard.snapshot().actualBusinessWrites).toBe(0);
      expect(guard.snapshot().blockedMutationAttempts).toHaveLength(0);
      expect(submitted).toBe(0);
    } finally { await guard.dispose(); await new Promise((resolve) => server.close(resolve)); }
  });
 }
}
