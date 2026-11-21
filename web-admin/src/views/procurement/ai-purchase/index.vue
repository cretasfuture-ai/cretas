<script setup lang="ts">
import { CompatAlert, CompatDatePicker, CompatForm, CompatFormItem, CompatOption, CompatSelect } from '@/components/compat';
import { useCompatMessages } from '@/components/compat/messages';
const { ElMessage } = useCompatMessages();
import { useT } from '@/i18n-pilot/useT';
const { t } = useT();

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAuthStore } from '@/store/modules/auth';
import { get } from '@/api/request';
import {
  bindPurchaseTimer, getPurchaseTimer, reconcilePurchaseTimer, type PurchaseTimerBinding,
  getControlledPurchaseStatus,
  authorizeControlledPurchaseForLater,
  executeDeferredControlledPurchase,
  previewControlledPurchase,
  revokeControlledPurchase,
  type ControlledPurchaseParams,
  type ControlledPurchaseOutcome,
} from '@/api/controlledPurchase';
import type { IntentExecuteResponse } from '@/api/smartbi/intent-chat';
import AiOperationCard from '@/views/smart-bi/components/chat/AiOperationCard.vue';

interface Option { id: string; name: string; code?: string | null }

const router = useRouter();
const auth = useAuthStore();
const factoryId = computed(() => String(auth.factoryId || ''));
const userId = computed(() => String(auth.user?.id || ''));
const scopeKey = computed(() => `${factoryId.value}:${userId.value}`);
let generation = 0;
let mounted = true;
const isCurrent = (scope: string, run: number) => mounted && scopeKey.value === scope && generation === run;
const suppliers = ref<Option[]>([]);
const materials = ref<Option[]>([]);
const loading = ref(false);
const recoveryLoading = ref(false);
const recoveryReady = ref(false);
const recoveryState = ref('');
const draftOrderId = ref('');
const cancelInFlight = ref(false);
const timerBinding = ref<PurchaseTimerBinding | null>(null);
const timerExecuteAt = ref('');
const timerInFlight = ref(false);
const timerUncertain = ref(false);

async function schedulePurchase(): Promise<void> {
  if (!grantId.value || recoveryVersion.value == null || !timerExecuteAt.value || timerInFlight.value || timerBinding.value || timerUncertain.value) return;
  const scope = scopeKey.value; const run = generation;
  timerInFlight.value = true;
  try {
    const result = await bindPurchaseTimer(factoryId.value, grantId.value, recoveryVersion.value, new Date(timerExecuteAt.value).toISOString());
    if (isCurrent(scope, run)) { timerBinding.value = result; blockedReason.value = '一次定时已绑定；等待实际触发，禁止重复安排或手动执行。'; }
  } catch (error) {
    if (isCurrent(scope, run)) { timerUncertain.value = true; blockedReason.value = error instanceof Error ? error.message : '定时绑定结果未知，请刷新原授权状态'; }
  } finally { if (isCurrent(scope, run)) timerInFlight.value = false; }
}

async function reconcileTimer(): Promise<void> {
  if (!grantId.value || !timerBinding.value || !['UNKNOWN', 'PENDING'].includes(timerBinding.value.state) || timerInFlight.value) return;
  const scope = scopeKey.value; const run = generation;
  timerInFlight.value = true;
  try {
    const result = await reconcilePurchaseTimer(factoryId.value, grantId.value);
    if (isCurrent(scope, run)) timerBinding.value = result;
  } catch (error) { if (isCurrent(scope, run)) blockedReason.value = error instanceof Error ? error.message : '持久结果核对失败'; }
  finally { if (isCurrent(scope, run)) timerInFlight.value = false; }
}
const deferredInFlight = ref(false);
const blockedReason = ref('');
const grantId = ref('');
const recoveryVersion = ref<number | null>(null);
const previewResponse = ref<IntentExecuteResponse | null>(null);

