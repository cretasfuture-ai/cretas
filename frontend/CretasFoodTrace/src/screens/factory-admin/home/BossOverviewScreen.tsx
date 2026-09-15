import React from 'react';
import { StyleSheet, View } from 'react-native';
import { Appbar, Button, Card, Text } from 'react-native-paper';
import { useNavigation } from '@react-navigation/native';

type BossRoute =
  | 'BossProductionPlans'
  | 'BossSalesOrders'
  | 'BossPurchaseOrders'
  | 'BossFinishedGoods'
  // 通用现场能力 —— 注册在同一个 OverviewStack 里（见 BossNavigator.tsx）。
  | 'Traceability'
  | 'VisionVerify'
  | 'SourceIndividualForm';

/**
 * 首页分两组（2026-09-15 真机实测：7 个等权按钮平铺「生产销售采购全部放在这里很乱」）。
 *
 * - 「现场作业」置顶：溯源查询 / 拍照核验 / 源头个体 —— 演示与现场巡检真正会点的三个。
 * - 「经营」：生产进度 / 销售订单 / 采购订单 / 成品库存。
 *
 * ⚠️ 三个 testID（boss-entry-*）被 `__tests__/gates/factoryBossReachesFieldEntries.test.ts`
 *    钉着 —— 改名会让工厂超管「到得了现场能力屏」这道闸红。
 */
export default function BossOverviewScreen() {
  const navigation = useNavigation();
  const open = (route: BossRoute) => navigation.navigate(route as never);

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.Content title="经营总览" subtitle="查看进度与异常" />
      </Appbar.Header>
      <Card style={styles.notice}>
        <Card.Content>
          <Text style={styles.noticeText}>查看与决策，不替岗位录入；待处理事项进“审批”。</Text>
        </Card.Content>
      </Card>

      <Text variant="titleSmall" style={styles.groupTitle}>现场作业</Text>
      <View style={styles.grid}>
        <Button mode="contained-tonal" icon="qrcode-scan" testID="boss-entry-traceability" onPress={() => open('Traceability')} style={styles.button}>溯源查询</Button>
        <Button mode="contained-tonal" icon="camera-outline" testID="boss-entry-vision-verify" onPress={() => open('VisionVerify')} style={styles.button}>拍照核验</Button>
        <Button mode="contained-tonal" icon="card-account-details-outline" testID="boss-entry-source-individual" onPress={() => open('SourceIndividualForm')} style={styles.button}>源头个体</Button>
      </View>

      <Text variant="titleSmall" style={styles.groupTitle}>经营</Text>
      <View style={styles.grid}>
        <Button mode="contained-tonal" icon="factory" onPress={() => open('BossProductionPlans')} style={styles.button}>生产进度</Button>
        <Button mode="contained-tonal" icon="cart-outline" onPress={() => open('BossSalesOrders')} style={styles.button}>销售订单</Button>
        <Button mode="contained-tonal" icon="package-down" onPress={() => open('BossPurchaseOrders')} style={styles.button}>采购订单</Button>
        <Button mode="contained-tonal" icon="package-variant-closed" onPress={() => open('BossFinishedGoods')} style={styles.button}>成品库存</Button>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F7FA' },
  notice: { marginHorizontal: 16, marginTop: 12, marginBottom: 4, backgroundColor: '#E8F0FE' },
  noticeText: { color: '#315A8A', fontSize: 13, lineHeight: 18 },
  groupTitle: { marginHorizontal: 16, marginTop: 16, marginBottom: 8, color: '#5F6B7A', fontWeight: '600' },
  grid: { paddingHorizontal: 16, gap: 12 },
  button: { minHeight: 52, justifyContent: 'center' },
});
