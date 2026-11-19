import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';

const list = readFileSync(resolve(__dirname, 'list.vue'), 'utf8');

describe('入库任务与批次：没有任务但有批次时默认打开「已入库批次」', () => {
  it('只在任务数加载完且为 0、批次数大于 0、用户没点过标签时切换一次', () => {
    expect(list).toContain("activeInboundTab.value === 'ALL' && taskTotal === 0 && batchTotal > 0");
    expect(list).toContain('if (inboundTabTouched.value || !loaded) return;');
    expect(list).toContain('@tab-change="handleInboundTabChange"');
  });

  it('URL 带 view 参数时视为用户已指定，不再自动切换', () => {
    expect(list).toContain('if (normalized) inboundTabTouched.value = true;');
  });
});
