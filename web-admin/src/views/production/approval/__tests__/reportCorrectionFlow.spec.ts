import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, it } from 'vitest';

const here = dirname(fileURLToPath(import.meta.url));
const approval = readFileSync(resolve(here, '../list.vue'), 'utf8');
const oaPending = readFileSync(resolve(here, '../../../workflow/pending.vue'), 'utf8');
const stocktake = readFileSync(resolve(here, '../../../warehouse/stocktakes/index.vue'), 'utf8');
const api = readFileSync(resolve(here, '../../../../api/processProduction.ts'), 'utf8');
const permissionMatrix = readFileSync(resolve(here, '../../../platform/canvas-editor/components/ModulePermissionMatrix.vue'), 'utf8');

describe('报工核对修正主链', () => {
  it('原值自动预填，且表单没有投入库存字段', () => {
    expect(approval).toContain('outputQuantity: row.outputQuantity ?? null');
    expect(approval).toContain('goodQuantity: row.goodQuantity ?? null');
    expect(approval).toContain('defectQuantity: row.defectQuantity ?? null');
    expect(approval).toContain('提交 OA 审批');
    expect(approval).toContain('row.corrections || [row.lastCorrection]');
    const dialog = approval.slice(approval.indexOf('v-model="correctionDialogVisible"'));
    expect(dialog).not.toContain('投入数量');
    expect(dialog).not.toContain('原料批次');
    expect(dialog).toContain('报工阶段');
    expect(dialog).toContain('可信产出单位');
  });

  it('OA 待办展示修正前后并把该业务类型作为可操作审批', () => {
    expect(oaPending).toContain("'PRODUCTION_REPORT_CORRECTION'");
    expect(oaPending).toContain('reportCorrectionDetails.before');
    expect(oaPending).toContain('reportCorrectionDetails.after');
    expect(oaPending).toContain('reportCorrectionDetails.reason');
    expect(oaPending).toContain('label="修正内容"');
    expect(oaPending).toContain('可信产出单位');
  });

  it('盘点详情回读后展示重点核对提醒但不自动提交盘盈盘亏', () => {
    expect(stocktake).toContain('stocktakes/${row.id}');
    expect(stocktake).toContain('reportCorrectionWarningMessage');
    expect(stocktake).toContain('不会自动生成盘盈盘亏');
    expect(api).toContain('/production-report-corrections/reports/${reportId}');
  });

  it('报工修正入口使用独立可配置权限，不复用普通报工权限', () => {
    expect(approval).toContain("canWrite('production_report_correction')");
    expect(approval).toContain('v-if="canCorrectReports"');
    expect(approval).toContain("canWrite('production')");
    expect(permissionMatrix).toContain("'production_report_correction'");
  });
});
