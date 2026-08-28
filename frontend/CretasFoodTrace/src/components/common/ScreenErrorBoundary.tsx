import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Button, Text } from 'react-native-paper';
import { logger } from '../../utils/logger';

const screenBoundaryLogger = logger.createContextLogger('ScreenErrorBoundary');

interface Props {
  children: React.ReactNode;
  /** 出错的是哪一屏 —— 进日志，方便直接定位，⛔ 不要只记「某个屏崩了」。 */
  routeName?: string;
}
interface State {
  error: Error | null;
}

/**
 * 把渲染异常**关在一屏里**，而不是掀掉整棵导航树。
 *
 * <h3>为什么需要它（2026-08-29 逐角色走查实测）</h3>
 *
 * {@link AppErrorBoundary} 包的是 {@code <AppNavigator />} —— 粒度是**整个 App**。
 * 于是任何一屏的渲染异常都会把导航器整个卸载：用户看到的不是「这一页打不开」，
 * 而是**整个应用只剩一张错误卡**，其它功能一并消失。
 *
 * <p>实测：走查里 {@code navigationRef} 直接变成 {@code null}，
 * 连「还有哪些屏」都枚举不出来了 —— 这正是仪器只发现 12 条路由就收敛的原因。
 *
 * <h3>用法</h3>
 *
 * 挂在 navigator 上而不是逐屏包（react-navigation v7 的 {@code screenLayout}）：
 *
 * <pre>
 *   &lt;Stack.Navigator screenLayout={screenErrorBoundary}&gt;
 * </pre>
 *
 * ⚠️ 它是**兜底**，不是免检：一屏该显示什么由那一屏自己负责。
 * 详情屏拿不到 id 的正解是 {@code MissingRouteParams}（一句人能读的话），
 * 落到这里说明还有没料到的异常。
 */
export class ScreenErrorBoundary extends React.Component<Props, State> {
  state: State = { error: null };

  static getDerivedStateFromError(error: Error): State {
    return { error };
  }

  componentDidCatch(error: Error, info: React.ErrorInfo) {
    screenBoundaryLogger.error('屏级渲染异常', error, {
      routeName: this.props.routeName,
      componentStack: info.componentStack?.slice(0, 800),
    });
  }

  render() {
    const { error } = this.state;
    if (!error) return this.props.children;
    return (
      <View style={styles.container} testID="screen-error-boundary">
        <Text style={styles.title}>这一页打不开</Text>
        <Text style={styles.hint}>其它功能还能用，可以返回上一页继续。</Text>
        <Text style={styles.detail} numberOfLines={3}>
          {this.props.routeName ? `${this.props.routeName}: ` : ''}
          {error.message}
        </Text>
        <Button
          mode="contained"
          testID="screen-error-boundary-retry"
          style={styles.button}
          onPress={() => this.setState({ error: null })}
        >
          重试这一页
        </Button>
      </View>
    );
  }
}

/**
 * 直接传给 navigator 的 {@code screenLayout}。
 *
 * <pre>&lt;Stack.Navigator screenLayout={screenErrorBoundary}&gt;</pre>
 */
export const screenErrorBoundary = ({
  children,
  route,
}: {
  children: React.ReactElement;
  route?: { name?: string };
}) => <ScreenErrorBoundary routeName={route?.name}>{children}</ScreenErrorBoundary>;

const styles = StyleSheet.create({
  button: { marginTop: 20 },
  container: {
    alignItems: 'center',
    backgroundColor: '#fff',
    flex: 1,
    justifyContent: 'center',
    paddingHorizontal: 32,
  },
  detail: { color: '#bdbdbd', fontSize: 11, marginTop: 12, textAlign: 'center' },
  hint: { color: '#9e9e9e', fontSize: 13, marginTop: 8, textAlign: 'center' },
  title: { color: '#616161', fontSize: 16, fontWeight: '600' },
});

export default ScreenErrorBoundary;
