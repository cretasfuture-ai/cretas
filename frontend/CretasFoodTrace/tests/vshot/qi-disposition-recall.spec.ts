/**
 * 质检员 App · 不良品处置 / 批次召回 视觉验收 (纪律排查 B14 App 端)。
 *
 * <h3>为什么全打桩</h3>
 * 本机没有 f006_quality_insp 的密码 (TEST_QUALITY_INSP_PASS 未配置), 且这一轮只到 PR, prod 上没有这几页。
 * 所以整个 /api/** 都在浏览器里应答: 登录返回一个质检员, `modulePermissions` 与后端
 * PermissionServiceImpl 对 quality_inspector 的默认值一致 (quality=rw, 无 approval_quality_disposition);
 * 处置 / 召回接口返回 QA- 前缀的样本。⛔ 没有任何请求到达后端, 也就没有任何写入。
 *
 * <h3>判据</h3>
 *  - 首页两张卡片出现, 点进去是对应页面 (阳性: 屏上有批次号 QA-B14-…)
 *  - 等待审批页签有那条报废申请, 但没有「批准 / 驳回」(阴性), 有「由有审批权限的人处理」提示
 *  - 对照组: 同一账号把 approval_quality_disposition 配成 rw, 批准 / 驳回出现 (证明阴性不是因为页面没渲染)
 *  - 对照组: quality=- 时首页没有这两张卡片
 *
 * 运行: VSHOT_APP=http://localhost:3021 npx playwright test -c tests/vshot/vshot.config.ts qi-disposition-recall
 */
import { test, expect, type Page } from '@playwright/test';
import { waitForApp, login } from './vshot-lib';

const APP = process.env.VSHOT_APP ?? 'http://localhost:3021';
const SHOTS = process.env.VSHOT_OUT ?? 'test-results/qi-disposition-recall';

type Levels = Record<string, string>;
const INSPECTOR: Levels = { dashboard: 'r', production: 'r', quality: 'rw', work_report: 'r' };

/** 真机 11-12 的长批次号: 曾把右上角状态标签挤出屏。 */
const LONG = 'PB-PLAN-1794052204519-793732FC-24081';
// ⚠️ 标签被挤出屏只在 Android 上出现: RN-web 的 Text 默认会收缩换行, 修复前的页面在这里也不溢出
// (11-12 实测: 用 origin/main 的旧页面跑同一组 boundingBox, 标签右边界同样 370/412)。
// 所以这里只看长批次号能完整显示, 不拿浏览器布局当「不溢出」的证据; 布局由 Jest 结构断言 + 真机复验。

const ok = (data: unknown) => ({ code: 200, success: true, message: 'ok', data });

