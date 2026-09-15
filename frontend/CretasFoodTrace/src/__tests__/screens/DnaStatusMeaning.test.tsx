/**
 * 「DNA 不匹配」要给后果和下一步；四态文案只有一份（2026-09-16 评审 P1）。
 *
 * 2026-09-16 评审：整条演示流唯一的坏消息时刻是个体档案头部那颗红 chip「DNA 不匹配」，
 * 而它旁边一个字都没有 —— 老板会当场问「这头牛有问题？」，技术员会想「样本弄混还是系谱填错？」。
 * 另外 DNA 文案有三套：`dnaStatus.tsx` 说「不匹配」、`processing.json` 说「不符」，
 * 表单里选「不符」、档案里看到「不匹配」。
 *
 * 本文件守三层：
 *   (a) `dnaStatus.tsx` 是唯一出处：四态各有一句「这意味着什么」，只有 MISMATCH 有「下一步」；
 *       认不出来的码没有含义（⛔ 不编）。键在 zh-CN / en-US 两份 bundle 里都取得到。
 *   (b) 档案屏：MISMATCH 渲染 errorContainer 底的说明卡（含义 + 下一步 + 「去改登记的父本」→ 编辑表单）；
 *       MATCHED 只有一行含义、没有卡也没有按钮；未知码 chip 原样显示、整段说明不渲染。
 *   (c) 表单与档案的 MISMATCH 文案取自**同一个键**：两个真实屏渲染出来的字、与 JSON 里那个值三者相等，
 *       且表单源码不再自己拼 `sourceIndividual.dnaStatus.${status}`。
 *
 * ⚠️ 每条阴性断言都配一条「必发生」的读数（硬约束 9）：说「MATCHED 没有卡」之前先证明
 *    同一屏上 MATCHED 的那行含义渲染出来了 —— 否则「找不到卡」在整屏没渲染时同样成立。
 */
import React from 'react';
import fs from 'fs';
import path from 'path';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';

import { theme } from '../../theme';
import {
  DNA_FIX_SIRE_I18N,
  DNA_STATUS_CODES,
  DNA_STATUS_I18N,
  DNA_STATUS_LABEL,
  dnaStatusMeaning,
  dnaStatusNextStep,
  dnaStatusText,
} from '../../screens/traceability/components/dnaStatus';
import SourceIndividualDetailScreen, {
  SI_DETAIL_TEST_IDS,
} from '../../screens/shared/SourceIndividualDetailScreen';
import SourceIndividualFormScreen from '../../screens/shared/SourceIndividualFormScreen';
import type {
  DnaVerificationStatus,
  SourceIndividualDetail,
} from '../../services/api/sourceIndividualApiClient';

// ── 导航 / i18n / API / 弹窗 桩 ────────────────────────────────────────────────
const mockNavigate = jest.fn();
const mockPush = jest.fn();
let mockParams: Record<string, unknown> | undefined = { individualId: 'si-005' };
jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockParams }),
  useNavigation: () => ({ navigate: mockNavigate, push: mockPush, goBack: jest.fn() }),
}));

// 真的 i18n 实例在单测里没初始化 ⇒ 直接查 zh-CN 的 bundle，
// 断言里出现的中文和用户屏上看到的是**同一个来源**。
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
jest.mock('../../services/api/sourceIndividualApiClient', () => ({
  sourceIndividualApiClient: {
    getDetail: (...args: unknown[]) => mockGetDetail(...args),
    getById: jest.fn(),
    search: jest.fn(),
    create: jest.fn(),
    update: jest.fn(),
  },
}));

jest.mock('../../components/ui/AppDialog', () => ({
  appAlert: jest.fn(),
  AppDialogHost: () => null,
}));

