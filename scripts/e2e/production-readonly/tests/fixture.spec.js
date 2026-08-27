'use strict';

const http = require('node:http');
const path = require('node:path');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');
const { installMutationGuard } = require('../core/mutation-guard');

function pageHtml(pathname) {
  if (pathname === '/login') {
    return `<!doctype html><html><body>
      <form><input type="text" placeholder="用户名"><input type="password" placeholder="密码"><button type="submit">登录</button></form>
      <script>
        document.querySelector('form').addEventListener('submit', async (event) => {
          event.preventDefault();
          const response = await fetch('/api/mobile/auth/unified-login', { method: 'POST', headers: {'content-type':'application/json'}, body: JSON.stringify({ username: 'f006_admin', password: 'fixture-only' }) });
          const body = await response.json();
          localStorage.setItem('cretas_user', JSON.stringify(body.data));
          location.href = '/dashboard';
        });
      </script>
    </body></html>`;
  }
  const content = pathname === '/quality/label-qc'
    ? '<main><h1>包装标签拍检</h1><p>AI 初筛只提供疑点，人工审核形成最终结论。</p><div class="el-table"></div><button>待人工审核</button><button>已审核整理</button><button>归档记录</button><button>人工审核</button></main>'
    : pathname === '/system/product-processes'
    ? '<main><h1>Workflow 工序管理</h1><p>原料 Cell 与原料 SKU 只读检查，投入产出数量关系，保存、发布和应用按钮均不会点击。</p><span data-testid="input-unit-chip">kg</span></main>'
    : pathname === '/production/bom'
      ? '<main><h1>BOM 配方版本</h1><p>系统历史出成率 参考单价 30 元/kg 总成本 12 元/袋</p><div class="el-table"><span>已生效</span></div><section data-testid="bom-version-lifecycle"><p>v2 当前生效，内容已锁定</p><p>生产计划继续使用当前版本。</p><button>克隆为新版本修改</button></section><div class="el-tabs__item">原料</div></main>'
      : pathname === '/sales/orders'
        ? `<main><h1>销售订单管理</h1>
          <div class="sales-lifecycle-tabs" role="tablist">
            ${['全部 2', '待处理 0', '审批中 0', '待履约 0', '履约中 2', '已完成 0', '已关闭 0'].map((label, index) => `<button role="tab" aria-selected="${index === 0}">${label}</button>`).join('')}
          </div>
          <div class="search-bar">
            <div class="el-select" data-filter="shipment">
              <div class="el-select__wrapper"><input role="combobox" aria-label="出库状态" readonly><span class="el-select__selected-item">全部出库状态</span></div>
            </div>
            <div class="el-select" data-filter="payment">
              <div class="el-select__wrapper"><input role="combobox" aria-label="收款状态" readonly><span class="el-select__selected-item">全部收款状态</span></div>
            </div>
            <div class="el-select-dropdown"><button role="option" data-filter="shipment">全部出库状态</button><button role="option" data-filter="shipment">部分出库</button></div>
            <div class="el-select-dropdown"><button role="option" data-filter="payment">全部收款状态</button><button role="option" data-filter="payment">部分收款</button></div>
          </div>
          <div class="el-table">
            <div class="el-table__header-wrapper"><table><thead><tr><th>订单编号</th><th>下一步</th></tr></thead></table></div>
            <div class="el-table__body-wrapper"><table><tbody><tr><td><button class="el-table__expand-icon">展开</button>SO-FIXTURE-001</td><td>部分出库</td><td>部分收款</td><td>创建发货单</td></tr></tbody></table></div>
            <div class="order-trace-panel" hidden><section class="trace-section"><h3 class="trace-section-title">订单明细</h3></section><section class="trace-section"><h3 class="trace-section-title">出库履约</h3></section><section class="trace-section"><h3 class="trace-section-title">收款进度</h3></section></div>
          </div>
          <script>
            async function refreshSalesOrders() {
              await fetch('/api/mobile/F006/sales/orders?page=1&size=200');
            }
            document.querySelectorAll('[role="tab"]').forEach((tab) => tab.addEventListener('click', () => {
              document.querySelectorAll('[role="tab"]').forEach((item) => item.setAttribute('aria-selected', 'false'));
              tab.setAttribute('aria-selected', 'true');
              void refreshSalesOrders();
            }));
            document.querySelector('.el-table__expand-icon').addEventListener('click', () => {
              document.querySelector('.order-trace-panel').hidden = false;
            });
            document.querySelectorAll('[role="option"]').forEach((option) => option.addEventListener('click', () => {
              const selected = document.querySelector('.el-select[data-filter="' + option.dataset.filter + '"] .el-select__selected-item');
              selected.textContent = option.textContent;
              void refreshSalesOrders();
            }));
          </script>
        </main>`
        : '<main><h1>工作台</h1><p>f006_admin 六膳门食品科技 F006</p></main>';
  return wrapWithBootSplash(pathname, content);
}

