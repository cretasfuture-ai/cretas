export const qualityRecordDetailRoute = (recordId: string) => ({
  screen: 'QIRecordDetail',
  params: { recordId },
});

export const qualityInspectionEntryRoute = () => ({
  screen: 'QIInspectList',
});
