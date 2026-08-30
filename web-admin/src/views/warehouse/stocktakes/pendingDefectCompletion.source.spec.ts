import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), 'src/views/warehouse/stocktakes/index.vue'),
  'utf8',
);

describe('pending defect count completion UI contract', () => {
  it('only exposes the completion action to warehouse writers', () => {
    expect(source).toContain('<el-table-column v-if="canWrite" label="操作"');
    expect(source).toContain('@click="openDefectDialog(row)"');
    expect(source).toContain("permissionStore.canWrite('warehouse')");
  });

  it('keeps both explicit outcomes and blocks an invalid positive-count branch', () => {
    expect(source).toContain('<el-radio label="COUNTED">有不良品</el-radio>');
    expect(source).toContain('<el-radio label="NONE_CONFIRMED">已清点确认零</el-radio>');
    expect(source).toContain(':disabled="defectOutcome === \'COUNTED\' && (!defectQuantity || defectQuantity <= 0)"');
    expect(source).toContain("defectQuantity: defectOutcome.value === 'COUNTED' ? defectQuantity.value : null");
    expect(source).toContain("countDisplayUnit(String(defectDialogRow.quantityUnit || ''))");
  });

  it('refreshes from the server after success and keeps a failed dialog actionable', () => {
    expect(source).toContain('await loadPendingDefectCounts();');
    expect(source).toContain("defectError.value = response?.message");
    expect(source).toContain('补录失败，请保持窗口打开并重试。');
    expect(source.indexOf('defectDialogVisible.value = false;'))
      .toBeLessThan(source.indexOf('await loadPendingDefectCounts();'));
  });

  it('does not turn a load failure into a false empty list', () => {
    expect(source).toContain('v-if="pendingDefectLoadError"');
    expect(source).toContain('@click="loadPendingDefectCounts">重试加载待办</el-button>');
    const catchBody = source.slice(
      source.indexOf('} catch (error: unknown) {'),
      source.indexOf('function openDefectDialog'),
    );
    expect(catchBody).toContain('pendingDefectLoadError.value =');
    expect(catchBody).not.toContain('pendingDefectCounts.value = []');
  });
});
