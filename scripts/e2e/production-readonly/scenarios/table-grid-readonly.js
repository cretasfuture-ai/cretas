'use strict';
const { runReadOnlyPageScenario } = require('./_shared');
const { step, assertSafe } = require('./visual-unverified-followup-readonly');

function assertGrid(data) {
  if (!data.columns.length || data.rows < 1) throw new Error('UNVERIFIED: no populated table was measured');
  for (const column of data.columns) {
    if (column.header <= 0 || column.body <= 0 || Math.abs(column.header - column.body) >= 2) throw new Error('Table header/body width mismatch');
    if (column.right !== '1px' || column.bottom !== '1px') throw new Error('Table cell grid is incomplete');
  }
  if (data.outline !== '1px') throw new Error('Table viewport outline is missing');
}
function summarizeMeasurements(measurements) {
  return {
    measurementCount: measurements.length,
    rowCounts: measurements.map(data => data.rows),
    firstRowHeights: measurements.map(data => data.rowHeight),
    viewportWidths: measurements.filter(data => data.width).map(data => data.width),
    maximumHeaderBodyDifference: Math.max(...measurements.flatMap(data => data.columns.map(column => Math.abs(column.header - column.body)))),
    rightBorders: [...new Set(measurements.flatMap(data => data.columns.map(column => column.right)))],
    bottomBorders: [...new Set(measurements.flatMap(data => data.columns.map(column => column.bottom)))],
    outlines: [...new Set(measurements.map(data => data.outline))],
  };
}
function scenario(id, path, resize) {
  const inspect = async (page, _body, ctx) => {
    const table = page.locator('main.app-content .el-table').first();
    await table.locator('.el-table__body tbody tr').first().waitFor({ state: 'visible', timeout: 15000 });
    const measure = () => table.evaluate(el => {
      const heads = [...el.querySelectorAll('.el-table__header th:not(.gutter)')];
      const cells = [...el.querySelector('.el-table__body tbody tr').querySelectorAll('td')];
      return { rows: el.querySelectorAll('.el-table__body tbody tr').length,
        rowHeight: el.querySelector('.el-table__body tbody tr').getBoundingClientRect().height,
        outline: getComputedStyle(el).outlineWidth,
        columns: heads.map((header, i) => ({ header: header.getBoundingClientRect().width,
          body: cells[i]?.getBoundingClientRect().width || 0,
          right: cells[i] ? getComputedStyle(cells[i]).borderRightWidth : null,
          bottom: cells[i] ? getComputedStyle(cells[i]).borderBottomWidth : null })) };
    });
    const measurements = [], screenshots = [], resizedOrderWidths = [];
    for (const width of [1440, 1024]) {
      await page.setViewportSize({ width, height: 900 }); await page.waitForTimeout(200);
      const data = await measure(); assertGrid(data); measurements.push({ width, ...data });
      screenshots.push(await ctx.screenshot(`${id}-${width}`));
    }
    if (resize) {
      const header = table.locator('.el-table__header th').filter({ hasText: '订单编号' }).first();
      await header.scrollIntoViewIfNeeded();
      const initial = await header.boundingBox();
      let enlarged = false;
      for (const delta of [80, -160, -160]) {
        const box = await header.boundingBox();
        await page.mouse.move(box.x + box.width - 2, box.y + box.height / 2); await page.mouse.down();
        await page.mouse.move(Math.max(box.x + 2, box.x + box.width + delta), box.y + box.height / 2, { steps: 12 });
        await step(ctx, () => page.mouse.up()); await page.waitForTimeout(150);
        const data = await measure(); assertGrid(data); measurements.push({ resizeDelta: delta, ...data });
        const resizedWidth = (await header.boundingBox()).width;
        resizedOrderWidths.push(resizedWidth);
        if (resizedWidth < 120) throw new Error('Order identifier column collapsed below its readable minimum');
        if (delta > 0) enlarged = resizedWidth > initial.width + 20;
      }
      if (!enlarged) throw new Error('Resize gesture did not enlarge the target column');
      screenshots.push(await ctx.screenshot(`${id}-resized`));
    }
    assertSafe(ctx);
    return { ...summarizeMeasurements(measurements), resizedOrderWidths, screenshots, limits: 'Only current populated table; native pointer, persisted width memory and all-page coverage are not verified',
      assessment: { result: 'PASS', rootCauseClass: 'none' } };
  };
  return { id, defaultEnabled: false, inspect, run: ctx => runReadOnlyPageScenario(ctx, { id, path, screenshot: true, inspect: page => inspect(page, '', ctx) }) };
}
module.exports = { assertGrid, summarizeMeasurements,
  sales: scenario('table-grid-sales-readonly', '/sales/orders', true),
  label: scenario('table-grid-label-readonly', '/quality/label-qc', false) };
