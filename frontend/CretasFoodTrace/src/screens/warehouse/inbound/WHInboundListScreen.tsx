/**
 * 入库管理列表页面
 * 对应原型: warehouse/inbound.html
 *
 * API集成:
 * - materialBatchApiClient - 获取原材料批次列表
 */

import React, { useState, useCallback, useEffect } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  ActivityIndicator,
} from "react-native";
import {
  Text,
  Surface,
  Chip,
  Button,
  Searchbar,
  TouchableRipple,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { toBatchWorkStatus } from '../../../utils/materialBatchStatus';
import { WHInboundStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { handleError } from "../../../utils/errorHandler";
import { logger } from "../../../utils/logger";
import { formatShortDateTime } from "../../../utils/formatters";

type NavigationProp = NativeStackNavigationProp<WHInboundStackParamList>;

// 入库状态类型
type InboundStatus = "pending" | "inspecting" | "putaway" | "completed";

interface InboundItem {
  id: string;
  batchNumber: string;
  material: string;
  materialType: string;
  supplier: string;
  quantity: number;
  /** 批次真实计量单位; 后端可能不返回 → '未标注'。别再假设是 kg。 */
  unit: string;
  status: InboundStatus;
  createdAt: string;
  location?: string;
  inspectResult?: string;
}

const getStatusConfig = (t: (key: string) => string): Record<
  InboundStatus,
  { label: string; color: string; bgColor: string }
> => ({
  pending: { label: t('inbound.list.status.pending'), color: "#f57c00", bgColor: "#fff3e0" },
  inspecting: { label: t('inbound.list.status.inspecting'), color: "#1976d2", bgColor: "#e3f2fd" },
  putaway: { label: t('inbound.list.status.putaway'), color: "#7b1fa2", bgColor: "#f3e5f5" },
  completed: { label: t('inbound.list.status.completed'), color: "#388e3c", bgColor: "#e8f5e9" },
});

// 将后端状态映射到前端状态
// 2026-08-20: 收敛到 utils/materialBatchStatus。
// ⚠️ 原来这一份把 fresh/frozen 判成 'pending'（鲜品/冻品其实已经在库），
//    首页那一份则根本没有这两个 case —— 两份同源映射已经漂开了，这正是抽出来的原因。
const mapBatchStatusToInbound = (backendStatus: string | undefined): InboundStatus =>
  toBatchWorkStatus(backendStatus) as InboundStatus;

export function WHInboundListScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [searchQuery, setSearchQuery] = useState("");
  // 2026-08-21: 默认从「全部」改成「待入库」——收敛后这是仓管的落地页,
  // 他打开 App 就该看见"还有哪几单要收", ⛔ 不是先看一堆已收完的再自己去筛。
  // ⛔ 不删筛选 Chip: 质检中/已入库偶尔要回查。改的是默认值, 不是可选项。
  const [selectedStatus, setSelectedStatus] = useState<string>("pending");
  const [inboundList, setInboundList] = useState<InboundItem[]>([]);

  // 加载数据
  const loadData = useCallback(async () => {
    try {
      logger.info('[WHInboundListScreen] 开始加载入库列表');

      const response = await materialBatchApiClient.getMaterialBatches({ page: 1, size: 50 }) as
        { data?: { content?: MaterialBatch[] }; content?: MaterialBatch[] } | undefined;

      // 处理响应数据
      const batchData = response?.data?.content ?? response?.content ?? [];
      const batches = Array.isArray(batchData) ? batchData : [];

      const mappedList: InboundItem[] = batches.map((b: MaterialBatch) => ({
        id: b.id,
        batchNumber: b.batchNumber || b.id,
        material: b.materialName || '原材料',
        materialType: b.storageType?.toLowerCase() === 'frozen' ? '冻品' : b.storageType?.toLowerCase() === 'fresh' ? '鲜品' : '干货',
        supplier: b.supplierName || b.supplierId || '未知供应商',
        quantity: b.inboundQuantity || 0,
        // 口径修正 (2026-08-02): 卡片原本一律写死 " kg"。实测 F006 这一页 50 条里
        // 12 条不是 kg (box/slice/roll/case/无单位), 带上真实单位。
        unit: b.unit || '未标注',
        status: mapBatchStatusToInbound(b.status),
        createdAt: b.inboundDate
          ? formatShortDateTime(b.inboundDate)
          : '未知时间',
        location: b.storageLocation,
        inspectResult: b.qualityGrade ? `质量等级: ${b.qualityGrade}` : undefined,
      }));

      setInboundList(mappedList);
      logger.info(`[WHInboundListScreen] 加载入库列表成功: ${mappedList.length}条`);

    } catch (error) {
      logger.error('[WHInboundListScreen] 加载入库列表失败:', error);
      handleError(error, { title: t('messages.loadListFailed') });
      setInboundList([]);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }, []);

  useEffect(() => {
    loadData();
  }, [loadData]);

  const onRefresh = useCallback(() => {
    setRefreshing(true);
    loadData();
  }, [loadData]);

  // 筛选数据
  const filteredList = inboundList.filter((item) => {
    if (selectedStatus !== "all" && item.status !== selectedStatus) return false;
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return (
        item.batchNumber.toLowerCase().includes(query) ||
        item.material.toLowerCase().includes(query) ||
        item.supplier.toLowerCase().includes(query)
      );
    }
    return true;
  });

  // 统计数据 (仅供筛选 Chip 计数用 —— 2026-08-21 收敛后不再单独渲染一屏统计,
  // owner: 仓管不查库存/不看统计, 那块信息属于办公室在 web 上做的事)
  const stats = {
    total: inboundList.length,
    pending: inboundList.filter((i) => i.status === "pending").length,
    inspecting: inboundList.filter((i) => i.status === "inspecting").length,
    completed: inboundList.filter((i) => i.status === "completed").length,
  };

  const getActionText = (status: InboundStatus): string => {
    switch (status) {
      case "pending":
        return t('inbound.list.actions.confirmInbound');
      case "inspecting":
        return t('inbound.list.actions.continueInspect');
      case "putaway":
        return t('inbound.list.actions.confirmPutaway');
      case "completed":
        return t('inbound.list.actions.viewDetail');
      default:
        return t('inbound.list.actions.viewDetail');
    }
  };

  const statusConfig = getStatusConfig(t);

  const handleItemPress = (item: InboundItem) => {
    switch (item.status) {
      case "pending":
        navigation.navigate("WHInboundDetail", { batchId: item.id });
        break;
      case "inspecting":
        navigation.navigate("WHInspect", { batchId: item.id });
        break;
      case "putaway":
        navigation.navigate("WHPutaway", { batchId: item.id });
        break;
      case "completed":
        navigation.navigate("WHInboundDetail", { batchId: item.id });
        break;
    }
  };

  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <Text style={styles.headerTitle}>{t('inbound.list.title')}</Text>
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>{t('inbound.list.loading')}</Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>{t('inbound.list.title')}</Text>
        <Text style={styles.headerSubtitle}>
          {t('inbound.list.headerSubtitle', { pending: stats.pending })}
        </Text>
      </View>

      <ScrollView
        style={styles.content}
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 快捷操作 —— 2026-08-21 收敛: 三个按钮原来同权重并排, 仓管分不清先点哪个。
            改成 1 主 + 2 副: 「待收货任务」是他 90% 时候要点的那个, 单独一整行、
            填色更重; 扫码 / 餐饮待验收降级成小一号的次要入口, 并排在下面一行。
            ⛔ 不删任何入口, 只改视觉权重 (内联 UX 规则「主操作按钮唯一」)。

            🔴 2026-08-17 删除「新建入库」按钮 —— 它是一块过期的标签牌。
            原本跳 WHInboundCreate, 那个屏调已停用的 POST /material-batches(409), 屏已删;
            删屏时把 onPress 改成了 WHPurchaseReceiveList, 但**按钮上的字没改**。
            于是它和下面那个「待收货任务」跳的是同一行代码, 而仓管点进去看到的是一个空列表
            (F006 实测: 采购单只有 2 张 COMPLETED, 客户来料通知 1 条已 RECEIVED),
            读起来就是「新建入库点不动」。

            ⛔ 不要在这里再放一个「新建入库」—— 目前 App 侧**没有**无预告直接建批次的路径:
            正规无单入库 WHUnorderedInboundReceive 必须带 noticeId, 唯一入口就是
            WHPurchaseReceiveList 里的预告项。要不要开这个入口是产品决策, 见
            HANDOFF-2026-08-17b 第七节第 3 问。
            少一个按钮, 好过一个说谎的按钮。 */}
        <View style={styles.actionBar}>
          {/* SP6 Tier1 #25: 无扫码直接选单收货 —— 唯一主操作 */}
          <Button
            mode="contained"
            icon="clipboard-check-outline"
            onPress={() => navigation.navigate("WHPurchaseReceiveList")}
            style={[styles.primaryActionButton, { backgroundColor: '#1565c0' }]}
            labelStyle={styles.actionButtonLabel}
            contentStyle={styles.primaryActionButtonContent}
          >
            待收货任务
          </Button>
        </View>
        <View style={styles.productionReceiptActionBar}>
          <Button
            testID="wh-production-receipt-entry"
            mode="outlined"
            icon="package-down"
            onPress={() => navigation.navigate("WHTransitLedger")}
            style={styles.productionReceiptActionButton}
            contentStyle={styles.productionReceiptActionContent}
          >
            生产成品入库
          </Button>
        </View>
        <View style={styles.secondaryActionBar}>
          <Button
            mode="outlined"
            compact
            icon="qrcode-scan"
            onPress={() => navigation.navigate("WHScanOperation", { type: "inbound" })}
            style={styles.secondaryActionButton}
            labelStyle={styles.secondaryActionButtonLabel}
          >
            {t('inbound.list.scanInbound')}
          </Button>
          <Button
            mode="outlined"
            compact
            icon="truck-delivery"
            onPress={() => navigation.navigate("SupplierDeliveryList")}
            style={styles.secondaryActionButton}
            labelStyle={styles.secondaryActionButtonLabel}
          >
            餐饮待验收
          </Button>
        </View>

        {/* 搜索栏 */}
        <Searchbar
          placeholder={t('inbound.list.search')}
          value={searchQuery}
          onChangeText={setSearchQuery}
          style={styles.searchBar}
          inputStyle={styles.searchInput}
        />

        {/* 筛选标签 */}
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          style={styles.filterContainer}
        >
          <Chip
            selected={selectedStatus === "all"}
            onPress={() => setSelectedStatus("all")}
            style={[
              styles.filterChip,
              selectedStatus === "all" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "all" ? styles.filterChipTextActive : undefined}
          >
            {t('inbound.list.filter.all')}({stats.total})
          </Chip>
          <Chip
            selected={selectedStatus === "pending"}
            onPress={() => setSelectedStatus("pending")}
            style={[
              styles.filterChip,
              selectedStatus === "pending" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "pending" ? styles.filterChipTextActive : undefined}
          >
            {t('inbound.list.filter.pending')}({stats.pending})
          </Chip>
          <Chip
            selected={selectedStatus === "inspecting"}
            onPress={() => setSelectedStatus("inspecting")}
            style={[
              styles.filterChip,
              selectedStatus === "inspecting" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "inspecting" ? styles.filterChipTextActive : undefined}
          >
            {t('inbound.list.filter.inspecting')}({stats.inspecting})
          </Chip>
          <Chip
            selected={selectedStatus === "completed"}
            onPress={() => setSelectedStatus("completed")}
            style={[
              styles.filterChip,
              selectedStatus === "completed" && styles.filterChipActive,
            ]}
            textStyle={selectedStatus === "completed" ? styles.filterChipTextActive : undefined}
          >
            {t('inbound.list.filter.completed')}({stats.completed})
          </Chip>
        </ScrollView>

        {/* 入库列表 */}
        <View style={styles.listContainer}>
          {filteredList.map((item) => {
            const config = statusConfig[item.status];
            return (
              <TouchableRipple
                key={item.id}
                onPress={() => handleItemPress(item)}
                rippleColor="rgba(76, 175, 80, 0.15)"
              >
                <Surface style={styles.inboundCard} elevation={1}>
                  <View style={styles.cardHeader}>
                    <Text style={styles.batchNumber}>{item.batchNumber}</Text>
                    <View
                      style={[
                        styles.statusBadge,
                        { backgroundColor: config.bgColor },
                      ]}
                    >
                      <Text style={[styles.statusText, { color: config.color }]}>
                        {config.label}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.cardContent}>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('inbound.list.material')}</Text>
                      <Text style={styles.infoValue}>
                        {item.material} ({item.materialType})
                      </Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('inbound.list.supplier')}</Text>
                      <Text style={styles.infoValue}>{item.supplier}</Text>
                    </View>
                    <View style={styles.infoRow}>
                      <Text style={styles.infoLabel}>{t('inbound.list.quantity')}</Text>
                      <Text style={[styles.infoValue, styles.quantityValue]}>
                        {item.quantity} {item.unit}
                      </Text>
                    </View>
                  </View>

                  <View style={styles.cardFooter}>
                    <Text style={styles.timeText}>
                      {item.createdAt}
                      {item.location && ` | ${item.location}`}
                      {item.inspectResult && ` | ${item.inspectResult}`}
                    </Text>
                    <View style={styles.actionLink}>
                      <Text style={styles.actionText}>
                        {getActionText(item.status)}
                      </Text>
                      <MaterialCommunityIcons
                        name="chevron-right"
                        size={16}
                        color="#4CAF50"
                      />
                    </View>
                  </View>
                </Surface>
              </TouchableRipple>
            );
          })}
        </View>

        {/* 2026-08-21 收敛: 底部「已加载批次统计」4 格聚合数已删除 ——
            owner: 仓管不查库存/不看统计, 这块信息不帮他做「收哪一单」这个决定,
            只是每次都要先滑过去的额外重量。⛔ 不是白工: 与首页统计(F3)同一类问题,
            只是首页那份因为首页 tab 隐藏已经不可达, 而这份在本屏依旧可达, 需要单独砍。
            要查这些数字, 走 web-admin。 */}
        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
  },
  loadingContainer: {
    flex: 1,
    justifyContent: "center",
    alignItems: "center",
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: "#666",
  },
  header: {
    backgroundColor: "#4CAF50",
    paddingHorizontal: 16,
    paddingVertical: 16,
  },
  headerTitle: {
    fontSize: 20,
    fontWeight: "bold",
    color: "#fff",
  },
  headerSubtitle: {
    fontSize: 13,
    color: "rgba(255,255,255,0.9)",
    marginTop: 4,
  },
  content: {
    flex: 1,
  },
  actionBar: {
    paddingHorizontal: 16,
    paddingTop: 16,
  },
  primaryActionButton: {
    borderRadius: 8,
  },
  primaryActionButtonContent: {
    height: 48,
  },
  productionReceiptActionBar: {
    paddingHorizontal: 16,
    paddingTop: 10,
  },
  productionReceiptActionButton: {
    borderRadius: 8,
  },
  productionReceiptActionContent: {
    minHeight: 48,
  },
  actionButtonLabel: {
    color: "#fff",
    fontWeight: "600",
    fontSize: 15,
  },
  secondaryActionBar: {
    flexDirection: "row",
    paddingHorizontal: 16,
    paddingTop: 10,
    gap: 10,
  },
  secondaryActionButton: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#c8e6c9",
  },
  secondaryActionButtonLabel: {
    color: "#4CAF50",
    fontWeight: "500",
    fontSize: 12,
  },
  searchBar: {
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 8,
    backgroundColor: "#fff",
  },
  searchInput: {
    fontSize: 14,
  },
  filterContainer: {
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  filterChip: {
    marginRight: 8,
    backgroundColor: "#fff",
  },
  filterChipActive: {
    backgroundColor: "#4CAF50",
  },
  filterChipTextActive: {
    color: "#fff",
  },
  listContainer: {
    paddingHorizontal: 16,
  },
  inboundCard: {
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
    marginBottom: 12,
  },
  cardHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  batchNumber: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  statusBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: "500",
  },
  cardContent: {
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
    paddingTop: 12,
  },
  infoRow: {
    flexDirection: "row",
    marginBottom: 8,
  },
  infoLabel: {
    width: 60,
    fontSize: 13,
    color: "#999",
  },
  infoValue: {
    flex: 1,
    fontSize: 13,
    color: "#333",
  },
  quantityValue: {
    // 数量是仓管确认这一单对不对的关键数字 —— 放大字号防看错 (对应 fool-proof 数字大字号约束)。
    fontSize: 16,
    fontWeight: "700",
    color: "#4CAF50",
  },
  cardFooter: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
  },
  timeText: {
    fontSize: 12,
    color: "#999",
  },
  actionLink: {
    flexDirection: "row",
    alignItems: "center",
  },
  actionText: {
    fontSize: 13,
    color: "#4CAF50",
    fontWeight: "500",
  },
});

export default WHInboundListScreen;
