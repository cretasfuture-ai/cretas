/**
 * DNA 状态色板 —— **一处定义、四处同色**（FEAT-RN-DEMO-SCREENS-POLISH-20260915 / B.3）。
 *
 * 2026-09-15 真机实测：同一个「DNA 不匹配」在时间线上是棕字、在档案头部是中性描边、
 * 在后代行是蓝字 —— 三处三个长相，错误态看起来和正常态一样。
 *
 * 本文件守两层：
 *   ① 色板本身：MISMATCH → 错误色（errorContainer/onErrorContainer）、MATCHED → 完成色、
 *      SAMPLED → 信息色、UNSAMPLED → 中性、认不出来的码 → 中性且**原样显示那个码**。
 *   ② 接线：四个真实屏 / 组件上渲染出来的 chip，底色与字色都等于色板给出的那一对。
 *      ⚠️ 这一层不能省 —— 色板对了、某一处没接上（自己写了个色）在 ① 上看不出来（形态 B）。
 *
 * 变异对照（2026-09-15 做过）：把 `DNA_STATUS_TONE.MISMATCH` 改成 'success'
 * ⇒ ①「MISMATCH 用错误色」红，② 四处 MISMATCH 的断言全红。
 */
import React from 'react';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';

import { theme } from '../../theme';
import {
  DNA_STATUS_LABEL,
  dnaStatusColors,
  dnaStatusLabel,
  dnaStatusTone,
} from '../../screens/traceability/components/dnaStatus';
import { statusToneColors } from '../../screens/traceability/components/statusTone';
import LineageChainTimeline from '../../screens/traceability/components/LineageChainTimeline';
import SourceIndividualDetailScreen, {
  SI_DETAIL_TEST_IDS,
} from '../../screens/shared/SourceIndividualDetailScreen';
import VisionVerifyScreen from '../../screens/shared/VisionVerifyScreen';
import type { LineageSection } from '../../services/api/traceabilityApiClient';
import type { SourceIndividualDetail } from '../../services/api/sourceIndividualApiClient';

// ── 导航 / i18n / API 桩 ──────────────────────────────────────────────────────
const mockNavigate = jest.fn();
const mockPush = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: { individualId: 'si-005' } }),
  useNavigation: () => ({ navigate: mockNavigate, push: mockPush, goBack: jest.fn() }),
}));

jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/processing.json');
  const lookup = (key: string, opts?: Record<string, unknown>): string => {
    const raw = key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
    if (typeof raw !== 'string') return key;
    return raw.replace(/\{\{(\w+)\}\}/g, (_m, name) => String(opts?.[name] ?? ''));
  };
  return { useTranslation: () => ({ t: lookup }) };
});

const mockGetDetail = jest.fn();
const mockFindByTag = jest.fn();
jest.mock('../../services/api/sourceIndividualApiClient', () => ({
  sourceIndividualApiClient: {
    getDetail: (...args: unknown[]) => mockGetDetail(...args),
    findByTag: (...args: unknown[]) => mockFindByTag(...args),
    verify: jest.fn(),
  },
}));

// 扫码弹窗替身：露一个按钮，按下去等于扫到了一个码 —— 这是把 VisionVerify 推到「命中」态的最短路。
jest.mock('../../components/processing/BarcodeScannerModal', () => {
  const R = require('react');
  const { TouchableOpacity, Text } = require('react-native');
  return {
    BarcodeScannerModal: ({ onScan }: { onScan: (data: string) => void }) =>
      R.createElement(
        TouchableOpacity,
        { testID: 'mock-scan-trigger', onPress: () => onScan('1100000000005') },
        R.createElement(Text, null, 'scan')
      ),
  };
});

/** RN 的 style 可能是数组 / 嵌套数组 —— 摊平成一个对象再读键。 */
function flatten(style: unknown): Record<string, unknown> {
  if (style == null || typeof style === 'boolean') return {};
  if (Array.isArray(style)) return Object.assign({}, ...style.map(flatten));
  return style as Record<string, unknown>;
}

