import React, { useState, useEffect, useCallback, useMemo } from 'react';
import { View, StyleSheet, FlatList, Alert, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, Button, ActivityIndicator, SegmentedButtons } from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { FAManagementStackParamList } from '../../../types/navigation';
import { transferApiClient, InternalTransfer } from '../../../services/api/transferApiClient';
import { useAuthStore } from '../../../store/authStore';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { RowActionBottomSheet, StickyFooterSummary } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useListSummary } from '../../../hooks/useListSummary';
import { formatSummaryForAI } from '../../../utils/aiSummaryContext';
import { safePrint } from '../../../services/api/printApiClient';

type Nav = NativeStackNavigationProp<FAManagementStackParamList>;

/**
 * 调拨列表的筛选页签。
 *
 * 🔴 2026-08-20: 原来是「全部 / 申请中(REQUESTED) / 运输中(SHIPPED) / 已完成(CONFIRMED)」，
 * 两个问题叠在一起，净效果是**这个筛选栏完全不起作用**：
 *
 *   ① 后端列表接口**根本没有 status 参数** —— 前端传了，Spring 直接忽略
 *      ⇒ 点哪个页签都返回同一批数据（用户原话：「我怎么点它都是一样的」）。已在后端补上。
 *   ② 页签取值与真实数据对不上 —— prod 实测 F006 只有
 *      `CONFIRMED`(6) / `DRAFT`(3) / `CANCELLED`(2)，
 *      而 `REQUESTED` / `SHIPPED` **一条都没有**，`DRAFT` / `CANCELLED` **没有页签**。
 *
 * ⚠️ `TransferStatus` 有 9 个取值，逐个做页签对仓管过碎（M-10 同样的判断）。
 * ⇒ 按「他现在能做什么」分四组，一组对应多个后端状态（后端支持逗号分隔）。
 */
const TRANSFER_STATUS_TABS = [
  // 顺序按「看的人最常找什么」排（Steve 2026-08-20 指定）：
  // 已完成最常查 → 在途次之 → 已取消偶尔 → 草稿最后（自己刚建的，本来就知道）。
  { value: 'all', label: '全部' },
  { value: 'CONFIRMED', label: '已完成' },
  { value: 'REQUESTED,APPROVED,SHIPPED,RECEIVED', label: '在途' },
  { value: 'CANCELLED,REJECTED,REVERSED', label: '已取消' },
  { value: 'DRAFT', label: '草稿' },
] as const;

const STATUS_MAP: Record<string, { label: string; color: string }> = {
  DRAFT: { label: '草稿', color: '#909399' },
  REQUESTED: { label: '已申请', color: '#e6a23c' },
  APPROVED: { label: '已批准', color: '#409eff' },
  REJECTED: { label: '已驳回', color: '#f56c6c' },
  SHIPPED: { label: '已发运', color: '#e6a23c' },
  RECEIVED: { label: '已签收', color: '#409eff' },
  CONFIRMED: { label: '已确认', color: '#67c23a' },
  CANCELLED: { label: '已取消', color: '#909399' },
  // 2026-08-20: 后端 TransferStatus 有 9 个值, 这里原来只映了 8 个 —— 漏 REVERSED,
  // 界面上就会直接显示英文码。已加跨语言闸(transferEnumLabels.contract.test.ts)钉住。
  REVERSED: { label: '已冲正', color: '#909399' },
};

const TYPE_MAP: Record<string, string> = {
  HQ_TO_BRANCH: '总部→分部',
  BRANCH_TO_BRANCH: '分部→分部',
  BRANCH_TO_HQ: '分部→总部',
  // 🔴 漏的就是它: F006 的厂内仓间调拨全是这个类型, 截图上显示成 WAREHOUSE_TO_WAREHOUSE。
  WAREHOUSE_TO_WAREHOUSE: '仓库→仓库',
};

