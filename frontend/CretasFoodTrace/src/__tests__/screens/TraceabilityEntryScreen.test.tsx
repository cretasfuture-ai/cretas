/**
 * 溯源入口屏 —— 对着**真实的** `TraceabilityScreen` 跑, 只桩掉导航 / i18n / 扫码 Modal / AsyncStorage。
 *
 * 守的行为 (2026-09-15 评审 P1「承诺扫码、交付键盘 + 摘要卡多一跳」):
 *   ① 扫到码 ⇒ **直接** navigate('TraceabilityDetail', { batchNumber, traceCode }) —— 中间没有摘要卡
 *      对照: 扫到空串 / 没扫 ⇒ 不 navigate
 *   ② 手输 ⇒ navigate('TraceabilityDetail', { batchNumber }) —— 对照: 纯空白不 navigate
 *   ③ 摘要卡的文案 (「查看完整溯源链路」「原材料批次」「生产信息」) 不再渲染
 *      ⚠️ 阴性断言配「必发生」: 同一次渲染里扫码按钮必须在 (硬约束 9)
 *   ④ 「最近查过」: 存储里没有 ⇒ 整段不渲染; 有 ⇒ 渲染且点行导航
 *   ⑤ `parseTraceInput`: 标签二维码印的是 `TRACE-{batch}-{suffix}`, 拆出批次号
 *
 * 每条正向断言都有一条能让它红的对照 (见各 it 内)。
 */
import React from 'react';
import { fireEvent, render, waitFor } from '@testing-library/react-native';
import AsyncStorage from '@react-native-async-storage/async-storage';

import TraceabilityScreen, {
  parseTraceInput,
  pushRecent,
  RECENT_LIMIT,
  RECENT_STORAGE_KEY,
  TRACE_ENTRY_TEST_IDS as ID,
} from '../../screens/traceability/TraceabilityScreen';

const mockNavigate = jest.fn();
const mockGoBack = jest.fn();
jest.mock('@react-navigation/native', () => ({
  useNavigation: () => ({ navigate: mockNavigate, goBack: mockGoBack }),
}));

// 真的 i18n 实例在单测里没初始化 ⇒ 直接查 zh-CN 的 bundle, 断言里的中文和屏上是同一个来源。
jest.mock('react-i18next', () => {
  const bundle = require('../../i18n/locales/zh-CN/processing.json');
  const lookup = (key: string, opts?: Record<string, unknown>): string => {
    const raw = key.split('.').reduce<any>((acc, part) => (acc ? acc[part] : undefined), bundle);
    if (typeof raw !== 'string') return key;
    return raw.replace(/\{\{(\w+)\}\}/g, (_m, name) => String(opts?.[name] ?? ''));
  };
  return { useTranslation: () => ({ t: lookup }) };
});

/**
 * 扫码 Modal 替身: 可见时渲染一个「模拟扫到」按钮, 按下把 `mockScanPayload` 交给 onScan。
 * 不可见时什么都不渲染 —— 这样「没按扫码就能扫到」这种假路径在测试里也走不通。
 */
let mockScanPayload = '';
jest.mock('../../components/processing/BarcodeScannerModal', () => {
  const React = require('react');
  const { TouchableOpacity, Text } = require('react-native');
  return {
    BarcodeScannerModal: ({ visible, onScan }: { visible: boolean; onScan: (d: string) => void }) =>
      visible ? (
        <TouchableOpacity testID="mock-scanner-emit" onPress={() => onScan(mockScanPayload)}>
          <Text>emit</Text>
        </TouchableOpacity>
      ) : null,
  };
});

const DEMO_BATCH = 'SL-DEMO-BATCH-V1';
const DEMO_TRACE = 'TRACE-SL-DEMO-BATCH-V1-SEED01';

describe('parseTraceInput（标签二维码里印的是溯源码, 不是批次号）', () => {
  it('内嵌格式 TRACE-{batch}-{suffix} ⇒ 拆出批次号, 原串作 traceCode', () => {
    expect(parseTraceInput(DEMO_TRACE)).toEqual({ batchNumber: DEMO_BATCH, traceCode: DEMO_TRACE });
    // 后端同一条规则: 取最后一个 '-' 之前 —— 批次号自己带 '-' 也拆得对
    expect(parseTraceInput('TRACE-BATCH-2026-001-550e8400')).toEqual({
      batchNumber: 'BATCH-2026-001',
      traceCode: 'TRACE-BATCH-2026-001-550e8400',
    });
  });

  it('普通批次号 ⇒ 只有 batchNumber, 没有 traceCode 键（详情屏据此决定只显示系统码）', () => {
    const p = parseTraceInput(`  ${DEMO_BATCH} `);
    expect(p).toEqual({ batchNumber: DEMO_BATCH });
    expect(p && 'traceCode' in p).toBe(false);
  });

  it('对照: 空串 / 纯空白 ⇒ null; TRACE- 后面没有第二个 "-" 的串不拆, 整串当批次号', () => {
    expect(parseTraceInput('')).toBeNull();
    expect(parseTraceInput('   ')).toBeNull();
    expect(parseTraceInput('TRACE-X')).toEqual({ batchNumber: 'TRACE-X' });
  });

  it('pushRecent: 最新在前、去重、最多 RECENT_LIMIT 个', () => {
    expect(pushRecent(['a', 'b'], 'b')).toEqual(['b', 'a']);
    const five = ['1', '2', '3', '4', '5'];
    expect(pushRecent(five, '0')).toEqual(['0', '1', '2', '3', '4']);
    expect(pushRecent(five, '0')).toHaveLength(RECENT_LIMIT);
  });
});

