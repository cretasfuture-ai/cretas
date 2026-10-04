/**
 * 发货详情页面
 * 对应原型: warehouse/outbound-detail.html
 */

import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import { Text, Surface, Button, Divider, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useRoute, RouteProp } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHOutboundStackParamList } from "../../../types/navigation";
import { shipmentApiClient, ShipmentRecord } from "../../../services/api/shipmentApiClient";
import { formatDateTime as fmtDateTime, formatNumberWithCommas } from "../../../utils/formatters";
import { useAuthStore } from "../../../store/authStore";
import { useCanViewPrice } from "../../../store/canViewPriceStore";

type NavigationProp = NativeStackNavigationProp<WHOutboundStackParamList>;
type RouteType = RouteProp<WHOutboundStackParamList, "WHOutboundDetail">;
type AuthUser = ReturnType<typeof useAuthStore.getState>["user"];

function factoryOf(user: AuthUser): string | null {
  return user?.factoryUser?.factoryId ?? user?.factoryId ?? null;
}

function ownerKeyOf(user: AuthUser, shipmentId: string, factoryId: string | null): string {
  return [
    user?.id ?? "",
    factoryId ?? "",
    user?.userType ?? "",
    user?.factoryUser?.role ?? user?.platformUser?.role ?? "",
    JSON.stringify(user?.factoryUser?.modulePermissions ?? {}),
    shipmentId,
  ].join("|");
}

function shipmentForOwner(shipment: ShipmentRecord, shipmentId: string, factoryId: string): ShipmentRecord {
  if (!shipment || shipment.id !== shipmentId || shipment.factoryId !== factoryId) {
    throw new Error("发货详情身份校验失败，请重新打开当前单据");
  }
  return shipment;
}

/**
 * 获取发货状态信息
 */
const getStatusInfo = (status?: string): {
  status: string;
  label: string;
  bgColor: string;
  textColor: string;
  showPackButton: boolean;
} => {
  switch (status?.toLowerCase()) {
    case 'shipped':
      return { status: 'shipped', label: '已发货', bgColor: '#e3f2fd', textColor: '#2196F3', showPackButton: false };
    case 'delivered':
      return { status: 'delivered', label: '已送达', bgColor: '#e8f5e9', textColor: '#4CAF50', showPackButton: false };
    case 'returned':
      return { status: 'returned', label: '已退回', bgColor: '#ffebee', textColor: '#f44336', showPackButton: false };
    case 'pending':
    default:
      return { status: 'waiting', label: '待打包', bgColor: '#fff3e0', textColor: '#f57c00', showPackButton: true };
  }
};

/**
 * 格式化日期时间
 */
const formatDateTime = (dateStr?: string): string => {
  if (!dateStr) return '-';
  return fmtDateTime(dateStr);
};

/**
 * 计算发货时间提示
 */
const getDispatchTimeHint = (shipmentDate?: string): string => {
  if (!shipmentDate) return '尽快发出';
  const date = new Date(shipmentDate);
  const now = new Date();
  const diffHours = Math.ceil((date.getTime() - now.getTime()) / (1000 * 60 * 60));

  if (diffHours < 0) return '已超时';
  if (diffHours < 2) return '2小时内发出';
  if (diffHours < 24) return `${diffHours}小时内发出`;
  return formatDateTime(shipmentDate) + ' 前发出';
};

interface OrderProduct {
  id: string;
  name: string;
  batchNumber: string;
  quantity: number;
  unit: string | null;
  location: string;
}

interface OutboundDetail {
  orderNumber: string;
  status: string;
  statusLabel: string;
  statusBgColor: string;
  statusTextColor: string;
  showPackButton: boolean;
  isUrgent: boolean;
  dispatchTime: string;
  customer: string;
  customerContact: string;
  address: string;
  totalQuantity: number;
  totalAmount: number | null;
  createdAt: string;
  remarks: string;
  products: OrderProduct[];
  logisticsInfo?: string;
}

