import type { VisionDraft, VisionStage, VisionTaskSpec, VisionTemplate } from '@/api/visionTraining';

export const stageLabels: Record<VisionStage, string> = {
  preview: '确认检查内容', collect: '收集现场照片', dataset: '确认照片与标记',
  train: '让系统学习', evaluate: '检查识别效果', independent_acceptance: '用本厂新照片试用', enable: '确认启用',
};
export const actionLabels: Partial<Record<VisionStage, string>> = {
  collect: '开始收集照片', dataset: '确认照片与标记', train: '开始学习', evaluate: '检查识别效果',
};
const titles: Record<string, string> = {
  'generic-packaging-detection': '包装是否缺件', 'generic-packaging-ocr': '包装文字是否正确',
  'liushanmen-tray': '托盘标签检查（本厂专用）',
};
export function templateName(template?: VisionTemplate): string {
  if (!template) return '选择检查内容';
  return titles[template.template_id] || (/\p{Script=Han}/u.test(template.title) ? template.title : '本厂包装检查');
}
export function templateHint(template: VisionTemplate): string {
  if (template.scope === 'specialized') return '只用于本厂已接好的专用检查，不套用到其他工厂。';
  return template.ocr_rules_required ? '检查包装上的文字是否符合本厂要求。' : '检查包装或零件是否缺少、漏放。';
}
export function businessValue(value: string, subject: string): string {
  // Stored names are never translated speculatively or rewritten.
  return /\p{Script=Han}/u.test(value) ? value : `已记录${subject}`;
}
export function businessScope(values: string[], subject: string): string {
  return values.every(value => /\p{Script=Han}/u.test(value)) ? values.join('、') : `已关联 ${values.length} 种${subject}`;
}
export function projectLabel(draft: VisionDraft, index: number): string {
  const place = /\p{Script=Han}/u.test(draft.spec.workstation_id) ? ` · ${draft.spec.workstation_id}` : '';
  return `检查 ${index + 1} · ${templateName(draft.template)}${place}`;
}
export function friendlyReason(reason: string): string {
  if (/\p{Script=Han}/u.test(reason)) return reason;
  if (/no asset receipts|missing.*(?:asset|receipt)/i.test(reason)) return '还缺照片、学习结果或效果检查记录，请先准备本厂资料。';
  if (/enablement.*independent acceptance|independent acceptance.*(?:missing|passes|pending)/i.test(reason)) return '本厂新照片试用还没通过，暂时不能启用。';
  if (/not registered|no.*(?:runner|adapter|recipe)|recipe.*(?:unavailable|binding)/i.test(reason)) return '本厂执行设置还没接好，请让维护人员核对后更新进度。';
  if (/hash|identity|scope|mismatch/i.test(reason)) return '执行记录与本次检查设置不一致，请让维护人员核对后更新进度。';
  return '这一步暂时不能继续，请让维护人员查看系统记录并核对原因。';
}
export function friendlyError(error: string): string {
  return /\p{Script=Han}/u.test(error) ? error : '检查服务暂时没有完成请求，已保留填写内容，请稍后重试。';
}
export function newSettingReferences(): Pick<VisionTaskSpec, 'data_version' | 'recipe_version' | 'human_standard_version' | 'independent_acceptance_version' | 'ocr_rules_version'> {
  // Proposal references are not evidence of real data or acceptance.
  const revision = `setup-${Date.now()}-${crypto.randomUUID().slice(0, 8)}`;
  return { data_version: revision, recipe_version: revision, human_standard_version: revision,
    independent_acceptance_version: revision, ocr_rules_version: null };
}
