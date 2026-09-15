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
  ActivityIndicator,
  useTheme,
} from 'react-native-paper';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRoute } from '@react-navigation/native';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { AppTheme } from '../../theme';
import {
  traceabilityApiClient,
  FullTraceResponse,
  MaterialInfo,
  ProcessingStageInfo,
  QualityInfo,
  ShipmentInfo,
} from '../../services/api/traceabilityApiClient';
import LineageChainTimeline, { SectionHeader } from './components/LineageChainTimeline';
import SourceIndividualBanner from './components/SourceIndividualBanner';
import { ToneChip, type StatusTone } from './components/statusTone';

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
 * 质量状态 → 色调。这里**只**决定「是哪一种色调」，色值由 `statusTone.ts` 从主题 token 取，
 * ⛔ 不再在屏里写硬编码色值，也不再用 `chipColor + '20'` 拼透明度。
 *
 * 待检验 / 质检中 / 待审核 / 需返工 这些「还没定」的态走 pending（橙），
 * 合格 / 通过 / 返工完成 走 success，不合格 / 报废 走 error，认不出来的码走 neutral。
 */
export function qualityStatusTone(status?: string | null): StatusTone {
  switch (status?.toUpperCase()) {
    case 'PASSED':
    case 'PASS':
    case 'QUALIFIED':
    case 'REWORK_COMPLETED':
      return 'success';
    case 'FAILED':
    case 'FAIL':
    case 'SCRAPPED':
      return 'error';
    case 'PENDING':
    case 'PENDING_INSPECTION':
    case 'INSPECTING':
    case 'NEEDS_REVIEW':
    case 'CONDITIONAL':
    case 'PARTIAL_PASS':
    case 'REWORK_REQUIRED':
    case 'REWORKING':
      return 'pending';
    default:
      return 'neutral';
  }
}

export function shipmentStatusTone(status?: string | null): StatusTone {
  switch (status?.toLowerCase()) {
    case 'delivered':
      return 'success';
    case 'shipped':
      return 'info';
    case 'pending':
      return 'pending';
    case 'returned':
      return 'error';
    default:
      return 'neutral';
  }
}

/**
 * 完整溯源链路详情屏幕
 * 展示从原材料到出货的完整追溯信息
 */
