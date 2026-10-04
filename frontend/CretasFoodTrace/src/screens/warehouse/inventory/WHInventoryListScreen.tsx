/**
 * 库存管理列表页面
 * 对应原型: warehouse/inventory.html
 *
 * API集成:
 * - materialBatchApiClient - 获取库存统计和批次列表
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from "react";
import { appAlert } from '../../../components/ui/AppDialog';
import {
  View,
  ScrollView,
  StyleSheet,
  RefreshControl,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import {
  Text,
  Surface,
  Chip,
  Searchbar,
  Button,
  useTheme,
} from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation, useFocusEffect, CommonActions } from "@react-navigation/native";
import { StickyFooterSummary } from "../../../components/list";
import { useListSummary } from "../../../hooks/useListSummary";
import { formatSummaryForAI } from "../../../utils/aiSummaryContext";

type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from 'react-i18next';
import { useAuthStore } from '../../../store/authStore';
import { canViewPrice } from '../../../utils/priceViewAccess';
import { WHInventoryStackParamList } from "../../../types/navigation";
import type { User } from '../../../types/auth';
import { materialBatchApiClient } from "../../../services/api/materialBatchApiClient";
import {
  materialPackagingApiClient,
  type MaterialPackagingHierarchy,
} from "../../../services/api/materialPackagingApiClient";
import {
  convertByHierarchy,
  bucketByDimension,
  formatQuantity,
  canonicalUnit,
} from "../../../utils/packagingUnitConversion";
import { handleError } from "../../../utils/errorHandler";
import { logger } from "../../../utils/logger";
import { formatNumberWithCommas, formatDate } from "../../../utils/formatters";
import {  } from "react-native";
import { RowActionBottomSheet } from "../../../components/list";
import { useRowActions, type RowContext } from "../../../hooks/useRowActions";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type AncestorNavigation = {
  getParent?: () => AncestorNavigation | undefined;
  getState?: () => { routeNames?: readonly string[] };
};

// 物料类型
type MaterialType = "fresh" | "frozen" | "dry" | "unknown";

interface InventoryItem {
  id: string;
  name: string;
  batchNumber: string;
  type: MaterialType;
  /** 用来关联原料字典的规格层级 (material_packaging_hierarchy.material_type_id) */
  materialTypeId?: string;
  quantity: number;
  unit: string;
  batchCount: number;
  location: string;
  warning?: string;
  warningType?: "expire" | "low" | "normal";
  updatedAt: string;
}

const INVENTORY_PAGE_SIZE = 50;
const MAX_INVENTORY_PAGES = 1000;

type OwnerUser = {
  id?: number | string;
  username?: string;
  factoryId?: string;
  factoryUser?: {
    factoryId?: string;
    role?: string;
    permissions?: string[];
    modulePermissions?: Record<string, string>;
  };
} | null | undefined;

class InventoryPaginationError extends Error {}

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null;

const identityKeyForUser = (currentUser: OwnerUser): string => {
  const factoryId = currentUser?.factoryUser?.factoryId ?? currentUser?.factoryId ?? '';
  const actorId = currentUser?.id == null
    ? (currentUser?.username ?? '')
    : String(currentUser.id);
  const role = currentUser?.factoryUser?.role ?? '';
  return `${factoryId}:${actorId}:${role}`;
};

const ownerTagForUser = (currentUser: OwnerUser): string => {
  const permissions = [...(currentUser?.factoryUser?.permissions ?? [])].sort();
  const modulePermissions = Object.entries(currentUser?.factoryUser?.modulePermissions ?? {})
    .sort(([left], [right]) => left.localeCompare(right));
  return JSON.stringify({ identity: identityKeyForUser(currentUser), permissions, modulePermissions });
};

const factoryIdForUser = (currentUser: OwnerUser): string =>
  currentUser?.factoryUser?.factoryId ?? currentUser?.factoryId ?? '';

const hasAncestorRoute = (navigation: NavigationProp, routeName: string): boolean => {
  let current: AncestorNavigation | undefined = navigation as unknown as AncestorNavigation;
  for (let depth = 0; current && depth < 6; depth += 1) {
    if (current.getState?.().routeNames?.includes(routeName)) return true;
    current = current.getParent?.();
  }
  return false;
};

const isFiniteNumber = (value: unknown): value is number =>
  typeof value === 'number' && Number.isFinite(value);

interface InventoryBatchProjection {
  id: string;
  batchNumber: string;
  factoryId: string;
  materialTypeId?: string | null;
  materialName?: string | null;
  materialCategory?: string | null;
  storageType?: string | null;
  inboundQuantity: number;
  remainingQuantity: number;
  reservedQuantity?: number | null;
  usedQuantity?: number | null;
  unit?: string | null;
  quantityUnit?: string | null;
  storageLocation?: string | null;
  expiryDate?: string | null;
  updatedAt?: string | null;
  status: string;
}

const MATERIAL_BATCH_STATUSES = new Set([
  'IN_STOCK',
  'AVAILABLE',
  'FRESH',
  'FROZEN',
  'DEPLETED',
  'USED_UP',
  'EXPIRED',
  'INSPECTING',
  'SCRAPPED',
  'RESERVED',
  'PRODUCING_RESERVED',
  'DEFECTIVE',
]);

const isOptionalFiniteNumber = (value: unknown): boolean =>
  value == null || isFiniteNumber(value);

const isOptionalString = (value: unknown): boolean =>
  value == null || typeof value === 'string';

const isInventoryBatchProjection = (value: unknown): value is InventoryBatchProjection => {
  if (!isRecord(value)) return false;
  const status = typeof value.status === 'string' ? value.status.trim().toUpperCase() : '';
  return typeof value.id === 'string'
    && value.id.trim() !== ''
    && typeof value.batchNumber === 'string'
    && value.batchNumber.trim() !== ''
    && typeof value.factoryId === 'string'
    && value.factoryId.trim() !== ''
    && isFiniteNumber(value.inboundQuantity)
    && isFiniteNumber(value.remainingQuantity)
    && isOptionalFiniteNumber(value.reservedQuantity)
    && isOptionalFiniteNumber(value.usedQuantity)
    && isOptionalString(value.materialTypeId)
    && isOptionalString(value.materialName)
    && isOptionalString(value.materialCategory)
    && isOptionalString(value.storageType)
    && isOptionalString(value.unit)
    && isOptionalString(value.quantityUnit)
    && isOptionalString(value.storageLocation)
    && isOptionalString(value.expiryDate)
    && isOptionalString(value.updatedAt)
    && MATERIAL_BATCH_STATUSES.has(status);
};

