/**
 * 质检员 App · 批次召回 (列表)
 *
 * 与网页 /quality/recalls 同一组接口 (/quality-recalls)。进行中在前, 已结案在后。
 * 「发起召回」按 quality:read_write 显示 (后端同一门槛), ⛔ 不按岗位名。
 */
import React, { useCallback, useMemo, useState } from 'react';
import { RefreshControl, ScrollView, Text, View } from 'react-native';
import { Button, TouchableRipple } from 'react-native-paper';
import { Ionicons } from '@expo/vector-icons';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import {
  isRecallClosed,
  qualityRecallAPI,
  recallStatusText,
  type FactoryRecallSummary,
} from '../../services/api/qualityRecallApiClient';
import { useAuthStore } from '../../store/authStore';
import { QI_COLORS, type QualityInspectorStackParamList } from '../../types/qualityInspector';
import { getErrorMsg } from '../../utils/errorHandler';
import { getFactoryId } from '../../utils/factoryIdHelper';
import { canWriteQualityClosure } from '../../utils/qualityClosurePermissions';
import { EmptyBlock, ErrorBlock, LoadingBlock, shared } from './qualityClosureShared';

type Nav = NativeStackNavigationProp<QualityInspectorStackParamList>;

export default function QIRecallScreen() {
  const navigation = useNavigation<Nav>();
  const user = useAuthStore((s) => s.user);
  const factoryId = getFactoryId();
  const canWrite = canWriteQualityClosure(user);

  const [rows, setRows] = useState<FactoryRecallSummary[]>([]);
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
      setRows(await qualityRecallAPI.list(factoryId));
      setError('');
    } catch (e) {
      setError(getErrorMsg(e) || '召回记录读取失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useFocusEffect(useCallback(() => { void load(); }, [load]));

  const sorted = useMemo(
    () => [...rows].sort((a, b) => Number(isRecallClosed(a.status)) - Number(isRecallClosed(b.status))),
    [rows],
  );

  return (
    <ScrollView
      style={shared.screen}
      contentContainerStyle={shared.content}
      refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { void load('refresh'); }} />}
      testID="qi-recall-screen"
    >
      {canWrite && (
        <Button
          mode="contained"
          icon="alert-octagon-outline"
          buttonColor={QI_COLORS.danger}
          style={[shared.primaryButton, { marginTop: 0, marginBottom: 14 }]}
          contentStyle={shared.primaryButtonContent}
          onPress={() => navigation.navigate('QIRecallCreate')}
          testID="qi-recall-create"
        >
          发起召回
        </Button>
      )}

      {loading ? <LoadingBlock /> : error ? <ErrorBlock message={error} onRetry={() => { void load(); }} />
        : sorted.length === 0 ? (
          <EmptyBlock
            text="还没有召回记录"
            hint={canWrite ? '发现已出货批次有问题时，点上方「发起召回」。' : '有召回时会在这里显示进度。'}
          />
        ) : sorted.map((row) => {
          const closed = isRecallClosed(row.status);
          return (
            <TouchableRipple
              key={String(row.id)}
              style={shared.card}
              onPress={() => navigation.navigate('QIRecallDetail', { recallId: String(row.id) })}
              accessibilityRole="button"
              accessibilityLabel={`召回 ${row.eventCode} ${row.batchNumber}`}
              testID={`qi-recall-row-${row.id}`}
            >
              <View>
                <View style={[shared.row, { justifyContent: 'space-between' }]}>
                  <Text style={shared.cardTitle} numberOfLines={1}>{row.batchNumber}</Text>
                  <View style={[shared.tag, { backgroundColor: closed ? '#EDF7EE' : '#FFF1E6' }]}>
                    <Text style={[shared.tagText, { color: closed ? '#1B8A3A' : '#C05621' }]}>{recallStatusText(row.status)}</Text>
                  </View>
                </View>
                <Text style={shared.cardSub}>{row.productName} · 召回单 {row.eventCode}</Text>
                <Text style={shared.cardSub} numberOfLines={2}>原因：{row.reason}</Text>
                <View style={[shared.row, { marginTop: 8 }]}>
                  <Text style={{ color: QI_COLORS.primary, fontWeight: '700', fontSize: 15 }}>{closed ? '查看' : '查看处理进度'}</Text>
                  <Ionicons name="chevron-forward" size={18} color={QI_COLORS.primary} />
                </View>
              </View>
            </TouchableRipple>
          );
        })}
    </ScrollView>
  );
}
