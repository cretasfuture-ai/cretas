import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

const strip = (src: string) => src.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '');
const list = strip(readFileSync(resolve(process.cwd(), 'src/views/production/plans/list.vue'), 'utf8'));
const api = strip(readFileSync(resolve(process.cwd(), 'src/api/productionPlan.ts'), 'utf8'));

/**
 * S2-06: 路线被新版本替换后, 旧计划固定的版本不在「当前启用」列表里,
 * 计划列表必须改读计划固定的那一版, 而不是直接报「未读取到计划固定的工序路线」。
 * 后端行为由 ProductWorkflowUnifiedResolutionTest#pinnedCandidateStaysReadableAfterTheVersionIsReplaced 钉。
 */
describe('生产计划列表: 固定版本路线回落读取', () => {
  it('接口指向 /pinned 且带 workflowId + version', () => {
    expect(api).toContain('/product-process-workflows/pinned');
    expect(api).toMatch(/workflowId=.*version=/s);
  });

  it('未在启用候选中命中固定版本时调用 findPinnedWorkflow, 且在报错文案之前', () => {
    const fn = list.slice(list.indexOf('async function ensureRowWorkflowPreview'));
    const callAt = fn.indexOf('findPinnedWorkflow(factoryId.value, selectedWorkflowId, selectedWorkflowVersion)');
    const errorAt = fn.indexOf('未读取到计划固定的工序路线');
    expect(callAt).toBeGreaterThan(-1);
    expect(errorAt).toBeGreaterThan(callAt);
  });

  it('阴性对照: 回落读的是固定版本, 不是用启用候选顶替', () => {
    const fn = list.slice(list.indexOf('async function ensureRowWorkflowPreview'));
    expect(fn).not.toMatch(/candidate\s*=\s*resolvedCandidates\[0\]\s*;?\s*\n\s*}/);
  });
});
