import fs from 'fs';
import path from 'path';
import React from 'react';
import { fireEvent, render } from '@testing-library/react-native';
import QIHomeScreen from '../../../screens/quality-inspector/QIHomeScreen';

const mockNavigate = jest.fn();
jest.mock('@react-navigation/native', () => ({ useNavigation: () => ({ navigate: mockNavigate }) }));
jest.mock('react-native-safe-area-context', () => ({ useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }) }));
jest.mock('../../../store/authStore', () => ({ useAuthStore: (selector: (state: unknown) => unknown) => selector({ user: { fullName: 'QA inspector' } }) }));
jest.mock('../../../components/ai/FrontlineAIEntry', () => ({ FrontlineAIEntry: () => null }));

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
    expect(homeSource).toContain('testID="qi-home-new-inspection"');
    expect(homeSource).toContain("screen: 'QIInspectList'");
    expect(homeSource).toContain('testID="qi-home-inspection-records"');
    expect(homeSource).toContain("screen: 'QIRecords'");
  });

  it('mounts the real home actions into the dynamic inspection and records stacks', () => {
    const view = render(React.createElement(QIHomeScreen));
    fireEvent.press(view.getByTestId('qi-home-new-inspection'));
    expect(mockNavigate).toHaveBeenLastCalledWith('QIInspectTab', { screen: 'QIInspectList' });
    fireEvent.press(view.getByTestId('qi-home-inspection-records'));
    expect(mockNavigate).toHaveBeenLastCalledWith('QIRecordsTab', { screen: 'QIRecords' });
    expect(view.getByTestId('qi-home-new-label-qc')).toBeTruthy();
    expect(view.getByTestId('qi-home-label-qc-records')).toBeTruthy();
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
