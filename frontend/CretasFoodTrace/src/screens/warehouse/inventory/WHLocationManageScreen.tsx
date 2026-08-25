/**
 * 库位管理页面
 * 对应原型: warehouse/location-manage.html
 */

import React, { useState, useEffect, useCallback } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
  Modal,
} from "react-native";
import { Text, Surface, Button, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { materialBatchApiClient, MaterialBatch } from "../../../services/api/materialBatchApiClient";
import { warehouseLayoutApiClient, WarehouseLayoutConfiguration } from "../../../services/api/warehouseLayoutApiClient";
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

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [zones, setZones] = useState<Zone[]>([]);
  const [locations, setLocations] = useState<Location[]>([]);
  const [totalLocations, setTotalLocations] = useState(0);
  const [usedLocations, setUsedLocations] = useState(0);
  const [warehouseLayout, setWarehouseLayout] = useState<WarehouseLayoutConfiguration | null>(null);
  const [mapVisible, setMapVisible] = useState(false);
  const [mapFloorId, setMapFloorId] = useState('');
  const [mapRackId, setMapRackId] = useState('');

  // 从批次数据计算库位信息
  const loadLocationData = useCallback(async () => {
    try {
      const [response, layout] = await Promise.all([
        materialBatchApiClient.getMaterialBatches({ status: 'available', size: 200 }),
        warehouseLayoutApiClient.getLayout(),
      ]) as [
        { data?: { content?: MaterialBatch[] } | MaterialBatch[] },
        Awaited<ReturnType<typeof warehouseLayoutApiClient.getLayout>>,
      ];

      const allBatches: MaterialBatch[] = (response.data as { content?: MaterialBatch[] })?.content || response.data as MaterialBatch[] || [];
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
          {warehouseLayout && warehouseLayout.floors.length > 0 && (
            <Button
              mode="contained"
              icon="floor-plan"
              onPress={() => setMapVisible(true)}
              style={[styles.actionButton, { marginBottom: 12 }]}
            >
              仓库平面图
            </Button>
          )}
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
        <SafeAreaView style={styles.mapContainer}>
          <View style={styles.mapHeader}>
            <TouchableOpacity onPress={() => setMapVisible(false)}><MaterialCommunityIcons name="arrow-left" size={24} color="#17243a" /></TouchableOpacity>
            <Text style={styles.mapTitle}>仓库平面图</Text>
            <View style={{ width: 24 }} />
          </View>
          <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.floorTabs}>
            {warehouseLayout?.floors.map((floor) => (
              <TouchableOpacity key={floor.id} onPress={() => { setMapFloorId(floor.id); setMapRackId(''); }} style={[styles.floorTab, floor.id === mapFloorId && styles.floorTabActive]}>
                <Text style={styles.floorTabText}>{floor.name}</Text>
              </TouchableOpacity>
            ))}
          </ScrollView>
          <ScrollView horizontal style={styles.mapCanvasScroll}>
            <View style={styles.mapCanvas}>
              {mapFloor?.racks.map((rack) => (
                <TouchableOpacity key={rack.id} onPress={() => setMapRackId(rack.id)} style={[styles.mapRack, { left: rack.x, top: rack.y, width: rack.width, minHeight: rack.height }, rack.id === mapRackId && styles.mapRackActive]}>
                  <Text style={styles.mapRackName}>{rack.name}</Text>
                  <Text style={styles.mapRackMeta}>{warehouseLayout?.zones.find((zone) => zone.id === rack.zoneId)?.prefix} · {rack.levels} 层</Text>
                </TouchableOpacity>
              ))}
            </View>
          </ScrollView>
          {mapRack && (
            <View style={styles.mapShelfPanel}>
              <Text style={styles.mapShelfTitle}>{mapRack.name}</Text>
              {Array.from({ length: mapRack.levels }, (_, index) => mapRack.levels - index).map((level) => (
                <View key={level} style={styles.mapShelfRow}>
                  <Text style={styles.mapShelfLevel}>{level}F</Text>
                  <Text style={styles.mapShelfCodes}>{warehouseLayout?.locations.filter((location) => location.rackId === mapRack.id && location.level === level).map((location) => location.code).join('  ·  ') || '空闲'}</Text>
                </View>
              ))}
            </View>
          )}
        </SafeAreaView>
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
    paddingHorizontal: 16,
    paddingTop: 16,
    gap: 12,
  },
  zoneCard: {
    flex: 1,
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
  mapContainer: { flex: 1, backgroundColor: '#f6f8fb' },
  mapHeader: { height: 56, paddingHorizontal: 18, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', backgroundColor: '#fff' },
  mapTitle: { fontSize: 18, fontWeight: '600', color: '#17243a' },
  floorTabs: { maxHeight: 58, paddingHorizontal: 14, paddingTop: 10 },
  floorTab: { paddingHorizontal: 18, paddingVertical: 9, borderRadius: 9, marginRight: 9, backgroundColor: '#fff' },
  floorTabActive: { backgroundColor: '#dbeafe' },
  floorTabText: { fontSize: 14, color: '#17243a' },
  mapCanvasScroll: { flex: 1, margin: 14, backgroundColor: '#fff', borderRadius: 12 },
  mapCanvas: { width: 900, minHeight: 420, position: 'relative' },
  mapRack: { position: 'absolute', padding: 12, borderWidth: 1, borderColor: '#bfdbfe', backgroundColor: '#eff6ff', borderRadius: 10 },
  mapRackActive: { borderWidth: 2, borderColor: '#2563eb' },
  mapRackName: { color: '#17243a', fontSize: 14, fontWeight: '600' },
  mapRackMeta: { color: '#607089', fontSize: 12, marginTop: 5 },
  mapShelfPanel: { marginHorizontal: 14, marginBottom: 14, padding: 14, backgroundColor: '#fff', borderRadius: 12 },
  mapShelfTitle: { fontSize: 16, fontWeight: '600', marginBottom: 8 },
  mapShelfRow: { minHeight: 35, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1, borderBottomColor: '#e5eaf2' },
  mapShelfLevel: { width: 40, color: '#607089', fontSize: 13 },
  mapShelfCodes: { color: '#17243a', fontSize: 13 },
});

export default WHLocationManageScreen;
