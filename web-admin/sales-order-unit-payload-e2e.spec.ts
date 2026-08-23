import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test, expect, type Locator, type Page } from '@playwright/test';
import { fetchLoginToken, injectAuthCookie } from './e2e-auth-helper';

/**
 * 销售订单「单位进请求体」真机 E2E。
 *
 * 🔴 <b>为什么这条必须真跑浏览器, 单测不够</b>:
 * 请求体里的 `unit` 是三样东西合起来产出的 —— `onProductSelect` 写进表单的内部键、
 * `unitOptions()` 给 el-select 的 value、以及 `canonicalSalesOrderItemPayload` 的出口。
 * 单测只能各测一段; 「用户在下拉里看到「盒」而线上发出去 `box`」这件事,
 * 只有把三段接起来跑一遍才看得见。
 *
 * 🔴 <b>观测点是请求体, 不是数据库</b>:
 * 后端写入口 (#2869) 现在会把 `box` 归一成「盒」再落库, 所以<b>库里的行两种情况下一模一样</b> ——
 * 拿 DB 当判据会得到「改了没改都一样」的假绿。真正能分辨的只有 <b>outgoing POST body</b>。
 * (实测基线: 2026-08-18 prod `public.sales_order_items` 已无英文码, 而
 *  `public.sales_delivery_items` 仍有 2 行 `box` —— 后端在兜, 前端还在发。)
 *
 * 运行 (headed, per .claude/rules/playwright-headed-mode.md):
 *   cd web-admin
 *   E2E_PASS=... npx playwright test --project sales-order-unit-payload
 */

const BASE_URL = process.env.E2E_BASE_URL || 'http://localhost:5173';
const API_BASE = process.env.E2E_API_BASE || 'http://139.196.165.140:8086/api/mobile';
const USER = process.env.E2E_USER || 'f006_sales_mgr';
const PASS = process.env.E2E_PASS || process.env.TEST_FACTORY_ADMIN_PASS || '';

/** F006 上单位是「盒」且配了箱规(1 箱 = N 盒)的成品 —— 单位下拉会同时给出 盒 / 箱。 */
const PRODUCT_KEYWORD = process.env.E2E_UNIT_PRODUCT || '原切澳洲牛小排';
/** 该品的箱规系数, 用来验 calcBox 两分支没被改坏。 */
const BOX_COEFF = Number(process.env.E2E_UNIT_BOX_COEFF || 42);

const ARTIFACT_DIR = resolve(dirname(fileURLToPath(import.meta.url)), 'test-results');
const ARTIFACT = resolve(ARTIFACT_DIR, 'sales-order-unit-payload.json');

const hasHan = (value: string) => /\p{Script=Han}/u.test(value);

type CapturedRequest = { url: string; body: Record<string, unknown> };

