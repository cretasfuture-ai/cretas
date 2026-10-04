/** 原材料批次追溯：先确认本厂批次身份，再查询真实批号的上下游记录。 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, ScrollView, StyleSheet, TouchableOpacity, View } from "react-native";
import { Button, Surface, Text } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { RouteProp, useNavigation, useRoute } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { BottomTabNavigationProp } from "@react-navigation/bottom-tabs";
import { useTranslation } from "react-i18next";
import { useAuthStore } from "../../../store/authStore";
import { WHInventoryStackParamList, WarehouseManagerTabParamList } from "../../../types/navigation";
import { MaterialBatch, materialBatchApiClient } from "../../../services/api/materialBatchApiClient";
import { ImpactResponse, LineageNode, traceabilityApiClient } from "../../../services/api/traceabilityApiClient";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type TabNavigationProp = BottomTabNavigationProp<WarehouseManagerTabParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, "WHBatchTrace">;
type AuthUser = ReturnType<typeof useAuthStore.getState>["user"];

interface UsageRecord { id?: string | number; quantity?: number; usedQuantity?: number; unit?: string; quantityUnit?: string; createdAt?: string; usedAt?: string; }
interface TraceEvent { id: string; label: string; date: string | null; quantity: string | null; detail?: string | null; }
interface TraceData { batch: MaterialBatch; events: TraceEvent[]; impact: ImpactResponse | null; partial: boolean; }

function factoryOf(user: AuthUser): string | null { return user?.factoryUser?.factoryId ?? user?.factoryId ?? null; }
function ownerKeyOf(user: AuthUser, batchId: string, batchNumber: string): string { return [user?.id ?? "", factoryOf(user) ?? "", user?.userType ?? "", user?.factoryUser?.role ?? user?.platformUser?.role ?? "", JSON.stringify(user?.factoryUser?.modulePermissions ?? {}), batchId, batchNumber].join("|"); }
function dateOnly(value?: string | null): string | null { return value ? value.split("T")[0] ?? null : null; }
function isUsageRecord(value: unknown): value is UsageRecord {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return (typeof record.quantity === "number" || typeof record.usedQuantity === "number")
    && (record.quantity === undefined || typeof record.quantity === "number")
    && (record.usedQuantity === undefined || typeof record.usedQuantity === "number")
    && (record.unit === undefined || typeof record.unit === "string")
    && (record.quantityUnit === undefined || typeof record.quantityUnit === "string")
    && (record.createdAt === undefined || typeof record.createdAt === "string")
    && (record.usedAt === undefined || typeof record.usedAt === "string");
}
function isLineageNode(value: unknown, expectedType: LineageNode["nodeType"]): value is LineageNode {
  if (!value || typeof value !== "object") return false;
  const node = value as Record<string, unknown>;
  return node.nodeType === expectedType
    && typeof node.nodeId === "string"
    && node.nodeId.length > 0
    && (node.quantity === undefined || node.quantity === null || typeof node.quantity === "number")
    && (node.unit === undefined || node.unit === null || typeof node.unit === "string")
    && (node.eventTime === undefined || node.eventTime === null || typeof node.eventTime === "string")
    && (node.batchNumber === undefined || node.batchNumber === null || typeof node.batchNumber === "string")
    && (node.name === undefined || node.name === null || typeof node.name === "string")
    && (node.resolved === undefined || typeof node.resolved === "boolean");
}
function isImpactResponse(value: unknown, factoryId: string, batchId: string): value is ImpactResponse {
  if (!value || typeof value !== "object") return false;
  const impact = value as Record<string, unknown>;
  if ("success" in impact && impact.success !== true) return false;
  return impact.rootType === "MATERIAL_BATCH"
    && impact.rootId === batchId
    && Array.isArray(impact.productionBatches)
    && Array.isArray(impact.finishedBatches)
    && Array.isArray(impact.shipments)
    && impact.productionBatches.every((node) => isLineageNode(node, "PRODUCTION_BATCH"))
    && impact.finishedBatches.every((node) => isLineageNode(node, "FINISHED_BATCH"))
    && impact.shipments.every((node) => isLineageNode(node, "SHIPMENT_RECORD"))
    && (impact.factoryId === undefined || impact.factoryId === factoryId);
}
function isMaterialBatchRecord(value: unknown): value is MaterialBatch {
  if (!value || typeof value !== "object") return false;
  const record = value as Record<string, unknown>;
  return typeof record.id === "string"
    && typeof record.batchNumber === "string"
    && typeof record.factoryId === "string"
    && typeof record.materialTypeId === "string"
    && typeof record.inboundQuantity === "number"
    && typeof record.remainingQuantity === "number";
}
function batchFromResponse(response: Awaited<ReturnType<typeof materialBatchApiClient.getBatchById>>, batchId: string, factoryId: string): MaterialBatch {
  const batch = response?.success ? response.data : null;
  if (!isMaterialBatchRecord(batch) || batch.id !== batchId || batch.factoryId !== factoryId) throw new Error(response?.message || "批次身份校验失败");
  return batch;
}
function parseBatchList(response: Awaited<ReturnType<typeof materialBatchApiClient.getMaterialBatches>>): MaterialBatch[] {
  if (!response?.success || !response.data || !Array.isArray(response.data.content) || !Number.isInteger(response.data.totalPages) || response.data.totalPages < 0 || !response.data.content.every(isMaterialBatchRecord)) {
    throw new Error(response?.message || "批次数据格式无效");
  }
  return response.data.content;
}

export function WHBatchTraceScreen() {
  const { t } = useTranslation("warehouse");
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const user = useAuthStore((state) => state.user);
  const batchId = route.params.batchId ?? "";
  const suppliedBatchNumber = route.params.batchNumber ?? "";
  const factoryId = factoryOf(user);
  const ownerKey = ownerKeyOf(user, batchId, suppliedBatchNumber);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [actionErrorOwnerKey, setActionErrorOwnerKey] = useState<string | null>(null);
  const [traceData, setTraceData] = useState<{ ownerKey: string; data: TraceData } | null>(null);
  const generationRef = useRef(0);
  const renderContextRef = useRef({ ownerKey, batchId, suppliedBatchNumber, user });
  renderContextRef.current = { ownerKey, batchId, suppliedBatchNumber, user };

  const isLiveOwner = useCallback(() =>
    useAuthStore.getState().user === user
      && renderContextRef.current.ownerKey === ownerKey
      && renderContextRef.current.batchId === batchId
      && renderContextRef.current.suppliedBatchNumber === suppliedBatchNumber,
    [batchId, ownerKey, suppliedBatchNumber, user],
  );

  const loadTraceData = useCallback(async () => {
    const actor = user;
    const requestOwnerKey = ownerKey;
    if (!isLiveOwner()) return;
    const generation = ++generationRef.current;
    const isCurrent = () => useAuthStore.getState().user === actor && isLiveOwner() && renderContextRef.current.ownerKey === requestOwnerKey && generationRef.current === generation;
    setLoading(true);
    setLoadError(null);
    setErrorOwnerKey(null);
    setTraceData(null);
    try {
      if (!actor || !factoryId || (!batchId && !suppliedBatchNumber)) throw new Error(t("messages.batchIdentityMissing"));
      if (!isCurrent()) return;
      let batch: MaterialBatch;
      if (batchId) {
        batch = batchFromResponse(await materialBatchApiClient.getBatchById(batchId, factoryId), batchId, factoryId);
      } else {
        const candidates: MaterialBatch[] = [];
        let page = 1;
        let totalPages = 1;
        do {
          if (!isCurrent()) return;
          const pageResponse = await materialBatchApiClient.getMaterialBatches({ factoryId, keyword: suppliedBatchNumber, page, size: 50 });
          if (!isCurrent()) return;
          candidates.push(...parseBatchList(pageResponse));
          totalPages = pageResponse.data.totalPages;
          page += 1;
        } while (page <= totalPages);
        const numberMatches = candidates.filter((candidate) => candidate.batchNumber === suppliedBatchNumber);
        if (numberMatches.length !== 1 || numberMatches[0]?.factoryId !== factoryId) throw new Error(t("messages.batchIdentityMismatch"));
        const matched = numberMatches[0];
        if (!matched) throw new Error(t("messages.batchIdentityMismatch"));
        batch = matched;
      }
      if (!batch.batchNumber) throw new Error(t("messages.traceBatchNumberMissing"));
      if (!isCurrent()) return;
      const [impactResult, usageResult] = await Promise.allSettled([
        traceabilityApiClient.getImpact("MATERIAL_BATCH", batch.id, { factoryId }),
        materialBatchApiClient.getBatchUsageHistory(batch.id, factoryId),
      ]);
      if (!isCurrent()) return;
      const impact = impactResult.status === "fulfilled" && isImpactResponse(impactResult.value, factoryId, batch.id) ? impactResult.value : null;
      const usageValid = usageResult.status === "fulfilled"
        && usageResult.value?.success === true
        && Array.isArray(usageResult.value.data)
        && usageResult.value.data.every(isUsageRecord);
      const usage = usageValid ? usageResult.value.data : [];
      const events: TraceEvent[] = [{
        id: `inbound-${batch.id}`,
        label: t("shared.batchTrace.inbound"),
        date: dateOnly(batch.inboundDate || batch.createdAt),
        quantity: `${batch.inboundQuantity} ${batch.quantityUnit || t("inventoryDetail.unknownUnit")}`,
      }];
      usage.forEach((record, index) => {
        const quantity = record.quantity ?? record.usedQuantity;
        events.push({
          id: `usage-${record.id ?? index}`,
          label: t("shared.batchTrace.usage"),
          date: dateOnly(record.usedAt || record.createdAt),
          quantity: quantity == null ? null : `-${quantity} ${record.quantityUnit || record.unit || batch.quantityUnit || t("inventoryDetail.unknownUnit")}`,
        });
      });
      const addImpactEvents = (nodes: LineageNode[], label: string, prefix: string) => nodes.forEach((node, index) => {
        events.push({
          id: `${prefix}-${node.batchNumber ?? node.nodeId ?? index}`,
          label,
          date: dateOnly(node.eventTime),
          quantity: node.quantity == null ? null : `${node.quantity} ${node.unit || t("inventoryDetail.unknownUnit")}`,
          detail: node.name || node.batchNumber || null,
        });
      });
      if (impact) {
        addImpactEvents(impact.productionBatches, t("shared.batchTrace.productionImpact"), "production");
        addImpactEvents(impact.finishedBatches, t("shared.batchTrace.downstream"), "finished");
        addImpactEvents(impact.shipments, t("shared.batchTrace.shipment"), "shipment");
      }
      const partial = !impact || !usageValid;
      if (!isCurrent()) return;
      setTraceData({ ownerKey: requestOwnerKey, data: { batch, events, impact, partial } });
    } catch (error: unknown) {
      if (!isCurrent()) return;
      setLoadError(error instanceof Error ? error.message : t("messages.loadTraceFailed"));
      setErrorOwnerKey(requestOwnerKey);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [batchId, factoryId, isLiveOwner, ownerKey, suppliedBatchNumber, t, user]);

  useEffect(() => { void loadTraceData(); }, [loadTraceData]);

  const visible = traceData?.ownerKey === ownerKey ? traceData.data : null;
  const visibleError = errorOwnerKey === ownerKey ? loadError : null;
  const goBackIfCurrent = useCallback(() => { if (isLiveOwner()) navigation.goBack(); }, [isLiveOwner, navigation]);
  const openRecallIfCurrent = useCallback(() => {
    if (!isLiveOwner()) return;
    const parent = navigation.getParent<TabNavigationProp>();
    if (!parent) {
      setActionError(t("messages.parentNavigationUnavailable"));
      setActionErrorOwnerKey(ownerKey);
      return;
    }
    setActionError(null);
    setActionErrorOwnerKey(null);
    parent.navigate("WHProfileTab", { screen: "WHRecallManage" });
  }, [isLiveOwner, navigation, ownerKey, t]);
  if (loading || (!visible && !visibleError)) return <StateScreen title={t("shared.batchTrace.title")} message={t("shared.batchTrace.loading")} loading onBack={goBackIfCurrent} />;
  if (!visible || visibleError) return <StateScreen title={t("shared.batchTrace.title")} message={visibleError || t("shared.batchTrace.noData")} onBack={goBackIfCurrent} onRetry={() => void loadTraceData()} retryLabel={t("messages.retry")} />;

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      <Header title={t("shared.batchTrace.title")} subtitle={visible.batch.batchNumber} onBack={goBackIfCurrent} />
      <ScrollView style={styles.content}>
        <View style={styles.actions}><Button mode="outlined" onPress={openRecallIfCurrent}>{t("shared.batchTrace.recall")}</Button>{actionErrorOwnerKey === ownerKey && actionError ? <Text style={styles.error}>{actionError}</Text> : null}</View>
        {visible.partial ? <View style={styles.warning}><Text style={styles.warningText}>{t("shared.batchTrace.partialFailure")}</Text><Button mode="outlined" onPress={() => void loadTraceData()}>{t("messages.retry")}</Button></View> : null}
        <Surface style={styles.card} elevation={1}><Text style={styles.cardTitle}>{t("shared.batchTrace.traceability")}</Text><Text>{visible.batch.materialName ?? t("messages.unknownMaterial")}</Text><Text>{t("shared.batchTrace.batchNumber")}: {visible.batch.batchNumber}</Text></Surface>
        <Section title={t("shared.batchTrace.events")}>
          {visible.events.map((event) => <View key={event.id} style={styles.event}><Text style={styles.eventLabel}>{event.label}</Text>{event.detail ? <Text style={styles.muted}>{event.detail}</Text> : null}<Text style={styles.eventDate}>{event.date ?? "—"}</Text><Text style={styles.eventQty}>{event.quantity ?? "—"}</Text></View>)}
        </Section>
        <Section title={t("shared.batchTrace.qualityRecords")}><Text style={styles.muted}>{t("shared.batchTrace.qualityUnavailable")}</Text></Section>
        <View style={styles.bottomSpace} />
      </ScrollView>
    </SafeAreaView>
  );
}

function Header({ title, subtitle, onBack }: { title: string; subtitle?: string | null; onBack: () => void }) { return <View style={styles.header}><TouchableOpacity style={styles.backButton} onPress={onBack}><MaterialCommunityIcons name="arrow-left" size={24} color="#fff" /></TouchableOpacity><View style={styles.headerCenter}><Text style={styles.headerTitle}>{title}</Text>{subtitle ? <Text style={styles.headerSubtitle}>{subtitle}</Text> : null}</View><View style={styles.headerRight} /></View>; }
function StateScreen({ title, message, loading, onBack, onRetry, retryLabel }: { title: string; message: string; loading?: boolean; onBack: () => void; onRetry?: () => void; retryLabel?: string }) { return <SafeAreaView style={styles.container} edges={["top"]}><Header title={title} onBack={onBack} /><View style={styles.state}>{loading ? <ActivityIndicator color="#4CAF50" /> : null}<Text style={styles.stateText}>{message}</Text>{onRetry ? <Button mode="contained" onPress={onRetry}>{retryLabel}</Button> : null}</View></SafeAreaView>; }
function Section({ title, children }: { title: string; children: React.ReactNode }) { return <View style={styles.section}><Text style={styles.sectionTitle}>{title}</Text>{children}</View>; }

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f5f5f5" },
  header: { backgroundColor: "#4CAF50", flexDirection: "row", alignItems: "center", paddingHorizontal: 16, paddingVertical: 12 },
  backButton: { minWidth: 44, minHeight: 44, justifyContent: "center" },
  headerCenter: { flex: 1, alignItems: "center", marginRight: 44 },
  headerTitle: { fontSize: 18, fontWeight: "600", color: "#fff" },
  headerSubtitle: { fontSize: 12, color: "rgba(255,255,255,0.8)", marginTop: 2 },
  headerRight: { width: 0 },
  content: { flex: 1 },
  card: { backgroundColor: "#fff", margin: 16, borderRadius: 12, padding: 20, gap: 6 },
  cardTitle: { fontSize: 18, fontWeight: "700", marginBottom: 4 },
  warning: { margin: 16, padding: 14, backgroundColor: "#fff3e0", borderRadius: 10, gap: 10 },
  warningText: { color: "#8a4b08" },
  actions: { marginHorizontal: 16, marginBottom: 12, gap: 10 },
  error: { color: "#b71c1c", textAlign: "center" },
  section: { backgroundColor: "#fff", marginHorizontal: 16, marginBottom: 12, borderRadius: 12, padding: 16 },
  sectionTitle: { fontSize: 16, fontWeight: "700", marginBottom: 10 },
  event: { borderLeftWidth: 3, borderLeftColor: "#4CAF50", paddingLeft: 12, paddingVertical: 8, marginBottom: 8 },
  eventLabel: { fontWeight: "700" },
  eventDate: { color: "#666" },
  eventQty: { color: "#333" },
  muted: { color: "#666" },
  state: { flex: 1, alignItems: "center", justifyContent: "center", gap: 14, padding: 24 },
  stateText: { color: "#555", textAlign: "center" },
  bottomSpace: { height: 24 },
});

export default WHBatchTraceScreen;