const form = ref<ControlledPurchaseParams>({
  supplierId: '', materialTypeId: '', quantity: '', unit: 'kg', unitPrice: '',
  currency: 'CNY', taxRate: '13', amountCap: '', expectedDeliveryDate: '', remark: '',
});
const previewed = computed(() => Boolean(previewResponse.value));
const supplierName = computed(() => suppliers.value.find((x) => x.id === form.value.supplierId)?.name || form.value.supplierId);
const materialName = computed(() => materials.value.find((x) => x.id === form.value.materialTypeId)?.name || form.value.materialTypeId);
const amountText = computed(() => {
  if (!form.value.quantity || !form.value.unitPrice || !form.value.taxRate) {
    return { net: '--', tax: '--', gross: '--' };
  }
  const quantity = Number(form.value.quantity);
  const price = Number(form.value.unitPrice);
  const rate = Number(form.value.taxRate);
  if (!Number.isFinite(quantity) || !Number.isFinite(price) || !Number.isFinite(rate)) {
    return { net: '--', tax: '--', gross: '--' };
  }
  const net = (quantity * price).toFixed(2);
  const gross = (Number(net) * (1 + rate / 100)).toFixed(2);
  return { net, tax: (Number(gross) - Number(net)).toFixed(2), gross };
});
const canonicalRows = computed(() => {
  const action = previewResponse.value?.confirmableAction;
  const source = (action?.newValues || action?.previewData) as Record<string, unknown> | null | undefined;
  if (!source) return [];
  const request = source.purchaseOrderRequest as Record<string, unknown> | undefined;
  const item = Array.isArray(request?.items) ? request.items[0] as Record<string, unknown> | undefined : undefined;
  const canonicalSupplierId = String(request?.supplierId || '');
  const canonicalMaterialId = String(item?.materialTypeId || '');
  const canonicalSupplierName = suppliers.value.find((x) => x.id === canonicalSupplierId)?.name
    ? `${suppliers.value.find((x) => x.id === canonicalSupplierId)?.name} (ID: ${canonicalSupplierId})` : canonicalSupplierId;
  const canonicalMaterialName = materials.value.find((x) => x.id === canonicalMaterialId)?.name
    ? `${materials.value.find((x) => x.id === canonicalMaterialId)?.name} (ID: ${canonicalMaterialId})` : canonicalMaterialId;
  const format = (value: unknown) => value == null || value === '' ? '--' : String(value);
  return [
    ['供应商', canonicalSupplierName || format(request?.supplierId)],
    ['原料', canonicalMaterialName || format(item?.materialTypeId)],
    ['数量', `${format(item?.quantity)} kg`],
    ['未税单价', `${format(item?.unitPrice)} CNY/kg`],
    ['税率', `${format(item?.taxRate)}%`],
    ['币种', format(source.currency)],
    ['未税金额', `${format(source.net)} CNY`],
    ['税额', `${format(source.tax)} CNY`],
    ['含税合计', `${format(source.gross)} CNY`],
    ['含税预算上限', `${format(source.amountCap)} CNY`],
  ];
});
const storageKey = computed(() => `cretas:controlled-purchase:${scopeKey.value}`);

async function loadReferences(): Promise<void> {
  if (!factoryId.value) return;
  const scope = scopeKey.value;
  const run = generation;
  const [supplierResponse, materialResponse] = await Promise.all([
    get<Option[]>(`/${factoryId.value}/suppliers/active`),
    get<Option[]>(`/${factoryId.value}/raw-material-types/active`),
  ]);
  if (!supplierResponse.success || !materialResponse.success) throw new Error('供应商或原料读取失败，页面已锁定');
  if (!isCurrent(scope, run)) return;
  suppliers.value = supplierResponse.data ?? [];
  materials.value = materialResponse.data ?? [];
}

