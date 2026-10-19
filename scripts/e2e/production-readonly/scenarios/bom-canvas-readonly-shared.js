'use strict';

const STATUS_MARKERS = Object.freeze({
  active: /BOM\s*生效\s*v\d+/,
  draft: /草稿\s*v\d+\s*未生效/,
  noActive: /未建\s*BOM|还没有生效\s*BOM/,
  mismatch: /与已启用工艺不一致/,
});

function hasOnCanvasBomGuidance(text) {
  return /辅料\s*\/\s*包材(?:\s*\/\s*副产)?\s*cell\s*上配置/.test(text);
}

function stateFromRows(rows) {
  const rowStates = rows.map((text) => ({
    text,
    active: STATUS_MARKERS.active.test(text),
    draft: STATUS_MARKERS.draft.test(text),
    noActive: STATUS_MARKERS.noActive.test(text),
    mismatch: STATUS_MARKERS.mismatch.test(text),
  }));
  return {
    active: rowStates.some((row) => row.active),
    draft: rowStates.some((row) => row.draft),
    noActive: rowStates.some((row) => row.noActive),
    mismatch: rowStates.some((row) => row.mismatch),
    conflictingProductRows: rowStates.filter((row) => row.active && row.noActive).length,
    rowStates,
  };
}

async function count(locator) {
  return locator.count();
}

async function selectCanvasProduct(page) {
  const selector = page.locator('.toolbar-left .el-select').first();
  if (!(await selector.isVisible().catch(() => false))) {
    return { selected: false, selectionMode: null, failure: 'canonical top product selector missing', probedProducts: [] };
  }
  const selectedText = () => selector.locator('.el-select__selected-item').innerText().catch(() => '');
  const probedProducts = [];
  const startedAt = Date.now();
  const MAX_TRIES = 6;
  const DEADLINE_MS = 90_000;
  for (let index = 0; index < MAX_TRIES && Date.now() - startedAt <= DEADLINE_MS; index += 1) {
    const processNodes = await count(page.locator('.vue-flow__node-process'));
    const label = (await selectedText()).trim();
    if (processNodes > 0 && label && !/选择本条工艺|选择产品/.test(label)) {
      return { selected: true, selectedLabel: label, selectionMode: 'current-top-selector', probedProducts };
    }
    try {
      await selector.click({ timeout: 5_000 });
      const options = page.locator('.el-select-dropdown:visible .el-select-dropdown__item:not(.is-disabled)');
      await options.first().waitFor({ state: 'visible', timeout: 5_000 });
      if (index >= await options.count()) break;
      const optionLabel = (await options.nth(index).innerText().catch(() => '')).trim();
      await options.nth(index).click({ timeout: 5_000 });
      await page.waitForLoadState('networkidle', { timeout: 6_000 }).catch(() => {});
      await page.locator('.vue-flow__node-process').first().waitFor({ state: 'attached', timeout: 20_000 }).catch(() => {});
      probedProducts.push({ index, option: optionLabel.slice(0, 80), processNodes: await count(page.locator('.vue-flow__node-process')) });
    } catch (error) {
      probedProducts.push({ index, error: String(error?.message || error).slice(0, 120) });
      await page.keyboard.press('Escape').catch(() => {});
      break;
    }
  }
  return { selected: false, selectionMode: 'bounded-top-selector-probe', probedProducts, failure: 'no selected product with PROCESS context' };
}

async function inspectCanvas(page, body) {
  const processNodes = await count(page.locator('.vue-flow__node-process'));
  const materialNodes = await count(page.locator('.vue-flow__node-material'));
  const auxiliaryCells = await count(page.locator('.vue-flow__node-bomAuxiliary'));
  const packagingCells = await count(page.locator('.vue-flow__node-bomPackaging'));
  const byproductCells = await count(page.locator('[data-testid="byproduct-cell"]'));
  let rowTexts = [];
  let versionStatusVisible = await page.getByTestId('workflow-version-status').isVisible().catch(() => false);
  let versionRows = await count(page.getByTestId('workflow-version-status-row'));
  if (versionStatusVisible && versionRows === 0) {
    await page.getByTestId('workflow-version-status-toggle').click().catch(() => {});
    await page.getByTestId('workflow-version-status-row').first().waitFor({ state: 'visible', timeout: 5_000 }).catch(() => {});
    versionRows = await count(page.getByTestId('workflow-version-status-row'));
    body = await page.locator('body').innerText().catch(() => body);
  }
  rowTexts = await page.getByTestId('workflow-version-status-row').allInnerTexts().catch(() => []);
  const status = stateFromRows(rowTexts.length ? rowTexts : [body]);
  const packSubtitleTexts = await page.getByTestId('pack-subtitle').allInnerTexts().catch(() => []);
  const packagingUnitEvidence = packSubtitleTexts.length > 0
    && packSubtitleTexts.every((text) => /(?:\d+\s*种|分\s*\d+\s*层).*每\s*1\s*\S+成品/.test(text));
  return {
    processNodes,
    materialNodes,
    auxiliaryCells,
    packagingCells,
    byproductCells,
    versionStatusVisible,
    versionRows,
    bomBubbles: await count(page.getByTestId('bom-status-bubble')),
    auxDetailEntries: await count(page.getByTestId('aux-open-detail')),
    packSubtitles: await count(page.getByTestId('pack-subtitle')),
    packSubtitleTexts,
    packagingUnitEvidence,
    status,
    versionRowTexts: rowTexts,
    basicUnitVisible: packagingUnitEvidence || (packagingCells === 0 && /基本单位|投入基准单位/.test(body)),
    legacyVersionTableVisible: body.includes('BOM 配方版本'),
    legacyDrawerCopyVisible: body.includes('在右侧配置 BOM'),
    legacyJumpCopyVisible: body.includes('去 BOM 配置'),
  };
}

function classifyCanvas(evidence, productSelection = { selected: true }) {
  if (!productSelection.selected) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'real product context was not selected' };
  }
  if (evidence.processNodes === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'no PROCESS node hosts BOM overlays' };
  }
  if (!evidence.versionStatusVisible || evidence.versionRows === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'formal BOM version status is absent' };
  }
  const { active, draft, noActive, mismatch } = evidence.status;
  if (!active && !draft && !noActive && !mismatch) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'formal BOM state is not classifiable' };
  }
  if (evidence.status.conflictingProductRows > 0) {
    return { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: 'same product asserts both active and no-active BOM state' };
  }
  if (evidence.packagingCells > 0 && evidence.packSubtitles === 0) {
    return { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: 'packaging cell has no role/unit/quantity evidence' };
  }
  if (!evidence.basicUnitVisible || (evidence.packagingCells > 0 && !evidence.packagingUnitEvidence)) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'basic-unit semantics are absent' };
  }
  if (evidence.packagingCells === 0) {
    return { result: 'UNVERIFIED', rootCauseClass: 'data', reason: 'no packaging fixture is present to verify packaging semantics' };
  }
  return { result: 'PASS', rootCauseClass: 'none', reason: 'canonical canvas has a classifiable BOM state and unit/packaging evidence' };
}

module.exports = { STATUS_MARKERS, selectCanvasProduct, inspectCanvas, classifyCanvas, stateFromRows, hasOnCanvasBomGuidance };

