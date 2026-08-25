import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const source = (path: string): string =>
  readFileSync(resolve(process.cwd(), path), 'utf8');

describe('label QC training lifecycle UI contract', () => {
  const page = source('src/views/quality/label-qc/index.vue');
  const api = source('src/api/labelQc.ts');
  const workbench = source('src/views/quality/label-qc/LabelQcReviewWorkbench.vue');

  it('separates daily review, reviewed-data management, and archives', () => {
    expect(page).toContain('待人工审核');
    expect(page).toContain('已审核整理');
    expect(page).toContain('归档记录');
    expect(page).toContain('归档不删除，可随时恢复');
    expect(api).toContain('/archive');
    expect(api).toContain('/restore');
  });

  it('keeps backup export separate from approved training export', () => {
    expect(page).toContain('下载照片备份');
    expect(page).toContain('createPhotoArchive');
    expect(page.indexOf('const archive = await createPhotoArchive'))
      .toBeLessThan(page.indexOf('const recordResponse = await backupLabelQcTask'));
    expect(page).toContain('导出已批准训练集');
    expect(api).toContain('/backup');
    expect(api).toContain('/training-export');
  });

  it('supports reviewed-task selection and builds one complete bulk archive before audit writes', () => {
    expect(page).toContain('type="selection"');
    expect(page).toContain(':selectable="canSelectForPhotoBackup"');
    expect(page).toContain('批量下载照片');
    expect(page).toContain('createBulkPhotoArchive');
    expect(page.indexOf('const archive = await createBulkPhotoArchive(details);'))
      .toBeLessThan(page.indexOf('const recordResults = await Promise.allSettled'));
    expect(page.indexOf('const recordResults = await Promise.allSettled'))
      .toBeLessThan(page.indexOf('downloadPhotoArchive(archive);', page.indexOf('async function backupSelectedTasks')));
  });

  it('narrows the queue by production date and allows big pages before exporting', () => {
    expect(page).toContain('生产日期起');
    expect(page).toContain('生产日期止');
    expect(page).toContain('productionDateFrom: productionDateRange.value?.[0]');
    expect(page).toContain('productionDateTo: productionDateRange.value?.[1]');
    expect(page).toContain('const PAGE_SIZES = [20, 50, 100, 200, 500];');
    expect(page).toContain(':page-sizes="PAGE_SIZES"');
    expect(page).toContain('@size-change="changePageSize"');
    expect(api).toContain('productionDateFrom');
    expect(api).toContain('productionDateTo');
  });

  it('gates explicit training approval behind the system permission', () => {
    expect(page).toContain("permissions.includes('system:read_write')");
    expect(page).toContain('批准训练');
    expect(page).toContain('拒绝训练');
    expect(page).toContain('此操作不会自动训练或发布模型');
    expect(api).toContain('/training-decision');
  });

  it('keeps the customer risk queue separate from platform training truth', () => {
    expect(api).toContain("| 'AUTO_PASSED'");
    expect(page).toContain("? ['NEEDS_REVIEW', 'ANALYSIS_FAILED']");
    expect(page).toContain(": ['AUTO_PASSED', 'REVIEWED']");
    expect(page).toContain('客户只处理 AI 风险与无法判断照片');
    expect(page).toContain("row.status === 'AUTO_PASSED'");
    expect(page).toContain('非人工真值');
    expect(page).toContain("return task.status === 'REVIEWED'");
    expect(page).toContain("<template v-if=\"row.status === 'REVIEWED'\">");
  });

  it('keeps only suspicious boxes actionable without tray outlines, guide rows, or brush tools', () => {
    expect(workbench).toContain('v-for="item in visibleItems"');
    expect(workbench).toContain('托盘无需描框');
    expect(workbench).not.toContain('horizontal-guide');
    expect(workbench).not.toContain('buildHorizontalGuideRows');
    expect(workbench).not.toContain('object-tray-box');
    expect(workbench).not.toContain('reference-box');
    expect(workbench).not.toContain("mode: 'brush'");
    expect(workbench).not.toContain('漏了盒子');
    expect(workbench).not.toContain('删错盒子');
    expect(workbench).toContain("showTechnicalObjectReview.value || selectedObject.value?.decision === 'ADDED'");
    expect(workbench).toContain('if (!displayedObjectDraft.value.trays.length) break;');
  });

  it('shows normal label boxes and problem boxes together without requiring optional correction mode', () => {
    expect(workbench).toContain('activeDraft.value?.items.filter((item) => Boolean(item.bbox))');
    expect(workbench).toContain('objectDraft.value ?? buildObjectReviewDraft(activePhoto.value)');
    expect(workbench).toContain('<div v-if="displayedObjectDraft.trays.length" class="layer-toggles"');
    expect(workbench).toContain('<template v-for="tray in displayedObjectDraft.trays" :key="tray.key">');
    expect(workbench).not.toContain('<template v-if="showTechnicalObjectReview">');
    expect(workbench).toContain("'reference-label-box': !showTechnicalObjectReview && label.decision !== 'ADDED'");
    expect(workbench).toContain('.object-final-box.reference-label-box');
    expect(workbench).toContain('pointer-events: none');
    expect(workbench).toContain('正常标签与风险位置同时显示');
    expect(workbench).toContain('这些框以绿色正常框显示，可在提交前撤销');
    expect(workbench).not.toContain('这些框不会显示在照片上');
  });

  it('uses category-colored translucent fills while retaining tray ownership for optional label correction', () => {
    expect(workbench).toContain("'--overlay-fill': `${itemColor(item)}2e`");
    expect(workbench).toContain("'--overlay-fill': `${color}29`");
    expect(workbench).toContain('background: var(--overlay-fill)');
    expect(workbench).toContain('v-for="tray in displayedObjectDraft.trays"');
    expect(workbench).toContain('v-for="label in tray.labels"');
    expect(workbench).toContain('标签校正（可选）');
    expect(workbench).toContain('+ 补{{ objectLabelText(type) }}框');
  });

  it('separates optional existing-label supplementation from real missing-label defect review', () => {
    expect(workbench).toContain('标签数量参考');
    expect(workbench).toContain('按同批固定数量');
    expect(workbench).toContain('该图片可能存在模型未标注的白标或彩标');
    expect(workbench).toContain('仅提醒，不强制补标，不影响审核提交');
    expect(workbench).toContain('@click="beginLabelSupplement(type)"');
    expect(workbench).toContain('findTrayForLabelBox(draft, bbox)');
    expect(workbench).toContain('addSupplementalLabel(bbox)');
    expect(workbench).toContain('v-for="entry in addedLabelObjects"');
    expect(workbench).not.toContain('defaultLabelBox(tray)');
  });

  it('releases the submitting state before queue refresh and keeps timeout retries idempotent', () => {
    expect(page).toContain('prepareLabelQcReviewAttempt(');
    expect(page).toContain('reviewRequestId: attempt.requestId');
    expect(page).toContain('本批暂未确认提交成功，标注已保留，可直接重试');
    expect(page).toContain('本批已提交成功，但待审列表刷新失败');
    expect(page.indexOf('submitting.value = false;', page.indexOf('async function submitReview')))
      .toBeLessThan(page.indexOf('await load();', page.indexOf('async function submitReview')));
    expect(api).toContain('{ timeout: 30_000 }');
  });
});