describe('TraceabilityScreen（找一批 ⇒ 直达详情）', () => {
  beforeEach(async () => {
    mockNavigate.mockClear();
    mockScanPayload = '';
    await AsyncStorage.clear();
  });

  it('① 扫到码 ⇒ 直接 navigate 到 TraceabilityDetail, 带拆出来的 batchNumber + 原 traceCode', async () => {
    const { getByTestId, queryByTestId } = render(<TraceabilityScreen />);
    // 阳性对照: 还没按扫码, Modal 不可见、没有导航
    expect(queryByTestId('mock-scanner-emit')).toBeNull();
    expect(mockNavigate).not.toHaveBeenCalled();

    fireEvent.press(getByTestId(ID.scan));
    mockScanPayload = DEMO_TRACE;
    fireEvent.press(getByTestId('mock-scanner-emit'));

    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('TraceabilityDetail', {
      batchNumber: DEMO_BATCH,
      traceCode: DEMO_TRACE,
    });
    // 扫完 Modal 关掉
    await waitFor(() => expect(queryByTestId('mock-scanner-emit')).toBeNull());
  });

  it('对照: 扫到空串 ⇒ 不 navigate（没码不进详情, ⛔ 不带一个空批次号过去）', () => {
    const { getByTestId } = render(<TraceabilityScreen />);
    fireEvent.press(getByTestId(ID.scan));
    mockScanPayload = '   ';
    fireEvent.press(getByTestId('mock-scanner-emit'));
    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it('② 手输批次号 + 「查看溯源」⇒ navigate 只带 batchNumber; 对照: 纯空白按了不导航', () => {
    const { getByTestId } = render(<TraceabilityScreen />);
    // 对照先行: 空输入按「查看溯源」什么都不发生
    fireEvent.changeText(getByTestId(ID.input), '   ');
    fireEvent.press(getByTestId(ID.go));
    expect(mockNavigate).not.toHaveBeenCalled();

    fireEvent.changeText(getByTestId(ID.input), ` ${DEMO_BATCH} `);
    fireEvent.press(getByTestId(ID.go));
    expect(mockNavigate).toHaveBeenCalledTimes(1);
    expect(mockNavigate).toHaveBeenCalledWith('TraceabilityDetail', { batchNumber: DEMO_BATCH });
    // 键盘回车同一条路
    fireEvent(getByTestId(ID.input), 'submitEditing');
    expect(mockNavigate).toHaveBeenCalledTimes(2);
  });

  it('③ 摘要卡不再渲染: 「查看完整溯源链路」「原材料批次」「生产信息」都不在; 必发生: 扫码按钮在', () => {
    const { getByText, queryByText } = render(<TraceabilityScreen />);
    // 必发生 (硬约束 9): 屏真的渲染出来了 —— 否则下面三条 null 什么都证明不了
    expect(getByText('扫码查溯源')).toBeTruthy();
    expect(getByText('查看溯源')).toBeTruthy();

    expect(queryByText('查看完整溯源链路')).toBeNull();
    expect(queryByText('原材料批次')).toBeNull();
    expect(queryByText('生产信息')).toBeNull();
    expect(queryByText('关联数据')).toBeNull();
  });

  it('④ 「最近查过」: 存储空 ⇒ 整段不渲染 (必发生: 扫码按钮在)', async () => {
    const { getByTestId, queryByTestId } = render(<TraceabilityScreen />);
    expect(getByTestId(ID.scan)).toBeTruthy();
    // 读取是异步的 —— 等一拍再断言, 否则「还没读完」与「读到空」长得一样
    await waitFor(() => expect(AsyncStorage.getItem).toHaveBeenCalledWith(RECENT_STORAGE_KEY));
    expect(queryByTestId(ID.recentSection)).toBeNull();
  });

  it('④ 「最近查过」: 存储里有 ⇒ 渲染那几行, 点行直达详情; 导航后新码写回存储 (最新在前)', async () => {
    await AsyncStorage.setItem(RECENT_STORAGE_KEY, JSON.stringify(['OLD-BATCH-1']));
    const { getByTestId, findByTestId } = render(<TraceabilityScreen />);

    const row = await findByTestId(ID.recentRow('OLD-BATCH-1'));
    expect(getByTestId(ID.recentSection)).toBeTruthy();
    fireEvent.press(row);
    expect(mockNavigate).toHaveBeenCalledWith('TraceabilityDetail', { batchNumber: 'OLD-BATCH-1' });

    // 手输一个新码 ⇒ 写回存储, 新的在前
    fireEvent.changeText(getByTestId(ID.input), DEMO_BATCH);
    fireEvent.press(getByTestId(ID.go));
    await waitFor(() =>
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        RECENT_STORAGE_KEY,
        JSON.stringify([DEMO_BATCH, 'OLD-BATCH-1'])
      )
    );
    expect(await findByTestId(ID.recentRow(DEMO_BATCH))).toBeTruthy();

    // 清空 ⇒ 整段消失
    fireEvent.press(getByTestId(ID.recentClear));
    await waitFor(() => expect(AsyncStorage.removeItem).toHaveBeenCalledWith(RECENT_STORAGE_KEY));
  });
});
