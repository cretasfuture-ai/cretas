/**
 * 质检员 App · 超时处置 (食品 DAG 缺口 14): 待处置清单 + 处置记录。
 *
 * 操作员领到超时的料被拦, 提示让质检员处置; 质检员不登网页 (MOBILE_ONLY_ROLES), 所以在 App 上补这页。
 * 行为照网页 web-admin/src/views/quality/release/ProcessTimeoutEventsTab.vue,
 * 接口 ProcessTimeLimitController GET /events、/events/records。
 * 入口与「去处置」按后端同一权限 (utils/qualityClosurePermissions), ⛔ 不写岗位名。
 */
import React, { useCallback, useMemo, useState } from 'react';
import { RefreshControl, ScrollView, StyleSheet, Text, View } from 'react-native';
import { TouchableRipple } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { processTimeLimitApi, type ProcessTimeLimitEvent } from '../../services/api/processTimeLimitApi';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { formatDuration } from '../../utils/processTimeLimit';
import { canDecideTimeoutEvent } from '../../utils/qualityClosurePermissions';
import { EmptyBlock, ErrorBlock, LoadingBlock, shared } from './qualityClosureShared';
import { decisionLabel, formatBackendTime, overdueText, timeoutQuantity } from './qiTimeoutView';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;
type Tab = 'open' | 'records';

