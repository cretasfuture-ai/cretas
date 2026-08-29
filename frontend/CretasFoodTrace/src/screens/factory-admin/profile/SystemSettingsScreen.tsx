/**
 * 系统设置页面
 */
import React from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  Alert,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation } from '@react-navigation/native';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import AsyncStorage from '@react-native-async-storage/async-storage';

export function SystemSettingsScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('profile');
  const handleClearCache = () => {
    Alert.alert(
      t('systemSettings.clearCacheTitle'),
      t('systemSettings.clearCacheConfirm'),
      [
        { text: t('systemSettings.cancel'), style: 'cancel' },
        {
          text: t('systemSettings.confirm'),
          onPress: async () => {
            try {
              // 清除部分缓存
              const keys = await AsyncStorage.getAllKeys();
              const cacheKeys = keys.filter(key => key.startsWith('cache_'));
              await AsyncStorage.multiRemove(cacheKeys);
              Alert.alert(t('messages.saveSuccess'), t('messages.cacheCleared'));
            } catch (error) {
              Alert.alert(t('systemSettings.error'), t('systemSettings.clearCacheFailed'));
            }
          },
        },
      ]
    );
  };

  const SettingButton = ({ title, onPress }: {
    title: string;
    onPress: () => void;
  }) => (
    <TouchableOpacity style={styles.menuItem} onPress={onPress}>
      <Text style={styles.menuTitle}>{title}</Text>
      <View style={styles.menuRight}>
        <Icon source="chevron-right" size={20} color="#ccc" />
      </View>
    </TouchableOpacity>
  );

  const SettingInfo = ({ title, value }: { title: string; value: string }) => (
    <View style={styles.menuItem}>
      <Text style={styles.menuTitle}>{title}</Text>
      <Text style={styles.rightText}>{value}</Text>
    </View>
  );

  return (
    <SafeAreaView style={styles.container}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity onPress={() => navigation.goBack()} style={styles.backButton}>
          <Icon source="arrow-left" size={24} color="#333" />
        </TouchableOpacity>
        <Text style={styles.title}>{t('systemSettings.title')}</Text>
        <View style={{ width: 32 }} />
      </View>

      <ScrollView style={styles.content}>
        {/* 存储空间 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('systemSettings.storage')}</Text>
          <View style={styles.settingsCard}>
            <SettingButton
              title={t('systemSettings.clearCache')}
              onPress={handleClearCache}
            />
          </View>
        </View>

        {/* 当前连接信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>{t('systemSettings.advancedSettings')}</Text>
          <View style={styles.settingsCard}>
            <SettingInfo
              title={t('systemSettings.serverSettings')}
              value={t('systemSettings.productionEnv')}
            />
          </View>
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
  section: {
    marginTop: 16,
  },
  sectionTitle: {
    fontSize: 13,
    color: '#999',
    marginLeft: 20,
    marginBottom: 8,
  },
  settingsCard: {
    backgroundColor: '#fff',
    marginHorizontal: 16,
    borderRadius: 12,
    overflow: 'hidden',
  },
  menuItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: 16,
  },
  menuTitle: {
    fontSize: 16,
    color: '#333',
  },
  menuRight: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  rightText: {
    fontSize: 14,
    color: '#999',
  },
});

export default SystemSettingsScreen;
