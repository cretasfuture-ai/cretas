import React from 'react';
import {
  Alert,
  StyleSheet,
  TouchableOpacity,
  View,
  StyleProp,
  ViewStyle,
} from 'react-native';

/**
 * 「没权限就隐藏」→「置灰但可见」。
 *
 * ## 为什么
 *
 * 隐藏让人以为「没有这个功能」, 置灰让人知道「有, 但不归我」。
 * 对低技术素养用户, 后者少一类「为什么我这儿没有这个按钮」的追问。
 * 交互形态取自竞品(黑湖小工单)的报工界面: 只有自己有权限的工序绿色可点,
 * 其余灰色但可见, 讲解人明说是「防止员工误操作」。
 *
 * ## ⛔ 这个组件改的是【展示】, 不是【权限判定】
 *
 * 置灰之后那个元素【仍然不能真的调用后端】, 两道保险:
 *   1. children 被 clone 成 `disabled: true` + `onPress: undefined` —— 原来的
 *      业务 handler 从 props 上就没了, 不存在「灰着但还能点」。
 *   2. 外面再套一层 `pointerEvents="none"` 的 View —— 即使某个子组件忽略
 *      `disabled`(RN 生态里确实有这种), 触摸也到不了它。
 *
 * ⚠️ 前端置灰 ≠ 后端放行。后端那道闸是最终真值, 本组件一个字都不碰它。
 *
 * ## 为什么外层 TouchableOpacity 【不是】 disabled 的
 *
 * ⛔ 「只是灰掉不响应」和坏掉没区别。外层那个可点区域的职责是【解释】:
 * 点它弹一句「这项归谁」。所以语义上是 —— 外层=可点的解释器,
 * 内层=真正被禁用的动作。两者都 disabled 的话就没人能说出那句话了。
 */
export interface PermissionGatedActionProps {
  /** 有权限 = true 时原样渲染 children; false 时置灰 + 可点解释。 */
  granted: boolean;
  /** 一句话说明「这项归谁」。⛔ 不要写成「无权限」——那没有告诉用户任何事。 */
  reason: string;
  /** 外层容器样式 —— 放【定位】类(FAB 的 absolute、grid 的 width)。 */
  style?: StyleProp<ViewStyle>;
  /**
   * 内层容器样式 —— 放【排列】类(actionRow 的 flexDirection/gap)。
   *
   * ⚠️ 必须和 `style` 分开: 置灰时内层多套了一个 pointerEvents="none" 的 View,
   * 若把 flexDirection 留在外层, 那一排按钮在【置灰状态下】会竖着堆起来 ——
   * 一个只在没权限时才出现的布局错位, 有权限的人永远看不到。
   */
  contentStyle?: StyleProp<ViewStyle>;
  /** 置灰时外层可点区域的 testID; 有权限时挂在透传容器上。 */
  testID?: string;
  children: React.ReactNode;
}

/** 置灰弹窗的标题。抽成常量, 便于测试断言与后续文案统一。 */
export const PERMISSION_LOCKED_TITLE = '这项不归你';

export function PermissionGatedAction({
  granted,
  reason,
  style,
  contentStyle,
  testID,
  children,
}: PermissionGatedActionProps) {
  // 把业务 handler 从 props 上摘掉, 而不是「灰着但 onPress 还在」。
  const gatedChildren = granted
    ? children
    : React.Children.map(children, (child) =>
        React.isValidElement(child)
          ? React.cloneElement(child as React.ReactElement<any>, {
              disabled: true,
              onPress: undefined,
            })
          : child
      );

  // ⚠️ 两个分支【共用同一层内容容器】。曾经只有置灰分支多套一层 View,
  //    结果 actionRow 的 flexDirection 落在外层, 一排按钮在置灰时竖了起来 ——
  //    那种错位【只在没权限的人屏幕上出现】, 自测时永远看不到。
  //    形态 D: 同一个东西有两份, 它一定会漂。
  const content = (
    <View
      pointerEvents={granted ? 'auto' : 'none'}
      style={[styles.content, contentStyle, granted ? null : styles.dimmed]}
    >
      {gatedChildren}
    </View>
  );

  if (granted) {
    return (
      <View style={style} testID={testID}>
        {content}
      </View>
    );
  }

  return (
    <TouchableOpacity
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={reason}
      accessibilityHint={reason}
      activeOpacity={0.9}
      style={style}
      onPress={() => Alert.alert(PERMISSION_LOCKED_TITLE, reason)}
    >
      {content}
    </TouchableOpacity>
  );
}

const styles = StyleSheet.create({
  // 内容容器默认不改变被包元素的宽度 —— 父容器 alignItems:'center' 时
  // 不加这一条会让内容缩成「最宽子元素」的宽度, 视觉上偏离原位。
  content: {
    alignSelf: 'stretch',
  },
  // 0.45 —— 灰得够明显(一眼看出不可点), 又不至于看不清是什么功能。
  dimmed: {
    opacity: 0.45,
  },
});

export default PermissionGatedAction;
