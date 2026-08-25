/**
 * 盲盘（blind stocktake）契约闸 —— Steve 2026-08-25 拍板。
 *
 *     录入这一行时:  只有 品名 + 大字输入框 + [提交]      ← 看不到账面数
 *          ↓ 点提交
 *     立刻显示:      账面 135 / 实盘 120 / 差异 -15 🔴   ← 该行锁定, 不可再改
 *
 * 两层, 都要:
 *   1. 模块层 (blindStocktake.ts) —— 决策逻辑本身对不对
 *   2. **屏幕层 (RNTL 渲染真屏幕)** —— 屏幕有没有真的用上它
 *
 * ⚠️ 只有第 1 层是不够的: 「机制在、没接上」的经典长相就是模块写得好好的,
 *    而屏幕自己又把 item.systemQty 渲染了一遍。第 2 层才是守住行为的那一层。
 */
import React from 'react';
import { StyleSheet } from 'react-native';
import { render, fireEvent, waitFor } from '@testing-library/react-native';

import {
  canSubmitRow,
  isRowLocked,
  revealedDifference,
  revealedSystemQty,
  summarizeBlindCount,
  type BlindEntryRow,
} from '../../screens/warehouse/inventory/blindStocktake';
import { StocktakeEntryScreen } from '../../screens/warehouse/inventory/StocktakeEntryScreen';
import { stocktakeApiClient } from '../../services/api/stocktakeApiClient';

jest.mock('../../services/api/stocktakeApiClient', () => ({
  stocktakeApiClient: {
    getDetail: jest.fn(),
    updateItems: jest.fn(),
    submit: jest.fn(),
  },
}));

// ⚠️ navigation / route 必须是**稳定引用**。
//    每次调用都返回新对象的话, 屏幕里 useCallback([stocktakeId, navigation]) 的身份
//    每渲染一次就变一次 → useEffect 反复触发 → 无限重载。
//    实测: 500ms 内 getDetail 被调了 14 次, waitFor 永远等不到稳定态。
//    真实的 @react-navigation useNavigation() 返回的就是稳定引用, 这里对齐它。
const NAV = { navigate: jest.fn(), goBack: jest.fn(), setOptions: jest.fn(), addListener: jest.fn() };
const ROUTE = { params: { stocktakeId: 'ST-1' } };
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => NAV,
  useRoute: () => ROUTE,
  useFocusEffect: jest.fn(),
}));

const SYSTEM_QTY = 135;
const ACTUAL_QTY = 120;
const SERVER_DIFF = -15;

const row = (over: Partial<BlindEntryRow> = {}): BlindEntryRow => ({
  committed: false,
  systemQty: SYSTEM_QTY,
  actualQty: null,
  differenceQty: null,
  differenceType: null,
  ...over,
});

// ────────────────────────────────────────────────────────────────────────────
// 第 1 层: 模块契约
// ────────────────────────────────────────────────────────────────────────────