const CANDIDATES = [
  {
    id: 'QA-INSP-B14-1', productionBatchId: 9101, inspectorId: 9001, inspectorName: 'QA 质检员',
    inspectionDate: '2026-11-12', sampleSize: 20, passCount: 14, failCount: 6, passRate: 70,
    result: 'FAIL', notes: '外包装破损 6 袋', batchNumber: LONG, productName: 'QA-卤猪蹄 200g',
  },
  {
    id: 'QA-INSP-B14-2', productionBatchId: 9102, inspectorId: 9001, sampleSize: 10, passCount: 9, failCount: 1,
    passRate: 90, result: 'CONDITIONAL', inspectionDate: '2026-11-11', batchNumber: 'QA-B14-002', productName: 'QA-酱牛肉 250g',
  },
];
const PENDING = [{
  // 与真机一致: 后端 pending 不回 batchNumber, 只回 productionBatchId; App 按 entityId 从待处置列表对上
  id: 'QA-DISP-1', entityId: 'QA-INSP-B14-1', productionBatchId: 9101, batchNumber: null,
  decisionMade: 'SCRAP', reason: '微生物/安全指标超标', executorId: 9001, executorName: 'QA 质检员',
  approvalLevel: 'QUALITY_HEAD', createdAt: '2026-11-12T09:00:00',
}];
const ACTIONS = [
  { actionCode: 'RELEASE', actionName: '放行', description: '', requiresApproval: false },
  { actionCode: 'CONDITIONAL_RELEASE', actionName: '条件放行', description: '', requiresApproval: false },
  { actionCode: 'REWORK', actionName: '返工', description: '', requiresApproval: false },
  { actionCode: 'HOLD', actionName: '隔离', description: '', requiresApproval: false },
  { actionCode: 'SCRAP', actionName: '报废', description: '', requiresApproval: true, approvalLevel: 'QUALITY_HEAD' },
  { actionCode: 'SPECIAL_APPROVAL', actionName: '特批', description: '', requiresApproval: true, approvalLevel: 'FACTORY_MANAGER' },
];
const EVALUATION = {
  inspectionId: 'QA-INSP-B14-1', productionBatchId: 9101, recommendedAction: 'REWORK',
  recommendedActionDescription: '返工', requiresApproval: false, triggeredRuleName: 'QA 规则',
  confidence: 80, reason: '合格率 70% 低于放行线，建议返工后复检', alternativeActions: [],
  inspectionSummary: { passRate: 70 },
};
const RECALLS = [{
  id: 7001, eventCode: 'QA-RC-B14-1', batchId: 9101, batchNumber: LONG, productName: 'QA-卤猪蹄 200g',
  status: 'FROZEN', reason: '客户投诉：包装破损',
}];
const RECALL_DETAIL = {
  ...RECALLS[0], unit: '袋', remainingInventory: 120, canClose: false,
  blockers: [{ code: 'CUSTOMER_NOT_ACKED', message: '还有 1 家客户没有记录联系' }],
  shipments: [{
    shipmentId: 'QA-SHP-1', shipmentNumber: 'QA-DLV-B14-1', customerName: 'QA-客户甲', quantity: 50, unit: '袋',
    returnedQuantity: 10, pendingReturnQuantity: 5, remainingReturnQuantity: 35, acknowledged: false, returnOrders: [],
  }],
};

async function stubBackend(page: Page, levels: Levels) {
  const unhandled: string[] = [];
  await page.route(/\/api\//, async (route) => {
    const url = new URL(route.request().url());
    const p = url.pathname;
    const json = (body: unknown) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(body) });
    if (p.endsWith('/auth/unified-login')) {
      return json(ok({
        userId: 9001, username: 'qa_b14_inspector', role: 'quality_inspector', userType: 'factory',
        token: 'qa-token', accessToken: 'qa-token', refreshToken: 'qa-refresh', expiresIn: 86400,
        factoryId: 'F006', factoryName: 'QA 六膳门食品科技', factoryType: 'FACTORY',
        permissions: [], modulePermissions: levels,
        profile: { name: 'QA 质检员', department: 'quality' },
        createdAt: '2026-11-12T00:00:00', updatedAt: '2026-11-12T00:00:00',
      }));
    }
    if (p.includes('/processing/quality/inspections')) return json(ok({ content: CANDIDATES, totalElements: 2, totalPages: 1 }));
    if (p.endsWith('/quality-disposition/pending')) return json(ok(PENDING));
    if (p.endsWith('/quality-disposition/actions')) return json(ok(ACTIONS));
    if (p.endsWith('/quality-disposition/evaluate')) return json(ok(EVALUATION));
    if (/\/quality-recalls\/7001$/.test(p)) return json(ok(RECALL_DETAIL));
    if (p.endsWith('/quality-recalls')) return json(ok(RECALLS));
    if (route.request().method() !== 'GET') {
      unhandled.push(`${route.request().method()} ${p}`);
      return route.fulfill({ status: 403, contentType: 'application/json', body: JSON.stringify({ success: false, message: '验收桩: 不允许写' }) });
    }
    unhandled.push(`GET ${p}`);
    return json(ok([]));
  });
  return unhandled;
}

/** RN 栈返回 (浏览器的 history.back 不驱动 RN 栈)。 */
async function navBack(page: Page) {
  await page.evaluate(() => (globalThis as unknown as { __CRETAS_NAV__: { goBack: () => void } }).__CRETAS_NAV__.goBack());
  await page.waitForTimeout(800);
}

