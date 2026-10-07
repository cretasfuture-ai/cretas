import React from 'react';
import { render, screen } from '@testing-library/react-native';
import { detectRichData, RichContentRenderer } from '../RichContentRenderer';

it('renders stock quantity, batch unit and Chinese status through the actual AI table', () => {
  const data = detectRichData({
    content: [{
      id: 'B-1', factoryId: 'F006', batchNumber: 'MT-1', materialName: '成品盒',
      materialCode: 'BOX', materialCategory: '包装', materialMainCategory: '辅料',
      currentQuantity: 0, quantityUnit: '盒', unit: 'kg', status: 'USED_UP',
    }], totalElements: 1, currentPage: 1, totalPages: 1, hasMore: false,
  });
  expect(data).toBeDefined();
  render(<RichContentRenderer data={data!} />);
  expect(screen.getByText('账面数量')).toBeTruthy();
  expect(screen.getByText('0')).toBeTruthy();
  expect(screen.getByText('盒')).toBeTruthy();
  expect(screen.getByText('已用完')).toBeTruthy();
  expect(screen.queryByText('kg')).toBeNull();
  expect(screen.queryByText('Material Category')).toBeNull();
});

// ==================== 写操作结果卡 (2026-10-07) ====================
// prod: 领用成功后结果卡显示 Remaining Quantity / Material Type Id / Batch Id / AVAILABLE 等英文与内部编号。
// 数据形状取自 MaterialBatchUseTool.doExecute 的返回 (键名一字不差)。

const BATCH_USE_RESULT = {
  batchId: 'b3f1c2d4-1111-4222-8333-444455556666',
  batchNumber: 'MT-20260829-9687',
  materialTypeId: 'a9f1c2d4-1111-4222-8333-444455556666',
  consumedQuantity: 0.1,
  remainingQuantity: 0.9,
  unit: 'kg',
  status: 'AVAILABLE',
  productionPlanId: null,
};