describe('blindStocktake 模块契约', () => {
  it('未提交 → 账面数和差异都不吐出来', () => {
    expect(revealedSystemQty(row())).toBeNull();
    expect(revealedDifference(row())).toBeNull();
    // 打了字也一样 —— 否则填个数试探一下就能把账面数问出来
    expect(revealedSystemQty(row({ actualQty: ACTUAL_QTY }))).toBeNull();
  });

  it('账面为 0 的行, 未提交时给 null 而不是 0', () => {
    // 0 是账面数量的合法值, 不能拿它当"看不到"的哨兵
    expect(revealedSystemQty(row({ systemQty: 0 }))).toBeNull();
  });

  it('提交后 → 账面/差异露出来, 且该行锁定', () => {
    const done = row({ committed: true, actualQty: ACTUAL_QTY, differenceQty: SERVER_DIFF });
    expect(revealedSystemQty(done)).toBe(SYSTEM_QTY);
    expect(revealedDifference(done)).toBe(SERVER_DIFF);
    expect(isRowLocked(done)).toBe(true);
    expect(isRowLocked(row({ actualQty: ACTUAL_QTY }))).toBe(false);
  });

  it('服务端没给差异时返回 null, ⛔ 不用 actualQty - systemQty 兜底', () => {
    const noDiff = row({ committed: true, actualQty: ACTUAL_QTY, differenceQty: null });
    expect(revealedDifference(noDiff)).toBeNull();
    expect(revealedDifference(noDiff)).not.toBe(ACTUAL_QTY - SYSTEM_QTY);
  });

  it('已锁定的行不能再提交', () => {
    expect(canSubmitRow(row({ actualQty: ACTUAL_QTY }))).toBe(true);
    expect(canSubmitRow(row({ actualQty: null }))).toBe(false);
    expect(canSubmitRow(row({ committed: true, actualQty: ACTUAL_QTY }))).toBe(false);
  });

  it('汇总把「服务端没给差异」单列, ⛔ 不并进账实相符', () => {
    const summary = summarizeBlindCount([
      row({ committed: true, differenceQty: 0 }),
      row({ committed: true, differenceQty: -3 }),
      row({ committed: true, differenceQty: 5 }),
      row({ committed: true, differenceQty: null }), // 未知
      row({ actualQty: 9 }),                          // 没提交
    ]);
    expect(summary).toEqual({ total: 5, committed: 4, matched: 1, shortage: 1, surplus: 1, unknown: 1 });
    // 把 unknown 并进 matched 就会是 2 —— 那等于把"不知道"报成"没问题"
    expect(summary.matched).not.toBe(2);
  });
});

// ────────────────────────────────────────────────────────────────────────────
// 第 2 层: 屏幕真的用上了吗 (渲染 StocktakeEntryScreen 本体)
// ────────────────────────────────────────────────────────────────────────────

const mockedApi = stocktakeApiClient as jest.Mocked<typeof stocktakeApiClient>;

const detail = (item: Record<string, unknown>) => ({
  success: true,
  data: {
    id: 'ST-1',
    factoryId: 'F1',
    stocktakeNo: 'ST-202608-001',
    warehouseId: 'W1',
    periodMonth: '2026-08',
    status: 'COUNTING',
    items: [{ id: 'IT-1', stocktakeId: 'ST-1', materialBatchId: '带鱼段A', systemQty: SYSTEM_QTY, ...item }],
  },
});