async function recover(): Promise<void> {
  if (!factoryId.value) { recoveryReady.value = true; return; }
  const saved = localStorage.getItem(storageKey.value);
  if (!saved) { recoveryReady.value = true; return; }
  const scope = scopeKey.value;
  const run = generation;
  try {
    const parsed = JSON.parse(saved) as { grantId?: string };
    if (!parsed.grantId) return;
    recoveryLoading.value = true;
    grantId.value = parsed.grantId;
    const outcome = await getControlledPurchaseStatus(factoryId.value, parsed.grantId);
    if (!isCurrent(scope, run)) return;
    const timer = await getPurchaseTimer(factoryId.value, parsed.grantId);
    if (!isCurrent(scope, run)) return;
    timerBinding.value = timer;
    timerUncertain.value = false;
    recoveryState.value = outcome.state;
    recoveryVersion.value = outcome.version ?? null;
    if (String(outcome.state).toUpperCase() === 'PENDING') {
      previewResponse.value = null;
      blockedReason.value = '已有一笔采购已授权稍后执行，请先执行或撤销当前授权。';
    }
    if (outcome.state === 'DRAFT' && outcome.purchaseOrderId) {
      draftOrderId.value = outcome.purchaseOrderId;
      blockedReason.value = '采购草稿已创建，请核对订单后再开始下一笔。';
      return;
    }
    if (outcome.state === 'DRAFT' && !outcome.purchaseOrderId) {
      recoveryState.value = 'UNKNOWN';
      blockedReason.value = '采购草稿状态缺少订单号，请刷新授权状态后再继续。';
      return;
    }
    if (outcome.state === 'FAILED') {
      previewResponse.value = null;
      blockedReason.value = '服务器已确认本次采购失败。核对失败原因后，可明确开始一笔新采购。';
      return;
    }
    if (outcome.state !== 'REVOKED' && outcome.state !== 'EXPIRED') {
      blockedReason.value = outcome.state === 'UNKNOWN'
        ? '采购结果待核对，请先完成状态核对后再继续。'
        : '已有一笔受控采购正在处理，请先取消或完成它。';
    } else {
      if (timerBinding.value) {
        blockedReason.value = `原授权已${outcome.state === 'REVOKED' ? '撤销' : '过期'}；请读取定时任务终态后核对结果。`;
        return;
      }
      localStorage.removeItem(storageKey.value);
      grantId.value = '';
      recoveryState.value = '';
      recoveryVersion.value = null;
      previewResponse.value = null;
      blockedReason.value = '';
      draftOrderId.value = '';
    }
  } catch (error) {
    if (!isCurrent(scope, run)) return;
    blockedReason.value = error instanceof Error ? error.message : '无法读取采购恢复状态，请联系管理员。';
    recoveryState.value = 'RECOVERY_ERROR';
  } finally {
    if (isCurrent(scope, run)) { recoveryLoading.value = false; recoveryReady.value = true; }
  }
}

async function refreshRecovery(): Promise<void> {
  if (!grantId.value) return;
  blockedReason.value = '';
  await recover();
}

function acknowledgeDraft(): void {
  timerBinding.value = null; timerUncertain.value = false; timerExecuteAt.value = '';
  localStorage.removeItem(storageKey.value);
  grantId.value = '';
  recoveryState.value = '';
  draftOrderId.value = '';
  previewResponse.value = null;
  recoveryVersion.value = null;
  blockedReason.value = '';
}

