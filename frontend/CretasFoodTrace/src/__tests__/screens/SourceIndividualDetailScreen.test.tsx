/**
 * 个体详情屏 —— 对着**真实的** `SourceIndividualDetailScreen` 跑，只桩掉 API 客户端与导航。
 *
 * 数据形状照演示里的 SL-CATTLE-005：父有（SL-BULL-001）、母无、同父兄弟 2 头、
 * 挂了 1 个原料批次、下游 1 个生产批次。
 *
 * ⚠️ 每条阴性断言都配一条「必发生」的读数（硬约束 9）：断言「母本 未登记」时，
 *    同一屏上父本编码必须已经渲染出来 —— 否则「找到了『未登记』」在整屏没渲染时
 *    同样可能是别的东西。
 *
 * 变异对照（2026-09-15 做过）：把 ParentRow 里 `notRegistered` 那句换成 `'-'`
 * ⇒ 「母本 未登记」那条红（screen 上找不到「未登记」）。
 */
import React from 'react';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';

import SourceIndividualDetailScreen, {
  SI_DETAIL_TEST_IDS,
} from '../../screens/shared/SourceIndividualDetailScreen';
import type { SourceIndividualDetail } from '../../services/api/sourceIndividualApiClient';

const mockNavigate = jest.fn();
const mockPush = jest.fn();
const mockGoBack = jest.fn();
let mockParams: { individualId?: string } | undefined = { individualId: 'si-005' };

jest.mock('@react-navigation/native', () => ({
  useRoute: () => ({ params: mockParams }),
  useNavigation: () => ({ navigate: mockNavigate, push: mockPush, goBack: mockGoBack }),
}));

// 真的 i18n 实例在单测里没初始化 ⇒ 直接查 zh-CN 的 bundle，
// 这样断言里出现的中文和用户屏上看到的是**同一个来源**（同 homeModulesSectionHiddenWhenEmpty）。
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
  },
}));

function demoDetail(): SourceIndividualDetail {
  return {
    individual: {
      id: 'si-005',
      factoryId: 'CATTLE_3701_001',
      individualCode: 'SL-CATTLE-005',
      tagNumber: '1100000000005',
      species: '牛',
      breed: '西门塔尔',
      sex: '公',
      birthDate: '2025-03-12',
      originFarm: '胜龙一号牧场',
      dnaVerificationStatus: 'SAMPLED',
      dnaVerificationStatusDisplayName: '已采样',
      sireIndividualId: 'si-bull',
      damIndividualId: null,
    },
    sire: {
      id: 'si-bull',
      individualCode: 'SL-BULL-001',
      tagNumber: '1100000000001',
      species: '牛',
      breed: '西门塔尔',
      sex: '公',
      dnaVerificationStatus: 'MATCHED',
    },
    dam: null,
    offspring: [],
    siblingsBySire: [
      { id: 'si-003', individualCode: 'SL-CATTLE-003', tagNumber: '1100000000003', species: '牛', breed: '西门塔尔', sex: '公', dnaVerificationStatus: 'SAMPLED' },
      { id: 'si-007', individualCode: 'SL-CATTLE-007', tagNumber: '1100000000007', species: '牛', breed: '西门塔尔', sex: '公', dnaVerificationStatus: 'UNSAMPLED' },
    ],
    batches: [
      { id: 'mb-005', batchNumber: 'SL-MB-005', materialName: '活牛', quantity: 520, unit: 'kg', receiptDate: '2026-09-10' },
    ],
    downstream: {
      productionBatches: [
        { nodeType: 'PRODUCTION_BATCH', nodeId: '8001', batchNumber: 'SL-DEMO-BATCH-V1', name: '酱牛肉', resolved: true },
      ],
      finishedBatches: [],
      shipments: [],
    },
  };
}

