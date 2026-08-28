/**
 * 预警处理页面
 * 对应原型: warehouse/alert-handle.html
 */

import React, { useState } from "react";
import {
  View,
  ScrollView,
  StyleSheet,
  TouchableOpacity,
  Alert,
} from "react-native";
import { Text, TextInput, Button, RadioButton, useTheme } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { useNavigation } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { Picker } from "@react-native-picker/picker";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { formatNumberWithCommas } from "../../../utils/formatters";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type MCIconName = React.ComponentProps<typeof MaterialCommunityIcons>['name'];

interface Solution {
  id: string;
  icon: MCIconName;
  title: string;
  recommended?: boolean;
  description: string[];
}

/**
 * 这一屏接上真实预警数据、并且真的能提交处理方案之后，把它翻成 true。
 *
 * ⛔ 在那之前不要翻 —— 下面整套表单用的是写死的「带鱼(鲜品) 85kg / 舟山渔业合作社 /
 * 建议采购 150kg」，而且提交按钮原来无论如何都弹「成功 · 处理方案已提交」，
 * 实际什么都没提交。假的成功回执比没有功能危险得多。
 */
const ALERT_HANDLE_WIRED_UP = false;

export function WHAlertHandleScreen() {
  const theme = useTheme();
  const navigation = useNavigation<NavigationProp>();

  const [selectedSolution, setSelectedSolution] = useState("purchase");
  const [purchaseQty, setPurchaseQty] = useState("150");
  const [supplier, setSupplier] = useState("supplier1");
  const [unitPrice, setUnitPrice] = useState("30");
  const [remarks, setRemarks] = useState("");
  const [freezeQty, setFreezeQty] = useState("85");
  const [targetLocation, setTargetLocation] = useState("b1");

  const alertInfo = {
    name: "带鱼 (鲜品)",
    batch: "MB-20251223-001",
    currentStock: 85,
    safeStock: 200,
    gap: 115,
    expireDate: "2025-12-29 (3天后)",
  };

  const solutions: Solution[] = [
    {
      id: "purchase",
      icon: "cart-plus",
      title: "紧急采购",
      recommended: true,
      description: [
        "建议采购量: 150 kg",
        "推荐供应商: 舟山渔业合作社",
        "预计到货: 1-2天",
      ],
    },
    {
      id: "freeze",
      icon: "snowflake",
      title: "转为冻品",
      description: [
        "延长保质期: +30天",
        "转移至: B区-冷冻库",
        "适用于即将过期的批次",
      ],
    },
    {
      id: "fifo",
      icon: "sort-numeric-ascending",
      title: "优先消耗 (FIFO)",
      description: [
        "关联生产计划: 3个",
        "标记为优先消耗批次",
        "系统自动推荐此批次",
      ],
    },
    {
      id: "dispose",
      icon: "delete-outline",
      title: "报损处理",
      description: [
        "预计损失: ¥2,550",
        "需审批: 仓储主管",
        "适用于已过期/变质物料",
      ],
    },
  ];

  const suppliers = [
    { label: "舟山渔业合作社 (推荐)", value: "supplier1" },
    { label: "东海水产", value: "supplier2" },
    { label: "宁波海鲜批发", value: "supplier3" },
  ];

  const targetLocations = [
    { label: "B区-冷冻库-01 (剩余200kg)", value: "b1" },
    { label: "B区-冷冻库-02 (剩余150kg)", value: "b2" },
  ];

  const calculateTotal = () => {
    const qty = parseFloat(purchaseQty) || 0;
    const price = parseFloat(unitPrice) || 0;
    return formatNumberWithCommas(qty * price);
  };

  // ⛔ 原来无论如何都弹「成功 · 处理方案已提交」—— 而它不发任何请求。
  const handleSubmit = () => {
    Alert.alert(
      "尚未接入",
      "处理方案没有被提交。这一页还没有连上后端，请走对应的采购单／库存调整流程。",
      [{ text: "知道了" }],
    );
  };

  const renderSolutionCard = (solution: Solution) => {
    const isSelected = selectedSolution === solution.id;

    return (
      <TouchableOpacity
        key={solution.id}
        style={[
          styles.solutionItem,
          isSelected && styles.solutionItemSelected,
          solution.recommended && styles.solutionItemRecommended,
        ]}
        onPress={() => setSelectedSolution(solution.id)}
      >
        <View style={styles.solutionContent}>
          <View style={styles.solutionHeader}>
            <View
              style={[
                styles.solutionIcon,
                isSelected && styles.solutionIconSelected,
              ]}
            >
              <MaterialCommunityIcons
                name={solution.icon}
                size={20}
                color={isSelected ? "#fff" : "#666"}
              />
            </View>
            <Text
              style={[
                styles.solutionTitle,
                isSelected && styles.solutionTitleSelected,
              ]}
            >
              {solution.title}
            </Text>
            {solution.recommended && (
              <View style={styles.recommendedTag}>
                <Text style={styles.recommendedTagText}>推荐</Text>
              </View>
            )}
          </View>
          <View style={styles.solutionDesc}>
            {solution.description.map((desc, index) => (
              <Text key={index} style={styles.solutionDescText}>
                {desc}
              </Text>
            ))}
          </View>
        </View>
        <RadioButton
          value={solution.id}
          status={isSelected ? "checked" : "unchecked"}
          onPress={() => setSelectedSolution(solution.id)}
          color="#4CAF50"
        />
      </TouchableOpacity>
    );
  };

  // 🔴 这一屏**没有接任何接口，也收不到任何参数**。
  //
  // 预警列表是这样跳过来的：`CommonActions.navigate("WHAlertHandle")` —— 不带 params。
  // 所以无论点的是哪一条预警，打开的都是同一份写死的内容：
  // 「带鱼(鲜品) MB-20251223-001 / 当前库存 85 / 安全库存 200 / 缺口 115 /
  //  过期 2025-12-29(3天后) / 推荐供应商 舟山渔业合作社 / 建议采购 150kg」。
  // F006 做的是黄油鸡，日期停在八个月前。
  //
  // ⛔ 更严重的是下面那个提交按钮：它弹「成功 · 处理方案已提交」然后返回，
  //    而**什么都没有提交**。仓管会以为紧急采购已经发起了。
  //    假的成功回执比没有功能危险得多。
  //
  // 在这一屏能拿到 alertId、并且真的能提交之前，只显示「尚未接入」。
  // 下面整套表单代码保留在文件里，接上接口时把这个早返回去掉即可。
  if (!ALERT_HANDLE_WIRED_UP) {
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
            <Text style={styles.headerTitle}>预警处理</Text>
            <Text style={styles.headerSubtitle}>处理库存预警</Text>
          </View>
          <View style={styles.headerRight} />
        </View>

        <View style={styles.notWired} testID="wh-alert-handle-not-wired">
          <MaterialCommunityIcons name="database-off-outline" size={48} color="#bbb" />
          <Text style={styles.notWiredTitle}>预警处理尚未接入</Text>
          <Text style={styles.notWiredBody}>
            这一页还没有连上预警数据，也无法提交处理方案。
          </Text>
          <Text style={styles.notWiredBody}>
            需要紧急采购、转冻品或报损，请走对应的采购单／库存调整流程。
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  // 以下为接上接口后恢复的原表单（把上面的常量翻成 true 即可）。
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
          <Text style={styles.headerTitle}>预警处理</Text>
          <Text style={styles.headerSubtitle}>处理库存预警</Text>
        </View>
        <View style={styles.headerRight} />
      </View>

      <ScrollView style={styles.content} showsVerticalScrollIndicator={false}>
        {/* 预警信息 */}
        <View style={styles.alertDetailCard}>
          <View style={styles.alertDetailHeader}>
            <MaterialCommunityIcons name="package-variant" size={24} color="#666" />
            <View style={styles.alertDetailInfo}>
              <Text style={styles.alertDetailName}>{alertInfo.name}</Text>
              <Text style={styles.alertDetailBatch}>{alertInfo.batch}</Text>
            </View>
            <View style={styles.alertDetailLevel}>
              <Text style={styles.alertDetailLevelText}>紧急</Text>
            </View>
          </View>
          <View style={styles.alertDetailContent}>
            <View style={styles.alertDetailRow}>
              <Text style={styles.alertDetailLabel}>当前库存</Text>
              <Text style={[styles.alertDetailValue, { color: "#f44336" }]}>
                {alertInfo.currentStock} kg
              </Text>
            </View>
            <View style={styles.alertDetailRow}>
              <Text style={styles.alertDetailLabel}>安全库存</Text>
              <Text style={styles.alertDetailValue}>{alertInfo.safeStock} kg</Text>
            </View>
            <View style={styles.alertDetailRow}>
              <Text style={styles.alertDetailLabel}>缺口</Text>
              <Text style={[styles.alertDetailValue, { color: "#f44336" }]}>
                {alertInfo.gap} kg
              </Text>
            </View>
            <View style={styles.alertDetailRow}>
              <Text style={styles.alertDetailLabel}>过期时间</Text>
              <Text style={[styles.alertDetailValue, { color: "#f57c00" }]}>
                {alertInfo.expireDate}
              </Text>
            </View>
          </View>
        </View>

        {/* 处理方案 */}
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>选择处理方案</Text>
          <View style={styles.solutionList}>
            {solutions.map(renderSolutionCard)}
          </View>
        </View>

        {/* 采购信息 */}
        {selectedSolution === "purchase" && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>采购信息</Text>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>采购数量</Text>
              <View style={styles.inputWithUnit}>
                <TextInput
                  mode="outlined"
                  value={purchaseQty}
                  onChangeText={setPurchaseQty}
                  keyboardType="numeric"
                  style={styles.formInput}
                  outlineStyle={styles.inputOutline}
                />
                <Text style={styles.inputUnit}>kg</Text>
              </View>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>供应商</Text>
              <View style={styles.pickerContainer}>
                <Picker
                  selectedValue={supplier}
                  onValueChange={setSupplier}
                  style={styles.picker}
                >
                  {suppliers.map((s) => (
                    <Picker.Item key={s.value} label={s.label} value={s.value} />
                  ))}
                </Picker>
              </View>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>预计单价</Text>
              <View style={styles.inputWithUnit}>
                <TextInput
                  mode="outlined"
                  value={unitPrice}
                  onChangeText={setUnitPrice}
                  keyboardType="numeric"
                  style={styles.formInput}
                  outlineStyle={styles.inputOutline}
                />
                <Text style={styles.inputUnit}>元/kg</Text>
              </View>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>预计总金额</Text>
              <View style={styles.calculatedValue}>
                <Text style={styles.calculatedValueText}>¥{calculateTotal()}</Text>
              </View>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>备注</Text>
              <TextInput
                mode="outlined"
                value={remarks}
                onChangeText={setRemarks}
                placeholder="请输入备注信息"
                multiline
                numberOfLines={3}
                style={styles.formTextarea}
                outlineStyle={styles.inputOutline}
              />
            </View>
          </View>
        )}

        {/* 转冻品信息 */}
        {selectedSolution === "freeze" && (
          <View style={styles.section}>
            <Text style={styles.sectionTitle}>转冻品信息</Text>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>转换数量</Text>
              <View style={styles.inputWithUnit}>
                <TextInput
                  mode="outlined"
                  value={freezeQty}
                  onChangeText={setFreezeQty}
                  keyboardType="numeric"
                  style={styles.formInput}
                  outlineStyle={styles.inputOutline}
                />
                <Text style={styles.inputUnit}>kg</Text>
              </View>
            </View>

            <View style={styles.formGroup}>
              <Text style={styles.formLabel}>目标库位</Text>
              <View style={styles.pickerContainer}>
                <Picker
                  selectedValue={targetLocation}
                  onValueChange={setTargetLocation}
                  style={styles.picker}
                >
                  {targetLocations.map((loc) => (
                    <Picker.Item key={loc.value} label={loc.label} value={loc.value} />
                  ))}
                </Picker>
              </View>
            </View>

            <View style={styles.infoBox}>
              <Text style={styles.infoBoxTitle}>转冻品说明</Text>
              <View style={styles.infoBoxList}>
                <Text style={styles.infoBoxItem}>• 转冻后保质期延长30天</Text>
                <Text style={styles.infoBoxItem}>• 新到期日期: 2026-01-28</Text>
                <Text style={styles.infoBoxItem}>• 需要2小时冷冻处理</Text>
              </View>
            </View>
          </View>
        )}

        {/* 操作按钮 */}
        <View style={styles.actionButtons}>
          <Button
            mode="outlined"
            onPress={() => navigation.goBack()}
            style={styles.actionBtnSecondary}
            labelStyle={{ color: "#666" }}
          >
            取消
          </Button>
          <Button
            mode="contained"
            onPress={handleSubmit}
            style={styles.actionBtnPrimary}
            labelStyle={{ color: "#fff" }}
          >
            确认处理方案
          </Button>
        </View>

        <View style={{ height: 20 }} />
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  notWired: {
    alignItems: "center",
    flex: 1,
    justifyContent: "center",
    paddingHorizontal: 32,
  },
  notWiredTitle: {
    color: "#616161",
    fontSize: 16,
    fontWeight: "600",
    marginTop: 12,
  },
  notWiredBody: {
    color: "#9e9e9e",
    fontSize: 13,
    lineHeight: 20,
    marginTop: 8,
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
  alertDetailCard: {
    backgroundColor: "#fff",
    marginHorizontal: 16,
    marginTop: 16,
    borderRadius: 12,
    padding: 16,
  },
  alertDetailHeader: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 16,
  },
  alertDetailInfo: {
    flex: 1,
    marginLeft: 12,
  },
  alertDetailName: {
    fontSize: 16,
    fontWeight: "600",
    color: "#333",
  },
  alertDetailBatch: {
    fontSize: 12,
    color: "#999",
    marginTop: 2,
  },
  alertDetailLevel: {
    backgroundColor: "#f44336",
    paddingHorizontal: 10,
    paddingVertical: 4,
    borderRadius: 4,
  },
  alertDetailLevelText: {
    fontSize: 12,
    color: "#fff",
    fontWeight: "500",
  },
  alertDetailContent: {
    gap: 8,
  },
  alertDetailRow: {
    flexDirection: "row",
    justifyContent: "space-between",
  },
  alertDetailLabel: {
    fontSize: 13,
    color: "#666",
  },
  alertDetailValue: {
    fontSize: 13,
    fontWeight: "500",
    color: "#333",
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
  solutionList: {
    gap: 10,
  },
  solutionItem: {
    flexDirection: "row",
    alignItems: "center",
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    padding: 12,
    borderWidth: 2,
    borderColor: "transparent",
  },
  solutionItemSelected: {
    borderColor: "#4CAF50",
    backgroundColor: "#e8f5e9",
  },
  solutionItemRecommended: {
    borderColor: "#4CAF50",
  },
  solutionContent: {
    flex: 1,
  },
  solutionHeader: {
    flexDirection: "row",
    alignItems: "center",
    marginBottom: 8,
    gap: 10,
  },
  solutionIcon: {
    width: 32,
    height: 32,
    borderRadius: 16,
    backgroundColor: "#e0e0e0",
    alignItems: "center",
    justifyContent: "center",
  },
  solutionIconSelected: {
    backgroundColor: "#4CAF50",
  },
  solutionTitle: {
    fontSize: 14,
    fontWeight: "600",
    color: "#333",
  },
  solutionTitleSelected: {
    color: "#4CAF50",
  },
  recommendedTag: {
    backgroundColor: "#4CAF50",
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
  },
  recommendedTagText: {
    fontSize: 10,
    color: "#fff",
    fontWeight: "500",
  },
  solutionDesc: {
    marginLeft: 42,
    gap: 4,
  },
  solutionDescText: {
    fontSize: 12,
    color: "#666",
  },
  formGroup: {
    marginBottom: 16,
  },
  formLabel: {
    fontSize: 13,
    color: "#666",
    marginBottom: 8,
  },
  formInput: {
    backgroundColor: "#fff",
    flex: 1,
    height: 44,
  },
  inputOutline: {
    borderRadius: 8,
  },
  formTextarea: {
    backgroundColor: "#fff",
    minHeight: 80,
  },
  inputWithUnit: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
  },
  inputUnit: {
    fontSize: 14,
    color: "#666",
    width: 50,
  },
  pickerContainer: {
    backgroundColor: "#f9f9f9",
    borderRadius: 8,
    borderWidth: 1,
    borderColor: "#ddd",
    overflow: "hidden",
  },
  picker: {
    height: 50,
  },
  calculatedValue: {
    backgroundColor: "#e8f5e9",
    paddingVertical: 12,
    paddingHorizontal: 16,
    borderRadius: 8,
  },
  calculatedValueText: {
    fontSize: 18,
    fontWeight: "bold",
    color: "#4CAF50",
  },
  infoBox: {
    backgroundColor: "#e3f2fd",
    borderRadius: 8,
    padding: 12,
    marginTop: 8,
  },
  infoBoxTitle: {
    fontSize: 13,
    fontWeight: "600",
    color: "#1976d2",
    marginBottom: 8,
  },
  infoBoxList: {
    gap: 4,
  },
  infoBoxItem: {
    fontSize: 12,
    color: "#666",
    lineHeight: 18,
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

export default WHAlertHandleScreen;