async function submitPreview(): Promise<void> {
  if (loading.value || previewed.value || blockedReason.value
    || (grantId.value && ['PENDING', 'UNKNOWN', 'EXECUTING', 'RECOVERY_ERROR'].includes(recoveryState.value))
    || !factoryId.value || !recoveryReady.value || recoveryLoading.value) return;
  if (!form.value.supplierId || !form.value.materialTypeId || !form.value.quantity || !form.value.unitPrice || !form.value.amountCap) {
    ElMessage.warning(t('procurement.ai_purchase_index.message_900a980336c9'));
    return;
  }
  loading.value = true;
  const scope = scopeKey.value;
  const run = generation;
  try {
    const response = await previewControlledPurchase(factoryId.value, { ...form.value });
    if (!isCurrent(scope, run)) return;
    const resultId = String((response.resultData as Record<string, unknown> | null | undefined)?.grantId || '').trim();
    const previewId = String((response.confirmableAction?.previewData as Record<string, unknown> | null | undefined)?.grantId || '').trim();
    const id = resultId && resultId === previewId ? resultId : '';
    if (String(response.status).toUpperCase() !== 'PREVIEW'
      || String(response.intentCode || '').toUpperCase() !== 'PURCHASE_ORDER_CREATE'
      || !/^preview-[1-9][0-9]*$/.test(id) || !response.confirmableAction) {
      throw new Error(response.message || '采购预览未通过，未生成确认凭据');
    }
    grantId.value = id;
    recoveryState.value = 'PENDING';
    recoveryVersion.value = null;
    previewResponse.value = response;
    localStorage.setItem(storageKey.value, JSON.stringify({ grantId: id }));
  } catch (error) { if (isCurrent(scope, run)) ElMessage.error(error instanceof Error ? error.message : '采购预览失败'); }
  finally { if (isCurrent(scope, run)) loading.value = false; }
}

async function cancelPreview(): Promise<boolean> {
  if (!grantId.value || cancelInFlight.value) return false;
  cancelInFlight.value = true;
  const scope = scopeKey.value;
  const run = generation;
  try {
    const outcome = await revokeControlledPurchase(factoryId.value, grantId.value, recoveryVersion.value, '用户取消受控采购预览');
    if (!isCurrent(scope, run)) return false;
    if (outcome.state !== 'REVOKED') throw new Error('服务器未确认撤销，当前采购仍被锁定');
    if (timerUncertain.value && !timerBinding.value) {
      try {
        const persistedTimer = await getPurchaseTimer(factoryId.value, grantId.value);
        if (!isCurrent(scope, run)) return false;
        timerBinding.value = persistedTimer;
        timerUncertain.value = false;
      } catch (error) {
        if (!isCurrent(scope, run)) return false;
        recoveryState.value = 'REVOKED';
        recoveryVersion.value = outcome.version ?? null;
        previewResponse.value = null;
        blockedReason.value = error instanceof Error ? `授权已撤销，但定时绑定仍待核对：${error.message}` : '授权已撤销，但定时绑定仍待核对';
        return true;
      }
    }
    if (timerBinding.value) {
      recoveryState.value = 'REVOKED';
      recoveryVersion.value = outcome.version ?? null;
      previewResponse.value = null;
      blockedReason.value = '采购授权已撤销；保留定时任务参考，等待真实触发拒绝或核对错过任务。';
      return true;
    }
    localStorage.removeItem(storageKey.value);
    grantId.value = '';
    recoveryState.value = '';
    blockedReason.value = '';
    draftOrderId.value = '';
    previewResponse.value = null;
    timerBinding.value = null; timerUncertain.value = false;
    return true;
  } catch (error) { if (isCurrent(scope, run)) blockedReason.value = error instanceof Error ? error.message : '撤销失败，请先核对授权状态'; }
  finally { if (isCurrent(scope, run)) cancelInFlight.value = false; }
  return false;
}

async function authorizeLater(): Promise<void> {
  const proof = previewResponse.value?.confirmableAction;
  if (!grantId.value || !proof || deferredInFlight.value) return;
  const scope = scopeKey.value;
  const run = generation;
  deferredInFlight.value = true;
  try {
    const outcome = await authorizeControlledPurchaseForLater(factoryId.value, proof.confirmToken, proof.commandDigest, proof.expiresAt);
    if (!isCurrent(scope, run)) return;
    if (outcome.grantId !== grantId.value || outcome.state !== 'PENDING') throw new Error('服务器未确认稍后执行授权');
    recoveryState.value = outcome.state;
    recoveryVersion.value = outcome.version ?? null;
    previewResponse.value = null;
    blockedReason.value = '已授权稍后执行，请先执行或撤销当前授权。';
    ElMessage.success(t('procurement.ai_purchase_index.message_0beb0fc62ff7'));
  } catch (error) {
    if (!isCurrent(scope, run)) return;
    recoveryState.value = 'UNKNOWN';
    previewResponse.value = null;
    blockedReason.value = error instanceof Error ? error.message : '授权状态未知，请刷新后再处理';
  } finally { if (isCurrent(scope, run)) deferredInFlight.value = false; }
}

