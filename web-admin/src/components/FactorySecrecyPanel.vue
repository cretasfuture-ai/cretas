<script setup lang="ts">
/**
 * 平台「工厂管理 → 保密设置」: 每项保密一个开关 (Steve 2026-11-03)。
 * 文字 (名称 / 关闭后会怎样 / 二次确认提示) 都来自 secrecy-items.json (后端枚举生成), 不在这里另写一份。
 * 理由、确认的规则与后端相同, 后端照样再校验 (缺理由 / 关 e 没确认一律 400)。
 */
import { computed, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import {
  SECRECY_ITEMS,
  bulkPlatformFactorySecrecy,
  getPlatformFactorySecrecy,
  secrecyItemMeta,
  updatePlatformFactorySecrecy,
  type FactorySecrecySettings,
  type SecrecyCode,
  type SecrecyItemState,
  type SecrecyUpdate,
  type StoredSecrecyValue,
} from '@/api/factorySecrecy';

const props = defineProps<{ factoryId: string }>();
const emit = defineEmits<{ (e: 'writing-change', writing: boolean): void }>();

const settings = ref<FactorySecrecySettings | null>(null);
const loading = ref(false);
const saving = ref<string | null>(null);
const reason = ref('');
const errorMessage = ref('');
let loadSequence = 0;

const stateByCode = computed<Record<string, SecrecyItemState>>(() => {
  const map: Record<string, SecrecyItemState> = {};
  for (const item of settings.value?.items || []) map[item.code] = item;
  return map;
});

const labelByCode = computed<Record<string, string>>(() =>
  Object.fromEntries(SECRECY_ITEMS.map((item) => [item.code, item.label])));

const VALUE_TEXT: Record<StoredSecrecyValue, string> = {
  UNSET: '默认', ON: '开', OFF: '关', FOLLOW: '跟随工艺保密',
};

function valueText(value: StoredSecrecyValue): string {
  return VALUE_TEXT[value] || value;
}

function auditTarget(code: SecrecyCode): string {
  return labelByCode.value[code] || code;
}

async function load(factoryId: string): Promise<void> {
  const sequence = ++loadSequence;
  settings.value = null;
  errorMessage.value = '';
  if (!factoryId) return;
  loading.value = true;
  try {
    const response = await getPlatformFactorySecrecy(factoryId);
    if (!response.success || !response.data) throw new Error(response.message || '保密设置加载失败');
    if (response.data.factoryId !== factoryId) throw new Error('保密设置范围不匹配，已停止加载');
    if (sequence === loadSequence) settings.value = response.data;
  } catch (error) {
    if (sequence === loadSequence) errorMessage.value = error instanceof Error ? error.message : '保密设置加载失败';
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
}

watch(() => props.factoryId, (factoryId) => { void load(factoryId); }, { immediate: true });

/** 关闭「客户侧保管」之类要二次确认的项: 弹窗确认, 取消返回 false。 */
async function confirmOff(code: SecrecyCode): Promise<boolean> {
  const meta = secrecyItemMeta(code);
  if (!meta.offRequiresConfirmation) return true;
  try {
    await ElMessageBox.confirm(
      `${meta.offConfirmWarning}。${meta.offEffect}`,
      `确认关闭「${meta.label}」？`,
      { type: 'warning', confirmButtonText: '确认关闭', cancelButtonText: '取消', distinguishCancelAndClose: true },
    );
    return true;
  } catch {
    return false;
  }
}

function reasonMissing(code: SecrecyCode): boolean {
  return secrecyItemMeta(code).requiresReason && !reason.value.trim();
}

async function write(code: SecrecyCode, body: SecrecyUpdate): Promise<void> {
  const factoryId = props.factoryId;
  saving.value = code;
  emit('writing-change', true);
  try {
    const response = await updatePlatformFactorySecrecy(factoryId, code, body);
    if (!response.success) throw new Error(response.message || '保存失败');
    ElMessage.success(`「${secrecyItemMeta(code).label}」已保存`);
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = null;
    emit('writing-change', false);
    if (props.factoryId === factoryId) await load(factoryId);
  }
}

async function onToggle(code: SecrecyCode, enabled: boolean): Promise<void> {
  if (reasonMissing(code)) {
    ElMessage.warning(`修改「${secrecyItemMeta(code).label}」请先在上方填写理由`);
    return;
  }
  let confirmed = false;
  if (!enabled) {
    confirmed = await confirmOff(code);
    if (!confirmed) return;
  }
  await write(code, { enabled, reason: reason.value.trim() || undefined, confirmed });
}

async function onAuditMode(mode: 'ON' | 'OFF' | 'FOLLOW'): Promise<void> {
  const body: SecrecyUpdate = mode === 'FOLLOW'
    ? { followProcessSecret: true, reason: reason.value.trim() || undefined }
    : { enabled: mode === 'ON', reason: reason.value.trim() || undefined };
  await write('AI_SECRECY_AUDIT', body);
}

async function onBulk(enabled: boolean): Promise<void> {
  if (!reason.value.trim()) {
    ElMessage.warning('一键全开 / 全关会改动需要理由的项，请先在上方填写理由');
    return;
  }
  const customerHeldOn = stateByCode.value.CUSTOMER_HELD?.effective === true;
  let confirmed = false;
  try {
    await ElMessageBox.confirm(
      enabled
        ? '将逐项开启全部保密（AI 保密审计设为跟随工艺保密），每一项单独记入改动记录。'
        : '将逐项关闭全部保密，每一项单独记入改动记录。',
      enabled ? '一键全开' : '一键全关',
      { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' },
    );
  } catch {
    return;
  }
  if (!enabled && customerHeldOn) {
    confirmed = await confirmOff('CUSTOMER_HELD');
    if (!confirmed) return;
  }
  const factoryId = props.factoryId;
  saving.value = 'bulk';
  emit('writing-change', true);
  try {
    const response = await bulkPlatformFactorySecrecy(factoryId, { enabled, reason: reason.value.trim(), confirmed });
    if (!response.success) throw new Error(response.message || '保存失败');
    ElMessage.success(enabled ? '已逐项开启' : '已逐项关闭');
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '保存失败');
  } finally {
    saving.value = null;
    emit('writing-change', false);
    if (props.factoryId === factoryId) await load(factoryId);
  }
}

function auditMode(state: SecrecyItemState | undefined): 'ON' | 'OFF' | 'FOLLOW' {
  if (!state || state.stored === 'UNSET' || state.stored === 'FOLLOW') return 'FOLLOW';
  return state.stored;
}
</script>

<template>
  <div class="secrecy-panel">
    <el-skeleton v-if="loading" :rows="8" animated />
    <el-empty v-else-if="!factoryId" description="请选择左侧工厂" />
    <el-alert v-else-if="errorMessage" :title="errorMessage" type="error" show-icon :closable="false" />
    <template v-else-if="settings">
      <p class="panel-intro">
        每一项都可以按工厂单独开关，新工厂默认全部关闭。改动立即生效，每一次改动都会记录下来。
      </p>
      <div class="reason-row">
        <el-input
          v-model="reason"
          maxlength="500"
          show-word-limit
          clearable
          placeholder="修改理由（改「工艺保密」「平台看不到配方」「出外脱敏」「客户侧保管」时必须填写）"
          data-testid="secrecy-reason"
        />
        <div class="bulk-buttons">
          <el-button :disabled="!!saving" :loading="saving === 'bulk'" @click="onBulk(true)">一键全开</el-button>
          <el-button :disabled="!!saving" :loading="saving === 'bulk'" @click="onBulk(false)">一键全关</el-button>
        </div>
      </div>

      <div class="item-list">
        <section v-for="item in SECRECY_ITEMS" :key="item.code" class="item-card" :data-testid="`secrecy-item-${item.code}`">
          <div class="item-heading">
            <div>
              <h3>{{ item.label }}</h3>
              <el-tag v-if="item.effectiveOnlyWhen && stateByCode[item.code]?.effective && !stateByCode[item.code]?.inEffect"
                      type="info" size="small">
                现在不起作用：{{ labelByCode[item.effectiveOnlyWhen] }}关着
              </el-tag>
            </div>
            <el-radio-group
              v-if="item.triState"
              :model-value="auditMode(stateByCode[item.code])"
              :disabled="!!saving"
              size="small"
              @change="(value: string | number | boolean | undefined) => onAuditMode(value as 'ON' | 'OFF' | 'FOLLOW')"
            >
              <el-radio-button value="FOLLOW">跟随工艺保密</el-radio-button>
              <el-radio-button value="ON">开</el-radio-button>
              <el-radio-button value="OFF">关</el-radio-button>
            </el-radio-group>
            <el-switch
              v-else
              :model-value="stateByCode[item.code]?.effective === true"
              :disabled="!!saving"
              :loading="saving === item.code"
              :aria-label="item.label"
              @change="(value: string | number | boolean) => onToggle(item.code, value === true)"
            />
          </div>
          <p class="item-effect">{{ item.offEffect }}</p>
          <p v-if="item.requiresReason" class="item-rule">修改需要填写理由<span v-if="item.offRequiresConfirmation">；关闭前需要再次确认</span></p>
        </section>
      </div>

      <h4 class="audit-title">最近改动</h4>
      <el-empty v-if="!settings.recentAudit.length" description="还没有改动记录" :image-size="60" />
      <el-table v-else :data="settings.recentAudit" size="small" data-testid="secrecy-audit">
        <el-table-column label="时间" prop="changedAt" min-width="150" />
        <el-table-column label="项目" min-width="140">
          <template #default="{ row }">{{ auditTarget(row.itemCode) }}</template>
        </el-table-column>
        <el-table-column label="改动" min-width="130">
          <template #default="{ row }">{{ valueText(row.oldValue) }} → {{ valueText(row.newValue) }}</template>
        </el-table-column>
        <el-table-column label="理由" prop="reason" min-width="160" />
        <el-table-column label="操作人" min-width="110">
          <template #default="{ row }">{{ row.actorRole }} #{{ row.actorId }}</template>
        </el-table-column>
        <el-table-column label="方式" min-width="90">
          <template #default="{ row }">{{ row.bulk ? '一键' : '单项' }}<span v-if="row.confirmed">（已确认）</span></template>
        </el-table-column>
      </el-table>
    </template>
  </div>
</template>

<style lang="scss" scoped>
.secrecy-panel { display: flex; flex-direction: column; gap: 12px; }
.panel-intro { margin: 0; color: #4A5568; font-size: 13px; }
.reason-row { display: flex; gap: 12px; align-items: flex-start; }
.reason-row .el-input { flex: 1; }
.bulk-buttons { display: flex; gap: 8px; }
.item-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(320px, 1fr)); gap: 12px; }
.item-card { border: 1px solid #EDF2F7; border-radius: 10px; padding: 14px; }
.item-heading { display: flex; justify-content: space-between; align-items: flex-start; gap: 10px; }
.item-heading h3 { margin: 0 0 4px; font-size: 16px; }
.item-effect { margin: 8px 0 0; color: #4A5568; font-size: 13px; line-height: 1.6; }
.item-rule { margin: 6px 0 0; color: #7A8599; font-size: 12px; }
.audit-title { margin: 8px 0 0; font-size: 15px; }
@media (max-width: 900px) { .reason-row { flex-direction: column; } }
</style>
