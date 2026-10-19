'use strict';

const { ROUTES } = require('../config/routes');
const { runReadOnlyPageScenario } = require('./_shared');
const { inspectCanvas, classifyCanvas, selectCanvasProduct } = require('./bom-canvas-readonly-shared');

async function selectTargetProduct(page) {
  return selectCanvasProduct(page);
}

module.exports = {
  id: 'bom-readonly',
  run: (ctx) => runReadOnlyPageScenario(ctx, {
    id: 'bom-readonly',
    path: `${ROUTES.workflow}?tab=product-processes`,
    landmarks: ['Workflow'],
    screenshot: true,
    inspect: async (page, body, ctx) => {
      const productSelection = await selectTargetProduct(page);
      const currentBody = await page.locator('body').innerText();
      const contract = await inspectCanvas(page, currentBody);
      const assessment = classifyCanvas(contract, productSelection);
      const unsupportedLegacyContracts = {
        result: 'UNVERIFIED',
        reason: 'retired BOM page carrier has no current equivalent selector',
        checks: ['system historical yield', 'price-unit card', 'finished-good cost denominator', 'active immutable view', 'new-version/clone entry'],
      };
      const screenshots = [await ctx.screenshot('bom-active-readonly')];
      if (assessment.result !== 'PASS') return { ...contract, productSelection, modernCarrierAssessment: assessment, unsupportedLegacyContracts, contractFailures: [], screenshots, assessment };
      const failures = [];
      if (!productSelection.selected) failures.push(productSelection.failure);
      if (contract.legacyVersionTableVisible) failures.push('retired BOM version table still rendered');
      if (contract.legacyDrawerCopyVisible) failures.push('retired drawer copy still rendered');
      if (contract.legacyJumpCopyVisible) failures.push('retired jump-away copy still rendered');
      if (contract.auxiliaryCells > 0 && contract.auxDetailEntries === 0) failures.push('auxiliary cell lacks detail entry');
      if (contract.packagingCells > 0 && contract.packSubtitles === 0) failures.push('packaging cell lacks unit/role subtitle');
      return {
        ...contract,
        productSelection,
        unsupportedLegacyContracts,
        contractFailures: failures,
        screenshots,
        modernCarrierAssessment: failures.length ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend', reason: failures.join('; ') } : assessment,
        assessment: failures.length
          ? { result: 'CONFIRMED_DEFECT', rootCauseClass: 'frontend' }
          : { result: 'UNVERIFIED', rootCauseClass: 'tool', reason: unsupportedLegacyContracts.reason },
      };
    },
  }),
};
