import React, { useState, useEffect, useCallback } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  RefreshControl,
} from 'react-native';
import {
  Text,
  Card,
  Surface,
  Divider,
  Chip,
  ActivityIndicator,
  useTheme,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRoute } from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  traceabilityApiClient,
  FullTraceResponse,
  MaterialInfo,
  ProcessingStageInfo,
  QualityInfo,
  ShipmentInfo,
} from '../../services/api/traceabilityApiClient';
import LineageChainTimeline from './components/LineageChainTimeline';
import SourceIndividualBanner from './components/SourceIndividualBanner';

/**
 * 生产批次 `qualityStatus` 的用户可读说法。
 *
 * 取值集合来自后端 `entity/enums/QualityStatus.java`（11 个枚举名，中文取自枚举自带的
 * description），外加 `TraceabilityServiceImpl#buildProductionInfo` 在字段为 null 时
 * 兜出来的 `"PENDING"`（⚠️ 它不是枚举值，是后端的 null 占位 —— 公开溯源那条路对同一种
 * 情况写的是「待检验」，这里沿用同一句话）。
 *
 * ⚠️ 未知状态码**原样显示那个码**，⛔ 不翻译成「未知」之类看起来正常的值 ——
 * 把不认识的输入翻译成一个合法值，正是本仓踩过多次的那个坑（同 `DNA_STATUS_LABEL`）。
 *
 * ⚠️ `TraceabilityScreen.tsx` / `PublicTraceScreen.tsx` 各有一份内联 switch
 * （都带 `default: status || '未知'`），本次不在改动范围，登记在此，⛔ 别再抄第四份。
 */
export const QUALITY_STATUS_LABEL: Record<string, string> = {
  PENDING: '待检验',
  PENDING_INSPECTION: '待质检',
  INSPECTING: '质检中',
  NEEDS_REVIEW: '待审核',
  PASSED: '已通过',
  QUALIFIED: '合格',
  FAILED: '未通过',
  PARTIAL_PASS: '部分通过',
  REWORK_REQUIRED: '需返工',
  REWORKING: '返工中',
  REWORK_COMPLETED: '返工完成',
  SCRAPPED: '已报废',
};

export function qualityStatusLabel(status?: string | null): string | null {
  if (status == null || status === '') {
    return null;
  }
  const key = status.toUpperCase();
  return Object.prototype.hasOwnProperty.call(QUALITY_STATUS_LABEL, key)
    ? QUALITY_STATUS_LABEL[key]!
    : status;
}

/**
 * 后端 `TraceabilityServiceImpl#buildMaterialInfoList` 在原料批次没有 supplierId、
 * 或 supplierId 查不到 Supplier 时，把 supplierName 兜成这个字面串。
 * 它是后端造出来的占位，不是数据 —— 自有牧场的牛本来就没有「供应商」。
 * ⛔ 前端只负责不显示它，不许再造一个「自有牧场」之类的假值顶上去。
 */
export const BACKEND_UNKNOWN_SUPPLIER_PLACEHOLDER = '未知供应商';

/** 底部那一行灰字的三个可折叠段 —— 按页面顺序。 */
export const EMPTY_SECTION_LABEL = {
  processingStages: '加工',
  qualityInspections: '质检',
  shipments: '出货',
} as const;

export function emptySectionsLine(
  empties: ReadonlyArray<keyof typeof EMPTY_SECTION_LABEL>,
): string | null {
  if (empties.length === 0) {
    return null;
  }
  return `暂无${empties.map((k) => EMPTY_SECTION_LABEL[k]).join('/')}记录`;
}

function isBlank(value: unknown): boolean {
  return value == null || value === '' || value === '-';
}

/**
 * 完整溯源链路详情屏幕
 * 展示从原材料到出货的完整追溯信息
 */
