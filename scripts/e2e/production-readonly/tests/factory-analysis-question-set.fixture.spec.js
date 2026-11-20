'use strict';

const fs = require('node:fs/promises');
const http = require('node:http');
const path = require('node:path');
const { execFile } = require('node:child_process');
const { promisify } = require('node:util');
const vm = require('node:vm');
const { test, expect } = require('@playwright/test');
const { runSuiteWithPage } = require('../core/run-suite');

test.use({ headless: false, viewport: { width: 1920, height: 1080 }, locale: 'zh-CN', video: 'on' });
const runCommand = promisify(execFile);
const id = 'factory-analysis-question-set-readonly';
const endpoint = '/api/mobile/LIUSHANMEN/smart-bi/factory-analysis';
const loginEndpoint = '/api/mobile/auth/unified-login';
const prompts = ['工厂本月报工失败样例？', '产品 C P2026100002  的良品数量？', '模拟超时？', '下一题仍可收到答复？'];
const answer = `六膳门成品入库\n${'单位为盒，按报工日汇总。'.repeat(24)}\n联络样例 fixture@example.com，尾句保留。`;
const shell = content => `<div class="app-layout"><header class="app-header">工厂问数</header>
  <aside class="app-sidebar"><div class="sidebar-logo"><span class="logo-title">测试工厂</span></div></aside>${content}</div>`;
const login = `<form><input placeholder="用户名"><input type="password"><button type="submit">登录</button></form>
  <script>document.querySelector('form').onsubmit = async event => {
    event.preventDefault();
    const [username, password] = [...event.target.querySelectorAll('input')].map(input => input.value);
    const response = await fetch('${loginEndpoint}', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ username, password }) });
    const body = await response.json();
    localStorage.setItem('cretas_user', JSON.stringify({ ...body.data.factoryUser, factoryId: 'LIUSHANMEN' }));
    history.pushState({}, '', '/dashboard');
    document.body.innerHTML = ${JSON.stringify(shell('首页驾驶舱'))};
  };</script>`;

function chat(mode) {
  return shell(`<section data-testid="factory-analysis-chat">
    <div data-testid="factory-analysis-source"><span>业务系统</span></div>
    <form><div data-testid="factory-analysis-input"><textarea aria-label="问题"></textarea></div><button data-testid="factory-analysis-submit">发送问题</button></form>
    <div data-testid="factory-analysis-turns"></div>
    <script>document.querySelector('form').onsubmit = async event => {
      event.preventDefault();
      const input = document.querySelector('textarea');
      const question = input.value.trim();
      const turns = document.querySelector('[data-testid="factory-analysis-turns"]');
      turns.innerHTML = '<article class="turn turn--assistant"><div data-testid="factory-analysis-answer">正在读取当前授权数据并规划图表…</div><div class="el-skeleton"></div></article>';
      input.disabled = true;
      const mode = ${JSON.stringify(mode)};
      if (mode === 'dialog') {
        document.querySelector('section').insertAdjacentHTML('beforeend', '<div role="alertdialog">确认写入业务数据？ <button type="button">确认</button></div>');
        document.querySelector('[role="alertdialog"] button').onclick = () => fetch('/fixture-write', { method: 'POST' });
      }
      if (mode === 'unexpected-post') {
        fetch('/smartbi-api/api/smartbi/financial-dashboard/factory-details', { method: 'POST' }).catch(() => {});
      }
      const response = await fetch('${endpoint}', { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({ question, source:'business', year:2026, periodType:'month_range', startMonth:11, endMonth:11, analysisSubject:null }) });
      const body = await response.json();
      document.querySelector('[data-testid="factory-analysis-answer"]').textContent = body.success ? body.data.answer : '工厂分析失败';
      document.querySelector('.el-skeleton').remove();
      input.disabled = false;
    };</script></section>`);
}