export function WHOutboundDetailScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const user = useAuthStore((state) => state.user);
  const canViewPrice = useCanViewPrice();
  const shipmentId = route.params.shipmentId?.trim() ?? "";
  const factoryId = factoryOf(user);
  const ownerKey = ownerKeyOf(user, shipmentId, factoryId);

  const [loading, setLoading] = useState(true);
  const [detail, setDetail] = useState<{ ownerKey: string; data: OutboundDetail } | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const generationRef = useRef(0);
  const renderContextRef = useRef({ ownerKey, shipmentId, user });
  renderContextRef.current = { ownerKey, shipmentId, user };

  const isLiveOwner = useCallback(() =>
    useAuthStore.getState().user === user
      && renderContextRef.current.ownerKey === ownerKey
      && renderContextRef.current.shipmentId === shipmentId,
    [ownerKey, shipmentId, user],
  );

  // 加载数据
  const loadData = useCallback(async () => {
    const actor = user;
    const requestOwnerKey = ownerKey;
    if (!isLiveOwner()) return;
    const generation = ++generationRef.current;
    const isCurrent = () =>
      useAuthStore.getState().user === actor
      && isLiveOwner()
      && renderContextRef.current.ownerKey === requestOwnerKey
      && generationRef.current === generation;
    setLoading(true);
    setLoadError(null);
    setErrorOwnerKey(null);
    setDetail(null);
    try {
      if (!actor || !factoryId || !shipmentId) {
        throw new Error("无法确定当前工厂或发货单，请重新打开当前单据");
      }
      const shipment = shipmentForOwner(
        await shipmentApiClient.getShipmentById(shipmentId, factoryId),
        shipmentId,
        factoryId,
      );
      if (!isCurrent()) return;

      if (shipment) {
        const statusInfo = getStatusInfo(shipment.status);
        const qty = shipment.quantity ?? 0;

        // 判断是否紧急（发货时间在24小时内）
        const isUrgent = shipment.shipmentDate
          ? (new Date(shipment.shipmentDate).getTime() - Date.now()) < 24 * 60 * 60 * 1000
          : false;

        setDetail({
          ownerKey: requestOwnerKey,
          data: {
          orderNumber: shipment.shipmentNumber ?? shipment.orderNumber ?? `SH-${shipment.id}`,
          status: statusInfo.status,
          statusLabel: statusInfo.label,
          statusBgColor: statusInfo.bgColor,
          statusTextColor: statusInfo.textColor,
          showPackButton: statusInfo.showPackButton,
          isUrgent,
          dispatchTime: getDispatchTimeHint(shipment.shipmentDate),
          customer: shipment.customerId ?? '未知客户',
          customerContact: '-',  // API doesn't provide contact info
          address: shipment.deliveryAddress ?? '-',
          totalQuantity: qty,
          totalAmount: canViewPrice ? shipment.totalAmount ?? null : null,
          createdAt: formatDateTime(shipment.createdAt),
          remarks: shipment.notes ?? '无备注',
          products: [
            {
              id: '1',
              name: shipment.productName ?? '产品',
              batchNumber: '-',  // Not available in current API
              quantity: qty,
              unit: shipment.unit ?? null,
              location: '-',  // Not available in current API
            }
          ],
          logisticsInfo: shipment.logisticsCompany
            ? `${shipment.logisticsCompany}${shipment.trackingNumber ? ' | ' + shipment.trackingNumber : ''}`
            : undefined,
          },
        });
      }
    } catch (error: unknown) {
      if (!isCurrent()) return;
      setLoadError(error instanceof Error ? error.message : '加载发货详情失败，请点击重试');
      setErrorOwnerKey(requestOwnerKey);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [canViewPrice, factoryId, isLiveOwner, ownerKey, shipmentId, user]);

  useEffect(() => {
    void loadData();
  }, [loadData]);

  const visibleDetail = detail?.ownerKey === ownerKey ? detail.data : null;
  const visibleError = errorOwnerKey === ownerKey ? loadError : null;
  const goBackIfCurrent = useCallback(() => {
    if (isLiveOwner()) navigation.goBack();
  }, [isLiveOwner, navigation]);

  // 加载状态
  if (loading || (!visibleDetail && !visibleError)) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity testID="warehouse-detail-back" style={styles.backButton} onPress={goBackIfCurrent}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>发货详情</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (visibleError) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity testID="warehouse-detail-back" style={styles.backButton} onPress={goBackIfCurrent}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>发货详情</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="alert-circle-outline" size={64} color="#f57c00" />
          <Text style={styles.loadingText}>{visibleError}</Text>
          <Button mode="contained" onPress={() => void loadData()} style={styles.retryButton}>
            重试
          </Button>
        </View>
      </SafeAreaView>
    );
  }

  // 空状态
  if (!visibleDetail) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity testID="warehouse-detail-back" style={styles.backButton} onPress={goBackIfCurrent}>
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <Text style={styles.headerTitle}>发货详情</Text>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <MaterialCommunityIcons name="package-variant-closed" size={64} color="#ddd" />
          <Text style={styles.loadingText}>未找到发货记录</Text>
        </View>
      </SafeAreaView>
    );
  }

  const detailForRender = visibleDetail;

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={goBackIfCurrent}
        >
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <Text style={styles.headerTitle}>发货详情</Text>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 状态卡片 */}
        <Surface style={styles.statusCard} elevation={1}>
          <View style={styles.statusHeader}>
            <View style={styles.orderIdRow}>
              <Text style={styles.orderNumber}>{detailForRender.orderNumber}</Text>
              {detailForRender.isUrgent && (
                <View style={styles.urgentBadge}>
                  <Text style={styles.urgentText}>紧急</Text>
                </View>
              )}
            </View>
            <View style={[styles.statusBadge, { backgroundColor: detailForRender.statusBgColor }]}>
              <Text style={[styles.statusText, { color: detailForRender.statusTextColor }]}>{detailForRender.statusLabel}</Text>
            </View>
          </View>
          <View style={styles.dispatchInfo}>
            <MaterialCommunityIcons
              name="clock-outline"
              size={16}
              color="#f44336"
            />
            <Text style={styles.dispatchText}>{detailForRender.dispatchTime}</Text>
          </View>
          {detailForRender.logisticsInfo && (
            <View style={styles.logisticsInfo}>
              <MaterialCommunityIcons name="truck-delivery" size={16} color="#4CAF50" />
              <Text style={styles.logisticsText}>{detailForRender.logisticsInfo}</Text>
            </View>
          )}
        </Surface>

        {/* 客户信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>客户信息</Text>
          <View style={styles.infoGrid}>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>客户名称</Text>
              <Text style={styles.infoValue}>{detailForRender.customer}</Text>
            </View>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>联系人</Text>
              <Text style={styles.infoValue}>{detailForRender.customerContact}</Text>
            </View>
            <View style={[styles.infoItem, { width: "100%" }]}>
              <Text style={styles.infoLabel}>收货地址</Text>
              <Text style={styles.infoValue}>{detailForRender.address}</Text>
            </View>
          </View>
        </View>

        {/* 产品明细 */}
        <View style={styles.section}>
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionTitle}>产品明细</Text>
            <Text style={styles.totalText}>
              共 {detailForRender.totalQuantity}{detailForRender.products[0]?.unit ? ` ${detailForRender.products[0].unit}` : ''}
            </Text>
          </View>

          {detailForRender.products.map((product, index) => (
            <View key={product.id}>
              {index > 0 && <Divider style={styles.productDivider} />}
              <View style={styles.productItem}>
                <View style={styles.productMain}>
                  <Text style={styles.productName}>{product.name}</Text>
                  <Text style={styles.productQty}>{product.quantity}{product.unit ? ` ${product.unit}` : ''}</Text>
                </View>
                <View style={styles.productMeta}>
                  <Text style={styles.productBatch}>
                    批次: {product.batchNumber}
                  </Text>
                  <Text style={styles.productLocation}>
                    库位: {product.location}
                  </Text>
                </View>
              </View>
            </View>
          ))}
        </View>

        {/* 订单信息 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>订单信息</Text>
          <View style={styles.infoGrid}>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>订单金额</Text>
              <Text style={[styles.infoValue, styles.amountValue]}>
                {canViewPrice && detailForRender.totalAmount !== null
                  ? `¥${formatNumberWithCommas(detailForRender.totalAmount)}`
                  : canViewPrice ? '金额未提供' : '无权限查看价格'}
              </Text>
            </View>
            <View style={styles.infoItem}>
              <Text style={styles.infoLabel}>下单时间</Text>
              <Text style={styles.infoValue}>{detailForRender.createdAt}</Text>
            </View>
          </View>
        </View>

        {/* 备注 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>备注</Text>
        <Text style={styles.remarksText}>{detailForRender.remarks}</Text>
        </View>

        <View style={{ height: 100 }} />
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
  retryButton: {
    marginTop: 16,
  },
  header: {
    backgroundColor: "#4CAF50",
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backButton: {
    padding: 4,
  },
  headerTitle: {
    flex: 1,
    fontSize: 18,
    fontWeight: "600",
    color: "#fff",
    textAlign: "center",
    marginRight: 28,
  },
  headerRight: {
    width: 28,
  },
  content: {
    flex: 1,
  },
  statusCard: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  statusHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
  },
  orderIdRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  orderNumber: {
    fontSize: 16,
    fontWeight: "600",
    color: "#333",
  },
  urgentBadge: {
    backgroundColor: "#ffebee",
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  urgentText: {
    fontSize: 11,
    color: "#f44336",
    fontWeight: "600",
  },
  statusBadge: {
    backgroundColor: "#fff3e0",
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    color: "#f57c00",
    fontWeight: "500",
  },
  dispatchInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    marginTop: 12,
    paddingTop: 12,
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
  },
  dispatchText: {
    fontSize: 14,
    color: "#f44336",
    fontWeight: "600",
  },
  logisticsInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    marginTop: 8,
  },
  logisticsText: {
    fontSize: 13,
    color: "#4CAF50",
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  totalText: {
    fontSize: 14,
    color: "#4CAF50",
    fontWeight: "600",
  },
  infoGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
  },
  infoItem: {
    width: "50%",
    marginBottom: 12,
  },
  infoLabel: {
    fontSize: 12,
    color: "#999",
    marginBottom: 4,
  },
  infoValue: {
    fontSize: 14,
    color: "#333",
  },
  amountValue: {
    color: "#f44336",
    fontWeight: "600",
    fontSize: 16,
  },
  productItem: {
    paddingVertical: 12,
  },
  productMain: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 4,
  },
  productName: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  productQty: {
    fontSize: 15,
    fontWeight: "600",
    color: "#4CAF50",
  },
  productMeta: {
    flexDirection: "row",
    gap: 16,
  },
  productBatch: {
    fontSize: 12,
    color: "#999",
  },
  productLocation: {
    fontSize: 12,
    color: "#999",
  },
  productDivider: {
    marginVertical: 4,
  },
  remarksText: {
    fontSize: 14,
    color: "#666",
    lineHeight: 20,
  },
  bottomActions: {
    position: "absolute",
    bottom: 0,
    left: 0,
    right: 0,
    backgroundColor: "#fff",
    flexDirection: "row",
    padding: 16,
    paddingBottom: 34,
    gap: 12,
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
  },
  cancelButton: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#ddd",
  },
  cancelButtonLabel: {
    color: "#666",
  },
  actionButton: {
    flex: 1,
    borderRadius: 8,
    backgroundColor: "#4CAF50",
  },
  actionButtonLabel: {
    color: "#fff",
    fontWeight: "600",
  },
});

export default WHOutboundDetailScreen;
