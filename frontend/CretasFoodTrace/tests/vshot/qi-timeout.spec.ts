/**
 * 质检员 App · 超时处置 视觉验收 (食品 DAG 缺口 14 App 端)。全打桩, 没有请求到达后端。
 *
 * 样本照 F006 真事件构造: 计划 PLAN-1794315754383-106830FC, 半成品 QA-V15-14-SEMI 0.1kg 于 19:15:51 被拦。
 * ⛔ 不在 prod 上做处置决定: 提交走桩, 断言提交体是「判不合格 + 结论」; 真机验收由 organizer 做。
 *
 * 运行: VSHOT_APP=http://localhost:3021 npx playwright test -c tests/vshot/vshot.config.ts qi-timeout
 */
import { test, expect, type Page } from '@playwright/test';
import { waitForApp, login } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const SHOTS = process.env.VSHOT_OUT ?? 'test-results/qi-timeout';
type Levels = Record<string, string>;
const INSPECTOR: Levels = { dashboard: 'r', production: 'r', quality: 'rw', work_report: 'r' };
const ok = (data: unknown, message = 'ok') => ({ code: 200, success: true, message, data });

const EVENT = {
  id: 41, targetType: 'SEMI_FINISHED', targetId: 'QA-WIP-2314', batchNumber: 'QA-V15-14-SEMI', processName: '冷却',
  limitMinutes: 60, startEvent: 'OUTPUT_POSTED', mixRule: 'EARLIEST',
  startAt: '2026-11-12T18:00:00', deadlineAt: '2026-11-12T19:00:00', blockedAt: '2026-11-12T19:15:51',
  expiredQuantity: 0.1, unit: 'kg', recheckValidMinutes: 120, status: 'OPEN',
  conclusion: null, decidedByName: null, decidedAt: null, validUntil: null,
};
const RECORD = {
  ...EVENT, id: 40, batchNumber: 'QA-V15-13-SEMI', status: 'RELEASED', conclusion: '复检感官合格；中心温度复测合格',
  decidedByName: 'QA 质检员', decidedAt: '2026-11-12T16:20:00', validUntil: '2026-11-12T18:20:00',
};

async function stub(page: Page, levels: Levels) {
  const writes: { path: string; body: unknown }[] = [];
  await page.route(/\/api\//, async (route) => {
    const req = route.request();
    const p = new URL(req.url()).pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (p.endsWith('/auth/unified-login')) {
      return json(ok({
        userId: 9001, username: 'qa_timeout_inspector', role: 'quality_inspector', userType: 'factory',
        token: 'qa-token', accessToken: 'qa-token', refreshToken: 'qa-refresh', expiresIn: 86400,
        factoryId: 'F006', factoryName: 'QA 六膳门食品科技', factoryType: 'FACTORY',
        permissions: [], modulePermissions: levels, profile: { name: 'QA 质检员' },
        createdAt: '2026-11-12T00:00:00', updatedAt: '2026-11-12T00:00:00',
      }));
    }
    if (p.endsWith('/process-time-limits/events/records')) return json(ok([EVENT, RECORD]));
    if (p.endsWith('/process-time-limits/events')) return json(ok(writes.length ? [] : [EVENT]));
    if (req.method() === 'POST' && /\/process-time-limits\/events\/41\/decision$/.test(p)) {
      writes.push({ path: p, body: req.postDataJSON() });
      return json(ok({ ...EVENT, status: 'REJECTED' }, '已判定不合格'));
    }
    if (req.method() !== 'GET') {
      writes.push({ path: `UNEXPECTED ${p}`, body: null });
      return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false, message: '验收桩: 不允许写' }) });
    }
    return json(ok([]));
  });
  return writes;
}

async function loginAs(page: Page, levels: Levels) {
  const writes = await stub(page, levels);
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'qa_timeout_inspector', 'stub');
  await page.getByTestId('qi-home-screen').waitFor({ timeout: 90_000 });
  return writes;
}

test('质检员: 首页进超时处置, 判不合格并填结论', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const writes = await loginAs(page, INSPECTOR);
  await expect(page.getByTestId('qi-home-timeout')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/01-home-timeout-entry.png` });

  await page.getByTestId('qi-home-timeout').click();
  await expect(page.getByTestId('qi-timeout-event-41')).toBeVisible();
  await expect(page.getByText('QA-V15-14-SEMI')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/02-timeout-list.png` });

  await page.getByTestId('qi-timeout-event-41').click();
  await expect(page.getByTestId('qi-timeout-blocked-reason')).toHaveText('请先选「复检放行」或「判不合格」');
  await page.screenshot({ path: `${SHOTS}/03-decision-no-default.png`, fullPage: true });

  await page.getByTestId('qi-timeout-decision-REJECTED').click();
  await expect(page.getByTestId('qi-timeout-consequence')).toHaveText('判不合格后，超时的 0.1 kg 从库存转出，不能再领用');
  await page.getByTestId('qi-timeout-preset-感官异常（异味/变色）').click();
  await page.screenshot({ path: `${SHOTS}/04-decision-rejected-filled.png`, fullPage: true });

  await page.getByTestId('qi-timeout-submit').click();
  await page.getByText('确认判不合格', { exact: true }).last().click(); // 第一个是页面上的提交按钮, 最后一个是确认框里的
  await expect.poll(() => writes.length).toBe(1);
  expect(writes[0].body).toEqual({ decision: 'REJECTED', conclusion: '感官异常（异味/变色）' });

  await page.getByText('好', { exact: true }).click();
  await page.getByTestId('qi-timeout-tab-records').click();
  await expect(page.getByTestId('qi-timeout-record-40')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/05-records.png` });
});

test('对照: 只读 quality ⇒ 看得到清单, 没有处置按钮', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const writes = await loginAs(page, { ...INSPECTOR, quality: 'r' });
  await page.getByTestId('qi-home-timeout').click();
  await expect(page.getByTestId('qi-timeout-readonly-hint')).toBeVisible();
  await page.getByTestId('qi-timeout-event-41').click();
  await expect(page.getByTestId('qi-timeout-decision-readonly')).toBeVisible();
  await expect(page.getByTestId('qi-timeout-decision-REJECTED')).toHaveCount(0);
  await page.screenshot({ path: `${SHOTS}/06-control-readonly.png` });
  expect(writes).toEqual([]);
});

test('对照: quality / production 都关掉 ⇒ 首页没有超时处置入口', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  await loginAs(page, { ...INSPECTOR, quality: '-', production: '-' });
  await expect(page.getByTestId('qi-home-new-inspection')).toBeVisible();
  await expect(page.getByTestId('qi-home-timeout')).toHaveCount(0);
  await page.screenshot({ path: `${SHOTS}/07-control-no-entry.png` });
});