async function loginAs(page: Page, levels: Levels) {
  const unhandled = await stubBackend(page, levels);
  await page.goto(APP, { waitUntil: 'domcontentloaded' });
  await waitForApp(page);
  await login(page, 'qa_b14_inspector', 'stub');
  await page.getByTestId('qi-home-screen').waitFor({ timeout: 90_000 });
  return unhandled;
}

test('质检员: 两页能打开, 看不到报废审批按钮', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  const unhandled = await loginAs(page, INSPECTOR);

  await expect(page.getByTestId('qi-home-disposition')).toBeVisible();
  await expect(page.getByTestId('qi-home-recall')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/01-home-entries.png` });

  await page.getByTestId('qi-home-disposition').click();
  await expect(page.getByText(LONG).first()).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/02-disposition-candidates.png` });

  await page.getByTestId('qi-disposition-candidate-QA-INSP-B14-1').click();
  await expect(page.getByTestId('qi-disposition-suggestion')).toBeVisible();
  await expect(page.getByText('需要质量负责人审批').first()).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/03-disposition-execute.png`, fullPage: true });
  await navBack(page);

  await page.getByTestId('qi-disposition-tab-pending').click();
  await expect(page.getByTestId('qi-disposition-pending-QA-DISP-1')).toBeVisible();
  const pendingCard = page.getByTestId('qi-disposition-pending-QA-DISP-1');
  await expect(pendingCard.getByText(LONG)).toBeVisible();
  await expect(pendingCard.getByText('QA-卤猪蹄 200g')).toBeVisible();
  await expect(pendingCard.getByText('批次 #9101')).toHaveCount(0);
  await expect(page.getByTestId('qi-disposition-approve-QA-DISP-1')).toHaveCount(0);
  await expect(page.getByTestId('qi-disposition-reject-QA-DISP-1')).toHaveCount(0);
  await expect(page.getByTestId('qi-disposition-approve-hint')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/04-pending-no-approve-buttons.png` });

  await navBack(page);
  await page.getByTestId('qi-home-recall').click();
  await expect(page.getByTestId('qi-recall-row-7001')).toBeVisible();
  await expect(page.getByTestId('qi-recall-create')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/05-recall-list.png` });

  await page.getByTestId('qi-recall-row-7001').click();
  await expect(page.getByTestId('qi-recall-blockers')).toBeVisible();
  await page.getByTestId('qi-recall-return-open-QA-SHP-1').click();
  await page.getByTestId('qi-recall-return-qty-QA-SHP-1').fill('40');
  await expect(page.getByText('最多还能退 35')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/06-recall-detail-return-over-limit.png`, fullPage: true });

  console.log('未单独打桩的 GET:', [...new Set(unhandled)].join(' | '));
  expect(unhandled.filter((u) => !u.startsWith('GET'))).toEqual([]);
});

test('对照: 工厂把质检处置审批授给这个账号 ⇒ 批准 / 驳回出现', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  await loginAs(page, { ...INSPECTOR, approval_quality_disposition: 'rw' });
  await page.getByTestId('qi-home-disposition').click();
  await page.getByTestId('qi-disposition-tab-pending').click();
  await expect(page.getByTestId('qi-disposition-approve-QA-DISP-1')).toBeVisible();
  await expect(page.getByTestId('qi-disposition-reject-QA-DISP-1')).toBeVisible();
  await page.screenshot({ path: `${SHOTS}/07-control-approver-sees-buttons.png` });
});

test('对照: quality 权限被工厂关掉 ⇒ 首页没有这两个入口', async ({ page }) => {
  test.setTimeout(10 * 60_000);
  await loginAs(page, { ...INSPECTOR, quality: '-' });
  await expect(page.getByTestId('qi-home-new-inspection')).toBeVisible();
  await expect(page.getByTestId('qi-home-disposition')).toHaveCount(0);
  await expect(page.getByTestId('qi-home-recall')).toHaveCount(0);
  await page.screenshot({ path: `${SHOTS}/08-control-no-quality-no-entries.png` });
});