export default function QITimeoutScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canDecide = canDecideTimeoutEvent(user);

  const [tab, setTab] = useState<Tab>('open');
  const [events, setEvents] = useState<ProcessTimeLimitEvent[]>([]);
  const [records, setRecords] = useState<ProcessTimeLimitEvent[]>([]);
  const [loadedAt, setLoadedAt] = useState(Date.now());
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async (mode: 'initial' | 'refresh' = 'initial') => {
    if (!factoryId) {
      setError('读不到当前工厂，请退出后重新登录');
      setLoading(false);
      return;
    }
    if (mode === 'refresh') setRefreshing(true); else setLoading(true);
    try {
      const [open, recent] = await Promise.all([
        processTimeLimitApi.listOpenEvents(factoryId),
        processTimeLimitApi.listEventRecords(factoryId),
      ]);
      if (!open?.success || !recent?.success) {
        setError(open?.message || recent?.message || '超时清单读取失败');
        return;
      }
      setEvents(Array.isArray(open.data) ? open.data : []);
      setRecords(Array.isArray(recent.data) ? recent.data : []);
      setLoadedAt(Date.now());
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '超时清单读取失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const decided = useMemo(() => records.filter((r) => r.status !== 'OPEN'), [records]);

  const renderOpen = () => {
    if (events.length === 0) {
      return (
        <EmptyBlock
          text="没有超时待处置的料"
          hint="操作员领用时被时限拦下的料会出现在这里。"
          actionText="返回首页"
          onAction={() => navigation.navigate('QIHome')}
        />
      );
    }
    return (
      <>
        {!canDecide && (
          <Text style={[shared.note, { marginBottom: 10 }]} testID="qi-timeout-readonly-hint">
            你的账号只能查看。复检放行或判不合格需要质检写权限，请联系质量负责人。
          </Text>
        )}
        {events.map((item) => (
          <TouchableRipple
            key={item.id}
            style={shared.card}
            onPress={() => navigation.navigate('QITimeoutDecision', { event: item })}
            accessibilityRole="button"
            accessibilityLabel={`超时处置 ${item.batchNumber ?? ''}`}
            testID={`qi-timeout-event-${item.id}`}
          >
            <View>
              <View style={styles.titleRow}>
                <Text style={[shared.cardTitle, styles.title]} numberOfLines={2}>{item.batchNumber || '批次号缺失'}</Text>
                <View style={[shared.tag, styles.tag, { backgroundColor: '#FDECEC' }]}>
                  <Text style={[shared.tagText, { color: QI_COLORS.danger }]}>已超时 {overdueText(item, loadedAt)}</Text>
                </View>
              </View>
              <Text style={shared.cardSub}>工序：{item.processName || '—'} · 时限 {formatDuration(item.limitMinutes)}</Text>
              <Text style={shared.cardSub}>超时数量：{timeoutQuantity(item)} · 截止 {formatBackendTime(item.deadlineAt)}</Text>
              <View style={[shared.row, { marginTop: 8 }]}>
                <Text style={[styles.go, { color: canDecide ? QI_COLORS.primary : QI_COLORS.textSecondary }]}>
                  {canDecide ? '去处置' : '查看'}
                </Text>
                <Ionicons name="chevron-forward" size={18} color={canDecide ? QI_COLORS.primary : QI_COLORS.textSecondary} />
              </View>
            </View>
          </TouchableRipple>
        ))}
      </>
    );
  };

  const renderRecords = () => {
    if (decided.length === 0) return <EmptyBlock text="还没有处置记录" />;
    return decided.map((item) => {
      const released = item.status === 'RELEASED';
      return (
        <View key={item.id} style={shared.card} testID={`qi-timeout-record-${item.id}`}>
          <View style={styles.titleRow}>
            <Text style={[shared.cardTitle, styles.title]} numberOfLines={2}>{item.batchNumber || '批次号缺失'}</Text>
            <View style={[shared.tag, styles.tag, { backgroundColor: released ? '#EDF7EE' : '#FDECEC' }]}>
              <Text style={[shared.tagText, { color: released ? '#1B8A3A' : QI_COLORS.danger }]}>{decisionLabel(item.status)}</Text>
            </View>
          </View>
          <Text style={shared.cardSub}>工序：{item.processName || '—'} · 超时数量 {timeoutQuantity(item)}</Text>
          {item.conclusion ? <Text style={shared.cardSub}>复检结论：{item.conclusion}</Text> : null}
          <Text style={shared.cardSub}>
            {item.decidedByName || '—'} · {formatBackendTime(item.decidedAt)}
            {released ? ` · 放行有效到 ${formatBackendTime(item.validUntil)}` : ''}
          </Text>
        </View>
      );
    });
  };

  return (
    <View style={shared.screen} testID="qi-timeout-screen">
      <View style={styles.tabs}>
        {([
          ['open', `待处置 (${events.length})`],
          ['records', '处置记录'],
        ] as const).map(([key, label]) => (
          <TouchableRipple
            key={key}
            style={[styles.tab, tab === key && styles.tabActive]}
            onPress={() => setTab(key)}
            accessibilityRole="tab"
            accessibilityState={{ selected: tab === key }}
            testID={`qi-timeout-tab-${key}`}
          >
            <Text style={[styles.tabText, tab === key && styles.tabTextActive]}>{label}</Text>
          </TouchableRipple>
        ))}
      </View>
      <ScrollView
        contentContainerStyle={shared.content}
        refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { void load('refresh'); }} />}
      >
        {loading ? <LoadingBlock /> : error ? <ErrorBlock message={error} onRetry={() => { void load(); }} />
          : tab === 'open' ? renderOpen() : renderRecords()}
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  tabs: {
    flexDirection: 'row',
    backgroundColor: QI_COLORS.card,
    borderBottomWidth: 1,
    borderBottomColor: QI_COLORS.border,
  },
  tab: { flex: 1, minHeight: 52, alignItems: 'center', justifyContent: 'center', borderBottomWidth: 3, borderBottomColor: 'transparent' },
  tabActive: { borderBottomColor: QI_COLORS.primary },
  tabText: { fontSize: 16, fontWeight: '600', color: QI_COLORS.textSecondary },
  tabTextActive: { color: QI_COLORS.primary, fontWeight: '800' },
  // 长批次号换行, 右上角标签保持完整 (Android 上行内 Text 默认不收缩, 会把标签挤出屏)
  titleRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  title: { flex: 1, flexShrink: 1, marginRight: 10 },
  tag: { flexShrink: 0, marginTop: 2 },
  go: { fontSize: 15, fontWeight: '700' },
});
