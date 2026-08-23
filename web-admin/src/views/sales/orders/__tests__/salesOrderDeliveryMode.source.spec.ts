import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const detail = readFileSync(resolve(import.meta.dirname, '../detail.vue'), 'utf8');
const createPage = readFileSync(resolve(import.meta.dirname, '../new.vue'), 'utf8');

describe('sales delivery mode contract', () => {
  it('offers explicit one-time full and partial modes', () => {
    expect(detail).toContain('deliveryMode: \'FULL\'');
    expect(detail).toContain('value="FULL"');
    expect(detail).toContain('value="PARTIAL"');
    expect(createPage).not.toContain('deliveryMode');
  });

  it('creates one child shipment for full mode through the mother endpoint', () => {
    expect(detail).toContain('/sales/deliveries/${master.id}/shipments');
    expect(detail).toContain('web-full-shipment-${master.id}');
    expect(detail).toContain('母单只负责计划');
  });
});