export default function TransferListScreen() {
  const navigation = useNavigation<Nav>();
  const { user } = useAuthStore();
  const factoryId = user?.factoryId || user?.factoryUser?.factoryId || '';
  const [transfers, setTransfers] = useState<InternalTransfer[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [statusFilter, setStatusFilter] = useState('all');
  const [selectedTransfer, setSelectedTransfer] = useState<InternalTransfer | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  const loadData = useCallback(async () => {
    try {
      const params: any = { page: 1, size: 50 };
      if (statusFilter !== 'all') params.status = statusFilter;
      const res = await transferApiClient.getTransfers(params);
      if (res.success && res.data) {
        setTransfers(res.data.content || []);
      }
    } catch { Alert.alert('错误', '加载调拨单失败'); }
    finally { setLoading(false); setRefreshing(false); }
  }, [statusFilter]);

  useEffect(() => { loadData(); }, [loadData]);
  const onRefresh = () => { setRefreshing(true); loadData(); };

  // UX-A2 (Track H): row-action bottom sheet
  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => navigation.navigate('TransferDetail', { transferId: e.id }),
    // 调拨单暂复用 material-requisition 模板 (warehouse:read 也准入)；后续可扩 transfer 专属模板
    'print-pdf': (e: RowContext) => { void safePrint('material-requisition', e.id); },
  }), [navigation]);

  const sheetCtx: RowContext = selectedTransfer
    ? { status: selectedTransfer.status, id: selectedTransfer.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('transfer', sheetCtx, { handlers });

  const openSheet = (transfer: InternalTransfer) => { setSelectedTransfer(transfer); setActionSheetVisible(true); };

  // U-FOOTER-1 (Track I): sticky summary
  const summaryRequest = useMemo(
    () => ({ filterConditions: statusFilter !== 'all' ? { status: statusFilter } : {} }),
    [statusFilter],
  );
  const { summary, refresh: refreshSummary } = useListSummary('internalTransfer', summaryRequest);
  useEffect(() => { refreshSummary(); }, [refreshSummary, refreshing]);

  const renderTransfer = ({ item }: { item: InternalTransfer }) => {
    const status = STATUS_MAP[item.status] || { label: item.status, color: '#909399' };
    const isIntraFactory = item.sourceFactoryId === item.targetFactoryId;
    const isOutbound = item.sourceFactoryId === factoryId;
    const dir = isIntraFactory
      ? { label: '厂内调拨', bg: '#67c23a20', fg: '#67c23a' }
      : isOutbound
        ? { label: '调出', bg: '#e6a23c20', fg: '#e6a23c' }
        : { label: '调入', bg: '#409eff20', fg: '#409eff' };

    return (
      <Card style={styles.card}
        onPress={() => navigation.navigate('TransferDetail', { transferId: item.id })}
        onLongPress={() => openSheet(item)}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <View style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <Text variant="titleMedium" style={styles.transferNum}>{item.transferNumber}</Text>
              {/*
                2026-08-20: 原来是 Paper 的 <Chip> 且写死 height:22 + fontSize:10 ——
                Chip 有内置 padding, 硬压高度把文字整个挤没了, 用户看到的是一个
                「黄色气泡但不知道里面是什么」。⇒ 改成普通 View+Text, 高度由内容决定。

                ⚠️ 同时修措辞: 厂内仓间调拨(调出方 == 调入方)标成「调出」是错的 ——
                F006→F006 的 WAREHOUSE_TO_WAREHOUSE 既不是调出也不是调入。
              */}
              <View style={[styles.dirBadge, { backgroundColor: dir.bg }]}>
                <Text style={[styles.dirBadgeText, { color: dir.fg }]}>{dir.label}</Text>
              </View>
            </View>
            <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
          </View>

          <View style={styles.cardRow}>
            <Text style={styles.label}>类型</Text>
            <Text style={styles.value}>{TYPE_MAP[item.transferType] || item.transferType}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>调出方</Text>
            <Text style={styles.value}>{item.sourceFactory?.name || item.sourceFactoryId}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>调入方</Text>
            <Text style={styles.value}>{item.targetFactory?.name || item.targetFactoryId}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>金额</Text>
            <Text style={[styles.value, styles.amount]}>¥{formatNumberWithCommas(item.totalAmount)}</Text>
          </View>
          <View style={styles.cardRow}>
            <Text style={styles.label}>日期</Text>
            <Text style={styles.value}>{item.transferDate}</Text>
          </View>
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title="调拨管理" />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      <View style={styles.filterRow}>
        <SegmentedButtons
          value={statusFilter}
          onValueChange={setStatusFilter}
          buttons={TRANSFER_STATUS_TABS.map((t) => ({ value: t.value, label: t.label }))}
        />
      </View>

      <View style={styles.listWrap}>
        {loading ? (
          <ActivityIndicator style={styles.loader} size="large" />
        ) : (
          <FlatList
            data={transfers}
            keyExtractor={item => item.id}
            renderItem={renderTransfer}
            contentContainerStyle={styles.list}
            refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
            ListEmptyComponent={<Text style={styles.empty}>暂无调拨单</Text>}
          />
        )}
      </View>

      <StickyFooterSummary
        stats={summary?.stats ?? []}
        loading={summary == null && !loading}
        onAIAnalyze={() =>
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: {
              entityType: 'INTERNAL_TRANSFER',
              initialMessage: `分析当前调拨列表${formatSummaryForAI(summary, { filter: { status: statusFilter } })}`,
            },
          }))
        }
      />

      <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedTransfer ? `调拨单 ${selectedTransfer.transferNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedTransfer) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'TRANSFER', initialMessage: `${selectedTransfer.transferNumber}: ` },
          }));
        }}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  dirBadge: { paddingHorizontal: 8, paddingVertical: 3, borderRadius: 10 },
  dirBadgeText: { fontSize: 11, fontWeight: '600' },
  filterRow: { paddingHorizontal: 12, paddingVertical: 8, backgroundColor: '#fff' },
  listWrap: { flex: 1 },
  loader: { flex: 1, justifyContent: 'center' },
  list: { padding: 12, paddingBottom: 80 },
  card: { marginBottom: 10, borderRadius: 10 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 },
  transferNum: { fontWeight: '700' },
  cardRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 3 },
  label: { color: '#909399', fontSize: 13 },
  value: { fontSize: 13, color: '#333' },
  amount: { fontWeight: '600', color: '#409eff' },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
});
