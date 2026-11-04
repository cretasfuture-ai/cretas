import type { ProcessCustomFieldDef } from '../../services/api/yieldReportApi';
import {
  buildCustomFieldsRequest,
  checkParam,
  collectParamDeviations,
  enabledParamDefs,
  formatParamStandard,
  isInvalidNumberText,
} from '../../utils/processParamCheck';

const oil: ProcessCustomFieldDef = {
  key: 'oilTemp', label: '油温', type: 'number', enabled: true, unit: '℃', standardValue: 155, min: 150, max: 160,
};
const moisture: ProcessCustomFieldDef = {
  key: 'moisture', label: '水分', type: 'number', enabled: true, unit: '%', max: 12,
};
const aperture: ProcessCustomFieldDef = {
  key: 'aperture', label: '孔径', type: 'number', enabled: true, unit: 'mm', standardValue: 6,
};
const remark: ProcessCustomFieldDef = { key: 'remark', label: '备注', type: 'text', enabled: true };

describe('processParamCheck (与 web 同口径)', () => {
  it('judges against the bounds; boundaries are OK; unjudgeable stays null', () => {
    expect(checkParam(oil, 165)).toBe('HIGH');
    expect(checkParam(oil, '149.9')).toBe('LOW');
    expect(checkParam(oil, 150)).toBe('OK');
    expect(checkParam(oil, 160)).toBe('OK');
    expect(checkParam(oil, null)).toBeNull();
    expect(checkParam(oil, '')).toBeNull();
    expect(checkParam(oil, '高')).toBeNull();
    expect(checkParam(aperture, 9)).toBeNull();
    expect(checkParam({ ...oil, type: 'text' }, 999)).toBeNull();
  });

  it('formats the standard identically to web', () => {
    expect(formatParamStandard(oil)).toBe('标准 155℃，范围 150–160℃');
    expect(formatParamStandard(moisture)).toBe('不高于 12%');
    expect(formatParamStandard({ ...moisture, max: null, min: 8 })).toBe('不低于 8%');
    expect(formatParamStandard(aperture)).toBe('标准 6mm');
    expect(formatParamStandard({ key: 'n', label: 'n', type: 'number', enabled: true })).toBe('');
    expect(formatParamStandard(remark)).toBe('');
  });

  it('collects only out-of-range params, with the exact confirmation text', () => {
    const deviations = collectParamDeviations([oil, moisture, aperture], { oilTemp: 165, moisture: 10, aperture: 9 });
    expect(deviations).toHaveLength(1);
    expect(deviations[0]).toMatchObject({ key: 'oilTemp', status: 'HIGH' });
    expect(deviations[0]?.text).toBe('油温 165℃，高于标准 155℃，范围 150–160℃');
    const low = collectParamDeviations([moisture, { ...oil }], { oilTemp: '140' });
    expect(low[0]?.text).toBe('油温 140℃，低于标准 155℃，范围 150–160℃');
  });

  it('keeps only enabled fields', () => {
    expect(enabledParamDefs(null)).toEqual([]);
    expect(enabledParamDefs([oil, { ...moisture, enabled: false }, { ...remark, enabled: undefined }])).toHaveLength(2);
  });

  it('flags non-numeric text only in number fields, empty is not invalid', () => {
    expect(isInvalidNumberText(oil, '高')).toBe(true);
    expect(isInvalidNumberText(oil, '')).toBe(false);
    expect(isInvalidNumberText(oil, '155.5')).toBe(false);
    expect(isInvalidNumberText(remark, '随便写')).toBe(false);
  });

  it('builds the request: numbers as numbers, filled only, non-numeric not sent', () => {
    expect(buildCustomFieldsRequest([oil, moisture, remark], { oilTemp: '165', moisture: '', remark: ' 偏干 ' }))
      .toEqual({ customFields: { oilTemp: 165, remark: '偏干' }, invalidLabels: [] });
    expect(buildCustomFieldsRequest([oil, moisture], { oilTemp: '很高', moisture: '10' }))
      .toEqual({ customFields: { moisture: 10 }, invalidLabels: ['油温'] });
    expect(buildCustomFieldsRequest([oil], {})).toEqual({ customFields: undefined, invalidLabels: [] });
  });
});
