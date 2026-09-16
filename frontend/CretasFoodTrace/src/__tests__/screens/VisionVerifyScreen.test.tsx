/**
 * 拍照核验屏（demo-nav-shell / 评审 P0 + P2）—— 对着**真实的** `VisionVerifyScreen` 跑，
 * 只桩掉 API 客户端、导航、相机、扫码弹层。
 *
 * 2026-09-15 真机：「拍照核验」标题压进状态栏与系统时钟重叠（手写 Text 当标题，没有 Appbar）；
 * 扫码是 tonal、拍照是 contained，而提示句说「扫码是确定的，拍照识别是估的」—— 层级与文案相反；
 * 耳标「没读出编号」那张卡没有重拍按钮，还显示「把握程度 0%」；成功卡与失败卡长得一样。
 *
 * 守的行为（每条阴性断言都配一条同屏「必发生」的读数，硬约束 9）：
 * ① Appbar 返回键接到 goBack；标题只在 Appbar 里出现一次（手写标题已去掉）
 * ② 标识牌模板：扫码 contained / 拍照 contained-tonal / 相册 outlined；肉品模板：拍照 contained
 * ③ 耳标失败态（读不出编号）：卡上有 contained 的「重拍」，按下去再次拉起相机
 * ④ 失败态不渲染「把握程度」；成功态渲染「把握程度 92%」
 * ⑤ 成功卡 successBg / 失败卡 errorContainer —— 只看容器色，文案不动
 * ⑥ 识别中：大号 ActivityIndicator + bodyLarge 文案
 * ⑦ 每个按钮都有 accessibilityLabel
 *
 * 变异对照（2026-09-16 做过，每条先打印可观测量确认变异到达再看红）：
 *  · 失败态 Card.Actions 里去掉「重拍」按钮 ⇒ ③ 红
 *  · 把握程度去掉 `tagRead &&` 前置 ⇒ ④ 红（失败态渲染出「把握程度 0%」）
 *  · 扫码按钮换回 contained-tonal ⇒ ② 红
 *  · StyleSheet 写回 `backgroundColor: '#f5f7fa'` ⇒ gates/demoScreensUseThemeTokens 红
 */
import React from 'react';
import { ActivityIndicator } from 'react-native';
import { act, fireEvent, render, waitFor, within } from '@testing-library/react-native';
import * as ImagePicker from 'expo-image-picker';

import VisionVerifyScreen from '../../screens/shared/VisionVerifyScreen';
import { theme } from '../../theme';
import type { SourceIndividual, VisionVerifyOutcome } from '../../services/api/sourceIndividualApiClient';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();

jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: mockGoBack }),
}));

// 单测里没有 i18n 实例 ⇒ 直接查 zh-CN bundle；`common:buttons.back` 这种跨 namespace 写法也要认。
jest.mock('react-i18next', () => {
  const bundles: Record<string, unknown> = {
    processing: require('../../i18n/locales/zh-CN/processing.json'),
    common: require('../../i18n/locales/zh-CN/common.json'),
  };
  const lookup = (key: string, opts?: Record<string, unknown>): string => {
    const [ns, path] = key.includes(':') ? key.split(':', 2) : ['processing', key];
    const raw = path!.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundles[ns!]);
    if (typeof raw !== 'string') return key;
    return raw.replace(/\{\{(\w+)\}\}/g, (_m, name) => String(opts?.[name] ?? ''));
  };
  return { useTranslation: () => ({ t: lookup }) };
});

// setup.ts 的 expo-image-picker 替身没有相机那两个方法 —— 这里给整套，且可数调用次数。
jest.mock('expo-image-picker', () => ({
  requestCameraPermissionsAsync: jest.fn(() => Promise.resolve({ granted: true })),
  launchCameraAsync: jest.fn(() =>
    Promise.resolve({ canceled: false, assets: [{ uri: 'file://cam.jpg', base64: 'QUJD' }] })
  ),
  requestMediaLibraryPermissionsAsync: jest.fn(() => Promise.resolve({ granted: true })),
  launchImageLibraryAsync: jest.fn(() =>
    Promise.resolve({ canceled: false, assets: [{ uri: 'file://lib.jpg', base64: 'QUJD' }] })
  ),
}));