// ── bundle 直读（与 mock 同一份文件；(c) 的「JSON 里那个值」从这里来） ─────────────
const LOCALES = path.resolve(__dirname, '../../i18n/locales');
function loadBundle(locale: 'zh-CN' | 'en-US'): Record<string, unknown> {
  return JSON.parse(fs.readFileSync(path.join(LOCALES, locale, 'processing.json'), 'utf8'));
}
function lookupIn(bundle: Record<string, unknown>, key: string): unknown {
  return key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
}
const ZH = loadBundle('zh-CN');
const EN = loadBundle('en-US');
/** 与屏幕上用的是同一种查法（同一段 lookup 逻辑），⛔ 不另写一套解析。 */
const tZh = (key: string, opts?: Record<string, unknown>): string => {
  const raw = lookupIn(ZH, key);
  if (typeof raw !== 'string') return key;
  return raw.replace(/\{\{(\w+)\}\}/g, (_m, name) => String(opts?.[name] ?? ''));
};

/** RN 的 style 可能是数组 / 嵌套数组 —— 摊平成一个对象再读键。 */
function flatten(style: unknown): Record<string, unknown> {
  if (style == null || typeof style === 'boolean') return {};
  if (Array.isArray(style)) return Object.assign({}, ...style.map(flatten));
  return style as Record<string, unknown>;
}

describe('(a) dnaStatus.tsx 是四态文案的唯一出处', () => {
  it('阳性对照：四个码都登记了，键在 zh-CN 与 en-US 里都能取到一段非空字符串（不是把 key 打在屏上）', () => {
    expect([...DNA_STATUS_CODES].sort()).toEqual(['MATCHED', 'MISMATCH', 'SAMPLED', 'UNSAMPLED']);
    for (const code of DNA_STATUS_CODES) {
      const keys = DNA_STATUS_I18N[code];
      for (const key of [keys.label, keys.meaning, keys.nextStep].filter((k): k is string => !!k)) {
        for (const [name, bundle] of [['zh-CN', ZH], ['en-US', EN]] as const) {
          const v = lookupIn(bundle, key);
          expect(typeof v === 'string' && v.trim().length > 0).toBe(true);
          // 键取到的不是键本身（否则 lookup 退化成「返回 key」也会绿）
          expect(v).not.toBe(key);
          void name;
        }
      }
    }
    expect(typeof lookupIn(ZH, DNA_FIX_SIRE_I18N)).toBe('string');
    expect(typeof lookupIn(EN, DNA_FIX_SIRE_I18N)).toBe('string');
  });

  it('四态各有一句「这意味着什么」，且四句互不相同、都不等于标签本身', () => {
    const meanings = DNA_STATUS_CODES.map((c) => dnaStatusMeaning(tZh, c));
    for (const [i, m] of meanings.entries()) {
      expect(typeof m).toBe('string');
      expect((m as string).length).toBeGreaterThan(0);
      expect(m).not.toBe(dnaStatusText(tZh, DNA_STATUS_CODES[i]));
    }
    expect(new Set(meanings).size).toBe(DNA_STATUS_CODES.length);
  });

  it('只有 MISMATCH 有「下一步」；其它三态是 null（对照）', () => {
    expect(dnaStatusNextStep(tZh, 'MISMATCH')).toBe(tZh('sourceIndividual.dnaStatus.nextStep.MISMATCH'));
    expect(dnaStatusNextStep(tZh, 'MISMATCH')).toMatch(/核对采样记录/);
    expect(dnaStatusNextStep(tZh, 'MISMATCH')).toMatch(/重新采样/);
    expect(dnaStatusNextStep(tZh, 'MISMATCH')).toMatch(/父本/);
    for (const code of ['MATCHED', 'SAMPLED', 'UNSAMPLED'] as const) {
      expect(dnaStatusNextStep(tZh, code)).toBeNull();
    }
  });

  it('MISMATCH 的含义说清「后果」：样本 / 系谱两种可能都提到，血缘按未确认处理', () => {
    const m = dnaStatusMeaning(tZh, 'MISMATCH') as string;
    expect(m).toMatch(/样本/);
    expect(m).toMatch(/系谱/);
    expect(m).toMatch(/未确认/);
    // ⛔ 不把物种焊进文案（本屏对所有物种通用）
    expect(m).not.toMatch(/这头牛/);
  });

  it('认不出来的码：标签原样显示那个码，含义 / 下一步都是 null，⛔ 不编一句', () => {
    expect(dnaStatusText(tZh, 'WHATEVER')).toBe('WHATEVER');
    expect(dnaStatusMeaning(tZh, 'WHATEVER')).toBeNull();
    expect(dnaStatusNextStep(tZh, 'WHATEVER')).toBeNull();
    expect(dnaStatusText(tZh, null)).toBeNull();
    expect(dnaStatusText(tZh, '')).toBeNull();
    expect(dnaStatusMeaning(tZh, null)).toBeNull();
  });

  it('兼容出口 DNA_STATUS_LABEL 与 i18n 走的是同一份 zh-CN 文案（不会漂）', () => {
    for (const code of DNA_STATUS_CODES) {
      expect(DNA_STATUS_LABEL[code]).toBe(tZh(DNA_STATUS_I18N[code].label));
    }
  });
});

