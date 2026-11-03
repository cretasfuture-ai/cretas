import { describe, expect, it } from 'vitest';
import type { ProcessSheetCustomFieldDef } from '@/api/processProduction';
import {
  checkParam,
  collectParamDeviations,
  formatParamStandard,
  parseParamStandard,
} from '../processParamCheck';

const oil: ProcessSheetCustomFieldDef = {
  key: 'oilTemp', label: '油温', type: 'number', enabled: true, unit: '℃', standardValue: 155, min: 150, max: 160,
};
const moisture: ProcessSheetCustomFieldDef = {
  key: 'moisture', label: '水分', type: 'number', enabled: true, unit: '%', max: 12,
};
const aperture: ProcessSheetCustomFieldDef = {
  key: 'aperture', label: '孔径', type: 'number', enabled: true, unit: 'mm', standardValue: 6,
};

describe('processParamCheck', () => {
  it('judges against the bounds, boundaries are OK, and does not judge what it cannot', () => {
    expect(checkParam(oil, 165)).toBe('HIGH');
    expect(checkParam(oil, '149.9')).toBe('LOW');
    expect(checkParam(oil, 150)).toBe('OK');
    expect(checkParam(oil, 160)).toBe('OK');
    expect(checkParam(oil, null)).toBeNull();
    expect(checkParam(oil, '')).toBeNull();
    expect(checkParam(oil, '高')).toBeNull();
    // 只有标准值、没有上下限: 不判
    expect(checkParam(aperture, 9)).toBeNull();
    expect(checkParam({ ...oil, type: 'text' }, 999)).toBeNull();
  });

  it('formats the standard the way the operator reads it', () => {
    expect(formatParamStandard(oil)).toBe('标准 155℃，范围 150–160℃');
    expect(formatParamStandard(moisture)).toBe('不高于 12%');
    expect(formatParamStandard({ ...moisture, max: null, min: 8 })).toBe('不低于 8%');
    expect(formatParamStandard(aperture)).toBe('标准 6mm');
    expect(formatParamStandard({ key: 'n', label: 'n', type: 'number', enabled: true })).toBe('');
  });

  it('collects only out-of-range params for the submit confirmation', () => {
    const deviations = collectParamDeviations([oil, moisture, aperture], { oilTemp: 165, moisture: 10, aperture: 9 });
    expect(deviations).toHaveLength(1);
    expect(deviations[0]).toMatchObject({ key: 'oilTemp', status: 'HIGH' });
    expect(deviations[0].text).toBe('油温 165℃，高于标准 155℃，范围 150–160℃');
  });

  it('parses the config inputs with the same rules as the backend schema check', () => {
    expect(parseParamStandard({ type: 'number', unit: ' ℃ ', standardValue: '155', min: '150', max: '160' }))
      .toEqual({ value: { unit: '℃', standardValue: 155, min: 150, max: 160 }, error: null });
    expect(parseParamStandard({ type: 'number', unit: '', standardValue: '', min: '', max: '' }))
      .toEqual({ value: {}, error: null });
    expect(parseParamStandard({ type: 'text', unit: '℃', standardValue: '1', min: '', max: '' }))
      .toEqual({ value: {}, error: null });
    expect(parseParamStandard({ type: 'number', unit: '', standardValue: '', min: '160', max: '150' }).error)
      .toBe('下限不能大于上限');
    expect(parseParamStandard({ type: 'number', unit: '', standardValue: '170', min: '150', max: '160' }).error)
      .toBe('标准值必须在上下限范围内');
    expect(parseParamStandard({ type: 'number', unit: '', standardValue: 'abc', min: '', max: '' }).error)
      .toBe('标准值和上下限必须是数字');
  });
});
