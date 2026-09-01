import React from 'react';
import { Alert, Image, Platform, ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { TouchableRipple } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as Application from 'expo-application';
import { useTranslation } from 'react-i18next';

import { useAuthStore } from '../../store/authStore';
import { LANGUAGE_NAMES, type SupportedLanguage, useLanguageStore } from '../../store/languageStore';
import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;
const APP_VERSION = Application.nativeApplicationVersion;

export default function QIProfileScreen() {
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const { user, logout } = useAuthStore();
  const { language, setLanguage } = useLanguageStore();
  const { t } = useTranslation('quality');

  const toggleLanguage = () => {
    const next: SupportedLanguage = language === 'zh-CN' ? 'en-US' : 'zh-CN';
    setLanguage(next);
  };

  const handleLogout = () => {
    const account = user?.fullName
      ? `${user.fullName}（${t('profile.inspector')}）`
      : `@${user?.username ?? ''}（${t('profile.inspector')}）`;
    const message = `确定退出 ${account} 吗？`;
    if (Platform.OS === 'web') {
      if (window.confirm(message)) logout();
      return;
    }
    Alert.alert('退出登录', message, [
      { text: '取消', style: 'cancel' },
      { text: '退出', style: 'destructive', onPress: logout },
    ]);
  };

  return (
    <ScrollView
      style={styles.screen}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      testID="qi-profile-screen"
    >
      <View style={styles.profileCard}>
        {user?.avatar ? (
          <Image source={{ uri: user.avatar }} style={styles.avatar} />
        ) : (
          <View style={styles.avatarPlaceholder}>
            <Ionicons name="person" size={34} color="#fff" />
          </View>
        )}
        <View style={styles.userCopy}>
          <Text style={styles.userName}>{user?.fullName || user?.username || '质检员'}</Text>
          <Text style={styles.role}>现场拍照质检员</Text>
          {user?.factoryId && <Text style={styles.factory}>工厂：{user.factoryId}</Text>}
        </View>
      </View>

      <View style={styles.menuCard}>
        <TouchableRipple
          style={styles.menuItem}
          onPress={toggleLanguage}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="切换语言"
        >
          <View style={styles.menuContent}>
            <Ionicons name="language-outline" size={23} color={QI_COLORS.textSecondary} />
            <Text style={styles.menuLabel}>语言</Text>
            <Text style={styles.menuValue}>{LANGUAGE_NAMES[language]}</Text>
            <Ionicons name="chevron-forward" size={20} color={QI_COLORS.disabled} />
          </View>
        </TouchableRipple>
        <TouchableRipple
          style={styles.menuItem}
          onPress={() => navigation.navigate('QISettings')}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="打开设置"
        >
          <View style={styles.menuContent}>
            <Ionicons name="settings-outline" size={23} color={QI_COLORS.textSecondary} />
            <Text style={styles.menuLabel}>设置</Text>
            <Ionicons name="chevron-forward" size={20} color={QI_COLORS.disabled} />
          </View>
        </TouchableRipple>
        <View style={styles.menuContent}>
          <Ionicons name="information-circle-outline" size={23} color={QI_COLORS.textSecondary} />
          <Text style={styles.menuLabel}>版本</Text>
          <Text style={styles.menuValue}>
            {APP_VERSION ? `v${APP_VERSION}` : '—'}
          </Text>
        </View>
      </View>

      <TouchableRipple
        style={styles.logoutButton}
        onPress={handleLogout}
        borderless={false}
        accessibilityRole="button"
        accessibilityLabel="退出登录"
        testID="qi-profile-logout-button"
      >
        <View style={styles.logoutContent}>
          <Ionicons name="log-out-outline" size={22} color={QI_COLORS.danger} />
          <Text style={styles.logoutText}>退出登录</Text>
        </View>
      </TouchableRipple>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1, backgroundColor: QI_COLORS.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 16 },
  profileCard: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 20,
    borderRadius: 16,
    backgroundColor: QI_COLORS.card,
  },
  avatar: { width: 64, height: 64, borderRadius: 32 },
  avatarPlaceholder: {
    width: 64,
    height: 64,
    borderRadius: 32,
    alignItems: 'center',
    justifyContent: 'center',
    backgroundColor: QI_COLORS.primary,
  },
  userCopy: { flex: 1, marginLeft: 15 },
  userName: { fontSize: 19, fontWeight: '800', color: QI_COLORS.text },
  role: { marginTop: 4, fontSize: 14, color: QI_COLORS.textSecondary },
  factory: { marginTop: 3, fontSize: 12, color: QI_COLORS.textSecondary },
  menuCard: { marginTop: 16, borderRadius: 16, backgroundColor: QI_COLORS.card, overflow: 'hidden' },
  menuItem: { minHeight: 56, borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: QI_COLORS.border },
  menuContent: { minHeight: 56, flexDirection: 'row', alignItems: 'center', paddingHorizontal: 16 },
  menuLabel: { flex: 1, marginLeft: 12, fontSize: 15, color: QI_COLORS.text },
  menuValue: { marginRight: 8, fontSize: 14, color: QI_COLORS.textSecondary },
  logoutButton: {
    minHeight: 56,
    marginTop: 18,
    borderRadius: 14,
    backgroundColor: QI_COLORS.card,
    overflow: 'hidden',
  },
  logoutContent: { minHeight: 56, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8 },
  logoutText: { fontSize: 16, fontWeight: '700', color: QI_COLORS.danger },
});
