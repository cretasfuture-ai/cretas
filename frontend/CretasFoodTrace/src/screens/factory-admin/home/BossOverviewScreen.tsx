import React from 'react';
import { ScrollView, StyleSheet, View } from 'react-native';
import { Appbar, List, Surface, Text, TouchableRipple, useTheme } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';

import type { AppTheme } from '../../../theme';

type BossRoute =
  | 'BossProductionPlans'
  | 'BossSalesOrders'
  | 'BossPurchaseOrders'
  | 'BossFinishedGoods'
  // 通用现场能力 —— 注册在同一个 OverviewStack 里（见 BossNavigator.tsx）。
  | 'Traceability'
  | 'VisionVerify'
  | 'SourceIndividualForm';

type IconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

/**
 * 「现场作业」：这个角色每天要按的三个 —— 做成大瓷砖（2 列，≥96dp），一眼看到。
 * ⚠️ 三个 testID（boss-entry-*）被 `__tests__/gates/factoryBossReachesFieldEntries.test.ts`
 *    钉着 —— 改名会让工厂超管「到得了现场能力屏」这道闸红。
 */
const FIELD_ENTRIES: ReadonlyArray<{ route: BossRoute; icon: IconName; label: string; testID: string }> = [
  { route: 'Traceability', icon: 'qrcode-scan', label: '溯源查询', testID: 'boss-entry-traceability' },
  { route: 'VisionVerify', icon: 'camera-outline', label: '拍照核验', testID: 'boss-entry-vision-verify' },
  { route: 'SourceIndividualForm', icon: 'card-account-details-outline', label: '源头个体', testID: 'boss-entry-source-individual' },
];

/** 「经营」：次要动作，列表项（≥48dp），视觉权重比瓷砖轻。 */
const BUSINESS_ENTRIES: ReadonlyArray<{ route: BossRoute; icon: IconName; label: string }> = [
  { route: 'BossProductionPlans', icon: 'factory', label: '生产进度' },
  { route: 'BossSalesOrders', icon: 'cart-outline', label: '销售订单' },
  { route: 'BossPurchaseOrders', icon: 'package-down', label: '采购订单' },
  { route: 'BossFinishedGoods', icon: 'package-variant-closed', label: '成品库存' },
];

/** 两块一行。 */
function chunkPairs<T>(items: ReadonlyArray<T>): T[][] {
  const rows: T[][] = [];
  for (let i = 0; i < items.length; i += 2) {
    rows.push(items.slice(i, i + 2));
  }
  return rows;
}

/**
 * 首页分两组（2026-09-15 真机实测：7 个等权按钮平铺「生产销售采购全部放在这里很乱」）。
 *
 * - 「现场作业」置顶：溯源查询 / 拍照核验 / 源头个体 —— 演示与现场巡检真正会点的三个。
 * - 「经营」：生产进度 / 销售订单 / 采购订单 / 成品库存。
 *
 * 2026-09-15 polish：说明卡（「查看与决策，不替岗位录入…」）是政策说明不是功能，删掉；
 * 两组不再是 7 个一模一样的全宽按钮 —— 现场作业是瓷砖，经营是列表。
 * 颜色全部走 `useTheme()`，本文件里不出现色值。
 */
export default function BossOverviewScreen() {
  const navigation = useNavigation();
  const theme = useTheme<AppTheme>();
  const open = (route: BossRoute) => navigation.navigate(route as never);
  const { spacing, borderRadius } = theme.custom;

  return (
    <View style={[styles.container, { backgroundColor: theme.colors.background }]}>
      <Appbar.Header>
        <Appbar.Content title="经营总览" />
      </Appbar.Header>
      <ScrollView contentContainerStyle={{ paddingBottom: spacing.xl }}>
        <Text
          variant="titleSmall"
          style={{
            color: theme.colors.onSurfaceVariant,
            marginHorizontal: spacing.l,
            marginTop: spacing.l,
            marginBottom: spacing.s,
          }}
        >
          现场作业
        </Text>
        <View style={{ paddingHorizontal: spacing.l, gap: spacing.m }}>
          {chunkPairs(FIELD_ENTRIES).map((pair, rowIndex) => (
            <View key={`tile-row-${rowIndex}`} style={[styles.tileRow, { gap: spacing.m }]}>
              {pair.map((entry) => (
                <Surface
                  key={entry.route}
                  elevation={0}
                  style={[styles.tile, { borderRadius: borderRadius.m, backgroundColor: theme.colors.primaryContainer }]}
                >
                  <TouchableRipple
                    onPress={() => open(entry.route)}
                    testID={entry.testID}
                    accessibilityRole="button"
                    accessibilityLabel={entry.label}
                    borderless
                    style={[styles.tileTouchable, { borderRadius: borderRadius.m }]}
                  >
                    <View style={[styles.tileInner, { gap: spacing.s }]}>
                      <MaterialCommunityIcons name={entry.icon} size={32} color={theme.colors.onPrimaryContainer} />
                      <Text variant="titleMedium" style={{ color: theme.colors.onPrimaryContainer }}>
                        {entry.label}
                      </Text>
                    </View>
                  </TouchableRipple>
                </Surface>
              ))}
              {/* 单数那一行补一个占位，让最后一块保持与其它块同宽，⛔ 不拉成全宽 */}
              {pair.length === 1 && <View style={styles.tile} />}
            </View>
          ))}
        </View>

        <List.Section>
          <Text
            variant="titleSmall"
            style={{
              color: theme.colors.onSurfaceVariant,
              marginHorizontal: spacing.l,
              marginTop: spacing.l,
              marginBottom: spacing.s,
            }}
          >
            经营
          </Text>
          {BUSINESS_ENTRIES.map((entry) => (
            <List.Item
              key={entry.route}
              title={entry.label}
              titleStyle={{ color: theme.colors.onSurface }}
              onPress={() => open(entry.route)}
              left={(props) => <List.Icon {...props} icon={entry.icon} color={theme.colors.onSurfaceVariant} />}
              right={(props) => <List.Icon {...props} icon="chevron-right" />}
              style={[styles.listItem, { paddingHorizontal: spacing.l }]}
            />
          ))}
        </List.Section>
      </ScrollView>
    </View>
  );
}

// ⚠️ 这里只放布局：颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant，
//    `gates/demoScreensUseThemeTokens` 守着本文件里不再出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  // 2 列：每行两块各占一半（gap 由父级 spacing.m 提供）。
  tileRow: { flexDirection: 'row' },
  tile: { flex: 1, minHeight: 96, overflow: 'hidden' },
  tileTouchable: { flex: 1, minHeight: 96 },
  tileInner: { flex: 1, alignItems: 'center', justifyContent: 'center' },
  listItem: { minHeight: 48 },
});
