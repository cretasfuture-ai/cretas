import React, { useState, useEffect, useMemo } from 'react';
import { View, StyleSheet, FlatList, Alert, RefreshControl } from 'react-native';
import { Text, Appbar, Card, Chip, ActivityIndicator, ProgressBar } from 'react-native-paper';
import { useNavigation, CommonActions } from '@react-navigation/native';
import { useTranslation } from 'react-i18next';
import { salesApiClient, FinishedGoodsBatch } from '../../../services/api/salesApiClient';
import { formatNumberWithCommas } from '../../../utils/formatters';
import { RowActionBottomSheet } from '../../../components/list';
import { useRowActions, type RowContext } from '../../../hooks/useRowActions';
import { useAuthStore } from '../../../store/authStore';
import { isMobileBusinessObserver } from '../../../utils/mobileRoleBoundaries';

export default function FinishedGoodsListScreen() {
  const navigation = useNavigation();
  const { t } = useTranslation('warehouse');
  const user = useAuthStore((state) => state.user);
  const isOperationsReadOnly = isMobileBusinessObserver(user);
  const [batches, setBatches] = useState<FinishedGoodsBatch[]>([]);
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [selectedBatch, setSelectedBatch] = useState<FinishedGoodsBatch | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);

  /**
   * 批次总量。后端发的是 producedQuantity / nominalQuantity, 没有 totalQuantity。
   * 取不到就返回 null —— ⛔ 不要兜底成 0: 0 会让「已售罄」和「不知道总量」变成同一件事。
   */
  const totalOf = (batch: FinishedGoodsBatch): number | null => {
    const n = batch.producedQuantity ?? batch.nominalQuantity;
    return typeof n === 'number' && n > 0 ? n : null;
  };

  /**
   * Derive a STATUS_ACTIONS_MAP key from quantity ratio.
   *
   * ⚠️ 这个判断和下面 `getStockStatus` 是同一件事的两份实现, 而它们原本用同一个
   * 不存在的字段 `totalQuantity`, 却因为写法不同得出**相反**的错误答案:
   * 这里 `undefined > 0` 为假 ⇒ ratio 取 0 ⇒ `0 < 0.2` 恒真 ⇒ 一律 LOW_STOCK;
   * 那边 `available / undefined` 是 NaN ⇒ `NaN < 0.2` 恒假 ⇒ 一律「充足」。
   * 现在两处都走 `totalOf`。⛔ 再加第三处判断之前, 先把这两处抽成一个。
   */
  const deriveStockStatus = (b: FinishedGoodsBatch): string => {
    if (b.availableQuantity <= 0) return 'OUT_OF_STOCK';
    const total = totalOf(b);
    if (total !== null && b.availableQuantity / total < 0.2) return 'LOW_STOCK';
    return 'IN_STOCK';
  };

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => Alert.alert('查看详情', `批次 ${e.id}`),
    transfer: (e: RowContext) => Alert.alert('调拨', `调拨批次 ${e.id} (待接调拨流程)`),
    'view-price-history': (e: RowContext) => Alert.alert('价格历史', `批次 ${e.id}`),
  }), []);

  const sheetCtx: RowContext = selectedBatch
    ? { status: deriveStockStatus(selectedBatch), id: selectedBatch.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('inventory', sheetCtx, { handlers });

  const openSheet = (batch: FinishedGoodsBatch) => { setSelectedBatch(batch); setActionSheetVisible(true); };

  const loadData = async () => {
    try {
      const res = await salesApiClient.getFinishedGoods({ page: 1, size: 100 });
      if (res.success && res.data) {
        setBatches(res.data.content || []);
      }
    } catch { Alert.alert(t('common:error.loadFailed'), t('finishedGoods.loadFailed')); }
    finally { setLoading(false); setRefreshing(false); }
  };

  useEffect(() => { loadData(); }, []);
  const onRefresh = () => { setRefreshing(true); loadData(); };

  const getStockStatus = (batch: FinishedGoodsBatch) => {
    if (batch.availableQuantity <= 0) return { label: t('finishedGoods.status.soldOut'), color: '#f56c6c' };
    const total = totalOf(batch);
    // 以前这里算的是 availableQuantity / totalQuantity, 而 totalQuantity 后端不发
    // ⇒ ratio 恒为 NaN, `NaN < 0.2` 恒 false ⇒「库存偏低」这一档一次都没出现过。
    if (total !== null && batch.availableQuantity / total < 0.2) {
      return { label: t('finishedGoods.status.lowStock'), color: '#e6a23c' };
    }
    return { label: t('finishedGoods.status.sufficient'), color: '#67c23a' };
  };

  const renderBatch = ({ item }: { item: FinishedGoodsBatch }) => {
    const status = getStockStatus(item);
    const total = totalOf(item);
    const ratio = total !== null ? item.availableQuantity / total : 0;

    return (
      <Card style={styles.card} onLongPress={isOperationsReadOnly ? undefined : () => openSheet(item)}>
        <Card.Content>
          <View style={styles.cardHeader}>
            <View style={{ flex: 1 }}>
              <Text variant="titleMedium" style={styles.productName}>{item.productName || item.productTypeName || item.productTypeId}</Text>
              <Text style={styles.batchNum}>{item.batchNumber}</Text>
            </View>
            <Chip style={{ backgroundColor: status.color + '20' }} textStyle={{ color: status.color, fontSize: 12 }}>{status.label}</Chip>
          </View>

          <ProgressBar progress={ratio} color={status.color} style={styles.progress} />

          <View style={styles.statsRow}>
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('finishedGoods.stats.total')}</Text>
              <Text style={styles.statValue}>{total !== null ? `${total} ${item.unit}` : '—'}</Text>
            </View>
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('finishedGoods.stats.available')}</Text>
              <Text style={[styles.statValue, { color: status.color }]}>{item.availableQuantity} {item.unit}</Text>
            </View>
            <View style={styles.statItem}>
              <Text style={styles.statLabel}>{t('finishedGoods.stats.reserved')}</Text>
              <Text style={styles.statValue}>{item.reservedQuantity} {item.unit}</Text>
            </View>
          </View>

          <View style={styles.metaRow}>
            <Text style={styles.metaText}>{t('finishedGoods.productionDate')}: {item.productionDate}</Text>
            {item.expireDate && <Text style={styles.metaText}>{t('finishedGoods.expiryDate')}: {item.expireDate}</Text>}
          </View>
          {item.unitCost != null && (
            <View style={styles.metaRow}>
              <Text style={styles.metaText}>{t('finishedGoods.unitCost')}: ¥{formatNumberWithCommas(item.unitCost)}</Text>
              {item.storageLocation && <Text style={styles.metaText}>{t('finishedGoods.storageLocation')}: {item.storageLocation}</Text>}
            </View>
          )}
        </Card.Content>
      </Card>
    );
  };

  return (
    <View style={styles.container}>
      <Appbar.Header>
        <Appbar.BackAction onPress={() => navigation.goBack()} />
        <Appbar.Content title={t('finishedGoods.title')} />
        <Appbar.Action icon="refresh" onPress={onRefresh} />
      </Appbar.Header>

      {loading ? (
        <ActivityIndicator style={styles.loader} size="large" />
      ) : (
        <FlatList
          data={batches}
          keyExtractor={item => item.id}
          renderItem={renderBatch}
          contentContainerStyle={styles.list}
          refreshControl={<RefreshControl refreshing={refreshing} onRefresh={onRefresh} />}
          ListEmptyComponent={<Text style={styles.empty}>{t('finishedGoods.empty')}</Text>}
        />
      )}

      {!isOperationsReadOnly && <RowActionBottomSheet
        visible={actionSheetVisible}
        onClose={() => setActionSheetVisible(false)}
        actions={rowActions}
        title={selectedBatch ? `成品 ${selectedBatch.batchNumber}` : ''}
        aiTriggerEnabled
        onAITrigger={() => {
          if (!selectedBatch) return;
          navigation.dispatch(CommonActions.navigate('FAAITab', {
            screen: 'AIChat',
            params: { entityType: 'INVENTORY', initialMessage: `${selectedBatch.batchNumber}: ` },
          }));
        }}
      />}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f5f5f5' },
  loader: { flex: 1, justifyContent: 'center' },
  list: { padding: 12, paddingBottom: 40 },
  card: { marginBottom: 10, borderRadius: 10 },
  cardHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 10 },
  productName: { fontWeight: '700' },
  batchNum: { fontSize: 12, color: '#909399', marginTop: 2 },
  progress: { height: 6, borderRadius: 3, marginBottom: 10 },
  statsRow: { flexDirection: 'row', justifyContent: 'space-around', marginBottom: 8 },
  statItem: { alignItems: 'center' },
  statLabel: { fontSize: 11, color: '#909399' },
  statValue: { fontSize: 15, fontWeight: '600', color: '#333', marginTop: 2 },
  metaRow: { flexDirection: 'row', justifyContent: 'space-between', paddingVertical: 2 },
  metaText: { fontSize: 12, color: '#909399' },
  empty: { textAlign: 'center', marginTop: 60, color: '#999' },
});
