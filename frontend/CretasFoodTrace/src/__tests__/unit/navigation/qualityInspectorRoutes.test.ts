import { qualityInspectionEntryRoute, qualityRecordDetailRoute } from '../../../navigation/qualityInspectorRoutes';

describe('quality inspector nested routes', () => {
  it('opens a record detail through the sibling records stack', () => {
    expect(qualityRecordDetailRoute('QI-F006-1')).toEqual({
      screen: 'QIRecordDetail',
      params: { recordId: 'QI-F006-1' },
    });
  });

  it('opens the server-derived dynamic inspection list without replacing label QC', () => {
    expect(qualityInspectionEntryRoute()).toEqual({ screen: 'QIInspectList' });
  });
});