/** 从渲染出来的 chip 上读「底色 / 字色」——替身把 style / textStyle 原样透传到 TouchableOpacity。 */
function chipColors(chip: { props: Record<string, unknown> }): { backgroundColor: unknown; color: unknown } {
  return {
    backgroundColor: flatten(chip.props.style).backgroundColor,
    color: flatten(chip.props.textStyle).color,
  };
}

const ERROR = statusToneColors(theme, 'error');
const SUCCESS = statusToneColors(theme, 'success');

describe('① 色板本身', () => {
  it('阳性对照：五种色调给出的底/字都是 token 里的值，且互不相同（否则下面「同色」是恒真式）', () => {
    const tones = ['success', 'info', 'pending', 'error', 'neutral'] as const;
    const bgs = tones.map((t) => statusToneColors(theme, t).backgroundColor);
    expect(new Set(bgs).size).toBe(tones.length);
    for (const t of tones) {
      const c = statusToneColors(theme, t);
      expect(typeof c.backgroundColor).toBe('string');
      expect(typeof c.color).toBe('string');
    }
  });

  it('MISMATCH 用错误色（errorContainer / onErrorContainer）—— 不匹配必须看得出是错', () => {
    expect(dnaStatusTone('MISMATCH')).toBe('error');
    expect(dnaStatusColors(theme, 'MISMATCH')).toEqual({
      backgroundColor: theme.colors.errorContainer,
      color: theme.colors.onErrorContainer,
    });
    // 阴性对照：它**不是**成功色 —— 变异「MISMATCH → success」在这里红
    expect(dnaStatusColors(theme, 'MISMATCH')).not.toEqual(SUCCESS);
  });

  it('MATCHED 用完成色、SAMPLED 用信息色、UNSAMPLED 用中性', () => {
    expect(dnaStatusColors(theme, 'MATCHED')).toEqual({
      backgroundColor: theme.custom.workflow.doneBg,
      color: theme.custom.workflow.doneText,
    });
    expect(dnaStatusColors(theme, 'SAMPLED')).toEqual({
      backgroundColor: theme.colors.primaryContainer,
      color: theme.colors.onPrimaryContainer,
    });
    expect(dnaStatusColors(theme, 'UNSAMPLED')).toEqual({
      backgroundColor: theme.colors.surfaceVariant,
      color: theme.colors.onSurfaceVariant,
    });
  });

  it('认不出来的码 ⇒ 中性色 + 原样显示那个码，⛔ 不翻译成一个看起来正常的值', () => {
    expect(dnaStatusTone('WHATEVER')).toBe('neutral');
    expect(dnaStatusLabel('WHATEVER')).toBe('WHATEVER');
    expect(dnaStatusLabel(null)).toBeNull();
    expect(dnaStatusLabel('')).toBeNull();
    // 四个已知码都有文案（少一个就会有一处显示成码）
    expect(Object.keys(DNA_STATUS_LABEL).sort()).toEqual(['MATCHED', 'MISMATCH', 'SAMPLED', 'UNSAMPLED']);
  });
});

// ── ② 四处接线 ────────────────────────────────────────────────────────────────

function lineageWith(status: string): LineageSection {
  return {
    rootType: 'PRODUCTION_BATCH',
    rootId: '8967',
    maxDepth: 5,
    nodeCount: 1,
    sourceIndividualCount: 1,
    ancestors: [
      {
        depth: 1,
        nodes: [
          {
            nodeType: 'MATERIAL_BATCH',
            nodeId: 'mb-near',
            batchNumber: 'MB-NEAR-001',
            name: '整牛',
            resolved: true,
            sourceIndividual: {
              id: 'si-005',
              individualCode: 'SL-CATTLE-005',
              tagNumber: '1100000000005',
              species: '牛',
              breed: '西门塔尔',
              originFarm: '胜龙一号牧场',
              dnaVerificationStatus: status,
            },
          },
        ],
      },
    ],
  };
}

