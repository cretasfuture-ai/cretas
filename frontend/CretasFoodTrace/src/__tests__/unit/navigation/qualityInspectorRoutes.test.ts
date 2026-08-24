import { qualityRecordDetailRoute } from '../../../navigation/qualityInspectorRoutes';

describe('quality inspector nested routes', () => {
  it('opens a record detail through the sibling records stack', () => {
    expect(qualityRecordDetailRoute('QI-F006-1')).toEqual({
      screen: 'QIRecordDetail',
      params: { recordId: 'QI-F006-1' },
    });
  });
});