const mockVerify = jest.fn<Promise<VisionVerifyOutcome>, [unknown]>();
const mockFindByTag = jest.fn();
jest.mock('../../services/api/sourceIndividualApiClient', () => ({
  sourceIndividualApiClient: {
    verify: (...args: unknown[]) => mockVerify(args[0]),
    findByTag: (...args: unknown[]) => mockFindByTag(...args),
  },
}));

// 扫码弹层依赖 expo-camera，不在本测范围 —— 渲染成空。
jest.mock('../../components/processing/BarcodeScannerModal', () => ({
  BarcodeScannerModal: () => null,
}));

const launchCameraAsync = ImagePicker.launchCameraAsync as unknown as jest.Mock;

const MATCHED: SourceIndividual = {
  id: 'si-005',
  factoryId: 'CATTLE_3701_001',
  individualCode: 'SL-CATTLE-005',
  tagNumber: '1100000000005',
  species: '牛',
  breed: '西门塔尔',
  originFarm: '胜龙一号牧场',
  dnaVerificationStatus: 'MATCHED',
} as SourceIndividual;

/** 模型读不出编号（真实 422 之外的另一种「没认出来」：200 但 normalizedTagNumber 为 null）。 */
const UNREADABLE: VisionVerifyOutcome = {
  ok: true,
  result: { templateCode: 'EAR_TAG', confidence: 0, normalizedTagNumber: null },
};

const READ_AND_MATCHED: VisionVerifyOutcome = {
  ok: true,
  result: {
    templateCode: 'EAR_TAG',
    confidence: 0.92,
    normalizedTagNumber: '1100000000005',
    matchCount: 1,
    matched: MATCHED,
  },
};

/** 拍一张 —— 走真实的 takePhoto（权限 → 相机 → runVerify）。 */
async function shoot(getByTestId: (id: string) => any) {
  fireEvent.press(getByTestId('vision-camera-button'));
  await waitFor(() => expect(mockVerify).toHaveBeenCalled());
}

function bgOf(node: { props: { style?: unknown } }): unknown {
  const flat = ([] as unknown[]).concat(node.props.style as never).reduce<Record<string, unknown>>(
    (acc, s) => ({ ...acc, ...((s as Record<string, unknown>) ?? {}) }),
    {}
  );
  return flat.backgroundColor;
}

beforeEach(() => {
  mockVerify.mockReset();
  mockFindByTag.mockReset();
  mockNavigate.mockReset();
  mockGoBack.mockReset();
  launchCameraAsync.mockClear();
});

describe('① 顶部导航壳', () => {
  it('返回键接到 goBack，恰好一次', () => {
    const { getByTestId } = render(<VisionVerifyScreen />);
    const back = getByTestId('vision-back');
    expect(back.props.accessibilityLabel).toBe('返回');
    expect(mockGoBack).toHaveBeenCalledTimes(0);
    fireEvent.press(back);
    expect(mockGoBack).toHaveBeenCalledTimes(1);
  });

  it('「拍照核验」只在 Appbar 里出现一次 —— 手写标题已去掉', () => {
    const { getAllByText, getByTestId } = render(<VisionVerifyScreen />);
    expect(within(getByTestId('vision-title')).getByText('拍照核验')).toBeTruthy();
    expect(getAllByText('拍照核验')).toHaveLength(1);
  });
});

