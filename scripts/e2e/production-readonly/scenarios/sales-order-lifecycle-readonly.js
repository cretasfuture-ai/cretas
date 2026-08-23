'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');

const EXPECTED_LIFECYCLES = ['全部', '待处理', '审批中', '待履约', '履约中', '已完成', '已关闭'];

function normalizedTabLabel(value) {
  return String(value || '').replace(/\d+/g, '').replace(/\s+/g, '');
}

async function waitForTableSettled(page) {
  await page.locator('.el-loading-mask').waitFor({ state: 'hidden', timeout: 30_000 });
  await Promise.race([
    page.locator('.el-table__body-wrapper tbody tr').first().waitFor({ state: 'visible', timeout: 30_000 }),
    page.locator('.el-table__empty-block').first().waitFor({ state: 'visible', timeout: 30_000 }),
  ]);
}

async function selectReadOnlyDimension(page, label, optionLabel) {
  const control = page.getByRole('combobox', { name: label }).first();
  await control.waitFor({ state: 'visible' });
  const tagName = await control.evaluate((element) => element.tagName);
  if (tagName === 'SELECT') {
    await control.selectOption({ label: optionLabel });
    return control.locator('option:checked').innerText();
  }

  const wrapper = control
    .locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " el-select ")][1]')
    .locator('.el-select__wrapper');
  await wrapper.click();
  await page.getByRole('option', { name: optionLabel, exact: true }).click();
  await page.waitForTimeout(250);
  return wrapper.innerText();
}

module.exports = {
  id: 'sales-order-lifecycle-readonly',
  path: ROUTES.salesOrders,
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'sales-order-lifecycle-readonly',
    path: ROUTES.salesOrders,
    landmarks: ['销售订单管理', '待处理', '审批中', '待履约', '履约中', '已完成', '已关闭'],
    timeoutMs: 60_000,
    inspect: async (page, _body, scenarioCtx) => {
      await page.locator('.sales-lifecycle-tabs').waitFor({ state: 'visible', timeout: 30_000 });
      await page.locator('.el-table__body-wrapper').waitFor({ state: 'visible', timeout: 30_000 });
      await waitForTableSettled(page);

      const tabs = page.locator('.sales-lifecycle-tabs [role="tab"]');
      const lifecycleLabels = (await tabs.allInnerTexts()).map(normalizedTabLabel);
      const lifecycleExact = EXPECTED_LIFECYCLES.every((label) => lifecycleLabels.includes(label))
        && lifecycleLabels.length === EXPECTED_LIFECYCLES.length;
      const shipmentFilterCount = await page.getByRole('combobox', { name: '出库状态' }).count();
      const paymentFilterCount = await page.getByRole('combobox', { name: '收款状态' }).count();
      const nextActionHeaderCount = await page
        .locator('.el-table__header-wrapper th')
        .filter({ hasText: '下一步' })
        .count();
      const orderRowCount = await page.locator('.el-table__body-wrapper tbody tr').count();

      let traceExpanded = false;
      let traceSameViewport = false;
      let traceLabels = [];
      const screenshots = [];
      const expandButton = page.locator('.el-table__expand-icon').first();
      if (await expandButton.count()) {
        await expandButton.click();
        const trace = page.locator('.order-trace-panel').first();
        await trace.waitFor({ state: 'visible' });
        traceLabels = await trace.locator('.trace-section-title').allInnerTexts();
        const viewportWidth = page.viewportSize()?.width || 0;
        const boxes = await trace.locator('.trace-section').evaluateAll((elements) => elements.map((element) => {
          const rect = element.getBoundingClientRect();
          return { left: rect.left, right: rect.right, width: rect.width };
        }));
        traceExpanded = ['订单明细', '出库履约', '收款进度'].every((label) => traceLabels.includes(label));
        traceSameViewport = boxes.length === 3
          && boxes.every((box) => box.left >= 0 && box.right <= viewportWidth + 1 && box.width > 0);
        screenshots.push(await scenarioCtx.screenshot('sales-order-lifecycle-expanded'));
      }

      const shipmentValue = await selectReadOnlyDimension(page, '出库状态', '部分出库');
      const paymentValue = await selectReadOnlyDimension(page, '收款状态', '部分收款');
      await waitForTableSettled(page);
      const combinationApplied = shipmentValue.includes('部分出库') && paymentValue.includes('部分收款');
      const filteredRows = page.locator('.el-table__body-wrapper tbody tr');
      const filteredRowTexts = await filteredRows.allInnerTexts();
      const combinationSemanticsVerified = filteredRowTexts.length > 0
        && filteredRowTexts.every((text) => text.includes('部分出库') && text.includes('部分收款'));

      const fulfillingTab = tabs.filter({ hasText: '履约中' }).first();
      await fulfillingTab.click();
      await page.waitForTimeout(300);
      const fulfillmentSelected = await fulfillingTab.getAttribute('aria-selected') === 'true'
        || (await fulfillingTab.getAttribute('class') || '').includes('is-active');

      await selectReadOnlyDimension(page, '出库状态', '全部出库状态');
      await selectReadOnlyDimension(page, '收款状态', '全部收款状态');
      await tabs.filter({ hasText: '全部' }).first().click();

      const structuralPassed = lifecycleExact
        && shipmentFilterCount === 1
        && paymentFilterCount === 1
        && nextActionHeaderCount === 1
        && combinationApplied
        && combinationSemanticsVerified
        && fulfillmentSelected;
      const fullyVerified = structuralPassed && orderRowCount > 0 && traceExpanded && traceSameViewport;
      return {
        lifecycleLabels,
        shipmentFilterCount,
        paymentFilterCount,
        nextActionHeaderCount,
        orderRowCount,
        traceLabels,
        traceExpanded,
        traceSameViewport,
        combinationApplied,
        combinationSemanticsVerified,
        filteredRowCount: filteredRowTexts.length,
        fulfillmentSelected,
        screenshots,
        assessment: fullyVerified
          ? { result: 'PASS', rootCauseClass: 'none' }
          : orderRowCount === 0 || filteredRowTexts.length === 0
            ? { result: 'UNVERIFIED', rootCauseClass: 'data' }
            : { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' },
      };
    },
  }),
};
