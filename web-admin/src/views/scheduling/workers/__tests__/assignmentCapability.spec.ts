import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

describe('worker assignment capability guard', () => {
  it('does not issue the known unavailable optimization request and retains manual assignment', () => {
    const source = readFileSync(resolve(__dirname, '../assignment.vue'), 'utf8');
    expect(source).toContain('getSchedulingCapabilities');
    expect(source).toContain('assignWorkers(factoryId.value');
    expect(source).not.toContain('optimizeWorkers(');
    expect(source).toContain('workerOptimizationReason');
  });
});
