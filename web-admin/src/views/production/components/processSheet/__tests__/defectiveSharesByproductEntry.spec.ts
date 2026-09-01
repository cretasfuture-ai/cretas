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
 * ⚠️ 2026-09-01 第二次订正（owner「都要做的反正」）：回料也搬进这块区**显示**，
 *    但它**不是副产明细** —— 载体仍是自己的 MultiOutputLine，提交负载逐字不变。
 *    「一起填写」是显示层，「底层分类」是载体层，两头各有一条判据钉着。
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

  it('🔴 回料**不**进副产明细 —— 它保留自己的产出条目', () => {
    // 🔴 2026-09-01 owner 说「都要做」之后，回料的**显示位置**搬进了同一块区，
    //    但它的**载体没变**：仍是一条 MultiOutputLine，不是 BoundByproductLine。
    //    这条判据钉的正是那个没变的部分 —— 把它转成副产明细会让它走
    //    `material_batches`，移动加权成本直接丢。
    const result = partitionOutputPorts([mainPort, reworkPort]);
    expect(result.rowPorts.map((p) => p.workflowPortId).sort()).toEqual(['p-main', 'p-rew']);
    expect(result.inlineByproductPorts).toEqual([]);
  });

  it('🔴 三类填在同一处：回料按渲染搬进那块区，而**提交负载逐字不变**', () => {
    // owner 2026-09-01：「不良品产出和副产是一起填写的……底层逻辑会分类」。
    // 「一起填写」是显示层的事，「底层分类」是载体层的事 —— 这条同时钉两头。
    const TABLE = readFileSync(resolve(__dirname, '../ProcessDataTable.vue'), 'utf8');

    // ① 显示层：回料从行里挑出来挂到宿主行上
    expect(TABLE).toContain('const reworkViews = all.filter((view) => isReworkLine(view.line));');
    expect(TABLE).toContain('inlineReworkViews: reworkViews');
    // 没有主产出行时不静默丢掉
    expect(TABLE).toContain('if (rowViews.length === 0) return reworkViews;');

    // ② 载体层，本条要害：`row.multiOutputs` 是提交/校验/批次回填的唯一来源，
    //    搬动只许发生在 outputViews 里，⛔ 不许有人去改那个数组。
    const viewsFn = TABLE.slice(
      TABLE.indexOf('function outputViews(row: SheetRow)'),
      TABLE.indexOf('function outputViewsAll(row: SheetRow)'),
    );
    expect(viewsFn).not.toContain('row.multiOutputs =');
    expect(viewsFn).not.toContain('.splice(');
    expect(viewsFn).not.toContain('BoundByproductLine');

    // ③ 界面：回料条目只给数量，⛔ 不给「回收单价」（它不是回收物）
    const reworkBlock = OUTPUT_TABLE.slice(
      OUTPUT_TABLE.indexOf('data-testid="rework-inline-list"'),
      OUTPUT_TABLE.indexOf('成本分摊比例与重量提示'),
    );
    expect(reworkBlock).toContain('rw.line.quantity');
    expect(reworkBlock).not.toContain('unitPrice');

    // ④ 🔴 阴性对照：回料那块必须是**独立 v-if**。接进副产的 v-else 链会让
    //    「有回料 + 手填副产」这一种组合里的手填字段整块消失（本轮实测过一次）。
    expect(OUTPUT_TABLE).toContain('v-if="view.inlineReworkViews.length > 0"');
    expect(reworkBlock).not.toContain('v-else');
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
