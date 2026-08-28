import React, { Component, type ErrorInfo, type ReactNode } from 'react';
import { ScrollView, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { logger } from '../../utils/logger';

/**
 * 全局错误边界。
 *
 * ## 为什么需要它（2026-08-28 逐屏走查实测）
 *
 * 在这之前，`App.tsx` 里 `<AppNavigator/>` 上面**没有任何错误边界**
 * （全仓唯一的 `componentDidCatch` 在 `MarkdownRenderer` 内部，只管它自己那一小块）。
 * 于是任何一屏的渲染异常都会把整棵 React 树卸载掉，用户看到的**不是报错，是一片白**：
 *
 * ```
 * PAGEERROR: Cannot read properties of undefined (reading 'totalSales')
 * → 导航容器整个卸载, rootState 变 null, 可见文本变 ""
 * ```
 *
 * 逐屏走查 274 屏里出现 **50 次**这个长相。其中两次是用户走得到的
 * （销售分析 / 采购异常，均已修）；更要紧的是**第 51 次一定还会有**，
 * 而这个 App 的使用场景是车间里戴手套的人 —— 白屏对他们等于「手机坏了」，
 * 只能杀进程重开，重开之后还是那一屏。
 *
 * ## 这一层负责什么、不负责什么
 *
 * ✅ 让异常**停在一屏**：给一句看得懂的话 + 一个「再试一次」。
 * ⛔ 不负责让那一屏变对 —— 它只是把「整个 App 没了」降级成「这一屏打不开」。
 *    ⚠️ 所以**不要**因为有了它就把屏内的空值防护省掉：
 *       用户看到「这一页打不开」和看到正确的数据，仍然是两件事。
 *
 * ## 判据
 *
 * `AppErrorBoundaryContractTest`（`src/__tests__/gates/`）：子组件抛异常时
 * ① 不再把整棵树卸载（兜底 UI 渲染出来）② 「再试一次」能让恢复后的子组件重新显示。
 * 阴性对照：不抛异常时**不许**出现兜底 UI —— 否则「永远显示错误页」也能过。
 */

interface Props {
  children: ReactNode;
  /** 测试用：注入一个可观测的重置回调。产品代码不传。 */
  onReset?: () => void;
}

interface State {
  error: Error | null;
  /** 每次「再试一次」自增，用它当 key 强制重挂子树。 */
  resetCount: number;
}

export class AppErrorBoundary extends Component<Props, State> {
  state: State = { error: null, resetCount: 0 };

  static getDerivedStateFromError(error: Error): Partial<State> {
    return { error };
  }

  componentDidCatch(error: Error, errorInfo: ErrorInfo) {
    // ⚠️ 这里不要再抛，也不要在模块顶层建 logger:
    // 错误边界自己抛异常会绕过它自己, 而顶层 `createLogger(...)` 在测试里被 mock
    // 掉之后是 undefined —— 那会让「兜底」本身成为第一个崩溃点。
    const stack = (errorInfo?.componentStack ?? '').slice(0, 2000);
    try {
      logger?.error?.('未捕获的渲染异常', { message: error?.message, stack });
    } catch {
      /* 记日志失败不能变成第二次崩溃 */
    }
  }

  private handleReset = () => {
    this.setState((s) => ({ error: null, resetCount: s.resetCount + 1 }));
    this.props.onReset?.();
  };

  render() {
    const { error } = this.state;
    if (!error) {
      // key 变化 ⇒ 子树重挂，让「再试一次」真的重来一次而不是复用坏掉的实例
      return <React.Fragment key={this.state.resetCount}>{this.props.children}</React.Fragment>;
    }

    return (
      <View style={styles.container} testID="app-error-boundary">
        <View style={styles.card}>
          <Text style={styles.title}>这一页打不开</Text>
          <Text style={styles.body}>
            不是你的操作有问题，是这一页出错了。可以先点「再试一次」；
            还是不行就换个页面用，并把下面这行字告诉技术支持。
          </Text>
          <ScrollView style={styles.detailBox}>
            <Text style={styles.detail} testID="app-error-boundary-message">
              {String(error?.message ?? error)}
            </Text>
          </ScrollView>
          <TouchableOpacity
            style={styles.button}
            onPress={this.handleReset}
            testID="app-error-boundary-retry"
            accessibilityRole="button"
          >
            <Text style={styles.buttonText}>再试一次</Text>
          </TouchableOpacity>
        </View>
      </View>
    );
  }
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F1F5F9', justifyContent: 'center', padding: 20 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 12, padding: 20 },
  title: { fontSize: 20, fontWeight: '700', color: '#0F172A', marginBottom: 10 },
  body: { fontSize: 15, lineHeight: 24, color: '#334155', marginBottom: 14 },
  detailBox: { maxHeight: 140, backgroundColor: '#F8FAFC', borderRadius: 8, padding: 10, marginBottom: 16 },
  detail: { fontSize: 12, lineHeight: 18, color: '#64748B' },
  button: { backgroundColor: '#2563EB', borderRadius: 10, paddingVertical: 14, alignItems: 'center' },
  buttonText: { color: '#FFFFFF', fontSize: 17, fontWeight: '600' },
});

export default AppErrorBoundary;