const TraceabilityDetailScreen: React.FC = () => {
  const theme = useTheme();
  const route = useRoute<any>();
  const { batchNumber, traceCode: scannedTraceCode } = (route.params || {}) as {
    batchNumber?: string;
    /**
     * 扫码 / 手输进来的**标签码**（可选）。
     * ⚠️ 2026-09-15：全仓还没有调用方传它（`TraceabilityScreen` 只传 batchNumber），
     * 这里先把槽位留出来；接上之前，卡片只会显示服务端那一个码。
     */
    traceCode?: string;
  };

  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [traceData, setTraceData] = useState<FullTraceResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const loadTraceData = useCallback(async () => {
    if (!batchNumber) {
      setError('缺少批次号参数');
      setLoading(false);
      return;
    }

    try {
      const result = await traceabilityApiClient.getFullTrace(batchNumber);
      if (result) {
        setTraceData(result);
        setError(null);
      } else {
        setError('未找到该批次的溯源信息');
      }
    } catch (err: any) {
      console.error('加载溯源详情失败:', err);
      setError(err.message || '加载失败');
    } finally {
      setLoading(false);
    }
  }, [batchNumber]);

  useEffect(() => {
    loadTraceData();
  }, [loadTraceData]);

  const onRefresh = useCallback(async () => {
    setRefreshing(true);
    await loadTraceData();
    setRefreshing(false);
  }, [loadTraceData]);

  const getQualityStatusColor = (status: string) => {
    switch (status?.toUpperCase()) {
      case 'PASSED':
      case 'PASS':
      case 'QUALIFIED':
        return '#4CAF50';
      case 'FAILED':
      case 'FAIL':
      case 'SCRAPPED':
        return '#f44336';
      case 'CONDITIONAL':
      case 'PARTIAL_PASS':
      case 'REWORK_REQUIRED':
      case 'REWORKING':
        return '#FF9800';
      default:
        return '#9e9e9e';
    }
  };

  const getShipmentStatusColor = (status: string) => {
    switch (status?.toLowerCase()) {
      case 'delivered':
        return '#4CAF50';
      case 'shipped':
        return '#2196F3';
      case 'pending':
        return '#FF9800';
      case 'returned':
        return '#f44336';
      default:
        return '#9e9e9e';
    }
  };

  const getShipmentStatusText = (status: string) => {
    switch (status?.toLowerCase()) {
      case 'delivered':
        return '已送达';
      case 'shipped':
        return '已发货';
      case 'pending':
        return '待发货';
      case 'returned':
        return '已退货';
      default:
        return status || '未知';
    }
  };

  if (loading) {
    return (
      <View style={styles.centerContainer}>
        <ActivityIndicator size="large" />
        <Text style={styles.loadingText}>正在加载溯源数据...</Text>
      </View>
    );
  }

  if (error) {
    return (
      <View style={styles.centerContainer}>
        <MaterialCommunityIcons name="alert-circle-outline" size={64} color={theme.colors.error} />
        <Text style={[styles.errorText, { color: theme.colors.error }]}>{error}</Text>
      </View>
    );
  }

  if (!traceData) {
    return (
      <View style={styles.centerContainer}>
        <Text>无数据</Text>
      </View>
    );
  }

  // 多跳来源链已经包含一跳（本批次的 MaterialConsumption 就是 depth=1 那一层），
  // 有祖先时再列一遍「原材料溯源」= 同 6 头牛列两遍。只有血缘没回填 / 引擎没拿到时
  // 才退回一跳列表，让老租户仍看得到原料。
  const hasLineageAncestors = (traceData.lineage?.ancestors?.length ?? 0) > 0;

  const processingStages = traceData.processingStages ?? [];
  const emptySections: Array<keyof typeof EMPTY_SECTION_LABEL> = [];
  if (processingStages.length === 0) emptySections.push('processingStages');
  if (traceData.qualityInspections.length === 0) emptySections.push('qualityInspections');
  if (traceData.shipments.length === 0) emptySections.push('shipments');
  const emptyLine = emptySectionsLine(emptySections);

  // 标签码与系统码不同时才需要把两个都摆出来并各自贴标签。
  const labelCode = scannedTraceCode?.trim() || null;
  const showBothCodes = !!labelCode && labelCode !== traceData.traceCode;

  return (
    <SafeAreaView style={styles.container} edges={['bottom']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
      >
        {/* 溯源码
            🔴 `traceData.traceCode` 是**每次查询现生成**的，不是标签上印的那个码：
            `TraceabilityServiceImpl#getFullTrace` 第 7 步调 `generateTraceCode(factoryId, batchNumber)`
            → `EncodingRuleServiceImpl#generateCode(factoryId, "TRACE_CODE", ctx)`：
              · 工厂配了 TRACE_CODE 编码规则 → 按规则出码并 incrementSequence（所以这个方法不能 readOnly）；
              · 没配（演示工厂 CATTLE_3701_001 就没配）→ `generateDefaultCode` =
                `TR-{factoryId}-{yyyyMMdd}-{System.currentTimeMillis() % 10000}`，
                即真机看到的 `TR-CATTLE_3701_001-20260915-8125`。
            两条路都**不落库、不与批次绑定**，下拉刷新一次就换一个；`TR-…` 也扫不回来
            （`TraceabilityController#resolveBatchNumberFromTraceCode` 只认 `TRACE-` 内嵌格式
            或 `labels.trace_code` 里持久化过的码）。
            而标签上印的 `TRACE-SL-DEMO-BATCH-V1-SEED01` 是 seed 脚本经 `POST /labels` 写进
            `labels.trace_code` 的内嵌格式码（`TRACE-{batchNumber}-{后缀}`），才是能扫的那个。
            ⇒ 有标签码时把两个都摆出来并标「标签码 / 系统码」，否则只标「溯源码」；
            批次号永远在下面一行，它才是两边共同的锚。 */}
        <Surface style={styles.traceCodeCard} elevation={2} testID="trace-code-card">
          <View style={styles.traceCodeHeader}>
            <MaterialCommunityIcons name="qrcode" size={24} color={theme.colors.primary} />
            {showBothCodes ? (
              <View style={styles.traceCodeColumn}>
                <View style={styles.traceCodeRow}>
                  <Text style={styles.traceCodeLabel}>标签码</Text>
                  <Text style={styles.traceCode} testID="trace-code-label">{labelCode}</Text>
                </View>
                <View style={styles.traceCodeRow}>
                  <Text style={styles.traceCodeLabel}>系统码</Text>
                  <Text style={styles.traceCode} testID="trace-code-system">{traceData.traceCode}</Text>
                </View>
              </View>
            ) : (
              <View style={styles.traceCodeRow}>
                <Text style={styles.traceCodeLabel}>溯源码</Text>
                <Text style={styles.traceCode} testID="trace-code-system">{traceData.traceCode}</Text>
              </View>
            )}
          </View>
          <Text style={styles.traceCodeBatch} testID="trace-code-batch">
            批次号 {traceData.production.batchNumber}
          </Text>
        </Surface>

        {/* === 1. 生产信息 === */}
        <Card style={styles.card}>
          <Card.Content>
            <View style={styles.sectionHeader}>
              <View style={styles.sectionIconContainer}>
                <MaterialCommunityIcons name="factory" size={24} color="#fff" />
              </View>
              <Text variant="titleMedium" style={styles.sectionTitle}>生产信息</Text>
            </View>
            <Divider style={styles.divider} />

            <View style={styles.infoGrid}>
              <InfoItem label="批次号" value={traceData.production.batchNumber} />
              <InfoItem label="产品名称" value={traceData.production.productName} />
              <InfoItem label="生产工厂" value={traceData.production.factoryName} />
              <InfoItem label="生产日期" value={traceData.production.productionDate} />
              {/* 负责人 / 设备 为空时整行不出现，⛔ 不显示占位横杠（InfoItem 内部判空） */}
              <InfoItem label="负责人" value={traceData.production.supervisorName} />
              <InfoItem label="设备" value={traceData.production.equipmentName} />
              {traceData.production.quantity != null && (
                <InfoItem
                  label="产量"
                  value={`${traceData.production.quantity} ${traceData.production.unit || ''}`}
                />
              )}
              <InfoItem
                label="质量状态"
                value={qualityStatusLabel(traceData.production.qualityStatus)}
                chipColor={getQualityStatusColor(traceData.production.qualityStatus)}
              />
            </View>
          </Card.Content>
        </Card>

        {/* === 2. 「本批来自 N 头牛」横幅 + 来源链（多跳血缘） ===
            排在生产信息之后、加工环节之前 —— 真机实测它原来在一跳列表后面，要滚两屏。
            ⚠️ 这一屏才是演示实际走到的地方（ProcessingDashboard → 溯源查询 →
            TraceabilityScreen 输入批次号 → 这里）；PublicTraceScreen 只注册了路由，
            全仓零 navigate('PublicTrace')、无 linking 配置，App 里到不了。
            ⛔ N 不在前端算，见 SourceIndividualBanner 的注释。 */}
        <SourceIndividualBanner lineage={traceData.lineage} />

        <LineageChainTimeline lineage={traceData.lineage} showSupplier />

        {/* === 2.1 原材料溯源（一跳）——只在没有多跳祖先时退回显示 ===
            有祖先时来源链已经把 depth=1 那层（就是这份 materials）画出来了，
            再列一遍就是同一批牛出现两次。 */}
        {!hasLineageAncestors && (
          <Card style={styles.card} testID="materials-one-hop-card">
            <Card.Content>
              <View style={styles.sectionHeader}>
                <View style={[styles.sectionIconContainer, { backgroundColor: '#FF9800' }]}>
                  <MaterialCommunityIcons name="package-variant" size={24} color="#fff" />
                </View>
                <Text variant="titleMedium" style={styles.sectionTitle}>原材料溯源</Text>
                <Chip mode="flat">{traceData.materials.length} 批次</Chip>
              </View>
              <Divider style={styles.divider} />

              {traceData.materials.length === 0 ? (
                <Text style={styles.emptyText}>暂无原材料消耗记录</Text>
              ) : (
                traceData.materials.map((material, index) => (
                  <MaterialCard key={index} material={material} isLast={index === traceData.materials.length - 1} />
                ))
              )}
            </Card.Content>
          </Card>
        )}

        {/* === 3. 加工环节 ===（空数组不占卡，折进底部那一行） */}
        {processingStages.length > 0 && (
          <Card style={styles.card}>
            <Card.Content>
              <View style={styles.sectionHeader}>
                <View style={[styles.sectionIconContainer, { backgroundColor: '#00BCD4' }]}>
                  <MaterialCommunityIcons name="cog-transfer" size={24} color="#fff" />
                </View>
                <Text variant="titleMedium" style={styles.sectionTitle}>加工环节</Text>
                <Chip mode="flat">{processingStages.length} 道工序</Chip>
              </View>
              <Divider style={styles.divider} />

              {[...processingStages]
                .sort((a, b) => (a.stageOrder ?? 0) - (b.stageOrder ?? 0))
                .map((stage, index) => (
                <ProcessingStageCard
                  key={index}
                  stage={stage}
                  isLast={index === processingStages.length - 1}
                />
              ))}
            </Card.Content>
          </Card>
        )}

        {/* === 4. 质检记录 ===（空数组不占卡） */}
        {traceData.qualityInspections.length > 0 && (
          <Card style={styles.card}>
            <Card.Content>
              <View style={styles.sectionHeader}>
                <View style={[styles.sectionIconContainer, { backgroundColor: '#9C27B0' }]}>
                  <MaterialCommunityIcons name="clipboard-check-outline" size={24} color="#fff" />
                </View>
                <Text variant="titleMedium" style={styles.sectionTitle}>质检记录</Text>
                <Chip mode="flat">{traceData.qualityInspections.length} 次</Chip>
              </View>
              <Divider style={styles.divider} />

              {traceData.qualityInspections.map((inspection, index) => (
                <QualityCard
                  key={index}
                  inspection={inspection}
                  getStatusColor={getQualityStatusColor}
                  isLast={index === traceData.qualityInspections.length - 1}
                />
              ))}
            </Card.Content>
          </Card>
        )}

        {/* === 5. 出货记录 ===（空数组不占卡） */}
        {traceData.shipments.length > 0 && (
          <Card style={styles.card}>
            <Card.Content>
              <View style={styles.sectionHeader}>
                <View style={[styles.sectionIconContainer, { backgroundColor: '#4CAF50' }]}>
                  <MaterialCommunityIcons name="truck-delivery" size={24} color="#fff" />
                </View>
                <Text variant="titleMedium" style={styles.sectionTitle}>出货记录</Text>
                <Chip mode="flat">{traceData.shipments.length} 单</Chip>
              </View>
              <Divider style={styles.divider} />

              {traceData.shipments.map((shipment, index) => (
                <ShipmentCard
                  key={index}
                  shipment={shipment}
                  getStatusColor={getShipmentStatusColor}
                  getStatusText={getShipmentStatusText}
                  isLast={index === traceData.shipments.length - 1}
                />
              ))}
            </Card.Content>
          </Card>
        )}

        {/* 三段里真正为空的那些，合成一行灰字；一段都不空时这一行不出现。 */}
        {emptyLine && (
          <Text style={styles.emptySectionsLine} testID="empty-sections-line">{emptyLine}</Text>
        )}

        {/* 查询时间 */}
        <Text style={styles.queryTime}>
          查询时间: {traceData.queryTime ? new Date(traceData.queryTime).toLocaleString() : '-'}
        </Text>
      </ScrollView>
    </SafeAreaView>
  );
};

// 信息项组件 —— 值为空 / null / "-" 时整行不渲染，⛔ 不显示占位横杠。
const InfoItem: React.FC<{
  label: string;
  value?: string | null;
  chipColor?: string;
}> = ({ label, value, chipColor }) => {
  if (isBlank(value)) {
    return null;
  }
  return (
    <View style={styles.infoItem}>
      <Text style={styles.infoLabel}>{label}</Text>
      {chipColor ? (
        <Chip
          mode="flat"
          style={[styles.statusChip, { backgroundColor: chipColor + '20' }]}
          textStyle={{ color: chipColor, fontSize: 12 }}
        >
          {value}
        </Chip>
      ) : (
        <Text style={styles.infoValue}>{value}</Text>
      )}
    </View>
  );
};

// 加工环节卡片组件
const ProcessingStageCard: React.FC<{ stage: ProcessingStageInfo; isLast: boolean }> = ({ stage, isLast }) => (
  <View style={[styles.itemCard, !isLast && styles.itemCardBorder]}>
    <View style={styles.itemHeader}>
      <Text style={styles.itemTitle}>{stage.stageName || stage.stageType}</Text>
      <Text style={styles.itemSubtitle}>第{stage.stageOrder}道工序</Text>
    </View>
    <View style={styles.itemDetails}>
      {stage.operatorName && (
        <Text style={styles.itemDetail}>操作员: {stage.operatorName}</Text>
      )}
      {stage.equipmentName && (
        <Text style={styles.itemDetail}>设备: {stage.equipmentName}</Text>
      )}
      {stage.durationMinutes != null && (
        <Text style={styles.itemDetail}>耗时: {stage.durationMinutes}分钟</Text>
      )}
      {stage.inputWeight != null && stage.outputWeight != null && (
        <Text style={styles.itemDetail}>
          投入/产出: {stage.inputWeight}kg → {stage.outputWeight}kg
        </Text>
      )}
      {stage.lossRate != null && (
        <Text style={styles.itemDetail}>损耗率: {(stage.lossRate * 100).toFixed(1)}%</Text>
      )}
      {stage.passRate != null && (
        <Text style={styles.itemDetail}>合格率: {(stage.passRate * 100).toFixed(1)}%</Text>
      )}
      {stage.temperature != null && (
        <Text style={styles.itemDetail}>温度: {stage.temperature}°C</Text>
      )}
      {stage.startTime && (
        <Text style={styles.itemDetail}>
          时间: {new Date(stage.startTime).toLocaleString()}
          {stage.endTime ? ` ~ ${new Date(stage.endTime).toLocaleTimeString()}` : ''}
        </Text>
      )}
    </View>
  </View>
);

// 原材料卡片组件（一跳）
const MaterialCard: React.FC<{ material: MaterialInfo; isLast: boolean }> = ({ material, isLast }) => {
  // 后端兜出来的「未知供应商」是占位不是数据（见 BACKEND_UNKNOWN_SUPPLIER_PLACEHOLDER）
  const supplierName =
    isBlank(material.supplierName) || material.supplierName === BACKEND_UNKNOWN_SUPPLIER_PLACEHOLDER
      ? null
      : material.supplierName;
  return (
    <View style={[styles.itemCard, !isLast && styles.itemCardBorder]}>
      <View style={styles.itemHeader}>
        <Text style={styles.itemTitle}>{material.materialName}</Text>
        <Text style={styles.itemSubtitle}>{material.batchNumber}</Text>
      </View>
      <View style={styles.itemDetails}>
        {supplierName && (
          <Text style={styles.itemDetail}>供应商: {supplierName}</Text>
        )}
        {!isBlank(material.receiptDate) && (
          <Text style={styles.itemDetail}>入库日期: {material.receiptDate}</Text>
        )}
        {material.expireDate && (
          <Text style={styles.itemDetail}>过期日期: {material.expireDate}</Text>
        )}
        {material.quantity != null && (
          <Text style={styles.itemDetail}>消耗量: {material.quantity} {material.unit}</Text>
        )}
        {material.storageLocation && (
          <Text style={styles.itemDetail}>存储位置: {material.storageLocation}</Text>
        )}
      </View>
    </View>
  );
};

// 质检卡片组件
const QualityCard: React.FC<{
  inspection: QualityInfo;
  getStatusColor: (status: string) => string;
  isLast: boolean;
}> = ({ inspection, getStatusColor, isLast }) => (
  <View style={[styles.itemCard, !isLast && styles.itemCardBorder]}>
    <View style={styles.itemHeader}>
      <Text style={styles.itemTitle}>质检 #{inspection.inspectionId?.slice(-8) || '-'}</Text>
      <Chip
        mode="flat"
        style={[
          styles.miniChip,
          { backgroundColor: getStatusColor(inspection.result) + '20' },
        ]}
        textStyle={{ color: getStatusColor(inspection.result), fontSize: 11 }}
      >
        {inspection.result || '待定'}
      </Chip>
    </View>
    <View style={styles.itemDetails}>
      <Text style={styles.itemDetail}>检验日期: {inspection.inspectionDate?.split('T')[0]}</Text>
      <Text style={styles.itemDetail}>检验员: {inspection.inspectorName}</Text>
      {inspection.passRate !== undefined && (
        <Text style={styles.itemDetail}>合格率: {inspection.passRate}%</Text>
      )}
      {inspection.remarks && (
        <Text style={styles.itemDetail}>备注: {inspection.remarks}</Text>
      )}
    </View>
  </View>
);

// 出货卡片组件
const ShipmentCard: React.FC<{
  shipment: ShipmentInfo;
  getStatusColor: (status: string) => string;
  getStatusText: (status: string) => string;
  isLast: boolean;
}> = ({ shipment, getStatusColor, getStatusText, isLast }) => (
  <View style={[styles.itemCard, !isLast && styles.itemCardBorder]}>
    <View style={styles.itemHeader}>
      <Text style={styles.itemTitle}>{shipment.shipmentNumber}</Text>
      <Chip
        mode="flat"
        style={[
          styles.miniChip,
          { backgroundColor: getStatusColor(shipment.status) + '20' },
        ]}
        textStyle={{ color: getStatusColor(shipment.status), fontSize: 11 }}
      >
        {getStatusText(shipment.status)}
      </Chip>
    </View>
    <View style={styles.itemDetails}>
      <Text style={styles.itemDetail}>出货日期: {shipment.shipmentDate}</Text>
      <Text style={styles.itemDetail}>客户: {shipment.customerName}</Text>
      {shipment.quantity && (
        <Text style={styles.itemDetail}>数量: {shipment.quantity} {shipment.unit}</Text>
      )}
      {shipment.logisticsCompany && (
        <Text style={styles.itemDetail}>物流公司: {shipment.logisticsCompany}</Text>
      )}
      {shipment.trackingNumber && (
        <Text style={styles.itemDetail}>物流单号: {shipment.trackingNumber}</Text>
      )}
    </View>
  </View>
);

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f5f5f5',
  },
  centerContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: 20,
  },
  loadingText: {
    marginTop: 12,
    color: '#666',
  },
  errorText: {
    marginTop: 12,
    fontSize: 16,
    textAlign: 'center',
  },
  scrollView: {
    flex: 1,
  },
  scrollContent: {
    padding: 16,
    paddingBottom: 32,
  },
  traceCodeCard: {
    padding: 16,
    borderRadius: 12,
    marginBottom: 16,
  },
  traceCodeHeader: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  traceCodeColumn: {
    flex: 1,
    gap: 4,
  },
  traceCodeRow: {
    flex: 1,
    flexDirection: 'row',
    alignItems: 'center',
  },
  traceCodeLabel: {
    marginLeft: 8,
    color: '#666',
  },
  traceCode: {
    flex: 1,
    textAlign: 'right',
    fontFamily: 'monospace',
    fontSize: 12,
    color: '#333',
  },
  traceCodeBatch: {
    marginTop: 8,
    marginLeft: 32,
    fontSize: 12,
    color: '#888',
  },
  card: {
    marginBottom: 16,
    borderRadius: 12,
  },
  sectionHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 8,
  },
  sectionIconContainer: {
    width: 36,
    height: 36,
    borderRadius: 18,
    backgroundColor: '#2196F3',
    justifyContent: 'center',
    alignItems: 'center',
  },
  sectionTitle: {
    marginLeft: 12,
    flex: 1,
    fontWeight: '600',
  },
  divider: {
    marginVertical: 12,
  },
  infoGrid: {
    gap: 8,
  },
  infoItem: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: 4,
  },
  infoLabel: {
    color: '#666',
    fontSize: 14,
  },
  infoValue: {
    fontSize: 14,
    fontWeight: '500',
  },
  statusChip: {
    height: 26,
  },
  miniChip: {
    height: 24,
  },
  emptyText: {
    textAlign: 'center',
    color: '#999',
    paddingVertical: 16,
  },
  emptySectionsLine: {
    textAlign: 'center',
    color: '#999',
    fontSize: 13,
    marginTop: 4,
    marginBottom: 8,
  },
  itemCard: {
    paddingVertical: 12,
  },
  itemCardBorder: {
    borderBottomWidth: 1,
    borderBottomColor: '#eee',
  },
  itemHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 8,
  },
  itemTitle: {
    fontSize: 14,
    fontWeight: '600',
  },
  itemSubtitle: {
    fontSize: 12,
    color: '#666',
  },
  itemDetails: {
    gap: 4,
  },
  itemDetail: {
    fontSize: 13,
    color: '#555',
  },
  queryTime: {
    textAlign: 'center',
    color: '#999',
    fontSize: 12,
    marginTop: 8,
  },
});

export default TraceabilityDetailScreen;