function detailWith(status: 'MATCHED' | 'MISMATCH' | 'SAMPLED' | 'UNSAMPLED'): SourceIndividualDetail {
  return {
    individual: {
      id: 'si-005',
      factoryId: 'CATTLE_3701_001',
      individualCode: 'SL-CATTLE-005',
      tagNumber: '1100000000005',
      species: '牛',
      breed: '西门塔尔',
      sex: '公',
      dnaVerificationStatus: status,
      sireIndividualId: 'si-bull',
      damIndividualId: null,
    },
    sire: {
      id: 'si-bull',
      individualCode: 'SL-BULL-001',
      tagNumber: '1100000000001',
      species: '牛',
      dnaVerificationStatus: 'MATCHED',
    },
    dam: null,
    offspring: [],
    siblingsBySire: [
      { id: 'si-003', individualCode: 'SL-CATTLE-003', tagNumber: '1100000000003', species: '牛', dnaVerificationStatus: status },
    ],
    batches: [],
    downstream: { productionBatches: [], finishedBatches: [], shipments: [] },
  };
}

describe('② 四处渲染出来的 chip 都等于色板那一对', () => {
  beforeEach(() => {
    mockGetDetail.mockReset();
    mockFindByTag.mockReset();
  });

  it('来源链时间线节点：MISMATCH ⇒ 错误色；MATCHED ⇒ 完成色（同一处、两个状态、两种颜色）', () => {
    const bad = render(<LineageChainTimeline lineage={lineageWith('MISMATCH')} showSupplier />);
    const badChip = bad.getByTestId('lineage-individual-dna-mb-near');
    expect(badChip).toHaveTextContent('DNA 不匹配');
    expect(chipColors(badChip)).toEqual(ERROR);
    bad.unmount();

    const good = render(<LineageChainTimeline lineage={lineageWith('MATCHED')} showSupplier />);
    const goodChip = good.getByTestId('lineage-individual-dna-mb-near');
    expect(goodChip).toHaveTextContent('DNA 已比中');
    expect(chipColors(goodChip)).toEqual(SUCCESS);
  });

  it('个体档案：头部 chip、父本行 chip、同父后代行 chip —— MISMATCH 全是错误色', async () => {
    mockGetDetail.mockResolvedValue(detailWith('MISMATCH'));
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    const head = getByTestId(SI_DETAIL_TEST_IDS.dna);
    expect(head).toHaveTextContent('DNA 不匹配');
    expect(chipColors(head)).toEqual(ERROR);

    const sibling = getByTestId(SI_DETAIL_TEST_IDS.dnaOf('sibling-si-003'));
    expect(sibling).toHaveTextContent('DNA 不匹配');
    expect(chipColors(sibling)).toEqual(ERROR);

    // 同一屏上父本是 MATCHED —— 完成色。两种颜色并存，证明不是「整屏一个色」。
    const sire = getByTestId(SI_DETAIL_TEST_IDS.dnaOf('sire'));
    expect(sire).toHaveTextContent('DNA 已比中');
    expect(chipColors(sire)).toEqual(SUCCESS);

    // 行本身还是可点的（chip 没有把行的 onPress 吃掉）
    fireEvent.press(getByTestId('si-detail-sibling-si-003'));
    expect(mockPush).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-003' });
  });

  it('拍照核验命中结果：DNA 状态那一行是同一颗 chip，MISMATCH ⇒ 错误色', async () => {
    mockFindByTag.mockResolvedValue({
      tagNumber: '1100000000005',
      matchCount: 1,
      matched: {
        id: 'si-005',
        factoryId: 'CATTLE_3701_001',
        individualCode: 'SL-CATTLE-005',
        tagNumber: '1100000000005',
        dnaVerificationStatus: 'MISMATCH',
        dnaVerificationStatusDisplayName: 'DNA 不匹配',
      },
      allMatches: [],
    });
    const { getByTestId } = render(<VisionVerifyScreen />);
    fireEvent.press(getByTestId('mock-scan-trigger'));
    await waitFor(() => expect(getByTestId('vision-matched')).toBeTruthy());
    expect(mockFindByTag).toHaveBeenCalledWith('1100000000005');

    const chip = within(getByTestId('vision-matched')).getByTestId('vision-dna-status');
    expect(chip).toHaveTextContent('DNA 不匹配');
    expect(chipColors(chip)).toEqual(ERROR);
  });
});