// ── (b)(c) 真实屏 ─────────────────────────────────────────────────────────────

function detailWith(status: string): SourceIndividualDetail {
  return {
    individual: {
      id: 'si-005',
      factoryId: 'CATTLE_3701_001',
      individualCode: 'SL-CATTLE-005',
      tagNumber: '1100000000005',
      species: '牛',
      breed: '西门塔尔',
      sex: '公',
      // 故意放宽到 string：后端某天多发一个新码时，前端类型拦不住，屏上要能原样显示它
      dnaVerificationStatus: status as DnaVerificationStatus,
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
    siblingsBySire: [],
    batches: [],
    downstream: { productionBatches: [], finishedBatches: [], shipments: [] },
  };
}

const MISMATCH_LABEL_ZH = lookupIn(ZH, 'sourceIndividual.dnaStatus.MISMATCH') as string;

describe('(b) 个体档案：MISMATCH 给说明卡与按钮，其它态只有一行含义', () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockPush.mockClear();
    mockGetDetail.mockReset();
    mockParams = { individualId: 'si-005' };
  });

  it('MISMATCH ⇒ errorContainer 底的说明卡：含义 + 下一步 + 「去改登记的父本」，按下去进编辑表单并带 id', async () => {
    mockGetDetail.mockResolvedValue(detailWith('MISMATCH'));
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    // 必发生：头部 chip 还是那颗，文案来自 JSON 的同一个键
    expect(getByTestId(SI_DETAIL_TEST_IDS.dna)).toHaveTextContent(MISMATCH_LABEL_ZH);

    const card = getByTestId(SI_DETAIL_TEST_IDS.dnaMismatch);
    expect(flatten(card.props.style).backgroundColor).toBe(theme.colors.errorContainer);
    expect(within(card).getByTestId(SI_DETAIL_TEST_IDS.dnaMeaning)).toHaveTextContent(
      tZh('sourceIndividual.dnaStatus.meaning.MISMATCH')
    );
    expect(within(card).getByTestId(SI_DETAIL_TEST_IDS.dnaNextStep)).toHaveTextContent(
      tZh('sourceIndividual.dnaStatus.nextStep.MISMATCH')
    );

    const fix = within(card).getByTestId(SI_DETAIL_TEST_IDS.dnaFixSire);
    expect(fix).toHaveTextContent(tZh(DNA_FIX_SIRE_I18N));
    expect(fix.props.accessibilityLabel).toBe(tZh(DNA_FIX_SIRE_I18N));
    fireEvent.press(fix);
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualForm', { individualId: 'si-005' });
  });

  it('MATCHED ⇒ 一行含义（onSurfaceVariant），没有说明卡、没有按钮、没有「下一步」', async () => {
    mockGetDetail.mockResolvedValue(detailWith('MATCHED'));
    const { getByTestId, queryByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    // 必发生：含义那一行在，且是 MATCHED 的那句
    const meaning = getByTestId(SI_DETAIL_TEST_IDS.dnaMeaning);
    expect(meaning).toHaveTextContent(tZh('sourceIndividual.dnaStatus.meaning.MATCHED'));
    expect(flatten(meaning.props.style).color).toBe(theme.colors.onSurfaceVariant);

    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaMismatch)).toBeNull();
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaFixSire)).toBeNull();
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaNextStep)).toBeNull();
  });

  it.each(['SAMPLED', 'UNSAMPLED'] as const)('%s ⇒ 一行含义，同样没有卡和按钮', async (status) => {
    mockGetDetail.mockResolvedValue(detailWith(status));
    const { getByTestId, queryByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(getByTestId(SI_DETAIL_TEST_IDS.dnaMeaning)).toHaveTextContent(
      tZh(`sourceIndividual.dnaStatus.meaning.${status}`)
    );
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaMismatch)).toBeNull();
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaFixSire)).toBeNull();
  });

  it('认不出来的码 ⇒ chip 原样显示那个码；含义整段不渲染（⛔ 不给它编一句看起来正常的话）', async () => {
    mockGetDetail.mockResolvedValue(detailWith('WHATEVER'));
    const { getByTestId, queryByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(getByTestId(SI_DETAIL_TEST_IDS.dna)).toHaveTextContent('WHATEVER');
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaMeaning)).toBeNull();
    expect(queryByTestId(SI_DETAIL_TEST_IDS.dnaMismatch)).toBeNull();
  });

  it('系谱行：母本未登记 ⇒ 「未登记母本」（说清没登记的是什么），⛔ 不编创始 / 未录的假区分', async () => {
    mockGetDetail.mockResolvedValue(detailWith('MATCHED'));
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    const damRow = getByTestId(SI_DETAIL_TEST_IDS.damRow);
    expect(within(damRow).getByText(tZh('sourceIndividual.detail.parentNotRegistered', { parent: '母本' }))).toBeTruthy();
    expect(within(damRow).getByText('未登记母本')).toBeTruthy();
    // 父本是登记了的 —— 同一屏两行不同，证明不是整屏一句话
    expect(within(getByTestId(SI_DETAIL_TEST_IDS.sireRow)).queryByText(/未登记/)).toBeNull();
  });
});