async function executeLater(): Promise<void> {
  if (!grantId.value || recoveryVersion.value == null || deferredInFlight.value || timerBinding.value || timerUncertain.value || timerInFlight.value) return;
  const scope = scopeKey.value;
  const run = generation;
  deferredInFlight.value = true;
  try {
    const outcome = await executeDeferredControlledPurchase(factoryId.value, grantId.value, recoveryVersion.value, 'skill');
    if (!isCurrent(scope, run)) return;
    if (outcome.grantId !== grantId.value || outcome.state !== 'DRAFT' || !outcome.purchaseOrderId) throw new Error('采购结果凭据不匹配，请刷新状态');
    draftOrderId.value = outcome.purchaseOrderId;
    recoveryState.value = 'DRAFT';
    blockedReason.value = '采购草稿已创建，请核对订单后再开始下一笔。';
  } catch (error) {
    if (!isCurrent(scope, run)) return;
    recoveryState.value = 'UNKNOWN';
    blockedReason.value = error instanceof Error ? error.message : '执行结果未知，请刷新授权状态后再继续';
  } finally { if (isCurrent(scope, run)) deferredInFlight.value = false; }
}

function handleConfirmed(result: IntentExecuteResponse): void {
  const status = String(result.status || '').toUpperCase();
  if (status === 'SUCCESS') {
    const outcome = result.resultData as unknown as ControlledPurchaseOutcome | null;
    const resultGrantId = String((result.resultData as Record<string, unknown> | null | undefined)?.grantId || '').trim();
    if (resultGrantId === grantId.value && outcome?.purchaseOrderId) {
      draftOrderId.value = outcome.purchaseOrderId;
      recoveryState.value = 'DRAFT';
      blockedReason.value = '采购草稿已创建，请核对订单后再开始下一笔。';
    } else {
      recoveryState.value = 'UNKNOWN';
      blockedReason.value = '采购确认结果缺少匹配订单凭据，请刷新授权状态后再继续。';
    }
  } else if (status === 'OUTCOME_UNKNOWN') {
    recoveryState.value = 'UNKNOWN';
    blockedReason.value = '采购结果待核对，请查看授权状态；禁止重新下单。';
  } else {
    recoveryState.value = 'UNKNOWN';
    blockedReason.value = '采购确认未完成，请刷新授权状态后再继续。';
  }
}

function handleUncertain(): void {
  recoveryState.value = 'UNKNOWN';
  blockedReason.value = '确认凭据已超时，结果待核对；请刷新授权状态后再继续。';
}

function handleRecovered(outcome: ControlledPurchaseOutcome): void {
  if (outcome.grantId !== grantId.value) {
    recoveryState.value = 'UNKNOWN';
    blockedReason.value = '处理参考无法与当前采购核对，请勿重复提交。';
    return;
  }
  if (String(outcome.state).toUpperCase() !== 'PENDING') return;
  recoveryState.value = 'PENDING';
  recoveryVersion.value = outcome.version ?? null;
  blockedReason.value = '采购处理仍待确认，请先撤销当前授权后再继续。';
  previewResponse.value = null;
}

