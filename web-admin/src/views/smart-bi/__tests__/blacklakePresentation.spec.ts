import { describe, expect, it } from 'vitest';
import { formatBlacklakeNumber, localizeBlacklakeField, localizeBlacklakeText, readableEvidenceEntries } from '../blacklakePresentation';

describe('blacklake presentation helpers', () => {
  it('keeps readable labels and caps numbers at two decimals', () => {
    expect(localizeBlacklakeField('recognizedCost')).toBe('来源金额');
    expect(localizeBlacklakeText('source_zero_cost')).toBe('来源金额为零');
    expect(localizeBlacklakeText('42')).toBe('来源系统未提供可读说明');
    expect(localizeBlacklakeText('GD202610290011 产品A1.2345 批次1.2345-001')).toBe('GD202610290011 产品A1.2345 批次1.2345-001');
    expect(localizeBlacklakeText('成本 1.2345 元')).toBe('成本 1.23 元');
    expect(localizeBlacklakeText('单位成本 60.2421来源金额/KG，成品量 1,431.6000KG')).toBe('单位成本 60.24来源金额/KG，成品量 1,431.60KG');
    expect(localizeBlacklakeText('确认是否漏估值')).toBe('确认材料当时是否没有记价，或确实为零成本');
    expect(localizeBlacklakeText('当时估值依据')).toBe('当时的计价依据');
    expect(localizeBlacklakeText('最终良品量缺失或非正数')).toBe('成品良品量没有提供，或小于等于零');
    expect(localizeBlacklakeText('领退料与工单关联三元组')).toBe('核对领退料的工单编码、业务单类型和关联编号');
    expect(localizeBlacklakeText('核对归集口径；材料归集完整；归集完整性')).toBe('核对材料和工资有没有记全、记对；材料是否记全；记录是否完整');
    expect(formatBlacklakeNumber(12.3456)).toBe('12.35');
    expect(formatBlacklakeNumber(null)).toBe('未提供');
  });

  it('does not leak raw IDs or stringify nested evidence objects', () => {
    expect(readableEvidenceEntries({ recognizedCost: 12.345, projectId: '8c1f4c6e-4c46-4be5-9343-62c7b81b5c8d', raw: { orderId: 9 } })).toEqual([
      { label: '来源金额', value: '12.35' },
      { label: '来源字段', value: '已提供结构化证据，页面不展开内部编码' },
    ]);
  });

  it('renders nested cost evidence with zero versus missing values and keeps business order codes', () => {
    const entries = readableEvidenceEntries({
      lines: [{ material: '鸡胸肉', orderCode: 'MO-2026-001', quantity: 0, unit: 'kg', unitPrice: null, recognizedCost: 0, id: 'internal-line-id' }],
      'materialLinesDetail.recognizedCost': 4.567,
    });
    expect(entries).toEqual([
      { label: '领退料明细', value: '材料 鸡胸肉 · 领退料单编码 MO-2026-001 · 单位 kg · 数量 0.00 · 来源单价 未提供 · 来源金额 0.00' },
      { label: '材料明细中的来源金额', value: '4.57' },
    ]);
  });

  it('presents numeric status codes as workflow labels and keeps thresholds as labeled decimals', () => {
    expect(readableEvidenceEntries({ status: 20, threshold: 0.2 })).toEqual([
      { label: '当前状态', value: '已完工' },
      { label: '提醒标准', value: '20.00%' },
    ]);
  });
  it('identifies the exact highest and lowest cost orders with readable percentages', () => {
    expect(readableEvidenceEntries({ baselineProject:'GD202611010001', comparisonProject:'GD202610300015', spread:0.250084, sampleSize:4, comparabilityVerified:false })).toEqual([
      {label:'最低成本工单',value:'GD202611010001'},
      {label:'最高成本工单',value:'GD202610300015'},
      {label:'最高比最低高出',value:'25.01%'},
      {label:'参与比较的工单数',value:'4.00'},
      {label:'产品规格与工艺是否已人工核实一致',value:'否'},
    ]);
  });
});
