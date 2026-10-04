/** 原材料批次详情：缺少后端事实时显示未知，不编造质量、温度或价格。 */

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
import { labelApiClient } from "../../../services/api/labelApiClient";
import { printBatchLabel } from "../../../services/BatchLabelPrintService";

type NavigationProp = NativeStackNavigationProp<WHInventoryStackParamList>;
type RouteType = RouteProp<WHInventoryStackParamList, "WHBatchDetail">;
type AuthUser = ReturnType<typeof useAuthStore.getState>["user"];

interface BatchDetailData {
  batch: MaterialBatch;
  expiryDays: number | null;
  shelfLifeDays: number | null;
  unit: string | null;
  currentQuantity: number;
  initialQuantity: number;
  consumedQuantity: number;
  canViewPrice: boolean;
}

function factoryOf(user: AuthUser): string | null {
  return user?.factoryUser?.factoryId ?? user?.factoryId ?? null;
}

function ownerKeyOf(user: AuthUser, batchId: string, batchNumber: string): string {
  return [user?.id ?? "", factoryOf(user) ?? "", user?.userType ?? "", user?.factoryUser?.role ?? user?.platformUser?.role ?? "", JSON.stringify(user?.factoryUser?.modulePermissions ?? {}), batchId, batchNumber].join("|");
}

function validDate(value?: string | null): Date | null {
  if (!value) return null;
  const date = /^\d{4}-\d{2}-\d{2}$/.test(value) ? new Date(`${value}T00:00:00`) : new Date(value);
  return Number.isNaN(date.getTime()) ? null : date;
}

function dateOnly(value?: string | null): string | null {
  return value ? value.split("T")[0] ?? null : null;
}

function daysUntil(value?: string | null): number | null {
  const date = validDate(value);
  if (!date) return null;
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  return Math.ceil((date.getTime() - today.getTime()) / 86400000);
}

function shelfLifeDays(productionDate?: string | null, expiryDate?: string | null): number | null {
  const production = validDate(productionDate);
  const expiry = validDate(expiryDate);
  if (!production || !expiry || expiry < production) return null;
  return Math.round((expiry.getTime() - production.getTime()) / 86400000);
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
  if (!response?.success || !response.data || !Array.isArray(response.data.content) || !Number.isInteger(response.data.totalPages) || response.data.totalPages < 0 || !response.data.content.every(isMaterialBatchRecord)) {
    throw new Error(response?.message || "批次数据格式无效");
  }
  return response.data.content;
}

function batchFromResponse(response: Awaited<ReturnType<typeof materialBatchApiClient.getBatchById>>, batchId: string, factoryId: string, expectedMaterialTypeId?: string): MaterialBatch {
  const batch = response?.success ? response.data : null;
  if (!isMaterialBatchRecord(batch) || batch.id !== batchId || batch.factoryId !== factoryId || (expectedMaterialTypeId && batch.materialTypeId !== expectedMaterialTypeId)) throw new Error(response?.message || "批次身份校验失败");
  return batch;
}