onMounted(async () => {
  await initialize();
});
async function initialize(): Promise<void> {
  const scope = scopeKey.value;
  const run = generation;
  recoveryReady.value = false;
  await recover();
  if (!isCurrent(scope, run)) return;
  try { await loadReferences(); }
  catch (error) {
    if (isCurrent(scope, run) && !blockedReason.value) blockedReason.value = error instanceof Error ? error.message : '基础数据读取失败，页面已锁定';
  }
  if (isCurrent(scope, run)) recoveryReady.value = true;
}
watch(scopeKey, () => {
  timerBinding.value = null; timerExecuteAt.value = ''; timerInFlight.value = false; timerUncertain.value = false;
  generation += 1;
  grantId.value = '';
  previewResponse.value = null;
  blockedReason.value = '';
  recoveryState.value = '';
  draftOrderId.value = '';
  recoveryVersion.value = null;
  suppliers.value = [];
  materials.value = [];
  loading.value = false;
  recoveryLoading.value = false;
  cancelInFlight.value = false;
  deferredInFlight.value = false;
  form.value = {
    supplierId: '', materialTypeId: '', quantity: '', unit: 'kg', unitPrice: '',
    currency: 'CNY', taxRate: '13', amountCap: '', expectedDeliveryDate: '', remark: '',
  };
  void initialize();
});
onBeforeUnmount(() => { mounted = false; generation += 1; });
</script>