const parseInventoryPage = (
  response: unknown,
  page: number,
  expectedTotalPages?: number,
): { batches: InventoryBatchProjection[]; totalPages: number } => {
  if (!isRecord(response) || response.success !== true || !isRecord(response.data)) {
    throw new InventoryPaginationError('库存列表响应无效');
  }
  const totalPages = response.data.totalPages;
  const currentPage = response.data.currentPage;
  if (typeof totalPages !== 'number' || !Number.isInteger(totalPages) || totalPages < 0
    || totalPages > MAX_INVENTORY_PAGES
    || (totalPages < page && !(totalPages === 0 && page === 1))
    || (totalPages === 0 && Array.isArray(response.data.content) && response.data.content.length > 0)
    || typeof currentPage !== 'number' || !Number.isInteger(currentPage) || currentPage !== page) {
    throw new InventoryPaginationError('库存分页信息无效');
  }
  if (expectedTotalPages != null && totalPages !== expectedTotalPages) {
    throw new InventoryPaginationError('库存分页信息前后不一致');
  }
  if (!Array.isArray(response.data.content)) {
    throw new InventoryPaginationError('库存批次数据无效');
  }
  const batches = response.data.content.map((value) => {
    if (!isInventoryBatchProjection(value)) {
      throw new InventoryPaginationError('库存批次身份信息无效');
    }
    return value;
  });
  return { batches, totalPages };
};

interface InventorySummaryFooterProps {
  factoryId: string;
  ownerActor: User;
  ownerTag: string;
  visible: boolean;
  loading: boolean;
  refreshing: boolean;
  aiAvailable: boolean;
  navigation: NavigationProp;
  isCurrentOwner: () => boolean;
}

function InventorySummaryFooter({
  factoryId,
  ownerActor,
  ownerTag,
  visible,
  loading,
  refreshing,
  aiAvailable,
  navigation,
  isCurrentOwner,
}: InventorySummaryFooterProps) {
  const summaryRequest = useMemo(() => ({ filterConditions: {} }), [ownerActor, ownerTag]);
  const {
    summary,
    loading: summaryLoading,
    error: summaryError,
    refresh: refreshSummary,
  } = useListSummary('inventory', summaryRequest, factoryId);
  useEffect(() => {
    if (refreshing && visible && isCurrentOwner()) void refreshSummary();
  }, [isCurrentOwner, refreshing, refreshSummary, visible]);

  const currentSummary = visible && !loading && !summaryError ? summary : null;
  const summaryUnavailable = visible && !loading && summaryError != null;
  const visibleSummary = useMemo(() => {
    if (!currentSummary) return null;
    const ownerCanViewPrice = canViewPrice(ownerActor);
    return {
      ...currentSummary,
      stats: currentSummary.stats.filter(
        (stat) => ownerCanViewPrice || (stat.canViewPrice !== true && stat.format !== 'currency'),
      ),
    };
  }, [currentSummary, ownerActor]);
  const handleAIAnalyze = useCallback(() => {
    if (!aiAvailable || !hasAncestorRoute(navigation, 'AIChat') || !visible || !visibleSummary || !isCurrentOwner()) return;
    navigation.dispatch(CommonActions.navigate('AIChat', {
      initialMessage: `分析当前库存 (低库存预警 / 总价值 / 周转率)${formatSummaryForAI(visibleSummary)}`,
    }));
  }, [aiAvailable, isCurrentOwner, navigation, visible, visibleSummary]);

  return (
    <StickyFooterSummary
      stats={(visibleSummary?.stats ?? []).filter(
        (st) => !(st.format === 'number' && !String(st.unit ?? '').trim())
      )}
      loading={loading || (visible && summaryLoading && visibleSummary == null && !summaryError)}
      empty={summaryUnavailable || (!loading && visibleSummary == null && !summaryLoading)}
      emptyText={summaryUnavailable ? '统计暂不可用，可下拉重试' : undefined}
      onAIAnalyze={aiAvailable && visibleSummary != null ? handleAIAnalyze : undefined}
    />
  );
}

interface QuickAction {
  key: string;
  label: string;
  icon: MCIconName;
  color: string;
  screen: keyof WHInventoryStackParamList;
}

// 将后端批次状态映射为仓储物料类型
const mapBatchToMaterialType = (batch: InventoryBatchProjection): MaterialType => {
  // storageType 是后端原料类型的受控字段；缺失或未知时必须保留未知，不能猜鲜品。
  const storageType = batch.storageType?.trim().toLowerCase();
  if (storageType === 'fresh') return 'fresh';
  if (storageType === 'frozen') return 'frozen';
  if (storageType === 'dry') return 'dry';
  return 'unknown';
};

// 计算预警类型
const getWarningType = (batch: InventoryBatchProjection): "expire" | "low" | "normal" => {
  const remaining = batch.remainingQuantity ?? 0;
  const total = batch.inboundQuantity ?? 0;

  // 检查是否即将过期 (7天内)
  if (batch.expiryDate) {
    const expDate = new Date(batch.expiryDate);
    const now = new Date();
    const daysUntilExpire = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
    if (daysUntilExpire <= 7) {
      return 'expire';
    }
  }

  // 检查库存是否过低 (低于30%)
  if (total > 0 && remaining / total < 0.3) {
    return 'low';
  }

  return 'normal';
};

// 获取预警文本
const getWarningText = (warningType: "expire" | "low" | "normal", batch: InventoryBatchProjection): string => {
  if (warningType === 'expire') {
    if (batch.expiryDate) {
      const expDate = new Date(batch.expiryDate);
      const now = new Date();
      const daysUntilExpire = Math.ceil((expDate.getTime() - now.getTime()) / (1000 * 60 * 60 * 24));
      return daysUntilExpire <= 0 ? '已过期' : `${daysUntilExpire}天后过期`;
    }
    return '即将过期';
  } else if (warningType === 'low') {
    return '库存不足';
  }
  return '正常';
};