describe('StocktakeEntryScreen 盲盘行为', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('提交前: 屏幕上没有账面数量, 提交后: 账面/差异都出来且输入框锁死', async () => {
    mockedApi.getDetail
      .mockResolvedValueOnce(detail({}) as never)                      // 挂载时加载
      .mockResolvedValueOnce(detail({                                  // 提交后回读
        actualQty: ACTUAL_QTY, differenceQty: SERVER_DIFF, differenceType: 'SHORTAGE',
      }) as never);
    mockedApi.updateItems.mockResolvedValue({ success: true, data: null } as never);

    const screen = render(<StocktakeEntryScreen />);

    const input = await waitFor(() => screen.getByTestId('stocktake-entry-actual-qty-input'));

    // ── 提交前 ──────────────────────────────────────────────
    // 阳性对照: 屏幕确实渲染出来了(否则下面的 queryByText 全是恒真的 null)
    expect(screen.getByText('带鱼段A')).toBeTruthy();
    expect(screen.getByTestId('stocktake-entry-submit-item')).toBeTruthy();
    // 盲: 既没有「账面数量」这个标签, 也没有 135 这个数字出现在任何地方
    expect(screen.queryByText('账面数量')).toBeNull();
    expect(screen.queryByText(String(SYSTEM_QTY))).toBeNull();
    expect(screen.queryByTestId('stocktake-entry-reveal')).toBeNull();

    // ── 输入并提交 ──────────────────────────────────────────
    fireEvent.changeText(input, String(ACTUAL_QTY));
    expect(screen.queryByText(String(SYSTEM_QTY))).toBeNull();

    // 隐蔽泄露: 就算账面数的**文字**没了, 输入框按"与账面是否偏离"变色照样能把它
    // 透露出去(试几个数就能二分出来)。文本断言看不见颜色, 所以这里单独查样式。
    // 120 ≠ 135 ⇒ 旧实现会把 color 涂成 RED(#FF4D4F); 盲盘下它必须还是默认色。
    const styleAfterTyping = StyleSheet.flatten(
      screen.getByTestId('stocktake-entry-actual-qty-input').props.style,
    ) as { color?: string };
    expect(styleAfterTyping.color).not.toBe('#FF4D4F'); // RED
    expect(styleAfterTyping.color).not.toBe('#FA8C16'); // AMBER
    expect(styleAfterTyping.color).toBe('#111');        // 阳性对照: 确实读到了样式

    fireEvent.press(screen.getByTestId('stocktake-entry-submit-item'));

    // ── 提交后 ──────────────────────────────────────────────
    await waitFor(() => expect(screen.getByTestId('stocktake-entry-reveal')).toBeTruthy());
    expect(screen.getByTestId('stocktake-entry-reveal-system').props.children).toBe(SYSTEM_QTY);
    // 差异取服务端的 differenceQty, 不是前端减出来的
    expect(screen.getByTestId('stocktake-entry-reveal-diff')).toBeTruthy();
    expect(screen.getByText('账面')).toBeTruthy();

    // 锁定: 输入框不可编辑, 大按钮已经换成「下一品/完成录入」
    expect(screen.getByTestId('stocktake-entry-actual-qty-input').props.editable).toBe(false);
    expect(screen.queryByTestId('stocktake-entry-submit-item')).toBeNull();
    expect(screen.getByTestId('stocktake-entry-next')).toBeTruthy();
  });

  it('提交时把实盘数发给后端, 且差异是回读来的不是本地算的', async () => {
    mockedApi.getDetail
      .mockResolvedValueOnce(detail({}) as never)
      .mockResolvedValueOnce(detail({
        actualQty: ACTUAL_QTY, differenceQty: SERVER_DIFF, differenceType: 'SHORTAGE',
      }) as never);
    mockedApi.updateItems.mockResolvedValue({ success: true, data: null } as never);

    const screen = render(<StocktakeEntryScreen />);
    const input = await waitFor(() => screen.getByTestId('stocktake-entry-actual-qty-input'));
    fireEvent.changeText(input, String(ACTUAL_QTY));
    fireEvent.press(screen.getByTestId('stocktake-entry-submit-item'));

    await waitFor(() => expect(mockedApi.updateItems).toHaveBeenCalledTimes(1));
    expect(mockedApi.updateItems).toHaveBeenCalledWith('ST-1', [{ itemId: 'IT-1', actualQty: ACTUAL_QTY }]);
    // 提交后必须再读一次详情 —— 差异只认服务端算的那一份。
    // 这条同时是「没发生」类断言的必发生对照: 它没涨就说明提交根本没跑完。
    await waitFor(() => expect(mockedApi.getDetail).toHaveBeenCalledTimes(2));
  });

  it('已提交的行再次进入时是锁的, 输入改不动', async () => {
    mockedApi.getDetail.mockResolvedValue(detail({
      actualQty: ACTUAL_QTY, differenceQty: SERVER_DIFF, differenceType: 'SHORTAGE',
    }) as never);

    const screen = render(<StocktakeEntryScreen />);
    const input = await waitFor(() => screen.getByTestId('stocktake-entry-actual-qty-input'));

    expect(input.props.editable).toBe(false);
    fireEvent.changeText(input, '999');
    expect(screen.getByTestId('stocktake-entry-actual-qty-input').props.value).toBe(String(ACTUAL_QTY));
  });
});
