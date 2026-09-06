import React, { useMemo } from 'react';
import { View, StyleSheet, ScrollView, Alert, TouchableOpacity } from 'react-native';
import { Text, Avatar, Icon } from 'react-native-paper';
import { useNavigation, useRoute, CommonActions } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../store/authStore';
import { ModuleCard } from './components/ModuleCard';
import { QuickStatsPanel } from './components/QuickStatsPanel';
import { MainTabParamList } from '../../types/navigation';
import { ROLE_METADATA } from '../../types/auth';
import { ScreenWrapper } from '../../components/ui';
import { theme } from '../../theme';
import { useFactoryFeatureStore } from '../../store/factoryFeatureStore';
import {
  resolveHomeModules,
  toHomeModuleSetId,
  type ResolvedHomeModule,
} from './homeModules';

type HomeScreenNavigationProp = NativeStackNavigationProp<MainTabParamList, 'HomeTab'>;

/**
 * Home Screen (Neo Minimal Style)
 * 
 * Main dashboard with clean layout and standard components.
 */
export default function HomeScreen() {
  const navigation = useNavigation<HomeScreenNavigationProp>();
  const route = useRoute();
  const { user } = useAuthStore();
  const { t } = useTranslation('home');
  const { isModuleEnabled } = useFactoryFeatureStore();

  /**
   * 模块集由**注册这块首页的导航器**通过 `initialParams` 指定。
   * ⛔ 不用全局可变量：同一个 HomeScreen 组件被 5 棵树复用，全局量会串台。
   */
  const moduleSetId = toHomeModuleSetId(
    (route.params as { moduleSet?: unknown } | undefined)?.moduleSet
  );

  const modules: ResolvedHomeModule[] = useMemo(
    () => resolveHomeModules(moduleSetId, user, isModuleEnabled, t),
    [moduleSetId, user, t, isModuleEnabled]
  );

  const handleModulePress = (module: ResolvedHomeModule) => {
    if (module.status === 'locked') {
      Alert.alert(
        t('alerts.noAccess.title'),
        t('alerts.noAccess.message', { moduleName: module.name })
      );
      return;
    }

    if (module.route) {
      // ⚠️ navigate 只向上冒泡，不会钻进兄弟 Tab 的子栈 ⇒ 跨 Tab 必须带 `{ screen }`
      //    （同 `WHHomeScreen.tsx` 的写法）。
      navigation.dispatch(
        CommonActions.navigate(
          module.nestedScreen
            ? { name: module.route, params: { screen: module.nestedScreen } }
            : { name: module.route }
        )
      );
    } else if (module.id === 'settings') {
      Alert.alert(t('alerts.settingsInProgress.title'), t('alerts.settingsInProgress.message'));
    }
  };

  // User Display Info
  const displayName = user?.fullName || user?.username || 'User';
  const roleText = user?.userType === 'platform'
    ? t('roles.platformAdmin')
    : user?.userType === 'factory'
      ? getRoleDisplayName(user.factoryUser?.role || 'viewer', t)
      : 'User';

  return (
    <ScreenWrapper backgroundColor={theme.colors.background} edges={['top']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.contentContainer}
        showsVerticalScrollIndicator={false}
      >
        {/* Custom Header */}
        <View style={styles.header}>
          <View style={styles.userInfo}>
            <Avatar.Text 
              size={48} 
              label={displayName.charAt(0).toUpperCase()} 
              style={{ backgroundColor: theme.colors.primaryContainer }}
              color={theme.colors.onPrimaryContainer}
            />
            <View style={styles.userText}>
              <Text variant="titleMedium" style={styles.greeting}>{t('greeting')}, {displayName}</Text>
              <Text variant="bodySmall" style={styles.role}>{roleText}</Text>
            </View>
          </View>
          <TouchableOpacity
            onPress={() => Alert.alert(t('alerts.notification.title'), t('alerts.notification.noNew'))}
            style={styles.notificationButton}
          >
            <Icon source="bell-outline" size={24} color={theme.colors.onSurface} />
          </TouchableOpacity>
        </View>

        {/* Quick Stats */}
        {user && (
          <View style={styles.section}>
            <QuickStatsPanel user={user} />
          </View>
        )}

        {/* Modules Grid —— 兜底：一张卡都没有时整段不渲染，
            ⛔ 不留一个下面空无一物的「功能模块」标题（这次缺陷的可见症状）。 */}
        {modules.length > 0 && (
          <View style={styles.section} testID="home-modules-section">
            <Text variant="titleLarge" style={styles.sectionTitle}>{t('sections.modules')}</Text>
            <View style={styles.modulesGrid}>
              {modules.map(module => (
                <ModuleCard
                  key={module.id}
                  module={module}
                  testID={'home-module-' + module.id}
                  onPress={() => handleModulePress(module)}
                />
              ))}
            </View>
          </View>
        )}

        <View style={styles.bottomSpacer} />
      </ScrollView>
    </ScreenWrapper>
  );
}

/**
 * APP-UX-006 (2026-08-02): 原来这里是一张只有 6 个角色的私有表, 表外的角色
 * (财务主管/出纳/销售主管/采购主管/生产经理…) 直接 `return role` —— 首页顶部
 * 于是显示内部英文角色码。改成: 由角色码推出 i18n key, **兜底取权威表
 * ROLE_METADATA 的中文名**, 这样以后新增角色即使漏了翻译也不会再泄漏角色码。
 */
function roleI18nKey(role: string): string {
  return `roles.${role.replace(/_([a-z])/g, (_m, c: string) => c.toUpperCase())}`;
}

function getRoleDisplayName(
  role: string,
  t: (key: string, options?: { defaultValue?: string }) => string
): string {
  return t(roleI18nKey(role), {
    defaultValue: ROLE_METADATA[role]?.displayName || role,
  });
}

const styles = StyleSheet.create({
  scrollView: {
    flex: 1,
  },
  contentContainer: {
    padding: 16,
    paddingTop: 24, // Extra padding for top
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 32,
  },
  userInfo: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 12,
  },
  userText: {
    justifyContent: 'center',
  },
  greeting: {
    fontWeight: '700',
    color: theme.colors.text,
    fontSize: 20,
  },
  role: {
    color: theme.colors.onSurfaceVariant,
    marginTop: 2,
  },
  notificationButton: {
    padding: 8,
    backgroundColor: theme.colors.surface,
    borderRadius: theme.custom.borderRadius.round,
    ...theme.custom.shadows.small,
  },
  section: {
    marginBottom: 24,
  },
  sectionTitle: {
    fontWeight: '700',
    marginBottom: 16,
    color: theme.colors.text,
    fontSize: 18,
  },
  modulesGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    justifyContent: 'space-between',
  },
  bottomSpacer: {
    height: 40,
  },
});
