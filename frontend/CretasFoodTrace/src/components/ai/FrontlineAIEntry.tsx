/**
 * 一线角色首页的「AI 助手 · 说一句话」入口。
 *
 * 用在：操作员工序首页、仓库员三个 tab 首屏、质检员首页。组件**自己**判断显不显示
 * （角色 ∈ FRONTLINE_AI_ROLES 且熔断开关两层都开），所以放进共用屏幕（仓储三屏也给 warehouse_manager 用）
 * 不会给别的角色多出一个入口。
 *
 * 设计回应（docs/specs/rn-frontline-ai-entry-2026-10-05.md）：
 *  F1 全宽、≥64pt 高、图标 + 两行字，戴手套也点得中、认得出
 *  F2 描边次级样式，不抢页面主操作
 *  F6 导航**不带 entityType** —— 带了会触发 AIChatScreen 的场景过滤，把澄清/确认按钮滤掉
 */
import React from 'react';
import { StyleSheet, View, type StyleProp, type ViewStyle } from 'react-native';
import { Text, TouchableRipple, useTheme } from 'react-native-paper';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation, type NavigationProp, type ParamListBase } from '@react-navigation/native';
import type { AppTheme } from '../../theme';
import { useFrontlineAIEntryEnabled } from '../../hooks/useFrontlineAIEntry';

export const FRONTLINE_AI_ENTRY_TEST_ID = 'frontline-ai-entry';
export const FRONTLINE_AI_CHAT_ROUTE = 'AIChat';

interface FrontlineAIEntryProps {
  /** 外层留白（放在哪个屏由那个屏决定）。入口不显示时外层也不渲染 —— 不留一条空白。 */
  containerStyle?: StyleProp<ViewStyle>;
}

export function FrontlineAIEntry({ containerStyle }: FrontlineAIEntryProps) {
  const enabled = useFrontlineAIEntryEnabled();
  const navigation = useNavigation<NavigationProp<ParamListBase>>();
  const theme = useTheme<AppTheme>();
  if (!enabled) return null;

  return (
    <View style={containerStyle}>
      <TouchableRipple
        testID={FRONTLINE_AI_ENTRY_TEST_ID}
        accessibilityRole="button"
        accessibilityLabel="AI 助手，点这里说一句话"
        borderless={false}
        onPress={() => navigation.navigate(FRONTLINE_AI_CHAT_ROUTE)}
        style={[
          styles.entry,
          {
            backgroundColor: theme.colors.surface,
            borderColor: theme.colors.primary,
            borderRadius: theme.custom.borderRadius.l,
          },
        ]}
      >
        <View style={styles.row}>
          <View
            style={[
              styles.icon,
              { backgroundColor: theme.colors.primaryContainer, borderRadius: theme.custom.borderRadius.l },
            ]}
          >
            <MaterialCommunityIcons name="microphone" size={32} color={theme.colors.onPrimaryContainer} />
          </View>
          <View style={styles.copy}>
            <Text variant="titleMedium" style={[styles.title, { color: theme.colors.onSurface }]}>
              AI 助手
            </Text>
            <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant }}>
              点这里，说一句话
            </Text>
          </View>
          <MaterialCommunityIcons name="chevron-right" size={28} color={theme.colors.onSurfaceVariant} />
        </View>
      </TouchableRipple>
    </View>
  );
}

// ⚠️ 只放布局：颜色全部走 useTheme() token（闸 gates/demoScreensUseThemeTokens 守着本文件）。
const styles = StyleSheet.create({
  entry: {
    minHeight: 64,
    borderWidth: 1.5,
    overflow: 'hidden',
  },
  row: {
    minHeight: 64,
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 14,
    paddingVertical: 10,
  },
  icon: {
    width: 48,
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  copy: {
    flex: 1,
    marginHorizontal: 12,
  },
  title: {
    fontWeight: '700',
  },
});

export default FrontlineAIEntry;