describe('(c) 表单与档案的 MISMATCH 文案取自同一个键', () => {
  beforeEach(() => {
    mockGetDetail.mockReset();
  });

  it('JSON 里的值 = 档案头部 chip 渲染的字 = 表单状态按钮渲染的字；旧的「不符」不再出现', async () => {
    // 键本身：MISMATCH 说的是「不匹配」，不是「不符」
    expect(MISMATCH_LABEL_ZH).toMatch(/不匹配/);
    expect(MISMATCH_LABEL_ZH).not.toMatch(/不符/);

    // 档案
    mockParams = { individualId: 'si-005' };
    mockGetDetail.mockResolvedValue(detailWith('MISMATCH'));
    const detail = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(detail.getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    const chipText = within(detail.getByTestId(SI_DETAIL_TEST_IDS.dna)).getByText(MISMATCH_LABEL_ZH);
    expect(chipText).toBeTruthy();
    detail.unmount();

    // 表单（空白建档，不带参数 ⇒ 不请求）
    mockParams = undefined;
    const form = render(<SourceIndividualFormScreen />);
    const button = form.getByTestId('si-dna-status-MISMATCH');
    expect(button).toHaveTextContent(MISMATCH_LABEL_ZH);
    // 四个按钮全部与 JSON 一致（不只 MISMATCH 一个）
    for (const code of DNA_STATUS_CODES) {
      expect(form.getByTestId(`si-dna-status-${code}`)).toHaveTextContent(
        lookupIn(ZH, DNA_STATUS_I18N[code].label) as string
      );
    }
    expect(form.queryByText('不符')).toBeNull();
  });

  it('表单源码不再自己拼 `sourceIndividual.dnaStatus.${status}` —— 走 dnaStatusText(t, status)', () => {
    const src = fs.readFileSync(
      path.resolve(__dirname, '../../screens/shared/SourceIndividualFormScreen.tsx'),
      'utf8'
    );
    expect(src).toMatch(/dnaStatusText\(t, status\)/);
    expect(src).not.toMatch(/sourceIndividual\.dnaStatus\.\$\{/);
  });
});