describe('SourceIndividualDetailScreen（演示形状：父有 / 母无 / 同父兄弟 2 / 下游 1 个生产批次）', () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockPush.mockClear();
    mockGetDetail.mockReset();
    mockParams = { individualId: 'si-005' };
  });

  it('父本编码出现、「母本 未登记」出现、同父兄弟 2 行、生产批次号出现', async () => {
    mockGetDetail.mockResolvedValue(demoDetail());
    const { getByTestId, getByText, getAllByTestId } = render(<SourceIndividualDetailScreen />);

    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(mockGetDetail).toHaveBeenCalledWith('si-005');

    // 必发生：本体编码 + 父本编码（这两条不在，下面的阴性断言无意义）
    // 编码在 Appbar 标题和身份卡各出现一次 ⇒ 限定在身份卡里找，⛔ 不用 getAllByText 把「几处」糊掉
    expect(within(getByTestId('si-detail-identity')).getByText('SL-CATTLE-005')).toBeTruthy();
    expect(within(getByTestId(SI_DETAIL_TEST_IDS.sireRow)).getByText(/SL-BULL-001/)).toBeTruthy();

    // 母本未登记 —— 灰字「未登记母本」（2026-09-16：说清「没登记的是什么」，
    // 但⛔不编「创始个体 / 还没录」这种数据上分不出的假状态），⛔ 不是 '-' 之类的假值
    const damRow = getByTestId(SI_DETAIL_TEST_IDS.damRow);
    expect(within(damRow).getByText('母本')).toBeTruthy();
    expect(within(damRow).getByText('未登记母本')).toBeTruthy();
    expect(within(damRow).queryByText('未登记')).toBeNull(); // 光秃秃的「未登记」不许再出现
    expect(within(damRow).queryByText('-')).toBeNull();

    // 同父兄弟 2 行（钉住条数，不是「至少一条」）
    expect(getAllByTestId(/^si-detail-sibling-/)).toHaveLength(2);
    expect(getByText(/SL-CATTLE-003/)).toBeTruthy();
    expect(getByText(/SL-CATTLE-007/)).toBeTruthy();

    // 去了哪：原料批次 → 生产批次
    expect(getByText('SL-MB-005')).toBeTruthy();
    expect(getByText('SL-DEMO-BATCH-V1')).toBeTruthy();
    expect(getByText('酱牛肉')).toBeTruthy();
  });

  it('父本行可点 ⇒ push 同一个屏换 id；同父兄弟行同理', async () => {
    mockGetDetail.mockResolvedValue(demoDetail());
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    fireEvent.press(getByTestId(SI_DETAIL_TEST_IDS.sireRow));
    expect(mockPush).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-bull' });

    fireEvent.press(getByTestId('si-detail-sibling-si-007'));
    expect(mockPush).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-007' });
    expect(mockPush).toHaveBeenCalledTimes(2);
  });

  it('右上「编辑」⇒ navigate 到 SourceIndividualForm 并带 individualId', async () => {
    mockGetDetail.mockResolvedValue(demoDetail());
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    fireEvent.press(getByTestId(SI_DETAIL_TEST_IDS.edit));
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualForm', { individualId: 'si-005' });
  });

  it('挂了批次但 downstream 三个数组全空 ⇒ 「尚未进入生产」；没挂批次 ⇒ 「尚未挂到任何原料批次」', async () => {
    const d = demoDetail();
    d.downstream = { productionBatches: [], finishedBatches: [], shipments: [] };
    mockGetDetail.mockResolvedValue(d);
    const first = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(first.getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(first.getByText('SL-MB-005')).toBeTruthy(); // 必发生：批次还在
    expect(first.getByTestId(SI_DETAIL_TEST_IDS.notInProduction)).toBeTruthy();
    expect(first.queryByTestId(SI_DETAIL_TEST_IDS.batchesEmpty)).toBeNull();
    first.unmount();

    const e = demoDetail();
    e.batches = [];
    e.downstream = { productionBatches: [], finishedBatches: [], shipments: [] };
    mockGetDetail.mockResolvedValue(e);
    const second = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(second.getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(second.getByTestId(SI_DETAIL_TEST_IDS.batchesEmpty)).toBeTruthy();
    // 没挂批次时不再重复说「尚未进入生产」—— 一句就够
    expect(second.queryByTestId(SI_DETAIL_TEST_IDS.notInProduction)).toBeNull();
  });

  it('系谱指向已不可查的档案（id 在、摘要 null）⇒ 「档案已不可查」，⛔ 不显示成「未登记」', async () => {
    const d = demoDetail();
    d.sire = null; // 但 individual.sireIndividualId 仍是 'si-bull'
    mockGetDetail.mockResolvedValue(d);
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());

    const sireRow = getByTestId(SI_DETAIL_TEST_IDS.sireRow);
    expect(within(sireRow).getByText('档案已不可查')).toBeTruthy();
    expect(within(sireRow).queryByText(/未登记/)).toBeNull();
    // 母本那一行仍然是「未登记母本」—— 两句话在同一屏上并存，证明不是同一条分支
    expect(within(getByTestId(SI_DETAIL_TEST_IDS.damRow)).getByText('未登记母本')).toBeTruthy();
  });

  it('接口失败 ⇒ 显示错误原话 + 重试，⛔ 不渲染一张空档案', async () => {
    mockGetDetail.mockRejectedValue(new Error('后端 503：血缘引擎未就绪'));
    const { getByTestId, getByText, queryByTestId } = render(<SourceIndividualDetailScreen />);

    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.error)).toBeTruthy());
    expect(getByText('后端 503：血缘引擎未就绪')).toBeTruthy();
    expect(queryByTestId(SI_DETAIL_TEST_IDS.screen)).toBeNull();

    // 重试真的再请求一次（不是只换个 spinner）
    mockGetDetail.mockResolvedValue(demoDetail());
    fireEvent.press(getByText('重试'));
    await waitFor(() => expect(getByTestId(SI_DETAIL_TEST_IDS.screen)).toBeTruthy());
    expect(mockGetDetail).toHaveBeenCalledTimes(2);
  });

  it('不带参数打开 ⇒ 说「缺少个体 ID」，不崩、不请求', () => {
    mockParams = undefined;
    const { getByTestId } = render(<SourceIndividualDetailScreen />);
    expect(getByTestId(SI_DETAIL_TEST_IDS.missingParam)).toBeTruthy();
    expect(mockGetDetail).not.toHaveBeenCalled();
  });
});
