/**
 * 个体档案列表屏 —— 对着**真实的** `SourceIndividualListScreen` 跑, 只桩掉 API 客户端 / 导航 / i18n。
 *
 * 守的行为 (2026-09-15 评审 P1「首页『源头个体』直达登记表」的替代品):
 *   ① 3 头 mock 牛渲染 3 行; 「共 N 头」的 N 来自 totalElements (42), ⛔ 不是 content.length (3)
 *   ② 标识牌编号只改显示: 15 位每 5 位一组; 13 位原样; 没有标牌的行不渲染那一段 (⛔ 没有 '-')
 *      品种 · 来源场: 两个都有用 ' · ' 连; 只有一个就只显示那一个; 都没有整段不渲染
 *   ③ 点行 ⇒ navigate('SourceIndividualDetail', { individualId })
 *   ④ 空态 (一条档案都没有) ⇒ 「还没有登记任何个体」+「登记第一头」→ Form
 *      对照: 搜了没匹配是另一种「没有」⇒ 「搜过了…」, ⛔ 不引导登记
 *   ⑤ 出错 ⇒ 把错误原话显示出来 + 重试; 重试真的再打一次接口
 *   ⑥ 到底 ⇒ 请求下一页并追加; 最后一页不再请求
 *
 * 每条阴性断言都配一条「必发生」读数 (硬约束 9): 断言「行 3 没有标牌段」时, 行 1 的标牌段必须已经在。
 */
import React from 'react';
import { FlatList } from 'react-native';
import { fireEvent, render, waitFor, within } from '@testing-library/react-native';

import SourceIndividualListScreen, {
  breedAndFarm,
  formatTagNumber,
  PAGE_SIZE,
  SI_LIST_TEST_IDS as ID,
} from '../../screens/shared/SourceIndividualListScreen';
import type { SourceIndividual, SourceIndividualPage } from '../../services/api/sourceIndividualApiClient';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: mockGoBack }),
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

const mockList = jest.fn();
jest.mock('../../services/api/sourceIndividualApiClient', () => ({
  sourceIndividualApiClient: {
    list: (...args: unknown[]) => mockList(...args),
  },
}));

function cow(partial: Partial<SourceIndividual> & { id: string; individualCode: string }): SourceIndividual {
  return { factoryId: 'CATTLE_3701_001', ...partial };
}

/** 三头形状各不同: 全字段 / 只有品种 / 什么都没有 —— 让「缺则不渲染」有东西可比。 */
const THREE: SourceIndividual[] = [
  cow({
    id: 'si-001',
    individualCode: 'SL-CATTLE-001',
    tagNumber: '110370281000001',
    breed: '西门塔尔',
    originFarm: '胜龙一号牧场',
    dnaVerificationStatus: 'MATCHED',
  }),
  cow({
    id: 'si-002',
    individualCode: 'SL-CATTLE-002',
    tagNumber: '1100000000002',
    breed: '西门塔尔',
    dnaVerificationStatus: 'SAMPLED',
  }),
  cow({ id: 'si-003', individualCode: 'SL-CATTLE-003' }),
];

function pageOf(content: SourceIndividual[], extra: Partial<SourceIndividualPage> = {}): SourceIndividualPage {
  return { content, page: 1, size: PAGE_SIZE, totalElements: content.length, totalPages: 1, ...extra };
}

describe('纯函数', () => {
  it('formatTagNumber: 15 位纯数字每 5 位一组; 其它原样 (对照: 13 位 / 带字母 / 14 位都不动)', () => {
    expect(formatTagNumber('110370281000001')).toBe('11037 02810 00001');
    expect(formatTagNumber('1100000000002')).toBe('1100000000002');
    expect(formatTagNumber('11037028100000A')).toBe('11037028100000A');
    expect(formatTagNumber('11037028100000')).toBe('11037028100000');
  });

  it('breedAndFarm: 两个都有 ⇒ " · " 连; 只有一个 ⇒ 那一个; 都没有 / 空白 ⇒ null', () => {
    expect(breedAndFarm({ breed: '西门塔尔', originFarm: '胜龙一号牧场' })).toBe('西门塔尔 · 胜龙一号牧场');
    expect(breedAndFarm({ breed: '西门塔尔' })).toBe('西门塔尔');
    expect(breedAndFarm({ originFarm: '胜龙一号牧场' })).toBe('胜龙一号牧场');
    expect(breedAndFarm({})).toBeNull();
    expect(breedAndFarm({ breed: '  ', originFarm: null })).toBeNull();
  });
});

