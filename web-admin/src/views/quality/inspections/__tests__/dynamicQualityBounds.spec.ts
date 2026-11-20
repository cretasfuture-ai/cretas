import { mount } from '@vue/test-utils';
import ElementPlus from 'element-plus';
import { describe, expect, it, vi } from 'vitest';
import DynamicQualityInspectionForm from '../DynamicQualityInspectionForm.vue';
import {
  formatDynamicItemStandard,
  itemIsOutOfRange,
  type DynamicQualityForm,
  type DynamicQualityItem,
} from '../dynamicQualityInspection';

vi.mock('@/api/request', () => ({ get: vi.fn(), post: vi.fn() }));

// 形状取自后端 DynamicQualityFormDTO.Item: valueType 为 NUMERIC/RANGE/TEXT/BOOLEAN,
// 上下限字段统一叫 effectiveMinValue / effectiveMaxValue (BigDecimal -> JSON number)。
function item(overrides: Partial<DynamicQualityItem>): DynamicQualityItem {
  return {
    bindingId: 'b1',
    itemId: 'i1',
    itemVersion: 1,
    label: '净含量',
    valueType: 'NUMERIC',
    unit: 'g',
    effectiveIsRequired: false,
    ...overrides,
  };
}

describe('formatDynamicItemStandard', () => {
  it('RANGE 双边界: 下限 ~ 上限 + 单位', () => {
    expect(formatDynamicItemStandard(item({ valueType: 'RANGE', unit: '°C', effectiveMinValue: 0, effectiveMaxValue: 4 }))).toBe('0 ~ 4 °C');
  });

  it('NUMERIC 双边界与 RANGE 一致 (净含量 98 ~ 102 g)', () => {
    expect(formatDynamicItemStandard(item({ effectiveMinValue: 98, effectiveMaxValue: 102 }))).toBe('98 ~ 102 g');
  });

  it('只有下限: ≥ 下限, 不出现 undefined/null', () => {
    const text = formatDynamicItemStandard(item({ effectiveMinValue: 98, effectiveMaxValue: null }));
    expect(text).toBe('≥ 98 g');
    expect(text).not.toMatch(/undefined|null/);
  });

  it('只有上限: ≤ 上限 (字段缺省即 undefined)', () => {
    const text = formatDynamicItemStandard(item({ effectiveMaxValue: 102 }));
    expect(text).toBe('≤ 102 g');
    expect(text).not.toMatch(/undefined|null/);
  });

  it('边界为 0 不被当成没有', () => {
    expect(formatDynamicItemStandard(item({ effectiveMinValue: 0 }))).toBe('≥ 0 g');
  });

  it('无单位时不留尾随空格', () => {
    expect(formatDynamicItemStandard(item({ unit: null, effectiveMinValue: 1, effectiveMaxValue: 2 }))).toBe('1 ~ 2');
  });

  it('没有任何边界: 空串 (由调用方只显示单位)', () => {
    expect(formatDynamicItemStandard(item({}))).toBe('');
    expect(formatDynamicItemStandard(item({ valueType: 'RANGE', effectiveMinValue: null, effectiveMaxValue: null }))).toBe('');
  });

  it('valueType 大小写/空白不影响 (后端判定时 trim + 转大写)', () => {
    expect(formatDynamicItemStandard(item({ valueType: ' numeric ', effectiveMinValue: 98, effectiveMaxValue: 102 }))).toBe('98 ~ 102 g');
  });
});

describe('itemIsOutOfRange 与后端同口径: NUMERIC 也按上下限判定', () => {
  const numeric = item({ effectiveMinValue: 98, effectiveMaxValue: 102 });
  it('NUMERIC 超出范围', () => {
    expect(itemIsOutOfRange(numeric, '97')).toBe(true);
    expect(itemIsOutOfRange(numeric, '103')).toBe(true);
  });
  it('NUMERIC 范围内 / 边界值 / 空值不报', () => {
    expect(itemIsOutOfRange(numeric, '100')).toBe(false);
    expect(itemIsOutOfRange(numeric, '98')).toBe(false);
    expect(itemIsOutOfRange(numeric, '')).toBe(false);
  });
  it('TEXT 不按数值判定', () => {
    expect(itemIsOutOfRange(item({ valueType: 'TEXT', effectiveMinValue: 98 }), '1')).toBe(false);
  });
});

describe('质检表单渲染 (产品真实入口)', () => {
  const form: DynamicQualityForm = {
    batchId: '11003',
    productTypeId: 'SKU-QA',
    productName: 'QA成品',
    standardRevision: 'rev-1',
    items: [
      item({ bindingId: 'b-temp', itemId: 'i-temp', label: '温度', valueType: 'RANGE', unit: '°C', effectiveMinValue: 0, effectiveMaxValue: 4 }),
      item({ bindingId: 'b-net', itemId: 'i-net', label: '净含量', valueType: 'NUMERIC', unit: 'g', effectiveMinValue: 98, effectiveMaxValue: 102 }),
      item({ bindingId: 'b-low', itemId: 'i-low', label: '只设下限', valueType: 'NUMERIC', unit: 'g', effectiveMinValue: 98 }),
      item({ bindingId: 'b-none', itemId: 'i-none', label: '无边界', valueType: 'NUMERIC', unit: 'kg' }),
    ],
  };

  function mountForm() {
    return mount(DynamicQualityInspectionForm, {
      props: { factoryId: 'F006', batchId: '11003', batchNumber: '11003', productName: 'QA成品', form },
      global: { plugins: [ElementPlus] },
    });
  }

  it('NUMERIC 项显示上下限, 与 RANGE 项一致', () => {
    const metas = mountForm().findAll('.dynamic-field-meta').map((w) => w.text());
    expect(metas).toHaveLength(4);
    expect(metas[0]).toContain('0 ~ 4 °C');
    expect(metas[1]).toContain('98 ~ 102 g');
    expect(metas[2]).toContain('≥ 98 g');
    expect(metas[3]).toContain('kg');
    expect(metas.join('|')).not.toMatch(/undefined|null/);
  });

  it('NUMERIC 项用数字输入框, 填超范围值时出现超范围提示', async () => {
    const wrapper = mountForm();
    const inputs = wrapper.findAll('input');
    expect(inputs[1].attributes('type')).toBe('number');
    await inputs[1].setValue('97');
    expect(wrapper.text()).toContain('实测值超出标准范围');
  });
});
