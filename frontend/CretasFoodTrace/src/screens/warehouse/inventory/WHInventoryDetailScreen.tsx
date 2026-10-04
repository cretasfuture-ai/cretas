/** 原材料库存详情：只展示当前工厂 API 实际返回的数据。 */

import React, { useCallback, useEffect, useRef, useState } from "react";
import { ActivityIndicator, ScrollView, StyleSheet, TouchableOpacity, View } from "react-native";
import { Button, Surface, Text } from "react-native-paper";
import { SafeAreaView } from "react-native-safe-area-context";
import { MaterialCommunityIcons } from "@expo/vector-icons";
import { RouteProp, useNavigation, useRoute } from "@react-navigation/native";
import { NativeStackNavigationProp } from "@react-navigation/native-stack";
import { useTranslation } from "react-i18next";
import { useAuthStore } from "../../../store/authStore";
import { canViewPrice } from "../../../utils/priceViewAccess";
import { WHInventoryStackParamList } from "../../../types/navigation";
import { MaterialBatch, materialBatchApiClient } from "../../../services/api/materialBatchApiClient";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, "WHInventoryDetail">;
type AuthUser = ReturnType<typeof useAuthStore.getState>["user"];

interface BatchItem {
  id: string;
  batchNumber: string;
  quantity: number;
  quantityUnit: string | null;
  inboundDate: string | null;
  expiryDays: number | null;
  location: string | null;
}

interface InventoryDetailData {
  materialName: string | null;
  materialType: string | null;
  totalQuantity: number | null;
  quantityUnit: string | null;
  batches: BatchItem[];
  locationDistribution: { name: string; quantity: number; unit: string | null }[];
  unitPrice: number | null;
  movingAvgPrice: number | null;
}

function factoryOf(user: AuthUser): string | null {
  return user?.factoryUser?.factoryId ?? user?.factoryId ?? null;
}

function ownerKeyOf(user: AuthUser, routeKey: string): string {
  return [
    user?.id ?? "",
    factoryOf(user) ?? "",
    user?.userType ?? "",
    user?.factoryUser?.role ?? user?.platformUser?.role ?? "",
    JSON.stringify(user?.factoryUser?.modulePermissions ?? {}),
    routeKey,
  ].join("|");
}

