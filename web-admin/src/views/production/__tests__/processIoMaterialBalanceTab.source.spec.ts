import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 缺口 #07 物料平衡表: 加在「工序级投入产出对比」页上, 不另造一页。
 * 钉住: 调的是后端那一份计算 (不在前端重算)、按日期查、单位不可比不显示数字、阈值取自后端。
 */
const src = readFileSync(resolve(__dirname, '../ProcessIOComparison.vue'), 'utf-8');

describe('ProcessIOComparison 物料平衡表', () => {
  it('读后端 material-balance 接口并带报工日期, 不在前端算差额', () => {
    expect(src).toContain('/production/yield/material-balance');
    expect(src).toContain('startDate: balanceDate.value');
    expect(src).not.toMatch(/input\s*-\s*output/);
  });

  it('五种状态都有标签, 单位不可比与未登记投入如实标出', () => {
    for (const label of ['已对平', '差额偏大', '差额过大', '单位不可比', '未登记投入']) {
      expect(src).toContain(label);
    }
  });

  it('阈值说明来自后端返回的 warnPct / redPct, 不写死 5/15', () => {
    expect(src).toContain('balance.warnPct');
    expect(src).toContain('balance.redPct');
  });

  it('数量缺失显示破折号, 不显示 0', () => {
    expect(src).toContain("v == null ? '—'");
  });
});
