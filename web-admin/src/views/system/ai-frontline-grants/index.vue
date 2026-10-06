<script setup lang="ts">
/**
 * 一线角色 AI 操作权限
 *
 * owner 2026-10-06: 一线角色在 App AI 助手里只能执行「已对其开放」的写操作。
 * 默认开放的一组 (日常工作) 定义在后端代码里, 这里只读展示、不能关;
 * 其余由工厂管理员在这里逐个开放 / 关闭。查询类操作不在这里, 也不受影响。
 *
 * 编辑权限沿用「功能模块配置」页的判据: permissionStore.canWrite('system')。
 * 后端 PUT/DELETE 另外只认 factory_super_admin / permission_admin ——
 * 前端判据比后端宽的那部分角色会拿到 403, 由失败回退 + 提示兜住。
 */
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { useAuthStore } from '@/store/modules/auth';
import { usePermissionStore } from '@/store/modules/permission';
import {
  getFrontlineGrants,
  grantFrontlineIntent,
  revokeFrontlineIntent,
} from '@/api/aiFrontlineGrants';
import type {
  FrontlineGrantIntent,
  FrontlineRole,
  FrontlineSensitivityLevel,
} from '@/api/aiFrontlineGrants';
import { ApiError } from '@/types/api';

const authStore = useAuthStore();
const permissionStore = usePermissionStore();
const factoryId = computed(() => authStore.factoryId);
const canWrite = computed(() => permissionStore.canWrite('system'));

const loading = ref(false);
const loadError = ref('');
const roles = ref<FrontlineRole[]>([]);
const intents = ref<FrontlineGrantIntent[]>([]);

const keyword = ref('');
const onlyOpened = ref(false);

/** 正在提交的格子: key → 用户刚拨到的值 (请求期间先按它显示, 失败即删掉 = 回退)。 */
const pending = reactive(new Map<string, boolean>());

const HIGH_SENSITIVITY: ReadonlySet<FrontlineSensitivityLevel> = new Set(['HIGH', 'CRITICAL']);

const sensitivityMeta: Record<FrontlineSensitivityLevel, { label: string; type: 'success' | 'warning' | 'danger' }> = {
  LOW: { label: '低敏感', type: 'success' },
  MEDIUM: { label: '中敏感', type: 'warning' },
  HIGH: { label: '高敏感', type: 'danger' },
  CRITICAL: { label: '极高敏感', type: 'danger' },
};

function cellKey(intentCode: string, roleCode: string): string {
  return `${intentCode}::${roleCode}`;
}

function isDefaultOpen(intent: FrontlineGrantIntent, roleCode: string): boolean {
  return intent.defaultOpenRoles.includes(roleCode);
}

function isGranted(intent: FrontlineGrantIntent, roleCode: string): boolean {
  return intent.grantedRoles.includes(roleCode);
}

/** 格子当前应显示的开关值: 默认开放恒为开; 提交中按用户刚拨到的值; 否则按后端返回。 */
function cellOn(intent: FrontlineGrantIntent, roleCode: string): boolean {
  if (isDefaultOpen(intent, roleCode)) return true;
  const key = cellKey(intent.intentCode, roleCode);
  const optimistic = pending.get(key);
  if (optimistic !== undefined) return optimistic;
  return isGranted(intent, roleCode);
}

function isPending(intent: FrontlineGrantIntent, roleCode: string): boolean {
  return pending.has(cellKey(intent.intentCode, roleCode));
}

function sensitivityLabel(level: FrontlineSensitivityLevel): string {
  return sensitivityMeta[level]?.label ?? level;
}

function sensitivityType(level: FrontlineSensitivityLevel): 'success' | 'warning' | 'danger' | 'info' {
  return sensitivityMeta[level]?.type ?? 'info';
}

const filteredIntents = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return intents.value.filter((intent) => {
    if (kw) {
      const hit = intent.intentName.toLowerCase().includes(kw)
        || intent.intentCode.toLowerCase().includes(kw);
      if (!hit) return false;
    }
    if (onlyOpened.value) {
      // 「已开放」= 至少对一个一线角色是开着的 (含默认开放)
      return roles.value.some((role) => cellOn(intent, role.code));
    }
    return true;
  });
});

onMounted(() => {
  void loadData();
});