/**
 * 复刻 web-admin/index.html 的启动闪屏。
 *
 * 🔴 2026-08-27 实测: 这个夹具此前【直接吐渲染好的 HTML，从来没有闪屏】——
 *    而生产上恰恰是先渲染 `#app-loader`、SPA 挂载后才由 CSS
 *    `#app:not(:empty) + #app-loader { display: none; }` 把它藏掉。
 *    ⇒ 夹具缺的正是生产上会坏的那一环, 所以「16 个场景里 13 个 UNVERIFIED」
 *      这个仪器缺陷在本地一次都复现不出来。
 *
 * ⛔ `/login` 与 `/sales/orders` 保持原样: 它们的内联 <script> 必须在解析期执行,
 *    而 innerHTML 注入进去的 <script> 不会运行。这两条是【显式登记的例外】,
 *    不是漏改 —— 其余路径都会走一遍真实的「先闪屏、后挂载」。
 */
function wrapWithBootSplash(pathname, content, delayMs = 250) {
  if (pathname === '/login' || pathname === '/sales/orders') {
    return `<!doctype html><html><body>${content}</body></html>`;
  }
  return `<!doctype html><html><head><style>
    #app:not(:empty) + #app-loader { display: none; }
  </style></head><body>
    <div id="app"></div>
    <div id="app-loader"><div class="logo">白垩纪 AI Agent</div><div class="sub">正在加载应用…</div></div>
    <script>
      setTimeout(function () {
        document.getElementById('app').innerHTML = ${JSON.stringify(content)};
      }, ${delayMs});
    </script>
  </body></html>`;
}

