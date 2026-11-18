import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const src = readFileSync(resolve(__dirname, '../list.vue'), 'utf-8');
const tpl = src.slice(src.indexOf('<template>'));

describe('生产批次页提示框状态用中文 (PROTO-DEMO-LAST)', () => {
  it('ConceptDisambiguationAlert 的 here/other 不含英文状态码, 且复用 getStatusText', () => {
    const start = tpl.indexOf('<ConceptDisambiguationAlert');
    const alert = tpl.slice(start, tpl.indexOf('/>', start));
    expect(alert.replace(/getStatusText\('[A-Z_]+'\)/g, '')).not.toMatch(/IN_PROGRESS|COMPLETED|PENDING/);
    expect(alert).toContain("getStatusText('IN_PROGRESS')");
    expect(alert).toContain("getStatusText('COMPLETED')");
    expect(alert).toContain("getStatusText('PENDING')");
  });
  it('映射结果为中文', () => {
    expect(src).toMatch(/IN_PROGRESS: '生产中'/);
    expect(src).toMatch(/COMPLETED: '已完成'/);
  });
});
