/**
 * 召回管理页面
 * 对应原型: warehouse/recall-manage.html
 */

import React, { useState } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
} from "react-native";
import { Text, Button, ProgressBar, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { WHInventoryStackParamList } from "../../../types/navigation";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;

interface RecallItem {
  id: string;
  number: string;
  level: "urgent" | "normal";
  status: "processing" | "completed";
  reason: string;
  batch: string;
  quantity: number;
  startTime: string;
  completeTime?: string;
  progress?: number;
  scope?: {
    icon: string;
    label: string;
    value: string;
    status: "success" | "warning";
  }[];
}

interface FlowStep {
  number: number;
  title: string;
  description: string;
  status: "completed" | "active" | "pending";
}

export function WHRecallManageScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();

  const [selectedTab, setSelectedTab] = useState("active");

  // 🔴 召回记录：**这一屏没有接任何接口**，下面两组原来是写死的假记录。
  //
  // 原来屏上显示的是：「进行中(1) RC-20251226-001 · 紧急 · 执行中 /
  // 召回原因 供应商质检问题 / 涉及 200kg / 发起时间 2025-12-26 09:00 /
  // 追溯范围：库存在库 50kg 已冻结、已发货 100kg → 3家客户 通知中」，
  // 外加一张把「冻结库存」「通知客户」标成**已完成**的流程图。
  //
  // ⛔ 这是整套走查里最危险的一屏：它在告诉仓管「有一次紧急召回正在执行、
  //    客户已经在通知了」。看到它的人可能因此**不去发起真正需要的召回**，
  //    也可能拿它当已经处置过的凭据。食品追溯产品里这不是「界面占位」问题。
  //
  // 在召回真的落库之前，这两组必须是空的，且空态要说清是「还没接入」，
  // ⛔ 不是「没有召回」—— 后者是一句我们此刻答不上来的话。
  const activeRecalls: RecallItem[] = [];
  const completedRecalls: RecallItem[] = [];

  // ⚠️ 这两个数原来是写死的 "(1)" 和 "(3)" —— 跟着上面那三条假记录一起编的。
  //    删了记录还留着计数，屏上就会出现「进行中(1)」配一句「尚未接入」。
  //    ⇒ 计数一律由列表长度算出来，⛔ 不写字面量。
  const tabs = [
    { key: "active", label: `进行中(${activeRecalls.length})` },
    { key: "completed", label: `已完成(${completedRecalls.length})` },
    { key: "create", label: "新建召回" },
  ];


  // 这是一张**流程说明**（召回该怎么走），⛔ 不是某一次召回的进度。
  // 原来第 1、2 步被标成 completed、第 3 步 active，于是它读起来像
  // 「这次召回已经冻结库存并通知了客户」。全部改成 pending（中性）。
  const flowSteps: FlowStep[] = [
    { number: 1, title: "冻结库存", description: "锁定问题批次", status: "pending" },
    { number: 2, title: "通知客户", description: "发送召回通知", status: "pending" },
    { number: 3, title: "回收产品", description: "客户退货处理", status: "pending" },
    { number: 4, title: "处置记录", description: "销毁/处理", status: "pending" },
    { number: 5, title: "原因分析", description: "根因分析报告", status: "pending" },
    { number: 6, title: "关闭召回", description: "完成闭环", status: "pending" },
  ];

  // ⛔ 这个按钮原来弹一句「选择需要召回的批次」就没有下文了 —— 它不发起任何东西。
  //    在召回真的能落库之前，说清楚它做不到，比让人以为自己已经发起了召回安全得多。
  const handleCreateRecall = () => {
    Alert.alert(
      "召回功能尚未接入",
      "这一页还不能发起召回。需要召回请走线下流程并联系质量负责人，⛔ 不要以为在这里点过就已经发起了。",
    );
  };

  const getStepStyle = (status: FlowStep["status"]) => {
    switch (status) {
      case "completed":
        return { bg: "#4CAF50", color: "#fff", connector: "#4CAF50" };
      case "active":
        return { bg: "#1976d2", color: "#fff", connector: "#e0e0e0" };
      case "pending":
        return { bg: "#e0e0e0", color: "#999", connector: "#e0e0e0" };
    }
  };

  const renderActiveRecall = (recall: RecallItem) => (
    <View key={recall.id} style={styles.recallCardActive}>
      <View style={styles.recallHeader}>
        <View style={styles.recallInfo}>
          <Text style={styles.recallNumber}>{recall.number}</Text>
          <View
            style={[
              styles.recallLevel,
              recall.level === "urgent" && styles.recallLevelUrgent,
            ]}
          >
            <Text style={styles.recallLevelText}>
              {recall.level === "urgent" ? "紧急" : "一般"}
            </Text>
          </View>
        </View>
        <View style={styles.recallStatus}>
          <Text style={styles.recallStatusText}>执行中</Text>
        </View>
      </View>

      <View style={styles.recallContent}>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>召回原因</Text>
          <Text style={styles.recallValue}>{recall.reason}</Text>
        </View>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>涉及批次</Text>
          <Text style={styles.recallValue}>{recall.batch}</Text>
        </View>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>涉及数量</Text>
          <Text style={[styles.recallValue, { color: "#f44336" }]}>
            {recall.quantity} kg
          </Text>
        </View>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>发起时间</Text>
          <Text style={styles.recallValue}>{recall.startTime}</Text>
        </View>
      </View>

      {recall.scope && (
        <View style={styles.recallScope}>
          <Text style={styles.recallScopeTitle}>追溯范围</Text>
          {recall.scope.map((item, index) => (
            <View key={index} style={styles.scopeItem}>
              <View style={styles.scopeIcon}>
                <Text style={styles.scopeIconText}>{item.icon}</Text>
              </View>
              <View style={styles.scopeContent}>
                <Text style={styles.scopeLabel}>{item.label}</Text>
                <Text style={styles.scopeValue}>{item.value}</Text>
              </View>
              <View
                style={[
                  styles.scopeStatus,
                  item.status === "success"
                    ? styles.scopeStatusSuccess
                    : styles.scopeStatusWarning,
                ]}
              >
                <Text
                  style={[
                    styles.scopeStatusText,
                    item.status === "success"
                      ? styles.scopeStatusTextSuccess
                      : styles.scopeStatusTextWarning,
                  ]}
                >
                  {item.status === "success" ? "已冻结" : "通知中"}
                </Text>
              </View>
            </View>
          ))}
        </View>
      )}

      {recall.progress !== undefined && (
        <View style={styles.recallProgress}>
          <View style={styles.progressHeader}>
            <Text style={styles.progressLabel}>处理进度</Text>
            <Text style={styles.progressValue}>{Math.round(recall.progress * 100)}%</Text>
          </View>
          <ProgressBar
            progress={recall.progress}
            color="#4CAF50"
            style={styles.progressBar}
          />
        </View>
      )}

      <View style={styles.recallActions}>
        <Button
          mode="outlined"
          compact
          onPress={() => {}}
          style={styles.recallBtnSecondary}
          labelStyle={{ color: "#666", fontSize: 12 }}
        >
          查看详情
        </Button>
        <Button
          mode="contained"
          compact
          onPress={() => {}}
          style={styles.recallBtnPrimary}
          labelStyle={{ color: "#fff", fontSize: 12 }}
        >
          继续处理
        </Button>
      </View>
    </View>
  );

  const renderCompletedRecall = (recall: RecallItem) => (
    <View key={recall.id} style={styles.recallCard}>
      <View style={styles.recallHeader}>
        <View style={styles.recallInfo}>
          <Text style={styles.recallNumber}>{recall.number}</Text>
          <View style={styles.recallLevel}>
            <Text style={styles.recallLevelTextNormal}>
              {recall.level === "urgent" ? "紧急" : "一般"}
            </Text>
          </View>
        </View>
        <View style={styles.recallStatusCompleted}>
          <Text style={styles.recallStatusTextCompleted}>已完成</Text>
        </View>
      </View>
      <View style={styles.recallContent}>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>召回原因</Text>
          <Text style={styles.recallValue}>{recall.reason}</Text>
        </View>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>涉及数量</Text>
          <Text style={styles.recallValue}>{recall.quantity} kg</Text>
        </View>
        <View style={styles.recallRow}>
          <Text style={styles.recallLabel}>完成时间</Text>
          <Text style={styles.recallValue}>{recall.completeTime}</Text>
        </View>
      </View>
    </View>
  );

  const renderFlowStep = (step: FlowStep, index: number) => {
    const stepStyle = getStepStyle(step.status);
    const isLast = index === flowSteps.length - 1;

    return (
      <View key={step.number} style={styles.flowStepContainer}>
        <View style={styles.flowStepRow}>
          <View style={[styles.flowNumber, { backgroundColor: stepStyle.bg }]}>
            {step.status === "completed" ? (
              <MaterialCommunityIcons name="check" size={14} color="#fff" />
            ) : (
              <Text style={[styles.flowNumberText, { color: stepStyle.color }]}>
                {step.number}
              </Text>
            )}
          </View>
          <View style={styles.flowContent}>
            <Text
              style={[
                styles.flowTitle,
                step.status === "pending" && styles.flowTitlePending,
              ]}
            >
              {step.title}
            </Text>
            <Text style={styles.flowDesc}>{step.description}</Text>
          </View>
        </View>
        {!isLast && (
          <View
            style={[styles.flowConnector, { backgroundColor: stepStyle.connector }]}
          />
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
          <Text style={styles.headerTitle}>召回管理</Text>
          <Text style={styles.headerSubtitle}>产品召回追溯</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 筛选标签 */}
        <View style={styles.filterTabs}>
          {tabs.map((tab) => (
            <TouchableOpacity
              key={tab.key}
              style={[
                styles.filterTab,
                selectedTab === tab.key && styles.filterTabActive,
              ]}
              onPress={() => {
                if (tab.key === "create") {
                  handleCreateRecall();
                } else {
                  setSelectedTab(tab.key);
                }
              }}
            >
              <Text
                style={[
                  styles.filterTabText,
                  selectedTab === tab.key && styles.filterTabTextActive,
                ]}
              >
                {tab.label}
              </Text>
            </TouchableOpacity>
          ))}
        </View>

        {selectedTab === "active" && (
          <>
            {/* 进行中的召回 */}
            <View style={styles.section}>
              <Text style={[styles.sectionTitle, styles.sectionTitleDanger]}>
                进行中的召回
              </Text>
              {activeRecalls.length > 0 ? (
                activeRecalls.map(renderActiveRecall)
              ) : (
                <View style={styles.notWired} testID="wh-recall-not-wired">
                  <MaterialCommunityIcons name="database-off-outline" size={40} color="#bbb" />
                  <Text style={styles.notWiredTitle}>召回记录尚未接入</Text>
                  <Text style={styles.notWiredBody}>
                    这一页还没有连上召回数据，⛔ 不能据此判断「当前没有召回」。
                    需要确认是否有正在进行的召回，请走线下流程或联系质量负责人。
                  </Text>
                </View>
              )}
            </View>

            {/* 召回流程说明 */}
            <View style={styles.section}>
              <Text style={styles.sectionTitle}>召回流程</Text>
              <View style={styles.flowContainer}>
                {flowSteps.map((step, index) => renderFlowStep(step, index))}
              </View>
            </View>
          </>
        )}

        {selectedTab === "completed" && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>历史召回记录</Text>
            {completedRecalls.length > 0 ? (
              completedRecalls.map(renderCompletedRecall)
            ) : (
              <View style={styles.notWired} testID="wh-recall-history-not-wired">
                <MaterialCommunityIcons name="database-off-outline" size={40} color="#bbb" />
                <Text style={styles.notWiredTitle}>召回记录尚未接入</Text>
                <Text style={styles.notWiredBody}>
                  历史召回同样没有连上数据源，这里的空白不代表历史上没有发生过召回。
                </Text>
              </View>
            )}
          </View>
        )}

        {/* 操作按钮 */}
        <View style={styles.actionButtons}>
          <Button
            mode="contained"
            onPress={handleCreateRecall}
            style={styles.actionBtnPrimary}
            labelStyle={{ color: "#fff" }}
            icon="plus"
          >
            发起新召回
          </Button>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  // 「尚未接入」的占位：中性配色，⛔ 不要用告警红 —— 没接上不是一次告警。
  notWired: {
    alignItems: "center",
    backgroundColor: "#fafafa",
    borderColor: "#e0e0e0",
    borderRadius: 8,
    borderStyle: "dashed",
    borderWidth: 1,
    paddingHorizontal: 16,
    paddingVertical: 24,
  },
  notWiredTitle: {
    color: "#616161",
    fontSize: 15,
    fontWeight: "600",
    marginTop: 8,
  },
  notWiredBody: {
    color: "#9e9e9e",
    fontSize: 13,
    lineHeight: 20,
    marginTop: 6,
    textAlign: "center",
  },

  container: {
    flex: 1,
    backgroundColor: "#f5f5f5",
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
  filterTabs: {
    flexDirection: "row",
    backgroundColor: "#fff",
    paddingHorizontal: 16,
    paddingVertical: 12,
    gap: 8,
  },
  filterTab: {
    flex: 1,
    paddingVertical: 10,
    borderRadius: 8,
    backgroundColor: "#f5f5f5",
    alignItems: "center",
  },
  filterTabActive: {
    backgroundColor: "#4CAF50",
  },
  filterTabText: {
    fontSize: 13,
    color: "#666",
  },
  filterTabTextActive: {
    color: "#fff",
    fontWeight: "500",
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
  sectionTitleDanger: {
    color: "#f44336",
  },
  recallCardActive: {
    backgroundColor: "#fff",
    borderRadius: 8,
    padding: 12,
    borderWidth: 1,
    borderColor: "#ffcdd2",
  },
  recallCard: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 10,
  },
  recallHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    marginBottom: 12,
  },
  recallInfo: {
    flexDirection: "row",
    alignItems: "center",
    gap: 8,
  },
  recallNumber: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  recallLevel: {
    backgroundColor: "#f5f5f5",
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  recallLevelUrgent: {
    backgroundColor: "#f44336",
  },
  recallLevelText: {
    fontSize: 11,
    color: "#fff",
    fontWeight: "500",
  },
  recallLevelTextNormal: {
    fontSize: 11,
    color: "#666",
  },
  recallStatus: {
    backgroundColor: "#fff3e0",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 4,
  },
  recallStatusText: {
    fontSize: 12,
    color: "#f57c00",
    fontWeight: "500",
  },
  recallStatusCompleted: {
    backgroundColor: "#e8f5e9",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 4,
  },
  recallStatusTextCompleted: {
    fontSize: 12,
    color: "#4CAF50",
    fontWeight: "500",
  },
  recallContent: {
    gap: 6,
    marginBottom: 12,
  },
  recallRow: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  recallLabel: {
    fontSize: 13,
    color: "#666",
  },
  recallValue: {
    fontSize: 13,
    color: "#333",
  },
  recallScope: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    marginBottom: 12,
  },
  recallScopeTitle: {
    fontSize: 12,
    fontWeight: "600",
    color: "#666",
    marginBottom: 10,
  },
  scopeItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 8,
    borderBottomWidth: 1,
    borderBottomColor: "#f0f0f0",
  },
  scopeIcon: {
    width: 24,
    height: 24,
    borderRadius: 12,
    backgroundColor: "#e0e0e0",
    alignItems: "center",
    justifyContent: "center",
    marginRight: 10,
  },
  scopeIconText: {
    fontSize: 11,
    fontWeight: "bold",
    color: "#666",
  },
  scopeContent: {
    flex: 1,
  },
  scopeLabel: {
    fontSize: 12,
    color: "#999",
  },
  scopeValue: {
    fontSize: 13,
    color: "#333",
    fontWeight: "500",
  },
  scopeStatus: {
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: 4,
  },
  scopeStatusSuccess: {
    backgroundColor: "#e8f5e9",
  },
  scopeStatusWarning: {
    backgroundColor: "#fff3e0",
  },
  scopeStatusText: {
    fontSize: 11,
    fontWeight: "500",
  },
  scopeStatusTextSuccess: {
    color: "#4CAF50",
  },
  scopeStatusTextWarning: {
    color: "#f57c00",
  },
  recallProgress: {
    marginBottom: 12,
  },
  progressHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    marginBottom: 6,
  },
  progressLabel: {
    fontSize: 12,
    color: "#666",
  },
  progressValue: {
    fontSize: 12,
    fontWeight: "600",
    color: "#4CAF50",
  },
  progressBar: {
    height: 6,
    borderRadius: 3,
    backgroundColor: "#e0e0e0",
  },
  recallActions: {
    flexDirection: "row",
    justifyContent: "flex-end",
    gap: 8,
  },
  recallBtnSecondary: {
    borderRadius: 6,
    borderColor: "#ddd",
  },
  recallBtnPrimary: {
    borderRadius: 6,
    backgroundColor: "#4CAF50",
  },
  flowContainer: {
    paddingLeft: 8,
  },
  flowStepContainer: {
    marginBottom: 4,
  },
  flowStepRow: {
    flexDirection: "row",
    alignItems: "flex-start",
  },
  flowNumber: {
    width: 24,
    height: 24,
    borderRadius: 12,
    alignItems: "center",
    justifyContent: "center",
    marginRight: 12,
  },
  flowNumberText: {
    fontSize: 12,
    fontWeight: "bold",
  },
  flowContent: {
    flex: 1,
    paddingTop: 2,
  },
  flowTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  flowTitlePending: {
    color: "#999",
  },
  flowDesc: {
    fontSize: 12,
    color: "#999",
    marginTop: 2,
  },
  flowConnector: {
    width: 2,
    height: 20,
    marginLeft: 11,
    marginVertical: 4,
  },
  actionButtons: {
    paddingHorizontal: 16,
    marginTop: 16,
  },
  actionBtnPrimary: {
    borderRadius: 8,
    backgroundColor: "#4CAF50",
  },
});

export default WHRecallManageScreen;
