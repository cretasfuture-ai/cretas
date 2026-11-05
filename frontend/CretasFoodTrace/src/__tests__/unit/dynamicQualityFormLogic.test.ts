import { hasReasonText, itemsNeedingFailReason } from '../../screens/quality-inspector/dynamicQualityForm.logic';
import type { DynamicQualityItem } from '../../types/qualityInspector';

const base = { itemId: 'i', itemVersion: 1 };
const temp: DynamicQualityItem = { ...base, bindingId: 'temp', label: '中心温度', valueType: 'RANGE', effectiveMinValue: 0, effectiveMaxValue: 4, requireNoteOnFail: true };
const sealed: DynamicQualityItem = { ...base, bindingId: 'seal', label: '封口完好', valueType: 'BOOLEAN', effectiveStandardValue: 'true', requireNoteOnFail: true };
const color: DynamicQualityItem = { ...base, bindingId: 'color', label: '色泽', valueType: 'TEXT', effectiveStandardValue: '正常', requireNoteOnFail: true };
const weight: DynamicQualityItem = { ...base, bindingId: 'weight', label: '净含量', valueType: 'NUMERIC', effectiveStandardValue: '100', requireNoteOnFail: true };
const optional: DynamicQualityItem = { ...temp, bindingId: 'opt', label: '感官评分', requireNoteOnFail: false };

describe('动态质检表 · 不合格要写原因', () => {
  it('判不合格且要求原因的项目才列出来, 名称给质检员看', () => {
    expect(itemsNeedingFailReason([temp, optional], { temp: '9', opt: '9' })).toEqual(['中心温度']);
  });

  it('合格、没填的项目不要求原因', () => {
    expect(itemsNeedingFailReason([temp, sealed], { temp: '3', seal: 'true' })).toEqual([]);
    expect(itemsNeedingFailReason([temp], {})).toEqual([]);
    expect(itemsNeedingFailReason([temp], { temp: '   ' })).toEqual([]);
  });

  it('四种题型都按标准判 (与后端同一口径)', () => {
    expect(itemsNeedingFailReason([sealed], { seal: 'false' })).toEqual(['封口完好']);
    expect(itemsNeedingFailReason([color], { color: '发黑' })).toEqual(['色泽']);
    expect(itemsNeedingFailReason([color], { color: '正常' })).toEqual([]);
    expect(itemsNeedingFailReason([weight], { weight: '98' })).toEqual(['净含量']);
    expect(itemsNeedingFailReason([weight], { weight: '100.0' })).toEqual([]);
  });

  it('全空白不算写了原因: 半角、全角、不换行空格、换行', () => {
    for (const blank of [null, undefined, '', '   ', '　　', ' ', ' \n\t']) {
      expect(hasReasonText(blank)).toBe(false);
    }
    expect(hasReasonText('  温度超标 ')).toBe(true);
  });
});