describe('SourceIndividualListScreen', () => {
  beforeEach(() => {
    mockNavigate.mockClear();
    mockGoBack.mockClear();
    mockList.mockReset();
  });

  it('① 3 头渲染 3 行; 「共 42 头」来自 totalElements 而不是当前页条数', async () => {
    mockList.mockResolvedValue(pageOf(THREE, { totalElements: 42, totalPages: 3 }));
    const { getByTestId, getAllByTestId, queryByTestId } = render(<SourceIndividualListScreen />);

    await waitFor(() => expect(getByTestId(ID.row('si-001'))).toBeTruthy());
    expect(mockList).toHaveBeenCalledTimes(1);
    expect(mockList).toHaveBeenCalledWith({ page: 1, size: PAGE_SIZE, keyword: undefined });

    expect(getAllByTestId(/^si-list-row-si-\d+$/)).toHaveLength(3);
    expect(within(getByTestId(ID.total)).getByText('共 42 头')).toBeTruthy();
    // 对照: 当前页条数是 3, 屏上不能出现「共 3 头」
    expect(within(getByTestId(ID.total)).queryByText('共 3 头')).toBeNull();
    // 有数据时空态 / 出错态都不在
    expect(queryByTestId(ID.empty)).toBeNull();
    expect(queryByTestId(ID.error)).toBeNull();
  });

  it('② 标牌分组显示 + 「品种 · 来源场」缺则不渲染 + DNA chip 经共用色板', async () => {
    mockList.mockResolvedValue(pageOf(THREE));
    const { getByTestId, queryByTestId } = render(<SourceIndividualListScreen />);
    await waitFor(() => expect(getByTestId(ID.row('si-003'))).toBeTruthy());

    // 必发生: 行 1 的标牌段、品种段、DNA chip 都在 —— 下面对行 3 的三条 null 才有意义
    const row1 = getByTestId(ID.row('si-001'));
    expect(within(row1).getByText('SL-CATTLE-001')).toBeTruthy();
    expect(within(getByTestId(ID.rowTag('si-001'))).getByText('11037 02810 00001')).toBeTruthy();
    expect(within(getByTestId(ID.rowMeta('si-001'))).getByText('西门塔尔 · 胜龙一号牧场')).toBeTruthy();
    expect(within(getByTestId(ID.rowDna('si-001'))).getByText('DNA 已比中')).toBeTruthy();

    // 行 2: 13 位原样; 只有品种 ⇒ 没有 ' · '
    expect(within(getByTestId(ID.rowTag('si-002'))).getByText('1100000000002')).toBeTruthy();
    expect(within(getByTestId(ID.rowMeta('si-002'))).getByText('西门塔尔')).toBeTruthy();
    expect(within(getByTestId(ID.rowMeta('si-002'))).queryByText(/·/)).toBeNull();
    expect(within(getByTestId(ID.rowDna('si-002'))).getByText('已采样')).toBeTruthy();

    // 行 3: 什么都没有 ⇒ 三段都不渲染, 且整行没有 '-'
    const row3 = getByTestId(ID.row('si-003'));
    expect(within(row3).getByText('SL-CATTLE-003')).toBeTruthy();
    expect(queryByTestId(ID.rowTag('si-003'))).toBeNull();
    expect(queryByTestId(ID.rowMeta('si-003'))).toBeNull();
    expect(queryByTestId(ID.rowDna('si-003'))).toBeNull();
    expect(within(row3).queryByText('-')).toBeNull();
    expect(within(row3).queryByText('未采样')).toBeNull();
  });

  it('③ 点行 ⇒ navigate 到 SourceIndividualDetail 带那一行的 id; 右上 + ⇒ SourceIndividualForm', async () => {
    mockList.mockResolvedValue(pageOf(THREE));
    const { getByTestId } = render(<SourceIndividualListScreen />);
    await waitFor(() => expect(getByTestId(ID.row('si-002'))).toBeTruthy());

    fireEvent.press(getByTestId(ID.row('si-002')));
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualDetail', { individualId: 'si-002' });
    // 对照: 不是随便哪一行都带 si-002
    fireEvent.press(getByTestId(ID.row('si-003')));
    expect(mockNavigate).toHaveBeenLastCalledWith('SourceIndividualDetail', { individualId: 'si-003' });

    fireEvent.press(getByTestId(ID.add));
    expect(mockNavigate).toHaveBeenLastCalledWith('SourceIndividualForm');
    expect(mockNavigate).toHaveBeenCalledTimes(3);
  });

  it('④ 一条档案都没有 ⇒ 空态文案 + 「登记第一头」→ Form; 总数「共 0 头」', async () => {
    mockList.mockResolvedValue(pageOf([]));
    const { getByTestId, queryByTestId, queryAllByTestId } = render(<SourceIndividualListScreen />);

    await waitFor(() => expect(getByTestId(ID.empty)).toBeTruthy());
    expect(within(getByTestId(ID.empty)).getByText('还没有登记任何个体')).toBeTruthy();
    expect(queryAllByTestId(/^si-list-row-/)).toHaveLength(0);
    expect(queryByTestId(ID.noMatch)).toBeNull();
    expect(within(getByTestId(ID.total)).getByText('共 0 头')).toBeTruthy();

    fireEvent.press(getByTestId(ID.emptyAction));
    expect(mockNavigate).toHaveBeenCalledWith('SourceIndividualForm');
  });

  it('④ 对照: 搜了没匹配 ⇒ 「搜过了…」, 不是「还没有登记」, 也没有「登记第一头」', async () => {
    mockList.mockImplementation(async (params: { keyword?: string }) =>
      params.keyword ? pageOf([]) : pageOf(THREE)
    );
    const { getByTestId, queryByTestId } = render(<SourceIndividualListScreen />);
    await waitFor(() => expect(getByTestId(ID.row('si-001'))).toBeTruthy());

    fireEvent.changeText(getByTestId(ID.search), 'zzz');
    // debounce 之后带关键字再打一次
    await waitFor(() => expect(mockList).toHaveBeenLastCalledWith({ page: 1, size: PAGE_SIZE, keyword: 'zzz' }));
    await waitFor(() => expect(getByTestId(ID.noMatch)).toBeTruthy());
    expect(within(getByTestId(ID.noMatch)).getByText('搜过了：没有匹配的个体档案')).toBeTruthy();
    expect(queryByTestId(ID.empty)).toBeNull();
    expect(queryByTestId(ID.emptyAction)).toBeNull();
    expect(queryByTestId(ID.row('si-001'))).toBeNull();
  });

  it('⑤ 出错 ⇒ 错误原话 + 重试; 重试真的再打接口并恢复列表; 出错时不显示总数', async () => {
    mockList.mockRejectedValueOnce(new Error('后端 500：source_individuals 查询失败'));
    const { getByTestId, queryByTestId } = render(<SourceIndividualListScreen />);

    await waitFor(() => expect(getByTestId(ID.error)).toBeTruthy());
    expect(within(getByTestId(ID.error)).getByText('后端 500：source_individuals 查询失败')).toBeTruthy();
    expect(queryByTestId(ID.empty)).toBeNull();
    expect(queryByTestId(ID.total)).toBeNull();
    expect(mockList).toHaveBeenCalledTimes(1);

    mockList.mockResolvedValueOnce(pageOf(THREE));
    fireEvent.press(getByTestId(ID.retry));
    await waitFor(() => expect(getByTestId(ID.row('si-001'))).toBeTruthy());
    expect(mockList).toHaveBeenCalledTimes(2);
    expect(queryByTestId(ID.error)).toBeNull();
    expect(getByTestId(ID.total)).toBeTruthy();
  });

  it('⑥ 到底 ⇒ 请求第 2 页并追加; 已是最后一页 ⇒ 不再请求', async () => {
    const page2 = [cow({ id: 'si-004', individualCode: 'SL-CATTLE-004' })];
    mockList.mockImplementation(async (params: { page: number }) =>
      params.page === 1
        ? pageOf(THREE, { totalElements: 4, totalPages: 2 })
        : pageOf(page2, { page: 2, totalElements: 4, totalPages: 2 })
    );
    const { getByTestId, getAllByTestId, UNSAFE_getByType } = render(<SourceIndividualListScreen />);
    await waitFor(() => expect(getByTestId(ID.row('si-003'))).toBeTruthy());
    expect(getAllByTestId(/^si-list-row-si-\d+$/)).toHaveLength(3);

    fireEvent(UNSAFE_getByType(FlatList), 'onEndReached');
    await waitFor(() => expect(getByTestId(ID.row('si-004'))).toBeTruthy());
    expect(mockList).toHaveBeenLastCalledWith({ page: 2, size: PAGE_SIZE, keyword: undefined });
    expect(getAllByTestId(/^si-list-row-si-\d+$/)).toHaveLength(4);
    // 前 3 行还在 (追加, 不是替换)
    expect(getByTestId(ID.row('si-001'))).toBeTruthy();

    // 对照: 已是最后一页, 再到底不打接口
    const calls = mockList.mock.calls.length;
    fireEvent(UNSAFE_getByType(FlatList), 'onEndReached');
    await new Promise((r) => setTimeout(r, 20));
    expect(mockList).toHaveBeenCalledTimes(calls);
  });
});
