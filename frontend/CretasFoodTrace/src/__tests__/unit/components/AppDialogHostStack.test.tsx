/**
 * 宿主栈 (2026-09-06, Alert.alert codemod 第一刀)。
 *
 * App.tsx 根上挂了全局宿主之后, 14 个屏内还各自挂着 <AppDialogHost />。原来的单槽 `_show`
 * 在屏内宿主**卸载**时直接置 null ⇒ 根宿主也被摘掉 ⇒ 之后所有 appAlert 在真机上静默丢失。
 *
 * 阳性: 根宿主 + 屏内宿主 → 卸载屏内宿主 → appAlert ⇒ 根宿主渲染出弹窗。
 * 阴性: 变异「卸载时清空整个栈」⇒ 这条红 (registeredDialogHostCount 从 1 变 0, 弹窗不出现)。
 * 对照: 两个宿主都在时只有栈顶那个接到弹窗 (不会弹两个)。
 */
import React from 'react';
import { render, act } from '@testing-library/react-native';
import { AppDialogHost, appAlert, registeredDialogHostCount } from '../../../components/ui/AppDialog';

describe('AppDialogHost stack', () => {
  it('屏内宿主卸载后根宿主仍在, appAlert 仍然弹', () => {
    const root = render(<AppDialogHost />);
    expect(registeredDialogHostCount()).toBe(1);

    const inner = render(<AppDialogHost />);
    expect(registeredDialogHostCount()).toBe(2);

    inner.unmount();
    const countAfter = registeredDialogHostCount();
    console.warn(`[host-stack] after inner unmount: hosts=${countAfter}`);
    expect(countAfter).toBe(1);

    act(() => {
      appAlert('根宿主还在', '屏内宿主已卸载');
    });
    expect(root.queryByText('根宿主还在')).not.toBeNull();
    root.unmount();
    expect(registeredDialogHostCount()).toBe(0);
  });

  it('两个宿主同时在时只有栈顶那个接到弹窗', () => {
    const root = render(<AppDialogHost />);
    const inner = render(<AppDialogHost />);
    act(() => {
      appAlert('只弹一次', 'x');
    });
    expect(inner.queryByText('只弹一次')).not.toBeNull();
    expect(root.queryByText('只弹一次')).toBeNull();
    inner.unmount();
    root.unmount();
  });
});
