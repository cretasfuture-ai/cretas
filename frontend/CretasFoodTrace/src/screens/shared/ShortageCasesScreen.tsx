/**
 * S4 缺料异常 (App 只读入口, L1 切片 4)。
 *
 * 一个缺料事件一条处理实例: 订单、阶段、按物料拆开的缺口 (需求 / 已放行 / 质检中 / 在途 / 还缺)。
 * 采购和销售在手机上能看「哪张单缺什么、缺多少、到哪一步了」; 有写权限的 (采购 / 生产 / 仓库 / 质检)
 * 可以「按当前库存重新核对」。
 *
 * 采购草稿、取消 / 转人工 / 关闭、局部重排这些动作仍在网页「缺料异常」里做 —— 它们要对照供应商、
 * 排程和多张单据, 手机上只给看与重新核对, ⛔ 不在这里塞一个缩水版的操作界面。
 *
 * 单位一律用后端给的 unitLabel (中文显示名), ⛔ 不显示枚举码、不默认 kg。
 */
import React, { useCallback, useEffect, useState } from 'react';
import { ActivityIndicator, FlatList, RefreshControl, StyleSheet, Text, TouchableOpacity, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { apiClient } from '../../services/api/apiClient';
import { getFactoryId, FactoryIdStrategy } from '../../utils/factoryIdHelper';
import { handleError } from '../../utils/errorHandler';
import { appAlert } from '../../components/ui/AppDialog';
import { useAuthStore } from '../../store/authStore';
import { hasPermission } from '../../types/auth';

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message: string;
}

interface ShortageCaseRow {
  id: string;
  salesOrderNumber: string | null;
  salesOrderStatusLabel: string | null;
  stageLabel: string;
  gapLineCount: number;
  lastRefreshedAt: string | null;
}

interface ShortageLine {
  materialTypeId: string;
  materialName: string | null;
  unitLabel: string | null;
  requiredQty: number;
  releasedQty: number;
  inspectingQty: number;
  onOrderQty: number;
  gapQty: number;
}

interface ShortageDetail {
  case: ShortageCaseRow;
  lines: ShortageLine[];
}

/** 有任一写权限才给「重新核对」按钮 —— 与后端 refresh 接口的权限口径一致。 */
const REFRESH_PERMISSIONS = ['procurement:read_write', 'production:read_write', 'warehouse:read_write', 'quality:read_write'];

function qty(v: number | null | undefined, unit: string | null): string {
  if (v === null || v === undefined) return '—';
  const n = Number(v);
  const text = Number.isInteger(n) ? String(n) : String(Number(n.toFixed(3)));
  return unit ? `${text} ${unit}` : text;
}

function time(s: string | null | undefined): string {
  return s ? s.replace('T', ' ').slice(0, 16) : '—';
}

