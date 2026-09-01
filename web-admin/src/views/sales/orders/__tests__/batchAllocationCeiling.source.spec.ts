import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 缺陷 41 (2026-09-02 owner 拍板): 批次分配合计校验从「必须等于发货量」
 * 改为「不得超过发货量」——短发(实际可用 < 计划发货量, 食品厂常态, 例如
 * 车装不下 / 生产损耗)不再被分配对话框堵死；超分配(分配 > 发货量)仍然拒绝,
 * 那是真错误。
 *
 * 六扇门实测: DLV-20260902-6382 → -01, 计划发 1.00, 实际只有 0.98 可用,
 * 原来的「分配合计 0.98 必须等于发货量 1」把用户堵在了这一步。
 *
 * 权威判据在后端 SalesDeliveryBatchAllocationServiceImpl (行为测试见
 * SalesDeliveryBatchAllocationServiceWarehouseTest 的
 * allocateBatches_partialAllocationBelowDeliveredQuantity_isAccepted /
 * allocateBatches_overAllocationAboveDeliveredQuantity_isStillRejected)。
 * 这里钉的是前端提交前的镜像校验 —— 两边必须同步, 否则用户在对话框里通过了,
 * 提交给后端却仍然被拒 (半截修复, 与本文件 measurement-and-wiring 硬约束 8 同源)。
 */
const source = readFileSync(resolve(__dirname, '..', 'detail.vue'), 'utf8');

describe('批次分配合计: 不得超过发货量(允许短发, 拒绝超分配)', () => {
  it('提交校验表达式已经从等值改为"超过"(总量 - 发货量 > 容差)', () => {
    expect(source, '旧的等值校验(Math.abs(total-deliveredQuantity)>0.001)不应再守着提交路径')
      .not.toMatch(/Math\.abs\(total - item\.deliveredQuantity\) > 0\.001\)/);
    expect(source, '新校验必须是 total - deliveredQuantity > 容差 这一形状')
      .toMatch(/if \(total - item\.deliveredQuantity > 0\.001\) \{/);
  });

  it('数值边界: 0.98/1.00(短发) 放行, 1.02/1.00(超分配) 仍拒绝, 1.00/1.00(精确) 放行', () => {
    // 直接从源码里抠出真实校验表达式的字面量形状, 而不是在测试里另写一份可能漂移的逻辑 ——
    // 抠不出来就说明源码已经变了, 下面这条断言本身要先红, 逼着这份测试跟着源码同步更新。
    const exprMatch = source.match(/if \(total - item\.deliveredQuantity > (0\.001)\) \{/);
    expect(exprMatch, '抠不出校验表达式 —— detail.vue 的写法变了, 需要同步这份测试').not.toBeNull();
    const tolerance = Number(exprMatch![1]);

    const wouldReject = (total: number, deliveredQuantity: number) =>
      total - deliveredQuantity > tolerance;

    expect(wouldReject(0.98, 1)).toBe(false); // 短发: 分配 < 发货量 -> 放行
    expect(wouldReject(1.02, 1)).toBe(true); // 阴性对照: 超分配 -> 仍拒绝
    expect(wouldReject(1, 1)).toBe(false); // 精确匹配的老场景不能被这次改动破坏
  });

  it('拒绝提示文案说"超过...不能大于", 不再说"必须等于"', () => {
    // ⚠️ 不能整份文件禁 "必须等于发货量" —— /ship endpoint 那条注释(描述发货时的
    // isFullyAllocated 语义, 本次故意没改, 见 SalesDeliveryBatchAllocationServiceImpl
    // isFullyAllocated 上的说明)仍然合法含有这句话。只钉这条具体的拒绝提示模板串。
    expect(source).not.toContain('分配合计 ${total} 必须等于发货量');
    expect(source).toContain('超过发货量 ${item.deliveredQuantity}，分配量不能大于发货量');
  });

  it('对话框说明文案改为"不能超过", 不再说"必须等于"', () => {
    expect(source).not.toContain("每行 '分配合计' 必须等于发货数量才能提交");
    expect(source).toContain('不能超过发货数量才能提交');
  });

  it('假的"需先重新分配批次保证总量匹配"警告已从仓库确认弹窗删除', () => {
    const confirmVue = readFileSync(
      resolve(__dirname, '..', '..', '..', 'warehouse', 'shipments', 'list.vue'),
      'utf8',
    );
    expect(confirmVue, '这句话在实拍现场被证伪: 不分配直接确认, FIFO 自动记录照样成功')
      .not.toContain('需先到"销售订单 → 发货记录"重新分配批次保证总量匹配');
    // 阴性对照的另一半: 真实发生的事(FIFO 自动记录)必须仍然被讲出来, 不能连真话一起删掉。
    expect(confirmVue).toContain('确认时按 FIFO 自动记录');
    expect(confirmVue).toContain('FIFO 自动选择批次并记录');
  });

  it('出货管理页的三步引导文案完整点出「新建分批发运」, 不再只说两步', () => {
    const warehouseListVue = readFileSync(
      resolve(__dirname, '..', '..', '..', 'warehouse', 'shipments', 'list.vue'),
      'utf8',
    );
    expect(warehouseListVue, '旧文案漏了中间那一步, 照做的人会建完母单对着空队列发呆')
      .not.toContain('新发货请从销售订单创建发货单，并在左侧待确认页完成仓库确认');
    expect(warehouseListVue).toContain('创建发货单');
    expect(warehouseListVue).toContain('新建分批发运');
    expect(warehouseListVue).toContain('仓库确认');
  });
});
