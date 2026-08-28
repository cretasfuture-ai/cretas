/**
 * 一屏出错不许把整个 App 带走。
 *
 * ## 为什么需要这一条（2026-08-28 逐屏走查实测）
 *
 * 走查 274 屏出现 **50 次**同一个长相：某屏渲染抛异常 → 整棵 React 树卸载 →
 * 导航 ref 变 null、可见文本变空、截图是一张白矩形。成因是
 * `App.tsx` 里 `<AppNavigator/>` 上面**没有任何错误边界**
 * （全仓唯一的 `componentDidCatch` 在 `MarkdownRenderer` 内部，只管它自己）。
 *
 * ⚠️ 白矩形在**任何**「有没有问题」的判据上都长得像「干净」——
 * 走查脚本是靠额外加了一个 `navAlive()` 才没把崩溃之后的每一屏都记成正常。
 *
 * ## 判据
 *
 *  ① 子组件抛异常时，兜底 UI 出现（⇒ 树没有被整个卸载）
 *  ② 「再试一次」之后，恢复正常的子组件能重新渲染出来
 *  ③ **阴性对照**：不抛异常时兜底 UI 不许出现
 *     —— 否则一个「永远显示错误页」的实现也能让 ①② 全绿。
 */
import React from 'react';
import { Text } from 'react-native';
import { render, fireEvent } from '@testing-library/react-native';
import AppErrorBoundary from '../../components/common/AppErrorBoundary';

function Boom({ explode }: { explode: boolean }): React.ReactElement {
  if (explode) throw new Error('boom-from-screen');
  return <Text testID="happy-child">正常内容</Text>;
}

describe('AppErrorBoundary', () => {
  // 错误边界会把 React 的错误日志打到 console.error, 静音以免污染测试输出
  let spy: jest.SpyInstance;
  beforeEach(() => {
    spy = jest.spyOn(console, 'error').mockImplementation(() => {});
  });
  afterEach(() => spy.mockRestore());

  it('阴性对照：子组件正常时，兜底 UI 不出现', () => {
    const { queryByTestId, getByTestId } = render(
      <AppErrorBoundary>
        <Boom explode={false} />
      </AppErrorBoundary>,
    );
    expect(getByTestId('happy-child')).toBeTruthy();
    expect(queryByTestId('app-error-boundary')).toBeNull();
  });

  it('子组件抛异常时兜底 UI 出现，并带上错误信息', () => {
    const { getByTestId } = render(
      <AppErrorBoundary>
        <Boom explode />
      </AppErrorBoundary>,
    );
    expect(getByTestId('app-error-boundary')).toBeTruthy();
    expect(getByTestId('app-error-boundary-message').props.children).toContain('boom-from-screen');
  });

  it('「再试一次」把子树重挂起来', () => {
    let explode = true;
    const onReset = () => {
      explode = false;
    };
    const Child = () => <Boom explode={explode} />;

    const { getByTestId, queryByTestId, rerender } = render(
      <AppErrorBoundary onReset={onReset}>
        <Child />
      </AppErrorBoundary>,
    );
    expect(getByTestId('app-error-boundary')).toBeTruthy();

    fireEvent.press(getByTestId('app-error-boundary-retry'));
    rerender(
      <AppErrorBoundary onReset={onReset}>
        <Child />
      </AppErrorBoundary>,
    );

    expect(queryByTestId('app-error-boundary')).toBeNull();
    expect(getByTestId('happy-child')).toBeTruthy();
  });
});