export default function ShortageCasesScreen() {
  const factoryId = getFactoryId(undefined, FactoryIdStrategy.REQUIRED);
  const { user } = useAuthStore();
  const canRefresh = REFRESH_PERMISSIONS.some((p) => hasPermission(user, p));

  const [rows, setRows] = useState<ShortageCaseRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [openId, setOpenId] = useState<string | null>(null);
  const [detail, setDetail] = useState<ShortageDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [rechecking, setRechecking] = useState(false);

  const fetchList = useCallback(async () => {
    try {
      const res = await apiClient.get<ApiResponse<ShortageCaseRow[]>>(
        `/api/mobile/${factoryId}/shortage-cases?openOnly=true&page=0&size=50`,
      );
      if (res.success) {
        setRows(res.data ?? []);
      } else {
        appAlert('加载失败', res.message || '请重试');
      }
    } catch (err) {
      handleError(err, { title: '加载缺料异常失败' });
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, [factoryId]);

  useEffect(() => { fetchList(); }, [fetchList]);

  const loadDetail = useCallback(async (caseId: string) => {
    setDetailLoading(true);
    try {
      const res = await apiClient.get<ApiResponse<ShortageDetail>>(`/api/mobile/${factoryId}/shortage-cases/${caseId}`);
      if (res.success) {
        setDetail(res.data);
      } else {
        appAlert('加载失败', res.message || '请重试');
      }
    } catch (err) {
      handleError(err, { title: '加载缺口明细失败' });
    } finally {
      setDetailLoading(false);
    }
  }, [factoryId]);

  const toggle = useCallback((caseId: string) => {
    if (openId === caseId) {
      setOpenId(null);
      setDetail(null);
      return;
    }
    setOpenId(caseId);
    setDetail(null);
    loadDetail(caseId);
  }, [openId, loadDetail]);

  const recheck = useCallback(async (caseId: string) => {
    setRechecking(true);
    try {
      const res = await apiClient.post<ApiResponse<unknown>>(`/api/mobile/${factoryId}/shortage-cases/${caseId}/refresh`);
      if (res.success) {
        appAlert('已重新核对', '已按当前库存与采购重新计算缺口');
        await Promise.all([loadDetail(caseId), fetchList()]);
      } else {
        appAlert('核对失败', res.message || '请重试');
      }
    } catch (err) {
      handleError(err, { title: '重新核对失败' });
    } finally {
      setRechecking(false);
    }
  }, [factoryId, loadDetail, fetchList]);

  const renderLine = (l: ShortageLine) => (
    <View key={l.materialTypeId} style={styles.line} testID={`shortage-line-${l.materialTypeId}`}>
      <Text style={styles.lineName}>{l.materialName || l.materialTypeId}</Text>
      <Text style={[styles.gap, l.gapQty > 0 ? styles.gapOpen : null]}>还缺 {qty(l.gapQty, l.unitLabel)}</Text>
      <Text style={styles.lineMeta}>
        需求 {qty(l.requiredQty, l.unitLabel)} · 已放行 {qty(l.releasedQty, l.unitLabel)} · 质检中 {qty(l.inspectingQty, l.unitLabel)} · 在途 {qty(l.onOrderQty, l.unitLabel)}
      </Text>
    </View>
  );

  const renderItem = ({ item }: { item: ShortageCaseRow }) => {
    const open = openId === item.id;
    return (
      <View style={styles.card}>
        <TouchableOpacity onPress={() => toggle(item.id)} testID={`shortage-case-${item.id}`} accessibilityRole="button">
          <View style={styles.cardHead}>
            <Text style={styles.order}>订单 {item.salesOrderNumber || '—'}</Text>
            <Text style={styles.stage}>{item.stageLabel}</Text>
          </View>
          <Text style={styles.meta}>
            {item.gapLineCount} 种物料有缺口 · {item.salesOrderStatusLabel || '—'} · 核对于 {time(item.lastRefreshedAt)}
          </Text>
        </TouchableOpacity>
        {open && (
          <View style={styles.detail}>
            {detailLoading && <ActivityIndicator />}
            {!detailLoading && detail && detail.lines.map(renderLine)}
            {canRefresh && (
              <TouchableOpacity
                style={[styles.button, rechecking ? styles.buttonDisabled : null]}
                disabled={rechecking}
                onPress={() => recheck(item.id)}
                testID={`shortage-recheck-${item.id}`}
              >
                <Text style={styles.buttonText}>{rechecking ? '核对中…' : '按当前库存重新核对'}</Text>
              </TouchableOpacity>
            )}
            <Text style={styles.hint}>生成采购草稿、取消、转人工、重排请在网页「缺料异常」里操作。</Text>
          </View>
        )}
      </View>
    );
  };

  return (
    <SafeAreaView style={styles.container} edges={['top']}>
      <Text style={styles.title}>缺料异常</Text>
      {loading ? (
        <ActivityIndicator style={styles.loading} />
      ) : (
        <FlatList
          data={rows}
          keyExtractor={(r) => r.id}
          renderItem={renderItem}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={() => { setRefreshing(true); fetchList(); }} />}
          ListEmptyComponent={<Text style={styles.empty}>当前没有未解决的缺料</Text>}
          contentContainerStyle={styles.list}
        />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#F5F5F5' },
  title: { fontSize: 18, fontWeight: '600', paddingHorizontal: 16, paddingVertical: 12, color: '#212121' },
  loading: { marginTop: 40 },
  list: { paddingHorizontal: 16, paddingBottom: 24 },
  card: { backgroundColor: '#FFFFFF', borderRadius: 8, padding: 14, marginBottom: 12 },
  cardHead: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  order: { fontSize: 16, fontWeight: '600', color: '#212121' },
  stage: { fontSize: 14, color: '#E65100' },
  meta: { marginTop: 6, fontSize: 13, color: '#757575' },
  detail: { marginTop: 10, borderTopWidth: 1, borderTopColor: '#EEEEEE', paddingTop: 10 },
  line: { marginBottom: 10 },
  lineName: { fontSize: 15, fontWeight: '500', color: '#212121' },
  gap: { fontSize: 15, marginTop: 2, color: '#2E7D32' },
  gapOpen: { color: '#C62828', fontWeight: '600' },
  lineMeta: { fontSize: 13, color: '#757575', marginTop: 2 },
  button: { marginTop: 6, backgroundColor: '#1565C0', borderRadius: 6, paddingVertical: 12, alignItems: 'center' },
  buttonDisabled: { backgroundColor: '#90A4AE' },
  buttonText: { color: '#FFFFFF', fontSize: 15, fontWeight: '600' },
  hint: { marginTop: 8, fontSize: 12, color: '#9E9E9E' },
  empty: { textAlign: 'center', marginTop: 40, color: '#9E9E9E' },
});