export function WHBatchDetailScreen() {
  const { t } = useTranslation("warehouse");
  const navigation = useNavigation<NavigationProp>();
  const route = useRoute<RouteType>();
  const user = useAuthStore((state) => state.user);
  const batchId = route.params.batchId?.trim() ?? "";
  const batchNumber = route.params.batchNumber?.trim() ?? "";
  const expectedMaterialTypeId = ((route.params as RouteType["params"] & { materialTypeId?: string }).materialTypeId ?? "").trim();
  const factoryId = factoryOf(user);
  const ownerKey = ownerKeyOf(user, batchId, `${batchNumber}|${expectedMaterialTypeId}`);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [errorOwnerKey, setErrorOwnerKey] = useState<string | null>(null);
  const [detail, setDetail] = useState<{ ownerKey: string; data: BatchDetailData } | null>(null);
  const [printing, setPrinting] = useState(false);
  const [printingOwnerKey, setPrintingOwnerKey] = useState<string | null>(null);
  const [printError, setPrintError] = useState<string | null>(null);
  const [printErrorOwnerKey, setPrintErrorOwnerKey] = useState<string | null>(null);
  const generationRef = useRef(0);
  const printGenerationRef = useRef(0);
  const printOwnerRef = useRef(ownerKey);
  const ownerEpochRef = useRef(0);
  const printRequestRef = useRef<{ ownerKey: string; epoch: number; generation: number } | null>(null);
  if (printOwnerRef.current !== ownerKey) {
    printOwnerRef.current = ownerKey;
    ownerEpochRef.current += 1;
    printGenerationRef.current += 1;
  }
  const renderContextRef = useRef({ ownerKey, batchId, batchNumber, user });
  renderContextRef.current = { ownerKey, batchId, batchNumber, user };

  const isLiveOwner = useCallback(() =>
    useAuthStore.getState().user === user
      && renderContextRef.current.ownerKey === ownerKey
      && renderContextRef.current.batchId === batchId
      && renderContextRef.current.batchNumber === batchNumber,
    [batchId, batchNumber, ownerKey, user],
  );

  const loadData = useCallback(async () => {
    const actor = user;
    const requestOwnerKey = ownerKey;
    if (!isLiveOwner()) return;
    const generation = ++generationRef.current;
    const isCurrent = () => useAuthStore.getState().user === actor && isLiveOwner() && renderContextRef.current.ownerKey === requestOwnerKey && generationRef.current === generation;
    setLoading(true);
    setLoadError(null);
    setErrorOwnerKey(null);
    setDetail(null);
    try {
      if (!actor || !factoryId || (!batchId && !batchNumber)) throw new Error(t("messages.batchIdentityMissing"));
      if (!isCurrent()) return;
      let batch: MaterialBatch;
      if (batchId) {
        batch = batchFromResponse(await materialBatchApiClient.getBatchById(batchId, factoryId), batchId, factoryId, expectedMaterialTypeId || undefined);
      } else {
        const candidates: MaterialBatch[] = [];
        let page = 1;
        let totalPages = 1;
        do {
          if (!isCurrent()) return;
          const pageResponse = await materialBatchApiClient.getMaterialBatches({ factoryId, keyword: batchNumber, page, size: 50 });
          if (!isCurrent()) return;
          candidates.push(...parseBatchList(pageResponse));
          totalPages = pageResponse.data.totalPages;
          page += 1;
        } while (page <= totalPages);
        const numberMatches = candidates.filter((candidate) => candidate.batchNumber === batchNumber);
        if (numberMatches.length !== 1 || numberMatches[0]?.factoryId !== factoryId || (expectedMaterialTypeId && numberMatches[0]?.materialTypeId !== expectedMaterialTypeId)) throw new Error(t("messages.batchIdentityMismatch"));
        const matched = numberMatches[0];
        if (!matched) throw new Error(t("messages.batchIdentityMismatch"));
        batch = matched;
      }
      if (!isCurrent()) return;
      const currentQuantity = batch.remainingQuantity;
      const initialQuantity = batch.inboundQuantity;
      setDetail({
        ownerKey: requestOwnerKey,
        data: {
          batch,
          expiryDays: daysUntil(batch.expiryDate),
          shelfLifeDays: shelfLifeDays(batch.productionDate, batch.expiryDate),
          unit: batch.quantityUnit ?? null,
          currentQuantity,
          initialQuantity,
          consumedQuantity: Math.max(0, initialQuantity - currentQuantity),
          canViewPrice: canViewPrice(actor),
        },
      });
    } catch (error: unknown) {
      if (!isCurrent()) return;
      setLoadError(error instanceof Error ? error.message : t("messages.loadBatchDetailFailed"));
      setErrorOwnerKey(requestOwnerKey);
    } finally {
      if (isCurrent()) setLoading(false);
    }
  }, [batchId, batchNumber, expectedMaterialTypeId, factoryId, isLiveOwner, ownerKey, t, user]);

  useEffect(() => { void loadData(); }, [loadData]);

  const isCurrentContext = useCallback(() => useAuthStore.getState().user === user && isLiveOwner(), [isLiveOwner, user]);
  const activePrintForOwner = printing
    && printingOwnerKey === ownerKey
    && printRequestRef.current?.ownerKey === ownerKey
    && printRequestRef.current.epoch === ownerEpochRef.current;
  const handlePrintLabel = useCallback(async () => {
    const visibleDetail = detail?.ownerKey === ownerKey ? detail.data : null;
    if (!visibleDetail || activePrintForOwner || !isCurrentContext()) return;
    if (!isCurrentContext()) return;
    const printGeneration = ++printGenerationRef.current;
    const isCurrentPrint = () => isCurrentContext() && printGenerationRef.current === printGeneration;
    printRequestRef.current = { ownerKey, epoch: ownerEpochRef.current, generation: printGeneration };
    setPrinting(true);
    setPrintingOwnerKey(ownerKey);
    setPrintError(null);
    setPrintErrorOwnerKey(null);
    try {
      let labelId: string | null = null;
      let qrContent: string | null = null;
      const resolvedBatchId = visibleDetail.batch.id;
      let generated: Awaited<ReturnType<typeof labelApiClient.generateMaterialBatchLabel>> | null = null;
      try {
        if (!isCurrentPrint()) return;
        generated = await labelApiClient.generateMaterialBatchLabel(resolvedBatchId, factoryId ?? undefined);
      } catch {
        generated = null;
      }
      if (generated) {
        if (!isCurrentPrint()) return;
        if (!generated.success || !generated.data || generated.data.factoryId !== factoryId || generated.data.batchId !== resolvedBatchId) throw new Error(t("messages.printIdentityMismatch"));
        labelId = generated.data.id;
        qrContent = generated.data.traceCode ?? generated.data.labelCode ?? null;
      } else {
        if (!isCurrentPrint()) return;
        const labels = await labelApiClient.getLabelsByBatchId(resolvedBatchId, factoryId ?? undefined);
        if (!isCurrentPrint()) return;
        if (!labels?.success || !Array.isArray(labels.data) || labels.data.some((label) => label.factoryId !== factoryId || label.batchId !== resolvedBatchId)) throw new Error(t("messages.printIdentityMismatch"));
        const active = labels.data.find((label) => label.status === "ACTIVE" || label.status === "PRINTED");
        labelId = active?.id ?? null;
        qrContent = active?.traceCode ?? active?.labelCode ?? null;
      }
      if (!isCurrentPrint()) return;
      if (!qrContent) throw new Error(t("messages.printLabelUnavailable"));
      await printBatchLabel({
        materialName: visibleDetail.batch.materialName ?? t("messages.unknownMaterial"),
        batchNumber: visibleDetail.batch.batchNumber,
        factoryNumber: visibleDetail.batch.factoryNumber,
        originPlace: visibleDetail.batch.originPlace,
        quantity: visibleDetail.currentQuantity,
        quantityUnit: visibleDetail.unit ?? t("inventoryDetail.unknownUnit"),
        qrContent,
        labelCreatedAt: visibleDetail.batch.createdAt,
      });
      if (labelId && isCurrentPrint()) {
        const recorded = await labelApiClient.recordPrint(labelId, factoryId ?? undefined);
        if (!isCurrentPrint()) return;
        if (!recorded?.success || !recorded.data || recorded.data.factoryId !== factoryId || recorded.data.batchId !== resolvedBatchId) throw new Error(t("messages.printIdentityMismatch"));
      }
    } catch (error: unknown) {
      if (isCurrentPrint()) {
        setPrintError(error instanceof Error ? error.message : t("batch.detail.printFailed"));
        setPrintErrorOwnerKey(ownerKey);
      }
    } finally {
      if (isCurrentPrint()) {
        setPrinting(false);
        setPrintingOwnerKey(null);
        printRequestRef.current = null;
      }
    }
  }, [activePrintForOwner, batchId, detail, factoryId, isCurrentContext, ownerKey, t]);

  const visibleDetail = detail?.ownerKey === ownerKey ? detail.data : null;
  const visibleError = errorOwnerKey === ownerKey ? loadError : null;
  const visiblePrintError = printErrorOwnerKey === ownerKey ? printError : null;
  const visiblePrinting = activePrintForOwner;
  const goBackIfCurrent = useCallback(() => { if (isLiveOwner()) navigation.goBack(); }, [isLiveOwner, navigation]);
  const openTraceIfCurrent = useCallback((currentBatch: MaterialBatch) => {
    if (!isLiveOwner()) return;
    navigation.navigate("WHBatchTrace", { batchId: currentBatch.id, batchNumber: currentBatch.batchNumber });
  }, [isLiveOwner, navigation]);
  const openExpireIfCurrent = useCallback((currentBatch: MaterialBatch) => {
    if (!isLiveOwner()) return;
    navigation.navigate("WHExpireHandle", { batchId: currentBatch.id, batchNumber: currentBatch.batchNumber });
  }, [isLiveOwner, navigation]);
  if (loading || (!visibleDetail && !visibleError)) return <StateScreen title={t("batch.detail.title")} message={t("batch.detail.loading")} loading onBack={goBackIfCurrent} />;
  if (!visibleDetail || visibleError) return <StateScreen title={t("batch.detail.title")} message={visibleError || t("batch.detail.noData")} onBack={goBackIfCurrent} onRetry={() => void loadData()} retryLabel={t("messages.retry")} />;

  const batch = visibleDetail.batch;
  const expiryLabel = visibleDetail.expiryDays == null ? t("batch.detail.expiryUnknown") : visibleDetail.expiryDays <= 0 ? t("batch.detail.expired") : `${visibleDetail.expiryDays} ${t("batch.detail.daysRemainingShort")}`;
  return (
    <SafeAreaView style={styles.container} edges={["top"]}>
      <Header title={t("batch.detail.title")} subtitle={batch.batchNumber} onBack={goBackIfCurrent} />
      <ScrollView style={styles.content}>
        <Surface style={styles.card} elevation={1}><Text style={styles.status}>{expiryLabel}</Text><Text style={styles.muted}>{batch.materialName ?? t("messages.unknownMaterial")}</Text></Surface>
        <Section title={t("batch.detail.batchInfo")}>
          <Info label={t("batch.detail.batchNumber")} value={batch.batchNumber} />
          <Info label={t("batch.detail.materialName")} value={batch.materialName} />
          <Info label={t("batch.detail.materialType")} value={batch.materialCategory ?? batch.storageType} />
          <Info label={t("batch.detail.currentQty")} value={`${visibleDetail.currentQuantity} ${visibleDetail.unit ?? t("inventoryDetail.unknownUnit")}`} />
          <Info label={t("batch.detail.initialQty")} value={`${visibleDetail.initialQuantity} ${visibleDetail.unit ?? t("inventoryDetail.unknownUnit")}`} />
          <Info label={t("batch.detail.consumed")} value={`${visibleDetail.consumedQuantity} ${visibleDetail.unit ?? t("inventoryDetail.unknownUnit")}`} />
        </Section>
        <Section title={t("batch.detail.timeInfo")}>
          <Info label={t("batch.detail.inboundDate")} value={dateOnly(batch.inboundDate || batch.createdAt)} />
          <Info label={t("batch.detail.productionDate")} value={dateOnly(batch.productionDate)} />
          <Info label={t("batch.detail.shelfLife")} value={visibleDetail.shelfLifeDays == null ? null : `${visibleDetail.shelfLifeDays} ${t("batch.detail.daysUnit")}`} />
          <Info label={t("batch.detail.expiryDate")} value={dateOnly(batch.expiryDate)} />
          <Info label={t("batch.detail.statusLabel")} value={expiryLabel} />
        </Section>
        <Section title={t("batch.detail.storageInfo")}>
          <Info label={t("batch.detail.location")} value={batch.storageLocation} />
          <Info label={t("batch.detail.storageType")} value={batch.storageType} />
          <Info label={t("batch.detail.temperature")} value={null} />
          <Info label={t("batch.detail.qualityGrade")} value={batch.qualityGrade} />
        </Section>
        <Section title={t("batch.detail.sourceInfo")}>
          <Info label={t("batch.detail.supplier")} value={batch.supplierName} />
          <Info label={t("batch.detail.inboundNumber")} value={batch.batchNumber} />
          <Info label={t("batch.detail.inspector")} value={null} />
          {visibleDetail.canViewPrice && batch.unitPrice != null ? <Info label={t("batch.detail.unitPrice")} value={`¥${batch.unitPrice}`} /> : null}
          {visibleDetail.canViewPrice && batch.movingAvgPrice != null ? <Info label={t("batch.detail.movingAvgPrice")} value={`¥${batch.movingAvgPrice}`} /> : null}
        </Section>
        <Section title={t("batch.detail.aiAnalysis")}><Text style={styles.muted}>{t("batch.detail.analysisUnavailable")}</Text></Section>
        <Section title={t("batch.detail.consumptionLog")}><Text style={styles.muted}>{t("batch.detail.consumptionUnavailable")}</Text></Section>
        <View style={styles.actions}>
          <TouchableOpacity style={[styles.printButton, visiblePrinting && styles.disabled]} onPress={() => void handlePrintLabel()} disabled={visiblePrinting} accessibilityRole="button"><MaterialCommunityIcons name="printer" size={22} color="#fff" /><Text style={styles.printText}>{visiblePrinting ? t("batch.detail.printing") : t("batch.detail.print")}</Text></TouchableOpacity>
          {visiblePrintError ? <Text style={styles.error}>{visiblePrintError}</Text> : null}
          {visiblePrintError ? <Button mode="outlined" onPress={() => void handlePrintLabel()}>{t("messages.retry")}</Button> : null}
          <Button mode="outlined" onPress={() => openTraceIfCurrent(batch)}>{t("batch.detail.actions.trace")}</Button>
          <Button mode="outlined" onPress={() => openExpireIfCurrent(batch)}>{t("batch.detail.actions.expireHandle")}</Button>
        </View>
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

function Section({ title, children }: { title: string; children: React.ReactNode }) { return <View style={styles.section}><Text style={styles.sectionTitle}>{title}</Text>{children}</View>; }
function Info({ label, value }: { label: string; value: string | number | null | undefined }) { return <View style={styles.infoRow}><Text style={styles.infoLabel}>{label}</Text><Text style={styles.infoValue}>{value ?? "—"}</Text></View>; }

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
  status: { fontSize: 20, fontWeight: "700", color: "#388e3c" },
  muted: { color: "#666", textAlign: "center" },
  section: { backgroundColor: "#fff", marginHorizontal: 16, marginBottom: 12, borderRadius: 12, padding: 16 },
  sectionTitle: { fontSize: 16, fontWeight: "700", color: "#333", marginBottom: 10 },
  infoRow: { flexDirection: "row", justifyContent: "space-between", gap: 12, paddingVertical: 7 },
  infoLabel: { color: "#777", flex: 1 },
  infoValue: { color: "#333", flex: 1, textAlign: "right" },
  actions: { marginHorizontal: 16, gap: 12 },
  printButton: { minHeight: 48, borderRadius: 8, backgroundColor: "#4CAF50", flexDirection: "row", alignItems: "center", justifyContent: "center", gap: 8 },
  disabled: { opacity: 0.6 },
  printText: { color: "#fff", fontWeight: "700" },
  error: { color: "#b71c1c", textAlign: "center" },
  state: { flex: 1, alignItems: "center", justifyContent: "center", gap: 14, padding: 24 },
  stateText: { color: "#555", textAlign: "center" },
  bottomSpace: { height: 24 },
});

export default WHBatchDetailScreen;
