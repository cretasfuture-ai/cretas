/**
 * Workshop Supervisor 设备管理
 * 包含: 搜索、统计、设备列表（按状态分组）
 */
import React, { useState, useCallback, useEffect } from 'react';
import {
  View,
  Text,
  StyleSheet,
  ScrollView,
  TouchableOpacity,
  TextInput,
  RefreshControl,
  ActivityIndicator,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { useNavigation, useFocusEffect } from '@react-navigation/native';
import { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { WSEquipmentStackParamList } from '../../../types/navigation';
import { equipmentApiClient, Equipment as EquipmentDTO } from '../../../services/api/equipmentApiClient';

type NavigationProp = NativeStackNavigationProp<WSEquipmentStackParamList, 'WSEquipment'>;

/**
 * 设备卡片展示模型 —— 每个字段都来自 GET /api/mobile/{factoryId}/equipment 的
 * EquipmentDTO，⛔ 不含任何本地补出来的值。
 *
 * 后端 EquipmentDTO 里**没有**这三样，所以卡片上也不显示：
 *   - OEE      需要逐台调 /equipment/{id}/oee，列表接口不返回
 *   - 当前任务  DTO 无此字段
 *   - 告警条数  在另一个接口 /equipment-alerts 上，本屏不拉
 * 需要它们的话得先接对应接口，⛔ 不是在这里编一个。
 */
interface EquipmentCard {
  id: string;
  name: string;
  /** 后端 equipmentCode；缺失则 undefined */
  code?: string;
  type?: string;
  /** 后端原样返回的 status 字符串 */
  rawStatus: string;
  bucket: EquipmentBucket;
  location?: string;
  /** 后端算好的「7 天内该保养」标记 */
  needsMaintenance: boolean;
  operatorName?: string;
}

type EquipmentBucket = 'running' | 'idle' | 'attention' | 'other';

/**
 * 后端 FactoryEquipment.status 是**自由字符串**（实体默认值 'idle'，
 * service 里另有常量 'active'/'scrapped'），所以这里把已知取值全列出来。
 * ⛔ 未知取值不许并进任何一档 —— 落到 'other' 并把原始字符串显示出来。
 */
const STATUS_META: Record<string, { label: string; bucket: EquipmentBucket; color: string; icon: string }> = {
  running:     { label: '运行中', bucket: 'running',   color: '#52c41a', icon: 'play-circle' },
  active:      { label: '运行中', bucket: 'running',   color: '#52c41a', icon: 'play-circle' },
  idle:        { label: '空闲',   bucket: 'idle',      color: '#1890ff', icon: 'pause-circle' },
  inactive:    { label: '空闲',   bucket: 'idle',      color: '#1890ff', icon: 'pause-circle' },
  maintenance: { label: '维护中', bucket: 'attention', color: '#faad14', icon: 'wrench' },
  fault:       { label: '故障',   bucket: 'attention', color: '#ff4d4f', icon: 'alert-circle' },
  scrapped:    { label: '已报废', bucket: 'other',     color: '#8c8c8c', icon: 'power-plug-off' },
  offline:     { label: '离线',   bucket: 'other',     color: '#8c8c8c', icon: 'power-plug-off' },
};

const UNKNOWN_STATUS = { bucket: 'other' as EquipmentBucket, color: '#8c8c8c', icon: 'help-circle' };

const PAGE_SIZE = 100;

function toCard(e: EquipmentDTO): EquipmentCard {
  const rawStatus = String(e.status ?? '').toLowerCase();
  const meta = STATUS_META[rawStatus];
  const needsMaintenance = (e as unknown as { needsMaintenance?: boolean }).needsMaintenance === true;
  return {
    id: String(e.id),
    // 后端两个别名都发：name 与 equipmentName
    name: e.equipmentName || e.name || '',
    code: e.equipmentCode || e.code || undefined,
    type: e.type || undefined,
    rawStatus,
    bucket: needsMaintenance ? 'attention' : (meta?.bucket ?? UNKNOWN_STATUS.bucket),
    location: e.location || undefined,
    needsMaintenance,
    operatorName: e.operatorName || undefined,
  };
}

export function WSEquipmentScreen() {
  const navigation = useNavigation<NavigationProp>();
  const { t } = useTranslation('workshop');
  const insets = useSafeAreaInsets();
  const [searchQuery, setSearchQuery] = useState('');
  const [refreshing, setRefreshing] = useState(false);

  // 设备数据 — 全部来自 GET /api/mobile/{factoryId}/equipment
  const [equipments, setEquipments] = useState<EquipmentCard[]>([]);
  const [total, setTotal] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    try {
      setError(null);
      const page = await equipmentApiClient.getEquipments({ page: 1, size: PAGE_SIZE });
      if (!page || !Array.isArray(page.content)) {
        throw new Error('设备接口返回的分页结构不符合预期');
      }
      setEquipments(page.content.map(toCard));
      setTotal(typeof page.totalElements === 'number' ? page.totalElements : null);
    } catch (err) {
      console.error('[WSEquipment] 加载设备列表失败:', err);
      setEquipments([]);
      setTotal(null);
      setError(err instanceof Error ? err.message : '加载失败');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  // 搜索（本地过滤已拉到的这一页）
  const visible = equipments.filter(e => {
    if (!searchQuery) return true;
    const q = searchQuery.toLowerCase();
    return e.name.toLowerCase().includes(q) || (e.code ?? '').toLowerCase().includes(q);
  });

  // 按状态分组
  const needAttention = visible.filter(e => e.bucket === 'attention');
  const running = visible.filter(e => e.bucket === 'running');
  const idle = visible.filter(e => e.bucket === 'idle');
  const other = visible.filter(e => e.bucket === 'other');

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  /**
   * 未知状态显示后端原样返回的字符串, ⛔ 不翻译成「离线」之类看起来合理的值 ——
   * 那会让「我不认识这个状态」长得跟「设备离线了」一模一样。
   */
  const getStatusStyle = (rawStatus: string) => {
    const meta = STATUS_META[rawStatus];
    if (meta) return { bg: meta.color, text: meta.label, icon: meta.icon };
    return { bg: UNKNOWN_STATUS.color, text: rawStatus || t('equipment.status.unknown'), icon: UNKNOWN_STATUS.icon };
  };

  // 渲染设备卡片
  const renderEquipmentCard = (equipment: EquipmentCard) => {
    const statusStyle = getStatusStyle(equipment.rawStatus);
    const metaLine = [equipment.code, equipment.location, equipment.type].filter(Boolean).join(' | ');
    // 详情页的路由参数是 number，而后端 EquipmentDTO.id 是 String。
    // 转不出数字就不给点 —— ⛔ 不要塞一个 0 或 NaN 进去让详情页去查一台不存在的设备。
    const numericId = Number(equipment.id);
    const canOpenDetail = Number.isFinite(numericId);

    return (
      <TouchableOpacity
        key={equipment.id}
        disabled={!canOpenDetail}
        style={[styles.equipmentCard, equipment.needsMaintenance ? styles.alertCard : undefined]}
        onPress={() => navigation.navigate('EquipmentDetail', { equipmentId: numericId })}
      >
        <View style={[styles.equipmentAvatar, { backgroundColor: statusStyle.bg }]}>
          <Icon source={statusStyle.icon} size={24} color="#fff" />
        </View>

        <View style={styles.equipmentInfo}>
          <View style={styles.equipmentNameRow}>
            <Text style={styles.equipmentName}>{equipment.name}</Text>
            {equipment.needsMaintenance && (
              <View style={styles.alertBadge}>
                <Icon source="wrench" size={12} color="#fff" />
                <Text style={styles.alertBadgeText}>待保养</Text>
              </View>
            )}
          </View>
          {metaLine ? <Text style={styles.equipmentMeta}>{metaLine}</Text> : null}
          {equipment.operatorName ? (
            <Text style={styles.equipmentTask}>操作员: {equipment.operatorName}</Text>
          ) : null}
        </View>

        <View style={styles.equipmentRight}>
          <View style={[styles.statusBadge, { backgroundColor: `${statusStyle.bg}20` }]}>
            <Text style={[styles.statusText, { color: statusStyle.bg }]}>{statusStyle.text}</Text>
          </View>
        </View>
      </TouchableOpacity>
    );
  };

  return (
    <View style={styles.container}>
      {/* 头部 */}
      <View style={[styles.header, { paddingTop: insets.top + 8 }]}>
        <Text style={styles.headerTitle}>{t('equipment.title')}</Text>
      </View>

      {/* 搜索栏 */}
      <View style={styles.searchContainer}>
        <View style={styles.searchBar}>
          <Icon source="magnify" size={20} color="#999" />
          <TextInput
            style={styles.searchInput}
            placeholder={t('equipment.searchPlaceholder')}
            placeholderTextColor="#999"
            value={searchQuery}
            onChangeText={setSearchQuery}
          />
        </View>
      </View>

      {/* 设备统计 */}
      <View style={styles.statsGrid}>
        <View style={[styles.statCard, { backgroundColor: '#f6ffed' }]}>
          <Icon source="play-circle" size={20} color="#52c41a" />
          <Text style={[styles.statValue, { color: '#52c41a' }]}>{running.length}</Text>
          <Text style={styles.statLabel}>{t('equipment.stats.running')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#e6f7ff' }]}>
          <Icon source="pause-circle" size={20} color="#1890ff" />
          <Text style={[styles.statValue, { color: '#1890ff' }]}>{idle.length}</Text>
          <Text style={styles.statLabel}>{t('equipment.stats.idle')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#fff7e6' }]}>
          <Icon source="wrench" size={20} color="#faad14" />
          <Text style={[styles.statValue, { color: '#faad14' }]}>{needAttention.length}</Text>
          <Text style={styles.statLabel}>{t('equipment.stats.needMaintenance')}</Text>
        </View>
        <View style={[styles.statCard, { backgroundColor: '#f5f5f5' }]}>
          <Icon source="cog" size={20} color="#666" />
          <Text style={[styles.statValue, { color: '#666' }]}>{total ?? '—'}</Text>
          <Text style={styles.statLabel}>{t('equipment.stats.total')}</Text>
        </View>
      </View>

      {/* 设备列表 */}
      <ScrollView
        style={styles.listContainer}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} colors={['#667eea']} />
        }
      >
        {loading && (
          <View style={styles.stateBox}>
            <ActivityIndicator size="large" color="#667eea" />
            <Text style={styles.stateText}>{t('common.loading')}</Text>
          </View>
        )}

        {/* 加载失败 —— 明确报错, ⛔ 不退回任何本地数据 */}
        {!loading && error && (
          <View style={styles.stateBox}>
            <Icon source="cloud-off-outline" size={44} color="#C0C4CC" />
            <Text style={styles.stateText}>设备加载失败：{error}</Text>
            <TouchableOpacity style={styles.retryBtn} onPress={onRefresh}>
              <Text style={styles.retryBtnText}>{t('common.retry')}</Text>
            </TouchableOpacity>
          </View>
        )}

        {!loading && !error && visible.length === 0 && (
          <View style={styles.stateBox}>
            <Icon source="tray" size={44} color="#C0C4CC" />
            <Text style={styles.stateText}>{t('common.noData')}</Text>
          </View>
        )}

        {/* 需要关注 */}
        {!loading && !error && needAttention.length > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <Icon source="alert-circle" size={18} color="#ff4d4f" />
              <Text style={styles.sectionTitle}>{t('equipment.sections.needAttention')}</Text>
            </View>
            {needAttention.map(renderEquipmentCard)}
          </View>
        )}

        {/* 运行中 */}
        {!loading && !error && running.length > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <Icon source="play-circle" size={18} color="#52c41a" />
              <Text style={styles.sectionTitle}>{t('equipment.sections.running')}</Text>
            </View>
            {running.map(renderEquipmentCard)}
          </View>
        )}

        {/* 空闲 */}
        {!loading && !error && idle.length > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <Icon source="pause-circle" size={18} color="#1890ff" />
              <Text style={styles.sectionTitle}>{t('equipment.sections.idle')}</Text>
            </View>
            {idle.map(renderEquipmentCard)}
          </View>
        )}

        {/* 其他（含后端返回了本屏不认识的 status 的设备） */}
        {!loading && !error && other.length > 0 && (
          <View style={styles.section}>
            <View style={styles.sectionHeader}>
              <Icon source="dots-horizontal-circle" size={18} color="#8c8c8c" />
              <Text style={styles.sectionTitle}>其他</Text>
            </View>
            {other.map(renderEquipmentCard)}
          </View>
        )}

        <View style={{ height: 100 }} />
      </ScrollView>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f7fa',
  },
  header: {
    paddingHorizontal: 20,
    paddingVertical: 16,
    backgroundColor: '#667eea',
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: '600',
    color: '#fff',
  },

  // 搜索栏
  searchContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
  },
  searchBar: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#f5f5f5',
    borderRadius: 8,
    paddingHorizontal: 12,
    height: 40,
  },
  searchInput: {
    flex: 1,
    marginLeft: 8,
    fontSize: 14,
    color: '#333',
  },

  // 统计
  statsGrid: {
    flexDirection: 'row',
    paddingHorizontal: 16,
    paddingVertical: 12,
    backgroundColor: '#fff',
    gap: 8,
    marginBottom: 8,
  },
  statCard: {
    flex: 1,
    alignItems: 'center',
    paddingVertical: 12,
    borderRadius: 12,
  },
  statValue: {
    fontSize: 20,
    fontWeight: 'bold',
    marginTop: 4,
  },
  statLabel: {
    fontSize: 11,
    color: '#666',
    marginTop: 2,
  },

  // 列表
  listContainer: {
    flex: 1,
    paddingHorizontal: 16,
    paddingTop: 8,
  },
  section: {
    marginBottom: 16,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: '600',
    color: '#333',
    marginLeft: 6,
  },

  // 设备卡片
  equipmentCard: {
    flexDirection: 'row',
    alignItems: 'center',
    backgroundColor: '#fff',
    borderRadius: 12,
    padding: 16,
    marginBottom: 10,
  },
  alertCard: {
    borderLeftWidth: 4,
    borderLeftColor: '#ff4d4f',
  },
  equipmentAvatar: {
    width: 48,
    height: 48,
    borderRadius: 12,
    justifyContent: 'center',
    alignItems: 'center',
  },
  equipmentInfo: {
    flex: 1,
    marginLeft: 12,
  },
  equipmentNameRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  equipmentName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#333',
  },
  alertBadge: {
    flexDirection: 'row',
    alignItems: 'center',
    marginLeft: 8,
    paddingHorizontal: 6,
    paddingVertical: 2,
    backgroundColor: '#ff4d4f',
    borderRadius: 10,
  },
  alertBadgeText: {
    fontSize: 10,
    color: '#fff',
    marginLeft: 2,
    fontWeight: 'bold',
  },
  equipmentMeta: {
    fontSize: 13,
    color: '#999',
    marginTop: 4,
  },
  equipmentTask: {
    fontSize: 12,
    color: '#667eea',
    marginTop: 4,
  },
  equipmentRight: {
    alignItems: 'flex-end',
  },
  stateBox: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: 48,
  },
  stateText: {
    marginTop: 12,
    fontSize: 14,
    color: '#606266',
    textAlign: 'center',
    paddingHorizontal: 24,
  },
  retryBtn: {
    marginTop: 16,
    paddingHorizontal: 20,
    paddingVertical: 8,
    backgroundColor: '#667eea',
    borderRadius: 6,
  },
  retryBtnText: {
    color: '#fff',
    fontSize: 14,
    fontWeight: '500',
  },
  statusBadge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 10,
  },
  statusText: {
    fontSize: 11,
    fontWeight: '500',
  },
});

export default WSEquipmentScreen;