function validDate(value?: string | null): Date | null {
  if (!value) return null;
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function expiryDays(expiryDate?: string | null): number | null {
  const expiry = validDate(expiryDate);
  if (!expiry) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.ceil((expiry.getTime() - today.getTime()) / 86400000);
}

function displayDate(value?: string | null): string | null {
  return value ? value.split("T")[0] ?? null : null;
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

function parseBatchList(response: Awaited<ReturnType<typeof materialBatchApiClient.getMaterialBatches>>): MaterialBatch[] {
  if (!response?.success || !response.data || !Array.isArray(response.data.content) || !Number.isInteger(response.data.totalPages) || response.data.totalPages < 0) {
    throw new Error(response?.message || "批次数据格式无效");
  }
  if (!response.data.content.every(isMaterialBatchRecord)) throw new Error("批次数据格式无效");
  return response.data.content;
}

export function WHInventoryDetailScreen() {
  const { t } = useTranslation("warehouse");
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const user = useAuthStore((state) => state.user);
  const { materialId, inventoryId } = route.params;
  const routeKey = `${materialId ?? ""}:${inventoryId ?? ""}`;
  const ownerKey = ownerKeyOf(user, routeKey);
  const factoryId = factoryOf(user);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ ownerKey: string; data: InventoryDetailData } | null>(null);
  const generationRef = useRef(0);
  const renderContextRef = useRef({ ownerKey, routeKey, user });
  renderContextRef.current = { ownerKey, routeKey, user };

  const isLiveOwner = useCallback(() =>
    useAuthStore.getState().user === user
      && renderContextRef.current.ownerKey === ownerKey
      && renderContextRef.current.routeKey === routeKey,
    [ownerKey, routeKey, user],
  );

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
      if (!actor || !factoryId) throw new Error(t("messages.factoryUnavailable"));
      let materialTypeId = materialId?.trim() || "";
      if (!materialTypeId && inventoryId) {
        if (!isCurrent()) return;
        const batchResponse = await materialBatchApiClient.getBatchById(inventoryId, factoryId);
        if (!isCurrent()) return;
        const batch = batchResponse?.success ? batchResponse.data : null;
        if (!isMaterialBatchRecord(batch) || batch.id !== inventoryId || batch.factoryId !== factoryId || !batch.materialTypeId) {
          throw new Error(t("messages.batchIdentityMismatch"));
        }
        materialTypeId = batch.materialTypeId;
      }
      if (!materialTypeId) throw new Error(t("messages.batchIdentityMissing"));
      if (!isCurrent()) return;
      const response = await materialBatchApiClient.getMaterialBatches({ factoryId, materialTypeId, page: 1, size: 50 });
      if (!isCurrent()) return;
      const batchList = parseBatchList(response);
      if (batchList.some((batch) => batch.factoryId !== factoryId || batch.materialTypeId !== materialTypeId)) {
        throw new Error(t("messages.batchIdentityMismatch"));
      }

      const batchUnits = batchList.map((batch) => batch.quantityUnit ?? null);
      const firstUnit = batchUnits[0] ?? null;
      const quantityUnit = batchUnits.length > 0 && firstUnit !== null && batchUnits.every((unit) => unit === firstUnit)
        ? firstUnit
        : null;
      const totalQuantity = quantityUnit !== null ? batchList.reduce((sum, batch) => sum + batch.remainingQuantity, 0) : null;
      const batchItems = batchList.map((batch): BatchItem => ({
        id: batch.id,
        batchNumber: batch.batchNumber,
        quantity: batch.remainingQuantity,
        quantityUnit: batch.quantityUnit ?? null,
        inboundDate: displayDate(batch.inboundDate || batch.createdAt),
        expiryDays: expiryDays(batch.expiryDate),
        location: batch.storageLocation ?? null,
      }));
      const locations = new Map<string, { name: string; quantity: number; unit: string | null }>();
      for (const batch of batchList) {
        const name = batch.storageLocation || t("inventoryDetail.unassignedLocation");
        const unit = batch.quantityUnit ?? null;
        const key = `${name}\u0000${unit ?? ""}`;
        const previous = locations.get(key) ?? { name, quantity: 0, unit };
        locations.set(key, { name, quantity: previous.quantity + batch.remainingQuantity, unit });
      }
      const firstBatch = batchList[0];
      const data: InventoryDetailData = {
        materialName: firstBatch?.materialName ?? null,
        materialType: firstBatch?.materialCategory ?? firstBatch?.storageType ?? null,
        totalQuantity,
        quantityUnit,
        batches: batchItems,
        locationDistribution: [...locations.values()],
        unitPrice: canViewPrice(actor) ? firstBatch?.unitPrice ?? null : null,
        movingAvgPrice: canViewPrice(actor) ? firstBatch?.movingAvgPrice ?? null : null,
      };
      if (!isCurrent()) return;
      setDetail({ ownerKey: requestOwnerKey, data });
    } catch (error: unknown) {
      if (!isCurrent()) return;
      setLoadError(error instanceof Error ? error.message : t("messages.loadInventoryDetailFailed"));
      setErrorOwnerKey(requestOwnerKey);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [factoryId, inventoryId, isLiveOwner, materialId, ownerKey, t, user]);

  useEffect(() => { void loadData(); }, [loadData]);

  const visibleDetail = detail?.ownerKey === ownerKey ? detail.data : null;
  const visibleError = errorOwnerKey === ownerKey ? loadError : null;
  const goBackIfCurrent = useCallback(() => { if (isLiveOwner()) navigation.goBack(); }, [isLiveOwner, navigation]);
  const openBatchIfCurrent = useCallback((batch: BatchItem) => {
    if (!isLiveOwner()) return;
    navigation.navigate("WHBatchDetail", { batchId: batch.id, batchNumber: batch.batchNumber });
  }, [isLiveOwner, navigation]);
  const openExpireIfCurrent = useCallback(() => {
    if (!isLiveOwner()) return;
    navigation.navigate("WHExpireHandle", {});
  }, [isLiveOwner, navigation]);
  const renderLoading = loading || (!visibleDetail && !visibleError);

  if (renderLoading) {
    return <StateScreen title={t("inventoryDetail.title")} loading message={t("inventoryDetail.loading")} onBack={goBackIfCurrent} />;
  }
  if (visibleError || !visibleDetail) {
    return <StateScreen title={t("inventoryDetail.title")} message={visibleError || t("inventoryDetail.noData")} onBack={goBackIfCurrent} onRetry={() => void loadData()} retryLabel={t("messages.retry")} />;
  }

  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      <Header title={t("inventoryDetail.title")} subtitle={visibleDetail.materialName} onBack={goBackIfCurrent} />
      <View style={{ marginHorizontal: 16, paddingVertical: 8 }}><Button mode="outlined" onPress={openExpireIfCurrent}>{t("batch.detail.actions.expireHandle")}</Button></View>
      <ScrollView style={styles.content}>
        <Surface style={styles.card} elevation={1}>
          <Text style={styles.bigNumber}>{visibleDetail.totalQuantity == null ? "—" : visibleDetail.totalQuantity}</Text>
          <Text style={styles.unit}>{visibleDetail.quantityUnit || t("inventoryDetail.mixedUnits")}</Text>
          <Text style={styles.muted}>{t("inventoryDetail.loadedSummary")}</Text>
        </Surface>
        <Section title={t("inventoryDetail.basicInfo")}>
          <Info label={t("inventoryDetail.materialName")} value={visibleDetail.materialName} />
          <Info label={t("batch.detail.materialType")} value={visibleDetail.materialType} />
          <Info label={t("inventoryDetail.status")} value={t("inventoryDetail.statusUnavailable")} />
          {visibleDetail.unitPrice != null ? <Info label={t("inventoryDetail.unitPrice")} value={`¥${visibleDetail.unitPrice}`} /> : null}
          {visibleDetail.movingAvgPrice != null ? <Info label={t("inventoryDetail.movingAvgPrice")} value={`¥${visibleDetail.movingAvgPrice}`} /> : null}
        </Section>
        <Section title={`${t("inventoryDetail.batchList")} (${visibleDetail.batches.length})`}>
          {visibleDetail.batches.map((batch) => (
            <TouchableOpacity key={batch.id} style={styles.batchCard} onPress={() => openBatchIfCurrent(batch)}>
              <Text style={styles.batchNumber}>{batch.batchNumber}</Text>
              <Info label={t("inventoryDetail.currentStock")} value={`${batch.quantity} ${batch.quantityUnit || t("inventoryDetail.unknownUnit")}`} />
              <Info label={t("batch.detail.inboundDate")} value={batch.inboundDate} />
              <Info label={t("inventoryDetail.location")} value={batch.location} />
              <Info label={t("batch.detail.expiryDate")} value={batch.expiryDays == null ? t("batch.detail.expiryUnknown") : `${batch.expiryDays} ${t("batch.detail.daysRemainingShort")}`} />
            </TouchableOpacity>
          ))}
        </Section>
        <Section title={t("inventoryDetail.location")}>
          {visibleDetail.locationDistribution.map((location) => <Info key={`${location.name}\u0000${location.unit ?? ""}`} label={location.name} value={`${location.quantity} ${location.unit || t("inventoryDetail.unknownUnit")}`} />)}
        </Section>
        <View style={styles.bottomSpace} />
      </ScrollView>
    </SafeAreaView>
  );
}