describe('写操作结果卡: 给一线看的中文', () => {
  it('缺值显示暂无数据，真实 0 保留，精度不被压成 0.00', () => {
    const data = detectRichData({ batchId: 'b-1', batchNumber: 'MT-1', remainingQuantity: null, consumedQuantity: 0, unit: 'kg', status: 'AVAILABLE' });
    render(<RichContentRenderer data={data!} />);
    expect(screen.getByText('暂无数据')).toBeTruthy();
    expect(screen.getByText('0 kg')).toBeTruthy();
    expect(screen.getByText('可用')).toBeTruthy();
    expect(screen.queryByText('0.00 kg')).toBeNull();
  });

  it('领用成功: 中文标签 + 数量带单位 + 状态翻成中文, 不显示内部编号和英文标签', () => {
    const data = detectRichData({ ...BATCH_USE_RESULT });
    expect(data?.dataType).toBe('DETAIL');
    render(<RichContentRenderer data={data!} />);

    expect(screen.getByText('剩余数量')).toBeTruthy();
    expect(screen.getByText('0.9 kg')).toBeTruthy();
    expect(screen.getByText('已领用数量')).toBeTruthy();
    expect(screen.getByText('0.1 kg')).toBeTruthy();
    expect(screen.getByText('批次号')).toBeTruthy();
    expect(screen.getByText('MT-20260829-9687')).toBeTruthy();
    expect(screen.getByText('状态')).toBeTruthy();
    expect(screen.getByText('可用')).toBeTruthy();

    // 阴性: 英文标签 / 内部编号 / 英文状态码 / 单独的「单位」行都不在
    for (const leaked of ['Remaining Quantity', 'Consumed Quantity', 'Batch Id', 'Material Type Id',
      'Production Plan Id', 'AVAILABLE', '单位']) {
      expect(screen.queryByText(leaked)).toBeNull();
    }
    expect(screen.queryByText(/b3f1c2d4/)).toBeNull();
    expect(screen.queryByText(/a9f1c2d4/)).toBeNull();
  });

  it('对照: 已有中文标签的字段原样保留 (计划编号 / 产品 / 计划数量 / 进行中 / 创建时间)', () => {
    const data = detectRichData({
      planId: 'p-1', planNumber: 'PP-20261007-0001', productName: '鸡胸肉', plannedQuantity: 100,
      status: 'IN_PROGRESS', createdAt: '2026-10-07 09:00',
    });
    expect(data?.dataType).toBe('DETAIL');
    render(<RichContentRenderer data={data!} />);
    expect(screen.getByText('计划编号')).toBeTruthy();
    expect(screen.getByText('PP-20261007-0001')).toBeTruthy();
    expect(screen.getByText('产品')).toBeTruthy();
    expect(screen.getByText('鸡胸肉')).toBeTruthy();
    expect(screen.getByText('计划数量')).toBeTruthy();
    expect(screen.getByText('100')).toBeTruthy(); // 没有单位字段时数量不凭空加单位
    expect(screen.getByText('进行中')).toBeTruthy();
    expect(screen.getByText('创建时间')).toBeTruthy();
    expect(screen.queryByText('Plan Id')).toBeNull();
  });

  it('认不出的英文状态码不显示这一行 (不回退成英文); 本来就是中文的状态原样显示; statusDisplayName 优先且不重复', () => {
    const unknown = detectRichData({ batchId: 'b-1', batchNumber: 'MT-1', status: 'WEIRD_CODE' });
    const view = render(<RichContentRenderer data={unknown!} />);
    expect(screen.getByText('MT-1')).toBeTruthy();
    expect(screen.queryByText('状态')).toBeNull();
    expect(screen.queryByText('WEIRD_CODE')).toBeNull();
    view.unmount();

    const zh = detectRichData({ batchId: 'b-1', batchNumber: 'MT-1', status: '已用完' });
    const view2 = render(<RichContentRenderer data={zh!} />);
    expect(screen.getByText('已用完')).toBeTruthy();
    view2.unmount();

    const both = detectRichData({ batchId: 'b-1', batchNumber: 'MT-1', status: 'AVAILABLE', statusDisplayName: '可用' });
    render(<RichContentRenderer data={both!} />);
    expect(screen.getAllByText('状态')).toHaveLength(1);
    expect(screen.getByText('可用')).toBeTruthy();
  });

  it('没有任何中文标签可显示时不画空卡', () => {
    const data = detectRichData({ batchId: 'b-1', someInternalFlag: 'x' });
    expect(data?.dataType).toBe('DETAIL');
    const view = render(<RichContentRenderer data={data!} />);
    expect(view.toJSON()).toBeNull();
  });

  it('质检登记 / 打卡 / 周转框 的结果形状不触发详情卡 (没有英文字段名可漏); 质检标记已检验触发的卡也是中文', () => {
    // QualityResultSubmitTool / QualityCheckCreateTool: 键里没有 batchId / orderId 等触发键
    expect(detectRichData({
      inspectionId: 'i-1', productionBatchId: 'p-1', result: 'PASS', passRate: 98.5,
      qualityGrade: 'A', inspectionDate: '2026-10-07', isUpdate: false,
    })).toBeUndefined();
    // ClockInTool / ReusableContainer*: buildSimpleResult → {success, message, data:{...}} 嵌套, 同样不出卡
    expect(detectRichData({ success: true, message: '签到打卡成功', data: { id: 1, clockInTime: '09:00' } })).toBeUndefined();
    expect(detectRichData({ success: true, message: '归还入库', data: { containerId: 'c-1', quantity: 5 } })).toBeUndefined();
    // QualityBatchMarkInspectedTool: 带 batchId 会出卡
    const marked = detectRichData({
      batchId: 'p-1', markedBy: 7, markedAt: '2026-10-07T09:00:00', status: 'INSPECTED',
    });
    render(<RichContentRenderer data={marked!} />);
    expect(screen.getByText('标记时间')).toBeTruthy();
    expect(screen.getByText('已检验')).toBeTruthy();
    expect(screen.queryByText('Marked By')).toBeNull();
    expect(screen.queryByText('Marked At')).toBeNull();
    expect(screen.queryByText('INSPECTED')).toBeNull();
  });
});
