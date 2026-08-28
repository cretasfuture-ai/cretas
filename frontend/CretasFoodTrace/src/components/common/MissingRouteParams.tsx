import React from 'react';
import { View, StyleSheet } from 'react-native';
import { Text, Appbar } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';

/**
 * 「这一屏需要参数，而它没拿到」——把崩溃换成一句人能读的话。
 *
 * <h3>为什么需要（2026-08-28 表单走查实测）</h3>
 *
 * 详情/表单屏普遍写成 `const { xxxId } = route.params;`。没有 params 时它**直接抛**：
 *
 * <pre>Cannot destructure property 'batchId' of 'route.params' as it is undefined.</pre>
 *
 * 走查里三屏当场崩（`WHReceiptCreate` / `WHPutaway` / `WHDeliveryConfirm`），
 * 由第三轮加的 `AppErrorBoundary` 接住，屏上显示「这一页打不开」+ 那句英文异常。
 * 全仓这个写法共 **68 处**。
 *
 * ⚠️ 这一类**大多是潜伏的**：正常入口（列表卡片点进来）都传了参数。
 * 所以这里不建闸去逼 68 屏全改 —— 那是给每个新详情屏加税，
 * 而错误边界已经把影响面限制在「这一页」。只把**实测崩过的**换成这个组件。
 *
 * ⇒ 新写详情屏时用它：`if (!id) return <MissingRouteParams title="收货" hint="…" />`
 */
export function MissingRouteParams({
  title,
  hint,
  onBack,
}: {
  /** Appbar 标题，用这一屏本来的名字。 */
  title: string;
  /** 告诉使用者**该从哪进来**，⛔ 不要只说「参数缺失」。 */
  hint: string;
  onBack?: () => void;
}) {
  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Appbar.Header>
        {onBack ? <Appbar.BackAction onPress={onBack} /> : null}
        <Appbar.Content title={title} />
      </Appbar.Header>
      <View style={styles.body} testID="missing-route-params">
        <Text style={styles.title}>没有拿到要打开的对象</Text>
        <Text style={styles.hint}>{hint}</Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  body: { alignItems: 'center', flex: 1, justifyContent: 'center', paddingHorizontal: 32 },
  container: { backgroundColor: '#fff', flex: 1 },
  hint: { color: '#9e9e9e', fontSize: 13, lineHeight: 20, marginTop: 8, textAlign: 'center' },
  title: { color: '#616161', fontSize: 15, fontWeight: '600' },
});

export default MissingRouteParams;
