import React from 'react';
import { Alert, TextInput } from 'react-native';
import { act, fireEvent, render } from '@testing-library/react-native';
import { Checkbox } from 'react-native-paper';
import FactoryAIWizardScreen from '../../../screens/platform/factory/FactoryAIWizardScreen';

const mockGoBack = jest.fn();
const mockNavigate = jest.fn();
let mockLocale: 'zh-CN' | 'en-US' = 'zh-CN';

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ goBack: mockGoBack, navigate: mockNavigate }),
  useRoute: () => ({ params: { selectedIndustry: 'seafood' } }),
}));

// Use the shipped dictionaries so missing or misleading user-facing copy fails.
jest.mock('react-i18next', () => ({
  useTranslation: () => ({
    t: (key: string, options?: { defaultValue?: string }) => {
      const dictionaries = {
        'zh-CN': require('../../../i18n/locales/zh-CN/platform.json'),
        'en-US': require('../../../i18n/locales/en-US/platform.json'),
      };
      const value = key.split('.').reduce(
        (current: any, part: string) => current?.[part],
        dictionaries[mockLocale]
      );
      return typeof value === 'string' ? value : options?.defaultValue ?? key;
    },
  }),
}));

// Replace only the native gradient; the real screen and its handlers are rendered.
jest.mock('expo-linear-gradient', () => {
  const React = require('react');
  const { View } = require('react-native');
  return {
    LinearGradient: ({ children, ...props }: any) => React.createElement(View, props, children),
  };
});

async function finishFormerSimulatedDelay() {
  await act(async () => {
    jest.advanceTimersByTime(3001);
  });
}

describe('FactoryAIWizardScreen: unavailable generation never reports success', () => {
  beforeEach(() => {
    mockLocale = 'zh-CN';
    mockGoBack.mockClear();
    mockNavigate.mockClear();
    (Alert.alert as jest.Mock).mockClear();
    jest.useFakeTimers();
  });

  afterEach(() => {
    jest.useRealTimers();
  });

  it.each([
    ['zh-CN', '暂时无法生成', '自动生成暂不可用。本次未创建工厂，也未保存配置；已填内容仍在当前页面，请继续编辑或返回上一步。', '继续编辑'],
    ['en-US', 'Generation unavailable', 'Automatic generation is unavailable. No factory was created and no configuration was saved. Your entries remain on this page; continue editing or return to the previous step.', 'Keep editing'],
  ] as const)('states the actual outcome in %s after the former success delay', async (locale, title, message, action) => {
    mockLocale = locale;
    const view = render(<FactoryAIWizardScreen />);

    fireEvent.press(view.getByText('开始生成'));
    await finishFormerSimulatedDelay();

    expect(Alert.alert).toHaveBeenCalledTimes(1);
    expect(Alert.alert).toHaveBeenCalledWith(title, message, [{ text: action }]);
    expect(mockGoBack).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('keeps the page after acknowledging the outcome instead of exiting as if saved', async () => {
    const view = render(<FactoryAIWizardScreen />);
    fireEvent.press(view.getByText('开始生成'));
    await finishFormerSimulatedDelay();

    const buttons = (Alert.alert as jest.Mock).mock.calls[0][2];
    await act(async () => {
      buttons.forEach((button: { onPress?: () => void }) => button.onPress?.());
    });

    expect(mockGoBack).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
    expect(view.getByText('开始生成')).toBeTruthy();
  });

  it('preserves edited description, quota and generation choices and remains editable', async () => {
    const view = render(<FactoryAIWizardScreen />);
    const description = '准备配置，仍需人工确认';
    fireEvent.changeText(view.UNSAFE_getByType(TextInput), description);
    fireEvent.press(view.getByText('300'));
    fireEvent.press(view.getByText('生成业务规则'));
    expect(view.UNSAFE_getAllByType(Checkbox)[4].props.status).toBe('unchecked');

    fireEvent.press(view.getByText('开始生成'));
    await finishFormerSimulatedDelay();

    expect(view.UNSAFE_getByType(TextInput).props.value).toBe(description);
    expect(view.getByText('300次/周')).toBeTruthy();
    expect(view.UNSAFE_getAllByType(Checkbox)[4].props.status).toBe('unchecked');
    expect(mockGoBack).not.toHaveBeenCalled();

    fireEvent.changeText(view.UNSAFE_getByType(TextInput), '继续编辑未提交的配置');
    expect(view.UNSAFE_getByType(TextInput).props.value).toBe('继续编辑未提交的配置');
  });

  it('repeated generation attempts never advance or claim success', async () => {
    const view = render(<FactoryAIWizardScreen />);
    fireEvent.press(view.getByText('开始生成'));
    await finishFormerSimulatedDelay();
    fireEvent.press(view.getByText('开始生成'));
    await finishFormerSimulatedDelay();

    const calls = (Alert.alert as jest.Mock).mock.calls;
    expect(calls).toHaveLength(2);
    expect(calls.every((call) => call[0] === '暂时无法生成')).toBe(true);
    expect(mockGoBack).not.toHaveBeenCalled();
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('still allows the user to return deliberately with the previous-step action', () => {
    const view = render(<FactoryAIWizardScreen />);
    fireEvent.press(view.getByText('上一步'));
    expect(mockGoBack).not.toHaveBeenCalled();
    fireEvent.press(view.getByText('上一步'));
    expect(mockGoBack).toHaveBeenCalledTimes(1);
    expect(Alert.alert).not.toHaveBeenCalled();
  });
});
