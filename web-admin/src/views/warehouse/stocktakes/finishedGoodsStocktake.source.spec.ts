import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const source = readFileSync(resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'), 'utf8');

describe('finished goods stocktake UI contract', () => {
  it('makes the inventory scope explicit and posts it with the create request', () => {
    expect(source).toContain("inventoryScope: 'RAW_MATERIAL'");
    expect(source).toContain('value="FINISHED_GOODS"');
    expect(source).toContain('inventoryScope: initiateForm.inventoryScope');
    expect(source).toContain("item.finishedGoodsBatchId || item.materialBatchId");
  });
});
