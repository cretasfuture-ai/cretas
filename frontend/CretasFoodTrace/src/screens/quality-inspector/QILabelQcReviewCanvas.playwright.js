// Load with Playwright MCP browser_run_code_unsafe(filename=...). Expo must run on 3044.
async (page) => {
  const context = await page.context().browser().newContext({ viewport: { width: 430, height: 800 } });
  const p = await context.newPage();
  const evidence = 'C:/Users/Steve/cretas-drive-20260910-b04/frontend/CretasFoodTrace/';
  try {
    await p.route('http://localhost:3044/', route => route.fulfill({ contentType: 'text/html', body:
      '<!doctype html><div id="root" style="height:100vh"></div><script src="/src/screens/quality-inspector/QILabelQcReviewCanvas.fixture.bundle?platform=web&dev=true&hot=false"></script>' }));
    await p.goto('http://localhost:3044/');
    await p.getByTestId('fixture-state').waitFor();
    const read = async () => JSON.parse(await p.getByTestId('fixture-state').innerText());
    const assert = (condition, message) => { if (!condition) throw new Error(message); };
    const reset = async () => p.getByText('Reset', { exact: true }).click();
    await p.screenshot({ path: evidence + 'b04-before-delete.png' });
    await p.getByRole('button', { name: '删除人工标注框', exact: true }).click();
    const deleted = await read();
    assert(deleted.count === 0 && deleted.adds === 0 && deleted.deletes === 1, 'Delete fired canvas action');
    await p.screenshot({ path: evidence + 'b04-after-delete.png' });
    await reset();
    await p.getByText('Zoom 2x', { exact: true }).click();
    await p.getByRole('button', { name: '删除人工标注框', exact: true }).click();
    const zoomDeleted = await read();
    assert(zoomDeleted.count === 0 && zoomDeleted.adds === 0, 'Zoomed delete fired canvas action');
    await reset();
    await p.getByText('缺白', { exact: true }).click();
    const labeled = await read();
    assert(labeled.count === 1 && labeled.adds === 0 && labeled.annotations[0].label === 'MISSING_WHITE_LABEL', 'Label fired canvas action');
    await reset();
    const box = await p.getByTestId('qi-label-qc-annotation-human-1').boundingBox();
    await p.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
    await p.mouse.down();
    await p.mouse.move(box.x + box.width / 2 + 30, box.y + box.height / 2 + 20, { steps: 8 });
    await p.mouse.up();
    const dragged = await read();
    assert(dragged.moves === 1 && dragged.adds === 0 && dragged.annotations[0].bbox.xMin > .3, 'Drag did not move selected box');
    await reset();
    const handle = await p.getByLabel('拖动缩放标注框', { exact: true }).boundingBox();
    await p.mouse.move(handle.x + handle.width / 2, handle.y + handle.height / 2);
    await p.mouse.down();
    await p.mouse.move(handle.x + handle.width / 2 + 24, handle.y + handle.height / 2 + 20, { steps: 8 });
    await p.mouse.up();
    const resized = await read();
    assert(resized.annotations[0].bbox.xMax > .55 && resized.adds === 0, 'Resize did not grow selected box');
    await reset();
    const canvas = await p.getByTestId('qi-label-qc-photo-canvas').boundingBox();
    await p.mouse.click(canvas.x + 340, canvas.y + 430);
    await p.waitForFunction(() => JSON.parse(document.querySelector('[data-testid="fixture-state"]').textContent).adds === 1,
      undefined, { timeout: 3000 }); // Single tap waits for the exclusive double-tap recognizer.
    const added = await read();
    assert(added.count === 2 && added.adds === 1, 'Blank canvas tap no longer adds a box');
    await p.getByText('Toggle readonly', { exact: true }).click();
    assert(await p.getByRole('button', { name: '删除人工标注框', exact: true }).count() === 0, 'Readonly toolbar visible');
    return { deleted, zoomDeleted, labeled, dragged, resized, added, readonly: true, fixtureOnly: true };
  } finally {
    await context.close();
  }
}
