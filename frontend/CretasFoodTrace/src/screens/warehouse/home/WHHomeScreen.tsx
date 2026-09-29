/** 仓管首页：只负责把现场人员带到入库或发货。 */
import React, { useCallback } from 'react';
import { StyleSheet, View } from 'react-native';
import { Text, Surface, TouchableRipple } from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import { BottomTabNavigationProp } from '@react-navigation/bottom-tabs';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useNavigation } from '@react-navigation/native';

import { WHHomeStackParamList, WarehouseManagerTabParamList } from '../../../types/navigation';
import { handleError } from '../../../utils/errorHandler';

type TabNav = BottomTabNavigationProp<WarehouseManagerTabParamList>;
type HomeNav = NativeStackNavigationProp<WHHomeStackParamList>;

export default function WHHomeScreen() {
  const navigation = useNavigation<HomeNav>();

  /** 仓库地图在**本栈**内, 不切 Tab —— 它是现场找货的参照, 不是第三条作业主线。 */
  const openWarehouseMap = useCallback(() => {
    navigation.navigate('WHLocationManage', { openMap: true });
  }, [navigation]);

  const openTab = useCallback((tab: 'WHInboundTab' | 'WHOutboundTab') => {
    const tabNavigation = navigation.getParent<TabNav>();
    if (!tabNavigation) {
      handleError(new Error('仓管底部导航不可用'), {
        title: '暂时无法打开',
        customMessage: '请返回后重试；仍无法打开请退出账号重新登录。',
      });
      return;
    }
    if (tab === 'WHInboundTab') {
      tabNavigation.navigate('WHInboundTab', { screen: 'WHInboundList' });
    } else {
      tabNavigation.navigate('WHOutboundTab', { screen: 'WHDeliveryConfirmList' });
    }
  }, [navigation]);

  return (
    <SafeAreaView style={styles.container} edges={['top']} testID="wh-home-screen">
      <View style={styles.header}>
        <Text style={styles.headerTitle}>仓储首页</Text>
        <Text style={styles.headerSubtitle}>请选择今天要处理的工作</Text>
      </View>

      <View style={styles.actions}>
        <TouchableRipple
          testID="wh-home-inbound"
          accessibilityRole="button"
          accessibilityLabel="进入入库管理"
          onPress={() => openTab('WHInboundTab')}
          style={styles.touchable}
          rippleColor="rgba(21, 101, 192, 0.12)"
        >
          <Surface style={[styles.actionCard, styles.inboundCard]} elevation={1}>
            <View style={[styles.iconCircle, styles.inboundIcon]}>
              <MaterialCommunityIcons name="package-down" size={44} color="#1565C0" />
            </View>
            <View style={styles.actionCopy}>
              <Text style={styles.actionTitle}>入库</Text>
              <Text style={styles.actionHint}>收货、扫码入库、生产成品入库</Text>
            </View>
            <MaterialCommunityIcons name="chevron-right" size={30} color="#1565C0" />
          </Surface>
        </TouchableRipple>

        <TouchableRipple
          testID="wh-home-outbound"
          accessibilityRole="button"
          accessibilityLabel="进入发货管理"
          onPress={() => openTab('WHOutboundTab')}
          style={styles.touchable}
          rippleColor="rgba(46, 125, 50, 0.12)"
        >
          <Surface style={[styles.actionCard, styles.outboundCard]} elevation={1}>
            <View style={[styles.iconCircle, styles.outboundIcon]}>
              <MaterialCommunityIcons name="package-up" size={44} color="#2E7D32" />
            </View>
            <View style={styles.actionCopy}>
              <Text style={styles.actionTitle}>发货</Text>
              <Text style={styles.actionHint}>查看待发任务并确认实际发货数量</Text>
            </View>
            <MaterialCommunityIcons name="chevron-right" size={30} color="#2E7D32" />
          </Surface>
        </TouchableRipple>

        <TouchableRipple
          testID="wh-home-warehouse-map"
          accessibilityRole="button"
          accessibilityLabel="查看库位与仓库地图"
          onPress={openWarehouseMap}
          style={styles.touchable}
          rippleColor="rgba(109, 76, 176, 0.12)"
        >
          <Surface style={[styles.actionCard, styles.mapCard]} elevation={1}>
            <View style={[styles.iconCircle, styles.mapIcon]}>
              <MaterialCommunityIcons name="floor-plan" size={44} color="#6D4CB0" />
            </View>
            <View style={styles.actionCopy}>
              <Text style={styles.actionTitle}>仓库地图</Text>
              <Text style={styles.actionHint}>看库位在哪一层、放了哪些批次</Text>
            </View>
            <MaterialCommunityIcons name="chevron-right" size={30} color="#6D4CB0" />
          </Surface>
        </TouchableRipple>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F7F6' },
  header: { backgroundColor: '#4CAF50', paddingHorizontal: 20, paddingVertical: 22 },
  headerTitle: { color: '#FFFFFF', fontSize: 24, fontWeight: '700' },
  headerSubtitle: { color: 'rgba(255,255,255,0.9)', fontSize: 15, marginTop: 6 },
  actions: { flex: 1, justifyContent: 'center', paddingHorizontal: 18, gap: 16 },
  touchable: { borderRadius: 18 },
  actionCard: {
    minHeight: 132,
    borderRadius: 18,
    paddingHorizontal: 18,
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
  },
  inboundCard: { backgroundColor: '#F7FBFF', borderColor: '#BBDEFB' },
  outboundCard: { backgroundColor: '#F7FCF7', borderColor: '#C8E6C9' },
  mapCard: { backgroundColor: '#FAF8FF', borderColor: '#DDD3F2' },
  iconCircle: {
    width: 72,
    height: 72,
    borderRadius: 36,
    alignItems: 'center',
    justifyContent: 'center',
  },
  inboundIcon: { backgroundColor: '#E3F2FD' },
  outboundIcon: { backgroundColor: '#E8F5E9' },
  mapIcon: { backgroundColor: '#EFE9FA' },
  actionCopy: { flex: 1, marginHorizontal: 16 },
  actionTitle: { color: '#1F2937', fontSize: 26, fontWeight: '700' },
  actionHint: { color: '#667085', fontSize: 14, lineHeight: 21, marginTop: 7 },
});
