import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import {
  boundOutputRoleLabel,
  boundOutputRoleOf,
  partitionOutputPorts,
} from '../processSheetOutputs';

const OUTPUT_TABLE = readFileSync(resolve(__dirname, '../ProcessOutputTable.vue'), 'utf8');

/**
 * owner 2026-09-01 原话：
 * 「不良品产出和副产是一起填写的，只是在 workflow 里面会标注产出的副产是作为不良品
 *   还是作为回料保存，所以我说文字上面就是不良品/副产，但是在底层逻辑会分类」。
 *
 * ⇒ 判据两条，缺一条这个说法就没落实：
 *   ① **一起填**：不良品端口和副产端口归到同一块录入区，⛔ 不给不良品另开一块；
 *   ② **底层分类**：每条带角色，界面上分得出来。
 *
 * ⚠️ 回料**不在**这块区里：它独立成行（要填产出数量、要带批次和移动加权成本进下一批）。
 *    这一点单列一条阴性对照钉住 —— 把它顺手并进来会改掉一个已经在跑的行为。
 */
describe('不良品与副产共用同一块录入区，靠角色分类', () => {
  const byproductPort = { workflowPortId: 'p-byp', byproduct: true, materialName: '肥油' };
  const defectivePort = { workflowPortId: 'p-def', defective: true, materialName: '金检剔出' };
  const reworkPort = { workflowPortId: 'p-rew', materialName: '回料' };   // 后端不发 byproduct/defective
  const mainPort = { workflowPortId: 'p-main', materialName: '主产出' };

  it('① 不良品端口和副产端口一起进「按需填写」区，不独立成行', () => {
    const result = partitionOutputPorts([mainPort, byproductPort, defectivePort]);
    expect(result.rowPorts.map((p) => p.workflowPortId)).toEqual(['p-main']);
    expect(result.inlineByproductPorts.map((p) => p.workflowPortId).sort())
      .toEqual(['p-byp', 'p-def']);
    expect(result.hostPortId).toBe('p-main');
  });

  it('② 每条带角色，且角色有各自的文案', () => {
    expect(boundOutputRoleOf(byproductPort)).toBe('BYPRODUCT');
    expect(boundOutputRoleOf(defectivePort)).toBe('DEFECTIVE');
    expect(boundOutputRoleLabel('BYPRODUCT')).toBe('副产');
    expect(boundOutputRoleLabel('DEFECTIVE')).toBe('不良品');
    // ⛔ 被 owner 否掉的两个说法，一个都不许当角色文案
    for (const role of ['BYPRODUCT', 'DEFECTIVE'] as const) {
      expect(boundOutputRoleLabel(role)).not.toContain('判废');
      expect(boundOutputRoleLabel(role)).not.toContain('不入库');
    }
  });

  it('🔴 阴性对照：回料**不**进这块区 —— 它独立成行', () => {
    // 没有这条，把判据写成「所有特殊产出都内联」也能让上面两条绿，
    // 而那会悄悄改掉回料已经在跑的报工形态。
    const result = partitionOutputPorts([mainPort, reworkPort]);
    expect(result.rowPorts.map((p) => p.workflowPortId).sort()).toEqual(['p-main', 'p-rew']);
    expect(result.inlineByproductPorts).toEqual([]);
  });

  it('🔴 阴性对照：都没标记时这块区是空的 —— 证明上面的绿不是「什么都内联」', () => {
    const result = partitionOutputPorts([mainPort]);
    expect(result.inlineByproductPorts).toEqual([]);
    expect(result.hostPortId).toBeNull();
    expect(result.orphanNotice).toBeNull();
  });

  it('只有不良品、没有主产出时不静默 —— 提升成行并说清原因', () => {
    const result = partitionOutputPorts([defectivePort]);
    expect(result.rowPorts.map((p) => p.workflowPortId)).toEqual(['p-def']);
    expect(result.orphanNotice).toContain('不良品/副产');
    expect(result.orphanNotice).toContain('没有主产出');
  });

  it('界面上这块区叫「不良品/副产」，且逐条打角色 tag', () => {
    expect(OUTPUT_TABLE).toContain('按需填写：本工序不良品/副产与成本分摊');
    expect(OUTPUT_TABLE).toContain('按需填写：不良品/副产与成本分摊');
    expect(OUTPUT_TABLE).toContain('boundOutputRoleLabel(bp.role)');
    expect(OUTPUT_TABLE).toContain("bp.role === 'DEFECTIVE' ? 'danger' : 'info'");
    // ⛔ 旧的写死 tag 不许留着 —— 留着就是「有两份，一份会漂」
    expect(OUTPUT_TABLE).not.toContain('<el-tag size="small" type="info">副产</el-tag>');
  });
});