test.describe('销售订单 · 单位进请求体 (真机)', () => {
  test.describe.configure({ mode: 'serial' });

  let captured: CapturedRequest[] = [];

  test.beforeEach(async ({ context, page }) => {
    test.skip(!PASS, '未配置 E2E_PASS —— 凭证不进仓库, 本地 export 后再跑');
    const { token, loginData } = await fetchLoginToken(USER, PASS, API_BASE);
    await injectAuthCookie(context, page, token, loginData, BASE_URL);

    captured = [];
    page.on('request', (req) => {
      if (req.method() !== 'POST') return;
      if (!/\/sales\/orders(\?|$)/.test(req.url())) return;
      const body = req.postDataJSON();
      if (body) captured.push({ url: req.url(), body });
    });
  });

  /** 打开「新建销售订单」对话框, 返回对话框 locator。 */
  async function openCreateDialog(page: Page): Promise<Locator> {
    await page.goto(`${BASE_URL}/sales/orders`, { waitUntil: 'domcontentloaded' });
    const createButton = page.locator('button:has-text("新建")').first();
    await expect(createButton, '进不去销售订单列表 —— 登录态或路由有问题').toBeVisible({ timeout: 30000 });
    await createButton.click();
    const dialog = page.locator('.el-dialog:visible').filter({ hasText: '明细' }).first();
    await expect(dialog).toBeVisible({ timeout: 15000 });
    return dialog;
  }

  /** Element Plus 的下拉是 teleport 到 body 的, 按可见性取当前那一个。 */
  async function pickOption(page: Page, trigger: Locator, typed: string, optionText?: string) {
    await trigger.click();
    const dropdown = page.locator('.el-select-dropdown:visible').last();
    await expect(dropdown).toBeVisible({ timeout: 10000 });
    if (typed) {
      await trigger.locator('input').first().fill(typed);
      await page.waitForTimeout(400);
    }
    const option = optionText
      ? dropdown.locator('.el-select-dropdown__item', { hasText: optionText }).first()
      : dropdown.locator('.el-select-dropdown__item:not(.is-disabled)').first();
    await expect(option, `下拉里找不到「${optionText || typed || '任何'}」`).toBeVisible({ timeout: 10000 });
    await option.click();
    await page.waitForTimeout(300);
  }

  /** 读当前可见下拉里的全部选项文案, 然后按 Esc 关掉。 */
  async function readOptionLabels(page: Page, trigger: Locator): Promise<string[]> {
    await trigger.click();
    const dropdown = page.locator('.el-select-dropdown:visible').last();
    await expect(dropdown).toBeVisible({ timeout: 10000 });
    const labels = await dropdown.locator('.el-select-dropdown__item').allInnerTexts();
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);
    return labels.map((s) => s.trim()).filter(Boolean);
  }

  async function fillFirstItemRow(page: Page, dialog: Locator) {
    // 客户 (对话框里第一个 el-select)
    await pickOption(page, dialog.locator('.el-form-item:has-text("客户") .el-select').first(), '');

    const row = dialog.locator('.item-row').nth(1); // nth(0) 是表头
    await pickOption(page, row.locator('.sticky-product'), PRODUCT_KEYWORD, PRODUCT_KEYWORD);
    await row.locator('.sticky-quantity input').fill(String(BOX_COEFF * 2));
    await row.locator('.sticky-quantity input').blur();
    await page.waitForTimeout(400);
    await row.locator('.unit-price-wrap input').first().fill('12.5');
    await row.locator('.unit-price-wrap input').first().blur();
    await page.waitForTimeout(300);
    return row;
  }

  test('① 选品 → 提交: 请求体里的 unit 是用户看到的那个中文字', async ({ page }) => {
    const dialog = await openCreateDialog(page);
    const row = await fillFirstItemRow(page, dialog);

    // 用户眼睛看到的那个字 —— 后面要断言「发的就是它」。
    // ⚠️ 不能读 `input` 的 value: filterable 的 el-select 里那个 input 是**搜索框**, 平时是空的。
    // 选中项渲染在 `.el-select__selected-item` 里, 读不到就必须当场红 ——
    // 否则 shownUnit='' 会让下面那条「发的就是看到的」变成一句无从判断的空话。
    const shownUnit = (await row.locator('.sticky-unit').innerText()).trim();
    expect(shownUnit, '读不到界面上显示的单位 —— 「发的就是看到的」这条无从判断').not.toBe('');

    await dialog.locator('button:has-text("创建")').first().click();
    // 请求发出即可判定; 不等后端结果, 避免把「后端 4xx」误读成「前端发错了」
    await expect
      .poll(() => captured.length, { timeout: 30000, message: 'POST /sales/orders 一次都没发出去' })
      .toBeGreaterThan(0);

    const body = captured[0].body as { items?: Array<Record<string, unknown>> };
    const items = body.items || [];
    mkdirSync(ARTIFACT_DIR, { recursive: true });
    writeFileSync(ARTIFACT, JSON.stringify({
      shownUnit,
      url: captured[0].url,
      items: items.map((i) => ({ productTypeId: i.productTypeId, quantity: i.quantity, unit: i.unit })),
    }, null, 2), 'utf8');

    expect(items.length, '请求体里一条明细都没有 —— 上面的断言全部作废').toBeGreaterThan(0);
    const sentUnit = String(items[0].unit ?? '');

    // 🔴 正面: 发的就是用户看到的那个字, 且明确等于「盒」
    expect(sentUnit, `界面显示「${shownUnit}」而请求体发的是「${sentUnit}」`).toBe(shownUnit);
    expect(sentUnit).toBe('盒');
    // 🔴 阴性对照: 只写 not.toBe('box') 太容易恒真('BOX'/'' 都能过), 所以正反都钉
    expect(sentUnit).not.toBe('box');
    expect(hasHan(sentUnit), `请求体里的单位「${sentUnit}」不是中文`).toBe(true);
  });

  test('② unitOptions() 与包装规格匹配没被改坏', async ({ page }) => {
    const dialog = await openCreateDialog(page);
    const row = await fillFirstItemRow(page, dialog);

    // ── unitOptions(): 下拉给出 基本单位 + 箱规单位, 且一律中文标签 ──────────────
    const unitLabels = await readOptionLabels(page, row.locator('.sticky-unit'));
    expect(unitLabels.length, '单位下拉是空的 —— unitOptions() 挂了').toBeGreaterThan(0);
    expect(unitLabels).toContain('盒');
    expect(unitLabels).toContain('箱');
    for (const label of unitLabels) {
      expect(hasHan(label), `单位下拉里出现了英文码「${label}」`).toBe(true);
    }

    // ── calcBox 分支 A: 按基本单位下单 → 箱数 = 数量 / 箱规系数 ────────────────
    const boxInput = row.locator('input[placeholder="箱"]');
    await expect(boxInput).toBeVisible();
    expect(Number(await boxInput.inputValue()), '盒下单时箱数应为 数量/系数').toBeCloseTo(2, 2);

    // ── 切到「箱」: 箱规下拉出现, 且 calcBox 分支 B → 箱数 = 数量本身 ──────────
    await pickOption(page, row.locator('.sticky-unit'), '', '箱');
    await page.waitForTimeout(500);
    expect(Number(await boxInput.inputValue()), '箱下单时箱数应为数量本身').toBeCloseTo(BOX_COEFF * 2, 2);

    // 包装规格那一列: 要么给出「1箱=N盒」这种规格选项, 要么明确写「不涉及」——
    // 不许是空白 (空白就说明 packagingOptionsForUnit 的比较键被改坏了)
    const packagingCell = row.locator('.el-select, .el-text').nth(3);
    const packagingText = (await row.innerText()).trim();
    expect(/不涉及|1箱|=/.test(packagingText), `包装规格列既没有规格也没有「不涉及」: ${packagingText}`).toBe(true);
    void packagingCell;

    // ── 切回「盒」, 确认可逆 (onOrderUnitChange 清掉不匹配的箱规) ───────────────
    await pickOption(page, row.locator('.sticky-unit'), '', '盒');
    await page.waitForTimeout(500);
    expect(Number(await boxInput.inputValue()), '切回盒之后箱数应回到 数量/系数').toBeCloseTo(2, 2);
  });
});
