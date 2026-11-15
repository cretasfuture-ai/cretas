<script setup lang="ts">
/**
 * 平台「工厂管理 › 外部软件」：维护外部软件接口授权（P10，平台专属表 factory_external_integrations）。
 * 工厂端没有任何写入口；推送凭证明文只在签发那次显示一次，库里只留 SHA-256。
 */
import { ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  getPlatformIntegrations,
  issuePlatformPushToken,
  updatePlatformIntegration,
  type PlatformIntegrationRow,
} from '@/api/externalIntegrations';

const props = defineProps<{ factoryId: string }>();
const emit = defineEmits<{ (e: 'writing-change', writing: boolean): void }>();

const rows = ref<PlatformIntegrationRow[]>([]);
const loading = ref(false);
const saving = ref(false);
const loadError = ref('');
const forms = ref<Record<string, { sourceOrgId: string; note: string }>>({});
const issued = ref<{ system: string; token: string } | null>(null);

const STATUS_LABEL: Record<PlatformIntegrationRow['status'], string> = { UNSET: '未授权', ACTIVE: '已授权', SUSPENDED: '已暂停' };

async function load() {
  if (!props.factoryId) return;
  loading.value = true;
  loadError.value = '';
  try {
    const res = await getPlatformIntegrations(props.factoryId);
    if (!res.success) throw new Error(res.message || '读取失败');
    rows.value = res.data ?? [];
    forms.value = Object.fromEntries(rows.value.map((r) => [r.system, { sourceOrgId: r.sourceOrgId ?? '', note: r.note ?? '' }]));
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '读取失败';
  } finally {
    loading.value = false;
  }
}

async function save(row: PlatformIntegrationRow, status: 'ACTIVE' | 'SUSPENDED') {
  const f = forms.value[row.system];
  if (status === 'ACTIVE' && !f.sourceOrgId.trim()) {
    ElMessage({ type: 'error', message: '授权前要填外部系统里的来源组织 id', duration: 0, showClose: true });
    return;
  }
  if (status === 'SUSPENDED') {
    try {
      await ElMessageBox.confirm(`暂停后，${props.factoryId} 的「${row.label}」对照列与外部软件页立即不再显示，推送也会被拒收。`, `暂停 ${row.label} 授权`, { type: 'warning' });
    } catch { return; }
  }
  saving.value = true;
  emit('writing-change', true);
  try {
    const res = await updatePlatformIntegration(props.factoryId, row.system, {
      status, sourceOrgId: f.sourceOrgId.trim() || null, note: f.note.trim() || null,
    });
    if (!res.success) throw new Error(res.message || '保存失败');
    rows.value = res.data ?? rows.value;
    ElMessage.success(status === 'ACTIVE' ? '已授权' : '已暂停');
  } catch (e) {
    ElMessage({ type: 'error', message: e instanceof Error ? e.message : '保存失败', duration: 0, showClose: true });
  } finally {
    saving.value = false;
    emit('writing-change', false);
  }
}

async function issue(row: PlatformIntegrationRow) {
  try {
    await ElMessageBox.confirm(row.hasPushToken ? '重新签发后旧凭证立即失效，本机推送要换成新凭证。' : '凭证只显示这一次，请直接存进本机 DPAPI（set-token），不要发到聊天或写进文件。',
      `签发 ${row.label} 推送凭证`, { type: 'warning' });
  } catch { return; }
  saving.value = true;
  emit('writing-change', true);
  try {
    const res = await issuePlatformPushToken(props.factoryId, row.system);
    if (!res.success) throw new Error(res.message || '签发失败');
    issued.value = { system: row.system, token: res.data.token };
    await load();
  } catch (e) {
    ElMessage({ type: 'error', message: e instanceof Error ? e.message : '签发失败', duration: 0, showClose: true });
  } finally {
    saving.value = false;
    emit('writing-change', false);
  }
}

watch(() => props.factoryId, () => { issued.value = null; load(); }, { immediate: true });
</script>

<template>
  <div class="panel" data-testid="factory-integrations-panel">
    <p class="muted">外部软件接口授权只由平台维护。授权后，该工厂才出现外部对照列与「外部软件对照」页；数据由本机采集每天推一份只读快照，不回写外部系统。</p>
    <UAlert v-if="loadError" color="error" variant="subtle" :title="loadError" class="mb-3" />
    <section v-for="row in rows" :key="row.system" class="cs-card">
      <header class="cs-card-head">
        <span>{{ row.label }}</span>
        <UBadge :color="row.status === 'ACTIVE' ? 'success' : row.status === 'SUSPENDED' ? 'warning' : 'neutral'" variant="soft">{{ STATUS_LABEL[row.status] }}</UBadge>
        <span v-if="row.authorizedAt" class="muted">授权时间 {{ row.authorizedAt }}（授权人 #{{ row.authorizedBy }}）</span>
      </header>
      <div class="body">
        <label>来源组织 id（外部系统里的工厂 id）<UInput v-model="forms[row.system].sourceOrgId" :disabled="saving" :aria-label="`${row.label} 来源组织 id`" /></label>
        <label>备注<UInput v-model="forms[row.system].note" :disabled="saving" :aria-label="`${row.label} 备注`" /></label>
        <p class="muted">
          推送凭证：{{ row.hasPushToken ? `已签发（${row.pushTokenIssuedAt}）` : '未签发' }}；
          外部数据时点：{{ row.lastCollectedAt ? `${row.lastCollectedAt}（北京时间）${row.stale ? '，超过 36 小时未更新' : ''}` : '还没收到' }}
        </p>
        <div class="actions">
          <UButton :loading="saving" @click="save(row, 'ACTIVE')">{{ row.status === 'ACTIVE' ? '保存' : '授权' }}</UButton>
          <UButton v-if="row.status === 'ACTIVE'" color="neutral" variant="outline" :disabled="saving" @click="save(row, 'SUSPENDED')">暂停</UButton>
          <UButton v-if="row.status !== 'UNSET'" color="neutral" variant="outline" :disabled="saving" @click="issue(row)">
            {{ row.hasPushToken ? '重新签发推送凭证' : '签发推送凭证' }}
          </UButton>
        </div>
        <UAlert v-if="issued?.system === row.system" color="warning" variant="subtle" title="推送凭证（只显示这一次）"
                :description="issued.token" data-testid="issued-token" />
      </div>
    </section>
    <p v-if="loading" class="muted">读取中…</p>
  </div>
</template>

<style scoped>
.panel { display: flex; flex-direction: column; gap: var(--cs-space-3); }
.body { padding: var(--cs-space-3) var(--cs-space-5); display: flex; flex-direction: column; gap: var(--cs-space-2); }
.body label { display: flex; flex-direction: column; gap: var(--cs-space-1); color: var(--cs-text); }
.actions { display: flex; gap: var(--cs-space-2); flex-wrap: wrap; }
.muted { color: var(--cs-muted); margin: 0; }
</style>
