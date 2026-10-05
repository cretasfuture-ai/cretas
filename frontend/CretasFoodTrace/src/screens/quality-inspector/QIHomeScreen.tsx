/**
 * 现场质检员首页
 *
 * 该角色只负责发起标签拍检和查看拍检记录。人工审核属于质量审核员的 Web 工作台。
 */

import React from 'react';
import { ScrollView, StyleSheet, Text, View } from 'react-native';
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';
import { useNavigation } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { TouchableRipple } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { QI_COLORS, QualityInspectorStackParamList } from '../../types/qualityInspector';
import { useAuthStore } from '../../store/authStore';
import { FrontlineAIEntry } from '../../components/ai/FrontlineAIEntry';

type NavigationProp = NativeStackNavigationProp<QualityInspectorStackParamList>;

export default function QIHomeScreen() {
  const navigation = useNavigation<NavigationProp>();
  const insets = useSafeAreaInsets();
  const user = useAuthStore((state) => state.user);

  return (
    <ScrollView
      style={styles.container}
      contentContainerStyle={[styles.content, { paddingBottom: insets.bottom + 24 }]}
      testID="qi-home-screen"
    >
      <View style={styles.welcomeSection}>
        <Ionicons name="person-circle" size={48} color={QI_COLORS.primary} />
        <View style={styles.welcomeText}>
          <Text style={styles.greeting}>质检拍照</Text>
          <Text style={styles.userName}>
            {user?.fullName || user?.username || '质检员'}
          </Text>
        </View>
      </View>

      <Text style={styles.guide}>请选择要做的事情</Text>

      <View style={styles.actions}>
        <TouchableRipple
          style={styles.actionCard}
          onPress={() => navigation.navigate('QIInspectTab', { screen: 'QILabelQcCreate' })}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="发起标签拍检"
          testID="qi-home-new-label-qc"
        >
          <View style={styles.actionContent}>
            <View style={[styles.actionIcon, styles.photoActionIcon]}>
              <MaterialCommunityIcons name="camera-plus-outline" size={31} color="#B23E2C" />
            </View>
            <View style={styles.actionCopy}>
              <Text style={styles.actionTitle}>发起标签拍检</Text>
              <Text style={styles.actionHint}>选择 SKU，拍摄并上传包装照片</Text>
            </View>
            <Ionicons name="chevron-forward" size={24} color={QI_COLORS.textSecondary} />
          </View>
        </TouchableRipple>

        <TouchableRipple
          style={styles.actionCard}
          onPress={() => navigation.navigate('QIRecordsTab', { screen: 'QILabelQcQueue' })}
          borderless={false}
          accessibilityRole="button"
          accessibilityLabel="查看拍检记录"
          testID="qi-home-label-qc-records"
        >
          <View style={styles.actionContent}>
            <View style={[styles.actionIcon, styles.recordActionIcon]}>
              <Ionicons name="images-outline" size={30} color="#1463A5" />
            </View>
            <View style={styles.actionCopy}>
              <Text style={styles.actionTitle}>拍检记录</Text>
              <Text style={styles.actionHint}>查看照片上传、AI 初筛和处理结果</Text>
            </View>
            <Ionicons name="chevron-forward" size={24} color={QI_COLORS.textSecondary} />
          </View>
        </TouchableRipple>
      </View>

      {/* 一线 AI 助手入口: 排在两项本职任务之后, 次级样式。组件自己判角色+熔断开关 */}
      <FrontlineAIEntry containerStyle={styles.aiEntry} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: QI_COLORS.background },
  content: { width: '100%', maxWidth: 620, alignSelf: 'center', padding: 18 },
  welcomeSection: {
    flexDirection: 'row',
    alignItems: 'center',
    marginTop: 8,
    marginBottom: 28,
  },
  welcomeText: { flex: 1, marginLeft: 11 },
  greeting: { fontSize: 13, color: QI_COLORS.textSecondary },
  userName: { marginTop: 2, fontSize: 20, fontWeight: '800', color: QI_COLORS.text },
  guide: { marginBottom: 12, fontSize: 16, fontWeight: '700', color: QI_COLORS.text },
  actions: { gap: 14 },
  aiEntry: { marginTop: 22 },
  actionCard: {
    minHeight: 96,
    borderRadius: 17,
    backgroundColor: QI_COLORS.card,
    overflow: 'hidden',
  },
  actionContent: { minHeight: 96, flexDirection: 'row', alignItems: 'center', padding: 16 },
  actionIcon: {
    width: 58,
    height: 58,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: 16,
  },
  photoActionIcon: { backgroundColor: '#FFF0EC' },
  recordActionIcon: { backgroundColor: '#EAF3FF' },
  actionCopy: { flex: 1, marginHorizontal: 14 },
  actionTitle: { color: QI_COLORS.text, fontSize: 18, fontWeight: '800' },
  actionHint: { marginTop: 5, color: QI_COLORS.textSecondary, fontSize: 13, lineHeight: 19 },
});
