import { buildDetailRows, formatColumnName, getListCellValue, getListDisplayKeys } from '../richListPresentation';

describe('material inventory table', () => {
  const batch = {
    id: 'batch-id', factoryId: 'F006', batchNumber: 'B-1', materialName: '排腿',
    materialCode: 'RAW-1', materialCategory: '肉类', materialMainCategory: '原料',
    currentQuantity: 0, quantity: 99, availableQuantity: 25, unit: 'kg', status: 'USED_UP',
  };

  it('shows the actual stock field instead of DTO metadata or available quantity', () => {
    expect(getListDisplayKeys(batch)).toEqual(['batchNumber', 'materialName', 'currentQuantity', 'unit', 'status']);
    expect(getListCellValue(batch, 'currentQuantity')).toBe(0);
    expect(getListCellValue(batch, 'status')).toBe('已用完');
  });

  it('keeps missing stock explicit and does not substitute a different quantity', () => {
    const missing = { ...batch, currentQuantity: null };
    expect(getListDisplayKeys(missing)).toContain('currentQuantity');
    expect(getListCellValue(missing, 'currentQuantity')).toBeNull();
  });

  it('uses the batch unit instead of the material type default', () => {
    const packaged = { ...batch, quantityUnit: '盒', unit: 'kg' };
    expect(getListDisplayKeys(packaged)).toEqual(['batchNumber', 'materialName', 'currentQuantity', 'quantityUnit', 'status']);
    expect(getListCellValue(packaged, 'quantityUnit')).toBe('盒');
  });

  it('supports legacy quantities, packaging units and supplied Chinese status', () => {
    const legacy = { batchNumber: 'B-2', materialName: '成品盒', quantity: 12, unit: '盒', statusDisplayName: '可用', status: 'AVAILABLE' };
    expect(getListDisplayKeys(legacy)).toEqual(['batchNumber', 'materialName', 'quantity', 'unit', 'statusDisplayName']);
    expect(getListCellValue(legacy, 'unit')).toBe('盒');
    expect(getListCellValue(legacy, 'statusDisplayName')).toBe('可用');
  });

  it('preserves other business tables and unknown status values', () => {
    const order = { id: 3, orderNumber: 'SO-1', customerId: 9, status: 'SHIPPED', quantity: 3, unit: '箱', totalAmount: 12, remark: 'extra' };
    expect(getListDisplayKeys(order)).toEqual(['orderNumber', 'status', 'quantity', 'unit', 'totalAmount']);
    expect(getListCellValue(order, 'status')).toBe('SHIPPED');
    expect(getListCellValue({ ...batch, status: 'NEW_STATUS' }, 'status')).toBe('NEW_STATUS');
  });
});

describe('中文键 (后端已给人话标签) 不被当成未知英文键藏掉 (organizer 审 #954, 2026-10-07)', () => {
  it('列表 / 统计 / 详情: 中文键原样显示, 没标签的英文内部字段仍隐藏', () => {
    expect(formatColumnName('今天的打卡')).toBe('今天的打卡');
    expect(formatColumnName('internalFoo')).toBe('');
    expect(getListDisplayKeys({ 工序: '卤制', 状态: '进行中', internalFoo: 'x' })).toEqual(['工序', '状态']);
    const rows = buildDetailRows({ 剩余数量: '0.8 kg', internalFoo: 'x', message: '重复的一句话' });
    expect(rows.map((r) => r.label)).toEqual(['剩余数量']);
  });
});

describe('A3: 列表单元格与详情卡用同一份状态 / 结果中文 (Codex 本机验收 2026-10-08)', () => {
  const inspection = { result: 'PENDING', qualityGrade: 'B', passRate: 94, inspectionDate: '2026-08-18', notes: null };

  it('质检结果码: 列表与详情翻成同一个中文', () => {
    for (const [code, zh] of [['PENDING', '待检验'], ['FAIL', '不合格'], ['PASS', '合格'], ['CONDITIONAL', '有条件放行']]) {
      const row = { ...inspection, result: code };
      expect(getListCellValue(row, 'result')).toBe(zh);
      expect(buildDetailRows(row).find((r) => r.key === 'result')?.value).toBe(zh);
    }
  });

  it('通用状态码在非库存列表里也翻 (以前只翻库存批次)', () => {
    expect(getListCellValue({ processName: '卤制', status: 'IN_PROGRESS' }, 'status')).toBe('进行中');
    expect(getListCellValue({ orderNumber: 'SO-1', status: 'COMPLETED' }, 'status')).toBe('已完成');
  });

  it('阴性对照: status 键上的 PENDING 不猜 (工序里是待开始), 认不出的码原样显示、不藏', () => {
    expect(getListCellValue({ processName: '卤制', status: 'PENDING' }, 'status')).toBe('PENDING');
    expect(getListCellValue({ ...inspection, result: 'WEIRD_CODE' }, 'result')).toBe('WEIRD_CODE');
    expect(getListCellValue({ ...inspection, qualityGrade: 'B' }, 'qualityGrade')).toBe('B');
  });
});