async function withFixture(callback, mode = 'mixed', factoryId = 'LIUSHANMEN') {
  const requests = [];
  const server = http.createServer(async (req, res) => {
    const url = new URL(req.url, 'http://127.0.0.1');
    const entry = { method: req.method, path: url.pathname };
    requests.push(entry);
    const send = (status, body) => {
      res.writeHead(status, { 'Content-Type': 'application/json' });
      res.end(JSON.stringify(body));
    };
    if (url.pathname === loginEndpoint) {
      send(200, { success: true, data: { factoryUser: { factoryId, username: 'fixture-admin', role: 'factory_super_admin', factoryName: '测试工厂' } } });
    } else if (url.pathname === endpoint) {
      let raw = '';
      for await (const chunk of req) raw += chunk;
      entry.payload = JSON.parse(raw);
      if (mode === 'all-fail' || entry.payload.question === prompts[0]) {
        send(500, { success: false, message: 'fixture failure' });
      } else if (entry.payload.question !== prompts[2]) {
        const cites = entry.payload.question === prompts[1];
        send(200, { success: true, data: {
          factoryId: 'LIUSHANMEN', source: 'business', sourceLabel: '本厂业务系统', question: entry.payload.question,
          period: { label: '2026 年 11 月', year: 2026, periodType: 'month_range', startMonth: 11, endMonth: 11 },
          status: 'answered', answer, selectedIds: [cites ? 'process.good@盒' : 'inventory'],
          recommendations: [{ id: cites ? 'process.good@盒' : 'inventory', source: cites ? 'blacklake-production · 黑湖生产报工 · 快照采集 2026-11-20' : 'inventory · 业务库存' }],
          catalog: [{ id: 'process.good@盒', sourceKey: 'blacklake-production' }, { id: 'inventory', sourceKey: 'inventory' }],
          facts: [], limitations: [], sourceErrors: [], subjects: [], followUpQuestions: [],
        } });
      }
    } else {
      const html = { '/login': login, '/dashboard': shell('首页驾驶舱'), '/smart-bi/analysis': chat(mode) }[url.pathname];
      res.writeHead(html ? 200 : url.pathname === '/favicon.ico' ? 204 : 404, { 'Content-Type': 'text/html; charset=utf-8' });
      res.end(html ? `<!doctype html><html lang="zh-CN"><body>${html}</body></html>` : '');
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try {
    return await callback(`http://127.0.0.1:${server.address().port}`, requests);
  } finally {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
}

function options(baseUrl, testInfo, factoryAnalysisQuestions, expectedFactoryId = 'LIUSHANMEN') {
  return {
    baseUrl, evidenceDir: testInfo.outputPath('evidence'), scenarios: [id], factoryAnalysisQuestions,
    expectedFactoryId, expectedUsername: 'fixture-admin', username: 'fixture-admin', password: 'fixture-only',
    factoryAnalysisQuestionTimeoutMs: 2_000,
  };
}

test('bundled MCP entry preserves HTTP failure, full answer, timeout and the next answer', async ({ page }, testInfo) => {
  await withFixture(async (baseUrl, requests) => {
    const bundled = await fs.readFile(path.resolve(__dirname, '../mcp-entry.js'), 'utf8');
    const entry = vm.runInNewContext(`(${bundled})`, { URL, TextEncoder, setTimeout, clearTimeout });
    page.__cretasReadonlyOptions = options(baseUrl, testInfo, prompts);
    const report = await entry(page);
    const [result] = report.scenarios;
    expect(report.tenant.factoryId).toBe('LIUSHANMEN');
    expect(result.result).toBe('PARTIAL_DEFECT');
    expect(result.questionSet).toEqual({ requested: 4, attempted: 4, answered: 2 });
    expect(report.exitCode).toBe(1);
    expect(report.actualBusinessWrites).toBe(0);
    expect(report.blockedMutationAttempts).toHaveLength(0);
    expect(report.readonlyPostRequests).toHaveLength(4);
    expect(requests.filter(item => item.path === loginEndpoint)).toHaveLength(1);
    expect(requests.filter(item => item.path === endpoint).map(item => item.payload.question)).toEqual(prompts);
    expect(requests.filter(item => item.path === endpoint).every(item => item.payload.source === 'business' && !item.payload.contextId)).toBe(true);
    const saved = JSON.parse(await fs.readFile(report.evidence.jsonPath, 'utf8'));
    const rows = saved.scenarios[0].pageEvidence;
    expect(rows).toEqual(result.pageEvidence);
    expect(rows.map(row => row.status)).toEqual(['failed', 'answered', 'failed', 'answered']);
    expect(rows.map(row => row.httpStatus)).toEqual([500, 200, null, 200]);
    expect(rows.map(row => row.failureReason)).toEqual(['analysis-http-error', null, 'timeout', null]);
    expect(rows.map(row => row.cites_blacklake)).toEqual([false, true, false, false]);
    expect(rows[1].sourceIdentifiers).toContain('blacklake-production');
    expect(rows[1].factoryAnalysisAnswer).toBe(answer.replace('fixture@example.com', '[REDACTED_EMAIL]'));
    expect(result.screenshots).toHaveLength(4);
    for (const row of rows) {
      expect(row.elapsedMs).toBeGreaterThan(0);
      expect((await fs.stat(row.screenshot)).size).toBeGreaterThan(0);
    }
  });
});

test('CLI exits 2 if both questions fail and writes their individual results', async ({}, testInfo) => {
  await withFixture(async (baseUrl, requests) => {
    const dir = testInfo.outputPath('evidence');
    await fs.mkdir(dir, { recursive: true });
    const file = path.join(dir, 'questions.json');
    await fs.writeFile(file, JSON.stringify(prompts.slice(0, 2)));
    try {
      await runCommand(process.execPath, [path.resolve(__dirname, '../cli-runner.mjs'), '--headed', '--base-url', baseUrl,
        '--scenario', id, '--evidence-dir', dir], { env: { ...process.env, E2E_FACTORY_ID: 'LIUSHANMEN',
        TEST_LIUSHANMEN_ADMIN_USER: 'fixture-admin', TEST_LIUSHANMEN_ADMIN_PASS: 'fixture-only', E2E_FACTORY_ANALYSIS_QUESTIONS_FILE: file } });
      throw new Error('CLI unexpectedly succeeded');
    } catch (error) {
      expect(error.code, error.stderr).toBe(2);
    }
    const saved = JSON.parse(await fs.readFile(path.join(dir, 'report.json'), 'utf8'));
    expect(saved.scenarios[0].pageEvidence.map(item => item.status)).toEqual(['failed', 'failed']);
    expect(saved.actualBusinessWrites).toBe(0);
    expect(requests.filter(item => item.path === endpoint)).toHaveLength(2);
  }, 'all-fail');
});

for (const mode of ['dialog', 'unexpected-post']) {
  test(`${mode} fails safety without sending a mutation or continuing the batch`, async ({ page }, testInfo) => {
    await withFixture(async (baseUrl, requests) => {
      const report = await runSuiteWithPage(page, options(baseUrl, testInfo, [prompts[1], prompts[3]]));
      expect(report.actualBusinessWrites).toBe(0);
      expect(report.blockedMutationAttempts).toHaveLength(1);
      expect(report.blockedMutationAttempts[0].method).toBe(mode === 'dialog' ? 'UI' : 'POST');
      expect(report.exitCode).toBe(1);
      expect(report.scenarios[0].pageEvidence.map(item => item.status)).toEqual(['failed']);
      expect(requests.filter(item => item.method === 'POST').map(item => item.path)).toEqual([loginEndpoint, endpoint]);
    }, mode);
  });
}

test('live login scope mismatch never reaches the question view', async ({ page }, testInfo) => {
  await withFixture(async (baseUrl, requests) => {
    const report = await runSuiteWithPage(page, options(baseUrl, testInfo, prompts));
    expect(report.scenarios.map(item => item.result)).toEqual(['TOOL_ERROR']);
    expect(JSON.stringify(report.scenarios)).toContain('Live login response did not prove factory LIUSHANMEN');
    expect(requests.some(item => item.path === '/smart-bi/analysis')).toBe(false);
  }, 'mixed', 'F006');
});
