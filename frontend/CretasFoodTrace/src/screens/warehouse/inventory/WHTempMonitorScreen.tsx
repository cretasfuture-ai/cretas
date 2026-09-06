/**
 * 温控监控页面
 * 对应原型: warehouse/temperature-monitor.html
 */

import React, { useState, useEffect, useCallback } from "react";
import { appAlert } from '../../../components/ui/AppDialog';
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  ActivityIndicator,
} from "react-native";
import { Text, Surface, Button, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { alertApiClient, AlertDTO } from "../../../services/api/alertApiClient";
import { handleError } from "../../../utils/errorHandler";
import { formatShortDateTime, formatTimeFull } from "../../../utils/formatters";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

interface TempZone {
  id: string;
  name: string;
  currentTemp: number;
  targetMin: number;
  targetMax: number;
  status: "normal" | "warning" | "danger";
  maxTemp24h: number;
  minTemp24h: number;
  avgTemp24h: number;
}

interface AlertRecord {
  id: string;
  time: string;
  level: "warning" | "success" | "info";
  title: string;
  description: string;
  handled?: string;
}

interface ThresholdConfig {
  zone: string;
  upperLimit: string;
  lowerLimit: string;
}

export function WHTempMonitorScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();

  // 状态管理
  const [loading, setLoading] = useState(true);
  const [lastUpdate, setLastUpdate] = useState("");
  const [alertRecords, setAlertRecords] = useState<AlertRecord[]>([]);

  /**
   * 🔴 2026-08-26 真机实测: 这里原来写死了两组温度读数
   * (A区 2.5°C / B区 -18.2°C, 含 24h 最高/最低/平均), 而本屏**没有任何温度 API 调用** ——
   * 也就是把一组编造的数字, 配上绿色「正常」徽章和一个实时刷新的「最后更新」时间戳,
   * 当成真实冷链读数展示给仓管。违反「禁止降级处理 - 不返回假数据, 明确显示错误」。
   *
   * ⚠️ 它此前藏在被 SIMPLIFIED_WAREHOUSE_NAV 隐藏的库存 Tab 后面(仓管够不着);
   * 2026-08-26 接通「仓库地图」后, 本屏的入口变得可达 —— 一个潜伏缺陷被新入口激活。
   *
   * ⇒ 在真的接上温控设备 API 之前, 这里保持空: 空态说「未接入」是真的,
   *   假读数会让人以为冷链在被监控。下方**告警记录**走的是真实
   *   alertApiClient.getEquipmentAlerts, 保留不动。
   */
  const zones: TempZone[] = [];
  const thresholds: ThresholdConfig[] = [];

  // 加载告警记录
  const loadAlertData = useCallback(async () => {
    try {
      const response = await alertApiClient.getEquipmentAlerts({
        page: 1,
        size: 10,
      });

      const alerts = response.data?.content || [];

      // 转换为告警记录格式
      const records: AlertRecord[] = alerts.map((alert: AlertDTO) => {
        // 根据告警状态确定level
        let level: 'warning' | 'success' | 'info' = 'info';
        if (alert.status === 'RESOLVED') {
          level = 'success';
        } else if (alert.level === 'CRITICAL') {
          level = 'warning';
        }

        // 格式化时间
        const date = new Date(alert.triggeredAt || Date.now());
        const timeStr = `${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`;

        return {
          id: String(alert.id),
          time: timeStr,
          level,
          title: alert.alertType || '温度告警',
          description: alert.message || '-',
          handled: alert.status === 'RESOLVED' ? `已处理: ${alert.resolutionNotes || '问题已解决'}` : undefined,
        };
      });

      setAlertRecords(records.length > 0 ? records : [
        {
          id: "1",
          time: formatShortDateTime(new Date()),
          level: "info",
          title: "系统正常",
          description: "暂无温度异常告警",
        }
      ]);

      // 更新时间
      const now = new Date();
      setLastUpdate(`${String(now.getHours()).padStart(2, '0')}:${String(now.getMinutes()).padStart(2, '0')}:${String(now.getSeconds()).padStart(2, '0')}`);

    } catch (error) {
      handleError(error, { title: '加载告警数据失败' });
      // 使用默认告警记录
      setAlertRecords([
        {
          id: "1",
          time: formatShortDateTime(new Date()),
          level: "info",
          title: "暂无告警",
          description: "温度监控系统运行正常",
        }
      ]);
      setLastUpdate(formatTimeFull(new Date()));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    loadAlertData();
  }, [loadAlertData]);

  const getStatusBadgeStyle = (status: TempZone["status"]) => {
    switch (status) {
      case "normal":
        return { bg: "#e8f5e9", color: "#4CAF50" };
      case "warning":
        return { bg: "#fff3e0", color: "#f57c00" };
      case "danger":
        return { bg: "#ffebee", color: "#f44336" };
    }
  };

  const getAlertLevelStyle = (level: AlertRecord["level"]) => {
    switch (level) {
      case "warning":
        return { bg: "#fff3e0", color: "#f57c00", border: "#f57c00" };
      case "success":
        return { bg: "#e8f5e9", color: "#4CAF50", border: "#4CAF50" };
      case "info":
        return { bg: "#e3f2fd", color: "#1976d2", border: "#1976d2" };
    }
  };

  const getAlertLevelText = (level: AlertRecord["level"]) => {
    switch (level) {
      case "warning":
        return "警告";
      case "success":
        return "恢复";
      case "info":
        return "通知";
    }
  };

  const handleExport = () => {
    appAlert("成功", "温度报表已导出");
  };

  const handleSetThreshold = () => {
    appAlert("提示", "告警阈值设置功能");
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
            <Text style={styles.headerTitle}>温控监控</Text>
            <Text style={styles.headerSubtitle}>实时温度监控</Text>
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

  const renderZoneCard = (zone: TempZone) => {
    const statusStyle = getStatusBadgeStyle(zone.status);

    return (
      <View key={zone.id} style={styles.section}>
        <Text style={styles.sectionTitle}>{zone.name}</Text>

        <View style={styles.tempZoneDetail}>
          <View style={styles.tempMain}>
            <View style={styles.tempCurrent}>
              <Text style={styles.tempValueLarge}>{zone.currentTemp}</Text>
              <Text style={styles.tempUnitLarge}>°C</Text>
            </View>
            <View
              style={[styles.statusBadge, { backgroundColor: statusStyle.bg }]}
            >
              <Text style={[styles.statusText, { color: statusStyle.color }]}>
                {zone.status === "normal" ? "正常" : "异常"}
              </Text>
            </View>
          </View>

          <Text style={styles.tempRange}>
            目标范围: {zone.targetMin}°C ~ {zone.targetMax}°C
          </Text>

          <View style={styles.tempChart}>
            <Text style={styles.chartLine}>▁▂▃▂▁▂▃▂▁▂▃▂▁▂▃▂▁▂▃▂▁▂▃▂</Text>
            <Text style={styles.chartLabel}>24小时温度趋势</Text>
          </View>

          <View style={styles.tempStats}>
            <View style={styles.tempStat}>
              <Text style={styles.tempStatLabel}>最高</Text>
              <Text style={styles.tempStatValue}>{zone.maxTemp24h}°C</Text>
            </View>
            <View style={styles.tempStat}>
              <Text style={styles.tempStatLabel}>最低</Text>
              <Text style={styles.tempStatValue}>{zone.minTemp24h}°C</Text>
            </View>
            <View style={styles.tempStat}>
              <Text style={styles.tempStatLabel}>平均</Text>
              <Text style={styles.tempStatValue}>{zone.avgTemp24h}°C</Text>
            </View>
          </View>
        </View>
      </View>
    );
  };

  const renderAlertRecord = (record: AlertRecord) => {
    const levelStyle = getAlertLevelStyle(record.level);

    return (
      <View
        key={record.id}
        style={[
          styles.alertRecord,
          { borderLeftColor: levelStyle.border },
        ]}
      >
        <View style={styles.alertRecordHeader}>
          <Text style={styles.alertRecordTime}>{record.time}</Text>
          <View
            style={[styles.alertLevelBadge, { backgroundColor: levelStyle.bg }]}
          >
            <Text style={[styles.alertLevelText, { color: levelStyle.color }]}>
              {getAlertLevelText(record.level)}
            </Text>
          </View>
        </View>
        <View style={styles.alertRecordContent}>
          <Text style={styles.alertRecordTitle}>{record.title}</Text>
          <Text style={styles.alertRecordDesc}>{record.description}</Text>
        </View>
        {record.handled && (
          <View style={styles.alertRecordAction}>
            <Text style={styles.alertHandled}>{record.handled}</Text>
          </View>
        )}
      </View>
    );
  };

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
          <Text style={styles.headerTitle}>温控监控</Text>
          <Text style={styles.headerSubtitle}>实时温度监控</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 系统状态 —— ⛔ 没有温控设备时不许说「系统在线」, 那句话本身就是个假读数 */}
        {zones.length > 0 && (
          <View style={styles.monitorStatus}>
            <View style={styles.statusIndicator} />
            <Text style={styles.statusTextOnline}>系统在线</Text>
            <Text style={styles.statusTime}>最后更新: {lastUpdate}</Text>
          </View>
        )}

        {/* 温区详情 */}
        {zones.length > 0 ? (
          zones.map(renderZoneCard)
        ) : (
          <View style={styles.section} testID="wh-temp-monitor-empty">
            <Text style={styles.sectionTitle}>温控设备未接入</Text>
            <Text style={styles.emptyHint}>
              本工厂还没有接入温控设备，这里不显示温度读数。
            </Text>
            <Text style={styles.emptyHint}>
              下方的告警记录来自真实设备告警接口，可正常查看。
            </Text>
          </View>
        )}

        {/* 告警记录 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>告警记录</Text>
          {alertRecords.map(renderAlertRecord)}
        </View>

        {/* 告警阈值设置 —— 没有温区就没有阈值可谈, 不显示空壳 */}
        {thresholds.length > 0 && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>告警阈值设置</Text>
          <View style={styles.thresholdList}>
            {thresholds.map((threshold, index) => (
              <View key={index} style={styles.thresholdItem}>
                <Text style={styles.thresholdZone}>{threshold.zone}</Text>
                <View style={styles.thresholdRange}>
                  <Text style={styles.thresholdText}>
                    上限: {threshold.upperLimit}
                  </Text>
                  <Text style={styles.thresholdText}>
                    下限: {threshold.lowerLimit}
                  </Text>
                </View>
              </View>
            ))}
          </View>
        </View>
        )}

        {/* 操作按钮 —— 只在真的有温区时出现。
            🔴 2026-08-26: 上一批把温区读数改成诚实空态之后, 这两个按钮还留在原地,
            于是同一屏上面写「温控设备未接入」, 下面「导出报表」点一下弹「成功·温度报表已导出」——
            自相矛盾, 而且那句「成功」本身就是假的: handleExport / handleSetThreshold
            都只有一句 Alert, 没有任何实现。修一半比不修更容易骗到人。 */}
        {zones.length > 0 && (
        <View style={styles.actionButtons}>
          <Button
            mode="outlined"
            onPress={handleExport}
            style={styles.actionBtnSecondary}
            labelStyle={{ color: "#666" }}
          >
            导出报表
          </Button>
          <Button
            mode="contained"
            onPress={handleSetThreshold}
            style={styles.actionBtnPrimary}
            labelStyle={{ color: "#fff" }}
          >
            设置阈值
          </Button>
        </View>
        )}

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
  monitorStatus: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 8,
    padding: 12,
  },
  statusIndicator: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: "#4CAF50",
    marginRight: 8,
  },
  statusTextOnline: {
    fontSize: 14,
    fontWeight: "500",
    color: "#4CAF50",
    marginRight: 12,
  },
  statusTime: {
    fontSize: 12,
    color: "#999",
  },
  section: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 12,
    borderRadius: 12,
    padding: 16,
  },
  emptyHint: {
    marginTop: 8,
    fontSize: 14,
    lineHeight: 21,
    color: '#667085',
  },
  sectionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#999",
    marginBottom: 12,
  },
  tempZoneDetail: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 16,
  },
  tempMain: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    marginBottom: 8,
  },
  tempCurrent: {
    flexDirection: "row",
    alignItems: "flex-start",
  },
  tempValueLarge: {
    fontSize: 36,
    fontWeight: "bold",
    color: "#333",
  },
  tempUnitLarge: {
    fontSize: 18,
    color: "#666",
    marginTop: 4,
  },
  statusBadge: {
    paddingHorizontal: 12,
    paddingVertical: 4,
    borderRadius: 12,
  },
  statusText: {
    fontSize: 12,
    fontWeight: "500",
  },
  tempRange: {
    fontSize: 13,
    color: "#666",
    marginBottom: 12,
  },
  tempChart: {
    alignItems: "center",
    backgroundColor: "#e8f5e9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
  },
  chartLine: {
    fontSize: 16,
    color: "#4CAF50",
    letterSpacing: 2,
  },
  chartLabel: {
    fontSize: 11,
    color: "#999",
    marginTop: 4,
  },
  tempStats: {
    flexDirection: "row",
    justifyContent: "space-around",
  },
  tempStat: {
    alignItems: "center",
  },
  tempStatLabel: {
    fontSize: 12,
    color: "#999",
    marginBottom: 4,
  },
  tempStatValue: {
    fontSize: 15,
    fontWeight: "600",
    color: "#333",
  },
  alertRecord: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
    borderLeftWidth: 3,
  },
  alertRecordHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 8,
  },
  alertRecordTime: {
    fontSize: 12,
    color: "#999",
  },
  alertLevelBadge: {
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  alertLevelText: {
    fontSize: 11,
    fontWeight: "500",
  },
  alertRecordContent: {
    marginBottom: 8,
  },
  alertRecordTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
    marginBottom: 2,
  },
  alertRecordDesc: {
    fontSize: 13,
    color: "#666",
  },
  alertRecordAction: {},
  alertHandled: {
    fontSize: 12,
    color: "#4CAF50",
  },
  thresholdList: {
    gap: 8,
  },
  thresholdItem: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
  },
  thresholdZone: {
    fontSize: 13,
    fontWeight: "600",
    color: "#333",
    marginBottom: 6,
  },
  thresholdRange: {
    flexDirection: "row",
    gap: 16,
  },
  thresholdText: {
    fontSize: 12,
    color: "#666",
  },
  actionButtons: {
    flexDirection: "row",
    paddingHorizontal: 16,
    marginTop: 16,
    gap: 12,
  },
  actionBtnSecondary: {
    flex: 1,
    borderRadius: 8,
    borderColor: "#ddd",
  },
  actionBtnPrimary: {
    flex: 1,
    borderRadius: 8,
    backgroundColor: "#4CAF50",
  },
});

export default WHTempMonitorScreen;