const TraceabilityDetailScreen: React.FC = () => {
  const theme = useTheme<AppTheme>();
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

  const { spacing, borderRadius } = theme.custom;
  const cardStyle = [styles.card, { marginBottom: spacing.l, borderRadius: borderRadius.m }];
  const dividerStyle = { marginVertical: spacing.m };

  if (loading) {
    return (
      <View style={[styles.centerContainer, { padding: spacing.xl }]}>
        <ActivityIndicator size="large" />
        <Text variant="bodyMedium" style={{ marginTop: spacing.m, color: theme.colors.onSurfaceVariant }}>
          正在加载溯源数据...
        </Text>
      </View>
    );
  }

  if (error) {
    return (
      <View style={[styles.centerContainer, { padding: spacing.xl }]}>
        <MaterialCommunityIcons name="alert-circle-outline" size={64} color={theme.colors.error} />
        <Text variant="bodyLarge" style={[styles.errorText, { marginTop: spacing.m, color: theme.colors.error }]}>
          {error}
        </Text>
      </View>
    );
  }

  if (!traceData) {
    return (
      <View style={[styles.centerContainer, { padding: spacing.xl }]}>
        <Text variant="bodyMedium">无数据</Text>
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
  const showBothCodes = !!labelCode && !!traceData.traceCode && labelCode !== traceData.traceCode;

  const codeLabelStyle = { marginLeft: spacing.s, color: theme.colors.onSurfaceVariant };
  const codeValueStyle = [styles.traceCode, { color: theme.colors.onSurface }];

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.colors.background }]} edges={['bottom']}>
      <ScrollView
        style={styles.scrollView}
        contentContainerStyle={{ padding: spacing.l, paddingBottom: spacing.xxl }}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
      >
        {/* 溯源码 = 这个批次已打印标签上的码(后端只读 labels.trace_code, 2026-09-15 起不再现造)。
            null ⇒ 还没打过标签, 明说「未打印标签」, ⛔ 不显示一个扫不回来的码。
            `route.params.traceCode`(扫码/输入进来的)与服务端持久化码不同时两个都摆出来。 */}
        <Surface
          style={[
            styles.traceCodeCard,
            {
              padding: spacing.l,
              marginBottom: spacing.l,
              borderRadius: borderRadius.m,
              backgroundColor: theme.colors.surfaceVariant,
            },
          ]}
          elevation={0}
          testID="trace-code-card"
        >
          <View style={styles.traceCodeHeader}>
            <MaterialCommunityIcons name="qrcode" size={24} color={theme.colors.primary} />
            {showBothCodes ? (
              <View style={[styles.traceCodeColumn, { gap: spacing.xs }]}>
                <View style={styles.traceCodeRow}>
                  <Text variant="labelMedium" style={codeLabelStyle}>扫入码</Text>
                  <Text variant="bodyMedium" style={codeValueStyle} testID="trace-code-label">{labelCode}</Text>
                </View>
                <View style={styles.traceCodeRow}>
                  <Text variant="labelMedium" style={codeLabelStyle}>标签码</Text>
                  <Text variant="bodyMedium" style={codeValueStyle} testID="trace-code-system">{traceData.traceCode}</Text>
                </View>
              </View>
            ) : traceData.traceCode ? (
              <View style={styles.traceCodeRow}>
                <Text variant="labelMedium" style={codeLabelStyle}>溯源码</Text>
                <Text variant="bodyMedium" style={codeValueStyle} testID="trace-code-system">{traceData.traceCode}</Text>
              </View>
            ) : (
              <View style={styles.traceCodeRow}>
                <Text variant="bodyMedium" style={codeLabelStyle} testID="trace-code-missing">未打印标签</Text>
              </View>
            )}
          </View>
          <Text
            variant="bodyMedium"
            style={{ marginTop: spacing.s, marginLeft: spacing.xxl, color: theme.colors.onSurfaceVariant }}
            testID="trace-code-batch"
          >
            批次号 {traceData.production.batchNumber}
          </Text>
        </Surface>

        {/* === 1. 生产信息 === */}
        <Card style={cardStyle}>
          <Card.Content>
            <SectionHeader icon="factory" title="生产信息" />
            <Divider style={dividerStyle} />

            <View style={{ gap: spacing.s }}>
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
                tone={qualityStatusTone(traceData.production.qualityStatus)}
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
          <Card style={cardStyle} testID="materials-one-hop-card">
            <Card.Content>
              <SectionHeader icon="package-variant" title="原材料溯源" trailing={`${traceData.materials.length} 批次`} />
              <Divider style={dividerStyle} />

              {traceData.materials.length === 0 ? (
                <Text
                  variant="bodyMedium"
                  style={[styles.emptyText, { color: theme.colors.onSurfaceVariant, paddingVertical: spacing.l }]}
                >
                  暂无原材料消耗记录
                </Text>
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
          <Card style={cardStyle}>
            <Card.Content>
              <SectionHeader icon="cog-transfer" title="加工环节" trailing={`${processingStages.length} 道工序`} />
              <Divider style={dividerStyle} />

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
          <Card style={cardStyle}>
            <Card.Content>
              <SectionHeader
                icon="clipboard-check-outline"
                title="质检记录"
                trailing={`${traceData.qualityInspections.length} 次`}
              />
              <Divider style={dividerStyle} />

              {traceData.qualityInspections.map((inspection, index) => (
                <QualityCard
                  key={index}
                  inspection={inspection}
                  isLast={index === traceData.qualityInspections.length - 1}
                />
              ))}
            </Card.Content>
          </Card>
        )}

        {/* === 5. 出货记录 ===（空数组不占卡） */}
        {traceData.shipments.length > 0 && (
          <Card style={cardStyle}>
            <Card.Content>
              <SectionHeader icon="truck-delivery" title="出货记录" trailing={`${traceData.shipments.length} 单`} />
              <Divider style={dividerStyle} />

              {traceData.shipments.map((shipment, index) => (
                <ShipmentCard
                  key={index}
                  shipment={shipment}
                  getStatusText={getShipmentStatusText}
                  isLast={index === traceData.shipments.length - 1}
                />
              ))}
            </Card.Content>
          </Card>
        )}

        {/* 三段里真正为空的那些，合成一行灰字；一段都不空时这一行不出现。 */}
        {emptyLine && (
          <Text
            variant="bodySmall"
            style={[
              styles.emptyText,
              { color: theme.colors.onSurfaceVariant, marginTop: spacing.xs, marginBottom: spacing.s },
            ]}
            testID="empty-sections-line"
          >
            {emptyLine}
          </Text>
        )}

        {/* 查询时间 */}
        <Text
          variant="bodySmall"
          style={[styles.emptyText, { color: theme.colors.onSurfaceVariant, marginTop: spacing.s }]}
        >
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
  /** 给了色调就渲染成状态 chip（质量状态那一行）。 */
  tone?: StatusTone;
}> = ({ label, value, tone }) => {
  const theme = useTheme<AppTheme>();
  if (isBlank(value)) {
    return null;
  }
  return (
    <View style={[styles.infoItem, { paddingVertical: theme.custom.spacing.xs }]}>
      <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant }}>{label}</Text>
      {tone ? (
        <ToneChip tone={tone}>{value as string}</ToneChip>
      ) : (
        <Text variant="bodyMedium" style={[styles.infoValue, { color: theme.colors.onSurface }]}>
          {value}
        </Text>
      )}
    </View>
  );
};

