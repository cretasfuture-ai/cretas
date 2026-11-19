import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import {
  UNLINK_REASON_MAX_LENGTH,
  unlinkSourceIndividualPath,
  validateUnlinkReason,
} from '../sourceIndividualUnlink';

/**
 * #1844 终审 L-d: 批次编辑弹窗里「解除来源个体挂载」。
 * 后端契约见 MaterialBatchUnlinkSourceIndividualEndpointContractTest (原因必填 ≤255, POST 独立端点)。
 */
describe('解除来源个体: 原因校验与后端同口径', () => {
  it('空 / 纯空白 → 拦下并说清', () => {
    expect(validateUnlinkReason('')).toBe('请填写解除原因');
    expect(validateUnlinkReason('   ')).toBe('请填写解除原因');
    expect(validateUnlinkReason(null)).toBe('请填写解除原因');
  });

  it('超 255 字 → 拦下; 恰好 255 字放行 (边界与后端 @Size 一致)', () => {
    expect(UNLINK_REASON_MAX_LENGTH).toBe(255);
    expect(validateUnlinkReason('x'.repeat(256))).toContain('最多 255 字');
    expect(validateUnlinkReason('x'.repeat(255))).toBe(true);
    expect(validateUnlinkReason('挂错了个体')).toBe(true);
  });

  it('端点路径: 独立的 unlink 端点, 不是编辑批次的 PUT', () => {
    expect(unlinkSourceIndividualPath('F006', 'MB-1')).toBe('/F006/material-batches/MB-1/source-individual/unlink');
  });
});

const RAW = readFileSync(resolve(process.cwd(), 'src/views/warehouse/materials/list.vue'), 'utf8');
const SOURCE = RAW
  .replace(/<!--[\s\S]*?-->/g, '')
  .replace(/\/\*[\s\S]*?\*\//g, '')
  .replace(/^\s*\/\/.*$/gm, '');

describe('批次编辑弹窗接上了解除入口', () => {
  it('解除走独立端点 + 原因校验, 不是往编辑提交体里塞空串', () => {
    expect(SOURCE).toContain('post(unlinkSourceIndividualPath(factoryId.value, batchId), { reason })');
    expect(SOURCE).toContain('inputValidator: validateUnlinkReason');
    // 编辑白名单里不许出现 sourceIndividualId (null=不改 / 空白=400 的语义不动)
    const whitelist = SOURCE.match(/const EDITABLE_FIELDS_ON_UPDATE = \[([\s\S]*?)\] as const;/);
    expect(whitelist, '没抓到编辑白名单声明').not.toBeNull();
    expect(whitelist![1]).not.toContain('sourceIndividualId');
  });

  it('按钮只在「编辑已挂个体的批次」且有写权限时出现', () => {
    expect(SOURCE).toMatch(/<el-form-item v-if="editingId && editingSourceIndividualId" label="来源个体">/);
    const button = SOURCE.match(/<el-button[^>]*data-testid="batch-source-individual-unlink"[^>]*>/);
    expect(button, '没抓到解除按钮').not.toBeNull();
    expect(button![0]).toContain('v-if="canWrite"');
    expect(button![0]).toContain('@click="handleUnlinkSourceIndividual"');
  });

  it('编辑时从行数据带出个体 id', () => {
    expect(SOURCE).toContain('editingSourceIndividualId.value = row.sourceIndividualId ? String(row.sourceIndividualId) : null;');
  });
});