describe('② 按钮层级与提示句同向', () => {
  it('标识牌模板：扫码 contained / 拍照 contained-tonal / 相册 outlined', () => {
    const { getByTestId, getByText } = render(<VisionVerifyScreen />);
    // 「必发生」：提示句在 —— 它就是层级要对齐的那句话
    expect(getByText(/扫码是确定的，拍照识别是估的/)).toBeTruthy();
    expect(getByTestId('vision-scan-button').props.mode).toBe('contained');
    expect(getByTestId('vision-camera-button').props.mode).toBe('contained-tonal');
    expect(getByTestId('vision-gallery-button').props.mode).toBe('outlined');
  });

  it('肉品模板：没有扫码这条路，拍照就是 contained', () => {
    const { getByTestId, queryByTestId, queryByText } = render(<VisionVerifyScreen />);
    fireEvent.press(getByTestId('vision-template-MEAT_CUT'));
    expect(queryByTestId('vision-scan-button')).toBeNull();
    expect(queryByText(/扫码是确定的/)).toBeNull();
    expect(getByTestId('vision-camera-button').props.mode).toBe('contained');
    expect(getByTestId('vision-gallery-button').props.mode).toBe('outlined');
  });
});

describe('③④⑤ 耳标结果卡：失败态 vs 成功态', () => {
  it('读不出编号 ⇒ 红卡 + contained「重拍」，按下去再次拉起相机；⛔ 不渲染「把握程度」', async () => {
    mockVerify.mockResolvedValue(UNREADABLE);
    const { getByTestId, queryByText, queryByTestId } = render(<VisionVerifyScreen />);

    await shoot(getByTestId);
    await waitFor(() => expect(getByTestId('vision-ear-tag-card')).toBeTruthy());
    // 「必发生」：这就是失败态 —— 文案在
    expect(getByTestId('vision-tag-unreadable')).toBeTruthy();

    // ③ 重拍主按钮
    const retake = getByTestId('vision-ear-tag-retake-button');
    expect(retake.props.mode).toBe('contained');
    expect(retake.props.accessibilityLabel).toBe('重拍');
    expect(launchCameraAsync).toHaveBeenCalledTimes(1);
    fireEvent.press(retake);
    await waitFor(() => expect(launchCameraAsync).toHaveBeenCalledTimes(2));

    // ④ 把握程度 0% 不是读数
    expect(queryByText(/把握程度/)).toBeNull();
    expect(queryByTestId('vision-confidence')).toBeNull();

    // ⑤ 容器色 = errorContainer
    expect(bgOf(getByTestId('vision-ear-tag-card'))).toBe(theme.colors.errorContainer);
  });

  it('读出编号且唯一命中 ⇒ 绿卡 + 「把握程度 92%」+ 查看档案；没有重拍', async () => {
    mockVerify.mockResolvedValue(READ_AND_MATCHED);
    const { getByTestId, getByText, queryByTestId } = render(<VisionVerifyScreen />);

    await shoot(getByTestId);
    await waitFor(() => expect(getByTestId('vision-ear-tag-card')).toBeTruthy());
    expect(getByTestId('vision-tag-number')).toBeTruthy();

    // ④ 成功态才有把握程度
    expect(getByText('把握程度 92%')).toBeTruthy();
    // ③ 阴性对照：成功态没有重拍
    expect(queryByTestId('vision-ear-tag-retake-button')).toBeNull();
    expect(queryByTestId('vision-tag-unreadable')).toBeNull();
    // ⑤ 容器色 = successBg，且与失败态不同
    expect(bgOf(getByTestId('vision-ear-tag-card'))).toBe(theme.custom.workflow.successBg);
    expect(theme.custom.workflow.successBg).not.toBe(theme.colors.errorContainer);

    fireEvent.press(getByTestId('vision-open-profile'));
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-005' });
  });
});

