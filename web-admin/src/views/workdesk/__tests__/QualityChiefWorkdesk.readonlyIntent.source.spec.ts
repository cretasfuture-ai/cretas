import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(import.meta.dirname, '../QualityChiefWorkdesk.vue'),
  'utf8',
);

describe('QualityChiefWorkdesk default pending query', () => {
  it('pins automatic and post-decision refreshes to the read-only workdesk intent', () => {
    expect(source).toContain("await sendQuery(true);");
    expect(source).toContain("forcePending ? 'QUALITY_CHIEF_WORKDESK' : undefined");
    expect(source).toContain('await triggerPendingQuery();');
    expect(source).not.toMatch(/onMounted\(\(\) => \{\s*sendQuery\(/);
  });
});