<template>
  <div class="page-container">
    <div class="page-header"><div><h2>{{ t('procurement.ai_purchase_index.text_6f367e368a2f') }}</h2><p>{{ t('procurement.ai_purchase_index.text_ffcf01f4dab5') }}</p></div></div>
    <CompatAlert v-if="recoveryLoading" :title="t('procurement.ai_purchase_index.title_f81d293acb67')" color="info" :close="false" />
    <CompatAlert v-if="blockedReason" :title="blockedReason" color="warning" :close="false" class="state-alert" />
    <div v-if="draftOrderId" class="recovery-actions"><UButton variant="link" color="primary" @click="router.push(`/procurement/orders/${encodeURIComponent(draftOrderId)}`)">{{ t('procurement.ai_purchase_index.action_a434a48d1fce') }}</UButton><UButton color="success" @click="acknowledgeDraft">{{ t('procurement.ai_purchase_index.action_09494dcdf6fb') }}</UButton></div>
    <div v-else-if="recoveryState === 'FAILED'" class="recovery-actions"><UButton color="neutral" variant="outline" @click="acknowledgeDraft">{{ t('procurement.ai_purchase_index.action_936c6e259a76') }}</UButton></div>
    <div v-else-if="grantId && !previewResponse && recoveryState === 'PENDING'" class="recovery-actions">
      <UButton v-if="!timerBinding && !timerUncertain" color="primary" :disabled="recoveryVersion == null || timerInFlight" :loading="deferredInFlight" @click="executeLater">{{ t('procurement.ai_purchase_index.action_621f6e8a3d55') }}</UButton><UButton color="error" :loading="cancelInFlight" @click="cancelPreview">{{ t('procurement.ai_purchase_index.action_cd695e010e1f') }}</UButton><UButton color="neutral" variant="outline" v-if="timerBinding || timerUncertain || recoveryVersion == null" :loading="recoveryLoading" @click="refreshRecovery">{{ t('procurement.ai_purchase_index.action_f9870fade6c7') }}</UButton><span v-if="recoveryVersion == null" class="state-hint">{{ t('procurement.ai_purchase_index.text_205da0dc660a') }}</span>
      <section v-if="!timerBinding && !timerUncertain" :aria-label="t('procurement.ai_purchase_index.aria_label_64d862004290')">
        <label for="purchase-timer-at">{{ t('procurement.ai_purchase_index.text_69b08e273626') }}</label>
        <input id="purchase-timer-at" v-model="timerExecuteAt" type="datetime-local" step="1" :disabled="timerInFlight || recoveryVersion == null" />
        <UButton color="neutral" variant="outline" :disabled="!timerExecuteAt || recoveryVersion == null || deferredInFlight || timerInFlight" @click="schedulePurchase">{{ t('procurement.ai_purchase_index.action_e72241a4422e') }}</UButton>
      </section>
    </div>
    <section v-if="timerBinding" :aria-label="t('procurement.ai_purchase_index.aria_label_fe81b4b03a25')">
      <p>{{ t('procurement.ai_purchase_index.text_3c39e9bde120') }} {{ timerBinding.taskId }} {{ t('procurement.ai_purchase_index.text_27c7390016dd') }} {{ timerBinding.executeAt }} {{ t('procurement.ai_purchase_index.text_8f9b944945d0') }} {{ timerBinding.expiresAt }}</p>
      <p>{{ t('procurement.ai_purchase_index.text_8d2859df5921') }} {{ timerBinding.serverNow }} {{ t('procurement.ai_purchase_index.text_644bc8684d0b') }} {{ timerBinding.serverZone }}</p>
      <p>{{ t('procurement.ai_purchase_index.text_1b7f6d458fc7') }} {{ timerBinding.state }} · {{ timerBinding.failureReason }}</p>
      <pre v-if="timerBinding.approvedSnapshot">{{ timerBinding.approvedSnapshot }}</pre>
      <p v-if="timerBinding.state === 'UNKNOWN'">{{ t('procurement.ai_purchase_index.text_1552ee060142') }}</p>
      <UButton color="neutral" variant="outline" v-if="timerBinding.state === 'UNKNOWN'" :loading="timerInFlight" @click="reconcileTimer">{{ t('procurement.ai_purchase_index.action_62c29c103017') }}</UButton>
      <UButton color="neutral" variant="outline" v-if="timerBinding.state === 'PENDING'" :loading="timerInFlight" @click="reconcileTimer">{{ t('procurement.ai_purchase_index.action_3f737191bcd2') }}</UButton>
      <UButton color="neutral" variant="outline" :loading="recoveryLoading" @click="refreshRecovery">{{ t('procurement.ai_purchase_index.action_c31b7e884942') }}</UButton>
      <UButton color="neutral" variant="outline" v-if="['REVOKED', 'EXPIRED'].includes(recoveryState) && ['REFUSED', 'SUCCEEDED'].includes(timerBinding.state)" @click="acknowledgeDraft">{{ t('procurement.ai_purchase_index.action_f4237172baae') }}</UButton>
    </section>
    <div v-else-if="grantId && ['UNKNOWN', 'EXECUTING', 'RECOVERY_ERROR'].includes(recoveryState)" class="recovery-actions"><UButton color="warning" :loading="recoveryLoading" @click="refreshRecovery">{{ t('procurement.ai_purchase_index.action_f9870fade6c7') }}</UButton></div>
    <div v-if="grantId && timerUncertain" class="recovery-actions"><UButton color="neutral" variant="outline" :loading="recoveryLoading" @click="refreshRecovery">{{ t('procurement.ai_purchase_index.action_3b20c383e437') }}</UButton><span>{{ t('procurement.ai_purchase_index.text_3d180ba044b5') }}</span></div>
    <UCard  class="content-card">
      <CompatForm label-position="top" class="purchase-form">
      <CompatFormItem :label="t('procurement.ai_purchase_index.label_033ed5945254')"><CompatSelect v-model="form.supplierId" filterable :disabled="!recoveryReady || previewed || Boolean(blockedReason)" :placeholder="t('procurement.ai_purchase_index.placeholder_a0106ac4aa3d')"><CompatOption v-for="item in suppliers" :key="item.id" :label="item.name" :value="item.id" /></CompatSelect></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_e63280c7ebcc')"><CompatSelect v-model="form.materialTypeId" filterable :disabled="!recoveryReady || previewed || Boolean(blockedReason)" :placeholder="t('procurement.ai_purchase_index.placeholder_4686e83b3f8a')"><CompatOption v-for="item in materials" :key="item.id" :label="item.name" :value="item.id" /></CompatSelect></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_4ca33c040687')"><UInput v-model="form.quantity" type="number" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" /></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_30a91dc43c1d')"><UInput v-model="form.unitPrice" type="number" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" /></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_9401d14fdcb4')"><CompatSelect v-model="form.taxRate" :disabled="!recoveryReady || previewed || Boolean(blockedReason)"><CompatOption label="0%" value="0" /><CompatOption label="13%" value="13" /></CompatSelect></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_c2fe0a2fa65d')"><UInput v-model="form.amountCap" type="number" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" /></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_43b604ae06d6')"><CompatDatePicker v-model="form.expectedDeliveryDate" type="date" value-format="YYYY-MM-DD" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" /></CompatFormItem>
        <CompatFormItem :label="t('procurement.ai_purchase_index.label_daede9881787')"><el-input v-model="form.remark" type="textarea" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" /></CompatFormItem>
      </CompatForm>
      <div class="snapshot" :aria-label="t('procurement.ai_purchase_index.aria_label_007b9237263c')"><span>{{ t('procurement.ai_purchase_index.text_51b7df601832') }}{{ supplierName }}</span><span>{{ t('procurement.ai_purchase_index.text_af6d15773688') }}{{ materialName }}</span><span>{{ t('procurement.ai_purchase_index.text_3a55379b7d5d') }}{{ amountText.net }} CNY</span><span>{{ t('procurement.ai_purchase_index.text_8426cec51e56') }}{{ amountText.tax }} CNY</span><strong>{{ t('procurement.ai_purchase_index.text_6ca6138fb2ae') }}{{ amountText.gross }} CNY</strong></div>
      <div class="actions"><UButton color="primary" :loading="loading" :disabled="!recoveryReady || previewed || Boolean(blockedReason)" @click="submitPreview">{{ t('procurement.ai_purchase_index.action_59044824da36') }}</UButton></div>
    </UCard>
    <AiOperationCard v-if="previewResponse" :response="previewResponse" :factory-id="factoryId" :cancel-handler="cancelPreview" @confirmed="handleConfirmed" @uncertain="handleUncertain" @recovered="handleRecovered" />
    <div v-if="previewResponse" class="deferred-actions"><UButton color="warning" :loading="deferredInFlight" @click="authorizeLater">{{ t('procurement.ai_purchase_index.action_1a4c84b81811') }}</UButton></div>
    <UCard v-if="previewResponse && canonicalRows.length"  class="snapshot-card">
      <h3>{{ t('procurement.ai_purchase_index.text_8466501a3347') }}</h3>
      <div class="canonical-grid"><template v-for="row in canonicalRows" :key="row[0]"><span>{{ row[0] }}</span><strong>{{ row[1] }}</strong></template></div>
    </UCard>
  </div>
