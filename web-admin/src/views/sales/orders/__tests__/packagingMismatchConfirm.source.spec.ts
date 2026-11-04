import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * O-309-1: 分配对话框里, 规格不一致的批次要看得见、提交前要问、确认过才带标记。
 * ⚠️ detail.vue 有两千多行, 这里是源码结构闸 (证明接上了, 证明不了用户看到了);
 *    确认行为本身的真挂载测试在 warehouse/shipments/__tests__/packagingConfirm.integration.test.ts。
 */
const source = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('销售订单分配对话框 · 规格不一致', () => {
  it('推荐接口的标记和两边规格要带进表格行', () => {
    expect(source).toContain('packagingMismatch: r.packagingMismatch === true');
    expect(source).toContain('batchPackagingLabel: String(r.batchPackagingLabel');
    expect(source).toContain('linePackagingLabel: String(r.linePackagingLabel');
  });

  it('行内显示两边规格', () => {
    expect(source).toMatch(/v-if="row\.packagingMismatch"[\s\S]{0,120}规格不一致: 批次 \{\{ row\.batchPackagingLabel \}\}, 本行 \{\{ row\.linePackagingLabel \}\}/);
  });

  it('取消确认就不提交', () => {
    expect(source).toContain('if (mismatchLines.length > 0 && !(await confirmPackagingConversion(mismatchLines))) return;');
    expect(source.indexOf('confirmPackagingConversion(mismatchLines)'))
      .toBeLessThan(source.indexOf('submitting.value = true;\n  let success = 0, failed = 0;'));
  });

  it('只有规格不一致的行才带确认标记', () => {
    expect(source).toContain('...(a.packagingMismatch ? { packagingConversionConfirmed: true } : {})');
  });
});