async function loadData() {
  if (!factoryId.value) {
    loadError.value = '当前账号没有关联工厂，无法查看一线角色的 AI 操作权限';
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const response = await getFrontlineGrants(factoryId.value);
    if (response.success && response.data) {
      roles.value = response.data.roles;
      intents.value = response.data.intents;
    } else {
      loadError.value = response.message || '加载失败';
    }
  } catch (error) {
    loadError.value = error instanceof Error && error.message ? error.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function replaceIntent(updated: FrontlineGrantIntent) {
  const idx = intents.value.findIndex((it) => it.intentCode === updated.intentCode);
  if (idx !== -1) intents.value.splice(idx, 1, updated);
}

function failureText(error: unknown): string {
  if (error instanceof ApiError && error.status === 403) {
    return '只有工厂超级管理员或权限管理员可以修改这里的设置';
  }
  if (error instanceof Error && error.message) return error.message;
  return '请稍后重试';
}

async function handleToggle(intent: FrontlineGrantIntent, role: FrontlineRole, next: boolean) {
  if (!factoryId.value || !canWrite.value) return;
  if (isDefaultOpen(intent, role.code)) return; // 默认开放不能关, UI 已禁用, 这里再守一道
  const key = cellKey(intent.intentCode, role.code);
  if (pending.has(key)) return;

  if (next && HIGH_SENSITIVITY.has(intent.sensitivityLevel)) {
    try {
      await ElMessageBox.confirm(
        `这是高敏感操作（例如放行、销毁、调拨），确定对「${role.name}」开放吗？`,
        `开放「${intent.intentName}」`,
        { confirmButtonText: '确定开放', cancelButtonText: '取消', type: 'warning' },
      );
    } catch {
      return; // 取消: 开关保持原样
    }
  }

  pending.set(key, next);
  const verb = next ? '开放' : '关闭';
  try {
    const response = next
      ? await grantFrontlineIntent(factoryId.value, role.code, intent.intentCode)
      : await revokeFrontlineIntent(factoryId.value, role.code, intent.intentCode);
    if (response.success && response.data) {
      replaceIntent(response.data);
      ElMessage.success(`已对「${role.name}」${verb}「${intent.intentName}」`);
    } else {
      ElMessage.error(`${verb}失败：${response.message || '请稍后重试'}`);
    }
  } catch (error) {
    ElMessage.error(`${verb}失败：${failureText(error)}`);
  } finally {
    // 成功时行已按后端返回替换; 失败时删掉乐观值 = 开关回到原状态
    pending.delete(key);
  }
}
</script>

<template>
  <div class="page-container">
    <el-card class="header-card">
      <div class="header-row">
        <div>
          <h2 class="page-title">一线角色 AI 操作权限</h2>
          <p class="page-desc">
            一线角色在 App 的 AI 助手里「查询」不受限制；「会改数据的操作」默认只开放日常工作，其余需要在这里逐个开放。开放后，员工在 AI 里办理时仍会先看到确认卡，点确认才执行。
          </p>
        </div>
        <el-tag type="info" size="large">工厂: {{ factoryId || '—' }}</el-tag>
      </div>
      <el-alert
        v-if="!canWrite"
        class="readonly-alert"
        type="info"
        :closable="false"
        show-icon
        title="你当前只能查看。如需开放或关闭，请联系工厂超级管理员或权限管理员。"
      />
    </el-card>

    <el-card>
      <div class="toolbar">
        <el-input
          v-model="keyword"
          class="search-input"
          clearable
          placeholder="搜索操作名称或编码"
        />
        <el-checkbox v-model="onlyOpened" class="only-opened">只看已开放</el-checkbox>
        <span class="toolbar-hint">标「默认」的格子是日常工作，固定开放，不能在这里关闭。</span>
      </div>

      <el-alert
        v-if="loadError"
        class="load-error"
        type="error"
        :closable="false"
        show-icon
        :title="`加载失败：${loadError}`"
      >
        <el-button size="small" @click="loadData">重新加载</el-button>
      </el-alert>

      <el-table
        v-else
        v-loading="loading"
        :data="filteredIntents"
        row-key="intentCode"
        border
        class="grant-table"
        empty-text="没有符合条件的操作"
      >
        <el-table-column label="操作" min-width="260">
          <template #default="{ row }">
            <div class="intent-cell" :data-intent="row.intentCode">
              <div class="intent-title">
                <span class="intent-name">{{ row.intentName }}</span>
                <el-tag
                  size="small"
                  :type="sensitivityType(row.sensitivityLevel)"
                  :effect="row.sensitivityLevel === 'CRITICAL' ? 'dark' : 'light'"
                >
                  {{ sensitivityLabel(row.sensitivityLevel) }}
                </el-tag>
              </div>
              <div v-if="row.description" class="intent-desc">{{ row.description }}</div>
              <div class="intent-code">{{ row.intentCode }}</div>
            </div>
          </template>
        </el-table-column>

        <el-table-column
          v-for="role in roles"
          :key="role.code"
          :label="role.name"
          align="center"
          width="130"
        >
          <template #default="{ row }">
            <div class="grant-cell" :data-cell="`${row.intentCode}::${role.code}`">
              <template v-if="isDefaultOpen(row, role.code)">
                <el-tooltip content="日常工作，默认开放，不能关闭" placement="top">
                  <span class="default-cell">
                    <el-switch :model-value="true" disabled data-state="default" />
                    <el-tag size="small" type="info" class="default-tag">默认</el-tag>
                  </span>
                </el-tooltip>
              </template>
              <el-switch
                v-else
                :model-value="cellOn(row, role.code)"
                :disabled="!canWrite"
                :loading="isPending(row, role.code)"
                :data-state="cellOn(row, role.code) ? 'on' : 'off'"
                @update:model-value="(v: string | number | boolean) => handleToggle(row, role, v === true)"
              />
            </div>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<style lang="scss" scoped>
.page-container {
  padding: 20px;
}

.header-card {
  margin-bottom: 20px;
}

.header-row {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}

.page-title {
  margin: 0 0 4px 0;
  font-size: 20px;
  color: var(--el-text-color-primary);
}

.page-desc {
  margin: 0;
  max-width: 760px;
  color: var(--el-text-color-secondary);
  font-size: 14px;
  line-height: 1.6;
}

.readonly-alert {
  margin-top: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 16px;
  margin-bottom: 16px;
}

.search-input {
  width: 260px;
}

.toolbar-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.load-error {
  margin-bottom: 16px;
}

.intent-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.intent-title {
  display: flex;
  align-items: center;
  gap: 8px;
}

.intent-name {
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.intent-desc {
  font-size: 12px;
  color: var(--el-text-color-regular);
  line-height: 1.5;
}

.intent-code {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  font-family: var(--el-font-family-mono, monospace);
}

.grant-cell {
  display: flex;
  justify-content: center;
}

.default-cell {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
</style>