</template>

<style scoped lang="scss">
.page-container { padding: 20px; background: #f4f6f9; min-height: 100%; }
.page-header { margin-bottom: 16px; h2 { margin: 0 0 6px; color: #1a2332; } p { margin: 0; color: #7a8599; } }
.content-card { max-width: 960px; border-radius: 10px; }
.purchase-form { display: grid; grid-template-columns: repeat(2, minmax(220px, 1fr)); gap: 0 20px; }
.snapshot { display: flex; flex-wrap: wrap; gap: 12px 24px; padding: 16px; margin-top: 8px; border: 1px solid #edf2f7; border-radius: 8px; color: #596579; }
.snapshot strong { color: #1b65a8; }
.actions { display: flex; justify-content: flex-end; margin-top: 20px; gap: 8px; }
.state-alert { margin: 12px 0; max-width: 960px; }
.snapshot-card { max-width: 960px; margin-top: 16px; border-radius: 10px; h3 { margin: 0 0 12px; color: #1a2332; font-size: 15px; } }
.canonical-grid { display: grid; grid-template-columns: 140px 1fr; gap: 8px 16px; color: #7a8599; strong { color: #1a2332; font-weight: 500; } }
@media (max-width: 720px) { .purchase-form { grid-template-columns: 1fr; } }
</style>
