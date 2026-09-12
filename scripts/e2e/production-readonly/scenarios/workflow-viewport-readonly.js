'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe, verifyTarget } = require('./visual-unverified-followup-readonly');
module.exports = { id: 'workflow-viewport-readonly', defaultEnabled: false, run: ctx => runReadOnlyPageScenario(ctx, {
  id: 'workflow-viewport-readonly', path: '/production/process-hub?tab=product-processes', screenshot: false,
  inspect: async page => {
    await verifyTarget(page, ctx, '/production/process-hub?tab=product-processes');
    const editor = page.locator('.workflow-editor');
    await editor.waitFor({ state: 'visible', timeout: 15000 });
    await page.locator('.vue-flow__node').first().waitFor({ state: 'attached', timeout: 15000 });
    const nodeIds = () => page.locator('.vue-flow__node').evaluateAll(nodes => nodes.map(n => n.getAttribute('data-id')).sort());
    const before = await nodeIds(); const states = []; const screenshots = [];
    const enter = page.getByTestId('workflow-immersive-enter');
    if (await enter.isVisible()) await step(ctx, () => enter.click({ timeout: 5000 }));
    const capture = async (state, fullscreen) => {
      // Cover the editor's 2500ms debounced autosave after every layout action.
      await page.waitForTimeout(3100); assertSafe(ctx);
      const box = await editor.boundingBox();
      const viewport = page.viewportSize();
      const parentTabs = await page.locator('.process-config-hub > .hub-tabs > .el-tabs__header').isVisible();
      const ids = await nodeIds();
      const nodesPreserved = JSON.stringify(ids) === JSON.stringify(before);
      const geometry = fullscreen ? Boolean(box && Math.abs(box.x)<2 && Math.abs(box.y)<2 && Math.abs(box.width-viewport.width)<2 && Math.abs(box.height-viewport.height)<2 && !parentTabs) : parentTabs;
      states.push({ state, fullscreen, box, parentTabs, nodesPreserved, geometry });
      screenshots.push(await ctx.screenshot(`workflow-${state}`));
    };
    await capture('fullscreen', true);
    // Viewport-change-end can autosave when editable. Fit safety is not established for production.
    const fit = { result: 'UNVERIFIED', reason: 'Editable viewport changes have an autosave path; local fit did not reproduce a write, but production fit remains untested.' };
    const dock = page.locator('.workflow-ai-dock');
    const collapse = dock.getByText('收起', { exact: true });
    let collapsed = false;
    if (await collapse.isVisible()) {
      const beforeHeight = (await dock.boundingBox()).height;
      await step(ctx, () => collapse.click({ timeout: 5000 }));
      await page.waitForTimeout(450);
      collapsed = (await dock.boundingBox()).height < beforeHeight;
    }
    await capture('ai-collapsed', true);
    await step(ctx, () => page.getByTestId('workflow-immersive-exit').click({ timeout: 5000 }));
    await capture('exit', false);
    await step(ctx, () => enter.click({ timeout: 5000 }));
    await capture('reenter', true);
    assertSafe(ctx);
    return { states, fit, collapsed, screenshots, assessment: { result: states.every(s=>s.nodesPreserved&&s.geometry)&&collapsed ? 'UNVERIFIED' : 'PARTIAL_DEFECT', rootCauseClass: states.every(s=>s.nodesPreserved&&s.geometry)&&collapsed ? 'tool' : 'frontend' } };
  },
}) };
