/**
 * P8 —— 同一 tick 连弹多个框时，后一个把前一个盖掉了。
 *
 * <h3>缺陷长相</h3>
 * `AppDialogHost` 原来只持有**一个** DialogState，`appAlert` 直接 setState 覆盖。
 * 而 `YieldStepReportScreen` 投入提交成功那一条路在同一个 tick 里连调三次:
 *
 * ```
 * maybeShowBalanceWarning(...)   // ⚠️ 物料平衡偏差   ← 被盖
 * maybeShowSemiOutputGap(...)    // 产出没进半成品库  ← 被盖
 * appAlert('投入已提交', ...)     // 只有这一个能看见
 * ```
 *
 * 被盖掉的那两个恰恰是**只在出问题时才出现**的告警 —— 也就是说它们从来没被看见过，
 * 而 `maybeShowSemiOutputGap` 头上的注释写的正是「前端零消费端就等于没加」。
 *
 * <h3>口径</h3>
 * 断言跑在 `AppDialogHost` 本体上 (真实入口就是它 + 模块级 `appAlert`)，
 * 逐条按 testID 取按钮、按标题文本核对当前是哪一条，⛔ 不做整棵树的 toContain。
 */
import React from 'react';
import { act, fireEvent, render, screen } from '@testing-library/react-native';
import { AppDialogHost, appAlert, appPrompt } from '../../../components/ui/AppDialog';

/** 关掉当前这一条 (纯提示框只有一个「知道了」按钮 → app-dialog-btn-0)。 */
function dismiss(): void {
  fireEvent.press(screen.getByTestId('app-dialog-btn-0'));
}

describe('AppDialogHost 是队列, 不是单槽', () => {
  it('同一 tick 连弹三个 → 三个都要轮到, 顺序与调用顺序一致', () => {
    render(<AppDialogHost />);

    act(() => {
      appAlert('⚠️ 物料平衡偏差', '投入 10, 产出 99');
      appAlert('产出没进半成品库', '本道未配半成品产出编号');
      appAlert('投入已提交', '本道进入生产阶段');
    });

    // 第一条: 先来先显示
    expect(screen.getByText('⚠️ 物料平衡偏差')).toBeTruthy();
    expect(screen.queryByText('投入已提交')).toBeNull();

    dismiss();
    expect(screen.getByText('产出没进半成品库')).toBeTruthy();
    expect(screen.queryByText('⚠️ 物料平衡偏差')).toBeNull();

    dismiss();
    expect(screen.getByText('投入已提交')).toBeTruthy();

    // 队空 → 宿主收起
    dismiss();
    expect(screen.queryByText('投入已提交')).toBeNull();
    expect(screen.queryByTestId('app-dialog-btn-0')).toBeNull();
  });

  it('按钮的 onPress 照旧触发, 且触发顺序跟着「哪一条被关掉」走', () => {
    render(<AppDialogHost />);
    const calls: string[] = [];

    act(() => {
      appAlert('第一条', undefined, [{ text: '知道了', onPress: () => calls.push('one') }]);
      appAlert('第二条', undefined, [{ text: '知道了', onPress: () => calls.push('two') }]);
    });

    dismiss();
    expect(calls).toEqual(['one']);
    dismiss();
    expect(calls).toEqual(['one', 'two']);
  });

  it('关掉当前这一条时, 它的 onPress 里再弹的框排到队尾', () => {
    render(<AppDialogHost />);

    act(() => {
      appAlert('原本就排着的', '这一条在队里');
      appAlert('会再弹一个的', undefined, [
        { text: '知道了', onPress: () => appAlert('回调里新弹的') },
      ]);
    });

    // 当前是第一条; 关掉它 → 轮到「会再弹一个的」
    dismiss();
    expect(screen.getByText('会再弹一个的')).toBeTruthy();
    // 关掉它会触发 onPress 再入队一条 → 队里只剩那一条
    dismiss();
    expect(screen.getByText('回调里新弹的')).toBeTruthy();
  });

  it('去重: 手抖点两下同一个按钮只弹一个框 (排队带来的唯一行为回退)', () => {
    render(<AppDialogHost />);
    const calls: string[] = [];

    act(() => {
      appAlert('完工出成确认', '完工后本道出成率锁定', [
        { text: '取消', style: 'cancel' },
        { text: '确认完工出成', onPress: () => calls.push('submit') },
      ]);
      appAlert('完工出成确认', '完工后本道出成率锁定', [
        { text: '取消', style: 'cancel' },
        { text: '确认完工出成', onPress: () => calls.push('submit') },
      ]);
    });

    fireEvent.press(screen.getByTestId('app-dialog-btn-1'));
    expect(calls).toEqual(['submit']);
    // 队里不该还排着第二个一模一样的确认框
    expect(screen.queryByText('完工出成确认')).toBeNull();
  });

  it('阴性对照: 标题相同但正文不同的两条不算重复, 两条都要弹', () => {
    render(<AppDialogHost />);

    act(() => {
      appAlert('提交失败', '库存不足');
      appAlert('提交失败', '网络超时');
    });

    expect(screen.getByText('库存不足')).toBeTruthy();
    dismiss();
    expect(screen.getByText('网络超时')).toBeTruthy();
  });

  it('⛔ prompt 不许去重 —— 每条各带一个 onSubmit, 丢一条就是丢一个回调', () => {
    render(<AppDialogHost />);
    const got: string[] = [];

    act(() => {
      appPrompt('驳回原因', '请说明原因', (v) => got.push(`A:${v}`));
      appPrompt('驳回原因', '请说明原因', (v) => got.push(`B:${v}`));
    });

    fireEvent.changeText(screen.getByTestId('app-dialog-input'), '第一条');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(got).toEqual(['A:第一条']);

    // 第二条必须还在队里, 且输入框回到它自己的初值 (不是上一条的残留)
    expect(screen.getByTestId('app-dialog-input').props.value).toBe('');
    fireEvent.changeText(screen.getByTestId('app-dialog-input'), '第二条');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(got).toEqual(['A:第一条', 'B:第二条']);
  });

  it('队里排着的 prompt 轮到时用的是它自己的 defaultValue', () => {
    render(<AppDialogHost />);
    const got: string[] = [];

    act(() => {
      appAlert('先挡一下', '让 prompt 在队里等着');
      appPrompt('编辑备注', undefined, (v) => got.push(v), { defaultValue: '原备注', required: false });
    });

    dismiss();
    expect(screen.getByTestId('app-dialog-input').props.value).toBe('原备注');
    fireEvent.press(screen.getByTestId('app-dialog-prompt-confirm'));
    expect(got).toEqual(['原备注']);
  });
});