/**
 * 列表项外框：标题行 + 若干明细行，项与项之间一条 outlineVariant 细线。
 * 加工 / 原料 / 质检 / 出货 四种项共用，⛔ 别各写一套。
 */
const ItemFrame: React.FC<{
  title: string;
  /** 标题右侧：一段说明文字，或一个状态 chip。 */
  trailing?: React.ReactNode;
  isLast: boolean;
  children: React.ReactNode;
}> = ({ title, trailing, isLast, children }) => {
  const theme = useTheme<AppTheme>();
  return (
    <View
      style={[
        { paddingVertical: theme.custom.spacing.m },
        !isLast && { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: theme.colors.outlineVariant },
      ]}
    >
      <View style={[styles.itemHeader, { marginBottom: theme.custom.spacing.s }]}>
        <Text variant="titleSmall" style={{ color: theme.colors.onSurface }}>{title}</Text>
        {typeof trailing === 'string' ? (
          <Text variant="labelMedium" style={{ color: theme.colors.onSurfaceVariant }}>{trailing}</Text>
        ) : (
          trailing
        )}
      </View>
      <View style={{ gap: theme.custom.spacing.xs }}>{children}</View>
    </View>
  );
};

/** 明细行：bodyMedium（14sp）onSurfaceVariant。 */
const Detail: React.FC<{ children: React.ReactNode }> = ({ children }) => {
  const theme = useTheme<AppTheme>();
  return (
    <Text variant="bodyMedium" style={{ color: theme.colors.onSurfaceVariant }}>
      {children}
    </Text>
  );
};

// 加工环节卡片组件
const ProcessingStageCard: React.FC<{ stage: ProcessingStageInfo; isLast: boolean }> = ({ stage, isLast }) => (
  <ItemFrame title={stage.stageName || stage.stageType} trailing={`第${stage.stageOrder}道工序`} isLast={isLast}>
    {stage.operatorName && <Detail>操作员: {stage.operatorName}</Detail>}
    {stage.equipmentName && <Detail>设备: {stage.equipmentName}</Detail>}
    {stage.durationMinutes != null && <Detail>耗时: {stage.durationMinutes}分钟</Detail>}
    {stage.inputWeight != null && stage.outputWeight != null && (
      <Detail>
        投入/产出: {stage.inputWeight}kg → {stage.outputWeight}kg
      </Detail>
    )}
    {stage.lossRate != null && <Detail>损耗率: {(stage.lossRate * 100).toFixed(1)}%</Detail>}
    {stage.passRate != null && <Detail>合格率: {(stage.passRate * 100).toFixed(1)}%</Detail>}
    {stage.temperature != null && <Detail>温度: {stage.temperature}°C</Detail>}
    {stage.startTime && (
      <Detail>
        时间: {new Date(stage.startTime).toLocaleString()}
        {stage.endTime ? ` ~ ${new Date(stage.endTime).toLocaleTimeString()}` : ''}
      </Detail>
    )}
  </ItemFrame>
);

