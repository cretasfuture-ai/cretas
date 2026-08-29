/**
 * 关于页面
 */
import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Linking,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import * as Application from 'expo-application';
import { checkForOtaUpdateManual } from '../../../services/otaUpdateService';

// ⛔ 同上(EnhancedLoginScreen): 版本号不许写死。
//    2026-08-25 小米真机实测, 同一台设备上三个数字互不相干:
//      真实 APK   versionName=1.0.4  versionCode=15   (dumpsys package)
//      登录页     v1.0.0.1                            (写死)
//      本页       1.0.0 (20251228)                    (写死)
//    本仓形态 D: 同一个东西有三份, 必定漂。读不到就留空, 不要给假值。
const APP_VERSION = Application.nativeApplicationVersion;
const BUILD_NUMBER = Application.nativeBuildVersion;
const COPYRIGHT_END_YEAR = new Date().getFullYear();

export function AboutScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('profile');

  const handleCheckUpdate = async () => {
    const result = await checkForOtaUpdateManual();
    const messageKey = {
      up_to_date: 'about.latestVersion',
      available: 'about.updateAvailable',
      downloaded: 'about.updateReady',
      disabled: 'about.updateCheckUnavailable',
      failed: 'about.updateCheckFailed',
      timeout: 'about.updateCheckTimeout',
    }[result.status];
    Alert.alert(t('about.checkUpdate'), t(messageKey));
  };

  const handlePrivacyPolicy = () => {
    Linking.openURL('https://www.cretas.com/privacy');
  };

  const handleUserAgreement = () => {
    Linking.openURL('https://www.cretas.com/terms');
  };

  const MenuItem = ({ icon, title, onPress, rightText }: {
    icon: string;
    title: string;
    onPress: () => void;
    rightText?: string;
  }) => (
    <TouchableOpacity style={styles.menuItem} onPress={onPress}>
      <Icon source={icon} size={22} color="#666" />
      <Text style={styles.menuTitle}>{title}</Text>
      <View style={styles.menuRight}>
        {rightText && <Text style={styles.rightText}>{rightText}</Text>}
        <Icon source="chevron-right" size={20} color="#ccc" />
      </View>
    </TouchableOpacity>
  );

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backButton}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.title}>{t('about.title')}</Text>
        <View style={{ width: 32 }} />
      </View>

      <ScrollView style={styles.content}>
        {/* Logo 和版本信息 */}
        <View style={styles.logoSection}>
          <View style={styles.logoContainer}>
            <Icon source="fish" size={60} color="#667eea" />
          </View>
          <Text style={styles.appName}>{t('about.appName')}</Text>
          <Text style={styles.version}>
            {t('about.version')} {APP_VERSION ?? '—'}{BUILD_NUMBER ? ` (${BUILD_NUMBER})` : ''}
          </Text>
        </View>

        {/* 功能介绍 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('about.productIntro')}</Text>
          <View style={styles.descriptionCard}>
            <Text style={styles.descriptionText}>
              {t('about.productDescription')}
            </Text>
          </View>
        </View>

        {/* 菜单选项 */}
        <View style={styles.section}>
          <View style={styles.menuCard}>
            <MenuItem
              icon="update"
              title={t('about.checkUpdate')}
              onPress={() => void handleCheckUpdate()}
              rightText={APP_VERSION ? `v${APP_VERSION}` : '—'}
            />
            <View style={styles.divider} />
            <MenuItem
              icon="shield-check"
              title={t('about.privacyPolicy')}
              onPress={handlePrivacyPolicy}
            />
            <View style={styles.divider} />
            <MenuItem
              icon="file-document"
              title={t('about.userAgreement')}
              onPress={handleUserAgreement}
            />
          </View>
        </View>

        {/* 版权信息 */}
        <View style={styles.copyrightSection}>
          <Text style={styles.copyrightText}>
            Copyright 2024-{COPYRIGHT_END_YEAR} Cretas Technology
          </Text>
          <Text style={styles.copyrightText}>
            All Rights Reserved
          </Text>
        </View>

        <View style={{ height: 32 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
    backgroundColor: '#fff',
    borderBottomWidth: 1,
    borderBottomColor: '#e0e0e0',
  },
  backButton: {
    padding: 4,
  },
  title: {
    fontSize: 18,
    fontWeight: '600',
    color: '#333',
  },
  content: {
    flex: 1,
  },
  logoSection: {
    alignItems: 'center',
    paddingVertical: 40,
    backgroundColor: '#fff',
    marginBottom: 16,
  },
  logoContainer: {
    width: 100,
    height: 100,
    borderRadius: 20,
    backgroundColor: '#f0f4ff',
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 16,
  },
  appName: {
    fontSize: 20,
    fontWeight: '600',
    color: '#333',
    marginBottom: 8,
  },
  version: {
    fontSize: 14,
    color: '#999',
  },
  section: {
    marginBottom: 16,
  },
  sectionTitle: {
    fontSize: 13,
    color: '#999',
    marginLeft: 20,
    marginBottom: 8,
  },
  descriptionCard: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    borderRadius: 12,
    padding: 16,
  },
  descriptionText: {
    fontSize: 14,
    color: '#666',
    lineHeight: 24,
  },
  menuCard: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: 'hidden',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: 16,
  },
  menuTitle: {
    flex: 1,
    fontSize: 16,
    color: '#333',
    marginLeft: 12,
  },
  menuRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  rightText: {
    fontSize: 14,
    color: '#999',
    marginRight: 4,
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: '#f0f0f0',
    marginHorizontal: 16,
  },
  copyrightSection: {
    alignItems: 'center',
    paddingVertical: 24,
  },
  copyrightText: {
    fontSize: 12,
    color: '#999',
    marginBottom: 4,
  },
});

export default AboutScreen;
