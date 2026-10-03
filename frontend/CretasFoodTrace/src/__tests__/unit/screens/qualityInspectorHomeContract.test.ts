import fs from 'fs';
import path from 'path';

const srcRoot = path.resolve(__dirname, '../../../');
const read = (relativePath: string) =>
  fs.readFileSync(path.join(srcRoot, relativePath), 'utf8');

describe('quality inspector home workflow contract', () => {
  const homeSource = read('screens/quality-inspector/QIHomeScreen.tsx');
  const navigatorSource = read('navigation/QualityInspectorNavigator.tsx');
  const createSource = read('screens/quality-inspector/QILabelQcCreateScreen.tsx');
  const queueSource = read('screens/quality-inspector/QILabelQcQueueScreen.tsx');
  const submittedSource = read('screens/quality-inspector/QILabelQcSubmittedScreen.tsx');

  it('keeps the field inspector home limited to capture and records', () => {
    expect(homeSource).toContain('testID="qi-home-new-label-qc"');
    expect(homeSource).toContain('testID="qi-home-label-qc-records"');
    expect(homeSource).toContain("screen: 'QILabelQcCreate'");
    expect(homeSource).toContain("screen: 'QILabelQcQueue'");
    expect(homeSource).not.toContain('qi-home-pending-review');
  });

  it('does not expose review, analytics, summaries, or generic quality work', () => {
    expect(homeSource).toContain('发起标签拍检');
    expect(homeSource).toContain('拍检记录');
    for (const forbidden of ['待我审核', '今日摘要', '其他待检批次', 'QILabelQcReview', 'QIAnalysisTab']) {
      expect(homeSource).not.toContain(forbidden);
      expect(navigatorSource).not.toContain(`name="${forbidden}"`);
    }
  });

  it('adds the device safe area to the full bottom navigation height', () => {
    expect(navigatorSource).toContain('useSafeAreaInsets');
    expect(navigatorSource).toContain('height: 64 + bottomInset');
    expect(navigatorSource).toContain('paddingBottom: bottomInset + 2');
    expect(navigatorSource).toContain('minHeight: 48');
    expect(navigatorSource).toContain('tabBarHideOnKeyboard: true');
  });

  it('allows the batch number to be skipped without fabricating a placeholder', () => {
    expect(createSource).toContain('批次号（选填，可跳过）');
    expect(createSource).toContain("batchNumber: batchNumber.trim()");
    expect(createSource).not.toMatch(/batchNumber:\s*['\"]1['\"]/);
    expect(createSource).not.toContain('if (!batchNumber.trim())');
  });

  it('shows pending-review states as read-only processing records', () => {
    expect(queueSource).toContain("PROCESSING: ['DRAFT', 'UPLOADING', 'QUEUED', 'ANALYZING', 'NEEDS_REVIEW', 'ANALYSIS_FAILED']");
    expect(queueSource).toContain("navigation.navigate('QILabelQcSubmitted'");
    expect(queueSource).toContain("if (managerMode && getManagerTaskRoute(task.status, canWriteQuality) === 'QILabelQcReview')");
    expect(submittedSource).not.toContain("navigation.navigate('QILabelQcReview'");
  });

  it('separates trusted AI auto-pass from completed human review in the records', () => {
    expect(queueSource).toContain("DONE: ['AUTO_PASSED', 'REVIEWED']");
    expect(queueSource).toContain("AUTO_PASSED: { label: 'AI 未见风险'");
    expect(submittedSource).toContain("['AUTO_PASSED', 'REVIEWED'].includes(status)");
    expect(submittedSource).toContain('本批未发现风险');
    expect(submittedSource).toContain('AI 自动完成不等同于人工训练真值');
  });
});