describe('⑥ 识别中', () => {
  it('大号转圈 + bodyLarge 文案，识别完就消失', async () => {
    let resolveVerify!: (o: VisionVerifyOutcome) => void;
    mockVerify.mockImplementation(() => new Promise((r) => { resolveVerify = r; }));
    const { getByTestId, queryByTestId, UNSAFE_getByType } = render(<VisionVerifyScreen />);

    await shoot(getByTestId);
    await waitFor(() => expect(getByTestId('vision-busy')).toBeTruthy());
    expect(UNSAFE_getByType(ActivityIndicator).props.size).toBe('large');
    expect(within(getByTestId('vision-busy')).getByText('识别中…').props.variant).toBe('bodyLarge');

    // ⚠️ 在 act 里放行 promise：promise 续体里的 setState 在 act 之外只能靠 waitFor 轮询等
    //    React 自己调度，实测（2026-09-16 探针）要 1.6–1.8 s，机器一忙就撞 waitFor 的 1 s 上限
    //    ——变异对照跑批时这条因此红过三次，与被变异的行为无关。act 里 181 ms 且确定。
    await act(async () => {
      resolveVerify(READ_AND_MATCHED);
    });
    expect(queryByTestId('vision-busy')).toBeNull();
    // 「必发生」：识别完成的结果卡出来了 —— 否则「busy 没了」也可能是整屏没渲染
    expect(getByTestId('vision-ear-tag-card')).toBeTruthy();
  });
});

describe('⑦ 每个交互元素都有 accessibilityLabel', () => {
  it('顶栏 + 模板 chip + 三个动作按钮', () => {
    const { getByTestId } = render(<VisionVerifyScreen />);
    for (const id of [
      'vision-back',
      'vision-template-EAR_TAG',
      'vision-template-MEAT_CUT',
      'vision-scan-button',
      'vision-camera-button',
      'vision-gallery-button',
    ]) {
      const label = getByTestId(id).props.accessibilityLabel;
      expect({ id, label: typeof label === 'string' && label.length > 0 }).toEqual({ id, label: true });
    }
  });
});

describe('⑧ 肉品卡：把握程度只跟着「认出来的部位」走', () => {
  // 2026-09-16 实测: 方块 cut=null 而模型自报 confidence 0.98, 卡片上「分割部位 —」紧挨着
  // 「把握程度 98%」, 老板会读成「98% 确定」。数字是真的, 放的位置让它撒谎。
  const meat = (cut: string | null, confidence: number): VisionVerifyOutcome => ({
    ok: true,
    result: {
      templateCode: 'MEAT_CUT',
      confidence,
      normalizedTagNumber: null,
      fields: { cut, marblingScore: 5, foreignObjectSuspected: false, contaminationSuspected: false, notes: null },
    } as never,
  });

  it('部位认出了 ⇒ 显示「分割部位 眼肉」+「对部位的把握 95%」', async () => {
    mockVerify.mockResolvedValueOnce(meat('眼肉', 0.95));
    const { getByTestId, getByText, queryByTestId, queryByText } = render(<VisionVerifyScreen />);
    fireEvent.press(getByTestId('vision-template-MEAT_CUT'));
    await shoot(getByTestId);
    await waitFor(() => expect(getByTestId('vision-meat-cut-card')).toBeTruthy());
    expect(getByText('眼肉')).toBeTruthy();
    expect(getByText('对部位的把握 95%')).toBeTruthy();
    expect(queryByTestId('vision-cut-unknown')).toBeNull();
    // 老文案「把握程度 95%」不再出现 —— 它读起来像整体准确率
    expect(queryByText(/^把握程度/)).toBeNull();
  });

  it('对照：部位没认出 ⇒ 明说「没认出部位」，⛔ 不显示 — 也不显示任何百分比', async () => {
    mockVerify.mockResolvedValueOnce(meat(null, 0.98));
    const { getByTestId, queryByTestId, queryByText } = render(<VisionVerifyScreen />);
    fireEvent.press(getByTestId('vision-template-MEAT_CUT'));
    await shoot(getByTestId);
    await waitFor(() => expect(getByTestId('vision-meat-cut-card')).toBeTruthy());
    expect(getByTestId('vision-cut-unknown')).toBeTruthy();
    expect(queryByTestId('vision-confidence')).toBeNull();
    expect(queryByText(/98%/)).toBeNull();
    expect(queryByText(/把握/)).toBeNull();
    // 必发生: 脂肪评分那一行仍在 —— 否则「没有百分比」也可能是整张卡没渲染
    expect(queryByText('脂肪分布评分（1-5）')).toBeTruthy();
  });
});