export function WHInventoryListScreen() {
  const { t } = useTranslation('warehouse');
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const user = useAuthStore((state) => state.user);
  const priceVisible = canViewPrice(user);
  const [refreshing, setRefreshing] = useState(false);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState("");
  const [selectedType, setSelectedType] = useState<string>("all");
  const [inventoryList, setInventoryList] = useState<InventoryItem[]>([]);
  /**
   * 原料字典的规格层级, key = materialTypeId。
   * 这是「1箱=8盒=800克」的**唯一权威**——全局单位表里计数单位的换算因子全是假的 1:1,
   * 不能用它换算 (详见 utils/packagingUnitConversion.ts 顶部)。
   */
  const [hierarchyMap, setHierarchyMap] = useState<Record<string, MaterialPackagingHierarchy>>({});
  const [inventoryStats, setInventoryStats] = useState<{
    totalValue: number | null;
    totalBatches: number;
    availableBatches: number;
    expiringBatchesCount: number;
    inventoryByType?: Record<string, number>;
  } | null>(null);
  const [selectedItem, setSelectedItem] = useState<InventoryItem | null>(null);
  const [actionSheetVisible, setActionSheetVisible] = useState(false);
  const [sheetOwner, setSheetOwner] = useState<typeof user>(null);
  const [sheetOwnerTag, setSheetOwnerTag] = useState<string | null>(null);
  const [sheetOwnerEpoch, setSheetOwnerEpoch] = useState<number | null>(null);
  const requestGenerationRef = useRef(0);
  const authVersionRef = useRef(0);
  const [authEpoch, setAuthEpoch] = useState(0);
  useEffect(() => {
    let lastActor = useAuthStore.getState().user;
    let lastOwnerTag = ownerTagForUser(lastActor);
    const unsubscribe = useAuthStore.subscribe((state) => {
      const nextOwnerTag = ownerTagForUser(state.user);
      if (state.user !== lastActor || nextOwnerTag !== lastOwnerTag) {
        authVersionRef.current += 1;
        requestGenerationRef.current += 1;
        setAuthEpoch((epoch) => epoch + 1);
      }
      lastActor = state.user;
      lastOwnerTag = nextOwnerTag;
    });
    return unsubscribe;
  }, []);
  const identityKey = identityKeyForUser(user);
  const ownerTag = ownerTagForUser(user);
  const ownerVersionRef = useRef<{ actor: typeof user; tag: string; version: number }>({
    actor: user,
    tag: ownerTag,
    version: 0,
  });
  if (ownerVersionRef.current.actor !== user || ownerVersionRef.current.tag !== ownerTag) {
    ownerVersionRef.current = {
      actor: user,
      tag: ownerTag,
      version: ownerVersionRef.current.version + 1,
    };
    requestGenerationRef.current += 1;
  }
  const ownerVersion = ownerVersionRef.current.version;
  const renderAuthVersion = authVersionRef.current;
  const [inventoryOwner, setInventoryOwner] = useState<typeof user>(user);
  const [inventoryOwnerTag, setInventoryOwnerTag] = useState<string>(ownerTag);
  const [inventoryOwnerEpoch, setInventoryOwnerEpoch] = useState(authEpoch);
  const [statsOwner, setStatsOwner] = useState<typeof user>(user);
  const [statsOwnerTag, setStatsOwnerTag] = useState<string>(ownerTag);
  const [statsOwnerEpoch, setStatsOwnerEpoch] = useState(authEpoch);
  const [hierarchyOwner, setHierarchyOwner] = useState<typeof user>(user);
  const [hierarchyOwnerTag, setHierarchyOwnerTag] = useState<string>(ownerTag);
  const [hierarchyOwnerEpoch, setHierarchyOwnerEpoch] = useState(authEpoch);
  const ownerSnapshotIsCurrent = inventoryOwner === user
    && inventoryOwnerTag === ownerTag
    && inventoryOwnerEpoch === authEpoch;
  const visibleInventoryList = ownerSnapshotIsCurrent ? inventoryList : [];
  const visibleInventoryStats = statsOwner === user && statsOwnerTag === ownerTag && statsOwnerEpoch === authEpoch
    ? inventoryStats
    : null;
  const visibleHierarchyMap = hierarchyOwner === user && hierarchyOwnerTag === ownerTag && hierarchyOwnerEpoch === authEpoch
    ? hierarchyMap
    : {};
  const isCurrentOwnerFor = (
    capturedUser: typeof user,
    capturedOwnerTag: string,
    capturedAuthVersion: number,
  ): boolean => {
    const liveUser = useAuthStore.getState().user;
    return liveUser === capturedUser
      && ownerTagForUser(liveUser) === capturedOwnerTag
      && authVersionRef.current === capturedAuthVersion;
  };

  /** Map warningType ('normal'/'low'/'expire') to STATUS_ACTIONS_MAP key. */
  const deriveInventoryStatus = (item: InventoryItem): string => {
    if (item.warningType === 'expire') return 'EXPIRE';
    if (item.warningType === 'low') return 'LOW';
    return 'NORMAL';
  };

  const handlers = useMemo(() => ({
    'view-detail': (e: RowContext) => {
      if (!isCurrentOwnerFor(user, ownerTag, renderAuthVersion)
        || !visibleInventoryList.some((item) => item.id === e.id)) return;
      navigation.navigate('WHInventoryDetail', { inventoryId: e.id });
    },
    // 🔴 2026-08-17: 原文案是「跳转 WHInventoryTransfer (待接)」—— 把内部屏幕名直接弹给了仓管,
    // 而它指向的那个屏是原型(只改 storageLocation, 数量不参与)。改成人话 + 说清去哪做。
    transfer: () => {
      if (isCurrentOwnerFor(user, ownerTag, renderAuthVersion)) {
        appAlert('调拨', '手机端暂不支持调拨, 请在网页端「库存 - 调拨」办理。');
      }
    },
    'view-price-history': (e: RowContext) => {
      if (isCurrentOwnerFor(user, ownerTag, renderAuthVersion)
        && visibleInventoryList.some((item) => item.id === e.id)) {
        appAlert('价格历史', `物料 ${e.id}`);
      }
    },
  }), [navigation, ownerTag, renderAuthVersion, user, visibleInventoryList]);

  const sheetCtx: RowContext = selectedItem
    ? { status: deriveInventoryStatus(selectedItem), id: selectedItem.id }
    : { status: '', id: '' };
  const rowActions = useRowActions('inventory', sheetCtx, { handlers });

  const openSheet = (item: InventoryItem) => {
    if (!isCurrentOwnerFor(user, ownerTag, renderAuthVersion)
      || !visibleInventoryList.some((candidate) => candidate.id === item.id)) return;
    setSelectedItem(item);
    setSheetOwner(user);
    setSheetOwnerTag(ownerTag);
    setSheetOwnerEpoch(authEpoch);
    setActionSheetVisible(true);
  };

  // Define type config and quick actions inside component to access t()
  const typeConfig: Record<MaterialType, { label: string; color: string; bgColor: string }> = {
    fresh: { label: t('inventory.filter.fresh'), color: "#4CAF50", bgColor: "#e8f5e9" },
    frozen: { label: t('inventory.filter.frozen'), color: "#2196F3", bgColor: "#e3f2fd" },
    dry: { label: t('inventory.filter.dry'), color: "#FF9800", bgColor: "#fff3e0" },
    unknown: { label: '类型未提供', color: "#757575", bgColor: "#f5f5f5" },
  };

  const quickActions: QuickAction[] = [
    { key: "check", label: t('inventory.quickActions.check'), icon: "clipboard-check-outline", color: "#4CAF50", screen: "WHInventoryCheck" },
    // 🔴 2026-08-17 摘掉「调拨」入口 —— WHInventoryTransferScreen 是**原型屏**(文件头注释自称
    // 「对应原型: warehouse/inventory-transfer.html」), 它:
    //   · 调入库位下拉是**写死的三条示例**(A区-冷藏库-02/03、B区-冷冻库-01), 与真实库位
    //     (WH-RAW / WH-WKS / WH-FG …) 无关;
    //   · 22 处硬编码 kg, 把 片/卷/盒 的批次也印成 kg;
    //   · executeTransfer 只调 materialBatchApiClient.updateBatch({ storageLocation }) ——
    //     把批次库位改写成 "A-02" 这种不存在的码, **用户输入的调拨数量完全不参与**,
    //     也不产生任何调拨单, 根本不走后端的 TransferServiceImpl。
    // 用户点完会看到「调拨成功」, 而实际上什么都没调走。⛔ 少一个入口, 好过一个说谎的入口。
    //
    // 要接回来: RN 侧已经有 `transferApiClient.createTransfer()` 打真实端点
    // `POST /api/mobile/{factoryId}/transfers`(实测可用, 见 HANDOFF-2026-08-17b 第五节),
    // 缺的是真实仓库列表 + 用批次自己的单位 + 数量语义。那是一次独立改造, 不在本次范围。
    { key: "location", label: t('inventory.quickActions.location'), icon: "map-marker", color: "#9C27B0", screen: "WHLocationManage" },
    { key: "expire", label: t('inventory.quickActions.expire'), icon: "clock-alert-outline", color: "#FF5722", screen: "WHExpireHandle" },
    { key: "transit", label: "\u4e2d\u8f6c\u786e\u8ba4", icon: "truck-check-outline", color: "#00695C", screen: "WHTransitLedger" },
    { key: "warnings", label: "库存预警", icon: "bell-alert-outline", color: "#E91E63", screen: "WHInventoryWarnings" },
  ];

  // 加载库存数据。列表必须完整完成服务端分页后才替换当前结果。
  const loadData = useCallback(async () => {
    const liveUserAtEntry = useAuthStore.getState().user;
    if (liveUserAtEntry !== user
      || identityKeyForUser(liveUserAtEntry) !== identityKey
      || ownerTagForUser(liveUserAtEntry) !== ownerTag
      || authVersionRef.current !== renderAuthVersion) return;
    const generation = ++requestGenerationRef.current;
    const requestActor = user;
    const requestIdentity = identityKey;
    const requestOwnerTag = ownerTag;
    const requestFactoryId = factoryIdForUser(requestActor);
    const requestAuthVersion = renderAuthVersion;
    const requestAuthEpoch = authEpoch;
    const isCurrentRequest = () =>
      generation === requestGenerationRef.current
      && authVersionRef.current === requestAuthVersion
      && authEpoch === requestAuthEpoch
      && useAuthStore.getState().user === requestActor
      && identityKeyForUser(useAuthStore.getState().user) === requestIdentity
      && ownerTagForUser(useAuthStore.getState().user) === requestOwnerTag;

    setLoading(true);
    setLoadError(null);

    if (!requestActor || !requestFactoryId || !isCurrentRequest()) {
      setLoading(false);
      setRefreshing(false);
      setLoadError('当前账号缺少工厂身份，无法加载库存。请重新登录后重试。');
      return;
    }

    try {
      logger.info('WHInventoryListScreen', '开始加载库存数据...');

      const loadAllBatches = async (): Promise<InventoryBatchProjection[]> => {
        const allBatches = new Map<string, InventoryBatchProjection>();
        let page = 1;
        let totalPages: number | undefined;
        while (totalPages == null || page <= totalPages) {
          if (!isCurrentRequest()) throw new InventoryPaginationError('库存请求已过期');
          const response = await materialBatchApiClient.getMaterialBatches({
            factoryId: requestFactoryId,
            page,
            size: INVENTORY_PAGE_SIZE,
          });
          if (!isCurrentRequest()) throw new InventoryPaginationError('库存请求已过期');
          const parsed = parseInventoryPage(response, page, totalPages);
          totalPages = parsed.totalPages;
          for (const batch of parsed.batches) {
            if (requestFactoryId && batch.factoryId !== requestFactoryId) {
              throw new InventoryPaginationError('库存批次不属于当前工厂');
            }
            if (!allBatches.has(batch.id)) allBatches.set(batch.id, batch);
          }
          page += 1;
        }
        return [...allBatches.values()];
      };

      const [batchesResult, statsResult, packagingResult] = await Promise.allSettled([
        loadAllBatches(),
        materialBatchApiClient.getInventoryStatistics(requestFactoryId),
        materialPackagingApiClient.list(requestFactoryId),
      ]);

      if (!isCurrentRequest()) return;

      if (packagingResult.status === 'fulfilled') {
        const map: Record<string, MaterialPackagingHierarchy> = {};
        for (const h of packagingResult.value ?? []) {
          if (h?.materialTypeId) map[h.materialTypeId] = h;
        }
        setHierarchyMap(map);
        setHierarchyOwner(requestActor);
        setHierarchyOwnerTag(requestOwnerTag);
        setHierarchyOwnerEpoch(requestAuthEpoch);
        logger.info('WHInventoryListScreen', `规格层级 ${Object.keys(map).length} 条`);
      } else {
        logger.warn('WHInventoryListScreen', '规格层级获取失败, 换算将显示为未设规格');
        setHierarchyMap({});
        setHierarchyOwner(requestActor);
        setHierarchyOwnerTag(requestOwnerTag);
        setHierarchyOwnerEpoch(requestAuthEpoch);
      }

      if (batchesResult.status === 'rejected') {
        throw batchesResult.reason;
      }

      const items: InventoryItem[] = batchesResult.value.map((batch: InventoryBatchProjection) => {
        const warningType = getWarningType(batch);
        return {
          id: batch.id,
          name: batch.materialName ?? batch.materialCategory ?? '未知物料',
          batchNumber: batch.batchNumber,
          type: mapBatchToMaterialType(batch),
          materialTypeId: batch.materialTypeId ?? undefined,
          quantity: batch.remainingQuantity ?? batch.inboundQuantity ?? 0,
          unit: batch.quantityUnit || '未标注',
          batchCount: 1,
          location: batch.storageLocation ?? '未提供库位',
          warning: getWarningText(warningType, batch),
          warningType: warningType,
          updatedAt: batch.updatedAt ? formatDate(new Date(batch.updatedAt)) : '更新时间未知',
        };
      });

      setInventoryList(items);
      setInventoryOwner(requestActor);
      setInventoryOwnerTag(requestOwnerTag);
      setInventoryOwnerEpoch(requestAuthEpoch);

      if (statsResult.status === 'fulfilled') {
        const statsResponse = statsResult.value as { success?: boolean; data?: { totalValue?: number | null; totalBatches: number; availableBatches: number; expiringBatchesCount: number; inventoryByType?: Record<string, number> } };
        if (statsResponse.success && statsResponse.data) {
          setInventoryStats({ ...statsResponse.data, totalValue: statsResponse.data.totalValue ?? null });
        } else {
          setInventoryStats(null);
        }
      } else {
        setInventoryStats(null);
      }
      setStatsOwner(requestActor);
      setStatsOwnerTag(requestOwnerTag);
      setStatsOwnerEpoch(requestAuthEpoch);
    } catch (error) {
      if (!isCurrentRequest()) return;
      logger.error('WHInventoryListScreen', '加载库存数据失败', error);
      handleError(error, { title: '加载库存数据失败' });
      setLoadError('库存列表加载失败，未显示不完整数据。请点击“重试”重新加载。');
    } finally {
      if (isCurrentRequest()) {
        setLoading(false);
        setRefreshing(false);
      }
    }
  }, [authEpoch, authVersionRef, identityKey, ownerTag, renderAuthVersion, user]);

  // 重新进入页面时刷新数据（盘点/调拨/入库后返回能看到最新库存）
  useFocusEffect(
    useCallback(() => {
      loadData();
    }, [loadData])
  );

  const onRefresh = useCallback(() => {
    const liveUser = useAuthStore.getState().user;
    if (liveUser !== user
      || identityKeyForUser(liveUser) !== identityKey
      || ownerTagForUser(liveUser) !== ownerTag
      || authVersionRef.current !== renderAuthVersion) return;
    setRefreshing(true);
    loadData();
  }, [identityKey, loadData, ownerTag, renderAuthVersion, user]);

  const retryLoad = useCallback(() => {
    const liveUser = useAuthStore.getState().user;
    if (liveUser === user
      && identityKeyForUser(liveUser) === identityKey
      && ownerTagForUser(liveUser) === ownerTag
      && authVersionRef.current === renderAuthVersion) loadData();
  }, [identityKey, loadData, ownerTag, renderAuthVersion, user]);

  const summaryAiAvailable = ownerSnapshotIsCurrent
    && hasAncestorRoute(navigation, 'AIChat')
    && (user?.factoryUser?.role === 'warehouse_manager' || user?.factoryUser?.role === 'factory_super_admin');
  const summaryFactoryId = factoryIdForUser(user);
  const isCurrentOwner = useCallback(() => {
    const liveUser = useAuthStore.getState().user;
    return liveUser === user
      && ownerTagForUser(liveUser) === ownerTag
      && authVersionRef.current === renderAuthVersion;
  }, [ownerTag, renderAuthVersion, user]);
  // 筛选数据
  const filteredList = visibleInventoryList.filter((item) => {
    if (selectedType !== "all" && item.type !== selectedType) return false;
    if (searchQuery) {
      const query = searchQuery.toLowerCase();
      return item.name.toLowerCase().includes(query)
        || item.batchNumber.toLowerCase().includes(query);
    }
    return true;
  });

  // 统计数据
  //
  // 🔴 口径修正 (2026-08-02)。这里原先把三个**不同范围**的数混在一起显示, 而且都标错了:
  //   1. total 取的是 inventoryStats.totalBatches —— 那是**批次数**(prod F006 = 296),
  //      却被渲染成「在库 296 种」「物料种类 296」。同屏底部 sticky bar 写的
  //      「共 296 批」才是对的, 同一个数在一屏里一处叫「种」一处叫「批」。
  //   2. totalWeight 是 inventoryList 求和, 而 inventoryList 只有
  //      getMaterialBatches({page:1,size:50}) 的 **50 条**(totalElements=296),
  //      即"总量"只统计了 1/6 的数据。
  //   3. 那 50 条单位不一致(实测 kg 38 / box 3 / slice 2 / roll 1 / case 1 / 无 5),
  //      却一律当 kg 相加并标 kg —— 与 box 被当 kg 算导致投料偏大 25% 是同一类错误。
  //
  // 修法: 分清「全量(服务端)」与「已加载(本页)」两个范围, 各自只说自己能证明的事;
  // 数量按单位分组, 绝不跨单位相加。
  const loadedCount = visibleInventoryList.length;
  const totalBatches = visibleInventoryStats?.totalBatches ?? loadedCount;

  /** 已加载批次按单位分组求和 —— 只在同一单位内相加。 */
  const loadedQuantityByUnit = visibleInventoryList.reduce<Record<string, number>>((acc, i) => {
    acc[i.unit] = (acc[i.unit] ?? 0) + i.quantity;
    return acc;
  }, {});
  const loadedUnitKinds = Object.keys(loadedQuantityByUnit);
  const loadedKgQuantity = loadedQuantityByUnit['kg'] ?? 0;
  const nonKgBatchCount = visibleInventoryList.filter((i) => i.unit !== 'kg').length;

  const stats = {
    // 批次总数 (全量, 服务端)
    totalBatches,
    // 本页已加载条数 —— 类型分项都只覆盖这些, 所以「全部」也必须用它, 否则
    // 全部(296) 与 鲜品+冻品+干货(50) 当场对不上。
    loaded: loadedCount,
    fresh: visibleInventoryList.filter((i) => i.type === "fresh").length,
    frozen: visibleInventoryList.filter((i) => i.type === "frozen").length,
    dry: visibleInventoryList.filter((i) => i.type === "dry").length,
    unknown: visibleInventoryList.filter((i) => i.type === "unknown").length,
    loadedKgQuantity,
    nonKgBatchCount,
    loadedUnitKinds: loadedUnitKinds.length,
    warningCount: visibleInventoryStats?.expiringBatchesCount ?? visibleInventoryList.filter((i) => i.warningType !== "normal").length,
    totalValue: visibleInventoryStats?.totalValue ?? null,
  };

  const handleItemPress = (item: InventoryItem) => {
    if (!isCurrentOwnerFor(user, ownerTag, renderAuthVersion)
      || !visibleInventoryList.some((candidate) => candidate.id === item.id)) return;
    navigation.navigate("WHInventoryDetail", { inventoryId: item.id });
  };

  const handleQuickAction = (action: QuickAction) => {
    if (!isCurrentOwnerFor(user, ownerTag, renderAuthVersion)) return;
    (navigation.navigate as (screen: string) => void)(action.screen);
  };

  const actionSheetOwned = actionSheetVisible
    && ownerSnapshotIsCurrent
    && selectedItem != null
    && visibleInventoryList.some((item) => item.id === selectedItem.id)
    && sheetOwner === user
    && sheetOwnerTag === ownerTag
    && sheetOwnerEpoch === authEpoch
    && isCurrentOwnerFor(user, ownerTag, renderAuthVersion);

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.headerTitle}>库存管理</Text>
        {/* 口径修正: 296 是**批次数**不是物料种类; 且列表只加载了其中一页, 明说清楚。 */}
        <Text style={styles.headerSubtitle}>
          在库 {stats.totalBatches} 批
          {stats.loaded < stats.totalBatches ? ` | 已加载 ${stats.loaded}` : ''}
        </Text>
      </View>

      {loading ? (
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载库存中...</Text>
        </View>
      ) : loadError ? (
        <View style={styles.errorContainer} testID="wh-inventory-load-error">
          <Searchbar
            placeholder="搜索物料名称/批次号"
            value={searchQuery}
            onChangeText={setSearchQuery}
            style={styles.searchBar}
            inputStyle={styles.searchInput}
          />
          <Text style={styles.errorText}>{loadError}</Text>
          <Button
            mode="contained"
            onPress={retryLoad}
            testID="wh-inventory-retry"
            style={styles.retryButton}
            contentStyle={styles.retryButtonContent}
          >
            重试
          </Button>
        </View>
      ) : (
      <ScrollView
        style={styles.content}
        testID="wh-inventory-scroll"
        refreshControl={
          <RefreshControl refreshing={refreshing} onRefresh={onRefresh} />
        }
        showsVerticalScrollIndicator={false}
      >
        {/* 快捷操作 */}
        <View style={styles.quickActions}>
          {quickActions.map((action) => (
            <TouchableOpacity
              key={action.key}
              style={styles.quickActionItem}
              onPress={() => handleQuickAction(action)}
              activeOpacity={0.7}
              testID={`wh-quick-action-${action.key}`}
            >
              <View
                style={[
                  styles.quickActionIcon,
                  { backgroundColor: `${action.color}15` },
                ]}
              >
                <MaterialCommunityIcons
                  name={action.icon}
                  size={24}
                  color={action.color}
                />
              </View>
              <Text style={styles.quickActionLabel}>{action.label}</Text>
            </TouchableOpacity>
          ))}
        </View>

        {/* 搜索栏 */}
        <Searchbar
          placeholder="搜索物料名称/批次号"
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
            selected={selectedType === "all"}
            onPress={() => setSelectedType("all")}
            style={[
              styles.filterChip,
              selectedType === "all" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "all" ? styles.filterChipTextActive : undefined}
          >
            {/* 口径修正: 类型分项只覆盖已加载的这一页, 「全部」必须同源,
                否则 全部(296) 与 鲜品+冻品+干货(50) 当场对不上。 */}
            全部({stats.loaded})
          </Chip>
          <Chip
            testID="wh-inventory-filter-fresh"
            selected={selectedType === "fresh"}
            onPress={() => setSelectedType("fresh")}
            style={[
              styles.filterChip,
              selectedType === "fresh" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "fresh" ? styles.filterChipTextActive : undefined}
          >
            鲜品({stats.fresh})
          </Chip>
          <Chip
            selected={selectedType === "frozen"}
            onPress={() => setSelectedType("frozen")}
            style={[
              styles.filterChip,
              selectedType === "frozen" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "frozen" ? styles.filterChipTextActive : undefined}
          >
            冻品({stats.frozen})
          </Chip>
          <Chip
            selected={selectedType === "dry"}
            onPress={() => setSelectedType("dry")}
            style={[
              styles.filterChip,
              selectedType === "dry" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "dry" ? styles.filterChipTextActive : undefined}
          >
            干货({stats.dry})
          </Chip>
          <Chip
            testID="wh-inventory-filter-unknown"
            selected={selectedType === "unknown"}
            onPress={() => setSelectedType("unknown")}
            style={[
              styles.filterChip,
              selectedType === "unknown" && styles.filterChipActive,
            ]}
            textStyle={selectedType === "unknown" ? styles.filterChipTextActive : undefined}
          >
            类型未提供({stats.unknown})
          </Chip>
        </ScrollView>

        {/* 库存列表 */}
        <View style={styles.listContainer}>
          {filteredList.map((item) => {
            const typeConf = typeConfig[item.type];
            return (
              <TouchableOpacity
                key={item.id}
                testID={`wh-inventory-row-${item.id}`}
                onPress={() => handleItemPress(item)}
                onLongPress={() => openSheet(item)}
                activeOpacity={0.7}
              >
                <Surface style={styles.inventoryCard} elevation={1}>
                  <View style={styles.cardHeader}>
                    <Text style={styles.materialName}>{item.name}</Text>
                    <View
                      style={[
                        styles.typeBadge,
                        { backgroundColor: typeConf.bgColor },
                      ]}
                    >
                      <Text style={[styles.typeText, { color: typeConf.color }]}>
                        {typeConf.label}
                      </Text>
                    </View>
                  </View>
                  <Text style={styles.batchNumber}>批次号: {item.batchNumber}</Text>

                  <View style={styles.cardContent}>
                    <View style={styles.mainInfo}>
                      <Text style={styles.quantityValue}>{formatQuantity(item.quantity)}</Text>
                      <Text style={styles.unitText}>{item.unit}</Text>
                    </View>

                    <View style={styles.metaInfo}>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>批次</Text>
                        <Text style={styles.metaValue}>{item.batchCount}个</Text>
                      </View>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>库位</Text>
                        <Text style={styles.metaValue}>{item.location}</Text>
                      </View>
                      <View style={styles.metaItem}>
                        <Text style={styles.metaLabel}>
                          {item.warningType === "normal" ? "状态" : "预警"}
                        </Text>
                        <Text
                          style={[
                            styles.metaValue,
                            item.warningType === "expire" && styles.warningText,
                            item.warningType === "low" && styles.lowText,
                            item.warningType === "normal" && styles.normalText,
                          ]}
                        >
                          {item.warning || "正常"}
                        </Text>
                      </View>
                    </View>
                  </View>

                  {/*
                    多单位换算 (2026-08-02): 仓管员不用心算「10000 盒是多少箱」。
                    换算只认原料字典的规格层级; 没配就明说「未设规格」并给入口,
                    绝不退回按 1:1 猜 —— 那正是 725,908 那个假数的成因。
                  */}
                  {(() => {
                    const h = item.materialTypeId ? visibleHierarchyMap[item.materialTypeId] : undefined;
                    const conv = convertByHierarchy(item.quantity, item.unit, h);
                    if (!conv) {
                      return (
                        <TouchableOpacity
                          style={styles.convertRow}
                          onPress={() => {
                            if (isCurrentOwnerFor(user, ownerTag, renderAuthVersion)) {
                              navigation.navigate('WHInventoryDetail', { inventoryId: item.id });
                            }
                          }}
                        >
                          <Text style={styles.convertMissing}>⚠ 未设规格 · 无法折算</Text>
                          <Text style={styles.convertLink}>去设置 ›</Text>
                        </TouchableOpacity>
                      );
                    }
                    const others = conv.levels.filter((l) => canonicalUnit(l.unit) !== canonicalUnit(item.unit));
                    if (others.length === 0) return null;
                    return (
                      <View style={styles.convertRow}>
                        <Text style={styles.convertText}>
                          {others.map((l) => `≈ ${formatQuantity(l.quantity)} ${l.unit}`).join('   ')}
                        </Text>
                      </View>
                    );
                  })()}

                  <View style={styles.cardFooter}>
                    <Text style={styles.updateText}>
                      更新: {item.updatedAt}
                    </Text>
                    <View style={styles.actionLink}>
                      <Text style={styles.actionText}>查看详情</Text>
                      <MaterialCommunityIcons
                        name="chevron-right"
                        size={16}
                        color="#4CAF50"
                      />
                    </View>
                  </View>
                </Surface>
              </TouchableOpacity>
            );
          })}
        </View>

        {/* 库存概览 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>库存概览</Text>
          {/*
            计数单位分列 —— 盒/箱/片/卷 之间没有通用换算 (全局单位表里那些 1:1 是假的),
            所以按单位各自成组显示, 而不是加成一个没有业务含义的总数。
          */}
          {bucketByDimension(visibleInventoryList).filter((b) => b.kind === 'count').length > 0 && (
            <View style={styles.countUnitRow}>
              {bucketByDimension(visibleInventoryList)
                .filter((b) => b.kind === 'count')
                .map((b) => (
                  <View key={b.unit} style={styles.countUnitChip}>
                    <Text style={styles.countUnitValue}>{formatQuantity(b.quantity)}</Text>
                    {/* ⛔ 这里不能用 b.unit —— 那是 canonicalUnit() 折出来的英文归一键
                        (盒 → box), 渲染它等于我们自己把中文翻成英文给用户看。
                        b.unit 留给上面的 React key 用。 */}
                    <Text style={styles.countUnitLabel}>{b.displayLabel} · {b.batchCount}批</Text>
                  </View>
                ))}
            </View>
          )}
          <View style={styles.statsGrid}>
            {/* 口径修正: 这个数是批次数, 不是物料种类 —— 底部 sticky bar 的「共 N 批」同源。 */}
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>{stats.totalBatches}</Text>
              <Text style={styles.statsLabel}>批次总数</Text>
            </View>
            {/* 口径修正: 重量族折 kg 合并, 计数族按单位各自成组 —— 绝不跨量纲相加。 */}
            <View style={styles.statsItem}>
              <Text style={styles.statsValue}>
                {formatQuantity(
                  bucketByDimension(visibleInventoryList).find((b) => b.kind === 'weight')?.quantity ?? 0
                )}
              </Text>
              <Text style={styles.statsLabel}>已加载食材(kg)</Text>
            </View>
            {priceVisible && (
              <View style={styles.statsItem} testID="wh-inventory-price-card">
                <Text style={styles.statsValue} testID="wh-inventory-price">
                  {stats.totalValue == null
                    ? '金额未提供'
                    : `¥${stats.totalValue > 1000 ? `${(stats.totalValue / 1000).toFixed(0)}K` : stats.totalValue.toFixed(0)}`}
                </Text>
                <Text style={styles.statsLabel}>库存价值</Text>
              </View>
            )}
            <View style={styles.statsItem}>
              <Text style={[styles.statsValue, { color: "#f44336" }]}>
                {stats.warningCount}
              </Text>
              <Text style={styles.statsLabel}>预警数</Text>
            </View>
          </View>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>
      )}

      {summaryFactoryId && user && (
        <InventorySummaryFooter
          key={`${authEpoch}:${ownerVersion}:${ownerTag}`}
          factoryId={summaryFactoryId}
          ownerActor={user}
          ownerTag={ownerTag}
          visible={ownerSnapshotIsCurrent && !loadError}
          loading={loading}
          refreshing={refreshing}
          aiAvailable={summaryAiAvailable}
          navigation={navigation}
          isCurrentOwner={isCurrentOwner}
        />
      )}

      {!summaryAiAvailable && summaryFactoryId && user && ownerSnapshotIsCurrent && (
        <Text style={styles.aiUnavailableText}>库存 AI 分析请联系仓储主管</Text>
      )}

      <RowActionBottomSheet
        visible={actionSheetOwned}
        onClose={() => setActionSheetVisible(false)}
        actions={actionSheetOwned ? rowActions : []}
        title={actionSheetOwned && selectedItem ? `${selectedItem.name}` : ''}
        aiTriggerEnabled={summaryAiAvailable}
        onAITrigger={() => {
          if (!selectedItem || !summaryAiAvailable || !hasAncestorRoute(navigation, 'AIChat') || !isCurrentOwnerFor(user, ownerTag, renderAuthVersion)) return;
          navigation.dispatch(CommonActions.navigate('AIChat', {
            initialMessage: `${selectedItem.name}: `,
          }));
        }}
      />
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
    paddingTop: 100,
  },
  loadingText: {
    marginTop: 12,
    fontSize: 14,
    color: "#666",
  },
  errorContainer: {
    flex: 1,
    padding: 16,
    alignItems: "stretch",
  },
  errorText: {
    marginTop: 24,
    textAlign: "center",
    color: "#8a1c1c",
    fontSize: 15,
    lineHeight: 22,
  },
  retryButton: {
    marginTop: 12,
    minHeight: 44,
    alignSelf: "center",
  },
  retryButtonContent: {
    minHeight: 44,
    paddingHorizontal: 20,
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
  quickActions: {
    flexDirection: "row",
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    paddingVertical: 16,
  },
  quickActionItem: {
    flex: 1,
    alignItems: "center",
  },
  quickActionIcon: {
    width: 48,
    height: 48,
    borderRadius: 24,
    alignItems: "center",
    justifyContent: "center",
    marginBottom: 8,
  },
  quickActionLabel: {
    fontSize: 12,
    color: "#666",
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
  inventoryCard: {
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
  materialName: {
    fontSize: 16,
    fontWeight: "600",
    color: "#333",
  },
  batchNumber: {
    marginTop: 4,
    color: "#666",
    fontSize: 13,
  },
  aiUnavailableText: {
    paddingHorizontal: 16,
    paddingVertical: 6,
    textAlign: "center",
    color: "#666",
    fontSize: 12,
  },
  typeBadge: {
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 12,
  },
  typeText: {
    fontSize: 12,
    fontWeight: "500",
  },
  cardContent: {
    flexDirection: "row",
    borderTopWidth: 1,
    borderTopColor: "#f0f0f0",
    paddingTop: 12,
  },
  mainInfo: {
    flexDirection: "row",
    alignItems: "baseline",
    width: 100,
  },
  countUnitRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 12,
  },
  countUnitChip: {
    backgroundColor: '#f4f7fb',
    borderRadius: 8,
    paddingHorizontal: 12,
    paddingVertical: 8,
    minWidth: 96,
  },
  countUnitValue: {
    fontSize: 18,
    fontWeight: 'bold',
    color: '#2d3748',
  },
  countUnitLabel: {
    fontSize: 12,
    color: '#718096',
    marginTop: 2,
  },
  convertRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginTop: 6,
    minHeight: 44,           // 触摸目标 >=44pt (ux-flow 内联规则)
    paddingVertical: 4,
  },
  convertText: {
    fontSize: 13,
    color: '#666',
  },
  convertMissing: {
    fontSize: 13,
    color: '#ed8936',
  },
  convertLink: {
    fontSize: 13,
    color: '#1565c0',
    fontWeight: '600',
  },
  quantityValue: {
    fontSize: 28,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  unitText: {
    fontSize: 14,
    color: "#666",
    marginLeft: 4,
  },
  metaInfo: {
    flex: 1,
    marginLeft: 16,
  },
  metaItem: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 4,
  },
  metaLabel: {
    fontSize: 12,
    color: "#999",
  },
  metaValue: {
    fontSize: 12,
    color: "#333",
  },
  warningText: {
    color: "#f44336",
    fontWeight: "600",
  },
  lowText: {
    color: "#ff9800",
    fontWeight: "600",
  },
  normalText: {
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
  updateText: {
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
  section: {
    marginHorizontal: 16,
    marginTop: 16,
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
    marginBottom: 12,
  },
  statsGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
  },
  statsItem: {
    width: "25%",
    alignItems: "center",
    paddingVertical: 8,
  },
  statsValue: {
    fontSize: 20,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  statsLabel: {
    fontSize: 11,
    color: "#666",
    marginTop: 4,
  },
});

export default WHInventoryListScreen;
