/**
 * 库位管理页面
 * 对应原型: warehouse/location-manage.html
 */

import React, { useState, useEffect, useCallback, useRef } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Modal,
  useWindowDimensions,
} from "react-native";
import { Text, Surface, Button, TouchableRipple, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { GestureHandlerRootView } from 'react-native-gesture-handler';
import * as ScreenOrientation from 'expo-screen-orientation';
import { RouteProp, useNavigation, useRoute } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { warehouseLayoutApiClient, WarehouseLayoutConfiguration } from "../../../services/api/warehouseLayoutApiClient";
import { requireFactoryId } from '../../../utils/factoryIdHelper';
import { WarehouseMapViewport } from './WarehouseMapViewport';
import { loadAllWarehouseMaterialBatches } from './warehouseInventoryPages';
import { handleError } from "../../../utils/errorHandler";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

interface Zone {
  id: string;
  name: string;
  tempRange: string;
  totalLocations: number;
  usedLocations: number;
  capacity: number;
}

interface Location {
  id: string;
  name: string;
  zoneId: string;
  status: "using" | "empty";
  capacity: number;
  used: number;
  temperature: number;
  tempNormal: boolean;
  items?: string;
}

export function WHLocationManageScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteProp<WHInventoryStackParamList, 'WHLocationManage'>>();
  const { width: screenWidth, height: screenHeight } = useWindowDimensions();
  const autoOpenedMap = useRef(false);
  const orientationQueue = useRef<Promise<void>>(Promise.resolve());

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);
  const [zones, setZones] = useState<Zone[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [totalLocations, setTotalLocations] = useState(0);
  const [usedLocations, setUsedLocations] = useState(0);
  const [warehouseLayout, setWarehouseLayout] = useState<WarehouseLayoutConfiguration | null>(null);
  const [mapVisible, setMapVisible] = useState(false);
  const [mapFloorId, setMapFloorId] = useState('');
  const [mapRackId, setMapRackId] = useState('');
  const [orientationError, setOrientationError] = useState(false);

  const lockMapOrientation = useCallback((lock: ScreenOrientation.OrientationLock) => {
    const next = orientationQueue.current.catch(() => undefined).then(() => ScreenOrientation.lockAsync(lock));
    orientationQueue.current = next;
    return next.catch(() => setOrientationError(true));
  }, []);

  useEffect(() => {
    void lockMapOrientation(mapVisible
      ? ScreenOrientation.OrientationLock.DEFAULT
      : ScreenOrientation.OrientationLock.PORTRAIT_UP);
  }, [lockMapOrientation, mapVisible]);

  useEffect(() => () => {
    void lockMapOrientation(ScreenOrientation.OrientationLock.PORTRAIT_UP);
  }, [lockMapOrientation]);

  // 从批次数据计算库位信息
  const loadLocationData = useCallback(async () => {
    setLoading(true);
    setLoadError(false);
    try {
      const factoryId = requireFactoryId();
      const layout = await warehouseLayoutApiClient.getLayout(factoryId);
      const allBatches = await loadAllWarehouseMaterialBatches<MaterialBatch>(factoryId, async (page) => {
        const response = await materialBatchApiClient.getMaterialBatches({ factoryId, status: 'available', page, size: 200 });
        if (!response.success || !response.data) throw new Error(response.message || '库存查询失败');
        return response.data;
      });
      setWarehouseLayout(layout);
      if (layout.floors[0]) setMapFloorId(layout.floors[0].id);

      // 按库位分组批次
      const locationMap = new Map<string, {
        batches: MaterialBatch[];
        totalQuantity: number;
        materials: Set<string>;
      }>();

      allBatches.forEach((batch: MaterialBatch) => {
        const loc = batch.storageLocation || '未分配';
        if (!locationMap.has(loc)) {
          locationMap.set(loc, {
            batches: [],
            totalQuantity: 0,
            materials: new Set(),
          });
        }
        const data = locationMap.get(loc)!;
        data.batches.push(batch);
        data.totalQuantity += batch.remainingQuantity || 0;
        if (batch.materialName) {
          data.materials.add(batch.materialName);
        }
      });

      // 生成库位列表
      const locationList: Location[] = layout.locations.map((location) => {
        const data = locationMap.get(location.code);
        return {
          id: location.code,
          name: location.name === location.code ? location.code : `${location.code} · ${location.name}`,
          zoneId: location.zoneId,
          status: data?.batches.length ? 'using' : 'empty',
          capacity: 0,
          used: data?.totalQuantity ?? 0,
          temperature: 0,
          tempNormal: true,
          items: data?.materials.size
            ? `${Array.from(data.materials).slice(0, 2).join('、')}${data.materials.size > 2 ? '等' : ''} · ${data.batches.length}批`
            : undefined,
        };
      });

      const zoneStats: Zone[] = layout.zones.map((zone) => {
        const zoneLocations = locationList.filter((location) => location.zoneId === zone.id);
        return {
          id: zone.id,
          name: zone.name,
          tempRange: zone.prefix,
          totalLocations: zoneLocations.length,
          usedLocations: zoneLocations.filter((location) => location.status === 'using').length,
          capacity: zoneLocations.reduce((total, location) => total + (locationMap.get(location.id)?.batches.length ?? 0), 0),
        };
      });

      setZones(zoneStats);
      setLocations(locationList);
      setTotalLocations(locationList.length);
      setUsedLocations(locationList.filter(l => l.status === 'using').length);

    } catch (error) {
      setLoadError(true);
      handleError(error, { title: '加载库位数据失败' });
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadLocationData();
  }, [loadLocationData]);

  const getUsagePercent = (used: number, capacity: number) => {
    return capacity > 0 ? Math.round((used / capacity) * 100) : 0;
  };

  const mapFloor = warehouseLayout?.floors.find((floor) => floor.id === mapFloorId);
  const mapRack = mapFloor?.racks.find((rack) => rack.id === mapRackId);

  useEffect(() => {
    if (autoOpenedMap.current || loading || loadError || !route.params?.openMap) return;
    autoOpenedMap.current = true;
    if (warehouseLayout?.floors.length) setMapVisible(true);
  }, [loading, loadError, route.params?.openMap, warehouseLayout]);

  const getCapacityColor = (percent: number) => {
    if (percent > 100) return "#f44336";
    if (percent > 80) return "#f57c00";
    return "#4CAF50";
  };

  const renderZoneCard = (zone: Zone) => (
    <Surface key={zone.id} style={styles.zoneCard} elevation={1}>
      <View style={styles.zoneHeader}>
        <Text style={styles.zoneName}>{zone.name}</Text>
        <Text style={styles.zoneTemp}>{zone.tempRange}</Text>
      </View>
      <View style={styles.zoneStats}>
        <View style={styles.zoneStat}>
          <Text style={styles.zoneStatValue}>{zone.totalLocations}</Text>
          <Text style={styles.zoneStatLabel}>库位</Text>
        </View>
        <View style={styles.zoneStat}>
          <Text style={styles.zoneStatValue}>{zone.usedLocations}</Text>
          <Text style={styles.zoneStatLabel}>使用中</Text>
        </View>
        <View style={styles.zoneStat}>
          <Text style={styles.zoneStatValue}>{zone.capacity}</Text>
          <Text style={styles.zoneStatLabel}>批次</Text>
        </View>
      </View>
    </Surface>
  );

  const renderLocationCard = (location: Location) => {
    const usagePercent = getUsagePercent(location.used, location.capacity);
    const capacityColor = getCapacityColor(usagePercent);
    const isOverCapacity = usagePercent > 100;

    return (
      <View
        key={location.id}
        style={[
          styles.locationCard,
          location.status === "empty" && styles.locationCardEmpty,
        ]}
      >
        <View style={styles.locationHeader}>
          <Text style={styles.locationName}>{location.name}</Text>
          <View
            style={[
              styles.locationStatus,
              location.status === "using"
                ? styles.locationStatusUsing
                : styles.locationStatusEmpty,
            ]}
          >
            <Text
              style={[
                styles.locationStatusText,
                location.status === "using"
                  ? styles.locationStatusTextUsing
                  : styles.locationStatusTextEmpty,
              ]}
            >
              {location.status === "using" ? "使用中" : "空闲"}
            </Text>
          </View>
        </View>

        <View style={styles.locationContent}>
          {location.capacity > 0 && <View style={styles.locationRow}>
            <Text style={styles.locationLabel}>容量</Text>
            <Text style={styles.locationValue}>{location.capacity} kg</Text>
          </View>}

          {location.status === "using" && location.capacity > 0 && (
            <View style={styles.locationRow}>
              <Text style={styles.locationLabel}>已用</Text>
              <Text
                style={[
                  styles.locationValue,
                  isOverCapacity && { color: "#f44336" },
                ]}
              >
                {location.used} kg ({usagePercent}%)
              </Text>
            </View>
          )}

          {location.temperature !== 0 && <View style={styles.locationRow}>
            <Text style={styles.locationLabel}>温度</Text>
            <Text
              style={[
                styles.locationValue,
                { color: location.tempNormal ? "#4CAF50" : "#f44336" },
              ]}
            >
              {location.temperature}°C {location.tempNormal ? "✓" : "!"}
            </Text>
          </View>}

          {location.items && (
            <View style={styles.locationRow}>
              <Text style={styles.locationLabel}>存放</Text>
              <Text style={styles.locationValue}>{location.items}</Text>
            </View>
          )}
        </View>

        {location.status === "using" && location.capacity > 0 && (
          <View style={styles.capacityBarContainer}>
            <View
              style={[
                styles.capacityBar,
                {
                  width: `${Math.min(usagePercent, 100)}%`,
                  backgroundColor: capacityColor,
                },
              ]}
            />
          </View>
        )}
      </View>
    );
  };

  // 加载状态
  if (loading) {
    return (
      <SafeAreaView style={styles.container} edges={["top"]}>
        <View style={styles.header}>
          <TouchableOpacity
            style={styles.backButton}
            onPress={() => navigation.goBack()}
          >
            <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
          </TouchableOpacity>
          <View style={styles.headerCenter}>
            <Text style={styles.headerTitle}>库位管理</Text>
            <Text style={styles.headerSubtitle}>加载中...</Text>
          </View>
          <View style={styles.headerRight} />
        </View>
        <View style={styles.loadingContainer}>
          <ActivityIndicator size="large" color="#4CAF50" />
          <Text style={styles.loadingText}>加载中...</Text>
        </View>
      </SafeAreaView>
    );
  }

  if (loadError) {
    return <SafeAreaView style={styles.container} edges={["top"]}>
      <View style={styles.loadingContainer}>
        <Text style={styles.loadingText}>仓库地图与库存未完整加载，请重试</Text>
        <Button mode="contained" onPress={loadLocationData} testID="warehouse-map-retry">重试</Button>
        <Button mode="text" onPress={() => navigation.goBack()}>返回</Button>
      </View>
    </SafeAreaView>;
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      {/* Header */}
      <View style={styles.header}>
        <TouchableOpacity
          style={styles.backButton}
          onPress={() => navigation.goBack()}
        >
          <MaterialCommunityIcons name="arrow-left" size={24} color="#fff" />
        </TouchableOpacity>
        <View style={styles.headerCenter}>
          <Text style={styles.headerTitle}>库位管理</Text>
          <Text style={styles.headerSubtitle}>
            总库位 {totalLocations} 个 | 使用中 {usedLocations} 个
          </Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {warehouseLayout && warehouseLayout.floors.length > 0 ? (
          <Button mode="contained" icon="floor-plan" onPress={() => setMapVisible(true)}
            style={styles.mapEntryButton} testID="warehouse-map-open">
            查看仓库平面图
          </Button>
        ) : route.params?.openMap ? (
          <View style={styles.mapUnavailableNotice}>
            <Text>当前仓库还没有平面图。可先查看下方库位，或请管理员在网页端配置。</Text>
          </View>
        ) : null}
        {/* 库区概览 */}
        <View style={styles.zoneOverview}>
          {zones.map(renderZoneCard)}
        </View>

        {zones.map((zone) => (
          <View key={zone.id} style={styles.section}>
            <Text style={styles.sectionTitle}>{zone.name} · {zone.tempRange}</Text>
            {locations.filter((location) => location.zoneId === zone.id).map(renderLocationCard)}
          </View>
        ))}

        {zones.length === 0 && (
          <View style={styles.section}>
            <Text style={styles.loadingText}>暂无储存位置，请先在网页端配置</Text>
          </View>
        )}

        {/* 操作按钮 */}
        <View style={styles.actionButtons}>
          <Button
            mode="outlined"
            onPress={() => navigation.navigate("WHTempMonitor")}
            style={styles.actionButton}
            labelStyle={{ color: "#4CAF50" }}
          >
            温控监控
          </Button>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>

      <Modal visible={mapVisible} animationType="slide" supportedOrientations={["portrait", "landscape"]} onRequestClose={() => setMapVisible(false)}>
        <GestureHandlerRootView style={styles.mapContainer}>
        <SafeAreaView style={styles.mapContainer} edges={['top', 'bottom']}>
          <View style={styles.mapHeader}>
            <TouchableRipple onPress={() => setMapVisible(false)} style={styles.mapCloseButton}
              accessibilityRole="button" accessibilityLabel="关闭仓库地图" testID="warehouse-map-close">
              <MaterialCommunityIcons name="arrow-left" size={24} color="#1F2937" />
            </TouchableRipple>
            <View style={styles.mapHeaderCopy}>
              <Text style={styles.mapTitle}>仓库平面图</Text>
              <Text style={styles.mapSubtitle}>{mapFloor?.name ?? '选择楼层'} · {mapFloor?.racks.length ?? 0} 个货架</Text>
            </View>
            <TouchableRipple onPress={() => { void lockMapOrientation(screenWidth > screenHeight
              ? ScreenOrientation.OrientationLock.PORTRAIT_UP
              : ScreenOrientation.OrientationLock.LANDSCAPE); }} style={styles.mapRotateButton}
              accessibilityRole="button" accessibilityLabel={screenWidth > screenHeight ? '切换竖屏' : '切换横屏'}
              testID="warehouse-map-rotate">
              <MaterialCommunityIcons name="screen-rotation" size={24} color="#1F2937" />
            </TouchableRipple>
          </View>
          {orientationError && <Text style={styles.mapOrientationError}>屏幕方向切换失败，请检查设备设置</Text>}
          <View style={styles.mapStage}>
            {mapFloor && warehouseLayout && <WarehouseMapViewport key={mapFloor.id} floor={mapFloor} layout={warehouseLayout}
              selectedRackId={mapRackId} onSelectRack={setMapRackId} />}
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.floorTabs}
              contentContainerStyle={styles.floorTabsContent}>
              {warehouseLayout?.floors.map((floor) => (
                <TouchableRipple key={floor.id} onPress={() => { setMapFloorId(floor.id); setMapRackId(''); }}
                  style={[styles.floorTab, floor.id === mapFloorId && styles.floorTabActive]}
                  accessibilityRole="tab" accessibilityState={{ selected: floor.id === mapFloorId }}
                  testID={`warehouse-map-floor-${floor.id}`}>
                  <Text style={[styles.floorTabText, floor.id === mapFloorId && styles.floorTabTextActive]}>{floor.name}</Text>
                </TouchableRipple>
              ))}
            </ScrollView>
          {mapRack ? (
            <View style={[styles.mapShelfPanel, screenWidth > screenHeight && styles.mapShelfPanelLandscape]}>
              <View style={styles.mapShelfHeader}>
                <View style={styles.mapShelfHeaderCopy}>
                  <Text style={styles.mapShelfTitle}>{mapRack.name}</Text>
                  <Text style={styles.mapShelfSubtitle}>
                    {warehouseLayout?.zones.find((zone) => zone.id === mapRack.zoneId)?.name ?? '未归属库区'} · {mapRack.levels} 层
                  </Text>
                </View>
                <TouchableRipple onPress={() => setMapRackId('')} style={styles.mapShelfClose}
                  accessibilityRole="button" accessibilityLabel="收起货架层位" testID="warehouse-map-rack-close">
                  <MaterialCommunityIcons name="close" size={21} color="#6B7280" />
                </TouchableRipple>
              </View>
              <ScrollView style={styles.mapShelfLevels} nestedScrollEnabled>
                {Array.from({ length: mapRack.levels }, (_, index) => mapRack.levels - index).map((level) => (
                  <View key={level} style={styles.mapShelfRow}>
                    <Text style={styles.mapShelfLevel}>{level}F</Text>
                    <Text style={styles.mapShelfCodes}>{warehouseLayout?.locations.filter((location) => location.rackId === mapRack.id && location.level === level).map((location) => location.code).join('  ·  ') || '空闲'}</Text>
                  </View>
                ))}
              </ScrollView>
            </View>
          ) : (
            !mapFloor?.racks.length && <View style={styles.mapHintPanel}>
              <MaterialCommunityIcons name="map-marker-off-outline" size={22} color="#6B7280" />
              <Text style={styles.mapHintText}>这一层暂无货架；可切换楼层，或请管理员在网页端配置</Text>
            </View>
          )}
          </View>
        </SafeAreaView>
        </GestureHandlerRootView>
      </Modal>
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
    flexDirection: "row",
    alignItems: "center",
    paddingHorizontal: 16,
    paddingVertical: 12,
  },
  backButton: {
    padding: 4,
  },
  headerCenter: {
    flex: 1,
    alignItems: "center",
    marginRight: 28,
  },
  headerTitle: {
    fontSize: 18,
    fontWeight: "600",
    color: "#fff",
  },
  headerSubtitle: {
    fontSize: 12,
    color: "rgba(255,255,255,0.8)",
    marginTop: 2,
  },
  headerRight: {
    width: 28,
  },
  content: {
    flex: 1,
  },
  zoneOverview: {
    flexDirection: "row",
    flexWrap: "wrap",
    paddingHorizontal: 16,
    paddingTop: 16,
    gap: 12,
  },
  zoneCard: {
    flexBasis: "46%",
    flexGrow: 1,
    minWidth: 145,
    backgroundColor: "#fff",
    borderRadius: 12,
    padding: 12,
  },
  zoneHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  zoneName: {
    fontSize: 13,
    fontWeight: "600",
    color: "#333",
  },
  zoneTemp: {
    fontSize: 11,
    color: "#0097a7",
  },
  zoneStats: {
    flexDirection: "row",
    justifyContent: "space-around",
  },
  zoneStat: {
    alignItems: "center",
  },
  zoneStatValue: {
    fontSize: 16,
    fontWeight: "bold",
    color: "#333",
  },
  zoneStatLabel: {
    fontSize: 10,
    color: "#999",
    marginTop: 2,
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  locationCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
  },
  locationCardEmpty: {
    opacity: 0.7,
  },
  locationHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 10,
  },
  locationName: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  locationStatus: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
  },
  locationStatusUsing: {
    backgroundColor: "#e8f5e9",
  },
  locationStatusEmpty: {
    backgroundColor: "#f5f5f5",
  },
  locationStatusText: {
    fontSize: 11,
    fontWeight: "500",
  },
  locationStatusTextUsing: {
    color: "#4CAF50",
  },
  locationStatusTextEmpty: {
    color: "#999",
  },
  locationContent: {
    gap: 6,
  },
  locationRow: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  locationLabel: {
    fontSize: 13,
    color: "#999",
  },
  locationValue: {
    fontSize: 13,
    color: "#333",
  },
  capacityBarContainer: {
    height: 4,
    backgroundColor: "#e0e0e0",
    borderRadius: 2,
    marginTop: 10,
    overflow: "hidden",
  },
  capacityBar: {
    height: "100%",
    borderRadius: 2,
  },
  actionButtons: {
    paddingHorizontal: 16,
    marginTop: 16,
  },
  actionButton: {
    borderRadius: 8,
    borderColor: "#4CAF50",
  },
  mapEntryButton: { marginHorizontal: 16, marginTop: 16, borderRadius: 12, minHeight: 52, justifyContent: 'center' },
  mapUnavailableNotice: { margin: 16, padding: 16, borderRadius: 12, backgroundColor: '#E6F7FF' },
  mapContainer: { flex: 1, backgroundColor: '#F5F5F5' },
  mapStage: { flex: 1 },
  mapHeader: { minHeight: 68, paddingHorizontal: 16, flexDirection: 'row', alignItems: 'center', backgroundColor: '#fff', borderBottomWidth: 1, borderBottomColor: '#E5E7EB' },
  mapCloseButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center', borderRadius: 22 },
  mapHeaderCopy: { flex: 1, alignItems: 'center' },
  mapRotateButton: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  mapOrientationError: { color: '#B42318', backgroundColor: '#FFF1F0', paddingHorizontal: 16, paddingVertical: 6, fontSize: 12 },
  mapTitle: { fontSize: 18, fontWeight: '700', color: '#1F2937' },
  mapSubtitle: { marginTop: 2, fontSize: 12, color: '#6B7280' },
  floorTabs: { position: 'absolute', top: 0, left: 0, right: 0, maxHeight: 64 },
  floorTabsContent: { paddingHorizontal: 16, paddingVertical: 10, alignItems: 'center' },
  floorTab: { minWidth: 64, minHeight: 44, paddingHorizontal: 16, marginRight: 8, borderRadius: 12, alignItems: 'center', justifyContent: 'center', backgroundColor: '#fff', borderWidth: 1, borderColor: '#E5E7EB' },
  floorTabActive: { backgroundColor: '#E6F7FF', borderColor: '#1890FF' },
  floorTabText: { fontSize: 14, fontWeight: '600', color: '#6B7280' },
  floorTabTextActive: { color: '#1890FF' },
  mapShelfPanel: { position: 'absolute', bottom: 16, left: 14, right: 14, maxHeight: '45%', padding: 14, backgroundColor: '#fff', borderRadius: 16, borderWidth: 1, borderColor: '#E5E7EB', elevation: 3 },
  mapShelfPanelLandscape: { top: 8, bottom: 8, left: undefined, width: 320, maxHeight: undefined },
  mapShelfHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  mapShelfHeaderCopy: { flex: 1 },
  mapShelfTitle: { fontSize: 17, fontWeight: '700', color: '#1F2937' },
  mapShelfSubtitle: { fontSize: 12, color: '#6B7280', marginTop: 2 },
  mapShelfClose: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  mapShelfLevels: { maxHeight: 176 },
  mapShelfRow: { minHeight: 44, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: '#E5E7EB' },
  mapShelfLevel: { width: 48, color: '#6B7280', fontSize: 14, fontWeight: '600' },
  mapShelfCodes: { flex: 1, color: '#1F2937', fontSize: 14 },
  mapHintPanel: { position: 'absolute', bottom: 16, left: 14, right: 14, minHeight: 64, paddingHorizontal: 16, flexDirection: 'row', gap: 10, alignItems: 'center', backgroundColor: '#fff', borderRadius: 16, borderWidth: 1, borderColor: '#E5E7EB' },
  mapHintText: { flex: 1, color: '#6B7280', fontSize: 14 },
});

export default WHLocationManageScreen;