test.describe('production read-only harness local fixture', () => {
  let server;
  let baseUrl;
  let businessWriteCount;
  let readonlyResolverCount;
  let salesOrderReadCount;
  let concurrentSalesOrderReads;
  let maxConcurrentSalesOrderReads;
  let loginStatus;

  test.beforeEach(async () => {
    businessWriteCount = 0;
    readonlyResolverCount = 0;
    salesOrderReadCount = 0;
    concurrentSalesOrderReads = 0;
    maxConcurrentSalesOrderReads = 0;
    loginStatus = 200;
    server = http.createServer((request, response) => {
      const url = new URL(request.url, 'http://fixture');
      if (request.method === 'POST' && url.pathname === '/api/mobile/auth/unified-login') {
        request.resume();
        request.on('end', () => {
          response.writeHead(loginStatus, { 'content-type': 'application/json' });
          response.end(JSON.stringify(loginStatus === 200
            ? { success: true, message: 'ok', data: { username: 'f006_admin', factoryId: 'F006', factoryName: '六膳门食品科技', role: 'factory_admin' } }
            : { success: false, message: 'fixture login rejected' }));
        });
        return;
      }
      if (request.method === 'POST' && url.pathname === '/api/mobile/F006/product-process-workflows/resolve-by-outputs') {
        readonlyResolverCount += 1;
        request.resume();
        request.on('end', () => {
          response.writeHead(200, { 'content-type': 'application/json' });
          response.end(JSON.stringify({
            success: true,
            data: {
              requestedProductTypeIds: ['P1'],
              resolutionMode: 'SINGLE_OUTPUT',
              candidates: [],
            },
          }));
        });
        return;
      }
      if (request.method === 'GET' && url.pathname === '/api/mobile/F006/sales/orders') {
        salesOrderReadCount += 1;
        concurrentSalesOrderReads += 1;
        maxConcurrentSalesOrderReads = Math.max(maxConcurrentSalesOrderReads, concurrentSalesOrderReads);
        const delayMs = salesOrderReadCount % 2 === 1 ? 120 : 20;
        setTimeout(() => {
          concurrentSalesOrderReads -= 1;
          response.writeHead(200, { 'content-type': 'application/json' });
          response.end(JSON.stringify({ success: true, data: { content: [], totalElements: 0 } }));
        }, delayMs);
        return;
      }
      if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(request.method)) businessWriteCount += 1;
      response.writeHead(200, { 'content-type': 'text/html; charset=utf-8' });
      response.end(pageHtml(url.pathname));
    });
    await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
    baseUrl = `http://127.0.0.1:${server.address().port}`;
  });

  test.afterEach(async () => {
    server.closeAllConnections?.();
    await new Promise((resolve, reject) => server.close((error) => error ? reject(error) : resolve()));
  });

  test('reuses one clean UI session and records zero business writes', async ({ page }) => {
    const evidenceDir = path.resolve('test-results', 'production-readonly-fixture');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation', 'bom-readonly', 'workflow-readonly', 'label-qc-readonly'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });
    expect(report.scenarios.map((result) => result.result)).toEqual(['PASS', 'PASS', 'PASS', 'PASS']);
    expect(report.authRequests).toHaveLength(1);
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.blockedMutationAttempts).toHaveLength(0);
    expect(businessWriteCount).toBe(0);
    const persisted = require('node:fs').readFileSync(path.join(evidenceDir, 'report.json'), 'utf8');
    expect(persisted).not.toContain('f006_admin');
    expect(persisted).not.toContain('fixture-only');
  });

  test('waits for the SPA to mount past the boot splash before sampling the page', async ({ page }) => {
    const evidenceDir = path.resolve('test-results', 'production-readonly-boot-splash');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation', 'bom-readonly', 'label-qc-readonly'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });

    // 🔴 这条测试守的是「取样发生在应用挂载【之后】」。
    //    没有这道等待时, goto 的 domcontentloaded 在闪屏阶段就满足, body 只有
    //    「白垩纪 AI Agent / 正在加载应用…」二十来个字符 ⇒ landmark 全不命中
    //    ⇒ 每条都掉进 UNVERIFIED。生产上实测 16 个场景里 13 个是这么来的。
    const navigated = report.scenarios.filter((result) => result.scenario !== 'tenant-isolation');
    expect(navigated.map((result) => result.result)).toEqual(['PASS', 'PASS']);

    for (const result of navigated) {
      const mountEvidence = result.pageEvidence.find((entry) => 'appMounted' in entry);
      // ⚠️ 「等了多久」必须每次都记下来 —— 否则下一个人分不清
      //    「应用没挂载」和「挂载了但页面缺东西」, 而那两件事的下一步动作相反。
      expect(mountEvidence, `${result.scenario} 缺少 appMounted 读数`).toBeTruthy();
      expect(mountEvidence.appMounted).toBe(true);
      // 夹具的闪屏延迟是 250ms; 若取样早于挂载, 这个数会明显偏小。
      expect(mountEvidence.appMountMs).toBeGreaterThanOrEqual(200);
      // 阳性对照: 正文必须是【挂载后】的内容, 不是闪屏那二十来个字符。
      const landmarkEvidence = result.pageEvidence.find((entry) => 'bodyTextLength' in entry);
      expect(landmarkEvidence.bodyTextLength).toBeGreaterThan(40);
      expect(landmarkEvidence.matchedLandmarks.length)
        .toBe(landmarkEvidence.expectedLandmarks.length);
    }
    expect(report.actualBusinessWrites).toBe(0);
  });

  test('verifies the sales order lifecycle workspace without business writes', async ({ page }) => {
    const evidenceDir = path.resolve('test-results', 'production-readonly-sales-order-fixture');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation', 'sales-order-lifecycle-readonly'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });
    expect(report.scenarios.map((result) => result.result)).toEqual(['PASS', 'PASS']);
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.blockedMutationAttempts).toHaveLength(0);
    expect(businessWriteCount).toBe(0);
    expect(salesOrderReadCount).toBe(6);
    expect(maxConcurrentSalesOrderReads).toBe(1);
  });

  test('aborts an unexpected mutation before the fixture server receives it', async ({ page }) => {
    const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'fixture-block-test' } });
    await page.goto(`${baseUrl}/dashboard`);
    const outcome = await page.evaluate(async () => {
      try { await fetch('/api/mobile/F006/boms/276', { method: 'DELETE' }); return 'sent'; }
      catch { return 'blocked'; }
    });
    expect(outcome).toBe('blocked');
    expect(guard.blockedMutationAttempts).toHaveLength(1);
    expect(guard.actualBusinessWrites).toBe(0);
    expect(businessWriteCount).toBe(0);
    await guard.dispose();
  });

  test('allows the exact query-only workflow resolver without counting a business write', async ({ page }) => {
    const guard = await installMutationGuard(page.context(), { scenarioRef: { value: 'fixture-resolver-test' } });
    await page.goto(`${baseUrl}/dashboard`);
    const status = await page.evaluate(async () => {
      const response = await fetch('/api/mobile/F006/product-process-workflows/resolve-by-outputs', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ productTypeIds: ['P1'] }),
      });
      return response.status;
    });
    expect(status).toBe(200);
    expect(guard.readonlyPostRequests).toHaveLength(1);
    expect(guard.actualBusinessWrites).toBe(0);
    expect(readonlyResolverCount).toBe(1);
    expect(businessWriteCount).toBe(0);
    await guard.dispose();
  });

  test('persists a sanitized TOOL_ERROR report when bootstrap fails', async ({ page }) => {
    loginStatus = 500;
    const evidenceDir = path.resolve('test-results', 'production-readonly-fixture-failure');
    const report = await runSuiteWithPage(page, {
      baseUrl,
      evidenceDir,
      scenarios: ['tenant-isolation'],
      username: 'f006_admin',
      password: 'fixture-only',
      expectedUsername: 'f006_admin',
      expectedFactoryId: 'F006',
      productionReadonly: true,
    });
    expect(report.scenarios).toHaveLength(1);
    expect(report.scenarios[0].scenario).toBe('ui-login');
    expect(report.scenarios[0].result).toBe('TOOL_ERROR');
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.authRequests).toHaveLength(1);
    const persisted = require('node:fs').readFileSync(path.join(evidenceDir, 'report.json'), 'utf8');
    expect(persisted).not.toContain('f006_admin');
    expect(persisted).not.toContain('fixture-only');
  });
});