// 原材料卡片组件（一跳）
const MaterialCard: React.FC<{ material: MaterialInfo; isLast: boolean }> = ({ material, isLast }) => {
  // 后端兜出来的「未知供应商」是占位不是数据（见 BACKEND_UNKNOWN_SUPPLIER_PLACEHOLDER）
  const supplierName =
    isBlank(material.supplierName) || material.supplierName === BACKEND_UNKNOWN_SUPPLIER_PLACEHOLDER
      ? null
      : material.supplierName;
  return (
    <ItemFrame title={material.materialName} trailing={material.batchNumber} isLast={isLast}>
      {supplierName && <Detail>供应商: {supplierName}</Detail>}
      {!isBlank(material.receiptDate) && <Detail>入库日期: {material.receiptDate}</Detail>}
      {material.expireDate && <Detail>过期日期: {material.expireDate}</Detail>}
      {material.quantity != null && (
        <Detail>
          消耗量: {material.quantity} {material.unit}
        </Detail>
      )}
      {material.storageLocation && <Detail>存储位置: {material.storageLocation}</Detail>}
    </ItemFrame>
  );
};

// 质检卡片组件
const QualityCard: React.FC<{ inspection: QualityInfo; isLast: boolean }> = ({ inspection, isLast }) => (
  <ItemFrame
    title={`质检 #${inspection.inspectionId?.slice(-8) || '-'}`}
    trailing={<ToneChip tone={qualityStatusTone(inspection.result)}>{inspection.result || '待定'}</ToneChip>}
    isLast={isLast}
  >
    <Detail>检验日期: {inspection.inspectionDate?.split('T')[0]}</Detail>
    <Detail>检验员: {inspection.inspectorName}</Detail>
    {inspection.passRate !== undefined && <Detail>合格率: {inspection.passRate}%</Detail>}
    {inspection.remarks && <Detail>备注: {inspection.remarks}</Detail>}
  </ItemFrame>
);

// 出货卡片组件
const ShipmentCard: React.FC<{
  shipment: ShipmentInfo;
  getStatusText: (status: string) => string;
  isLast: boolean;
}> = ({ shipment, getStatusText, isLast }) => (
  <ItemFrame
    title={shipment.shipmentNumber}
    trailing={<ToneChip tone={shipmentStatusTone(shipment.status)}>{getStatusText(shipment.status)}</ToneChip>}
    isLast={isLast}
  >
    <Detail>出货日期: {shipment.shipmentDate}</Detail>
    <Detail>客户: {shipment.customerName}</Detail>
    {shipment.quantity && (
      <Detail>
        数量: {shipment.quantity} {shipment.unit}
      </Detail>
    )}
    {shipment.logisticsCompany && <Detail>物流公司: {shipment.logisticsCompany}</Detail>}
    {shipment.trackingNumber && <Detail>物流单号: {shipment.trackingNumber}</Detail>}
  </ItemFrame>
);

// ⚠️ 这里只放布局：颜色 / 字号 / 间距全部走 useTheme() 与 Paper 的 variant，
//    `gates/demoScreensUseThemeTokens` 守着本文件里不再出现色值与 fontSize<14。
const styles = StyleSheet.create({
  container: { flex: 1 },
  centerContainer: { flex: 1, justifyContent: 'center', alignItems: 'center' },
  errorText: { textAlign: 'center' },
  scrollView: { flex: 1 },
  traceCodeCard: {},
  traceCodeHeader: { flexDirection: 'row', alignItems: 'center' },
  traceCodeColumn: { flex: 1 },
  traceCodeRow: { flex: 1, flexDirection: 'row', alignItems: 'center' },
  traceCode: { flex: 1, textAlign: 'right', fontFamily: 'monospace' },
  card: {},
  infoItem: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
  infoValue: { fontWeight: '500' },
  emptyText: { textAlign: 'center' },
  itemHeader: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center' },
});

export default TraceabilityDetailScreen;