function Header({ title, subtitle, onBack }: { title: string; subtitle?: string | null; onBack: () => void }) {
  return <View style={styles.header}><TouchableOpacity style={styles.backButton} onPress={onBack}><MaterialCommunityIcons name="arrow-left" size={24} color="#fff" /></TouchableOpacity><View style={styles.headerCenter}><Text style={styles.headerTitle}>{title}</Text>{subtitle ? <Text style={styles.headerSubtitle}>{subtitle}</Text> : null}</View><View style={styles.headerRight} /></View>;
}

function StateScreen({ title, message, loading, onBack, onRetry, retryLabel }: { title: string; message: string; loading?: boolean; onBack: () => void; onRetry?: () => void; retryLabel?: string }) {
  return <SafeAreaView style={styles.container} edges={["top"]}><Header title={title} onBack={onBack} /><View style={styles.state}>{loading ? <ActivityIndicator color="#4CAF50" /> : null}<Text style={styles.stateText}>{message}</Text>{onRetry ? <Button mode="contained" onPress={onRetry}>{retryLabel}</Button> : null}</View></SafeAreaView>;
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return <View style={styles.section}><Text style={styles.sectionTitle}>{title}</Text>{children}</View>;
}

function Info({ label, value }: { label: string; value: string | number | null | undefined }) {
  return <View style={styles.infoRow}><Text style={styles.infoLabel}>{label}</Text><Text style={styles.infoValue}>{value ?? "—"}</Text></View>;
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: "#f5f5f5" },
  header: { backgroundColor: "#4CAF50", flexDirection: "row", alignItems: "center", paddingHorizontal: 16, paddingVertical: 12 },
  backButton: { minWidth: 44, minHeight: 44, justifyContent: "center" },
  headerCenter: { flex: 1, alignItems: "center", marginRight: 44 },
  headerTitle: { fontSize: 18, fontWeight: "600", color: "#fff" },
  headerSubtitle: { fontSize: 12, color: "rgba(255,255,255,0.8)", marginTop: 2 },
  headerRight: { width: 0 },
  content: { flex: 1 },
  card: { backgroundColor: "#fff", margin: 16, borderRadius: 12, padding: 20, alignItems: "center" },
  bigNumber: { fontSize: 42, fontWeight: "700", color: "#388e3c" },
  unit: { fontSize: 16, color: "#555", marginTop: 4 },
  muted: { color: "#777", marginTop: 8 },
  section: { backgroundColor: "#fff", marginHorizontal: 16, marginBottom: 12, borderRadius: 12, padding: 16 },
  sectionTitle: { fontSize: 16, fontWeight: "700", color: "#333", marginBottom: 10 },
  infoRow: { flexDirection: "row", justifyContent: "space-between", gap: 12, paddingVertical: 7 },
  infoLabel: { color: "#777", flex: 1 },
  infoValue: { color: "#333", flex: 1, textAlign: "right" },
  batchCard: { borderWidth: 1, borderColor: "#e0e0e0", borderRadius: 10, padding: 12, marginBottom: 10, minHeight: 44 },
  batchNumber: { fontWeight: "700", color: "#333", marginBottom: 4 },
  state: { flex: 1, alignItems: "center", justifyContent: "center", gap: 14, padding: 24 },
  stateText: { color: "#555", textAlign: "center" },
  bottomSpace: { height: 24 },
});

export default WHInventoryDetailScreen;
