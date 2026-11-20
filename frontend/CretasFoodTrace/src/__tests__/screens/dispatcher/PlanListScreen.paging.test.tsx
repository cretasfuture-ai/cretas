/**
 * 调度员「生产计划」列表分页 (Steve 2026-11-12: 原来只取第一页 100 条, 第 101 条起既不在列表也不在汇总)。
 * 钉住: 第 1 页之后还有就写明「已加载 N / 共 M 条」; 加载剩余后第 101 条出现在列表并计入汇总、提示消失;
 * 是按页 (每页 100) 读的, 不是一次拉全量。
 */
import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import PlanListScreen from '../../../screens/dispatcher/plan/PlanListScreen';
import { productionPlanApiClient } from '../../../services/api/productionPlanApiClient';

jest.mock('@react-navigation/native', () => {
  const ReactActual = jest.requireActual('react');
  return {
    useNavigation: () => ({ navigate: jest.fn(), goBack: jest.fn(), dispatch: jest.fn() }),
    useFocusEffect: (cb: () => void) => ReactActual.useEffect(cb, []),
    CommonActions: { navigate: jest.fn() },
  };
});
jest.mock('react-native-safe-area-context', () => ({
  // eslint-disable-next-line @typescript-eslint/no-var-requires
  SafeAreaView: (props: object) => require('react').createElement(require('react-native').View, props),
  useSafeAreaInsets: () => ({ top: 0, bottom: 0, left: 0, right: 0 }),
}));
jest.mock('../../../services/api/productionPlanApiClient', () => ({
  productionPlanApiClient: { getProductionPlans: jest.fn() },
}));
jest.mock('../../../services/api/printApiClient', () => ({ safePrint: jest.fn() }));
jest.mock('../../../hooks/useRowActions', () => ({ useRowActions: () => [] }));
jest.mock('../../../components/list', () => ({
  RowActionBottomSheet: () => null,
  // 只把汇总数字摆出来, 方便断言 (真实组件的格式化另有测试)
  StickyFooterSummary: ({ stats }: { stats: { label: string; value: unknown; unit?: string }[] }) =>
    require('react').createElement(require('react-native').Text, { testID: 'footer-stats' },
      stats.map((s) => `${s.label}${String(s.value)}${s.unit ?? ''}`).join('|')),
}));

const mockedGet = productionPlanApiClient.getProductionPlans as jest.Mock;
/** 按子串比 (toHaveTextContent 传字符串是整段相等)。 */
const esc = (t: string) => t.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const TOTAL = 150;
const ALL = Array.from({ length: TOTAL }, (_, i) => ({
  id: `QA-P${i + 1}`,
  planNumber: `QA-PLAN-${String(i + 1).padStart(3, '0')}`,
  productTypeName: `QA-产品-${i + 1}`,
  plannedQuantity: 1,
  actualQuantity: 0,
  plannedUnit: 'kg',
  status: 'PENDING',
  // 越靠前越新 (后端按 createdAt 倒序), 第 101 条在第 2 页
  createdAt: new Date(Date.UTC(2026, 10, 12, 0, 0, 0) - i * 60000).toISOString(),
}));

beforeEach(() => {
  mockedGet.mockReset();
  mockedGet.mockImplementation(async ({ page, size }: { page: number; size: number }) => ({
    success: true,
    data: { content: ALL.slice((page - 1) * size, page * size), totalElements: TOTAL, page, size },
  }));
});

it('第 1 页之后还有: 写明「已加载 100 / 共 150 条」, 汇总只算已加载的', async () => {
  render(<PlanListScreen />);
  expect(await screen.findByTestId('plan-list-coverage')).toHaveTextContent(new RegExp(esc('已加载 100 / 共 150 条计划，汇总只算已加载的')));
  expect(screen.getByTestId('footer-stats')).toHaveTextContent(new RegExp(esc('共100条')));
  expect(screen.getByTestId('plan-list-count')).toHaveTextContent(new RegExp(esc('已显示 100 条')));
  expect(mockedGet).toHaveBeenCalledTimes(1);
  expect(mockedGet).toHaveBeenLastCalledWith({ page: 1, size: 100 });
});

it('加载剩余: 按页读第 2 页, 第 101 条计入汇总, 提示消失', async () => {
  render(<PlanListScreen />);
  fireEvent.press(await screen.findByTestId('plan-list-load-all'));
  await waitFor(() => expect(screen.getByTestId('footer-stats')).toHaveTextContent(new RegExp(esc('共150条'))));
  expect(screen.queryByTestId('plan-list-coverage')).toBeNull();
  expect(screen.getByTestId('plan-list-count')).toHaveTextContent(new RegExp(esc('共 150 条')));
  expect(mockedGet.mock.calls.map((c) => c[0])).toEqual([{ page: 1, size: 100 }, { page: 2, size: 100 }]);
});

it('全部加载后搜第 101 条: 它出现在列表里, 汇总只算它', async () => {
  render(<PlanListScreen />);
  fireEvent.press(await screen.findByTestId('plan-list-load-all'));
  await waitFor(() => expect(screen.getByTestId('footer-stats')).toHaveTextContent(new RegExp(esc('共150条'))));
  fireEvent.changeText(screen.getByPlaceholderText(/搜索/), 'QA-PLAN-101');
  await waitFor(() => expect(screen.getByTestId('footer-stats')).toHaveTextContent(new RegExp(esc('共1条'))));
  expect(screen.getByText('QA-产品-101')).toBeTruthy();
});

it('不足一页: 不提示、不翻页', async () => {
  mockedGet.mockResolvedValue({ success: true, data: { content: ALL.slice(0, 5), totalElements: 5 } });
  render(<PlanListScreen />);
  await waitFor(() => expect(screen.getByTestId('footer-stats')).toHaveTextContent(new RegExp(esc('共5条'))));
  expect(screen.queryByTestId('plan-list-coverage')).toBeNull();
  expect(screen.queryByTestId('plan-list-load-more')).toBeNull();
});
